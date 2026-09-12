package ai.hans.standard.automations

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.automations.androidui.SwappableAutomationToolApprovalProvider
import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationDynamicToolsTest {
    @Test
    fun durableMutationReportsPlatformReconciliationPendingWithoutInvitingDuplicateCreate() {
        val fixture = ToolRuntimeFixture(
            cycleResult = AutomationAdapterResult.Rejected("job_scheduler_rejected"),
        )
        val result = fixture.executor(GrantingAutomationApprovalProvider(fixture.clock)).run(
            call(
                "create",
                "create-pending-reconciliation",
                createArgumentsWithoutConfirmation("automation.pending_reconciliation"),
            ),
        )

        assertTrue(result.success)
        val data = JSONObject(result.contentText).getJSONObject("data")
        assertEquals("reconciliation_pending", data.getString("scheduleState"))
        assertEquals("automation_reconciliation_pending", data.getString("warningCode"))
        assertEquals(1, fixture.storage.definitions().size)
    }

    @Test
    fun userRequestedToolDefaultsOmittedConfirmationToUnattendedCapabilityPolicy() {
        val fixture = ToolRuntimeFixture()
        val executor = fixture.executor(GrantingAutomationApprovalProvider(fixture.clock))

        assertTrue(
            executor.run(
                call(
                    "create",
                    "create-default-unattended",
                    createArgumentsWithoutConfirmation("automation.default_unattended"),
                ),
            ).success,
        )

        val requirements = checkNotNull(
            fixture.storage.definition(AutomationId("automation.default_unattended")),
        ).requirements
        assertEquals(AutomationConfirmationPolicy.CAPABILITY_POLICY, requirements.confirmationPolicy)
        assertEquals(setOf(AutomationCapabilityId.CODEX_APP_SERVER), requirements.requiredCapabilities)
        assertTrue(requirements.requiresCodexAuthentication)
        assertTrue(requirements.requiresNetwork)
    }

    @Test
    fun lowLevelRequirementsDefaultRemainsEveryRunWhileEmptyToolRequirementsAreUnattended() {
        assertEquals(
            AutomationConfirmationPolicy.EVERY_RUN,
            AutomationRequirements().confirmationPolicy,
        )
        val fixture = ToolRuntimeFixture()
        val executor = fixture.executor(GrantingAutomationApprovalProvider(fixture.clock))
        val arguments = JSONObject(createArgumentsWithoutConfirmation("automation.empty_requirements"))
        arguments.getJSONObject("definition").put("requirements", JSONObject())

        assertTrue(
            executor.run(
                call("create", "create-empty-requirements", arguments.toString()),
            ).success,
        )
        assertEquals(
            AutomationConfirmationPolicy.CAPABILITY_POLICY,
            fixture.storage.definition(AutomationId("automation.empty_requirements"))
                ?.requirements?.confirmationPolicy,
        )
    }

    @Test
    fun updateOmissionPreservesAnExplicitEveryRunPolicy() {
        val fixture = ToolRuntimeFixture()
        val executor = fixture.executor(GrantingAutomationApprovalProvider(fixture.clock))
        val id = "automation.preserve_every_run"
        assertTrue(
            executor.run(
                call(
                    "create",
                    "create-preserved-every-run",
                    createArguments(id, confirmationPolicy = AutomationConfirmationPolicy.EVERY_RUN),
                ),
            ).success,
        )

        assertTrue(
            executor.run(
                call(
                    "update",
                    "update-preserved-every-run",
                    updateArgumentsWithoutRequirements(id, expectedRevision = 1),
                ),
            ).success,
        )

        assertEquals(
            AutomationConfirmationPolicy.EVERY_RUN,
            fixture.storage.definition(AutomationId(id))?.requirements?.confirmationPolicy,
        )
    }

    @Test
    fun fullAccessCrudWorksWithoutActivityButDoesNotStartAnUnrequestedRun() {
        val fixture = ToolRuntimeFixture()
        val router = SwappableAutomationToolApprovalProvider(
            actionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
            now = fixture.clock::now,
        )
        val executor = fixture.executor(router)
        val id = "automation.full_access"
        assertTrue(executor.run(call("create", "full-create", createArguments(id))).success)
        fixture.runDispatchedCycles()
        assertEquals(0, fixture.gateway.executions)
        assertTrue(fixture.storage.snapshot().manualInvocations.isEmpty())
        assertTrue(executor.run(call("update", "full-update", updateArguments(id, 1, "Requested change"))).success)
        assertTrue(executor.run(call("disable", "full-disable", revisionArguments(id, 2))).success)
        assertTrue(executor.run(call("enable", "full-enable", revisionArguments(id, 3))).success)
        assertTrue(executor.run(call("delete", "full-delete", revisionArguments(id, 4))).success)
        assertEquals(null, fixture.storage.definition(AutomationId(id)))
        assertEquals(0, fixture.gateway.executions)
    }

    @Test
    fun fullAccessImmediateRunRetainsEveryRunConfirmationAndIdempotence() {
        val fixture = ToolRuntimeFixture()
        val router = SwappableAutomationToolApprovalProvider(
            actionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
            now = fixture.clock::now,
        )
        val executor = fixture.executor(router)
        val id = "automation.full_run"
        assertTrue(executor.run(call("create", "full-create-run",
            createArguments(id, confirmationPolicy = AutomationConfirmationPolicy.EVERY_RUN))).success)
        assertTrue(executor.run(call("run_now", "full-run-now", revisionArguments(id, 1))).success)
        fixture.runDispatchedCycles()
        val run = fixture.storage.snapshot().runs.single()
        assertEquals(AutomationRunState.RETRY_WAIT, run.state)
        assertEquals("user_confirmation_required", run.lastFailureCode)
        assertEquals(0, fixture.gateway.executions)
        val arguments = JSONObject().put("automationId", id)
            .put("scheduledAt", run.key.scheduledAt.toString()).put("expectedRevision", 1).toString()
        val unconfirmed = executor.run(call("confirm", "full-no-self-confirm", arguments))
        assertFalse(unconfirmed.success)
        assertEquals("confirmation_required", JSONObject(unconfirmed.contentText).getString("status"))
        assertTrue(fixture.storage.snapshot().confirmations.isEmpty())
        val duplicate = executor.run(call("run_now", "full-run-now", revisionArguments(id, 1)))
        assertTrue(duplicate.success)
        assertTrue(JSONObject(duplicate.contentText).getJSONObject("data").getBoolean("duplicate"))
        assertEquals(1, fixture.storage.snapshot().manualInvocations.size)
        assertEquals(0, fixture.gateway.executions)
    }

    @Test
    fun fullAccessStillRejectsReceiptsForOtherCallsOperationsArgumentsOrExpiredWindows() {
        val corruptions: List<(AutomationToolApprovalReceipt) -> AutomationToolApprovalReceipt> = listOf(
            { it.copy(callId = "another-call") },
            { it.copy(operation = "delete") },
            { it.copy(argumentsSha256 = "0".repeat(64)) },
            { it.copy(approvedAt = it.approvedAt.minusSeconds(180), expiresAt = it.expiresAt.minusSeconds(180)) },
        )
        corruptions.forEachIndexed { index, corrupt ->
            val fixture = ToolRuntimeFixture()
            val router = SwappableAutomationToolApprovalProvider(
                actionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
                now = fixture.clock::now,
            )
            val executor = fixture.executor(AutomationToolApprovalProvider {
                corrupt(checkNotNull(router.approve(it)))
            })
            val result = executor.run(call("create", "full-invalid-$index",
                createArguments("automation.invalid_$index")))
            assertFalse(result.success)
            assertTrue(fixture.storage.definitions().isEmpty())
            assertEquals(0, fixture.dispatchedTriggerCount)
        }
    }

    @Test
    fun queuedCancellationPreventsApprovalStorageAndRuntimeDispatch() {
        val fixture = ToolRuntimeFixture()
        val queued = ManualExecutor()
        var approvalCalls = 0
        var callbacks = 0
        val executor = fixture.executor(
            approvalProvider = AutomationToolApprovalProvider {
                approvalCalls += 1
                null
            },
            backgroundExecutor = queued,
        )

        val handle = executor.executeCancellable(
            call(
                "create",
                "queued-automation-cancel",
                createArguments("automation.queued_cancel"),
            ),
            DynamicToolCancellation.NONE,
        ) { callbacks += 1 }

        assertEquals(
            DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT,
            handle.cancel(),
        )
        queued.runNext()
        assertEquals(0, approvalCalls)
        assertTrue(fixture.storage.definitions().isEmpty())
        assertEquals(0, fixture.dispatchedTriggerCount)
        assertEquals(0, callbacks)
    }

    @Test
    fun cancellationDuringApprovalPreventsAutomationMutationAndDispatch() {
        val fixture = ToolRuntimeFixture()
        val queued = ManualExecutor()
        var callbacks = 0
        var cancellationDisposition: DynamicToolCancellationDisposition? = null
        lateinit var handle: DynamicToolExecutionHandle
        val executor = fixture.executor(
            approvalProvider = AutomationToolApprovalProvider { request ->
                cancellationDisposition = handle.cancel()
                val now = fixture.clock.now()
                AutomationToolApprovalReceipt(
                    callId = request.callId,
                    operation = request.operation,
                    argumentsSha256 = request.argumentsSha256,
                    approvedAt = now,
                    expiresAt = now.plus(Duration.ofMinutes(5)),
                    nonce = "approval-cancelled-0001",
                )
            },
            backgroundExecutor = queued,
        )

        handle = executor.executeCancellable(
            call(
                "create",
                "approval-automation-cancel",
                createArguments("automation.approval_cancel"),
            ),
            DynamicToolCancellation.NONE,
        ) { callbacks += 1 }
        queued.runNext()

        assertEquals(
            DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED,
            cancellationDisposition,
        )
        assertTrue(fixture.storage.definitions().isEmpty())
        assertEquals(0, fixture.dispatchedTriggerCount)
        assertEquals(0, callbacks)
    }

    @Test
    fun cancellationDuringDefinitionWriteStillSchedulesReconciliationExactlyOnce() {
        val storage = HookedAutomationStorage()
        val fixture = ToolRuntimeFixture(storage)
        val queued = ManualExecutor()
        var callbacks = 0
        var cancellationDisposition: DynamicToolCancellationDisposition? = null
        lateinit var handle: DynamicToolExecutionHandle
        storage.afterUpsert = { cancellationDisposition = handle.cancel() }
        val executor = fixture.executor(
            approvalProvider = GrantingAutomationApprovalProvider(fixture.clock),
            backgroundExecutor = queued,
        )

        handle = executor.executeCancellable(
            call(
                "create",
                "definition-write-automation-cancel",
                createArguments("automation.definition_write_cancel"),
            ),
            DynamicToolCancellation.NONE,
        ) { callbacks += 1 }
        queued.runNext()

        assertEquals(
            DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED,
            cancellationDisposition,
        )
        assertTrue(
            fixture.storage.definition(AutomationId("automation.definition_write_cancel")) != null,
        )
        assertEquals(1, fixture.dispatchedTriggerCount)
        assertEquals(0, callbacks)
    }


    @Test
    fun duplicateConfirmationReportsThePersistedExpiry() {
        val now = Instant.parse("2026-01-01T08:00:00Z")
        val state = ToolPlatformState().apply { instant = now }
        val storage = InMemoryAutomationStorage()
        val definition = testDefinition(id = "automation.confirm_expiry")
        val key = storage.insertDueRun(definition, now)
        val persistedExpiry = now.plusSeconds(90)
        assertEquals(
            AutomationConfirmationWriteResult.STORED,
            storage.recordConfirmation(
                AutomationRunConfirmationReceipt(
                    AutomationConfirmationId("confirmation-existing-expiry"),
                    key,
                    definition.revision,
                    now,
                    persistedExpiry,
                ),
                now,
            ),
        )
        val queuedRuntimeWork = mutableListOf<Runnable>()
        val dispatchedTriggers = mutableListOf<AutomationRuntimeTrigger>()
        val owner = AutomationRuntimeOwner(
            storage = storage,
            wakeupAdapter = object : AutomationWakeupAdapter {
                override fun replaceWakeup(plan: AutomationWakeupPlan) =
                    AutomationAdapterResult.Accepted
            },
            cycleDispatcher = AutomationRuntimeCycleDispatcher { trigger ->
                dispatchedTriggers += trigger
                AutomationAdapterResult.Accepted
            },
            backgroundExecutor = Executor { queuedRuntimeWork += it },
            platformState = state,
            liveEnvironment = AutomationLiveEnvironmentSource.FAIL_CLOSED,
            codexExecutor = GatewayCodexAutomationExecutor(ToolRecordingGateway()),
        )
        val executor = HansAutomationDynamicToolExecutor(
            runtime = owner,
            backgroundExecutor = Executor(Runnable::run),
            approvalProvider = GrantingAutomationApprovalProvider(state),
            instantSource = AutomationInstantSource(state::now),
        )
        val args = JSONObject()
            .put("automationId", definition.id.value)
            .put("scheduledAt", key.scheduledAt.toString())
            .put("expectedRevision", definition.revision)
            .toString()

        val result = JSONObject(
            executor.run(call("confirm", "confirm-duplicate-expiry", args)).contentText,
        )

        assertEquals(persistedExpiry.toString(), result.getJSONObject("data").getString("expiresAt"))
        assertEquals(listOf(AutomationRuntimeTrigger.DefinitionChanged), dispatchedTriggers)
        assertTrue(queuedRuntimeWork.isEmpty())
    }

    @Test
    fun runNowIdentityIsScopedByThreadAndRejectsChangedSemantics() {
        val fixture = ToolRuntimeFixture()
        val executor = fixture.executor(GrantingAutomationApprovalProvider(fixture.clock))
        val firstId = "automation.identity_first"
        val secondId = "automation.identity_second"
        assertTrue(executor.run(call("create", "create-first", createArguments(firstId))).success)
        assertTrue(executor.run(call("create", "create-second", createArguments(secondId))).success)

        val first = executor.run(
            call(
                "run_now",
                "shared-call-id",
                revisionArguments(firstId, 1),
                threadId = "thread-one",
                turnId = "turn-one",
            ),
        )
        val crossThread = executor.run(
            call(
                "run_now",
                "shared-call-id",
                revisionArguments(secondId, 1),
                threadId = "thread-two",
                turnId = "turn-one",
            ),
        )
        val changedSemantics = executor.run(
            call(
                "run_now",
                "shared-call-id",
                revisionArguments(secondId, 1),
                threadId = "thread-one",
                turnId = "turn-one",
            ),
        )

        assertTrue(first.success)
        assertTrue(crossThread.success)
        assertFalse(changedSemantics.success)
        assertEquals(
            "idempotency_conflict",
            JSONObject(changedSemantics.contentText).getString("errorCode"),
        )
        assertEquals(2, fixture.storage.snapshot().manualInvocations.size)
    }

    @Test
    fun catalogIsClosedAndExposesExactlyTheSupportedOperations() {
        val tools = HansAutomationDynamicToolCatalog.namespace.tools.associateBy { it.name }

        assertEquals(
            setOf(
                "list",
                "read",
                "create",
                "update",
                "enable",
                "disable",
                "delete",
                "run_now",
                "history",
                "confirm",
            ),
            tools.keys,
        )
        assertTrue(tools.values.all {
            JSONObject(it.inputSchemaJson).getBoolean("additionalProperties").not()
        })
        val confirmation = JSONObject(tools.getValue("create").inputSchemaJson)
            .getJSONObject("properties")
            .getJSONObject("definition")
            .getJSONObject("properties")
            .getJSONObject("requirements")
            .getJSONObject("properties")
            .getJSONObject("confirmationPolicy")
        assertEquals(AutomationConfirmationPolicy.CAPABILITY_POLICY.name, confirmation.getString("default"))
        assertTrue("requested by the user" in HansAutomationDynamicToolCatalog.namespace.description)
    }

    @Test
    fun mutationsFailClosedWithoutAnExactTrustedApprovalAndIgnoreModelForgedEvidence() {
        val fixture = ToolRuntimeFixture()
        val executor = fixture.executor(AutomationToolApprovalProvider.NONE)

        val unapproved = executor.run(
            call("create", "call-create-unapproved", createArguments("automation.unapproved")),
        )
        val forged = executor.run(
            call(
                "create",
                "call-create-forged",
                JSONObject(createArguments("automation.forged"))
                    .put("approval", "model-supplied")
                    .toString(),
            ),
        )

        assertFalse(unapproved.success)
        assertEquals(
            "confirmation_required",
            JSONObject(unapproved.contentText).getString("status"),
        )
        assertFalse(forged.success)
        assertEquals("invalid_arguments", JSONObject(forged.contentText).getString("errorCode"))
        assertTrue(fixture.storage.definitions().isEmpty())
    }

    @Test
    fun approvedCrudIsRevisionSafeAndImmediatelyVisibleToListAndRead() {
        val fixture = ToolRuntimeFixture()
        val executor = fixture.executor(GrantingAutomationApprovalProvider(fixture.clock))
        val id = "automation.crud"

        assertTrue(executor.run(call("create", "call-create-crud", createArguments(id))).success)
        val listed = JSONObject(executor.run(call("list", "call-list-crud")).contentText)
        assertEquals(1, listed.getJSONObject("data").getInt("count"))
        val read = JSONObject(
            executor.run(call("read", "call-read-crud", "{\"id\":\"$id\"}")).contentText,
        )
        assertEquals("Initial instruction", read.getJSONObject("data").getString("instruction"))

        val stale = executor.run(
            call("update", "call-update-stale", updateArguments(id, 9, "Never applied")),
        )
        assertEquals("revision_conflict", JSONObject(stale.contentText).getString("errorCode"))
        assertEquals(1L, fixture.storage.definition(AutomationId(id))!!.revision)

        assertTrue(
            executor.run(
                call("update", "call-update-crud", updateArguments(id, 1, "Updated instruction")),
            ).success,
        )
        assertEquals("Updated instruction", fixture.storage.definition(AutomationId(id))!!.instruction)

        assertTrue(executor.run(call("disable", "call-disable-crud", revisionArguments(id, 2))).success)
        assertFalse(fixture.storage.definition(AutomationId(id))!!.enabled)
        assertEquals(3L, fixture.storage.definition(AutomationId(id))!!.revision)

        assertTrue(executor.run(call("enable", "call-enable-crud", revisionArguments(id, 3))).success)
        assertTrue(fixture.storage.definition(AutomationId(id))!!.enabled)
        assertEquals(4L, fixture.storage.definition(AutomationId(id))!!.revision)

        assertTrue(executor.run(call("delete", "call-delete-crud", revisionArguments(id, 4))).success)
        assertEquals(null, fixture.storage.definition(AutomationId(id)))
    }

    @Test
    fun runNowIsDurablyIdempotentAndExactRunConfirmationExecutesOnlyOnce() {
        val fixture = ToolRuntimeFixture()
        val executor = fixture.executor(GrantingAutomationApprovalProvider(fixture.clock))
        val id = "automation.confirmed"
        assertTrue(
            executor.run(
                call(
                    "create",
                    "call-create-confirmed",
                    createArguments(id, confirmationPolicy = AutomationConfirmationPolicy.EVERY_RUN),
                ),
            ).success,
        )

        val first = executor.run(
            call("run_now", "call-run-now-stable", revisionArguments(id, 1)),
        )
        assertTrue(first.success)
        assertEquals(0, fixture.gateway.executions)
        fixture.runDispatchedCycles()
        val deferred = fixture.storage.snapshot().runs.single()
        assertEquals(AutomationRunState.RETRY_WAIT, deferred.state)
        assertEquals("user_confirmation_required", deferred.lastFailureCode)

        val confirmArguments = JSONObject()
            .put("automationId", id)
            .put("scheduledAt", deferred.key.scheduledAt.toString())
            .put("expectedRevision", 1)
            .toString()
        assertTrue(executor.run(call("confirm", "call-confirm-exact", confirmArguments)).success)
        fixture.runDispatchedCycles()
        assertEquals(1, fixture.gateway.executions)
        assertEquals(AutomationRunState.SUCCEEDED, fixture.storage.snapshot().runs.single().state)
        assertTrue(fixture.storage.snapshot().confirmations.isEmpty())

        val duplicate = executor.run(
            call("run_now", "call-run-now-stable", revisionArguments(id, 1)),
        )
        assertTrue(duplicate.success)
        assertTrue(JSONObject(duplicate.contentText).getJSONObject("data").getBoolean("duplicate"))
        assertEquals(1, fixture.gateway.executions)

        val history = JSONObject(
            executor.run(call("history", "call-history", "{\"id\":\"$id\",\"limit\":10}")).contentText,
        )
        assertEquals(1, history.getJSONObject("data").getJSONArray("items").length())
    }

    private fun createArguments(
        id: String,
        instruction: String = "Initial instruction",
        confirmationPolicy: AutomationConfirmationPolicy = AutomationConfirmationPolicy.CAPABILITY_POLICY,
    ): String = JSONObject()
        .put("id", id)
        .put(
            "definition",
            JSONObject()
                .put(
                    "schedule",
                    JSONObject()
                        .put("startLocal", "2030-01-01T09:00:00")
                        .put("timeZone", "Europe/Oslo")
                        .put("rrule", "FREQ=DAILY"),
                )
                .put("instruction", instruction)
                .put(
                    "requirements",
                    JSONObject().put("confirmationPolicy", confirmationPolicy.name),
                ),
        )
        .toString()

    private fun createArgumentsWithoutConfirmation(id: String): String = JSONObject()
        .put("id", id)
        .put(
            "definition",
            JSONObject()
                .put(
                    "schedule",
                    JSONObject()
                        .put("startLocal", "2030-01-01T09:00:00")
                        .put("timeZone", "Europe/Oslo")
                        .put("rrule", "FREQ=DAILY"),
                )
                .put("instruction", "Initial instruction"),
        )
        .toString()

    private fun updateArguments(id: String, revision: Long, instruction: String): String =
        JSONObject(createArguments(id, instruction))
            .put("expectedRevision", revision)
            .toString()

    private fun updateArgumentsWithoutRequirements(id: String, expectedRevision: Long): String =
        JSONObject(createArgumentsWithoutConfirmation(id))
            .put("expectedRevision", expectedRevision)
            .toString()

    private fun revisionArguments(id: String, revision: Long): String = JSONObject()
        .put("id", id)
        .put("expectedRevision", revision)
        .toString()

    private fun call(
        tool: String,
        callId: String,
        arguments: String = "{}",
        threadId: String = "thread-automation-test",
        turnId: String = "turn-automation-test",
    ) = DynamicToolCallParams(
        threadId = threadId,
        turnId = turnId,
        callId = callId,
        namespace = HansAutomationDynamicToolCatalog.NAMESPACE,
        tool = tool,
        argumentsJson = arguments,
    )

    private fun HansAutomationDynamicToolExecutor.run(
        call: DynamicToolCallParams,
    ): DynamicToolExecutionResult {
        var callbacks = 0
        lateinit var result: DynamicToolExecutionResult
        execute(call) {
            callbacks += 1
            result = it
        }
        assertEquals(1, callbacks)
        return result
    }
}

