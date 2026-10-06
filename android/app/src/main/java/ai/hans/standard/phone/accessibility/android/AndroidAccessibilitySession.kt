package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.AccessibilityCommand
import ai.hans.standard.phone.accessibility.AccessibilityConfirmationRequest
import ai.hans.standard.phone.accessibility.AccessibilityCommandExecutor
import ai.hans.standard.phone.accessibility.AccessibilityExecutionResult
import ai.hans.standard.phone.accessibility.AccessibilityExecutionStatus
import ai.hans.standard.phone.accessibility.AccessibilityIdempotencyKey
import ai.hans.standard.phone.accessibility.AccessibilityPostcondition
import ai.hans.standard.phone.accessibility.AccessibilityPostconditionKind
import ai.hans.standard.phone.accessibility.AccessibilityPostconditionStatus
import ai.hans.standard.phone.accessibility.AccessibilitySessionId
import ai.hans.standard.phone.accessibility.AccessibilityUserApproval
import ai.hans.standard.phone.accessibility.DictationLifecycleStamp
import ai.hans.standard.phone.accessibility.DictationNonInterferenceGuard
import ai.hans.standard.phone.accessibility.SemanticUiSnapshot
import ai.hans.standard.phone.accessibility.UiBounds
import ai.hans.standard.phone.accessibility.UiDataTrust
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Read-only voice lifecycle surface. It intentionally has no stop or mutation method. */
fun interface ReadOnlyDictationLifecycleProbe {
    fun snapshot(): DictationLifecycleStamp
}

/**
 * Process-local injection point used by the AccessibilityService. If no voice
 * lifecycle probe is registered, accessibility actions fail closed before
 * reaching Android. Replacing or removing a probe during an action is also
 * treated as lifecycle interference.
 */
object AndroidDictationLifecycleRegistry : DictationNonInterferenceGuard {
    private val nextRegistrationId = AtomicLong(0)
    private val current = AtomicReference<RegisteredProbe?>()
    private val inFlight = ThreadLocal<Observation?>()

    fun register(probe: ReadOnlyDictationLifecycleProbe): AutoCloseable {
        val id = nextRegistrationId.incrementAndGet()
        check(id > 0) { "dictation probe registration exhausted" }
        val registered = RegisteredProbe(id, probe)
        current.set(registered)
        return AutoCloseable { current.compareAndSet(registered, null) }
    }

    override fun beforeAccessibilityAction(): DictationLifecycleStamp {
        val registered = checkNotNull(current.get()) {
            "dictation lifecycle probe is not registered"
        }
        val stamp = registered.probe.snapshot()
        inFlight.set(Observation(registered.id, stamp))
        return stamp
    }

    override fun remainedIndependent(before: DictationLifecycleStamp): Boolean {
        val observation = inFlight.get()
        inFlight.remove()
        if (observation == null || observation.stamp != before) return false
        val registered = current.get() ?: return false
        if (registered.id != observation.registrationId) return false
        return runCatching { registered.probe.snapshot() == before }.getOrDefault(false)
    }

    private data class RegisteredProbe(
        val id: Long,
        val probe: ReadOnlyDictationLifecycleProbe,
    )

    private data class Observation(
        val registrationId: Long,
        val stamp: DictationLifecycleStamp,
    )
}

fun interface AccessibilityCommandCallback {
    fun onResult(result: AccessibilityExecutionResult)
}

data class VisualUiCapture(
    val correlation: UiSnapshotCorrelation,
    val imageDataUrl: String,
    /** Dimensions of the encoded image delivered to the model. */
    val pixelWidth: Int,
    val pixelHeight: Int,
    /** Dimensions before bounded down-scaling by [VisualScreenshotEncoder]. */
    val sourcePixelWidth: Int,
    val sourcePixelHeight: Int,
    /** Gesture-coordinate space accepted by the Accessibility adapter. */
    val displayBounds: UiBounds,
    val capturedAtElapsedMillis: Long,
) {
    init {
        require(pixelWidth in 1..8_192 && pixelHeight in 1..8_192)
        require(sourcePixelWidth in 1..16_384 && sourcePixelHeight in 1..16_384)
        require(displayBounds.width > 0 && displayBounds.height > 0)
        require(capturedAtElapsedMillis >= 0)
        require(imageDataUrl.startsWith("data:image/"))
        require(imageDataUrl.length <= 2 * 1024 * 1024)
    }
}

