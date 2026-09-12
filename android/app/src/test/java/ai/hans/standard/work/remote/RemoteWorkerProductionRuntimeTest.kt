package ai.hans.standard.work.remote

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.integration.DynamicToolMigrationResult
import ai.hans.standard.integration.DynamicToolSnapshotApplyResult
import java.nio.file.Files
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWorkerProductionRuntimeTest {
    @Test
    fun coldEnabledConfigurationAndAllPassiveReadsPerformNoNetworkOrPublication() =
        withRuntime(preconfigured = configuration(enabled = true)) { runtime, activator, publisher, executor ->
            repeat(3) {
                val state = runtime.passiveState()
                assertTrue(state.requestedEnabled)
                assertFalse(state.effective)
                assertEquals(RemoteWorkerRuntimeStatus.NEEDS_ACTIVATION, state.status)
                assertNull(runtime.activePublication())
            }

            assertEquals(0, activator.calls)
            assertEquals(0, publisher.calls)
            assertEquals(0, executor.pending)

            var completed: RemoteWorkerRuntimeUpdateResult? = null
            assertTrue(runtime.activateExplicitly(1L) { completed = it })
            assertNull(completed)
            assertEquals(0, activator.calls)
            assertFalse(runtime.passiveState().effective)

            executor.runNext()
            assertTrue(completed is RemoteWorkerRuntimeUpdateResult.Saved)
            assertTrue(runtime.passiveState().effective)
            assertEquals(1, activator.calls)
            assertEquals(1, publisher.calls)
        }

    @Test
    fun queuedOrFailedAppServerApplyRollsBackCandidateAndNeverProjectsEffective() =
        withRuntime { runtime, activator, publisher, executor ->
            assertTrue(runtime.save(0L, configuration(enabled = true)) is
                RemoteWorkerRuntimeUpdateResult.Saved)
            publisher.enqueue(
                RemoteWorkerPublicationDisposition.Queued,
                RemoteWorkerPublicationDisposition.Unchanged,
            )
            var completed: RemoteWorkerRuntimeUpdateResult? = null
            runtime.activateExplicitly(1L) { completed = it }
            executor.runNext()

            assertSame(RemoteWorkerRuntimeUpdateResult.PublicationFailed, completed)
            assertEquals(1, activator.calls)
            assertNull(runtime.activePublication())
            assertFalse(runtime.passiveState().effective)
            assertEquals(RemoteWorkerRuntimeStatus.NEEDS_ACTIVATION, runtime.passiveState().status)
        }

    @Test
    fun enableDisableAndDifferentIdentityReenableRotatePublicationAndExecutor() =
        withRuntime { runtime, activator, _, executor ->
            val firstConfiguration = configuration(enabled = true)
            assertTrue(runtime.save(0L, firstConfiguration) is RemoteWorkerRuntimeUpdateResult.Saved)
            runtime.activateExplicitly(1L) {}
            executor.runNext()
            val first = checkNotNull(runtime.activePublication())

            assertTrue(runtime.save(1L, firstConfiguration.copy(enabled = false)) is
                RemoteWorkerRuntimeUpdateResult.Saved)
            assertEquals(RemoteWorkerRuntimeStatus.DISABLED, runtime.passiveState().status)
            assertNull(runtime.activePublication())

            val secondConfiguration = configuration(
                enabled = true,
                worker = "worker-2",
                endpoint = "https://second-worker.example/",
                pin = "cd".repeat(32),
            )
            assertTrue(runtime.save(2L, secondConfiguration) is RemoteWorkerRuntimeUpdateResult.Saved)
            runtime.activateExplicitly(3L) {}
            executor.runNext()
            val second = checkNotNull(runtime.activePublication())

            assertNotEquals(first.revisionToken, second.revisionToken)
            assertNotEquals(first.configurationDigest, second.configurationDigest)
            assertTrue(first.contribution.executor !== second.contribution.executor)
            assertEquals(2, activator.calls)
            assertTrue(runtime.passiveState().effective)
        }

    @Test
    fun appServerApplyResultMappingIsExhaustiveAndNeverTreatsQueuedAsEffective() {
        val applied = DynamicToolSnapshotApplyResult.Applied(
            previousRevision = "a".repeat(64),
            revision = "b".repeat(64),
            migration = DynamicToolMigrationResult.CURRENT,
        )
        assertSame(RemoteWorkerPublicationDisposition.Applied, applied.toRemoteWorkerPublicationDisposition())
        assertSame(
            RemoteWorkerPublicationDisposition.Unchanged,
            DynamicToolSnapshotApplyResult.Unchanged("a".repeat(64))
                .toRemoteWorkerPublicationDisposition(),
        )
        assertSame(
            RemoteWorkerPublicationDisposition.Queued,
            DynamicToolSnapshotApplyResult.Queued("a".repeat(64))
                .toRemoteWorkerPublicationDisposition(),
        )
        assertSame(
            RemoteWorkerPublicationDisposition.StaleDenied,
            DynamicToolSnapshotApplyResult.StaleDenied("a".repeat(64))
                .toRemoteWorkerPublicationDisposition(),
        )
        assertSame(
            RemoteWorkerPublicationDisposition.ActivationFailed,
            DynamicToolSnapshotApplyResult.ActivationFailed("a".repeat(64))
                .toRemoteWorkerPublicationDisposition(),
        )
        assertSame(
            RemoteWorkerPublicationDisposition.MissingHost,
            (null as DynamicToolSnapshotApplyResult?).toRemoteWorkerPublicationDisposition(),
        )
    }

    private fun withRuntime(
        preconfigured: RemoteWorkerConfiguration? = null,
        block: (
            RemoteWorkerProductionRuntime,
            TestActivator,
            PublicationProbe,
            ManualExecutor,
        ) -> Unit,
    ) {
        val directory = Files.createTempDirectory("remote-worker-production").toFile()
        try {
            val store = AppPrivateRemoteWorkerConfigurationStore(directory, TestCipher())
            if (preconfigured != null) {
                assertEquals(
                    RemoteWorkerConfigurationMutationResult.APPLIED,
                    store.compareAndSet(0L, preconfigured),
                )
            }
            val activator = TestActivator()
            val publisher = PublicationProbe()
            val executor = ManualExecutor()
            val runtime = RemoteWorkerProductionRuntime.fromOwner(
                RemoteWorkerRuntimeOwner(store, activator, publisher::publish),
                executor,
            )
            block(runtime, activator, publisher, executor)
        } finally {
            directory.deleteRecursively()
        }
    }

    private class ManualExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()
        val pending: Int get() = tasks.size
        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }
        fun runNext() = tasks.removeFirst().run()
    }

    private class PublicationProbe {
        private val dispositions = ArrayDeque<RemoteWorkerPublicationDisposition>()
        var calls = 0
            private set

        fun enqueue(vararg values: RemoteWorkerPublicationDisposition) =
            values.forEach(dispositions::addLast)

        fun publish(): RemoteWorkerPublicationDisposition {
            calls += 1
            return if (dispositions.isEmpty()) {
                RemoteWorkerPublicationDisposition.Applied
            } else {
                dispositions.removeFirst()
            }
        }
    }

    private class TestActivator : RemoteWorkerContributionActivator {
        var calls = 0
            private set

        override fun activate(configuration: RemoteWorkerConfiguration):
            RemoteWorkDynamicToolContribution {
            calls += 1
            val adapter = RemoteWorkAdapterDescriptor(
                configuration.approvedAdapters.single().id,
                configuration.approvedAdapters.single().version,
                "Test adapter",
            )
            val receipt = RemoteWorkerProbeReceipt(
                worker = configuration.identity.workerId,
                configurationDigest = configuration.identity.configurationDigest,
                protocolVersion = REMOTE_WORK_PROTOCOL_VERSION,
                adapters = listOf(adapter),
                observedAtEpochMillis = 1_000L,
            )
            return RemoteWorkDynamicToolContribution(
                activation = RemoteWorkerActivation(
                    configuration,
                    receipt,
                    listOf(adapter),
                    2_000L,
                ),
                executor = TestExecutor("executor-$calls"),
            )
        }
    }

    private class TestExecutor(private val marker: String) : DynamicToolExecutor {
        override val specs = listOf(
            DynamicToolNamespaceSpec(
                "remote_work_test",
                "Remote worker production test",
                listOf(DynamicToolFunctionSpec("run", "Run", "{\"type\":\"object\"}")),
            ),
        )

        override fun execute(
            call: DynamicToolCallParams,
            completion: (DynamicToolExecutionResult) -> Unit,
        ) = completion(DynamicToolExecutionResult("{\"marker\":\"$marker\"}", true))

        override fun failureResult(
            call: DynamicToolCallParams,
            code: String,
        ) = DynamicToolExecutionResult("{\"error\":\"$code\"}", false)
    }

    private class TestCipher : RemoteWorkerConfigurationCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(plaintext) + plaintext

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            require(ciphertext.size > 32)
            val digest = ciphertext.copyOfRange(0, 32)
            val plaintext = ciphertext.copyOfRange(32, ciphertext.size)
            require(MessageDigest.getInstance("SHA-256").digest(plaintext).contentEquals(digest))
            return plaintext
        }
    }

    private companion object {
        fun configuration(
            enabled: Boolean,
            worker: String = "worker-1",
            endpoint: String = "https://worker.example/",
            pin: String = "ab".repeat(32),
        ) = RemoteWorkerConfiguration(
            identity = RemoteWorkerConnectionIdentity(
                workerId = RemoteWorkerId(worker),
                endpoint = endpoint,
                serverSpkiSha256 = pin,
                deviceKeyAlias = "hans.remote.$worker",
            ),
            approvedAdapters = listOf(
                RemoteWorkAdapterApproval(RemoteWorkAdapterId("linux.build"), "1.2"),
            ),
            enabled = enabled,
        )
    }
}
