package ai.hans.standard.automations

import java.time.Duration
import java.time.Instant

data class CodexAutomationExecutionRequest(
    val runKey: AutomationRunKey,
    val definitionRevision: Long,
    val target: CodexAutomationTarget,
    val instruction: String,
    val requirements: AutomationRequirements = AutomationRequirements(),
    val idempotencyKey: String = runKey.stableIdempotencyKey(),
) {
    init {
        require(definitionRevision >= 1)
        require(instruction.isNotBlank() && instruction.length <= 32_768)
        require(idempotencyKey.length in 16..256)
    }

    val existingThreadId: String?
        get() = (target as? CodexAutomationTarget.ThreadBound)?.threadId

    val requestsIndependentThread: Boolean
        get() = target == CodexAutomationTarget.Independent
}

data class AutomationExecutionEnvironment(
    val availableCapabilities: Set<AutomationCapabilityId>,
    val grantedPermissions: Set<AutomationPermissionId>,
    val codexAuthenticated: Boolean,
    val networkAvailable: Boolean,
    val deviceUnlocked: Boolean,
    /** Exact run keys with a still-valid, durable confirmation receipt. */
    val confirmedRuns: Set<AutomationRunKey> = emptySet(),
)

sealed interface AutomationAuthorizationDecision {
    data object Allowed : AutomationAuthorizationDecision

    /** A recoverable unmet precondition. Its public code must not contain private task content. */
    data class Deferred(
        val errorCode: String,
        val retryAfter: Duration = Duration.ofMinutes(15),
    ) : AutomationAuthorizationDecision {
        init {
            require(errorCode.matches(SAFE_ERROR_CODE))
            require(!retryAfter.isNegative && !retryAfter.isZero)
            require(retryAfter <= Duration.ofDays(7))
        }
    }

    private companion object {
        val SAFE_ERROR_CODE = Regex("[a-z][a-z0-9_]{2,95}")
    }
}

fun interface AutomationAuthorizationProvider {
    fun authorize(
        definition: AutomationDefinition,
        runKey: AutomationRunKey,
    ): AutomationAuthorizationDecision
}

/** Builds a fail-closed authorization provider from a freshly probed runtime snapshot. */
class EnvironmentAutomationAuthorizationProvider(
    private val environmentSource: () -> AutomationExecutionEnvironment,
) : AutomationAuthorizationProvider {
    override fun authorize(
        definition: AutomationDefinition,
        runKey: AutomationRunKey,
    ): AutomationAuthorizationDecision {
        val environment = environmentSource()
        val requirements = definition.requirements
        return when {
            definition.timingPolicy == AutomationTimingPolicy.USER_VISIBLE_EXACT &&
                AutomationPermissionId.SCHEDULE_EXACT_ALARM !in environment.grantedPermissions ->
                AutomationAuthorizationDecision.Deferred("exact_alarm_access_required")
            !environment.availableCapabilities.containsAll(requirements.requiredCapabilities) ->
                AutomationAuthorizationDecision.Deferred("capability_unavailable")
            !environment.grantedPermissions.containsAll(requirements.requiredPermissions) ->
                AutomationAuthorizationDecision.Deferred("permission_required")
            requirements.requiresCodexAuthentication && !environment.codexAuthenticated ->
                AutomationAuthorizationDecision.Deferred("codex_login_required")
            requirements.requiresNetwork && !environment.networkAvailable ->
                AutomationAuthorizationDecision.Deferred(
                    "network_offline",
                    retryAfter = Duration.ofMinutes(5),
                )
            requirements.requiresUnlockedDevice && !environment.deviceUnlocked ->
                AutomationAuthorizationDecision.Deferred("device_unlock_required")
            requirements.confirmationPolicy == AutomationConfirmationPolicy.EVERY_RUN &&
                runKey !in environment.confirmedRuns ->
                AutomationAuthorizationDecision.Deferred("user_confirmation_required")
            else -> AutomationAuthorizationDecision.Allowed
        }
    }
}

sealed interface CodexAutomationExecutionOutcome {
    data object Succeeded : CodexAutomationExecutionOutcome

    data class RetryableFailure(val errorCode: String) : CodexAutomationExecutionOutcome {
        init {
            require(errorCode.matches(SAFE_ERROR_CODE))
        }
    }

    data class PermanentFailure(val errorCode: String) : CodexAutomationExecutionOutcome {
        init {
            require(errorCode.matches(SAFE_ERROR_CODE))
        }
    }

    private companion object {
        val SAFE_ERROR_CODE = Regex("[a-z][a-z0-9_]{2,95}")
    }
}

