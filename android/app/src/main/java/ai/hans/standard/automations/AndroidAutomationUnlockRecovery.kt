package ai.hans.standard.automations

import android.app.KeyguardManager
import android.content.Context
import android.os.SystemClock
import ai.hans.standard.phone.accessibility.resume.AndroidUiTaskContinuationRuntime

/**
 * Root-free, event-driven unlock reconciliation.
 *
 * ACTION_USER_PRESENT covers a living process. The launcher lifecycle and the user-enabled
 * AccessibilityService are additional public-API signals that can cold-start Hans or survive while
 * another app is in front. Android does not expose a manifest-safe unlock broadcast to target 36,
 * so the persisted retry remains the final eventual-delivery backstop when neither surface exists.
 */
internal object AndroidAutomationUnlockRecovery {
    private const val DISPATCH_DEBOUNCE_MILLIS = 1_500L
    private val accessibilityTransitions = AutomationUnlockTransitionGate()
    private val dispatchDebouncer = AutomationUnlockDispatchDebouncer(
        elapsedRealtime = SystemClock::elapsedRealtime,
        debounceMillis = DISPATCH_DEBOUNCE_MILLIS,
    )

    fun onProtectedUserPresent(context: Context): Boolean =
        dispatchIfUnlocked(context)

    fun onLauncherResumed(context: Context): Boolean =
        dispatchIfUnlocked(context)

    fun onAccessibilityServiceConnected(context: Context): Boolean {
        val unlocked = isDeviceUnlocked(context)
        return if (accessibilityTransitions.onServiceConnected(unlocked)) {
            dispatchIfUnlocked(context)
        } else {
            false
        }
    }

    fun onAccessibilityEvent(context: Context): Boolean {
        val unlocked = isDeviceUnlocked(context)
        return if (accessibilityTransitions.onPotentialStateChange(unlocked)) {
            dispatchIfUnlocked(context)
        } else {
            false
        }
    }

    private fun dispatchIfUnlocked(context: Context): Boolean {
        if (!isDeviceUnlocked(context)) return false
        // UI continuation reconciliation has its own durable claim gate and never polls. Phase B
        // currently records this event without dispatching or replaying an old Android action.
        AndroidUiTaskContinuationRuntime.onUserPresent(context)
        return dispatchDebouncer.attempt {
            AndroidAutomationDispatch.enqueueOrRetry(
                context,
                AutomationRuntimeTrigger.UserPresent,
            )
        }
    }

    private fun isDeviceUnlocked(context: Context): Boolean =
        runCatching {
            val keyguard = context.getSystemService(KeyguardManager::class.java)
                ?: return@runCatching false
            AndroidKeyguardUnlockPolicy.isUiUnlocked(
                deviceLocked = keyguard.isDeviceLocked,
                keyguardLocked = keyguard.isKeyguardLocked,
            )
        }.getOrDefault(false)
}

/**
 * Shared public-API interpretation for every automation unlock probe.
 *
 * Android deliberately reports [KeyguardManager.isDeviceLocked] as false for a swipe-only
 * keyguard. That still hides the target application behind the keyguard, so visual/semantic UI
 * work must wait until both platform signals are false.
 */
internal object AndroidKeyguardUnlockPolicy {
    fun isUiUnlocked(
        deviceLocked: Boolean,
        keyguardLocked: Boolean,
    ): Boolean = !deviceLocked && !keyguardLocked
}

/** Coalesces duplicate public-API signals but never hides a rejected durable handoff. */
internal class AutomationUnlockDispatchDebouncer(
    private val elapsedRealtime: () -> Long,
    private val debounceMillis: Long,
) {
    init {
        require(debounceMillis >= 0)
    }

    private var lastAcceptedAt = Long.MIN_VALUE

    @Synchronized
    fun attempt(dispatch: () -> Boolean): Boolean {
        val now = elapsedRealtime()
        val sinceAccepted = now - lastAcceptedAt
        if (lastAcceptedAt != Long.MIN_VALUE && sinceAccepted in 0 until debounceMillis) {
            return true
        }
        val accepted = runCatching(dispatch).getOrDefault(false)
        if (accepted) lastAcceptedAt = now
        return accepted
    }
}

/** Pure edge detector kept separate so lock/unlock behavior has a local regression test. */
internal class AutomationUnlockTransitionGate {
    private var lastUnlocked: Boolean? = null

    @Synchronized
    fun onServiceConnected(unlocked: Boolean): Boolean {
        lastUnlocked = unlocked
        // A service connection while already unlocked is itself a cold-process reconciliation
        // opportunity; the durable work ledger keeps the resulting cycle idempotent.
        return unlocked
    }

    @Synchronized
    fun onPotentialStateChange(unlocked: Boolean): Boolean {
        val shouldDispatch = unlocked && lastUnlocked == false
        lastUnlocked = unlocked
        return shouldDispatch
    }
}
