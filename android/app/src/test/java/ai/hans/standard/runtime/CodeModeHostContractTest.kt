package ai.hans.standard.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream

class CodeModeHostContractTest {
    @Test
    fun executablePathStaysInNativeLibraryDirectory() {
        assertEquals(
            "/data/app/example/lib/arm64/libcodex_code_mode_host.so",
            CodeModeHostContract.executableFile("/data/app/example/lib/arm64").path,
        )
        assertThrows(IllegalArgumentException::class.java) {
            CodeModeHostContract.executableFile("data/app/example")
        }
    }

    @Test
    fun loopbackEndpointBecomesAppServerArgument() {
        val endpoint = CodeModeHostContract.readEndpoint(
            ByteArrayInputStream("http://127.0.0.1:45678\n".toByteArray()),
        )

        assertEquals("http://127.0.0.1:45678", endpoint)
        assertEquals(
            CodexAssistantProfile.strictArguments() + listOf(
                "--code-mode-host",
                "http://127.0.0.1:45678",
            ),
            CodeModeHostContract.appServerArguments(endpoint),
        )
        assertEquals(
            listOf("--listen", "grpc://127.0.0.1:0"),
            CodeModeHostContract.launchArguments(),
        )
    }

    @Test
    fun mainAppServerStrictlyEnablesThePersonalAssistantProfileForEveryLaunch() {
        assertEquals(
            CodexAssistantProfile.strictArguments(),
            CodexAppServerV1Policy.strictPersonalAssistantArguments(),
        )
        val arguments = CodeModeHostContract.appServerArguments("http://127.0.0.1:45678")

        assertEquals("--strict-config", arguments.first())
        assertEquals(28, arguments.count { it == "-c" })
        assertEquals(1, arguments.count { it == "features.memories=true" })
        assertEquals(1, arguments.count { it == "memories.generate_memories=true" })
        assertEquals(1, arguments.count { it == "memories.use_memories=true" })
        assertEquals(1, arguments.count { it == "memories.dedicated_tools=true" })
        assertEquals(
            1,
            arguments.count { it == "memories.disable_on_external_context=false" },
        )
        assertEquals(0, arguments.count { it == "features.memories=false" })
        assertEquals(
            listOf("--code-mode-host", "http://127.0.0.1:45678"),
            arguments.takeLast(2),
        )
    }

    @Test
    fun endpointRejectsAnythingOutsideIpv4Loopback() {
        listOf(
            "http://0.0.0.0:1234",
            "http://example.com:1234",
            "https://127.0.0.1:1234",
            "ws://127.0.0.1:1234",
            "http://127.0.0.1:0",
            "http://127.0.0.1:1234/path",
            "http://user@127.0.0.1:1234",
        ).forEach { endpoint ->
            assertThrows(IllegalArgumentException::class.java) {
                CodeModeHostContract.validateEndpoint(endpoint)
            }
        }
    }
}
