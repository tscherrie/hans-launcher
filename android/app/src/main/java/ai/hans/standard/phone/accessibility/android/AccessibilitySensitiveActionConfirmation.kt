package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.R
import ai.hans.standard.phone.accessibility.AccessibilityCommand
import ai.hans.standard.phone.accessibility.AccessibilityConfirmationRequest
import ai.hans.standard.phone.accessibility.AccessibilityConfirmationRisk
import ai.hans.standard.phone.accessibility.AccessibilityIdempotencyKey
import ai.hans.standard.phone.accessibility.AccessibilityUserApproval
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Fixed action categories shown by the trusted Accessibility overlay. */
internal enum class AccessibilitySensitiveActionKind {
    CLICK,
    SET_TEXT,
    SCROLL,
    GLOBAL_ACTION,
    COORDINATE_GESTURE,
}

/** Only locally derived, bounded metadata may cross into the confirmation UI. */
internal data class AccessibilitySensitiveActionPrompt(
    val promptId: String,
    val idempotencyKey: AccessibilityIdempotencyKey,
    val commandFingerprint: String,
    val risk: AccessibilityConfirmationRisk,
    val correlation: UiSnapshotCorrelation,
    val serviceGenerationNonce: String,
    val actionKind: AccessibilitySensitiveActionKind,
    val appLabel: String,
    val packageName: String,
) {
    init {
        require(promptId.isSafeConfirmationIdentifier())
        require(commandFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(serviceGenerationNonce.isSafeConfirmationIdentifier())
        require(risk != AccessibilityConfirmationRisk.NONE)
        require(appLabel.isNotBlank() && appLabel.length <= MAX_APP_LABEL_CHARACTERS)
        require(packageName.isNotBlank() && packageName.length <= MAX_PACKAGE_NAME_CHARACTERS)
        require(appLabel == AccessibilityConfirmationTextSanitizer.appLabel(appLabel))
        require(packageName == AccessibilityConfirmationTextSanitizer.packageName(packageName))
    }
}

internal data class AccessibilitySensitiveActionReceipt(
    val promptId: String,
    val idempotencyKey: AccessibilityIdempotencyKey,
    val commandFingerprint: String,
    val risk: AccessibilityConfirmationRisk,
    val correlation: UiSnapshotCorrelation,
    val serviceGenerationNonce: String,
) {
    init {
        require(promptId.isSafeConfirmationIdentifier())
        require(commandFingerprint.matches(Regex("[0-9a-f]{64}")))
        require(serviceGenerationNonce.isSafeConfirmationIdentifier())
        require(risk != AccessibilityConfirmationRisk.NONE)
    }
}

internal sealed interface AccessibilitySensitiveActionOutcome {
    data class Approved(val receipt: AccessibilitySensitiveActionReceipt) :
        AccessibilitySensitiveActionOutcome

    data object Denied : AccessibilitySensitiveActionOutcome
    data object Expired : AccessibilitySensitiveActionOutcome
    data object ContextChanged : AccessibilitySensitiveActionOutcome
    data object Unavailable : AccessibilitySensitiveActionOutcome
}

/** Exact binding from a local overlay receipt into the existing one-shot approval gate. */
internal object AccessibilitySensitiveActionReceiptBinding {
    fun approvalFor(
        prompt: AccessibilitySensitiveActionPrompt,
        outcome: AccessibilitySensitiveActionOutcome,
    ): AccessibilityUserApproval? {
        val receipt = (outcome as? AccessibilitySensitiveActionOutcome.Approved)?.receipt
            ?: return null
        if (
            receipt.promptId != prompt.promptId ||
            receipt.idempotencyKey != prompt.idempotencyKey ||
            receipt.commandFingerprint != prompt.commandFingerprint ||
            receipt.risk != prompt.risk ||
            receipt.correlation != prompt.correlation ||
            receipt.serviceGenerationNonce != prompt.serviceGenerationNonce
        ) {
            return null
        }
        return AccessibilityUserApproval(
            approvalId = "ui:${receipt.promptId}",
            idempotencyKey = receipt.idempotencyKey,
            commandFingerprint = receipt.commandFingerprint,
            risk = receipt.risk,
            correlation = receipt.correlation,
        )
    }
}

/** Removes controls and directional formatting, then hard-bounds trusted package metadata. */
internal object AccessibilityConfirmationTextSanitizer {
    fun appLabel(raw: CharSequence?): String {
        val sanitized = sanitize(raw, MAX_APP_LABEL_CHARACTERS)
        return sanitized.ifBlank { "Android-App" }
    }

    fun packageName(raw: CharSequence?): String = sanitize(raw, MAX_PACKAGE_NAME_CHARACTERS)

    private fun sanitize(raw: CharSequence?, limit: Int): String {
        val output = StringBuilder(limit)
        val iterator = raw?.toString().orEmpty().codePoints().iterator()
        var pendingSpace = false
        while (iterator.hasNext() && output.length < limit) {
            val codePoint = iterator.nextInt()
            val type = Character.getType(codePoint)
            if (
                Character.isISOControl(codePoint) ||
                type == Character.FORMAT.toInt() ||
                type == Character.LINE_SEPARATOR.toInt() ||
                type == Character.PARAGRAPH_SEPARATOR.toInt()
            ) continue
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = output.isNotEmpty()
                continue
            }
            if (pendingSpace && output.length < limit) output.append(' ')
            pendingSpace = false
            if (output.length + Character.charCount(codePoint) <= limit) {
                output.appendCodePoint(codePoint)
            }
        }
        return output.toString()
    }
}

