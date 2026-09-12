package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.automations.AndroidAutomationUnlockRecovery
import ai.hans.standard.phone.accessibility.AccessibilityCommandExecutor
import ai.hans.standard.phone.accessibility.AccessibilityCommand
import ai.hans.standard.phone.accessibility.AccessibilityConfirmationRequest
import ai.hans.standard.phone.accessibility.AccessibilityGlobalAction
import ai.hans.standard.phone.accessibility.AccessibilitySessionId
import ai.hans.standard.phone.accessibility.AccessibilitySnapshotId
import ai.hans.standard.phone.accessibility.AccessibilityWindowId
import ai.hans.standard.phone.accessibility.BoundedSemanticUiSnapshotFactory
import ai.hans.standard.phone.accessibility.PolicyAwareAccessibilityConfirmationGate
import ai.hans.standard.phone.accessibility.SemanticNodeHandle
import ai.hans.standard.phone.accessibility.SemanticUiSnapshot
import ai.hans.standard.phone.accessibility.UiBounds
import ai.hans.standard.phone.accessibility.UiCoordinateGesture
import ai.hans.standard.phone.accessibility.UiInteractionAvailability
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import ai.hans.standard.phone.accessibility.UiSnapshotLimits
import ai.hans.standard.phone.accessibility.currentUiInteractionAvailability
import ai.hans.standard.phone.accessibility.userActionRequiredErrorCode
import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import ai.hans.standard.phone.keys.AndroidGlobalActionKeyAccessibilityController
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * User-enabled, root-free semantic app-control service. Android binds through
 * the signature-level BIND_ACCESSIBILITY_SERVICE permission. This component
 * intentionally exposes no custom Binder API. Visual fallback uses Android's declared
 * Accessibility screenshot capability one frame at a time, keeps pixels only in memory and
 * returns a bounded image directly to the requesting App Server tool call.
 *
 * The service is declared exported because AccessibilityManager lives in
 * system_server; the signature permission, not exported=false, is the bind
 * boundary.
 */