sealed interface VisualUiCaptureResult {
    data class Success(val capture: VisualUiCapture) : VisualUiCaptureResult

    data class Failure(val errorCode: String) : VisualUiCaptureResult {
        init {
            require(errorCode.matches(Regex("[a-z][a-z0-9_]{2,79}")))
        }
    }
}

/** Fixed local outcomes from the service-owned sensitive-action surface. */
sealed interface AccessibilitySensitiveActionApproval {
    data class Approved(val approval: AccessibilityUserApproval) :
        AccessibilitySensitiveActionApproval

    data object Denied : AccessibilitySensitiveActionApproval
    data object Expired : AccessibilitySensitiveActionApproval
    data object ContextChanged : AccessibilitySensitiveActionApproval
    data object Unavailable : AccessibilitySensitiveActionApproval
}

interface HansAccessibilitySession {
    val sessionId: AccessibilitySessionId

    /** A cross-turn handoff must discard command and post-action authority, not just pixels. */
    fun invalidateRetainedEvidence(): Boolean = false

    fun currentSnapshot(): SemanticUiSnapshot?

    /**
     * Performs at most one on-demand capture of the currently visible UI. Implementations with
     * a live Android host must not satisfy this request from a previously published snapshot;
     * the returned correlation becomes the stable input for the following find/action sequence.
     * Test and fallback sessions remain read-only by default.
     */
    fun refreshSnapshot(): SemanticUiSnapshot? = currentSnapshot()

    /** Fresh capture then event-driven, bounded read-only wait; no implicit action or retry. */
    fun awaitSnapshot(
        timeoutMillis: Long,
        cancelled: () -> Boolean,
        predicate: (SemanticUiSnapshot) -> Boolean,
    ): SemanticUiSnapshot? = null

    /** Wake an active read-only wait after the caller sets its cancellation flag. */
    fun wakeSnapshotWaiters() = Unit

    /**
     * Retains the exact snapshot just projected by inspect_ui for a following find/node action.
     * Visual and implicit refreshes never call this hook.
     */
    fun retainSnapshotForCommands(correlation: UiSnapshotCorrelation): Boolean =
        currentSnapshot()?.correlation == correlation

    /** Exact, short-lived post-action receipt only; never capture or substitute a current frame. */
    fun receiptSnapshotForObservation(correlation: UiSnapshotCorrelation): SemanticUiSnapshot? = null

    /** Explicitly promotes the receipt actually returned as a follow-up observation, once only. */
    fun retainReceiptSnapshotForCommands(correlation: UiSnapshotCorrelation): Boolean = false

    /** Removes only an undelivered receipt promotion, including after this session closes. */
    fun withdrawReceiptSnapshotForCommands(correlation: UiSnapshotCorrelation) = Unit

    /**
     * Returns only a fixed Hans-owned classification for the latest failed snapshot capture.
     * Implementations must never return platform text or exception details through this surface.
     */
    fun latestSnapshotFailure(): AccessibilitySnapshotFailure? = null

    /**
     * Captures one bounded visual observation correlated to the currently published semantic
     * window. Implementations never persist the pixels. The default keeps test/fallback sessions
     * source-compatible and fails closed when no real Accessibility host is attached.
     */
    fun captureVisualSnapshot(): VisualUiCaptureResult =
        VisualUiCaptureResult.Failure("visual_capture_unavailable")

    /**
     * Requests a one-time receipt from the connected service's trusted overlay. The launcher
     * Activity is intentionally not involved and model-supplied data never reaches this surface.
     */
    fun requestSensitiveActionApproval(
        command: AccessibilityCommand,
        request: AccessibilityConfirmationRequest,
    ): AccessibilitySensitiveActionApproval = AccessibilitySensitiveActionApproval.Unavailable

    /** Returns false when the bounded command queue is full or the service stopped. */
    fun submit(
        command: AccessibilityCommand,
        approval: AccessibilityUserApproval? = null,
        callback: AccessibilityCommandCallback,
    ): Boolean

    /** Cancellation is rechecked on the command worker immediately before domain execution. */
    fun submitGuarded(
        command: AccessibilityCommand,
        cancelled: () -> Boolean,
        callback: AccessibilityCommandCallback,
    ): Boolean = false

