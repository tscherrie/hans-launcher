package ai.hans.standard.fastphone.routines

import java.time.Instant
import java.time.ZoneId
import java.util.Collections

/** Subscription-authenticated, text-only, restricted analysis. No phone tools or authority. */
interface PhoneRoutineAnalysisRunner : AutoCloseable {
    fun currentAccountFingerprint(): String?
    fun run(inputJson: String, expectedAccountFingerprint: String): String
    fun preemptCurrent()
    /** Called only after [run] returned/threw, before another daily run can be admitted. */
    fun acknowledgeCompletedRun() = Unit
    override fun close()
}

class PhoneRoutineAnalysisEvidence(
    val id: String,
    val packageName: String,
    val goal: String,
    allowedActions: Set<String>,
    val maxSteps: Int,
    val helperCompletionCount: Int,
) {
    val allowedActions: Set<String> = Collections.unmodifiableSet(allowedActions.toSet())
    override fun toString() = "PhoneRoutineAnalysisEvidence(private=true)"
}

class PhoneRoutineAnalysisInput internal constructor(
    val inputJson: String,
    val watermark: String,
    evidence: List<PhoneRoutineAnalysisEvidence>,
) {
    val evidence: List<PhoneRoutineAnalysisEvidence> = Collections.unmodifiableList(evidence.toList())
    override fun toString() = "PhoneRoutineAnalysisInput(evidence=${evidence.size},private=true)"
}

interface DailyPhoneRoutineClock {
    fun nowMillis(): Long
    fun localEpochDay(): Long
    fun nextLocalDayStartMillis(): Long
}

object SystemDailyPhoneRoutineClock : DailyPhoneRoutineClock {
    override fun nowMillis() = System.currentTimeMillis()
    override fun localEpochDay() = Instant.ofEpochMilli(nowMillis()).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay()
    override fun nextLocalDayStartMillis() = Instant.ofEpochMilli(nowMillis()).atZone(ZoneId.systemDefault())
        .toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}

enum class DailyPhoneRoutineLearningStatus {
    DISABLED, ACCOUNT_UNAVAILABLE, WAITING_FOR_HISTORY, READY, RUNNING, BACKOFF,
    ALREADY_ANALYZED_TODAY, NO_NEW_HISTORY, ERROR,
}

data class DailyPhoneRoutineLearningSnapshot(
    val enabled: Boolean,
    val status: DailyPhoneRoutineLearningStatus,
    val lastSuccessAtMillis: Long?,
    val candidateCount: Int,
    val errorCode: String?,
    val nextEligibleAtMillis: Long?,
    val revision: Long,
)

class DailyPhoneRoutineClaim internal constructor(
    internal val runId: String,
    val revision: Long,
    val expectedAccountFingerprint: String,
    val input: PhoneRoutineAnalysisInput,
) {
    override fun toString() = "DailyPhoneRoutineClaim(private=true)"
}

enum class DailyPhoneRoutineRunResult { COMPLETED, SKIPPED, PREEMPTED, FAILED }
