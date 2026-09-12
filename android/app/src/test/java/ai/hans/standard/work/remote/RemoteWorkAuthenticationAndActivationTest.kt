package ai.hans.standard.work.remote

import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWorkAuthenticationAndActivationTest {
    @Test
    fun authenticatorSignsExactCanonicalRequestWithoutExportingAlias() {
        val keys = JvmSigningKeys()
        val identity = identity()
        val authenticator = AndroidKeystoreRemoteWorkAuthenticator(
            nowEpochMillis = { 1234L },
            nonceFactory = { ByteArray(24) { it.toByte() } },
            signingKeys = keys,
        )

        val proof = authenticator.authenticate(identity, "POST", "/v1/probe", "{}".toByteArray())
        val canonical = canonicalSignatureInput(
            identity.configurationDigest,
            proof.publicKeySpkiSha256,
            proof.timestampEpochMillis,
            proof.nonce,
            "POST",
            "/v1/probe",
            proof.bodySha256,
        )

        assertTrue(keys.verify(canonical, proof.signatureBase64Url))
        assertFalse(proof.toString().contains(identity.deviceKeyAlias))
    }

    @Test
    fun disabledConfigurationNeverProbesAndExactAdapterVersionIsRequired() {
        var probes = 0
        val identity = identity()
        val descriptor = RemoteWorkAdapterDescriptor(
            RemoteWorkAdapterId("linux.build"),
            "1.2",
            "Pinned build adapter",
        )
        val transport = NoopTransport(
            probe = RemoteWorkerProbeReceipt(
                worker = identity.workerId,
                configurationDigest = identity.configurationDigest,
                protocolVersion = REMOTE_WORK_PROTOCOL_VERSION,
                adapters = listOf(descriptor),
                observedAtEpochMillis = 1_000L,
            ),
            onProbe = { probes += 1 },
        )
        val activator = RemoteWorkerActivationProbe(
            transport = transport,
            nowEpochMillis = { 1_000L },
            freshnessMillis = 10_000L,
        )

        assertNull(
            activator.activate(
                RemoteWorkerConfiguration(
                    identity,
                    listOf(RemoteWorkAdapterApproval(descriptor.id, descriptor.version)),
                ),
            ),
        )
        assertEquals(0, probes)

        val active = activator.activate(
            RemoteWorkerConfiguration(
                identity,
                listOf(RemoteWorkAdapterApproval(descriptor.id, descriptor.version)),
                enabled = true,
            ),
        )
        assertEquals(listOf(descriptor), active?.adapters)
        assertEquals(1, probes)

        assertFails {
            activator.activate(
                RemoteWorkerConfiguration(
                    identity,
                    listOf(RemoteWorkAdapterApproval(descriptor.id, "1.3")),
                    enabled = true,
                ),
            )
        }
    }

    @Test
    fun localDeviceAdaptersAndStaleReceiptsFailClosed() {
        listOf("android.intent", "phone.call", "camera.capture", "location.read").forEach { id ->
            assertFails { RemoteWorkAdapterId(id) }
        }
        val identity = identity()
        val descriptor = RemoteWorkAdapterDescriptor(
            RemoteWorkAdapterId("linux.test"),
            "1",
            "Tests",
        )
        val stale = NoopTransport(
            RemoteWorkerProbeReceipt(
                identity.workerId,
                identity.configurationDigest,
                REMOTE_WORK_PROTOCOL_VERSION,
                listOf(descriptor),
                999L,
            ),
        )
        assertFails {
            RemoteWorkerActivationProbe(stale, nowEpochMillis = { 1_000L }).activate(
                RemoteWorkerConfiguration(
                    identity,
                    listOf(RemoteWorkAdapterApproval(descriptor.id, descriptor.version)),
                    enabled = true,
                ),
            )
        }
    }

    private class JvmSigningKeys : RemoteWorkSigningKeyProvider {
        private val pair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        override fun publicKeySpki(alias: String): ByteArray = pair.public.encoded

        override fun sign(alias: String, canonicalRequest: ByteArray): ByteArray =
            Signature.getInstance("SHA256withECDSA").run {
                initSign(pair.private)
                update(canonicalRequest)
                sign()
            }

        fun verify(canonical: ByteArray, encoded: String): Boolean =
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(pair.public)
                update(canonical)
                verify(java.util.Base64.getUrlDecoder().decode(encoded))
            }
    }

    private class NoopTransport(
        private val probe: RemoteWorkerProbeReceipt,
        private val onProbe: () -> Unit = {},
    ) : RemoteWorkerTransport {
        override fun probe(identity: RemoteWorkerConnectionIdentity): RemoteWorkerProbeReceipt {
            onProbe()
            return probe
        }

        override fun submit(identity: RemoteWorkerConnectionIdentity, request: RemoteWorkRequest, payloads: RemoteWorkPayloadSource): RemoteWorkStatusReceipt = unsupported()
        override fun status(identity: RemoteWorkerConnectionIdentity, operationId: RemoteWorkOperationId, executionId: RemoteExecutionId): RemoteWorkStatusReceipt = unsupported()
        override fun lookup(identity: RemoteWorkerConnectionIdentity, operationId: RemoteWorkOperationId, requestDigest: String): RemoteWorkStatusReceipt? = unsupported()
        override fun cancel(identity: RemoteWorkerConnectionIdentity, operationId: RemoteWorkOperationId, executionId: RemoteExecutionId): RemoteWorkStatusReceipt = unsupported()
        override fun download(identity: RemoteWorkerConnectionIdentity, operationId: RemoteWorkOperationId, executionId: RemoteExecutionId, artifact: RemoteWorkResultArtifact): RemoteWorkDownloadLease = unsupported()

        private fun <T> unsupported(): T = error("unused")
    }

    private fun identity() = RemoteWorkerConnectionIdentity(
        workerId = RemoteWorkerId("worker-1"),
        endpoint = "https://worker.example/",
        serverSpkiSha256 = "ab".repeat(32),
        deviceKeyAlias = "hans.remote.worker-1",
    )

    private fun assertFails(block: () -> Unit) {
        assertTrue(runCatching(block).isFailure)
    }
}
