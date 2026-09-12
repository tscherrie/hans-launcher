package ai.hans.standard.runtime.python

import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonRuntimeFdContractTest {
    @Test
    fun bootstrapManifestRoundTripsWithoutAnyMainUidPath() {
        val manifest = bootstrap()

        val encoded = PythonRuntimeFdContract.encodeBootstrap(manifest)
        val decoded = PythonRuntimeFdContract.decodeBootstrap(encoded)
        val native = JSONObject(
            PythonRuntimeFdContract.nativeBootstrapJson(decoded, 41, "/signed/native-libraries"),
        )

        assertEquals(manifest, decoded)
        assertEquals(41, native.getInt("stdlibFd"))
        assertEquals(7, native.getLong("expectedStdlibBytes"))
        assertFalse(native.has("stdlibZip"))
        assertFalse(native.has("environmentRoot"))
        assertFalse(native.has("runtimeRoot"))
        assertFalse(native.has("codeCacheDir"))
    }

    @Test
    fun executionLeaseMustMatchSessionRequestAndEffectiveEnvironmentDigest() {
        val request = request()
        val manifest = PythonRuntimeExecutionLeaseManifest(
            sessionNonce = NONCE,
            requestId = request.requestId,
            environmentDigest = request.environmentDigest,
            environmentBytes = 22,
            allowedNativeModules = listOf(MSGPACK_NATIVE),
        )
        val encoded = PythonRuntimeFdContract.encodeExecutionLease(manifest)

        assertEquals(
            manifest,
            PythonRuntimeFdContract.decodeExecutionLease(encoded, NONCE, request),
        )
        val otherNonce = "f".repeat(64)
        assertNotEquals(NONCE, otherNonce)
        assertTrue(
            runCatching {
                PythonRuntimeFdContract.decodeExecutionLease(encoded, otherNonce, request)
            }.isFailure,
        )
        assertTrue(
            runCatching {
                PythonRuntimeFdContract.decodeExecutionLease(
                    encoded,
                    NONCE,
                    request.copy(environmentDigest = "b".repeat(64)),
                )
            }.isFailure,
        )
    }

    @Test
    fun onlyTheServiceCanAttachANumericRuntimeLease() {
        val request = request().copy(workspaceHandle = "c".repeat(64))
        val publicJson = PythonRuntimeContract.encodeRequest(request, 1_000)
        val manifest = PythonRuntimeExecutionLeaseManifest(
            NONCE,
            request.requestId,
            request.environmentDigest,
            22,
            PythonRuntimeWorkspaceLeaseManifest(
                workspaceHandle = request.workspaceHandle!!,
                archiveDigest = "d".repeat(64),
                archiveBytes = 22,
                fileCount = 1,
                contentBytes = 7,
            ),
            listOf(MSGPACK_NATIVE),
        )

        val internalJson = PythonRuntimeFdContract.attachExecutionLease(publicJson, manifest, 55, 56)
        val internal = JSONObject(internalJson)

        assertFalse(JSONObject(publicJson).has("runtimeLease"))
        assertEquals(55, internal.getJSONObject("runtimeLease").getInt("environmentFd"))
        assertEquals(
            "msgpack._cmsgpack",
            internal.getJSONArray("allowedNativeModules").getJSONObject(0).getString("module"),
        )
        assertEquals(
            "libhans_py_msgpack___cmsgpack.so",
            internal.getJSONObject("runtimeLease")
                .getJSONArray("allowedNativeModules")
                .getJSONObject(0)
                .getString("packagedName"),
        )
        assertEquals(
            56,
            internal.getJSONObject("runtimeLease").getJSONObject("workspace").getInt("workspaceFd"),
        )
        assertFalse(internalJson.contains("/data/"))
        assertTrue(
            runCatching { PythonRuntimeContract.decodeRequest(internalJson, 1_000) }.isFailure,
        )
        val forged = JSONObject(publicJson)
            .put("allowedNativeModules", org.json.JSONArray())
            .toString()
        assertTrue(
            runCatching {
                PythonRuntimeFdContract.attachExecutionLease(forged, manifest, 55, 56)
            }.isFailure,
        )
    }

    @Test
    fun workspaceLeaseRoundTripIsBoundToTheExactRequestedContentAddress() {
        val request = request().copy(workspaceHandle = "c".repeat(64))
        val manifest = PythonRuntimeExecutionLeaseManifest(
            sessionNonce = NONCE,
            requestId = request.requestId,
            environmentDigest = request.environmentDigest,
            environmentBytes = 22,
            workspace = PythonRuntimeWorkspaceLeaseManifest(
                workspaceHandle = request.workspaceHandle!!,
                archiveDigest = "d".repeat(64),
                archiveBytes = 128,
                fileCount = 2,
                contentBytes = 99,
            ),
        )
        val encoded = PythonRuntimeFdContract.encodeExecutionLease(manifest)

        assertEquals(manifest, PythonRuntimeFdContract.decodeExecutionLease(encoded, NONCE, request))
        assertTrue(
            runCatching {
                PythonRuntimeFdContract.decodeExecutionLease(
                    encoded,
                    NONCE,
                    request.copy(workspaceHandle = "e".repeat(64)),
                )
            }.isFailure,
        )
        assertTrue(
            runCatching {
                PythonRuntimeFdContract.attachExecutionLease(
                    PythonRuntimeContract.encodeRequest(request, 1_000),
                    manifest,
                    55,
                    null,
                )
            }.isFailure,
        )
    }

    @Test
    fun ownedDescriptorClosesExactlyOnceAcrossCompetingCleanupPaths() {
        val closes = AtomicInteger()
        val descriptor = PythonOwnedFileDescriptor(9) { closes.incrementAndGet() }

        descriptor.close()
        descriptor.close()
        descriptor.close()

        assertTrue(descriptor.isClosed())
        assertEquals(1, closes.get())
    }

    @Test
    fun sessionNonceIsHighEntropyFixedWidthAndComparedInConstantWork() {
        val first = PythonRuntimeFdContract.newSessionNonce()
        val second = PythonRuntimeFdContract.newSessionNonce()

        assertTrue(PythonRuntimeFdContract.isSessionNonce(first))
        assertTrue(PythonRuntimeFdContract.isSessionNonce(second))
        assertNotEquals(first, second)
        assertTrue(PythonRuntimeFdContract.constantTimeEquals(first, first))
        assertFalse(PythonRuntimeFdContract.constantTimeEquals(first, second))
    }

    private fun bootstrap() = PythonRuntimeBootstrapManifest(
        sessionNonce = NONCE,
        expectedPythonVersion = "3.14.2",
        expectedAbi = "arm64-v8a",
        expectedStdlibDigest = "a".repeat(64),
        expectedStdlibBytes = 7,
    )

    private fun request() = PythonExecutionRequest(
        requestId = "request-1",
        idempotencyKey = "idem-1",
        environmentDigest = "a".repeat(64),
        entrypoint = PythonEntrypoint(PythonEntrypointKind.CODE, source = "42"),
        argumentsJson = "{}",
        limits = PythonResourceLimits(61_000),
    )

    private companion object {
        val MSGPACK_NATIVE = PythonAllowedNativeModule(
            module = "msgpack._cmsgpack",
            packagedName = "libhans_py_msgpack___cmsgpack.so",
        )
        const val NONCE =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
