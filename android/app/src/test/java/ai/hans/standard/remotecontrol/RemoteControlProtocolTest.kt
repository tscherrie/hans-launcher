package ai.hans.standard.remotecontrol

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteControlProtocolTest {
    @Test fun emitsOnlyPinnedMethodsAndExactEphemeralParameters() {
        val requests = listOf(RemoteControlProtocol.status("r1"), RemoteControlProtocol.enable("r2"),
            RemoteControlProtocol.disable("r3"), RemoteControlProtocol.pair("r4"),
            RemoteControlProtocol.clients("r5", "env_1"), RemoteControlProtocol.revoke("r6", "env_1", "mac_1"))
        assertEquals(listOf("remoteControl/status/read", "remoteControl/enable", "remoteControl/disable",
            "remoteControl/pairing/start", "remoteControl/client/list", "remoteControl/client/revoke"), requests.map { it.method })
        assertFalse(JSONObject(requests.first().wireJson).has("params"))
        for (index in listOf(1, 2)) {
            val params = JSONObject(requests[index].wireJson).getJSONObject("params")
            assertEquals(1, params.length())
            assertTrue(params.getBoolean("ephemeral"))
        }
    }

    @Test fun malformedTargetsAreRejectedBeforeAnyWireFrameExists() {
        for (target in listOf("", "wrong/path", "token\nvalue", "x".repeat(513))) {
            assertTrue(runCatching { RemoteControlProtocol.revoke("r1", "env_1", target) }.isFailure)
            assertTrue(runCatching { RemoteControlProtocol.clients("r1", target) }.isFailure)
        }
    }

    @Test fun pairingRequiresFutureIntegralSecondsAndRejectsOverflow() {
        val base = JSONObject().put("pairingCode", "secret").put("manualPairingCode", JSONObject.NULL)
            .put("environmentId", "env_1")
        for (invalid in listOf<Any>(0L, -1L, Long.MAX_VALUE, 1.5, "2000000000")) {
            assertTrue(runCatching { RemoteControlProtocol.pairing(base.put("expiresAt", invalid), 1_000) }.isFailure)
        }
        val pairing = RemoteControlProtocol.pairing(base.put("expiresAt", 2_000L), 1_000)
        assertEquals(2_000_000L, pairing.expiresAtMillis)
        val status = JSONObject(RemoteControlProtocol.pairingStatus("r1", pairing).wireJson).getJSONObject("params")
        assertEquals(1, status.length())
        assertEquals("secret", status.getString("pairingCode"))
    }

    @Test fun oversizedAndMalformedFramesFailWithoutEchoingProviderText() {
        assertTrue(runCatching { RemoteControlProtocol.envelope("x".repeat(RemoteControlProtocol.MAX_FRAME_BYTES + 1)) }.isFailure)
        assertTrue(runCatching { RemoteControlProtocol.envelope("not json") }.isFailure)
    }

    @Test fun unknownConnectionStatusNeverMapsToDisabled() {
        val result = JSONObject().put("status", "future").put("installationId", "i1")
            .put("serverName", "Hans").put("environmentId", JSONObject.NULL)
        assertTrue(runCatching { RemoteControlProtocol.connection(result) }.isFailure)
    }

    @Test fun secretWrappersDoNotRevealTheirPayloadInDiagnostics() {
        val pairing = RemoteControlPairing("SECRET-CODE", "SECRET-MANUAL", "env_1", 2_000)
        assertFalse(pairing.toString().contains("SECRET"))
        assertFalse(RemoteControlProtocol.pairingStatus("r1", pairing).toString().contains("SECRET"))
        assertFalse(RemoteControlSnapshot(pairing = pairing).toString().contains("SECRET"))
    }
}
