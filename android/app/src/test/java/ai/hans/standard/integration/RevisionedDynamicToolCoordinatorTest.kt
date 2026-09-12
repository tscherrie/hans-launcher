package ai.hans.standard.integration

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RevisionedDynamicToolCoordinatorTest {
    @Test
    fun activeWorkQueuesOnlyNewestSnapshotThenMigratesAndInstallsExactlyOnce() {
        val initial = snapshot("local")
        val second = snapshot("local", "remote_a")
        val newest = snapshot("local", "remote_b")
        var active = true
        var migrations = 0
        var installs = 0
        val coordinator = RevisionedDynamicToolCoordinator(
            router = RevisionedDynamicToolRouter(initial),
            migrateExact = {
                migrations += 1
                DynamicToolMigrationResult.THREAD_ROTATED
            },
            activityProbe = { active },
            installGeneration = { _, _ -> installs += 1 },
        )

        assertEquals(
            DynamicToolSnapshotApplyResult.Queued(second.revision),
            coordinator.apply(second),
        )
        assertEquals(
            DynamicToolSnapshotApplyResult.Queued(newest.revision),
            coordinator.apply(newest),
        )
        assertEquals(newest.revision, coordinator.pendingRevision())
        assertEquals(0, migrations)
        assertEquals(0, installs)

        active = false
        val applied = coordinator.onActivityChanged()
        assertTrue(applied is DynamicToolSnapshotApplyResult.Applied)
        assertEquals(newest.revision, coordinator.currentRevision())
        assertEquals(1, migrations)
        assertEquals(1, installs)
        assertEquals(null, coordinator.onActivityChanged())
        assertEquals(1, migrations)
    }

    @Test
    fun staleRevisionIsDeniedWithoutMigrationOrGenerationInstall() {
        val initial = snapshot("local")
        val next = snapshot("local", "remote")
        var migrations = 0
        var installs = 0
        val coordinator = RevisionedDynamicToolCoordinator(
            router = RevisionedDynamicToolRouter(initial),
            migrateExact = {
                migrations += 1
                DynamicToolMigrationResult.THREAD_ROTATED
            },
            activityProbe = { false },
            installGeneration = { _, _ -> installs += 1 },
        )
        assertTrue(coordinator.apply(next) is DynamicToolSnapshotApplyResult.Applied)
        assertEquals(
            DynamicToolSnapshotApplyResult.StaleDenied(initial.revision),
            coordinator.apply(initial),
        )
        assertEquals(1, migrations)
        assertEquals(1, installs)
    }

    @Test
    fun remoteContributorDefaultsToInteractiveOnly() {
        val local = DynamicToolContributor(
            FakeExecutor("local"),
            DynamicToolPlacement.BACKGROUND_ALLOWED,
        )
        val remote = DynamicToolContributor(FakeExecutor("remote"))
        val snapshot = RevisionedDynamicToolSnapshot.create(listOf(local, remote))
        val coordinator = RevisionedDynamicToolCoordinator(
            RevisionedDynamicToolRouter(snapshot),
            { DynamicToolMigrationResult.CURRENT },
            { false },
            { _, _ -> },
        )

        assertEquals(setOf("local", "remote"), coordinator.interactiveExecutor().specs.names())
        assertEquals(setOf("local"), coordinator.backgroundExecutor().specs.names())
    }

    @Test
    fun inFlightCallQueuesSnapshotAndCompletionTriggersExactlyOneMigration() {
        val holding = HoldingExecutor("local")
        val initial = RevisionedDynamicToolSnapshot.create(
            listOf(DynamicToolContributor(holding, DynamicToolPlacement.BACKGROUND_ALLOWED)),
        )
        val next = snapshot("local", "remote")
        var migrations = 0
        var installs = 0
        val coordinator = RevisionedDynamicToolCoordinator(
            RevisionedDynamicToolRouter(initial),
            {
                migrations += 1
                DynamicToolMigrationResult.THREAD_ROTATED
            },
            { false },
            { _, _ -> installs += 1 },
        )
        var completed = false
        coordinator.backgroundExecutor().execute(call("local")) { completed = it.success }

        assertEquals(
            DynamicToolSnapshotApplyResult.Queued(next.revision),
            coordinator.apply(next),
        )
        assertEquals(0, migrations)
        holding.complete()

        assertTrue(completed)
        assertEquals(next.revision, coordinator.currentRevision())
        assertEquals(1, migrations)
        assertEquals(1, installs)
    }

    @Test
    fun failedGenerationInstallKeepsOldContractAndAllowsExactRetry() {
        val initial = snapshot("local")
        val next = snapshot("local", "remote")
        var failInstall = true
        var migrations = 0
        val coordinator = RevisionedDynamicToolCoordinator(
            RevisionedDynamicToolRouter(initial),
            {
                migrations += 1
                DynamicToolMigrationResult.THREAD_ROTATED
            },
            { false },
            { _, _ -> if (failInstall) error("generation install failed") },
        )

        assertEquals(
            DynamicToolSnapshotApplyResult.ActivationFailed(next.revision),
            coordinator.apply(next),
        )
        assertEquals(initial.revision, coordinator.currentRevision())
        assertEquals(setOf("local"), coordinator.interactiveExecutor().specs.names())

        failInstall = false
        assertTrue(coordinator.apply(next) is DynamicToolSnapshotApplyResult.Applied)
        assertEquals(next.revision, coordinator.currentRevision())
        assertEquals(setOf("local", "remote"), coordinator.interactiveExecutor().specs.names())
        assertEquals(3, migrations)
    }

    @Test
    fun failedRollbackKeepsOldLeaseButFailsClosedForEveryLaterMutation() {
        val initial = snapshot("local")
        val next = snapshot("local", "remote")
        val later = snapshot("local", "remote_later")
        var migrations = 0
        var installs = 0
        val coordinator = RevisionedDynamicToolCoordinator(
            RevisionedDynamicToolRouter(initial),
            {
                migrations += 1
                if (migrations == 2) error("persisted contract rollback failed")
                DynamicToolMigrationResult.THREAD_ROTATED
            },
            { false },
            { _, _ ->
                installs += 1
                error("generation install failed")
            },
        )

        assertEquals(
            DynamicToolSnapshotApplyResult.ActivationFailed(next.revision),
            coordinator.apply(next),
        )
        assertEquals(initial.revision, coordinator.currentRevision())
        assertEquals(setOf("local"), coordinator.interactiveExecutor().specs.names())
        assertEquals(
            DynamicToolSnapshotApplyResult.ActivationFailed(later.revision),
            coordinator.apply(later),
        )
        assertEquals(2, migrations)
        assertEquals(1, installs)
        assertEquals(null, coordinator.pendingRevision())
    }

    private fun snapshot(vararg namespaces: String): RevisionedDynamicToolSnapshot =
        RevisionedDynamicToolSnapshot.create(
            namespaces.mapIndexed { index, namespace ->
                DynamicToolContributor(
                    FakeExecutor(namespace),
                    if (index == 0) {
                        DynamicToolPlacement.BACKGROUND_ALLOWED
                    } else {
                        DynamicToolPlacement.INTERACTIVE_ONLY
                    },
                )
            },
        )

    private fun List<DynamicToolNamespaceSpec>.names(): Set<String> =
        mapTo(linkedSetOf(), DynamicToolNamespaceSpec::name)

    private fun call(namespace: String) = DynamicToolCallParams(
        threadId = "thread-1",
        turnId = "turn-1",
        callId = "call-1",
        namespace = namespace,
        tool = "run",
        argumentsJson = "{}",
    )

    private class FakeExecutor(namespace: String) : DynamicToolExecutor {
        override val specs = listOf(
            DynamicToolNamespaceSpec(
                namespace,
                "Test $namespace",
                listOf(
                    DynamicToolFunctionSpec(
                        "run",
                        "Run",
                        """{"type":"object","additionalProperties":false}""",
                    ),
                ),
            ),
        )

        override fun execute(
            call: DynamicToolCallParams,
            completion: (DynamicToolExecutionResult) -> Unit,
        ) = completion(
            DynamicToolExecutionResult(
                JSONObject().put("status", "ok").toString(),
                success = true,
            ),
        )

        override fun failureResult(
            call: DynamicToolCallParams,
            code: String,
        ) = DynamicToolExecutionResult(
            JSONObject().put("status", "failed").put("errorCode", code).toString(),
            success = false,
        )
    }

    private class HoldingExecutor(namespace: String) : DynamicToolExecutor {
        private var completion: ((DynamicToolExecutionResult) -> Unit)? = null
        override val specs = FakeExecutor(namespace).specs

        override fun execute(
            call: DynamicToolCallParams,
            completion: (DynamicToolExecutionResult) -> Unit,
        ) {
            check(this.completion == null)
            this.completion = completion
        }

        override fun failureResult(
            call: DynamicToolCallParams,
            code: String,
        ) = FakeExecutor(requireNotNull(call.namespace)).failureResult(call, code)

        fun complete() {
            requireNotNull(completion).invoke(
                DynamicToolExecutionResult(
                    JSONObject().put("status", "ok").toString(),
                    success = true,
                ),
            )
        }
    }
}
