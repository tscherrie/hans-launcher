package ai.hans.standard.ui

import ai.hans.standard.phone.display.AndroidDeviceIdentity
import ai.hans.standard.phone.display.DisplayMotionDecision
import ai.hans.standard.phone.display.DisplayMotionMode
import ai.hans.standard.phone.display.DisplayMotionPolicy
import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Narrow event-driven seam; test fixtures never need to change global Android settings. */
internal interface SystemAnimationSource {
    fun animationsEnabled(): Boolean
    fun observe(onChanged: () -> Unit): AutoCloseable
}

internal val LocalSystemAnimationSource = staticCompositionLocalOf<SystemAnimationSource?> { null }

internal fun currentDisplayDeviceIdentity() = AndroidDeviceIdentity(
    manufacturer = Build.MANUFACTURER,
    brand = Build.BRAND,
    model = Build.MODEL,
    device = Build.DEVICE,
)

/** No timer, frame callback or polling: only settings, lifecycle and window-focus events. */
@Composable
internal fun rememberDisplayMotion(mode: DisplayMotionMode): DisplayMotionDecision {
    val context = LocalContext.current.applicationContext
    val source = LocalSystemAnimationSource.current ?: remember(context) {
        AndroidSystemAnimationSource(context)
    }
    val identity = remember { currentDisplayDeviceIdentity() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var animationsEnabled by remember(source) { mutableStateOf(source.animationsEnabled()) }
    var observing by remember(source) { mutableStateOf(false) }
    var resumed by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }

    DisposableEffect(source, lifecycle) {
        val subscription = runCatching {
            source.observe { animationsEnabled = source.animationsEnabled() }
        }.getOrNull()
        // If Android declines an observer, keep the UI usable and static rather than retaining
        // an unobservable animation preference. A new screen entry will try registration again.
        observing = subscription != null
        val lifecycleObserver = LifecycleEventObserver { _, _ ->
            resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (resumed) animationsEnabled = source.animationsEnabled()
        }
        lifecycle.addObserver(lifecycleObserver)
        animationsEnabled = source.animationsEnabled()
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
            runCatching { subscription?.close() }
        }
    }
    DisposableEffect(source, windowFocused) {
        // The first real window synchronizes Android's animator state. Re-read on focus gain,
        // including a return from Settings, rather than trusting a pre-window process default.
        if (windowFocused) animationsEnabled = source.animationsEnabled()
        onDispose { }
    }

    return DisplayMotionPolicy.decide(mode, identity, animationsEnabled && observing, resumed, windowFocused)
}

internal class AndroidSystemAnimationSource(context: Context) : SystemAnimationSource {
    private val context = context.applicationContext
    private val resolver = this.context.contentResolver
    private val powerManager = this.context.getSystemService(PowerManager::class.java)

    override fun animationsEnabled(): Boolean = runCatching {
        // An absent setting means Android's normal 1x default, not animations-off.
        val configuredScale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val configuredEnabled = configuredScale.isFinite() && configuredScale > 0f
        // API 31/32 expose no duration-scale listener. Read the public setting on its observer
        // event instead of the process-cached ValueAnimator value, which can still be stale then.
        configuredEnabled && powerManager?.isPowerSaveMode != true &&
            (Build.VERSION.SDK_INT < 33 || ValueAnimator.areAnimatorsEnabled())
    }.getOrDefault(false)

    override fun observe(onChanged: () -> Unit): AutoCloseable {
        var closed = false
        val publishChange = { if (!closed) onChanged() }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { publishChange() }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { publishChange() }
        }
        val subscriptions = try {
            registerDisplayMotionObservers(
                {
                    resolver.registerContentObserver(
                        Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
                        false,
                        observer,
                    )
                    AutoCloseable { resolver.unregisterContentObserver(observer) }
                },
                {
                    ContextCompat.registerReceiver(
                        context,
                        receiver,
                        IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                    AutoCloseable { context.unregisterReceiver(receiver) }
                },
                {
                    if (Build.VERSION.SDK_INT >= 33) Api33.observe(publishChange) else AutoCloseable { }
                },
            )
        } catch (failure: Exception) {
            closed = true
            throw failure
        }
        return AutoCloseable {
            if (!closed) {
                closed = true
                subscriptions.close()
            }
        }
    }

    @RequiresApi(33)
    private object Api33 {
        fun observe(onChanged: () -> Unit): AutoCloseable {
            val listener = ValueAnimator.DurationScaleChangeListener { onChanged() }
            check(ValueAnimator.registerDurationScaleChangeListener(listener)) {
                "Android declined the animation-scale observer"
            }
            return AutoCloseable { ValueAnimator.unregisterDurationScaleChangeListener(listener) }
        }
    }
}