private class ToolRuntimeFixture(
    val storage: AutomationStorage = InMemoryAutomationStorage(),
    private val cycleResult: AutomationAdapterResult = AutomationAdapterResult.Accepted,
) {
    val clock = ToolPlatformState()
    val gateway = ToolRecordingGateway()
    private val dispatchedTriggers = ArrayDeque<AutomationRuntimeTrigger>()
    val dispatchedTriggerCount: Int
        get() = dispatchedTriggers.size
    private val owner = AutomationRuntimeOwner(
        storage = storage,
        wakeupAdapter = object : AutomationWakeupAdapter {
            override fun replaceWakeup(plan: AutomationWakeupPlan): AutomationAdapterResult =
                AutomationAdapterResult.Accepted
        },
        cycleDispatcher = AutomationRuntimeCycleDispatcher { trigger ->
            dispatchedTriggers.addLast(trigger)
            cycleResult
        },
        backgroundExecutor = Executor(Runnable::run),
        platformState = clock,
        liveEnvironment = AutomationLiveEnvironmentSource {
            AutomationExecutionEnvironment(
                availableCapabilities = setOf(AutomationCapabilityId.CODEX_APP_SERVER),
                grantedPermissions = emptySet(),
                codexAuthenticated = true,
                networkAvailable = true,
                deviceUnlocked = true,
            )
        },
        codexExecutor = GatewayCodexAutomationExecutor(gateway),
        leaseTokenSource = object : AutomationLeaseTokenSource {
            private var sequence = 0
            override fun next(): AutomationLeaseToken {
                sequence += 1
                return AutomationLeaseToken("lease-tool-${sequence.toString().padStart(8, '0')}")
            }
        },
    )

    fun executor(
        approvalProvider: AutomationToolApprovalProvider,
        backgroundExecutor: Executor = Executor(Runnable::run),
    ) =
        HansAutomationDynamicToolExecutor(
            runtime = owner,
            backgroundExecutor = backgroundExecutor,
            approvalProvider = approvalProvider,
            instantSource = AutomationInstantSource(clock::now),
        )

    fun runDispatchedCycles() {
        while (dispatchedTriggers.isNotEmpty()) {
            owner.requestCycle(dispatchedTriggers.removeFirst())
        }
    }
}