fun interface AutomationHeartbeat {
    /** Returns false once ownership was lost; the executor must then stop producing side effects. */
    fun beat(): Boolean

    /**
     * Marks the boundary where a Codex turn is accepted or its request is already in flight. Once
     * this succeeds, a retryable-looking unknown outcome must never be blindly redispatched.
     */
    fun markExternalDispatchStarted(): Boolean = beat()

    /**
     * Removes the dispatch fence only when the same owned lease received an authoritative
     * pre-dispatch rejection. Timeouts, transport failures and unknown outcomes must not call it.
     */
    fun clearExternalDispatchAfterProvenRejection(): Boolean = false

    /** Registers a fast, non-blocking wakeup for gateway waits and child-process cancellation. */
    fun onCancellation(listener: () -> Unit): AutoCloseable = AutoCloseable {}

    /** Caps a blocking wait by the enclosing JobScheduler cycle's monotonic deadline. */
    fun boundWaitMillis(proposedMillis: Long): Long = proposedMillis
}

interface CodexAutomationExecutor {
    /**
     * Executes without logging [CodexAutomationExecutionRequest.instruction]. Implementations call
     * [heartbeat] immediately before their first external side effect and during long work. They
     * must stop without starting another side effect as soon as it returns false.
     */
    fun execute(
        request: CodexAutomationExecutionRequest,
        heartbeat: AutomationHeartbeat,
    ): CodexAutomationExecutionOutcome
}

fun interface AutomationInstantSource {
    fun now(): Instant
}

fun interface AutomationLeaseTokenSource {
    fun next(): AutomationLeaseToken
}

enum class AutomationRunAttemptStatus {
    NO_RUN_AVAILABLE,
    SUCCEEDED,
    RETRY_SCHEDULED,
    FAILED_TERMINAL,
    DEFERRED,
    OWNERSHIP_LOST,
}

data class AutomationRunAttemptResult(
    val status: AutomationRunAttemptStatus,
    val runKey: AutomationRunKey? = null,
)

