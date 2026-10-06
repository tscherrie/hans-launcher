package ai.hans.standard.remotecontrol

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RemotePhoneToolsBridgeConfigTest {
    private val token = "a1".repeat(32)

    @Test
    fun nativeConfigurationUsesFixedLoopbackAndEnvironmentReferenceWithoutASecretInArguments() {
        val config = RemotePhoneToolsBridgeConfig(34567, token)
        assertEquals("http://127.0.0.1:34567/mcp", config.url)
        assertEquals(listOf(
            "-c", "mcp_servers.hans_phone.url=\"http://127.0.0.1:34567/mcp\"",
            "-c", "mcp_servers.hans_phone.bearer_token_env_var=\"HANS_PHONE_TOOLS_TOKEN\"",
            "-c", "mcp_servers.hans_phone.enabled=true",
            "-c", "mcp_servers.hans_phone.required=true",
            "-c", "mcp_servers.hans_phone.tool_timeout_sec=120",
        ), config.arguments())
        assertFalse(config.arguments().joinToString(" ").contains(token))
        assertFalse(config.toString().contains(token))
        assertFalse(config.toString().contains("34567"))
        assertEquals("RemotePhoneToolsBridgeConfig(<redacted>)", config.toString())
    }

    @Test
    fun configurationCannotInjectCommandsAddressesOrPermissionChanges() {
        val config = RemotePhoneToolsBridgeConfig(65535, token)
        assertEquals("http://127.0.0.1:65535/mcp", config.url)
        assertEquals(1024, RemotePhoneToolsBridgeConfig(1024, token).port)
        for (port in listOf(Int.MIN_VALUE, -1, 0, 1, 1023, 65536, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { RemotePhoneToolsBridgeConfig(port, token) }
        }
        for (invalid in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64),
            "g".repeat(64), "a".repeat(63) + "\n", token + "\";anything")) {
            val failure = assertThrows(IllegalArgumentException::class.java) {
                RemotePhoneToolsBridgeConfig(34567, invalid)
            }
            if (invalid.isNotEmpty()) {
                assertFalse("Credential validation must not echo input", failure.message.orEmpty().contains(invalid))
            }
        }
        val settings = config.arguments().filterIndexed { index, _ -> index % 2 == 1 }
        assertTrue(settings.all { it.startsWith("mcp_servers.hans_phone.") })
        assertFalse(settings.any { "approval_policy" in it || "sandbox" in it || "root" in it })
    }

    @Test
    fun runtimeOnlyPassesEphemeralBearerToAppServerAndRemovesItFromCodeModeHost() {
        val runtime = source("runtime/RuntimeService.kt")
        val transport = source("integration/BinderSessionRuntimeTransport.kt")
        assertTrue("Bridge Binder call must be same-UID gated", Regex(
            "override fun configurePhoneToolsBridge[\\s\\S]*?getCallingUid\\(\\) == Process.myUid\\(\\)",
        ).containsMatchIn(runtime))
        val environmentProvider = runtime.substringAfter("environmentProvider = { directories ->")
            .substringBefore("argumentsProvider = { directories, environment ->")
        assertTrue(environmentProvider.contains("RemotePhoneToolsBridgeConfig.TOKEN_ENV to it.token"))
        assertTrue(environmentProvider.contains("RemotePhoneToolsBridgeConfig.PORT_ENV to it.port.toString()"))
        val argumentsProvider = runtime.substringAfter("argumentsProvider = { directories, environment ->")
            .substringBefore("clientVersion =")
        assertTrue("Code Mode must receive neither bridge credential nor port", Regex(
            "environment\\s*=\\s*environment\\s*-\\s*" +
                "ai\\.hans\\.standard\\.remotecontrol\\.RemotePhoneToolsBridgeConfig\\.TOKEN_ENV\\s*-\\s*" +
                "ai\\.hans\\.standard\\.remotecontrol\\.RemotePhoneToolsBridgeConfig\\.PORT_ENV",
        ).containsMatchIn(argumentsProvider))
        assertFalse("Arguments must use the captured environment, not reread a mutable bridge", argumentsProvider.contains("phoneToolsBridge"))
        assertTrue(argumentsProvider.contains("environment[ai.hans.standard.remotecontrol.RemotePhoneToolsBridgeConfig.TOKEN_ENV]"))
        assertTrue(argumentsProvider.contains("environment.getValue(ai.hans.standard.remotecontrol.RemotePhoneToolsBridgeConfig.PORT_ENV).toInt(), token"))
        assertTrue(argumentsProvider.contains(").arguments()"))
        val configure = transport.indexOf("runtime.configurePhoneToolsBridge(")
        val start = transport.indexOf("runtime.startAppServerSession(")
        assertTrue(configure >= 0 && configure < start)
        assertTrue(transport.contains("phoneToolsBridge?.port ?: 0, phoneToolsBridge?.token.orEmpty()"))
        val configSource = source("remotecontrol/RemotePhoneToolsBridgeConfig.kt")
        assertFalse(configSource.contains("SharedPreferences"))
        assertFalse(configSource.contains("writeText"))
        assertFalse(configSource.contains("FileOutputStream"))
    }

    private fun source(relative: String): String {
        val cwd = File(checkNotNull(System.getProperty("user.dir"))).absoluteFile
        val file = generateSequence(cwd) { it.parentFile }.map {
            File(it, "android/app/src/main/java/ai/hans/standard/$relative")
        }.firstOrNull(File::isFile)
        return checkNotNull(file) { "Source contract input is unavailable" }.readText()
    }
}