/** Package visibility may hide metadata even while Accessibility can safely identify the app. */
internal object AccessibilityConfirmationAppLabelPolicy {
    fun resolve(loader: () -> CharSequence?): String = try {
        AccessibilityConfirmationTextSanitizer.appLabel(loader())
    } catch (_: Exception) {
        AccessibilityConfirmationTextSanitizer.appLabel(null)
    }
}

/**
 * Full-screen, focusable TYPE_ACCESSIBILITY_OVERLAY confirmation owned by the connected service.
 * It has no exported component and needs no draw-over-other-apps permission.
 */
internal class AndroidAccessibilitySensitiveActionConfirmation(
    private val context: Context,
    private val windowManager: WindowManager,
    private val mainHandler: Handler,
    private val serviceGenerationNonce: String,
    private val contextStillMatches: (AccessibilitySensitiveActionPrompt) -> Boolean,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val active = AtomicReference<ActivePrompt?>()

    fun request(prompt: AccessibilitySensitiveActionPrompt): AccessibilitySensitiveActionOutcome {
        if (
            closed.get() ||
            prompt.serviceGenerationNonce != serviceGenerationNonce ||
            prompt.correlation.windowId.value < 0
        ) {
            return AccessibilitySensitiveActionOutcome.Unavailable
        }
        val pending = ActivePrompt(prompt)
        if (!active.compareAndSet(null, pending)) {
            return AccessibilitySensitiveActionOutcome.Unavailable
        }
        try {
            if (!mainHandler.post { showOnMain(pending) }) {
                pending.complete(AccessibilitySensitiveActionOutcome.Unavailable)
            }
            val completed = try {
                pending.latch.await(PROMPT_WAIT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!completed) pending.complete(AccessibilitySensitiveActionOutcome.Expired)
            return pending.outcome.get() ?: AccessibilitySensitiveActionOutcome.Unavailable
        } finally {
            active.compareAndSet(pending, null)
            mainHandler.removeCallbacks(pending.timeout)
            mainHandler.post { removeViewOnMain(pending) }
        }
    }

    fun hasActivePrompt(): Boolean = active.get() != null

    fun denyFromBack(): Boolean {
        val pending = active.get() ?: return false
        pending.complete(AccessibilitySensitiveActionOutcome.Denied)
        return true
    }

    /** Called event-driven by the service; no idle polling is introduced. */
    fun onAccessibilityContextMayHaveChanged() {
        val pending = active.get() ?: return
        if (!safeContextMatch(pending.prompt)) {
            pending.complete(AccessibilitySensitiveActionOutcome.ContextChanged)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        active.get()?.complete(AccessibilitySensitiveActionOutcome.Unavailable)
    }

    private fun showOnMain(pending: ActivePrompt) {
        if (closed.get() || active.get() !== pending) {
            pending.complete(AccessibilitySensitiveActionOutcome.Unavailable)
            return
        }
        if (!safeContextMatch(pending.prompt)) {
            pending.complete(AccessibilitySensitiveActionOutcome.ContextChanged)
            return
        }
        val root = AccessibilityConfirmationRootView(context) {
            pending.complete(AccessibilitySensitiveActionOutcome.Denied)
        }.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            setBackgroundColor(backgroundColor())
            addView(buildContent(pending), fullScreenContentLayoutParams())
        }
        pending.view = root
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.FILL
            title = "Hans sensitive action confirmation"
        }
        try {
            windowManager.addView(root, params)
            pending.added = true
            root.requestFocus()
            registerScreenOffReceiver(pending)
            mainHandler.postDelayed(pending.timeout, PROMPT_TIMEOUT_MILLIS)
        } catch (_: RuntimeException) {
            pending.complete(AccessibilitySensitiveActionOutcome.Unavailable)
        }
    }

    private fun buildContent(pending: ActivePrompt): View {
        val prompt = pending.prompt
        return buildContent(
            prompt = prompt,
            deny = { pending.complete(AccessibilitySensitiveActionOutcome.Denied) },
            approve = {
                if (!safeContextMatch(prompt)) {
                    pending.complete(AccessibilitySensitiveActionOutcome.ContextChanged)
                } else {
                    pending.complete(
                        AccessibilitySensitiveActionOutcome.Approved(
                            AccessibilitySensitiveActionReceipt(
                                prompt.promptId,
                                prompt.idempotencyKey,
                                prompt.commandFingerprint,
                                prompt.risk,
                                prompt.correlation,
                                prompt.serviceGenerationNonce,
                            ),
                        ),
                    )
                }
            },
        )
    }

    internal fun contentForLayoutTest(prompt: AccessibilitySensitiveActionPrompt): View =
        buildContent(prompt, deny = {}, approve = {})

    private fun buildContent(
        prompt: AccessibilitySensitiveActionPrompt,
        deny: () -> Unit,
        approve: () -> Unit,
    ): View {
        val foreground = foregroundColor()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            addView(
                textView(R.string.accessibility_sensitive_confirmation_title, 24f, foreground).apply {
                    maxLines = 2
                },
            )
            addView(
                ScrollView(context).apply {
                    isFillViewport = true
                    addView(
                        LinearLayout(context).apply {
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER
                            addView(
                                TextView(context).apply {
                                    text = context.getString(
                                        messageResource(prompt.actionKind),
                                        prompt.appLabel,
                                        prompt.packageName,
                                    )
                                    textSize = 18f
                                    setTextColor(foreground)
                                    gravity = Gravity.CENTER
                                    setPadding(0, dp(24), 0, dp(16))
                                },
                            )
                            addView(
                                TextView(context).apply {
                                    setText(confirmationRiskMessageResource(prompt.risk))
                                    textSize = 17f
                                    setTextColor(foreground)
                                    gravity = Gravity.CENTER
                                    setPadding(0, 0, 0, dp(16))
                                },
                            )
                            addView(
                                textView(
                                    R.string.accessibility_sensitive_confirmation_once,
                                    16f,
                                    foreground,
                                ),
                            )
                        },
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f,
                ),
            )
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    setPadding(0, dp(12), 0, 0)
                    addView(
                        button(R.string.accessibility_sensitive_confirmation_deny) { deny() },
                        pinnedButtonLayoutParams(),
                    )
                    addView(
                        button(R.string.accessibility_sensitive_confirmation_allow_once) {
                            approve()
                        },
                        pinnedButtonLayoutParams(),
                    )
                },
            )
        }
    }

    private fun textView(resource: Int, size: Float, color: Int) = TextView(context).apply {
        setText(resource)
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
    }

    private fun button(resource: Int, onClick: () -> Unit) = Button(context).apply {
        setText(resource)
        isAllCaps = false
        filterTouchesWhenObscured = true
        setOnClickListener { onClick() }
        minHeight = dp(52)
    }

    private fun pinnedButtonLayoutParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(4)
        bottomMargin = dp(4)
    }

    private fun registerScreenOffReceiver(pending: ActivePrompt) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                pending.complete(AccessibilitySensitiveActionOutcome.ContextChanged)
            }
        }
        pending.screenOffReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SHUTDOWN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun removeViewOnMain(pending: ActivePrompt) {
        pending.screenOffReceiver?.let { receiver ->
            pending.screenOffReceiver = null
            runCatching { context.unregisterReceiver(receiver) }
        }
        val view = pending.view
        pending.view = null
        if (pending.added && view != null) {
            pending.added = false
            runCatching { windowManager.removeViewImmediate(view) }
        }
    }

    private fun safeContextMatch(prompt: AccessibilitySensitiveActionPrompt): Boolean =
        !closed.get() &&
            prompt.serviceGenerationNonce == serviceGenerationNonce &&
            runCatching { contextStillMatches(prompt) }.getOrDefault(false)

    private fun messageResource(kind: AccessibilitySensitiveActionKind): Int = when (kind) {
        AccessibilitySensitiveActionKind.CLICK -> R.string.accessibility_sensitive_confirmation_click
        AccessibilitySensitiveActionKind.SET_TEXT -> R.string.accessibility_sensitive_confirmation_set_text
        AccessibilitySensitiveActionKind.SCROLL -> R.string.accessibility_sensitive_confirmation_scroll
        AccessibilitySensitiveActionKind.GLOBAL_ACTION -> R.string.accessibility_sensitive_confirmation_global
        AccessibilitySensitiveActionKind.COORDINATE_GESTURE ->
            R.string.accessibility_sensitive_confirmation_gesture
    }

    private fun foregroundColor(): Int = if (
        context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    ) Color.WHITE else Color.BLACK

    private fun backgroundColor(): Int = if (foregroundColor() == Color.WHITE) Color.BLACK else Color.WHITE

    private fun fullScreenContentLayoutParams() = FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
    )

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private inner class ActivePrompt(val prompt: AccessibilitySensitiveActionPrompt) {
        val latch = CountDownLatch(1)
        val outcome = AtomicReference<AccessibilitySensitiveActionOutcome?>()
        val timeout = Runnable { complete(AccessibilitySensitiveActionOutcome.Expired) }
        var view: View? = null
        var added: Boolean = false
        var screenOffReceiver: BroadcastReceiver? = null

        fun complete(value: AccessibilitySensitiveActionOutcome) {
            if (!outcome.compareAndSet(null, value)) return
            mainHandler.removeCallbacks(timeout)
            if (android.os.Looper.myLooper() == mainHandler.looper) {
                removeViewOnMain(this)
            } else {
                mainHandler.post { removeViewOnMain(this) }
            }
            latch.countDown()
        }
    }

    internal class AccessibilityConfirmationRootView(
        context: Context,
        private val deny: () -> Unit,
    ) : FrameLayout(context) {
        private var gainedWindowFocus = false
        private var predictiveBackRegistration: AutoCloseable? = null
        private var predictiveBackInvocationCount = 0

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            predictiveBackRegistration?.close()
            predictiveBackRegistration = AccessibilityPredictiveBackRegistration.register(
                view = this,
                deny = {
                    predictiveBackInvocationCount += 1
                    deny()
                },
            )
        }

        override fun onDetachedFromWindow() {
            predictiveBackRegistration?.close()
            predictiveBackRegistration = null
            super.onDetachedFromWindow()
        }

        internal fun hasPredictiveBackRegistrationForTest(): Boolean =
            predictiveBackRegistration != null

        internal fun predictiveBackInvocationCountForTest(): Int =
            predictiveBackInvocationCount

        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) deny()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            val obscured = event.flags and (
                MotionEvent.FLAG_WINDOW_IS_OBSCURED or
                    MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED
                ) != 0
            if (obscured) {
                deny()
                return true
            }
            super.dispatchTouchEvent(event)
            return true
        }

        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (hasWindowFocus) {
                gainedWindowFocus = true
            } else if (gainedWindowFocus && isAttachedToWindow) {
                deny()
            }
        }
    }

    private companion object {
        const val PROMPT_TIMEOUT_MILLIS = 60_000L
        const val PROMPT_WAIT_MILLIS = PROMPT_TIMEOUT_MILLIS
    }
}

