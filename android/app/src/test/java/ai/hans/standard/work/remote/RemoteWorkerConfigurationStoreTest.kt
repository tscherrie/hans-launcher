package ai.hans.standard.work.remote

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWorkerConfigurationStoreTest {
    @Test
    fun absenceIsHealthyDisabledAndPerformsNoImplicitConfiguration() = withStore { _, store, _ ->
        assertEquals(RemoteWorkerConfigurationSnapshot(0L, null), store.readExact())
        assertEquals(
            RemoteWorkerPublicConfigurationState(
                revision = 0L,
                configured = false,
                enabled = false,
                workerId = null,
                approvedAdapters = emptyList(),
                configurationDigest = null,
                storageHealthy = true,
            ),
            store.passiveState(),
        )
    }

    @Test
    fun exactCasSurvivesProcessRestartAndEncryptedFileDoesNotRevealCredentials() =
        withStore { directory, store, cipher ->
            val enabled = configuration(enabled = true)
            assertEquals(
                RemoteWorkerConfigurationMutationResult.APPLIED,
                store.compareAndSet(0L, enabled),
            )
            val restarted = AppPrivateRemoteWorkerConfigurationStore(directory, cipher)

            assertEquals(RemoteWorkerConfigurationSnapshot(1L, enabled), restarted.readExact())
            assertEquals(
                RemoteWorkerConfigurationMutationResult.REVISION_MISMATCH,
                restarted.compareAndSet(0L, enabled.copy(enabled = false)),
            )
            val raw = File(directory, "remote-worker-configuration-v1.enc").readBytes()
                .toString(Charsets.ISO_8859_1)
            assertFalse(raw.contains("worker.example"))
            assertFalse(raw.contains("hans.remote.worker-1"))
            assertFalse(raw.contains("ab".repeat(32)))
        }

    @Test
    fun disablingIsARevisionedCommitAndPublicProjectionNeverLeaksPrivateFields() =
        withStore { _, store, _ ->
            val enabled = configuration(enabled = true)
            assertEquals(
                RemoteWorkerConfigurationMutationResult.APPLIED,
                store.compareAndSet(0L, enabled),
            )
            assertEquals(
                RemoteWorkerConfigurationMutationResult.APPLIED,
                store.compareAndSet(1L, enabled.copy(enabled = false)),
            )

            val public = store.passiveState()
            assertEquals(2L, public.revision)
            assertTrue(public.configured)
            assertFalse(public.enabled)
            assertEquals("worker-1", public.workerId)
            assertEquals(listOf("linux.build"), public.approvedAdapters)
            assertEquals(enabled.identity.configurationDigest, public.configurationDigest)
            assertTrue(public.storageHealthy)
            assertFalse(public.toString().contains(enabled.identity.endpoint))
            assertFalse(public.toString().contains(enabled.identity.serverSpkiSha256))
            assertFalse(public.toString().contains(enabled.identity.deviceKeyAlias))
        }

    @Test
    fun editableProjectionIsPassiveExactAndStillRedactsConnectionMetadataFromLogs() =
        withStore { _, store, _ ->
            val enabled = configuration(enabled = true)
            assertEquals(
                RemoteWorkerConfigurationMutationResult.APPLIED,
                store.compareAndSet(0L, enabled),
            )

            val editable = store.passiveEditableState()

            assertEquals(1L, editable.revision)
            assertTrue(editable.configured)
            assertTrue(editable.enabled)
            assertEquals("worker-1", editable.workerId)
            assertEquals("https://worker.example/", editable.endpoint)
            assertEquals("ab".repeat(32), editable.serverSpkiSha256)
            assertEquals(enabled.approvedAdapters, editable.approvedAdapters)
            assertTrue(editable.storageHealthy)
            assertFalse(editable.toString().contains(enabled.identity.endpoint))
            assertFalse(editable.toString().contains(enabled.identity.serverSpkiSha256))
            assertFalse(editable.toString().contains(enabled.identity.deviceKeyAlias))
        }

    @Test
    fun tamperingFailsClosedAndCannotBeOverwrittenByStaleSettings() =
        withStore { directory, store, _ ->
            assertEquals(
                RemoteWorkerConfigurationMutationResult.APPLIED,
                store.compareAndSet(0L, configuration(enabled = true)),
            )
            val file = File(directory, "remote-worker-configuration-v1.enc")
            val tampered = file.readBytes().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            }
            file.writeBytes(tampered)

            val passive = store.passiveState()
            assertFalse(passive.storageHealthy)
            assertFalse(passive.enabled)
            assertFalse(passive.configured)
            assertEquals(
                RemoteWorkerConfigurationMutationResult.CORRUPT,
                store.compareAndSet(1L, configuration(enabled = false)),
            )
            assertTrue(runCatching { store.readExact() }.exceptionOrNull() is
                RemoteWorkerConfigurationCorruptException)
        }

    @Test
    fun atomicReplaceFailureRestoresExactPreviousConfiguration() =
        withStore { directory, store, cipher ->
            val previous = configuration(enabled = true)
            assertEquals(
                RemoteWorkerConfigurationMutationResult.APPLIED,
                store.compareAndSet(0L, previous),
            )
            val failing = AppPrivateRemoteWorkerConfigurationStore(
                directory,
                cipher,
                RemoteWorkerPersistenceFaultInjector { checkpoint ->
                    if (checkpoint == RemoteWorkerPersistenceCheckpoint.BEFORE_ATOMIC_REPLACE) {
                        throw IOException("simulated atomic move failure")
                    }
                },
            )

            assertEquals(
                RemoteWorkerConfigurationMutationResult.PERSISTENCE_FAILED,
                failing.compareAndSet(1L, previous.copy(enabled = false)),
            )
            assertEquals(RemoteWorkerConfigurationSnapshot(1L, previous), store.readExact())
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".transaction") })
            assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".rollback") })
        }

    @Test
    fun postWriteVerificationFailureRollsBackFirstAndSubsequentRevisions() =
        withStore { directory, store, cipher ->
            val previous = configuration(enabled = true)
            assertEquals(
                RemoteWorkerConfigurationMutationResult.APPLIED,
                store.compareAndSet(0L, previous),
            )
            val failing = AppPrivateRemoteWorkerConfigurationStore(
                directory,
                cipher,
                RemoteWorkerPersistenceFaultInjector { checkpoint ->
                    if (
                        checkpoint ==
                        RemoteWorkerPersistenceCheckpoint.BEFORE_POST_WRITE_VERIFICATION
                    ) {
                        throw IOException("simulated post-write verification failure")
                    }
                },
            )

            assertEquals(
                RemoteWorkerConfigurationMutationResult.PERSISTENCE_FAILED,
                failing.compareAndSet(1L, previous.copy(enabled = false)),
            )
            assertEquals(RemoteWorkerConfigurationSnapshot(1L, previous), store.readExact())

            val emptyDirectory = Files.createTempDirectory("remote-worker-empty-failure").toFile()
            try {
                val emptyFailing = AppPrivateRemoteWorkerConfigurationStore(
                    emptyDirectory,
                    cipher,
                    RemoteWorkerPersistenceFaultInjector { checkpoint ->
                        if (
                            checkpoint ==
                            RemoteWorkerPersistenceCheckpoint.BEFORE_POST_WRITE_VERIFICATION
                        ) {
                            throw IOException("simulated first-write verification failure")
                        }
                    },
                )
                assertEquals(
                    RemoteWorkerConfigurationMutationResult.PERSISTENCE_FAILED,
                    emptyFailing.compareAndSet(0L, previous),
                )
                assertEquals(
                    RemoteWorkerConfigurationSnapshot(0L, null),
                    emptyFailing.readExact(),
                )
            } finally {
                emptyDirectory.deleteRecursively()
            }
        }

    @Test
    fun strictCodecRejectsUnknownFieldsAndDuplicateAdapters() {
        val valid = RemoteWorkerConfigurationCodec.encode(
            RemoteWorkerConfigurationSnapshot(1L, configuration(enabled = false)),
        )
        assertEquals(
            RemoteWorkerConfigurationSnapshot(1L, configuration(enabled = false)),
            RemoteWorkerConfigurationCodec.decode(valid),
        )
        val unknown = valid.replaceFirst("{", "{\"unexpected\":true,")
        assertTrue(runCatching { RemoteWorkerConfigurationCodec.decode(unknown) }.isFailure)

        val duplicate = valid.replace(
            "[{\"id\":\"linux.build\",\"version\":\"1.2\"}]",
            "[{\"id\":\"linux.build\",\"version\":\"1.2\"}," +
                "{\"id\":\"linux.build\",\"version\":\"1.2\"}]",
        )
        assertTrue(runCatching { RemoteWorkerConfigurationCodec.decode(duplicate) }.isFailure)
    }

    @Test
    fun symlinkedConfigurationIsNeverFollowed() = withStore { directory, store, _ ->
        val outside = Files.createTempFile("remote-worker-outside", ".enc").toFile()
        try {
            val target = File(directory, "remote-worker-configuration-v1.enc")
            Files.createSymbolicLink(target.toPath(), outside.toPath())
            val public = store.passiveState()
            assertFalse(public.storageHealthy)
            assertFalse(public.enabled)
            assertEquals(
                RemoteWorkerConfigurationMutationResult.CORRUPT,
                store.compareAndSet(0L, configuration(enabled = true)),
            )
        } finally {
            outside.delete()
        }
    }

    @Test
    fun setupFactoryDerivesStableOpaqueKeystoreAliasWithoutEmbeddingEndpointOrPin() {
        val first = remoteWorkerConnectionIdentity(
            "worker-1",
            "https://worker.example",
            "ab".repeat(32),
        )
        val again = remoteWorkerConnectionIdentity(
            "worker-1",
            "https://worker.example/",
            "ab".repeat(32),
        )

        assertEquals(first, again)
        assertTrue(first.deviceKeyAlias.startsWith("hans.remote."))
        assertFalse(first.deviceKeyAlias.contains("worker.example"))
        assertFalse(first.deviceKeyAlias.contains("ab".repeat(8)))
    }

    private fun configuration(enabled: Boolean): RemoteWorkerConfiguration =
        RemoteWorkerConfiguration(
            identity = RemoteWorkerConnectionIdentity(
                workerId = RemoteWorkerId("worker-1"),
                endpoint = "https://worker.example/",
                serverSpkiSha256 = "ab".repeat(32),
                deviceKeyAlias = "hans.remote.worker-1",
            ),
            approvedAdapters = listOf(
                RemoteWorkAdapterApproval(RemoteWorkAdapterId("linux.build"), "1.2"),
            ),
            enabled = enabled,
        )

    private fun withStore(
        block: (File, AppPrivateRemoteWorkerConfigurationStore, TestCipher) -> Unit,
    ) {
        val directory = Files.createTempDirectory("remote-worker-config").toFile()
        try {
            val cipher = TestCipher()
            block(
                directory,
                AppPrivateRemoteWorkerConfigurationStore(directory, cipher),
                cipher,
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    /** Deterministic authenticated test cipher; Android production uses non-exportable AES-GCM. */
    private class TestCipher : RemoteWorkerConfigurationCipher {
        override fun encrypt(plaintext: ByteArray): ByteArray {
            val masked = plaintext.mapIndexed { index, byte ->
                (byte.toInt() xor MASK[index % MASK.size].toInt()).toByte()
            }.toByteArray()
            val tag = MessageDigest.getInstance("SHA-256").digest(masked)
            return tag + masked
        }

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            require(ciphertext.size > 32)
            val tag = ciphertext.copyOfRange(0, 32)
            val masked = ciphertext.copyOfRange(32, ciphertext.size)
            require(MessageDigest.getInstance("SHA-256").digest(masked).contentEquals(tag))
            return masked.mapIndexed { index, byte ->
                (byte.toInt() xor MASK[index % MASK.size].toInt()).toByte()
            }.toByteArray()
        }

        private companion object {
            val MASK = "test-only-remote-config-mask".toByteArray()
        }
    }
}
