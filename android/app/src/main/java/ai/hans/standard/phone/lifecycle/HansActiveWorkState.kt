package ai.hans.standard.phone.lifecycle

/**
 * User-visible work which must remain owned while Hans is no longer the
 * foreground application.
 *
 * The reasons deliberately have independent lifecycles. A Codex turn
 * completing must not tear down speech which is still playing, and speech
 * completing must not tear down an active Codex turn.
 */
enum class HansActiveWorkReason(
    internal val wireBit: Int,
) {
    CODEX_ACTIVE(1 shl 0),
    SPEECH_ACTIVE(1 shl 1),
    REMOTE_CONTROL(1 shl 2),
    ;

    internal companion object {
        val knownWireMask: Int = entries.fold(0) { mask, reason -> mask or reason.wireBit }

        fun fromWireMask(mask: Int): Set<HansActiveWorkReason>? {
            if (mask <= 0 || mask and knownWireMask != mask) return null
            return entries.filterTo(linkedSetOf()) { reason -> mask and reason.wireBit != 0 }
        }

        fun toWireMask(reasons: Set<HansActiveWorkReason>): Int =
            reasons.fold(0) { mask, reason -> mask or reason.wireBit }
    }
}

enum class HansActiveWorkUpdateStatus {
    APPLIED,
    UNCHANGED,
    REJECTED,
}

data class HansActiveWorkUpdate(
    val status: HansActiveWorkUpdateStatus,
    val activeReasons: Set<HansActiveWorkReason>,
    val revision: Long = 0L,
)

internal data class HansActiveWorkSnapshot(
    val activeReasons: Set<HansActiveWorkReason>,
    val revision: Long,
)

/** Pure reconciliation used after the Android service has acknowledged a foreground start. */
internal object ActiveWorkServiceReconciler {
    fun provisionalReasons(
        alreadyPublished: Set<HansActiveWorkReason>,
        requested: Set<HansActiveWorkReason>?,
        fallback: Set<HansActiveWorkReason>,
    ): Set<HansActiveWorkReason> = alreadyPublished.ifEmpty {
        requested.orEmpty().ifEmpty { fallback }
    }

    fun targetSnapshot(
        desired: HansActiveWorkSnapshot,
        requested: Set<HansActiveWorkReason>?,
        requestedRevision: Long,
    ): HansActiveWorkSnapshot {
        val requestIsValid = !requested.isNullOrEmpty() && requestedRevision > 0L
        return when {
            desired.revision >= requestedRevision -> desired
            requestIsValid -> HansActiveWorkSnapshot(
                activeReasons = requireNotNull(requested).toSet(),
                revision = requestedRevision,
            )
            else -> desired
        }
    }
}

internal enum class ActiveWorkCommand {
    NONE,
    START,
    UPDATE,
    STOP,
}

internal data class ActiveWorkTransition(
    val previous: Set<HansActiveWorkReason>,
    val current: Set<HansActiveWorkReason>,
    val command: ActiveWorkCommand,
)

internal object ActiveWorkReasonReducer {
    fun reduce(
        current: Set<HansActiveWorkReason>,
        reason: HansActiveWorkReason,
        active: Boolean,
    ): ActiveWorkTransition {
        val next = if (active) current + reason else current - reason
        val command = when {
            next == current -> ActiveWorkCommand.NONE
            current.isEmpty() -> ActiveWorkCommand.START
            next.isEmpty() -> ActiveWorkCommand.STOP
            else -> ActiveWorkCommand.UPDATE
        }
        return ActiveWorkTransition(
            previous = current.toSet(),
            current = next.toSet(),
            command = command,
        )
    }
}

internal fun interface ActiveWorkCommandSink {
    /** Returns false when Android rejected or could not apply the command. */
    fun apply(
        command: ActiveWorkCommand,
        reasons: Set<HansActiveWorkReason>,
        revision: Long,
    ): Boolean
}

/**
 * Thread-safe owner for the process-local reason set.
 *
 * A failed Android dispatch is transactional: the in-memory state remains at
 * the last state which Android accepted, allowing a caller to retry honestly.
 */
internal class HansActiveWorkCoordinator(
    private val sink: ActiveWorkCommandSink,
) {
    private val lock = Any()
    private var activeReasons: Set<HansActiveWorkReason> = emptySet()
    private var revision: Long = 0L

    fun setReason(
        reason: HansActiveWorkReason,
        active: Boolean,
    ): HansActiveWorkUpdate = synchronized(lock) {
        val transition = ActiveWorkReasonReducer.reduce(activeReasons, reason, active)
        if (transition.command == ActiveWorkCommand.NONE) {
            return@synchronized HansActiveWorkUpdate(
                status = HansActiveWorkUpdateStatus.UNCHANGED,
                activeReasons = activeReasons.toSet(),
                revision = revision,
            )
        }

        val previousRevision = revision
        val nextRevision = previousRevision + 1L
        activeReasons = transition.current
        revision = nextRevision
        if (!sink.apply(transition.command, transition.current, nextRevision)) {
            activeReasons = transition.previous
            revision = previousRevision
            return@synchronized HansActiveWorkUpdate(
                status = HansActiveWorkUpdateStatus.REJECTED,
                activeReasons = activeReasons.toSet(),
                revision = revision,
            )
        }

        HansActiveWorkUpdate(
            status = HansActiveWorkUpdateStatus.APPLIED,
            activeReasons = activeReasons.toSet(),
            revision = revision,
        )
    }

    fun snapshot(): Set<HansActiveWorkReason> = synchronized(lock) {
        activeReasons.toSet()
    }

    fun versionedSnapshot(): HansActiveWorkSnapshot = synchronized(lock) {
        HansActiveWorkSnapshot(
            activeReasons = activeReasons.toSet(),
            revision = revision,
        )
    }

    /**
     * Reconciles process-local state after Android destroys or times out the
     * service. This is intentionally notification-free and never dispatches a
     * new service start from a teardown callback.
     */
    fun onServiceStopped(serviceRevision: Long) = synchronized(lock) {
        if (serviceRevision == revision) {
            activeReasons = emptySet()
            revision += 1L
        }
    }

    fun onServiceTimedOut() = synchronized(lock) {
        activeReasons = emptySet()
        revision += 1L
    }
}