private class HookedAutomationStorage(
    private val delegate: AutomationStorage = InMemoryAutomationStorage(),
) : AutomationStorage by delegate {
    var afterUpsert: () -> Unit = {}

    override fun upsertDefinition(definition: AutomationDefinition): DefinitionWriteResult {
        val result = delegate.upsertDefinition(definition)
        afterUpsert()
        return result
    }
}

private class ManualExecutor : Executor {
    private val tasks = ArrayDeque<Runnable>()

    override fun execute(command: Runnable) {
        tasks.addLast(command)
    }

    fun runNext() {
        tasks.removeFirst().run()
    }
}

private class ToolPlatformState : AutomationPlatformStateSource {
    var instant: Instant = Instant.parse("2026-01-01T08:00:00Z")
    override fun now(): Instant = instant
    override fun systemZone(): ZoneId = ZoneId.of("Europe/Oslo")
    override fun bootSessionId(): AutomationBootSessionId = AutomationBootSessionId("boot-tool-test")
}

private class ToolRecordingGateway : AutomationCodexGateway {
    var executions = 0

    override fun executeInExistingThread(
        threadId: String,
        instruction: String,
        idempotencyKey: String,
        heartbeat: AutomationHeartbeat,
    ): CodexAutomationExecutionOutcome {
        check(heartbeat.beat())
        executions += 1
        return CodexAutomationExecutionOutcome.Succeeded
    }

    override fun executeInNewThread(
        instruction: String,
        idempotencyKey: String,
        heartbeat: AutomationHeartbeat,
    ): CodexAutomationExecutionOutcome {
        check(heartbeat.beat())
        executions += 1
        return CodexAutomationExecutionOutcome.Succeeded
    }
}

private class GrantingAutomationApprovalProvider(
    private val clock: ToolPlatformState,
) : AutomationToolApprovalProvider {
    private var sequence = 0

    override fun approve(request: AutomationToolApprovalRequest): AutomationToolApprovalReceipt {
        sequence += 1
        val now = clock.now()
        return AutomationToolApprovalReceipt(
            callId = request.callId,
            operation = request.operation,
            argumentsSha256 = request.argumentsSha256,
            approvedAt = now,
            expiresAt = now.plus(Duration.ofMinutes(5)),
            nonce = "approval-nonce-${sequence.toString().padStart(8, '0')}",
        )
    }
}