class AutomationExecutionRunner(
    private val storage: AutomationStorage,
    private val executor: CodexAutomationExecutor,
    private val instantSource: AutomationInstantSource,
    private val tokenSource: AutomationLeaseTokenSource,
    private val workerId: AutomationWorkerId,
    private val bootSessionId: AutomationBootSessionId,
    private val authorizationProvider: AutomationAuthorizationProvider,
    private val leaseDuration: Duration = Duration.ofMinutes(2),
) {
    init {
        require(!leaseDuration.isNegative && !leaseDuration.isZero)
        require(leaseDuration <= Duration.ofHours(24))
    }

    fun runNext(
        cancellationRequested: () -> Boolean = { false },
        cancellationRegistration: ((() -> Unit) -> AutoCloseable) = { AutoCloseable {} },
        waitBound: (Long) -> Long = { it },
    ): AutomationRunAttemptResult {
        val claim = storage.acquireNextLease(
            owner = workerId,
            token = tokenSource.next(),
            bootSessionId = bootSessionId,
            now = instantSource.now(),
            leaseDuration = leaseDuration,
        ) ?: return AutomationRunAttemptResult(AutomationRunAttemptStatus.NO_RUN_AVAILABLE)

        val request = CodexAutomationExecutionRequest(
            runKey = claim.run.key,
            definitionRevision = claim.definition.revision,
            target = claim.definition.target,
            instruction = claim.definition.instruction,
            requirements = claim.definition.requirements,
        )
        val authorization = try {
            if (cancellationRequested()) {
                AutomationAuthorizationDecision.Deferred(
                    errorCode = "job_execution_stopped",
                    retryAfter = Duration.ofMinutes(1),
                )
            } else {
                authorizationProvider.authorize(claim.definition, claim.run.key)
            }
        } catch (_: RuntimeException) {
            AutomationAuthorizationDecision.Deferred(
                errorCode = "authorization_probe_failed",
                retryAfter = Duration.ofMinutes(5),
            )
        }
        when (authorization) {
            AutomationAuthorizationDecision.Allowed -> Unit
            is AutomationAuthorizationDecision.Deferred -> {
                val deferredAt = instantSource.now()
                val result = storage.completeLease(
                    key = claim.run.key,
                    token = claim.lease.token,
                    now = deferredAt,
                    completion = AutomationCompletion.Deferred(
                        errorCode = authorization.errorCode,
                        retryAt = deferredAt.plus(authorization.retryAfter),
                    ),
                )
                return AutomationRunAttemptResult(
                    status = if (result == AutomationCompletionResult.DEFERRED) {
                        AutomationRunAttemptStatus.DEFERRED
                    } else {
                        AutomationRunAttemptStatus.OWNERSHIP_LOST
                    },
                    runKey = claim.run.key,
                )
            }
        }
        var externalDispatchStarted = false
        val heartbeat = object : AutomationHeartbeat {
            override fun beat(): Boolean =
                !cancellationRequested() && storage.heartbeat(
                    key = claim.run.key,
                    token = claim.lease.token,
                    now = instantSource.now(),
                    leaseDuration = leaseDuration,
                ) == AutomationHeartbeatResult.EXTENDED

            override fun markExternalDispatchStarted(): Boolean {
                if (cancellationRequested()) return false
                val marked = storage.markDispatchFence(
                    key = claim.run.key,
                    token = claim.lease.token,
                    now = instantSource.now(),
                    leaseDuration = leaseDuration,
                ) in setOf(
                    AutomationDispatchFenceMarkResult.MARKED,
                    AutomationDispatchFenceMarkResult.ALREADY_MARKED,
                )
                if (marked) externalDispatchStarted = true
                return marked
            }

            override fun clearExternalDispatchAfterProvenRejection(): Boolean {
                val cleared = storage.clearDispatchFenceAfterRejected(
                    key = claim.run.key,
                    token = claim.lease.token,
                    now = instantSource.now(),
                ) in setOf(
                    AutomationDispatchFenceClearResult.CLEARED,
                    AutomationDispatchFenceClearResult.NOT_MARKED,
                )
                if (cleared) externalDispatchStarted = false
                return cleared
            }

            override fun onCancellation(listener: () -> Unit): AutoCloseable =
                cancellationRegistration(listener)

            override fun boundWaitMillis(proposedMillis: Long): Long =
                waitBound(proposedMillis).coerceIn(1L, proposedMillis.coerceAtLeast(1L))
        }
        if (!heartbeat.beat()) {
            if (!cancellationRequested()) {
                return AutomationRunAttemptResult(
                    AutomationRunAttemptStatus.OWNERSHIP_LOST,
                    claim.run.key,
                )
            }
            val stoppedAt = instantSource.now()
            val deferred = storage.completeLease(
                key = claim.run.key,
                token = claim.lease.token,
                now = stoppedAt,
                completion = AutomationCompletion.Deferred(
                    errorCode = "job_execution_stopped",
                    retryAt = stoppedAt.plus(Duration.ofMinutes(1)),
                ),
            )
            return AutomationRunAttemptResult(
                if (deferred == AutomationCompletionResult.DEFERRED) {
                    AutomationRunAttemptStatus.DEFERRED
                } else {
                    AutomationRunAttemptStatus.OWNERSHIP_LOST
                },
                claim.run.key,
            )
        }
        val outcome = try {
            executor.execute(request, heartbeat)
        } catch (_: RuntimeException) {
            CodexAutomationExecutionOutcome.RetryableFailure("executor_exception")
        }
        val completion = when {
            cancellationRequested() && outcome is CodexAutomationExecutionOutcome.RetryableFailure &&
                !externalDispatchStarted -> {
                val stoppedAt = instantSource.now()
                AutomationCompletion.Deferred(
                    errorCode = "job_execution_stopped",
                    retryAt = stoppedAt.plus(Duration.ofMinutes(1)),
                )
            }
            outcome is CodexAutomationExecutionOutcome.RetryableFailure &&
                externalDispatchStarted ->
                AutomationCompletion.PermanentFailure("automation_outcome_ambiguous")
            outcome == CodexAutomationExecutionOutcome.Succeeded -> AutomationCompletion.Succeeded
            outcome is CodexAutomationExecutionOutcome.RetryableFailure ->
                AutomationCompletion.RetryableFailure(outcome.errorCode)
            outcome is CodexAutomationExecutionOutcome.PermanentFailure ->
                AutomationCompletion.PermanentFailure(outcome.errorCode)
            else -> AutomationCompletion.PermanentFailure("automation_outcome_ambiguous")
        }
        return when (
            storage.completeLease(
                key = claim.run.key,
                token = claim.lease.token,
                now = instantSource.now(),
                completion = completion,
            )
        ) {
            AutomationCompletionResult.COMPLETED -> AutomationRunAttemptResult(
                AutomationRunAttemptStatus.SUCCEEDED,
                claim.run.key,
            )
            AutomationCompletionResult.RETRY_SCHEDULED -> AutomationRunAttemptResult(
                AutomationRunAttemptStatus.RETRY_SCHEDULED,
                claim.run.key,
            )
            AutomationCompletionResult.DEFERRED -> AutomationRunAttemptResult(
                AutomationRunAttemptStatus.DEFERRED,
                claim.run.key,
            )
            AutomationCompletionResult.FAILED_TERMINAL -> AutomationRunAttemptResult(
                AutomationRunAttemptStatus.FAILED_TERMINAL,
                claim.run.key,
            )
            AutomationCompletionResult.LEASE_NOT_FOUND,
            AutomationCompletionResult.TOKEN_MISMATCH,
            AutomationCompletionResult.LEASE_EXPIRED,
            AutomationCompletionResult.INVALID_TIME,
            -> AutomationRunAttemptResult(
                AutomationRunAttemptStatus.OWNERSHIP_LOST,
                claim.run.key,
            )
        }
    }
}
