package ai.hans.standard.diagnostics

import ai.hans.standard.phone.lifecycle.HansActiveWorkForegroundSnapshot
import ai.hans.standard.remotecontrol.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteControlDiagnosticsTest {
    @Test fun absentRuntimeNeverClaimsActiveAccess() {
        val result = JSONObject(RemoteControlDiagnostics.encode(null, HansActiveWorkForegroundSnapshot()))
        assertEquals("UNKNOWN", result.getString("status"))
        assertFalse(result.getBoolean("runtimeReady"))
        assertFalse(result.getBoolean("localConsent"))
        assertFalse(result.getBoolean("phoneToolsAllowed"))
    }

    @Test fun pairingAndClientIdentitiesNeverEnterDump() {
        val snapshot = RemoteControlSnapshot(
            pairing = RemoteControlPairing("SECRET-PAIRING", "SECRET-MANUAL", "SECRET-ENV", 4_000_000_000L),
            connection = RemoteControlConnection(RemoteControlStatus.CONNECTED, "SECRET-INSTALL", "SECRET-NAME", "SECRET-ENV"),
            clients = listOf(RemoteControlClient("SECRET-CLIENT", "SECRET-DISPLAY", null, null, null, null, null, null)),
        )
        val raw = RemoteControlDiagnostics.encode(snapshot, HansActiveWorkForegroundSnapshot())
        assertFalse(raw.contains("SECRET"))
        val result = JSONObject(raw)
        assertTrue(result.getBoolean("pairingPresent"))
        assertEquals(1, result.getInt("knownClients"))
        assertFalse(result.getBoolean("phoneToolsAllowed"))
    }
}
