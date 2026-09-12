package ai.hans.standard.runtime

import ai.hans.standard.runtime.network.RuntimeNetworkEnvironment
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class CodexRuntimeContractTest {
    @Test
    fun executablePathStaysInNativeLibraryDirectory() {
        assertEquals(
            "/data/app/example/lib/arm64/libcodex_app_server.so",
            CodexRuntimeContract.executableFile("/data/app/example/lib/arm64").path,
        )
        assertThrows(IllegalArgumentException::class.java) {
            CodexRuntimeContract.executableFile("data/app/example")
        }
    }

    @Test
    fun runtimeDirectoriesAndEnvironmentAreAppPrivateAndAllowlisted() {
        val directories = CodexRuntimeContract.directories(
            noBackupFilesDirectory = File("/data/user/0/ai.hans.standard/no_backup"),
            cacheDirectory = File("/data/user/0/ai.hans.standard/cache"),
        )
        val environment = CodexRuntimeContract.controlledEnvironment(
            directories = directories,
            androidRoot = "/system",
            androidData = "/data",
            network = RuntimeNetworkEnvironment(
                caBundleFile = File("/data/user/0/ai.hans.standard/no_backup/codex/runtime/network/ca.pem"),
                proxyUrl = "http://hans:redacted@127.0.0.1:12345",
            ),
        )

        assertEquals(
            setOf(
                "ALL_PROXY",
                "ANDROID_DATA",
                "ANDROID_ROOT",
                "CODEX_HOME",
                "HOME",
                "HTTPS_PROXY",
                "HTTP_PROXY",
                "LANG",
                "NO_PROXY",
                "PATH",
                "SHELL",
                "SSL_CERT_FILE",
                "TMPDIR",
            ),
            environment.keys,
        )
        assertEquals(directories.homeDirectory.path, environment.getValue("HOME"))
        assertEquals(directories.codexHomeDirectory.path, environment.getValue("CODEX_HOME"))
        assertEquals(directories.temporaryDirectory.path, environment.getValue("TMPDIR"))
        assertEquals(environment.getValue("HTTP_PROXY"), environment.getValue("HTTPS_PROXY"))
        assertEquals(environment.getValue("HTTP_PROXY"), environment.getValue("ALL_PROXY"))
        assertEquals("localhost,127.0.0.1,::1", environment.getValue("NO_PROXY"))
        assertTrue(directories.workingDirectory.path.startsWith("/data/user/0/ai.hans.standard/"))
    }

    @Test
    fun initializeRequestMatchesPinnedJsonRpcContract() {
        val request = JSONObject(CodexRuntimeContract.initializeRequest("0.1.0-test"))

        assertEquals(CodexRuntimeContract.INITIALIZE_REQUEST_ID, request.getLong("id"))
        assertEquals("initialize", request.getString("method"))
        val params = request.getJSONObject("params")
        assertEquals("hans_android_runtime", params.getJSONObject("clientInfo").getString("name"))
        assertEquals("0.1.0-test", params.getJSONObject("clientInfo").getString("version"))
        val capabilities = params.getJSONObject("capabilities")
        assertEquals(
            setOf("experimentalApi", "optOutNotificationMethods"),
            capabilities.keys().asSequence().toSet(),
        )
        assertTrue(capabilities.getBoolean("experimentalApi"))
        assertEquals(
            listOf("app/list/updated"),
            capabilities.getJSONArray("optOutNotificationMethods").let { methods ->
                (0 until methods.length()).map(methods::getString)
            },
        )
    }

    @Test
    fun versionAndInitializeEvidenceMustMatch() {
        assertEquals("0.154.0", CodexRuntimeContract.parseVersion("codex-app-server 0.154.0\n"))
        assertNull(CodexRuntimeContract.parseVersion("codex 0.154.0"))

        val response = """{
            "id":1,
            "result":{
                "userAgent":"hans_android_runtime/0.154.0",
                "codexHome":"/data/user/0/ai.hans.standard/no_backup/codex/home",
                "platformFamily":"unix",
                "platformOs":"linux"
            }
        }""".trimIndent()
        val parsed = CodexRuntimeContract.parseInitializeResponse(
            responseJson = response,
            expectedCodexHome = "/data/user/0/ai.hans.standard/no_backup/codex/home",
        )

        assertEquals("unix", parsed.platformFamily)
        assertEquals("linux", parsed.platformOs)
        assertThrows(IllegalArgumentException::class.java) {
            CodexRuntimeContract.parseInitializeResponse(
                response.replace("\"id\":1", "\"id\":2"),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            CodexRuntimeContract.parseInitializeResponse(
                responseJson = response,
                expectedCodexHome = "/data/user/0/other/home",
            )
        }
    }

    @Test
    fun boundedReaderSkipsNotificationsAndReturnsMatchingResponse() {
        val stream = ByteArrayInputStream(
            ("""{"method":"configWarning","params":{"message":"missing bwrap"}}""" + "\n" +
                """{"id":1,"result":{"platformFamily":"unix"}}""" + "\n")
                .toByteArray(),
        )

        assertEquals(
            """{"id":1,"result":{"platformFamily":"unix"}}""",
            BoundedStreams.readJsonRpcResponse(stream, 1, 4096, 2048),
        )
    }

    @Test
    fun boundedReadersRejectOversizedOutput() {
        assertThrows(IllegalStateException::class.java) {
            BoundedStreams.readToEnd(ByteArrayInputStream(ByteArray(17)), 16)
        }
        assertThrows(IllegalStateException::class.java) {
            BoundedStreams.readJsonRpcResponse(
                ByteArrayInputStream(("{" + "x".repeat(32) + "}\n").toByteArray()),
                requestId = 1,
                maximumTotalBytes = 1024,
                maximumLineBytes = 16,
            )
        }
    }
}