internal class HansAccessibilityService : AccessibilityService(), RootFreeAccessibilityHost {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val semanticLimits = UiSnapshotLimits()
    private val snapshotFactory = BoundedSemanticUiSnapshotFactory(semanticLimits)
    private val projector = AndroidSemanticTreeProjector(semanticLimits)
    private val activeWindowResolver = AndroidActiveWindowRootResolver()
    private val displayResolver = AndroidAccessibilityDisplayResolver(
        AndroidDisplayManagerSource(this),
    )
    private val snapshots = RetainedAccessibilityFrameStore(SystemClock::elapsedRealtime)
    private val snapshotDiagnostics = AccessibilitySnapshotDiagnostics()
    private val snapshotMonitor = Object()
    private val nextSnapshotId = AtomicLong(0)
    private val connected = AtomicBoolean(false)
    private val captureScheduled = AtomicBoolean(false)
    private val gestureInFlight = AtomicBoolean(false)
    private val screenshotInFlight = AtomicBoolean(false)
    private val screenshotExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "hans-accessibility-screenshot").apply { isDaemon = true }
    }
    private val confirmationGate = ExpiringOneShotAccessibilityConfirmationGate(
        elapsedRealtimeMillis = SystemClock::elapsedRealtime,
    )
    private val uiAvailability by lazy {
        AndroidUiInteractionAvailabilityProbe(this)
    }
    private var sessionId: AccessibilitySessionId? = null
    private var commandSession: BoundedAccessibilityCommandSession? = null
    private var actionKeyController: AndroidGlobalActionKeyAccessibilityController? = null
    private var connectionOwner: Any? = null
    private var confirmationOverlay: AndroidAccessibilitySensitiveActionConfirmation? = null
    private var serviceGenerationNonce: String? = null

    private val captureRunnable = Runnable {
        captureScheduled.set(false)
        if (connected.get()) {
            runCatching { captureAndPublishOnMain() }.getOrElse {
                clearSnapshot(AccessibilitySnapshotFailure.CAPTURE_FAILED)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        tearDownSession()
        val newConnectionOwner = Any()
        val newSessionId = AccessibilitySessionId(UUID.randomUUID().toString())
        val newServiceGenerationNonce = newAccessibilityConfirmationIdentifier("service")
        connectionOwner = newConnectionOwner
        sessionId = newSessionId
        serviceGenerationNonce = newServiceGenerationNonce
        connected.set(true)
        AndroidAutomationUnlockRecovery.onAccessibilityServiceConnected(this)
        HansAccessibilitySessions.connect(newConnectionOwner)
        confirmationOverlay = createSensitiveActionConfirmation(newServiceGenerationNonce)
        actionKeyController = AndroidGlobalActionKeyAccessibilityController(
            context = this,
            setFrameworkFilteringEnabled = ::setActionKeyFilteringEnabled,
        ).also(AndroidGlobalActionKeyAccessibilityController::connect)
        HansAccessibilityApprovals.publish(confirmationGate)
        runCatching { captureAndPublishOnMain() }.getOrElse {
            clearSnapshot(AccessibilitySnapshotFailure.CAPTURE_FAILED)
        }

        val androidAdapter = RootFreeAndroidAccessibilityAdapter(snapshots, this)
        val domainExecutor = AccessibilityCommandExecutor(
            snapshots = snapshots,
            adapter = androidAdapter,
            confirmationGate = PolicyAwareAccessibilityConfirmationGate(
                delegate = confirmationGate,
                actionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
            ),
            riskPolicy = TrustedAndroidAccessibilityRiskPolicy(packageName),
            dictationGuard = AndroidDictationLifecycleRegistry,
            uiAvailability = uiAvailability,
        )
        BoundedAccessibilityCommandSession(
            sessionId = newSessionId,
            snapshot = snapshots::current,
            refresh = ::refreshSnapshotOnDemand,
            retainForCommands = snapshots::retainCurrentForCommand,
            snapshotFailure = snapshotDiagnostics::latest,
            executor = domainExecutor,
            visualCapture = ::captureVisualSnapshot,
            sensitiveActionApproval = ::requestSensitiveActionApproval,
            receiptSnapshot = { correlation ->
                if (canExposeReceipt(correlation)) snapshots.receiptFrame(correlation)?.snapshot else null
            },
            retainReceiptForCommands = { correlation ->
                canExposeReceipt(correlation) && snapshots.promoteReceiptForCommands(correlation)
            },
            withdrawReceiptForCommands = snapshots::withdrawReceiptPromotion,
        ).also { session ->
            commandSession = session
            HansAccessibilitySessions.publish(newConnectionOwner, session)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!connected.get()) return
        ensureSessionPublished()
        if (event == null) return
        AndroidAutomationUnlockRecovery.onAccessibilityEvent(this)
        confirmationOverlay?.onAccessibilityContextMayHaveChanged()
        // Copy no event/node object. A single one-shot refresh is enough for a
        // burst and avoids polling or an idle rendering loop.
        if (captureScheduled.compareAndSet(false, true)) {
            if (!mainHandler.postDelayed(captureRunnable, EVENT_DEBOUNCE_MILLIS)) {
                captureScheduled.set(false)
            }
        }
    }

    /** Consumes only an exact user-demonstrated dictation mapping. */
    override fun onKeyEvent(event: KeyEvent): Boolean {
        ensureSessionPublished()
        if (AccessibilityConfirmationBackKeyFilter.onKeyEvent(
                event = event,
                confirmationActive = confirmationOverlay?.hasActivePrompt() == true,
                deny = { confirmationOverlay?.denyFromBack() },
            )
        ) {
            return true
        }
        return actionKeyController?.onKeyEvent(event) == true
    }

    /** Accessibility feedback interruption never changes microphone state. */
    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        tearDownSession()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        tearDownSession()
        screenshotExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun performNodeAction(
        handle: SemanticNodeHandle,
        action: AndroidNodeAction,
    ): AndroidHostNodeActionResult = callOnMain {
        val availability = currentUiAvailability()
        if (!availability.isAvailable) {
            AndroidHostNodeActionResult(availability.toAndroidHostActionStatus())
        } else {
            performNodeActionOnMain(handle, action)
        }
    } ?: AndroidHostNodeActionResult(AndroidHostActionStatus.FAILED)

    override fun performGlobalAction(action: AccessibilityGlobalAction): AndroidHostActionStatus =
        callOnMain {
            val availability = currentUiAvailability()
            if (!availability.isAvailable) {
                return@callOnMain availability.toAndroidHostActionStatus()
            }
            val androidAction = when (action) {
                AccessibilityGlobalAction.BACK -> GLOBAL_ACTION_BACK
                AccessibilityGlobalAction.HOME -> GLOBAL_ACTION_HOME
                AccessibilityGlobalAction.RECENTS -> GLOBAL_ACTION_RECENTS
            }
            if (systemActions.none { it.id == androidAction }) {
                AndroidHostActionStatus.UNSUPPORTED
            } else if (performGlobalAction(androidAction)) {
                AndroidHostActionStatus.ACCEPTED
            } else {
                AndroidHostActionStatus.FAILED
            }
        } ?: AndroidHostActionStatus.FAILED

    override fun supportsCoordinateGestures(): Boolean = callOnMain {
        serviceInfo.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES != 0
    } ?: false

    override fun performCoordinateGesture(
        correlation: UiSnapshotCorrelation,
        gesture: UiCoordinateGesture,
    ): AndroidHostActionStatus {
        // Commands run on a private serial worker. Refuse a direct main-thread
        // call rather than blocking the service/callback looper.
        if (Looper.myLooper() == Looper.getMainLooper()) return AndroidHostActionStatus.FAILED
        currentUiAvailability().let { availability ->
            if (!availability.isAvailable) return availability.toAndroidHostActionStatus()
        }
        val current = snapshots.current() ?: return AndroidHostActionStatus.STALE_TARGET
        if (current.correlation != correlation) return AndroidHostActionStatus.STALE_TARGET
        if (!current.displayBounds.contains(gesture.start)) return AndroidHostActionStatus.BLOCKED
        if (gesture.end?.let(current.displayBounds::contains) == false) {
            return AndroidHostActionStatus.BLOCKED
        }
        val rootLocator = snapshots.currentFrame()?.takeIf { it.correlation == correlation }
            ?.locators
            ?.firstOrNull()
            ?: return AndroidHostActionStatus.STALE_TARGET
        if (AndroidAutomationExclusions.isProhibited(packageName, rootLocator)) {
            return AndroidHostActionStatus.BLOCKED
        }
        if (!supportsCoordinateGestures()) return AndroidHostActionStatus.UNSUPPORTED
        if (!gestureInFlight.compareAndSet(false, true)) {
            return AndroidHostActionStatus.BLOCKED
        }

        val completion = CountDownLatch(1)
        val completed = AtomicReference<Boolean>()
        val displayId = rootLocator.displayId
        val description = gestureDescription(gesture, displayId)
        val dispatchStatus = callOnMain {
            val availability = currentUiAvailability()
            if (!availability.isAvailable) {
                return@callOnMain availability.toAndroidHostActionStatus()
            }
            if (
                dispatchGesture(
                    description,
                    object : GestureResultCallback() {
                        override fun onCompleted(gestureDescription: GestureDescription) {
                            completed.set(true)
                            gestureInFlight.set(false)
                            completion.countDown()
                        }

                        override fun onCancelled(gestureDescription: GestureDescription) {
                            completed.set(false)
                            gestureInFlight.set(false)
                            completion.countDown()
                        }
                    },
                    mainHandler,
                )
            ) {
                AndroidHostActionStatus.ACCEPTED
            } else {
                AndroidHostActionStatus.FAILED
            }
        } ?: AndroidHostActionStatus.FAILED
        if (dispatchStatus != AndroidHostActionStatus.ACCEPTED) {
            gestureInFlight.set(false)
            return dispatchStatus
        }
        val timeout = (gesture.durationMillis + GESTURE_CALLBACK_GRACE_MILLIS)
            .coerceAtMost(MAX_GESTURE_WAIT_MILLIS)
        val callbackArrived = try {
            completion.await(timeout, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!callbackArrived) {
            // Leave the gate closed until a late callback or service restart;
            // dispatchGesture has no safe cancellation primitive.
            return AndroidHostActionStatus.TIMED_OUT
        }
        return if (completed.get() == true) {
            AndroidHostActionStatus.ACCEPTED
        } else {
            AndroidHostActionStatus.CANCELLED
        }
    }

    private fun captureVisualSnapshot(): VisualUiCaptureResult {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return VisualUiCaptureResult.Failure("visual_capture_main_thread_rejected")
        }
        currentUiAvailability().let { availability ->
            if (!availability.isAvailable) {
                return VisualUiCaptureResult.Failure(
                    availability.userActionRequiredErrorCode(),
                )
            }
        }
        if (!screenshotInFlight.compareAndSet(false, true)) {
            return VisualUiCaptureResult.Failure("visual_capture_busy")
        }
        try {
            val before = callOnMain { captureAndPublishOnMain() }
                ?: return visualFailureOrUiUnavailable("ui_snapshot_unavailable")
            val canCapture = callOnMain {
                serviceInfo.capabilities and
                    AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT != 0
            } ?: false
            if (!canCapture) {
                return VisualUiCaptureResult.Failure("visual_capture_capability_unavailable")
            }

            val completion = CountDownLatch(1)
            val raw = AtomicReference<RawScreenshotResult>()
            val screenshotDisplayId = callOnMain {
                snapshots.currentFrame()
                    ?.takeIf { it.correlation == before.correlation }
                    ?.displayId
            } ?: return visualFailureOrUiUnavailable("visual_capture_display_unavailable")
            val requestResult = callOnMain {
                val availability = currentUiAvailability()
                if (!availability.isAvailable) {
                    return@callOnMain ScreenshotRequestResult.UiUnavailable(availability)
                }
                takeScreenshot(
                    screenshotDisplayId,
                    screenshotExecutor,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(screenshot: ScreenshotResult) {
                            val encoded = runCatching { encodeScreenshot(screenshot) }.getOrNull()
                            raw.compareAndSet(
                                null,
                                encoded?.let(RawScreenshotResult::Success)
                                    ?: RawScreenshotResult.Failure("visual_capture_encoding_failed"),
                            )
                            completion.countDown()
                        }

                        override fun onFailure(errorCode: Int) {
                            raw.compareAndSet(
                                null,
                                RawScreenshotResult.Failure(screenshotFailureCode(errorCode)),
                            )
                            completion.countDown()
                        }
                    },
                )
                ScreenshotRequestResult.Requested
            } ?: ScreenshotRequestResult.Failed
            when (requestResult) {
                ScreenshotRequestResult.Requested -> Unit
                is ScreenshotRequestResult.UiUnavailable -> {
                    return VisualUiCaptureResult.Failure(
                        requestResult.availability.userActionRequiredErrorCode(),
                    )
                }
                ScreenshotRequestResult.Failed -> {
                    return VisualUiCaptureResult.Failure("visual_capture_request_failed")
                }
            }
            val completed = try {
                completion.await(SCREENSHOT_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                false
            }
            if (!completed) return VisualUiCaptureResult.Failure("visual_capture_timed_out")
            val screenshot = when (val result = raw.get()) {
                is RawScreenshotResult.Success -> result.screenshot
                is RawScreenshotResult.Failure -> return VisualUiCaptureResult.Failure(result.code)
                null -> return VisualUiCaptureResult.Failure("visual_capture_callback_missing")
            }

            // Publish a fresh semantic snapshot after the pixels were captured. If the active
            // window changed meanwhile, the image must not authorize coordinates on another UI.
            val afterState = callOnMain {
                val snapshot = captureAndPublishOnMain()
                    ?: return@callOnMain PostScreenshotLocatorState(null, null)
                PostScreenshotLocatorState(
                    snapshot = snapshot,
                    displayId = snapshots.currentFrame()
                        ?.takeIf { it.correlation == snapshot.correlation }
                        ?.displayId,
                )
            } ?: return visualFailureOrUiUnavailable("ui_snapshot_unavailable")
            val after = afterState.snapshot
                ?: return visualFailureOrUiUnavailable("ui_snapshot_unavailable")
            val afterDisplayId = afterState.displayId
                ?: return visualFailureOrUiUnavailable("visual_capture_display_unavailable")
            val captureStillCorrelated = VisualCaptureCorrelationGuard.matches(
                before = before,
                after = after,
                screenshotDisplayId = screenshotDisplayId,
                afterDisplayId = afterDisplayId,
            )
            if (!captureStillCorrelated) {
                return VisualUiCaptureResult.Failure("visual_capture_content_changed")
            }
            return VisualUiCaptureResult.Success(
                VisualUiCapture(
                    correlation = after.correlation,
                    imageDataUrl = screenshot.dataUrl,
                    pixelWidth = screenshot.width,
                    pixelHeight = screenshot.height,
                    sourcePixelWidth = screenshot.sourceWidth,
                    sourcePixelHeight = screenshot.sourceHeight,
                    displayBounds = after.displayBounds,
                    capturedAtElapsedMillis = SystemClock.elapsedRealtime(),
                ),
            )
        } finally {
            screenshotInFlight.set(false)
        }
    }

    private fun encodeScreenshot(screenshot: ScreenshotResult): EncodedVisualScreenshot? {
        val buffer = screenshot.hardwareBuffer
        return try {
            val wrapped = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace) ?: return null
            val software = try {
                wrapped.copy(Bitmap.Config.ARGB_8888, false)
            } finally {
                wrapped.recycle()
            } ?: return null
            try {
                VisualScreenshotEncoder.encode(software)
            } finally {
                software.recycle()
            }
        } finally {
            buffer.close()
        }
    }

    private fun screenshotFailureCode(errorCode: Int): String = when (errorCode) {
        ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "visual_capture_access_revoked"
        ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "visual_capture_rate_limited"
        ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "visual_capture_invalid_display"
        ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR -> "visual_capture_internal_error"
        else -> "visual_capture_platform_rejected"
    }

    private fun visualFailureOrUiUnavailable(fallbackCode: String): VisualUiCaptureResult.Failure {
        val availability = currentUiAvailability()
        return VisualUiCaptureResult.Failure(
            if (availability.isAvailable) fallbackCode else availability.userActionRequiredErrorCode(),
        )
    }

    override fun actionObservationBaseline(): AccessibilitySnapshotId? = callOnMain {
        nextSnapshotId.get()
            .takeIf { it > 0 }
            ?.let(::AccessibilitySnapshotId)
    }

    override fun snapshotAfterAction(
        before: UiSnapshotCorrelation,
        baseline: AccessibilitySnapshotId,
    ): SemanticUiSnapshot? {
        if (!currentUiAvailability().isAvailable) return null
        val deadline = SystemClock.elapsedRealtime() + EVENT_REFRESH_WAIT_MILLIS
        synchronized(snapshotMonitor) {
            while (connected.get()) {
                if (!currentUiAvailability().isAvailable) return null
                val current = snapshots.current()
                if (
                    current != null &&
                    current.correlation.sessionId == before.sessionId &&
                    current.correlation.snapshotId.value > baseline.value
                ) {
                    if (snapshots.retainCurrentForReceipt(current.correlation)) return current
                }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                try {
                    snapshotMonitor.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        val captured = callOnMain { captureAndPublishOnMain() } ?: return null
        if (
            captured.correlation.sessionId != before.sessionId ||
            captured.correlation.snapshotId.value <= baseline.value ||
            !snapshots.retainCurrentForReceipt(captured.correlation)
        ) {
            return null
        }
        return captured
    }

    override fun resolveTargetAfterAction(
        beforeLocator: AndroidNodeLocator,
        after: UiSnapshotCorrelation,
    ): AndroidTargetResolution {
        if (!currentUiAvailability().isAvailable) {
            return AndroidTargetResolution.StaleSnapshot
        }
        val frame = snapshots.receiptFrame(after)
            ?: return AndroidTargetResolution.StaleSnapshot
        val snapshot = frame.snapshot
        val matches = frame.locators.filter(beforeLocator::isSameIdentity)
        if (matches.size > 1) return AndroidTargetResolution.Ambiguous
        val locator = matches.singleOrNull() ?: return AndroidTargetResolution.Missing
        val handle = SemanticNodeHandle(after, locator.nodeOrdinal)
        val node = snapshot.resolve(handle) ?: return AndroidTargetResolution.StaleSnapshot
        return AndroidTargetResolution.Found(node, locator)
    }

    private fun canExposeReceipt(correlation: UiSnapshotCorrelation): Boolean =
        connected.get() && correlation.sessionId == sessionId &&
            currentUiAvailability().isAvailable

    private fun performNodeActionOnMain(
        handle: SemanticNodeHandle,
        action: AndroidNodeAction,
    ): AndroidHostNodeActionResult {
        currentUiAvailability().let { availability ->
            if (!availability.isAvailable) {
                return AndroidHostNodeActionResult(availability.toAndroidHostActionStatus())
            }
        }
        val expectedFrame = snapshots.commandFrame(handle.correlation)
            ?: return AndroidHostNodeActionResult(AndroidHostActionStatus.STALE_TARGET)
        val expected = expectedFrame.snapshot
        if (
            expected.correlation != handle.correlation ||
            expected.correlation.sessionId != sessionId
        ) {
            return AndroidHostNodeActionResult(AndroidHostActionStatus.STALE_TARGET)
        }
        val expectedLocator = expectedFrame.byOrdinal[handle.nodeOrdinal]
            ?: return AndroidHostNodeActionResult(AndroidHostActionStatus.STALE_TARGET)
        if (AndroidAutomationExclusions.isProhibited(packageName, expectedLocator)) {
            return AndroidHostNodeActionResult(AndroidHostActionStatus.BLOCKED)
        }
        val activeRoot = resolveActiveApplicationRoot()
            ?: return AndroidHostNodeActionResult(AndroidHostActionStatus.STALE_TARGET)
        val currentDisplay = when (
            val resolution = displayResolver.resolve(activeRoot.displayId)
        ) {
            is AndroidAccessibilityDisplayResolution.Available -> resolution.display
            is AndroidAccessibilityDisplayResolution.Failed -> {
                activeRoot.root.close()
                return AndroidHostNodeActionResult(AndroidHostActionStatus.FAILED)
            }
        }
        val displayBounds = currentDisplay.bounds
        val displayId = currentDisplay.displayId
        if (displayId != expectedLocator.displayId) {
            activeRoot.root.close()
            return AndroidHostNodeActionResult(AndroidHostActionStatus.STALE_TARGET)
        }
        val root = activeRoot.root
        val rootWindowId = runCatching { root.windowId }.getOrElse {
            root.close()
            return AndroidHostNodeActionResult(AndroidHostActionStatus.FAILED)
        }
        if (rootWindowId != handle.correlation.windowId.value) {
            root.close()
            return AndroidHostNodeActionResult(AndroidHostActionStatus.STALE_TARGET)
        }
        val projection = projector.withNodeMatchingLocator(
            root = root,
            expectedLocator = expectedLocator,
            correlation = expected.correlation,
            displayId = displayId,
            displayBounds = displayBounds,
            capturedAtElapsedMillis = SystemClock.elapsedRealtime(),
        ) { liveTarget, liveLocator, raw, _ ->
            val liveSnapshot = snapshotFactory.build(raw)
            val targetStillMatches = AndroidNodeActionTargetValidator.matches(
                expectedSnapshot = expected,
                expectedLocator = expectedLocator,
                liveSnapshot = liveSnapshot,
                liveLocator = liveLocator,
            )
            if (!targetStillMatches) {
                return@withNodeMatchingLocator AndroidHostNodeActionResult(
                    AndroidHostActionStatus.STALE_TARGET,
                )
            }
            if (AndroidAutomationExclusions.isProhibited(packageName, liveLocator)) {
                return@withNodeMatchingLocator AndroidHostNodeActionResult(
                    AndroidHostActionStatus.BLOCKED,
                )
            }
            val availability = currentUiAvailability()
            if (!availability.isAvailable) {
                return@withNodeMatchingLocator AndroidHostNodeActionResult(
                    availability.toAndroidHostActionStatus(),
                )
            }
            if (liveTarget.perform(action)) {
                AndroidHostNodeActionResult(AndroidHostActionStatus.ACCEPTED, expectedLocator)
            } else {
                AndroidHostNodeActionResult(AndroidHostActionStatus.FAILED)
            }
        }
        return when {
            projection.readFailed -> AndroidHostNodeActionResult(AndroidHostActionStatus.FAILED)
            projection.targetAmbiguous ->
                AndroidHostNodeActionResult(AndroidHostActionStatus.AMBIGUOUS_TARGET)
            !projection.targetFound ->
                AndroidHostNodeActionResult(AndroidHostActionStatus.STALE_TARGET)
            else -> projection.value ?: AndroidHostNodeActionResult(AndroidHostActionStatus.FAILED)
        }
    }

    private fun captureAndPublishOnMain(): SemanticUiSnapshot? {
        if (!connected.get()) return null
        if (!currentUiAvailability().isAvailable) {
            return clearSnapshot(AccessibilitySnapshotFailure.CAPTURE_FAILED)
        }
        val activeSession = sessionId ?: return null
        val activeRoot = resolveActiveApplicationRoot()
            ?: return clearSnapshot(AccessibilitySnapshotFailure.NO_ACTIVE_ROOT)
        val currentDisplay = when (
            val resolution = displayResolver.resolve(activeRoot.displayId)
        ) {
            is AndroidAccessibilityDisplayResolution.Available -> resolution.display
            is AndroidAccessibilityDisplayResolution.Failed -> {
                activeRoot.root.close()
                return clearSnapshot(resolution.failure)
            }
        }
        val displayBounds = currentDisplay.bounds
        val displayId = currentDisplay.displayId
        val root = activeRoot.root
        val windowId = runCatching { root.windowId }.getOrElse {
            root.close()
            return clearSnapshot(AccessibilitySnapshotFailure.INVALID_WINDOW)
        }
        if (windowId < 0) {
            root.close()
            return clearSnapshot(AccessibilitySnapshotFailure.INVALID_WINDOW)
        }
        val snapshotNumber = nextSnapshotId.incrementAndGet()
        if (snapshotNumber <= 0) {
            root.close()
            return clearSnapshot(AccessibilitySnapshotFailure.SNAPSHOT_ID_EXHAUSTED)
        }
        val correlation = UiSnapshotCorrelation(
            sessionId = activeSession,
            windowId = AccessibilityWindowId(windowId),
            snapshotId = AccessibilitySnapshotId(snapshotNumber),
        )
        if (!currentUiAvailability().isAvailable) {
            root.close()
            return clearSnapshot(AccessibilitySnapshotFailure.CAPTURE_FAILED)
        }
        val projected = runCatching {
            projector.project(
                root = root,
                correlation = correlation,
                displayId = displayId,
                displayBounds = displayBounds,
                capturedAtElapsedMillis = SystemClock.elapsedRealtime(),
            )
        }.getOrElse {
            return clearSnapshot(AccessibilitySnapshotFailure.PROJECTION_FAILED)
        }
        val raw = projected.rawSnapshot
        if (projected.readFailed) {
            return clearSnapshot(AccessibilitySnapshotFailure.PROJECTION_ROOT_READ_FAILED)
        }
        if (raw == null) return clearSnapshot(AccessibilitySnapshotFailure.PROJECTION_EMPTY)
        val semantic = runCatching { snapshotFactory.build(raw) }.getOrNull()
            ?: return clearSnapshot(AccessibilitySnapshotFailure.SNAPSHOT_FACTORY_FAILED)
        if (!currentUiAvailability().isAvailable) {
            return clearSnapshot(AccessibilitySnapshotFailure.CAPTURE_FAILED)
        }
        val published = runCatching {
            val retainedOrdinals = semantic.nodes.mapTo(hashSetOf()) { it.handle.nodeOrdinal }
            val locators = projected.locators.filter { it.nodeOrdinal in retainedOrdinals }
            snapshots.publish(
                AndroidAccessibilityFrame(
                    snapshot = semantic,
                    displayId = displayId,
                    locators = locators,
                ),
            )
        }.isSuccess
        if (!published) {
            return clearSnapshot(AccessibilitySnapshotFailure.SNAPSHOT_PUBLICATION_FAILED)
        }
        snapshotDiagnostics.clear()
        notifySnapshotChanged()
        return semantic
    }

    /**
     * Android occasionally exposes an active application window while `rootInActiveWindow` is
     * transiently null. Fall back only to a normal active/focused app window, retaining the
     * window's own display id. Secure windows remain protected because Android returns no
     * accessible root for them.
     */
    private fun resolveActiveApplicationRoot(): AndroidResolvedApplicationRoot? =
        if (!currentUiAvailability().isAvailable) {
            null
        } else activeWindowResolver.resolve(
            directRoot = {
                rootInActiveWindow?.let(::AccessibilityNodeInfoNode)
            },
            candidateWindows = {
                val byDisplay = windowsOnAllDisplays
                buildList {
                    for (index in 0 until byDisplay.size()) {
                        byDisplay.valueAt(index).orEmpty().forEach { window ->
                            add(AccessibilityWindowInfoWindow(window))
                        }
                    }
                }
            },
        )

    /**
     * One bounded refresh requested by a tool call. A newly rebound service can be connected
     * before Android publishes its first active-window root. Capture once, then let the main
     * looper consume the first real Accessibility event before one final capture. This waits on
     * the event monitor rather than polling or invalidating the UI continuously.
     */
    private fun refreshSnapshotOnDemand(): SemanticUiSnapshot? {
        ensureSessionPublished()
        if (!currentUiAvailability().isAvailable) return null
        val previousCorrelation = snapshots.current()?.correlation
        val immediate = callOnMain { captureAndPublishOnMain() }
        if (immediate != null || Looper.myLooper() == Looper.getMainLooper()) return immediate

        val deadline = SystemClock.elapsedRealtime() + ON_DEMAND_REFRESH_WAIT_MILLIS
        synchronized(snapshotMonitor) {
            while (connected.get()) {
                snapshots.current()
                    ?.takeIf { it.correlation != previousCorrelation }
                    ?.let { return it }
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining <= 0) break
                try {
                    snapshotMonitor.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
        }
        return callOnMain { captureAndPublishOnMain() }
    }

    private fun ensureSessionPublished() {
        val owner = connectionOwner ?: return
        val session = commandSession ?: return
        HansAccessibilitySessions.ensurePublished(owner, session)
    }

    private fun clearSnapshot(failure: AccessibilitySnapshotFailure): SemanticUiSnapshot? {
        snapshotDiagnostics.record(failure)
        snapshots.clearCurrent()
        notifySnapshotChanged()
        return null
    }

    private fun notifySnapshotChanged() {
        synchronized(snapshotMonitor) { snapshotMonitor.notifyAll() }
    }

    private fun gestureDescription(
        gesture: UiCoordinateGesture,
        displayId: Int,
    ): GestureDescription {
        val path = Path().apply {
            moveTo(gesture.start.x.toFloat(), gesture.start.y.toFloat())
            val end = gesture.end ?: gesture.start
            lineTo(end.x.toFloat(), end.y.toFloat())
        }
        return GestureDescription.Builder()
            .setDisplayId(displayId)
            .addStroke(GestureDescription.StrokeDescription(path, 0, gesture.durationMillis))
            .build()
    }

    /**
     * Builds the confirmation host from an explicit default-display WindowContext. An
     * AccessibilityService is not itself a display Context on Android 12+, and V1 deliberately
     * refuses sensitive actions on secondary displays.
     */
    private fun createSensitiveActionConfirmation(
        generationNonce: String,
    ): AndroidAccessibilitySensitiveActionConfirmation? = runCatching {
        val displayManager = getSystemService(DisplayManager::class.java)
            ?: return@runCatching null
        val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
            ?.takeIf { it.isValid }
            ?: return@runCatching null
        val windowContext = createWindowContext(
            defaultDisplay,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            null,
        )
        val windowManager = windowContext.getSystemService(WindowManager::class.java)
            ?: return@runCatching null
        AndroidAccessibilitySensitiveActionConfirmation(
            context = windowContext,
            windowManager = windowManager,
            mainHandler = mainHandler,
            serviceGenerationNonce = generationNonce,
            contextStillMatches = ::sensitiveActionContextStillMatches,
        )
    }.getOrNull()

    private fun requestSensitiveActionApproval(
        command: AccessibilityCommand,
        request: AccessibilityConfirmationRequest,
    ): AccessibilitySensitiveActionApproval {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return AccessibilitySensitiveActionApproval.Unavailable
        }
        if (!currentUiAvailability().isAvailable) {
            return AccessibilitySensitiveActionApproval.ContextChanged
        }
        val correlation = request.correlation
            ?: return AccessibilitySensitiveActionApproval.Unavailable
        val generationNonce = serviceGenerationNonce
            ?: return AccessibilitySensitiveActionApproval.Unavailable
        val actionKind = command.sensitiveActionKind()
            ?: return AccessibilitySensitiveActionApproval.Unavailable
        val target = confirmationTarget(command, correlation)
            ?: return AccessibilitySensitiveActionApproval.Unavailable
        if (target.displayId != Display.DEFAULT_DISPLAY) {
            return AccessibilitySensitiveActionApproval.Unavailable
        }
        val appLabel = installedAppLabel(target.packageName)
        val prompt = runCatching {
            AccessibilitySensitiveActionPrompt(
                promptId = newAccessibilityConfirmationIdentifier("prompt"),
                idempotencyKey = request.idempotencyKey,
                commandFingerprint = request.commandFingerprint,
                risk = request.risk,
                correlation = correlation,
                serviceGenerationNonce = generationNonce,
                actionKind = actionKind,
                appLabel = appLabel,
                packageName = AccessibilityConfirmationTextSanitizer.packageName(target.packageName),
            )
        }.getOrNull() ?: return AccessibilitySensitiveActionApproval.Unavailable
        val surface = confirmationOverlay ?: return AccessibilitySensitiveActionApproval.Unavailable
        if (!snapshots.extendCommandFrameForConfirmation(correlation)) {
            return AccessibilitySensitiveActionApproval.ContextChanged
        }
        val outcome = surface.request(prompt)
        return when (outcome) {
            is AccessibilitySensitiveActionOutcome.Approved -> {
                val approval = AccessibilitySensitiveActionReceiptBinding.approvalFor(prompt, outcome)
                    ?: return AccessibilitySensitiveActionApproval.Unavailable
                AccessibilitySensitiveActionApproval.Approved(approval)
            }
            AccessibilitySensitiveActionOutcome.Denied -> AccessibilitySensitiveActionApproval.Denied
            AccessibilitySensitiveActionOutcome.Expired -> AccessibilitySensitiveActionApproval.Expired
            AccessibilitySensitiveActionOutcome.ContextChanged ->
                AccessibilitySensitiveActionApproval.ContextChanged
            AccessibilitySensitiveActionOutcome.Unavailable ->
                AccessibilitySensitiveActionApproval.Unavailable
        }
    }

    /** Uses only the retained local locator; external node text never enters the prompt. */
    private fun confirmationTarget(
        command: AccessibilityCommand,
        correlation: UiSnapshotCorrelation,
    ): SensitiveActionTarget? {
        val frame = snapshots.commandFrame(correlation) ?: return null
        if (frame.correlation != correlation) return null
        val ordinal = when (command) {
            is AccessibilityCommand.Click -> command.handle
                .takeIf { it.correlation == correlation }
                ?.nodeOrdinal
            is AccessibilityCommand.SetText -> command.handle
                .takeIf { it.correlation == correlation }
                ?.nodeOrdinal
            is AccessibilityCommand.Scroll -> command.handle
                .takeIf { it.correlation == correlation }
                ?.nodeOrdinal
            is AccessibilityCommand.Global -> frame.snapshot.roots.singleOrNull()?.nodeOrdinal
            is AccessibilityCommand.CoordinateGesture -> frame.snapshot.roots.singleOrNull()?.nodeOrdinal
            is AccessibilityCommand.Find -> null
        } ?: return null
        val locator = frame.byOrdinal[ordinal] ?: return null
        val rawPackageName = locator.fingerprint.packageName ?: return null
        val packageName = AccessibilityConfirmationTextSanitizer.packageName(rawPackageName)
        if (packageName.isBlank() || packageName != rawPackageName) return null
        return SensitiveActionTarget(frame.displayId, packageName)
    }

    private fun installedAppLabel(packageName: String): String =
        AccessibilityConfirmationAppLabelPolicy.resolve {
            val info =
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                packageManager.getApplicationInfo(
                    packageName,
                    PackageManager.ApplicationInfoFlags.of(0),
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getApplicationInfo(packageName, 0)
            }
            packageManager.getApplicationLabel(info)
        }

    /**
     * Resolve only eligible APPLICATION windows. The confirmation's own Accessibility overlay
     * therefore cannot invalidate or replace the retained underlying app window.
     */
    private fun sensitiveActionContextStillMatches(
        prompt: AccessibilitySensitiveActionPrompt,
    ): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) return false
        if (!connected.get() || serviceGenerationNonce != prompt.serviceGenerationNonce) return false
        if (prompt.correlation.sessionId != sessionId) return false
        if (!currentUiAvailability().isAvailable) return false
        val frame = snapshots.commandFrame(prompt.correlation) ?: return false
        if (
            frame.displayId != Display.DEFAULT_DISPLAY ||
            frame.correlation.windowId.value != prompt.correlation.windowId.value
        ) return false
        val activeRoot = resolveActiveApplicationRoot() ?: return false
        return try {
            if (
                activeRoot.displayId != Display.DEFAULT_DISPLAY ||
                activeRoot.root.windowId != prompt.correlation.windowId.value
            ) return false
            val packageName = AccessibilityConfirmationTextSanitizer.packageName(
                activeRoot.root.packageName,
            )
            packageName == prompt.packageName
        } catch (_: RuntimeException) {
            false
        } finally {
            activeRoot.root.close()
        }
    }

    private fun tearDownSession() {
        confirmationOverlay?.close()
        confirmationOverlay = null
        serviceGenerationNonce = null
        actionKeyController?.close()
        actionKeyController = null
        connected.set(false)
        mainHandler.removeCallbacks(captureRunnable)
        captureScheduled.set(false)
        gestureInFlight.set(false)
        HansAccessibilityApprovals.remove(confirmationGate)
        val owner = connectionOwner
        commandSession?.let { session ->
            if (owner != null) HansAccessibilitySessions.remove(owner, session)
            session.close()
        }
        if (owner != null) HansAccessibilitySessions.disconnect(owner)
        commandSession = null
        connectionOwner = null
        sessionId = null
        snapshots.clearAll()
        snapshotDiagnostics.clear()
        notifySnapshotChanged()
    }

    private fun setActionKeyFilteringEnabled(enabled: Boolean) {
        val current = serviceInfo ?: return
        val nextFlags = if (enabled) {
            current.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        } else {
            current.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS.inv()
        }
        if (current.flags == nextFlags) return
        current.flags = nextFlags
        serviceInfo = current
    }

    private fun <T> callOnMain(block: () -> T): T? {
        if (!connected.get()) return null
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return runCatching(block).getOrNull()
        }
        val task = FutureTask(block)
        if (!mainHandler.post(task)) return null
        return try {
            task.get(MAIN_CALL_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            task.cancel(false)
            null
        }
    }

    private data class PostScreenshotLocatorState(
        val snapshot: SemanticUiSnapshot?,
        val displayId: Int?,
    )

    private data class SensitiveActionTarget(
        val displayId: Int,
        val packageName: String,
    )

    private sealed interface ScreenshotRequestResult {
        data object Requested : ScreenshotRequestResult
        data class UiUnavailable(
            val availability: UiInteractionAvailability,
        ) : ScreenshotRequestResult
        data object Failed : ScreenshotRequestResult
    }

    private sealed interface RawScreenshotResult {
        data class Success(val screenshot: EncodedVisualScreenshot) : RawScreenshotResult
        data class Failure(val code: String) : RawScreenshotResult
    }

    private fun currentUiAvailability(): UiInteractionAvailability =
        currentUiInteractionAvailability(uiAvailability)

    private companion object {
        const val EVENT_DEBOUNCE_MILLIS = 100L
        const val EVENT_REFRESH_WAIT_MILLIS = 500L
        const val ON_DEMAND_REFRESH_WAIT_MILLIS = 750L
        const val MAIN_CALL_TIMEOUT_MILLIS = 3_000L
        const val GESTURE_CALLBACK_GRACE_MILLIS = 2_000L
        const val MAX_GESTURE_WAIT_MILLIS = 12_000L
        const val SCREENSHOT_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * BACK belongs to the visible trusted confirmation overlay, never to a user
 * action-key mapping. The overlay also handles BACK through its focused view;
 * this filter covers framework delivery while action-key filtering is active.
 */
internal object AccessibilityConfirmationBackKeyFilter {
    fun onKeyEvent(
        event: KeyEvent,
        confirmationActive: Boolean,
        deny: () -> Unit,
    ): Boolean {
        if (!confirmationActive || event.keyCode != KeyEvent.KEYCODE_BACK) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) deny()
        return event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP
    }
}

internal object VisualCaptureCorrelationGuard {
    fun matches(
        before: SemanticUiSnapshot,
        after: SemanticUiSnapshot,
        screenshotDisplayId: Int,
        afterDisplayId: Int,
    ): Boolean =
        afterDisplayId == screenshotDisplayId &&
            after.correlation.sessionId == before.correlation.sessionId &&
            after.correlation.windowId == before.correlation.windowId &&
            SemanticSnapshotContent.same(before, after)
}

internal enum class AndroidAccessibilityWindowKind {
    APPLICATION,
    OTHER,
}

/** Short-lived view of one framework window. It never retains external window metadata. */
internal interface AndroidAccessibilityWindow : AutoCloseable {
    val windowId: Int
    val displayId: Int
    val active: Boolean
    val focused: Boolean
    val kind: AndroidAccessibilityWindowKind

    fun root(): AndroidAccessibilityNode?
}

/**
 * Root and display id are resolved atomically. A direct root is accepted only when its window id
 * matches an active/focused application window returned by getWindowsOnAllDisplays(). This avoids
 * deriving display state from AccessibilityService's non-UI Context.
 */
internal data class AndroidResolvedApplicationRoot(
    val displayId: Int,
    val root: AndroidAccessibilityNode,
)

internal class AndroidActiveWindowRootResolver {
    fun resolve(
        directRoot: () -> AndroidAccessibilityNode?,
        candidateWindows: () -> List<AndroidAccessibilityWindow>,
    ): AndroidResolvedApplicationRoot? {
        val direct = runCatching(directRoot).getOrNull()
        val candidates = runCatching(candidateWindows).getOrElse {
            direct?.close()
            return null
        }
        return try {
            val eligible = candidates.mapNotNull(::eligibleWindow)
            val directWindowId = direct?.let { node ->
                runCatching { node.windowId }.getOrNull()
            }
            val directWindow = directWindowId?.let { windowId ->
                eligible.singleOrNull { candidate ->
                    candidate.windowId == windowId
                }
            }
            if (direct != null && directWindow != null) {
                return AndroidResolvedApplicationRoot(directWindow.displayId, direct)
            }
            direct?.close()

            eligible.asSequence()
                .sortedWith(
                    compareByDescending<EligibleWindow> { it.active }
                        .thenByDescending { it.focused }
                        .thenByDescending { it.displayId == android.view.Display.DEFAULT_DISPLAY },
                )
                .mapNotNull { candidate ->
                    runCatching(candidate.window::root).getOrNull()?.let { root ->
                        AndroidResolvedApplicationRoot(candidate.displayId, root)
                    }
                }
                .firstOrNull()
        } finally {
            candidates.forEach { candidate -> runCatching(candidate::close) }
        }
    }

    private fun eligibleWindow(candidate: AndroidAccessibilityWindow): EligibleWindow? =
        runCatching {
            val displayId = candidate.displayId
            val windowId = candidate.windowId
            val active = candidate.active
            val focused = candidate.focused
            if (
                displayId < 0 ||
                windowId < 0 ||
                candidate.kind != AndroidAccessibilityWindowKind.APPLICATION ||
                (!active && !focused)
            ) {
                null
            } else {
                EligibleWindow(candidate, windowId, displayId, active, focused)
            }
        }.getOrNull()

    private data class EligibleWindow(
        val window: AndroidAccessibilityWindow,
        val windowId: Int,
        val displayId: Int,
        val active: Boolean,
        val focused: Boolean,
    )
}

private class AccessibilityWindowInfoWindow(
    private val window: AccessibilityWindowInfo,
) : AndroidAccessibilityWindow {
    override val windowId: Int
        get() = window.id
    override val displayId: Int
        get() = window.displayId
    override val active: Boolean
        get() = window.isActive
    override val focused: Boolean
        get() = window.isFocused
    override val kind: AndroidAccessibilityWindowKind
        get() = if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
            AndroidAccessibilityWindowKind.APPLICATION
        } else {
            AndroidAccessibilityWindowKind.OTHER
        }

    override fun root(): AndroidAccessibilityNode? =
        window.root?.let(::AccessibilityNodeInfoNode)

    @Suppress("DEPRECATION")
    override fun close() {
        window.recycle()
    }
}
