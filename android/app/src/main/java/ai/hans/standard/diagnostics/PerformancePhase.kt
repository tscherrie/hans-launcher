package ai.hans.standard.diagnostics

/** Observable session state, not a claim about which component caused elapsed time. */
internal enum class PerformancePhase(val wire: String) {
    IDLE_BETWEEN_TURNS("idle_between_turns"),
    DISPATCH_PENDING("dispatch_pending"),
    TURN_ACTIVE("turn_active"),
    WAITING_FOR_USER("waiting_for_user"),
    UNKNOWN("unknown"),
}

/** Fixed labels only: no payload, identifiers, error messages or streaming text fragments. */
internal enum class PerformanceEvent(val wire: String) {
    RECORDING_PHASE_SEED("recording_phase_seed"),
    PHASE_CHANGED("phase_changed"),
    DISPATCH_ACCEPTED("dispatch_accepted"),
    TURN_START_SEND("turn_start_send"),
    TURN_STEER_SEND("turn_steer_send"),
    INTERRUPT_SEND("interrupt_send"),
    DISPATCH_ACKNOWLEDGED("dispatch_acknowledged"),
    DISPATCH_FAILED("dispatch_failed"),
    TRANSPORT_OUTCOME_AMBIGUOUS("transport_outcome_ambiguous"),
    TURN_STARTED("turn_started"),
    TURN_COMPLETED("turn_completed"),
    SERVER_ITEM_STARTED("server_item_started"),
    SERVER_ITEM_COMPLETED("server_item_completed"),
    TOOL_RESULT_SEND("tool_result_send"),
    USER_WAIT_STARTED("user_wait_started"),
    USER_WAIT_ENDED("user_wait_ended"),
    FIRST_ASSISTANT_OUTPUT("first_assistant_output"),
}

/** Optional in-memory generation/thread fence; contains no IDs and is never exported. */
internal class PerformanceContextToken internal constructor()
