package ai.hans.standard.integration

import ai.hans.standard.remotecontrol.DesktopRemoteAccessPolicy
import ai.hans.standard.runtime.CodexRuntimeContract
import ai.hans.standard.runtime.network.RuntimeNetworkEnvironment
import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** No connection, pairing mutation, permission change or foreground-service acquisition. */
class DesktopRemoteAccessPolicyAndroidTest {
    @Test fun productBuildDisablesNativeRelayAndRejectsForegroundAcquisition() {
        assertFalse(DesktopRemoteAccessPolicy.enabled)
        val dirs = CodexRuntimeContract.directories(File("/data/user/0/ai.hans.standard/no_backup"),
            File("/data/user/0/ai.hans.standard/cache"))
        val environment = CodexRuntimeContract.controlledEnvironment(dirs, "/system", "/data",
            RuntimeNetworkEnvironment(File("/private/test-ca.pem"), "http://hans:synthetic@127.0.0.1:12345"))
        assertEquals("1", environment["CODEX_INTERNAL_APP_SERVER_REMOTE_CONTROL_DISABLED"])
        var acquired = false
        val gate = RemoteControlHostLifecycle<Any>(runtime = { error("Disabled policy must return before state access") },
            enable = { error("must not enable") }, disable = { true }, restart = {}, releaseForeground = {},
            schedulePromotionDeadline = { _, _ -> error("must not schedule") })
        assertFalse(gate.requestEnable { acquired = true; error("must not acquire") })
        assertFalse(acquired)
    }
}
