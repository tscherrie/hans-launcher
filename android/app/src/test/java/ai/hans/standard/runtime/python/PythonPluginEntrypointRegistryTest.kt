package ai.hans.standard.runtime.python

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PythonPluginEntrypointRegistryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun preparedAndCommittedEntrypointsRecoverButStayUncallableUntilFinalize() {
        val environmentDigest = "a".repeat(64)
        val sourceDigest = "b".repeat(64)
        val file = File(temporaryFolder.root, "entrypoints.json")
        val registry = PythonPluginEntrypointRegistry(file)
        val activation = activation(environmentDigest, sourceDigest)
        val receipt = registry.prepareActivation(activation)

        assertRejected(
            PythonPluginEntrypointRegistry.ERROR_UNCOMMITTED,
            registry.resolve("garage", "open-door", environmentDigest),
        )
        val preparedRestart = PythonPluginEntrypointRegistry(file)
        val preparedRecovery = preparedRestart.recoveryDescriptors().single()
        assertEquals(PythonPluginEntrypointRecoveryState.PREPARED, preparedRecovery.state)
        assertEquals(receipt.transactionId, preparedRecovery.receipt.transactionId)
        assertEquals(activation.metadataDigest, preparedRecovery.activation.metadataDigest)
        expectFailure("source postcondition") {
            preparedRestart.commitActivation(
                preparedRecovery.receipt,
                environmentDigest,
                "c".repeat(64),
            )
        }
        assertRejected(
            PythonPluginEntrypointRegistry.ERROR_UNCOMMITTED,
            preparedRestart.resolve("garage", "open-door", environmentDigest),
        )

        preparedRestart.commitActivation(preparedRecovery.receipt, environmentDigest, sourceDigest)
        preparedRestart.commitActivation(preparedRecovery.receipt, environmentDigest, sourceDigest)
        assertRejected(
            PythonPluginEntrypointRegistry.ERROR_UNCOMMITTED,
            preparedRestart.resolve("garage", "open-door", environmentDigest),
        )

        val committedRestart = PythonPluginEntrypointRegistry(file)
        val committedRecovery = committedRestart.recoveryDescriptors().single()
        assertEquals(PythonPluginEntrypointRecoveryState.COMMITTED, committedRecovery.state)
        assertRejected(
            PythonPluginEntrypointRegistry.ERROR_UNCOMMITTED,
            committedRestart.resolve("garage", "open-door", environmentDigest),
        )
        committedRestart.finalizeActivation(committedRecovery.receipt)
        val resolved = committedRestart.resolve("garage", "open-door", environmentDigest)
            as PythonPluginEntrypointResolution.Resolved
        assertEquals("garage", resolved.pluginId)
        assertEquals("open-door", resolved.entrypointId)
        assertEquals("garage/actions.py", resolved.relativePath)
        assertEquals(setOf("phone.read", "phone.write"), resolved.declaredCapabilities)
        assertFalse(resolved.toString().contains("actions.py"))
        expectFailure("unknown") {
            committedRestart.finalizeActivation(committedRecovery.receipt)
        }
        assertFalse(committedRestart.rollbackActivation(committedRecovery.receipt))

        val restarted = PythonPluginEntrypointRegistry(file)
        assertTrue(restarted.status().available)
        assertEquals(1, restarted.status().committedEntrypointCount)
        assertTrue(
            restarted.resolve("garage", "open-door", environmentDigest) is
                PythonPluginEntrypointResolution.Resolved,
        )
        assertRejected(
            PythonPluginEntrypointRegistry.ERROR_STALE_ENVIRONMENT,
            restarted.resolve("garage", "open-door", "d".repeat(64)),
        )
    }

    @Test
    fun rollbackAndUnknownIdsRemainMissing() {
        val environmentDigest = "1".repeat(64)
        val sourceDigest = "2".repeat(64)
        val registry = PythonPluginEntrypointRegistry(File(temporaryFolder.root, "registry.json"))
        val receipt = registry.prepareActivation(activation(environmentDigest, sourceDigest))

        val recovered = PythonPluginEntrypointRegistry(
            File(temporaryFolder.root, "registry.json"),
        )
        val recoveryReceipt = recovered.recoveryDescriptors().single().receipt
        assertEquals(receipt.transactionId, recoveryReceipt.transactionId)
        assertTrue(recovered.rollbackActivation(recoveryReceipt))
        assertFalse(recovered.rollbackActivation(recoveryReceipt))
        assertRejected(
            PythonPluginEntrypointRegistry.ERROR_MISSING,
            recovered.resolve("garage", "open-door", environmentDigest),
        )
        assertRejected(
            PythonPluginEntrypointRegistry.ERROR_MISSING,
            recovered.resolve("garage", "undeclared", environmentDigest),
        )
    }

    @Test
    fun committedJournalBeforeRegistryWriteCanResumeCommitAfterProcessDeath() {
        val environmentDigest = "e".repeat(64)
        val sourceDigest = "f".repeat(64)
        val prepared = preparedJournal(
            "commit-before-registry-write",
            environmentDigest,
            sourceDigest,
        )
        val journalRecord = PythonPluginEntrypointActivationJournalCodec.decode(
            prepared.journal.readBytes(),
        )
        prepared.journal.writeBytes(
            PythonPluginEntrypointActivationJournalCodec.encode(
                journalRecord.copy(state = PythonPluginEntrypointRecoveryState.COMMITTED),
            ),
        )

        val recovered = PythonPluginEntrypointRegistry(prepared.state)
        val recovery = recovered.recoveryDescriptors().single()

        assertEquals(PythonPluginEntrypointRecoveryState.COMMITTED, recovery.state)
        assertRejected(
            PythonPluginEntrypointRegistry.ERROR_UNCOMMITTED,
            recovered.resolve("garage", "open-door", environmentDigest),
        )
        recovered.commitActivation(recovery.receipt, environmentDigest, sourceDigest)
        assertRejected(
            PythonPluginEntrypointRegistry.ERROR_UNCOMMITTED,
            recovered.resolve("garage", "open-door", environmentDigest),
        )
        recovered.finalizeActivation(recovery.receipt)
        assertTrue(
            recovered.resolve("garage", "open-door", environmentDigest) is
                PythonPluginEntrypointResolution.Resolved,
        )
    }

    @Test
    fun sourceIdentityAndCompleteProofAreMandatoryAndCallerCollectionsAreCopied() {
        val environmentDigest = "3".repeat(64)
        val sourceDigest = "4".repeat(64)
        val callerCapabilities = mutableSetOf("phone.read")
        val declarations = mutableListOf(
            PythonPluginEntrypointDeclaration(
                entrypointId = "open-door",
                relativePath = "garage/actions.py",
                function = "run",
                sourceSha256 = sourceDigest,
                declaredCapabilities = callerCapabilities,
            ),
        )
        val proven = mutableSetOf("open-door")
        val activation = PythonPluginEntrypointActivation(
            pluginId = "garage",
            environmentDigest = environmentDigest,
            sourceSha256 = sourceDigest,
            declarations = declarations,
            provenEntrypointIds = proven,
        )
        declarations.clear()
        proven.clear()
        callerCapabilities.clear()
        assertEquals(1, activation.declarations.size)
        assertEquals(setOf("open-door"), activation.provenEntrypointIds)
        assertEquals(setOf("phone.read"), activation.declarations.single().declaredCapabilities)

        expectFailure("source identity") {
            PythonPluginEntrypointActivation(
                pluginId = "garage",
                environmentDigest = environmentDigest,
                sourceSha256 = sourceDigest,
                declarations = listOf(declaration("5".repeat(64))),
                provenEntrypointIds = setOf("open-door"),
            )
        }
        expectFailure("must be proven") {
            PythonPluginEntrypointActivation(
                pluginId = "garage",
                environmentDigest = environmentDigest,
                sourceSha256 = sourceDigest,
                declarations = listOf(declaration(sourceDigest)),
                provenEntrypointIds = emptySet(),
            )
        }
    }

    @Test
    fun tamperedDurableMetadataMakesTheWholeRegistryFailClosed() {
        val environmentDigest = "6".repeat(64)
        val sourceDigest = "7".repeat(64)
        val file = File(temporaryFolder.root, "registry.json")
        val registry = PythonPluginEntrypointRegistry(file)
        val receipt = registry.prepareActivation(activation(environmentDigest, sourceDigest))
        registry.commitActivation(receipt, environmentDigest, sourceDigest)
        registry.finalizeActivation(receipt)
        file.writeText(file.readText().replace(sourceDigest, "8".repeat(64)))

        val reloaded = PythonPluginEntrypointRegistry(file)

        assertFalse(reloaded.status().available)
        assertRejected(
            PythonPluginEntrypointRegistry.ERROR_REGISTRY_INVALID,
            reloaded.resolve("garage", "open-door", environmentDigest),
        )
    }

    @Test
    fun corruptOversizeUnknownAndSymlinkJournalsFailClosed() {
        val corrupt = preparedJournal("corrupt")
        corrupt.journal.writeText("{not-json")
        assertFalse(PythonPluginEntrypointRegistry(corrupt.state).status().available)

        val oversized = preparedJournal("oversized")
        RandomAccessFile(oversized.journal, "rw").use {
            it.setLength(PythonPluginEntrypointActivationJournalCodec.MAX_BYTES.toLong() + 1)
        }
        assertFalse(PythonPluginEntrypointRegistry(oversized.state).status().available)

        val unknown = preparedJournal("unknown")
        val unknownRoot = JSONObject(unknown.journal.readText()).put(
            "executablePath",
            "/data/local/tmp/plugin",
        )
        unknown.journal.writeText(unknownRoot.toString())
        assertFalse(PythonPluginEntrypointRegistry(unknown.state).status().available)

        val tampered = preparedJournal("tampered")
        val tamperedRoot = JSONObject(tampered.journal.readText()).also { root ->
            root.getJSONObject("activation").put("environmentDigest", "e".repeat(64))
        }
        tampered.journal.writeText(tamperedRoot.toString())
        assertFalse(PythonPluginEntrypointRegistry(tampered.state).status().available)

        val linkedRoot = temporaryFolder.newFolder("linked")
        val linkedState = File(linkedRoot, "registry.json")
        val linkedJournalDirectory = journalDirectoryFor(linkedState).apply { mkdirs() }
        val outside = File(linkedRoot, "outside.json").apply { writeText("{}") }
        val linkedJournal = File(linkedJournalDirectory, "${"f".repeat(32)}.json")
        if (runCatching {
                Files.createSymbolicLink(linkedJournal.toPath(), outside.toPath())
            }.isSuccess
        ) {
            assertFalse(PythonPluginEntrypointRegistry(linkedState).status().available)
        }
    }

    @Test
    fun orphanedJournalTemporaryIsRemovedWithoutGrantingExecution() {
        val root = temporaryFolder.newFolder("orphan-temp")
        val state = File(root, "registry.json")
        val journalDirectory = journalDirectoryFor(state).apply { mkdirs() }
        val orphan = File(journalDirectory, ".orphaned-write.tmp").apply {
            writeText("partial")
        }

        val recovered = PythonPluginEntrypointRegistry(state)

        assertTrue(recovered.status().available)
        assertFalse(orphan.exists())
        assertTrue(recovered.recoveryDescriptors().isEmpty())
        assertRejected(
            PythonPluginEntrypointRegistry.ERROR_MISSING,
            recovered.resolve("garage", "open-door", "a".repeat(64)),
        )
    }

    @Test
    fun committedRegistryActivationCanRestoreItsExactPreviousVersionBeforeFinalize() {
        val firstEnvironment = "9".repeat(64)
        val secondEnvironment = "a".repeat(64)
        val firstSource = "b".repeat(64)
        val secondSource = "c".repeat(64)
        val file = File(temporaryFolder.root, "rollback.json")
        val registry = PythonPluginEntrypointRegistry(file)
        val first = registry.prepareActivation(activation(firstEnvironment, firstSource))
        registry.commitActivation(first, firstEnvironment, firstSource)
        registry.finalizeActivation(first)
        val second = registry.prepareActivation(activation(secondEnvironment, secondSource))
        registry.commitActivation(second, secondEnvironment, secondSource)

        assertTrue(
            registry.resolve("garage", "open-door", firstEnvironment) is
                PythonPluginEntrypointResolution.Resolved,
        )
        assertRejected(
            PythonPluginEntrypointRegistry.ERROR_STALE_ENVIRONMENT,
            registry.resolve("garage", "open-door", secondEnvironment),
        )
        val committedRestart = PythonPluginEntrypointRegistry(file)
        val recovery = committedRestart.recoveryDescriptors().single()
        assertEquals(PythonPluginEntrypointRecoveryState.COMMITTED, recovery.state)
        assertEquals(firstEnvironment, recovery.previous!!.environmentDigest)

        assertTrue(committedRestart.rollbackActivation(recovery.receipt))
        assertFalse(committedRestart.rollbackActivation(recovery.receipt))

        assertTrue(
            committedRestart.resolve("garage", "open-door", firstEnvironment) is
                PythonPluginEntrypointResolution.Resolved,
        )
        assertRejected(
            PythonPluginEntrypointRegistry.ERROR_STALE_ENVIRONMENT,
            committedRestart.resolve("garage", "open-door", secondEnvironment),
        )
        val restarted = PythonPluginEntrypointRegistry(file)
        assertTrue(
            restarted.resolve("garage", "open-door", firstEnvironment) is
                PythonPluginEntrypointResolution.Resolved,
        )
    }

    private fun activation(environmentDigest: String, sourceDigest: String) =
        PythonPluginEntrypointActivation(
            pluginId = "garage",
            environmentDigest = environmentDigest,
            sourceSha256 = sourceDigest,
            declarations = listOf(declaration(sourceDigest)),
            provenEntrypointIds = setOf("open-door"),
        )

    private fun declaration(sourceDigest: String) = PythonPluginEntrypointDeclaration(
        entrypointId = "open-door",
        relativePath = "garage/actions.py",
        function = "run",
        sourceSha256 = sourceDigest,
        declaredCapabilities = setOf("phone.read", "phone.write"),
    )

    private data class PreparedJournal(val state: File, val journal: File)

    private fun preparedJournal(
        name: String,
        environmentDigest: String = "a".repeat(64),
        sourceDigest: String = "b".repeat(64),
    ): PreparedJournal {
        val root = temporaryFolder.newFolder(name)
        val state = File(root, "registry.json")
        PythonPluginEntrypointRegistry(state).prepareActivation(
            activation(environmentDigest, sourceDigest),
        )
        return PreparedJournal(
            state = state,
            journal = journalDirectoryFor(state).listFiles().orEmpty().single {
                it.name.endsWith(PythonPluginEntrypointActivationJournalCodec.FILE_SUFFIX)
            },
        )
    }

    private fun journalDirectoryFor(state: File) =
        File(state.parentFile, ".${state.name}.activation-journal")

    private fun assertRejected(
        expected: String,
        resolution: PythonPluginEntrypointResolution,
    ) {
        assertTrue(resolution is PythonPluginEntrypointResolution.Rejected)
        assertEquals(expected, (resolution as PythonPluginEntrypointResolution.Rejected).errorCode)
    }

    private fun expectFailure(fragment: String, action: () -> Unit) {
        try {
            action()
            fail("Expected failure containing '$fragment'")
        } catch (failure: IllegalArgumentException) {
            assertTrue(failure.message.orEmpty().contains(fragment, ignoreCase = true))
        }
    }
}
