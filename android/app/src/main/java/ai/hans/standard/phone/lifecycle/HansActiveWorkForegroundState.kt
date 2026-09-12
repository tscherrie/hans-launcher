package ai.hans.standard.phone.lifecycle

enum class HansActiveWorkForegroundEvent { PROTECTED, STOPPED, PROMOTION_REJECTED, TIMED_OUT }

/** Contains no task content, remote identity, pairing material, or Android permission claims. */
data class HansActiveWorkForegroundSnapshot(
    val sequence: Long = 0L,
    val revision: Long = 0L,
    val protectedReasons: Set<HansActiveWorkReason> = emptySet(),
    val event: HansActiveWorkForegroundEvent = HansActiveWorkForegroundEvent.STOPPED,
    val remoteStopRequestSequence: Long = 0L,
)

fun interface HansActiveWorkForegroundObserver {
    fun onForegroundChanged(snapshot: HansActiveWorkForegroundSnapshot)
}
