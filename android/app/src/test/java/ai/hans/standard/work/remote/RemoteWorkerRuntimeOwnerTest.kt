package ai.hans.standard.work.remote

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.ArrayDeque
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWorkerRuntimeOwnerTest {
    @Test
    fun coldOwnerWithEnabledConfigurationNeedsExplicitActivation() =
        withOwner(preconfigured = configuration(enabled = true)) { owner, activator, publications ->
            val state = owner.passiveState()

            assertTrue(state.requestedEnabled)
            assertFalse(state.effective)
            assertEquals(RemoteWorkerRuntimeStatus.NEEDS_ACTIVATION, state.status)
            assertNull(state.activeRevisionToken)
            assertNull(owner.activePublication())
            assertEquals(0, activator.calls)
            assertEquals(0, publications.count)
        }

    @Test
    fun saveIsPassiveAndActivationPublishesOnlyAfterExactProbe() =
        withOwner { owner, activator, publications ->
            val configuration = configuration(enabled = true)
            val saved = owner.save(0L, configuration) as RemoteWorkerRuntimeUpdateResult.Saved
            assertTrue(saved.state.requestedEnabled)
            assertFalse(saved.state.effective)
            assertEquals(RemoteWorkerRuntimeStatus.NEEDS_ACTIVATION, saved.state.status)
            assertEquals(0, activator.calls)
            assertNull(owner.activePublication())

            val result = owner.activate(1L) as RemoteWorkerRuntimeUpdateResult.Saved
            val active = checkNotNull(owner.activePublication())
            assertEquals(RemoteWorkerRuntimeStatus.EFFECTIVE, result.state.status)
            assertTrue(result.state.effective)
            assertEquals(1, activator.calls)
            assertSame(activator.contributions.single(), active.contribution)
            assertEquals(1L, active.configurationRevision)
            assertEquals(configuration.identity.configurationDigest, active.configurationDigest)
            assertEquals(
                "remote-worker:1:${configuration.identity.configurationDigest}",
                active.revisionToken,
            )
            assertEquals(active.revisionToken, result.state.activeRevisionToken)
            assertEquals(1, publications.count)
        }

    @Test
    fun revisionMismatchAndActivationFailureNeverPublish() =
        withOwner { owner, activator, publications ->
            val configuration = configuration(enabled = true)
            assertTrue(owner.save(0L, configuration) is RemoteWorkerRuntimeUpdateResult.Saved)
            assertSame(RemoteWorkerRuntimeUpdateResult.RevisionMismatch, owner.activate(0L))
            activator.fail = true
            assertSame(RemoteWorkerRuntimeUpdateResult.ActivationFailed, owner.activate(1L))
            assertNull(owner.activePublication())
            assertEquals(0, publications.count)
        }

    @Test
    fun everySavedRevisionUnpublishesOldRouteBeforeCommit() =
        withOwner { owner, _, publications ->
            val enabled = configuration(enabled = true)
            assertTrue(owner.save(0L, enabled) is RemoteWorkerRuntimeUpdateResult.Saved)
            assertTrue(owner.activate(1L) is RemoteWorkerRuntimeUpdateResult.Saved)
            val countAfterActivation = publications.count

            val updated = enabled.copy(
                approvedAdapters = listOf(
                    RemoteWorkAdapterApproval(RemoteWorkAdapterId("linux.test"), "2"),
                ),
            )
            val result = owner.save(1L, updated) as RemoteWorkerRuntimeUpdateResult.Saved
            assertNull(owner.activePublication())
            assertEquals(countAfterActivation + 1, publications.count)
            assertEquals(2L, result.state.revision)
            assertTrue(result.state.requestedEnabled)
            assertFalse(result.state.effective)
            assertEquals(RemoteWorkerRuntimeStatus.NEEDS_ACTIVATION, result.state.status)
        }

    @Test
    fun queuedRemovalRestoresOldEffectivePublicationAndDoesNotCommit() =
        withOwner { owner, _, publications ->
            val enabled = configuration(enabled = true)
            assertTrue(owner.save(0L, enabled) is RemoteWorkerRuntimeUpdateResult.Saved)
            assertTrue(owner.activate(1L) is RemoteWorkerRuntimeUpdateResult.Saved)
            val old = checkNotNull(owner.activePublication())
            publications.enqueue(
                RemoteWorkerPublicationDisposition.Queued,
                RemoteWorkerPublicationDisposition.Applied,
            )

            assertSame(
                RemoteWorkerRuntimeUpdateResult.PublicationFailed,
                owner.save(1L, enabled.copy(enabled = false)),
            )

            assertSame(old, owner.activePublication())
            assertEquals(1L, owner.passiveState().revision)
            assertTrue(owner.passiveState().requestedEnabled)
            assertTrue(owner.passiveState().effective)
            assertEquals(RemoteWorkerRuntimeStatus.EFFECTIVE, owner.passiveState().status)
        }

    @Test
    fun persistenceFailureRestoresExactEffectivePublicationAndRequestedState() {
        val fault = TogglePersistenceFault()
        withOwner(faultInjector = fault) { owner, _, _ ->
            val enabled = configuration(enabled = true)
            assertTrue(owner.save(0L, enabled) is RemoteWorkerRuntimeUpdateResult.Saved)
            assertTrue(owner.activate(1L) is RemoteWorkerRuntimeUpdateResult.Saved)
            val previousPublication = checkNotNull(owner.activePublication())
            val previousState = owner.passiveState()
            fault.checkpoint = RemoteWorkerPersistenceCheckpoint.AFTER_ATOMIC_REPLACE

            assertSame(
                RemoteWorkerRuntimeUpdateResult.PersistenceFailed,
                owner.save(1L, enabled.copy(enabled = false)),
            )

            assertSame(previousPublication, owner.activePublication())
            assertEquals(previousState, owner.passiveState())
            assertEquals(RemoteWorkerRuntimeStatus.EFFECTIVE, owner.passiveState().status)
            assertTrue(owner.passiveState().requestedEnabled)
            assertTrue(owner.passiveState().effective)
        }
    }

    @Test
    fun onlyAppliedAndUnchangedPublicationDispositionsBecomeEffective() {
        val rejected = listOf(
            RemoteWorkerPublicationDisposition.Queued,
            RemoteWorkerPublicationDisposition.StaleDenied,
            RemoteWorkerPublicationDisposition.ActivationFailed,
            RemoteWorkerPublicationDisposition.MissingHost,
        )
        rejected.forEach { disposition ->
            withOwner { owner, _, publications ->
                val enabled = configuration(enabled = true)
                assertTrue(owner.save(0L, enabled) is RemoteWorkerRuntimeUpdateResult.Saved)
                publications.enqueue(disposition, RemoteWorkerPublicationDisposition.Applied)

                assertSame(
                    RemoteWorkerRuntimeUpdateResult.PublicationFailed,
                    owner.activate(1L),
                )
                assertNull(owner.activePublication())
                assertFalse(owner.passiveState().effective)
                assertEquals(
                    RemoteWorkerRuntimeStatus.NEEDS_ACTIVATION,
                    owner.passiveState().status,
                )
            }
        }

        withOwner { owner, _, publications ->
            assertTrue(owner.save(0L, configuration(enabled = true)) is
                RemoteWorkerRuntimeUpdateResult.Saved)
            publications.enqueue(RemoteWorkerPublicationDisposition.Unchanged)
            assertTrue(owner.activate(1L) is RemoteWorkerRuntimeUpdateResult.Saved)
            assertTrue(owner.passiveState().effective)
        }
    }

    @Test
    fun disableAndReactivateSameConfigurationGetsFreshRevisionToken() =
        withOwner { owner, _, _ ->
            val enabled = configuration(enabled = true)
            assertTrue(owner.save(0L, enabled) is RemoteWorkerRuntimeUpdateResult.Saved)
            assertTrue(owner.activate(1L) is RemoteWorkerRuntimeUpdateResult.Saved)
            val first = checkNotNull(owner.activePublication())

            assertTrue(owner.save(1L, enabled.copy(enabled = false)) is
                RemoteWorkerRuntimeUpdateResult.Saved)
            assertNull(owner.activePublication())
            assertEquals(RemoteWorkerRuntimeStatus.DISABLED, owner.passiveState().status)

            assertTrue(owner.save(2L, enabled) is RemoteWorkerRuntimeUpdateResult.Saved)
            assertTrue(owner.activate(3L) is RemoteWorkerRuntimeUpdateResult.Saved)
            val reactivated = checkNotNull(owner.activePublication())

            assertNotEquals(first.revisionToken, reactivated.revisionToken)
            assertEquals(
                "remote-worker:3:${enabled.identity.configurationDigest}",
                reactivated.revisionToken,
            )
            assertNotEquals(first.configurationRevision, reactivated.configurationRevision)
        }

    @Test
    fun passiveProjectionNeverContainsEndpointPinOrKeyAlias() =
        withOwner(preconfigured = configuration(enabled = true)) { owner, _, _ ->
            val configuration = configuration(enabled = true)
            val projection = owner.passiveState().toString()

            assertFalse(projection.contains(configuration.identity.endpoint))
            assertFalse(projection.contains(configuration.identity.serverSpkiSha256))
            assertFalse(projection.contains(configuration.identity.deviceKeyAlias))
            assertTrue(projection.contains(configuration.identity.workerId.value))
        }

    @Test
    fun editableProjectionReadsOnlyLocalStoreAndDoesNotActivateOrPublish() =
        withOwner(preconfigured = configuration(enabled = true)) { owner, activator, publications ->
            val exact = configuration(enabled = true)

            val editable = owner.passiveEditableConfiguration()

            assertEquals(1L, editable.revision)
            assertEquals(exact.identity.endpoint, editable.endpoint)
            assertEquals(exact.identity.serverSpkiSha256, editable.serverSpkiSha256)
            assertEquals(exact.approvedAdapters, editable.approvedAdapters)
            assertEquals(0, activator.calls)
            assertEquals(0, publications.count)
            assertNull(owner.activePublication())
        }

    private fun withOwner(
        preconfigured: RemoteWorkerConfiguration? = null,
        faultInjector: RemoteWorkerPersistenceFaultInjector =
            RemoteWorkerPersistenceFaultInjector {},
        block: (RemoteWorkerRuntimeOwner, TestActivator, PublicationProbe) -> Unit,
    ) {
        val directory = Files.createTempDirectory("remote-worker-owner").toFile()
        try {
            val store = AppPrivateRemoteWorkerConfigurationStore(
                directory,
                TestCipher(),
                faultInjector,
            )
            if (preconfigured != null) {
                assertEquals(
                    RemoteWorkerConfigurationMutationResult.APPLIED,
                    store.compareAndSet(0L, preconfigured),
                )
            }
            val activator = TestActivator()
            val publication = PublicationProbe()
            block(
                RemoteWorkerRuntimeOwner(store, activator, publication::publish),
                activator,
                publication,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    private class PublicationProbe {
        var count = 0
        private val dispositions = ArrayDeque<RemoteWorkerPublicationDisposition>()

        fun enqueue(vararg disposition: RemoteWorkerPublicationDisposition) {
            disposition.forEach(dispositions::addLast)
        }

        fun publish(): RemoteWorkerPublicationDisposition {
            count += 1
            return if (dispositions.isEmpty()) {
                RemoteWorkerPublicationDisposition.Applied
            } else {
                dispositions.removeFirst()
            }
        }
    }

    private class TestActivator : RemoteWorkerContributionActivator {
        var calls = 0
        var fail = false
        val contributions = mutableListOf<RemoteWorkDynamicToolContribution>()

        override fun activate(
            configuration: RemoteWorkerConfiguration,
        ): RemoteWorkDynamicToolContribution? {
            calls += 1
            if (fail) return null
            return contribution(configuration).also(contributions::add)
        }
    }

    private class TogglePersistenceFault : RemoteWorkerPersistenceFaultInjector {
        var checkpoint: RemoteWorkerPersistenceCheckpoint? = null

        override fun check(checkpoint: RemoteWorkerPersistenceCheckpoint) {
            if (checkpoint == this.checkpoint) {
                throw IOException("simulated remote worker persistence failure")
            }
        }
    }

    private class TestCipher : RemoteWorkerConfigurationCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray {
            val masked = plaintext.mapIndexed { index, byte ->
                (byte.toInt() xor MASK[index % MASK.size].toInt()).toByte()
            }.toByteArray()
            return MessageDigest.getInstance("SHA-256").digest(masked) + masked
        }

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            require(ciphertext.size > 32)
            val digest = ciphertext.copyOfRange(0, 32)
            val masked = ciphertext.copyOfRange(32, ciphertext.size)
            require(MessageDigest.getInstance("SHA-256").digest(masked).contentEquals(digest))
            return masked.mapIndexed { index, byte ->
                (byte.toInt() xor MASK[index % MASK.size].toInt()).toByte()
            }.toByteArray()
        }

        private companion object {
            val MASK = "remote-owner-test".toByteArray()
        }
    }

    private companion object {
        fun configuration(enabled: Boolean): RemoteWorkerConfiguration = RemoteWorkerConfiguration(
            identity = RemoteWorkerConnectionIdentity(
                RemoteWorkerId("worker-1"),
                "https://worker.example/",
                "ab".repeat(32),
                "hans.remote.worker-1",
            ),
            approvedAdapters = listOf(
                RemoteWorkAdapterApproval(RemoteWorkAdapterId("linux.build"), "1.2"),
            ),
            enabled = enabled,
        )

        fun contribution(configuration: RemoteWorkerConfiguration): RemoteWorkDynamicToolContribution {
            val adapter = RemoteWorkAdapterDescriptor(
                configuration.approvedAdapters.single().id,
                configuration.approvedAdapters.single().version,
                "Test adapter",
            )
            val receipt = RemoteWorkerProbeReceipt(
                configuration.identity.workerId,
                configuration.identity.configurationDigest,
                REMOTE_WORK_PROTOCOL_VERSION,
                listOf(adapter),
                1_000L,
            )
            return RemoteWorkDynamicToolContribution(
                RemoteWorkerActivation(configuration, receipt, listOf(adapter), 2_000L),
                object : DynamicToolExecutor {
                    override val specs = listOf(
                        DynamicToolNamespaceSpec(
                            "remote_test",
                            "Remote test",
                            listOf(DynamicToolFunctionSpec("run", "Run", "{\"type\":\"object\"}")),
                        ),
                    )

                    override fun execute(
                        call: DynamicToolCallParams,
                        completion: (DynamicToolExecutionResult) -> Unit,
                    ) = completion(DynamicToolExecutionResult("{}", true))

                    override fun failureResult(
                        call: DynamicToolCallParams,
                        code: String,
                    ) = DynamicToolExecutionResult("{}", false)
                },
            )
        }
    }
}
