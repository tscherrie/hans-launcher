package ai.hans.standard.codex

data class ActiveTurn(
    val threadId: String,
    val turnId: String,
    val effectiveOptions: DispatchOptions,
) {
    init {
        requireOpaqueId(threadId, "Thread id")
        requireOpaqueId(turnId, "Turn id")
    }
}

enum class OptionDifference {
    MODEL,
    EFFORT,
    SERVICE_TIER,
    APPROVAL_POLICY,
    PERMISSIONS_PROFILE,
    SANDBOX,
    WORKING_DIRECTORY,
    PERSONALITY,
    REASONING_SUMMARY,
}

sealed interface DispatchPlan {
    data class StartTurn(val request: EncodedRequest) : DispatchPlan
    data class Steer(val request: EncodedRequest) : DispatchPlan

    /**
     * The caller must interrupt or await the active turn, then call turn/start.
     * Sending turn/start immediately is intentionally not done here because
     * App Server may interpret it as same-turn steering while a turn is active.
     */
    data class RequiresNewTurn(
        val differences: Set<OptionDifference>,
        val desiredOptions: DispatchOptions,
    ) : DispatchPlan
}

object DispatchPolicy {
    fun plan(
        id: RequestId,
        threadId: String,
        activeTurn: ActiveTurn?,
        input: List<CodexInput>,
        desiredOptions: DispatchOptions,
        catalog: ModelCatalog,
        clientUserMessageId: String? = null,
    ): DispatchPlan {
        catalog.requireSupported(desiredOptions)
        if (activeTurn == null) {
            return DispatchPlan.StartTurn(
                AppServerRequests.turnStart(
                    id = id,
                    threadId = threadId,
                    input = input,
                    options = desiredOptions,
                    clientUserMessageId = clientUserMessageId,
                ),
            )
        }
        require(activeTurn.threadId == threadId) {
            "Active turn belongs to a different thread"
        }
        val differences = differences(activeTurn.effectiveOptions, desiredOptions)
        if (differences.isNotEmpty()) {
            return DispatchPlan.RequiresNewTurn(
                differences = differences,
                desiredOptions = desiredOptions,
            )
        }
        return DispatchPlan.Steer(
            AppServerRequests.turnSteer(
                id = id,
                activeTurn = activeTurn,
                input = input,
                clientUserMessageId = clientUserMessageId,
            ),
        )
    }

    private fun differences(
        effective: DispatchOptions,
        desired: DispatchOptions,
    ): Set<OptionDifference> = buildSet {
        if (effective.model != desired.model) add(OptionDifference.MODEL)
        if (effective.effort != desired.effort) add(OptionDifference.EFFORT)
        if (effective.serviceTier != desired.serviceTier) add(OptionDifference.SERVICE_TIER)
        if (effective.approvalPolicy != desired.approvalPolicy) {
            add(OptionDifference.APPROVAL_POLICY)
        }
        if (effective.permissionsProfile != desired.permissionsProfile) {
            add(OptionDifference.PERMISSIONS_PROFILE)
        }
        if (effective.sandbox != desired.sandbox) add(OptionDifference.SANDBOX)
        if (effective.cwd != desired.cwd) add(OptionDifference.WORKING_DIRECTORY)
        if (effective.personality != desired.personality) add(OptionDifference.PERSONALITY)
        if (effective.reasoningSummary != desired.reasoningSummary) {
            add(OptionDifference.REASONING_SUMMARY)
        }
    }
}