    /**
     * Runs the approved retry atomically before later queued commands. Live sessions accept this
     * only from their own serial command worker; the default preserves simple test doubles.
     */
    fun submitApprovedRetry(
        command: AccessibilityCommand,
        approval: AccessibilityUserApproval,
        callback: AccessibilityCommandCallback,
    ): Boolean = submit(command, approval, callback)
}

/** Process-wide epoch also reaches fallback stores owned by superseded plugin/runtime leases. */
object HansPhoneToolEvidence {
    private val generation = AtomicLong(0)

    fun epoch(): Long = generation.get()

    fun invalidateRetainedEvidence() {
        generation.updateAndGet { previous ->
            check(previous < Long.MAX_VALUE) { "Phone UI evidence epoch exhausted" }
            previous + 1
        }
        val session = HansAccessibilitySessions.current()
        check(session == null || session.invalidateRetainedEvidence()) {
            "Phone UI evidence could not be invalidated"
        }
    }
}

/** Process-local discovery only; the AccessibilityService publishes no Binder API. */
object HansAccessibilitySessions {
    private data class PublishedSession(
        val owner: Any,
        val session: HansAccessibilitySession,
    )

    private val connectedOwner = AtomicReference<Any?>()
    private val active = AtomicReference<PublishedSession?>()

    fun current(): HansAccessibilitySession? = active.get()?.session

    fun isServiceConnected(): Boolean = connectedOwner.get() != null

    /** A fresh opaque owner is required for every Android service connection generation. */
    internal fun connect(owner: Any) {
        connectedOwner.set(owner)
        while (true) {
            val published = active.get() ?: return
            if (published.owner === owner) return
            if (active.compareAndSet(published, null)) return
        }
    }

    /**
     * Publishes only while [owner] is still the current bound service. The post-write owner
     * check closes the race where an obsolete callback publishes after a newer bind.
     */
    internal fun publish(owner: Any, session: HansAccessibilitySession): Boolean {
        if (connectedOwner.get() !== owner) return false
        val published = PublishedSession(owner, session)
        active.set(published)
        if (connectedOwner.get() === owner) return true
        active.compareAndSet(published, null)
        return false
    }

    /** Event-driven repair for a lost process-local registry reference. */
    internal fun ensurePublished(owner: Any, session: HansAccessibilitySession): Boolean {
        val published = active.get()
        if (published?.owner === owner && published.session === session) return true
        return publish(owner, session)
    }

    internal fun remove(owner: Any, session: HansAccessibilitySession) {
        while (true) {
            val published = active.get() ?: return
            if (published.owner !== owner || published.session !== session) return
            if (active.compareAndSet(published, null)) return
        }
    }

    internal fun disconnect(owner: Any) {
        if (!connectedOwner.compareAndSet(owner, null)) return
        while (true) {
            val published = active.get() ?: return
            if (published.owner !== owner) return
            if (active.compareAndSet(published, null)) return
        }
    }
}