/** Keeps API-33 types outside the API-31 view path while using the public overlay priority. */
private object AccessibilityPredictiveBackRegistration {
    fun register(view: View, deny: () -> Unit): AutoCloseable? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Api33.register(view, deny)
        } else {
            null
        }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33 {
        fun register(view: View, deny: () -> Unit): AutoCloseable? {
            val dispatcher = view.findOnBackInvokedDispatcher() ?: return null
            val callback = OnBackInvokedCallback { deny() }
            dispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                callback,
            )
            return AutoCloseable {
                dispatcher.unregisterOnBackInvokedCallback(callback)
            }
        }
    }
}

internal fun AccessibilityCommand.sensitiveActionKind(): AccessibilitySensitiveActionKind? =
    when (this) {
        is AccessibilityCommand.Find -> null
        is AccessibilityCommand.Click -> AccessibilitySensitiveActionKind.CLICK
        is AccessibilityCommand.SetText -> AccessibilitySensitiveActionKind.SET_TEXT
        is AccessibilityCommand.Scroll -> AccessibilitySensitiveActionKind.SCROLL
        is AccessibilityCommand.Global -> AccessibilitySensitiveActionKind.GLOBAL_ACTION
        is AccessibilityCommand.CoordinateGesture -> AccessibilitySensitiveActionKind.COORDINATE_GESTURE
    }

internal fun newAccessibilityConfirmationIdentifier(prefix: String): String =
    "$prefix:${UUID.randomUUID()}"

/** Fixed, trusted copy only; never interpolate target text or a set-text value. */
internal fun confirmationRiskMessageResource(risk: AccessibilityConfirmationRisk): Int = when (risk) {
    AccessibilityConfirmationRisk.EXTERNAL_COMMUNICATION ->
        R.string.accessibility_sensitive_confirmation_risk_external_communication
    AccessibilityConfirmationRisk.DESTRUCTIVE ->
        R.string.accessibility_sensitive_confirmation_risk_destructive
    AccessibilityConfirmationRisk.CREDENTIAL_UI ->
        R.string.accessibility_sensitive_confirmation_risk_credential
    AccessibilityConfirmationRisk.NONE -> error("non-sensitive action has no confirmation copy")
}

private fun String.isSafeConfirmationIdentifier(): Boolean =
    length in 8..160 && all { it.isLetterOrDigit() || it in "-_.:" }

private const val MAX_APP_LABEL_CHARACTERS = 80
private const val MAX_PACKAGE_NAME_CHARACTERS = 255
