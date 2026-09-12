package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.CurrentSemanticUiSnapshotSource
import ai.hans.standard.phone.accessibility.SemanticNodeHandle
import ai.hans.standard.phone.accessibility.SemanticUiSnapshot
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import java.util.LinkedHashMap

/**
 * One atomically published semantic frame and its app-private Android locators.
 * No framework node or pixel object is retained.
 */
internal class AndroidAccessibilityFrame(
    val snapshot: SemanticUiSnapshot,
    val displayId: Int,
    locators: List<AndroidNodeLocator>,
) {
    val correlation: UiSnapshotCorrelation = snapshot.correlation
    val locators: List<AndroidNodeLocator> = locators.map { locator ->
        locator.copy(
            structuralPath = locator.structuralPath.toList(),
            fingerprint = locator.fingerprint.copy(actions = locator.fingerprint.actions.toSet()),
        )
    }
    val byOrdinal: Map<Int, AndroidNodeLocator> = this.locators
        .associateBy(AndroidNodeLocator::nodeOrdinal)

    init {
        require(displayId >= 0)
        require(this.locators.size == byOrdinal.size)
        require(this.locators.all { locator ->
            locator.displayId == displayId &&
                locator.windowId == correlation.windowId.value &&
                snapshot.resolve(SemanticNodeHandle(correlation, locator.nodeOrdinal)) != null
        })
    }
}

/**
 * RAM-only correlation retention. Command frames are retained only for an explicit observation.
 * Post-action receipts remain separate and shorter-lived unless explicitly promoted while
 * returning that exact verified frame as a usable follow-up observation.
 */