internal class BoundedAccessibilityCommandSession(
    override val sessionId: AccessibilitySessionId,
    private val snapshot: () -> SemanticUiSnapshot?,
    private val refresh: () -> SemanticUiSnapshot? = snapshot,
    private val retainForCommands: (UiSnapshotCorrelation) -> Boolean = {
        snapshot()?.correlation == it
    },
    private val snapshotFailure: () -> AccessibilitySnapshotFailure? = { null },
    private val executor: AccessibilityCommandExecutor,
    private val visualCapture: () -> VisualUiCaptureResult = {
        VisualUiCaptureResult.Failure("visual_capture_unavailable")
    },
    private val sensitiveActionApproval: (
        AccessibilityCommand,
        AccessibilityConfirmationRequest,
    ) -> AccessibilitySensitiveActionApproval = { _, _ ->
        AccessibilitySensitiveActionApproval.Unavailable
    },
    queueCapacity: Int = DEFAULT_QUEUE_CAPACITY,
    private val receiptSnapshot: (UiSnapshotCorrelation) -> SemanticUiSnapshot? = { null },
    private val retainReceiptForCommands: (UiSnapshotCorrelation) -> Boolean = { false },
    private val withdrawReceiptForCommands: (UiSnapshotCorrelation) -> Unit = {},
    private val invalidateEvidence: () -> Unit = { error("Evidence invalidation unavailable") },
    private val awaitFreshSnapshot: (
        Long,
        () -> Boolean,
        (SemanticUiSnapshot) -> Boolean,
    ) -> SemanticUiSnapshot? = { _, _, _ -> null },
    private val wakeSnapshotWait: () -> Unit = {},
) : HansAccessibilitySession, AutoCloseable {
    private val closed = AtomicBoolean(false)
    private val commandWorker = AtomicReference<Thread?>()
    private val commandExecutor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(queueCapacity),
        { runnable -> Thread(runnable, "hans-accessibility-commands").apply { isDaemon = true } },
        ThreadPoolExecutor.AbortPolicy(),
    )

    init {
        require(queueCapacity in 1..MAX_QUEUE_CAPACITY)
    }

    override fun currentSnapshot(): SemanticUiSnapshot? = snapshot()

    override fun invalidateRetainedEvidence(): Boolean =
        !closed.get() && runCatching(invalidateEvidence).isSuccess

    override fun refreshSnapshot(): SemanticUiSnapshot? = if (closed.get()) {
        null
    } else {
        runCatching(refresh).getOrNull()
    }

    override fun awaitSnapshot(
        timeoutMillis: Long,
        cancelled: () -> Boolean,
        predicate: (SemanticUiSnapshot) -> Boolean,
    ): SemanticUiSnapshot? {
        if (closed.get() || timeoutMillis !in 1..EventDrivenSemanticSnapshotWaiter.MAX_WAIT_MILLIS) return null
        val stopped = { closed.get() || runCatching(cancelled).getOrDefault(true) }
        if (stopped()) return null
        return runCatching { awaitFreshSnapshot(timeoutMillis, stopped, predicate) }.getOrNull()
            ?.takeIf { !stopped() && it.correlation.sessionId == sessionId }
    }

    override fun wakeSnapshotWaiters() { runCatching(wakeSnapshotWait) }

    override fun retainSnapshotForCommands(correlation: UiSnapshotCorrelation): Boolean =
        !closed.get() && runCatching { retainForCommands(correlation) }.getOrDefault(false)

    override fun receiptSnapshotForObservation(correlation: UiSnapshotCorrelation): SemanticUiSnapshot? {
        if (closed.get() || correlation.sessionId != sessionId) return null
        return runCatching { receiptSnapshot(correlation) }.getOrNull()
            ?.takeIf { !closed.get() && it.correlation == correlation }
    }

    override fun retainReceiptSnapshotForCommands(correlation: UiSnapshotCorrelation): Boolean {
        if (closed.get() || correlation.sessionId != sessionId) return false
        val retained = runCatching { retainReceiptForCommands(correlation) }.getOrDefault(false)
        if (!retained || closed.get()) {
            withdrawReceiptSnapshotForCommands(correlation)
            return false
        }
        return true
    }

    override fun withdrawReceiptSnapshotForCommands(correlation: UiSnapshotCorrelation) {
        if (correlation.sessionId == sessionId) {
            runCatching { withdrawReceiptForCommands(correlation) }
        }
    }

    override fun latestSnapshotFailure(): AccessibilitySnapshotFailure? = if (closed.get()) {
        null
    } else {
        runCatching(snapshotFailure).getOrNull()
    }

    override fun captureVisualSnapshot(): VisualUiCaptureResult = if (closed.get()) {
        VisualUiCaptureResult.Failure("accessibility_session_closed")
    } else {
        runCatching(visualCapture)
            .getOrElse { VisualUiCaptureResult.Failure("visual_capture_failed") }
    }

    override fun requestSensitiveActionApproval(
        command: AccessibilityCommand,
        request: AccessibilityConfirmationRequest,
    ): AccessibilitySensitiveActionApproval = if (closed.get()) {
        AccessibilitySensitiveActionApproval.Unavailable
    } else {
        runCatching { sensitiveActionApproval(command, request) }
            .getOrDefault(AccessibilitySensitiveActionApproval.Unavailable)
    }

    override fun submit(
        command: AccessibilityCommand,
        approval: AccessibilityUserApproval?,
        callback: AccessibilityCommandCallback,
    ): Boolean {
        if (closed.get()) return false
        return try {
            commandExecutor.execute(QueuedCommand(command, approval, callback))
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun submitGuarded(
        command: AccessibilityCommand,
        cancelled: () -> Boolean,
        callback: AccessibilityCommandCallback,
    ): Boolean {
        if (closed.get()) return false
        if (runCatching(cancelled).getOrDefault(true)) {
            runCatching { callback.onResult(commandCancelled(command.idempotencyKey)) }
            return true
        }
        return try {
            commandExecutor.execute(QueuedCommand(command, null, callback, cancelled))
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    override fun submitApprovedRetry(
        command: AccessibilityCommand,
        approval: AccessibilityUserApproval,
        callback: AccessibilityCommandCallback,
    ): Boolean {
        if (closed.get() || commandWorker.get() !== Thread.currentThread()) return false
        val result = runCatching { executor.execute(command, approval) }
            .getOrElse { unexpectedFailure(command.idempotencyKey) }
        runCatching { callback.onResult(result) }
        return true
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        wakeSnapshotWaiters()
        // shutdownNow proves only that its returned tasks never started. Running tasks retain
        // their real callback; interrupting them is not itself a physical-completion receipt.
        val notStarted = commandExecutor.shutdownNow()
        commandWorker.set(null)
        notStarted.forEach { (it as? QueuedCommand)?.closeBeforeRun() }
    }

    private inner class QueuedCommand(
        private val command: AccessibilityCommand,
        private val approval: AccessibilityUserApproval?,
        private val callback: AccessibilityCommandCallback,
        private val cancelled: (() -> Boolean)? = null,
    ) : Runnable {
        private val claimed = AtomicBoolean(false)

        override fun run() {
            if (!claimed.compareAndSet(false, true)) return
            // Covers a worker which took a task just before shutdownNow drained the queue.
            if (closed.get()) {
                deliverSessionClosed()
                return
            }
            commandWorker.compareAndSet(null, Thread.currentThread())
            if (cancelled != null && runCatching(cancelled).getOrDefault(true)) {
                runCatching { callback.onResult(commandCancelled(command.idempotencyKey)) }
                return
            }
            // A guarded caller may probe another lifecycle while close runs concurrently.
            // Do not enter the domain after that probe if this session has already ended.
            if (cancelled != null && closed.get()) {
                deliverSessionClosed()
                return
            }
            val result = runCatching { executor.execute(command, approval) }
                .getOrElse { unexpectedFailure(command.idempotencyKey) }
            runCatching { callback.onResult(result) }
        }

        fun closeBeforeRun() {
            if (claimed.compareAndSet(false, true)) deliverSessionClosed()
        }

        private fun deliverSessionClosed() {
            runCatching { callback.onResult(sessionClosed(command.idempotencyKey)) }
        }
    }

    private fun sessionClosed(key: AccessibilityIdempotencyKey): AccessibilityExecutionResult =
        notExecuted(key, "accessibility_session_closed")

    private fun commandCancelled(key: AccessibilityIdempotencyKey): AccessibilityExecutionResult =
        notExecuted(key, "accessibility_command_cancelled")

    private fun notExecuted(key: AccessibilityIdempotencyKey, code: String): AccessibilityExecutionResult =
        AccessibilityExecutionResult(
            idempotencyKey = key,
            status = AccessibilityExecutionStatus.REJECTED,
            replayed = false,
            observation = null,
            postcondition = AccessibilityPostcondition(
                kind = AccessibilityPostconditionKind.REQUEST_NOT_EXECUTED,
                status = AccessibilityPostconditionStatus.FAILED,
                detailCode = code,
                before = null,
                after = null,
                trust = UiDataTrust.LOCAL_SYSTEM,
            ),
            errorCode = code,
        )

    private fun unexpectedFailure(key: AccessibilityIdempotencyKey): AccessibilityExecutionResult =
        AccessibilityExecutionResult(
            idempotencyKey = key,
            status = AccessibilityExecutionStatus.FAILED,
            replayed = false,
            observation = null,
            postcondition = AccessibilityPostcondition(
                kind = AccessibilityPostconditionKind.REQUEST_NOT_EXECUTED,
                status = AccessibilityPostconditionStatus.FAILED,
                detailCode = "session_execution_failed",
                before = null,
                after = null,
                trust = UiDataTrust.LOCAL_SYSTEM,
            ),
            errorCode = "session_execution_failed",
        )

    companion object {
        const val DEFAULT_QUEUE_CAPACITY = 64
        const val MAX_QUEUE_CAPACITY = 256
    }
}
