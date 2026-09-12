package ai.hans.standard.automations

import java.time.Instant

data class AutomationDefinitionDraft(
    val enabled: Boolean = true,
    val schedule: AutomationSchedule,
    val missedRunPolicy: MissedRunPolicy = MissedRunPolicy(),
    val retryPolicy: AutomationRetryPolicy = AutomationRetryPolicy(),
    val target: CodexAutomationTarget = CodexAutomationTarget.Independent,
    val instruction: String,
    val requirements: AutomationRequirements = AutomationRequirements(),
    val timingPolicy: AutomationTimingPolicy = AutomationTimingPolicy.RELIABLE_INEXACT,
)

sealed interface AutomationDefinitionMutationResult {
    data class Applied(val definition: AutomationDefinition) :
        AutomationDefinitionMutationResult

    data class InvalidSchedule(val errorCode: String) : AutomationDefinitionMutationResult
    data object AlreadyExists : AutomationDefinitionMutationResult
    data object NotFound : AutomationDefinitionMutationResult
    data object RevisionConflict : AutomationDefinitionMutationResult
    data object RevisionExhausted : AutomationDefinitionMutationResult
}

/** Revision-safe create/update/cancel boundary intended for settings, tools and installer flows. */
class AutomationDefinitionManager(
    private val storage: AutomationStorage,
    private val instantSource: AutomationInstantSource,
) {
    fun create(
        id: AutomationId,
        draft: AutomationDefinitionDraft,
    ): AutomationDefinitionMutationResult = storage.withAtomicAccess {
        if (storage.definition(id) != null) return@withAtomicAccess AutomationDefinitionMutationResult.AlreadyExists
        validate(draft)?.let { return@withAtomicAccess AutomationDefinitionMutationResult.InvalidSchedule(it) }
        val definition = draft.toDefinition(id, revision = 1, updatedAt = instantSource.now())
        when (storage.upsertDefinition(definition)) {
            DefinitionWriteResult.INSERTED -> AutomationDefinitionMutationResult.Applied(definition)
            DefinitionWriteResult.REJECTED_REVISION_CONFLICT,
            DefinitionWriteResult.REJECTED_STALE_REVISION,
            DefinitionWriteResult.UPDATED,
            DefinitionWriteResult.UNCHANGED,
            -> AutomationDefinitionMutationResult.AlreadyExists
        }
    }

    fun replace(
        id: AutomationId,
        expectedRevision: Long,
        draft: AutomationDefinitionDraft,
    ): AutomationDefinitionMutationResult = storage.withAtomicAccess {
        val current = storage.definition(id) ?: return@withAtomicAccess AutomationDefinitionMutationResult.NotFound
        if (current.revision != expectedRevision) {
            return@withAtomicAccess AutomationDefinitionMutationResult.RevisionConflict
        }
        if (expectedRevision == Long.MAX_VALUE) {
            return@withAtomicAccess AutomationDefinitionMutationResult.RevisionExhausted
        }
        validate(draft)?.let { return@withAtomicAccess AutomationDefinitionMutationResult.InvalidSchedule(it) }
        val replacement = draft.toDefinition(
            id,
            revision = expectedRevision + 1,
            updatedAt = instantSource.now(),
        )
        when (storage.upsertDefinition(replacement)) {
            DefinitionWriteResult.UPDATED -> AutomationDefinitionMutationResult.Applied(replacement)
            DefinitionWriteResult.REJECTED_REVISION_CONFLICT,
            DefinitionWriteResult.REJECTED_STALE_REVISION,
            DefinitionWriteResult.INSERTED,
            DefinitionWriteResult.UNCHANGED,
            -> AutomationDefinitionMutationResult.RevisionConflict
        }
    }

    fun cancel(
        id: AutomationId,
        expectedRevision: Long,
    ): AutomationCancellationResult = storage.cancelDefinition(
        id,
        expectedRevision,
        instantSource.now(),
    )

    private fun validate(draft: AutomationDefinitionDraft): String? =
        when (val parsed = RRuleParser.parse(draft.schedule.rrule)) {
            is RRuleParseResult.Valid -> null
            is RRuleParseResult.Invalid -> parsed.errorCode
        }

    private fun AutomationDefinitionDraft.toDefinition(
        id: AutomationId,
        revision: Long,
        updatedAt: Instant,
    ): AutomationDefinition = AutomationDefinition(
        id = id,
        revision = revision,
        enabled = enabled,
        schedule = schedule,
        missedRunPolicy = missedRunPolicy,
        retryPolicy = retryPolicy,
        target = target,
        instruction = instruction,
        updatedAt = updatedAt,
        requirements = requirements,
        timingPolicy = timingPolicy,
    )
}

data class AutomationHistoryCursor(
    val updatedAt: Instant,
    val key: AutomationRunKey,
)

data class AutomationRunHistoryPage(
    val runs: List<AutomationRun>,
    val nextCursor: AutomationHistoryCursor?,
    val hasMore: Boolean,
)

data class AutomationStatusSummary(
    val enabledDefinitions: Int,
    val disabledDefinitions: Int,
    val pendingInboxItems: Int,
    val pendingRuns: Int,
    val leasedRuns: Int,
    val retryingRuns: Int,
    val succeededRuns: Int,
    val terminalFailures: Int,
    val skippedRuns: Int,
)

class AutomationHistoryReader(
    private val storage: AutomationStorage,
) {
    fun summary(): AutomationStatusSummary {
        val snapshot = storage.snapshot()
        return AutomationStatusSummary(
            enabledDefinitions = snapshot.definitions.count { it.enabled },
            disabledDefinitions = snapshot.definitions.count { !it.enabled },
            pendingInboxItems = snapshot.inbox.size,
            pendingRuns = snapshot.runs.count { it.state == AutomationRunState.PENDING },
            leasedRuns = snapshot.runs.count { it.state == AutomationRunState.LEASED },
            retryingRuns = snapshot.runs.count { it.state == AutomationRunState.RETRY_WAIT },
            succeededRuns = snapshot.runs.count { it.state == AutomationRunState.SUCCEEDED },
            terminalFailures = snapshot.runs.count {
                it.state == AutomationRunState.FAILED_TERMINAL
            },
            skippedRuns = snapshot.runs.count { it.state == AutomationRunState.SKIPPED },
        )
    }

    fun page(
        automationId: AutomationId? = null,
        states: Set<AutomationRunState> = emptySet(),
        beforeExclusive: AutomationHistoryCursor? = null,
        limit: Int = 50,
    ): AutomationRunHistoryPage {
        require(limit in 1..200)
        val order = compareByDescending<AutomationRun> { it.updatedAt }
            .thenByDescending { it.key }
        val candidates = storage.snapshot().runs.asSequence()
            .filter { automationId == null || it.key.automationId == automationId }
            .filter { states.isEmpty() || it.state in states }
            .filter { run ->
                beforeExclusive == null ||
                    run.updatedAt < beforeExclusive.updatedAt ||
                    run.updatedAt == beforeExclusive.updatedAt && run.key < beforeExclusive.key
            }
            .sortedWith(order)
            .take(limit + 1)
            .toList()
        val hasMore = candidates.size > limit
        val runs = candidates.take(limit)
        return AutomationRunHistoryPage(
            runs = runs,
            nextCursor = runs.lastOrNull()?.let { AutomationHistoryCursor(it.updatedAt, it.key) },
            hasMore = hasMore,
        )
    }
}
