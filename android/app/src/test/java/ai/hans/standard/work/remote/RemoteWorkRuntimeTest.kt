package ai.hans.standard.work.remote

import ai.hans.standard.artifacts.ArtifactOrigin
import ai.hans.standard.artifacts.AtomicArtifactStore
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWorkRuntimeTest {
    @Test
    fun ambiguousSubmitUsesExactLookupAndNeverResubmits() {
        val fixture = fixture()
        val request = fixture.request("submit-ambiguous")
        val source = EmptyPayloads
        val digest = RemoteWorkRequestDigest.compute(request, emptyList())
        fixture.transport.submitFailure = RemoteWorkFailure("remote_work_transport_failed")

        assertTrue(runCatching { fixture.runtime.submit(request, source) }.exceptionOrNull() is RemoteWorkAmbiguousFailure)
        assertEquals(RemoteWorkPhase.AMBIGUOUS, fixture.journal.read(request.operationId).phase)
        assertEquals(1, fixture.transport.submitCount)

        fixture.transport.lookupReceipt = fixture.receipt(
            request,
            digest,
            RemoteWorkPhase.ACCEPTED,
        )
        val recovered = fixture.runtime.recoverAmbiguous(request.operationId)
        assertEquals(RemoteWorkPhase.ACCEPTED, recovered.phase)
        assertEquals(1, fixture.transport.lookupCount)

        assertEquals(recovered, fixture.runtime.submit(request, source))
        assertEquals(1, fixture.transport.submitCount)
    }

    @Test
    fun ambiguousCancelRecoversByStatusWithoutRepeatingCancel() {
        val fixture = fixture()
        val request = fixture.request("cancel-ambiguous")
        val digest = RemoteWorkRequestDigest.compute(request, emptyList())
        fixture.transport.submitReceipt = fixture.receipt(request, digest, RemoteWorkPhase.RUNNING)
        fixture.runtime.submit(request, EmptyPayloads)
        fixture.transport.cancelFailure = RemoteWorkFailure("remote_work_transport_failed")

        assertTrue(runCatching { fixture.runtime.cancel(request.operationId) }.exceptionOrNull() is RemoteWorkAmbiguousFailure)
        assertEquals(1, fixture.transport.cancelCount)

        fixture.transport.statusReceipt = fixture.receipt(request, digest, RemoteWorkPhase.CANCELLED)
        assertEquals(RemoteWorkPhase.CANCELLED, fixture.runtime.recoverAmbiguous(request.operationId).phase)
        assertEquals(1, fixture.transport.cancelCount)
        assertEquals(1, fixture.transport.statusCount)
    }

    @Test
    fun verifiedArtifactsImportExactlyOnceAndColdJournalRecoveryIsOffline() {
        val fixture = fixture()
        val request = fixture.request("import-result")
        val digest = RemoteWorkRequestDigest.compute(request, emptyList())
        val bytes = "verified remote result".toByteArray()
        val remote = RemoteWorkResultArtifact(
            opaqueRemoteId = "result-1",
            displayName = "result.txt",
            mimeType = "text/plain",
            byteCount = bytes.size.toLong(),
            sha256 = sha256(bytes),
        )
        fixture.transport.submitReceipt = fixture.receipt(
            request,
            digest,
            RemoteWorkPhase.RESULT_PROVEN,
            listOf(remote),
            "{\"summary\":\"done\"}",
        )
        fixture.transport.downloadBytes = bytes
        fixture.runtime.submit(request, EmptyPayloads)

        val first = fixture.runtime.import(request.operationId)
        val second = fixture.runtime.import(request.operationId)
        assertEquals(first, second)
        assertEquals(1, fixture.transport.downloadCount)
        val metadata = fixture.artifacts.metadata(first.artifacts.single().localHandle)
        assertEquals(ArtifactOrigin.REMOTE_WORKER, metadata.origin)
        assertEquals(digest, metadata.workspaceHandle)

        val coldJournal = RemoteWorkJournal(fixture.journalDirectory, nowEpochMillis = { 1_000L })
        assertTrue(coldJournal.isAvailable())
        val coldRuntime = RemoteWorkRuntime(
            fixture.activation,
            fixture.transport,
            coldJournal,
            fixture.artifacts,
            nowEpochMillis = { 1_000L },
        )
        assertEquals(first, coldRuntime.import(request.operationId))
        assertEquals(1, fixture.transport.downloadCount)
    }

    @Test
    fun journalContainsOnlyDigestsAndOpaqueIdsAndCorruptionFailsClosed() {
        val fixture = fixture()
        val request = fixture.request("journal-safe")
        val digest = RemoteWorkRequestDigest.compute(request, emptyList())
        fixture.transport.submitReceipt = fixture.receipt(request, digest, RemoteWorkPhase.ACCEPTED)
        fixture.runtime.submit(request, EmptyPayloads)

        val file = fixture.journalDirectory.listFiles().single { it.name.endsWith(".json") }
        val text = file.readText()
        assertFalse(text.contains(fixture.activation.configuration.identity.endpoint))
        assertFalse(text.contains(fixture.activation.configuration.identity.deviceKeyAlias))

        file.writeText("{corrupt")
        assertTrue(runCatching { RemoteWorkJournal(fixture.journalDirectory).read(request.operationId) }.isFailure)
    }

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("remote-work-runtime-test").toFile()
        val journalDirectory = File(root, "journal")
        val artifactBoundary = File(root, "private").apply { mkdirs() }
        val artifacts = AtomicArtifactStore(File(artifactBoundary, "artifacts"), artifactBoundary)
        val identity = RemoteWorkerConnectionIdentity(
            RemoteWorkerId("worker-1"),
            "https://worker.example/",
            "ab".repeat(32),
            "remote.key",
        )
        val adapter = RemoteWorkAdapterDescriptor(
            RemoteWorkAdapterId("linux.build"),
            "1",
            "Build",
        )
        val activation = RemoteWorkerActivation(
            RemoteWorkerConfiguration(
                identity,
                listOf(RemoteWorkAdapterApproval(adapter.id, adapter.version)),
                enabled = true,
            ),
            RemoteWorkerProbeReceipt(
                identity.workerId,
                identity.configurationDigest,
                REMOTE_WORK_PROTOCOL_VERSION,
                listOf(adapter),
                1_000L,
            ),
            listOf(adapter),
            20_000L,
        )
        val transport = FakeTransport(identity)
        val journal = RemoteWorkJournal(journalDirectory, nowEpochMillis = { 1_000L })
        val runtime = RemoteWorkRuntime(
            activation,
            transport,
            journal,
            artifacts,
            nowEpochMillis = { 1_000L },
        )
        return Fixture(activation, transport, journal, journalDirectory, artifacts, runtime, adapter.id)
    }

    private data class Fixture(
        val activation: RemoteWorkerActivation,
        val transport: FakeTransport,
        val journal: RemoteWorkJournal,
        val journalDirectory: File,
        val artifacts: AtomicArtifactStore,
        val runtime: RemoteWorkRuntime,
        val adapter: RemoteWorkAdapterId,
    ) {
        fun request(id: String) = RemoteWorkRequest(
            RemoteWorkOperationId(id),
            activation.configuration.identity.workerId,
            adapter,
            workspace = null,
            inputArtifacts = emptyList(),
            argumentsJson = "{}",
            limits = RemoteWorkLimits(),
        )

        fun receipt(
            request: RemoteWorkRequest,
            digest: String,
            phase: RemoteWorkPhase,
            artifacts: List<RemoteWorkResultArtifact> = emptyList(),
            output: String? = null,
        ) = RemoteWorkStatusReceipt(
            request.operationId,
            RemoteExecutionId("execution-1"),
            request.worker,
            activation.configuration.identity.configurationDigest,
            digest,
            phase,
            artifacts,
            output,
            1_000L,
        )
    }

    private class FakeTransport(
        private val identity: RemoteWorkerConnectionIdentity,
    ) : RemoteWorkerTransport {
        var submitCount = 0
        var statusCount = 0
        var lookupCount = 0
        var cancelCount = 0
        var downloadCount = 0
        var submitFailure: Throwable? = null
        var cancelFailure: Throwable? = null
        var submitReceipt: RemoteWorkStatusReceipt? = null
        var statusReceipt: RemoteWorkStatusReceipt? = null
        var lookupReceipt: RemoteWorkStatusReceipt? = null
        var downloadBytes: ByteArray = ByteArray(0)

        override fun probe(identity: RemoteWorkerConnectionIdentity): RemoteWorkerProbeReceipt = error("unused")

        override fun submit(identity: RemoteWorkerConnectionIdentity, request: RemoteWorkRequest, payloads: RemoteWorkPayloadSource): RemoteWorkStatusReceipt {
            submitCount += 1
            submitFailure?.let { throw it }
            return requireNotNull(submitReceipt)
        }

        override fun status(identity: RemoteWorkerConnectionIdentity, operationId: RemoteWorkOperationId, executionId: RemoteExecutionId): RemoteWorkStatusReceipt {
            statusCount += 1
            return requireNotNull(statusReceipt)
        }

        override fun lookup(identity: RemoteWorkerConnectionIdentity, operationId: RemoteWorkOperationId, requestDigest: String): RemoteWorkStatusReceipt? {
            lookupCount += 1
            return lookupReceipt
        }

        override fun cancel(identity: RemoteWorkerConnectionIdentity, operationId: RemoteWorkOperationId, executionId: RemoteExecutionId): RemoteWorkStatusReceipt {
            cancelCount += 1
            cancelFailure?.let { throw it }
            return requireNotNull(statusReceipt)
        }

        override fun download(identity: RemoteWorkerConnectionIdentity, operationId: RemoteWorkOperationId, executionId: RemoteExecutionId, artifact: RemoteWorkResultArtifact): RemoteWorkDownloadLease {
            downloadCount += 1
            return object : RemoteWorkDownloadLease {
                override val artifact = artifact
                override val input = ByteArrayInputStream(downloadBytes)
                override fun close() = input.close()
            }
        }
    }

    private object EmptyPayloads : RemoteWorkPayloadSource {
        override fun descriptors(request: RemoteWorkRequest) = emptyList<RemoteWorkPayloadDescriptor>()
        override fun open(descriptor: RemoteWorkPayloadDescriptor): RemoteWorkPayloadLease = error("unused")
    }
}