internal class RetainedAccessibilityFrameStore(
    private val elapsedRealtimeMillis: () -> Long,
    private val commandCapacity: Int = DEFAULT_COMMAND_CAPACITY,
    private val commandTtlMillis: Long = DEFAULT_COMMAND_TTL_MILLIS,
    private val receiptCapacity: Int = DEFAULT_RECEIPT_CAPACITY,
    private val receiptTtlMillis: Long = DEFAULT_RECEIPT_TTL_MILLIS,
    private val totalRetainedCapacity: Int = DEFAULT_TOTAL_RETAINED_CAPACITY,
) : CurrentSemanticUiSnapshotSource {
    private val lock = Any()
    private var currentFrame: AndroidAccessibilityFrame? = null
    private val commandFrames = LinkedHashMap<UiSnapshotCorrelation, TimedFrame>(16, 0.75f, true)
    private val receiptFrames = LinkedHashMap<UiSnapshotCorrelation, TimedFrame>(16, 0.75f, true)

    init {
        require(commandCapacity in 1..32)
        require(commandTtlMillis in 1_000L..5 * 60_000L)
        require(receiptCapacity in 1..32)
        require(receiptTtlMillis in 500L..60_000L)
        require(totalRetainedCapacity in 1..32)
    }

    override fun current(): SemanticUiSnapshot? = synchronized(lock) {
        currentFrame?.snapshot
    }

    fun currentFrame(): AndroidAccessibilityFrame? = synchronized(lock) { currentFrame }

    override fun snapshotForCorrelation(
        correlation: UiSnapshotCorrelation,
    ): SemanticUiSnapshot? = commandFrame(correlation)?.snapshot

    fun publish(frame: AndroidAccessibilityFrame) = synchronized(lock) {
        currentFrame = frame
        purgeExpiredLocked(elapsedRealtimeMillis())
    }

    /** Retains only the exact current frame, closing refresh/Accessibility-event races. */
    fun retainCurrentForCommand(correlation: UiSnapshotCorrelation): Boolean = synchronized(lock) {
        val now = elapsedRealtimeMillis()
        purgeExpiredLocked(now)
        val frame = currentFrame?.takeIf { it.correlation == correlation } ?: return false
        commandFrames[correlation] = TimedFrame(frame, now + commandTtlMillis)
        trimLocked(commandFrames, commandCapacity)
        trimTotalLocked(
            protectedKind = RetainedFrameKind.COMMAND,
            protectedCorrelation = correlation,
        )
        commandFrames.containsKey(correlation)
    }

    fun commandFrame(correlation: UiSnapshotCorrelation): AndroidAccessibilityFrame? =
        synchronized(lock) {
            val now = elapsedRealtimeMillis()
            purgeExpiredLocked(now)
            commandFrames[correlation]?.frame
        }

    /**
     * Extends only an already retained exact command frame for a visible one-time confirmation.
     * The small execution grace prevents the 60-second prompt deadline racing the action retry.
     */
    fun extendCommandFrameForConfirmation(
        correlation: UiSnapshotCorrelation,
        validityMillis: Long = DEFAULT_CONFIRMATION_RETENTION_MILLIS,
    ): Boolean = synchronized(lock) {
        require(validityMillis in 1_000L..MAX_CONFIRMATION_RETENTION_MILLIS)
        val now = elapsedRealtimeMillis()
        purgeExpiredLocked(now)
        val retained = commandFrames[correlation] ?: return false
        commandFrames[correlation] = retained.copy(expiresAtMillis = now + validityMillis)
        true
    }

    /** Pins an exact post-action frame only long enough to verify the action receipt. */
    fun retainCurrentForReceipt(correlation: UiSnapshotCorrelation): Boolean = synchronized(lock) {
        val now = elapsedRealtimeMillis()
        purgeExpiredLocked(now)
        val frame = currentFrame?.takeIf { it.correlation == correlation } ?: return false
        receiptFrames[correlation] = TimedFrame(frame, now + receiptTtlMillis)
        trimLocked(receiptFrames, receiptCapacity)
        trimTotalLocked(
            protectedKind = RetainedFrameKind.RECEIPT,
            protectedCorrelation = correlation,
        )
        receiptFrames.containsKey(correlation)
    }

    fun receiptFrame(correlation: UiSnapshotCorrelation): AndroidAccessibilityFrame? =
        synchronized(lock) {
            val now = elapsedRealtimeMillis()
            purgeExpiredLocked(now)
            receiptFrames[correlation]?.frame
        }

    /**
     * Explicit observation boundary: only the exact verified receipt being returned to the caller
     * becomes usable for follow-up semantic commands. Never substitutes the latest frame, extends
     * a previously consumed receipt, or captures another screen. Existing retention caps apply.
     */
    fun promoteReceiptForCommands(correlation: UiSnapshotCorrelation): Boolean = synchronized(lock) {
        val now = elapsedRealtimeMillis()
        purgeExpiredLocked(now)
        if (currentFrame?.correlation?.sessionId != correlation.sessionId) return false
        val receipt = receiptFrames.remove(correlation) ?: return false
        // A concurrent inspect owns its existing lease; do not replace or later withdraw it.
        if (commandFrames.containsKey(correlation)) return false
        commandFrames[correlation] = TimedFrame(
            receipt.frame, now + commandTtlMillis, promotedReceipt = true,
        )
        trimLocked(commandFrames, commandCapacity)
        trimTotalLocked(
            protectedKind = RetainedFrameKind.COMMAND,
            protectedCorrelation = correlation,
        )
        commandFrames.containsKey(correlation)
    }

    /** Revoke an undelivered promotion without removing an independently inspected frame. */
    fun withdrawReceiptPromotion(correlation: UiSnapshotCorrelation) = synchronized(lock) {
        if (commandFrames[correlation]?.promotedReceipt == true) {
            commandFrames.remove(correlation)
        }
        Unit
    }

    fun clearCurrent() = synchronized(lock) {
        currentFrame = null
    }

    /** Service rebind/disconnect boundary: no correlation survives it. */
    fun clearAll() = synchronized(lock) {
        currentFrame = null
        commandFrames.clear()
        receiptFrames.clear()
    }

    internal fun retainedCommandCount(): Int = synchronized(lock) {
        purgeExpiredLocked(elapsedRealtimeMillis())
        commandFrames.size
    }

    private fun purgeExpiredLocked(now: Long) {
        commandFrames.entries.removeAll { now >= it.value.expiresAtMillis }
        receiptFrames.entries.removeAll { now >= it.value.expiresAtMillis }
    }

    private fun trimLocked(
        frames: LinkedHashMap<UiSnapshotCorrelation, TimedFrame>,
        capacity: Int,
    ) {
        while (frames.size > capacity) {
            val eldest = frames.entries.iterator()
            if (!eldest.hasNext()) return
            eldest.next()
            eldest.remove()
        }
    }

    private fun trimTotalLocked(
        protectedKind: RetainedFrameKind,
        protectedCorrelation: UiSnapshotCorrelation,
    ) {
        while (commandFrames.size + receiptFrames.size > totalRetainedCapacity) {
            val removed = when (protectedKind) {
                RetainedFrameKind.COMMAND ->
                    removeEldestExcept(receiptFrames, null) ||
                        removeEldestExcept(commandFrames, protectedCorrelation)
                RetainedFrameKind.RECEIPT ->
                    removeEldestExcept(receiptFrames, protectedCorrelation) ||
                        removeEldestExcept(commandFrames, null)
            }
            if (!removed) return
        }
    }

    private fun removeEldestExcept(
        frames: LinkedHashMap<UiSnapshotCorrelation, TimedFrame>,
        protectedCorrelation: UiSnapshotCorrelation?,
    ): Boolean {
        val iterator = frames.entries.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().key == protectedCorrelation) continue
            iterator.remove()
            return true
        }
        return false
    }

    private enum class RetainedFrameKind {
        COMMAND,
        RECEIPT,
    }

    private data class TimedFrame(
        val frame: AndroidAccessibilityFrame,
        val expiresAtMillis: Long,
        val promotedReceipt: Boolean = false,
    )

    private companion object {
        const val DEFAULT_COMMAND_CAPACITY = 8
        const val DEFAULT_COMMAND_TTL_MILLIS = 60_000L
        const val DEFAULT_CONFIRMATION_RETENTION_MILLIS = 65_000L
        const val MAX_CONFIRMATION_RETENTION_MILLIS = 2 * 60_000L
        const val DEFAULT_RECEIPT_CAPACITY = 8
        const val DEFAULT_RECEIPT_TTL_MILLIS = 5_000L
        const val DEFAULT_TOTAL_RETAINED_CAPACITY = 8
    }
}
