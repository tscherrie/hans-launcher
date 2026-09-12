package ai.hans.standard.remotecontrol

import java.io.ByteArrayOutputStream
import java.io.ObjectOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteControlCoordinatorTest {
    private class Fixture {
        var now = 1_800_000_000_000L
        var accepted = true
        private var sequence = 0
        val requests = mutableListOf<RemoteControlRequest>()
        val states = mutableListOf<RemoteControlSnapshot>()
        val coordinator = RemoteControlCoordinator(
            sendRequest = { requests += it; accepted }, onChanged = { states += it },
            newRequestId = { "remote_${++sequence}" }, nowMillis = { now })
        val last: RemoteControlRequest get() = requests.last()
        fun ready(status: String = "disabled") {
            coordinator.onRuntimeReady(1)
            reply(connection(status))
        }
        fun enable(status: String = "connected") {
            assertTrue(coordinator.enableFromLocalUserConsent())
            reply(connection(status))
        }
        fun reply(result: JSONObject, id: String = last.id, generation: Long = 1): Boolean =
            coordinator.onRpcResponse(generation, JSONObject().put("id", id).put("result", result).toString())
        fun error(code: Int = -32603, message: String = "failed", id: String = last.id): Boolean =
            coordinator.onRpcResponse(1, JSONObject().put("id", id)
                .put("error", JSONObject().put("code", code).put("message", message)).toString())
        fun notification(status: String, generation: Long = 1, environment: String = "env_1") =
            coordinator.onStatusNotification(generation, JSONObject()
                .put("method", RemoteControlProtocol.STATUS_NOTIFICATION)
                .put("params", connection(status, environment)).toString())
        fun pairResult(expires: Long = now / 1_000 + 60) = JSONObject()
            .put("pairingCode", "pairing-SECRET-value")
            .put("manualPairingCode", "MANUAL-SECRET")
            .put("environmentId", "env_1").put("expiresAt", expires)
        fun clients(vararg ids: String, cursor: String? = null) = JSONObject()
            .put("data", JSONArray(ids.map { JSONObject().put("clientId", it).put("displayName", "Mac") }))
            .put("nextCursor", cursor ?: JSONObject.NULL)
    }

    @Test fun readyOnlyProbesCapabilityNeverImplicitlyEnablesOrPairs() {
        val fixture = Fixture()
        assertFalse(fixture.coordinator.snapshot.localConsentGranted)
        assertEquals(RemoteControlStatus.UNKNOWN, fixture.coordinator.snapshot.status)
        assertFalse(fixture.coordinator.enableFromLocalUserConsent())
        fixture.ready()
        assertEquals(listOf("remoteControl/status/read"), fixture.requests.map { it.method })
        assertEquals(RemoteControlCapability.SUPPORTED, fixture.coordinator.snapshot.capability)
        assertTrue(fixture.coordinator.snapshot.isDisabledConfirmed)
        assertFalse(fixture.coordinator.snapshot.mayUsePhoneToolsRemotely)
    }

    @Test fun earlyOptionalStatusIsConsumedWithoutInitializingOrGrantingAccess() {
        val fixture = Fixture()
        assertTrue(fixture.notification("connected"))
        assertFalse(fixture.coordinator.snapshot.runtimeReady)
        assertEquals(RemoteControlStatus.UNKNOWN, fixture.coordinator.snapshot.status)
        assertFalse(fixture.coordinator.snapshot.localConsentGranted)
        assertTrue(fixture.requests.isEmpty())
    }

    @Test fun enableAndDisableAreEphemeralAndNeverOptimistic() {
        val fixture = Fixture()
        fixture.ready()
        assertTrue(fixture.coordinator.enableFromLocalUserConsent())
        assertTrue(JSONObject(fixture.last.wireJson).getJSONObject("params").getBoolean("ephemeral"))
        assertTrue(fixture.coordinator.snapshot.isDisabledConfirmed)
        assertFalse(fixture.coordinator.snapshot.isEnabledConfirmed)
        fixture.reply(connection("connecting"))
        assertTrue(fixture.coordinator.snapshot.isEnabledConfirmed)
        assertFalse(fixture.coordinator.snapshot.mayUsePhoneToolsRemotely)
        fixture.notification("connected")
        assertTrue(fixture.coordinator.snapshot.mayUsePhoneToolsRemotely)
        assertTrue(fixture.coordinator.disableFromLocalUserAction())
        assertTrue(JSONObject(fixture.last.wireJson).getJSONObject("params").getBoolean("ephemeral"))
        assertFalse(fixture.coordinator.snapshot.localConsentGranted)
        assertFalse(fixture.coordinator.snapshot.isDisabledConfirmed)
        fixture.reply(connection("disabled"))
        assertTrue(fixture.coordinator.snapshot.isDisabledConfirmed)
    }

    @Test fun capabilityRequiresActualSupportedReplyAndUnsupportedCannotEnable() {
        val fixture = Fixture()
        fixture.coordinator.onRuntimeReady(1)
        fixture.error(-32601, "Method not found")
        assertEquals(RemoteControlCapability.UNSUPPORTED, fixture.coordinator.snapshot.capability)
        assertFalse(fixture.coordinator.enableFromLocalUserConsent())
        assertEquals(1, fixture.requests.size)
        assertFalse(fixture.coordinator.snapshot.isDisabledConfirmed)
    }

    @Test fun malformedStatusAndUnknownStatusNeverProveRuntimeState() {
        for (status in listOf("future-status", "")) {
            val fixture = Fixture()
            fixture.coordinator.onRuntimeReady(1)
            fixture.reply(connection(status))
            assertEquals(RemoteControlIssue.MALFORMED_RESPONSE, fixture.coordinator.snapshot.issue)
            assertEquals(RemoteControlStatus.UNKNOWN, fixture.coordinator.snapshot.status)
        }
    }

    @Test fun disablingPreemptsEnableAndLateEnableReplyIsConsumedWithoutRestoringAccess() {
        val fixture = Fixture()
        fixture.ready()
        fixture.coordinator.enableFromLocalUserConsent()
        val old = fixture.last.id
        fixture.coordinator.disableFromLocalUserAction()
        assertTrue(fixture.reply(connection("connected"), old))
        assertFalse(fixture.coordinator.snapshot.localConsentGranted)
        assertEquals(RemoteControlOperation.DISABLE, fixture.coordinator.snapshot.pendingOperation)
        fixture.reply(connection("disabled"))
        assertTrue(fixture.reply(connection("connected"), old))
        assertTrue(fixture.coordinator.snapshot.isDisabledConfirmed)
    }

    @Test fun staleGenerationAndAnotherSubsystemResponseCannotChangeState() {
        val fixture = Fixture()
        fixture.coordinator.onRuntimeReady(1)
        assertFalse(fixture.reply(connection("connected"), generation = 2))
        assertFalse(fixture.reply(connection("connected"), id = "other-subsystem"))
        assertEquals(RemoteControlStatus.UNKNOWN, fixture.coordinator.snapshot.status)
        fixture.reply(connection("disabled"))
        assertTrue(fixture.reply(connection("connected"))) // Duplicate own reply is swallowed.
        assertTrue(fixture.coordinator.snapshot.isDisabledConfirmed)
    }

    @Test fun disconnectedRuntimeAndRestartNeverRestoreConsentOrClaimOff() {
        val fixture = Fixture()
        fixture.ready()
        fixture.enable()
        fixture.coordinator.onRuntimeUnavailable()
        assertEquals(RemoteControlStatus.UNKNOWN, fixture.coordinator.snapshot.status)
        assertEquals(RemoteControlStatus.CONNECTED, fixture.coordinator.snapshot.connection?.status)
        assertFalse(fixture.coordinator.snapshot.localConsentGranted)
        fixture.coordinator.onRuntimeReady(2)
        assertEquals("remoteControl/status/read", fixture.last.method)
        assertFalse(fixture.coordinator.snapshot.localConsentGranted)
    }

    @Test fun disableFailureAndTimeoutFailClosedLocallyWithoutFalseOffClaim() {
        for (timeout in listOf(false, true)) {
            val fixture = Fixture()
            fixture.ready()
            fixture.enable()
            fixture.coordinator.disableFromLocalUserAction()
            if (timeout) {
                fixture.now = requireNotNull(fixture.coordinator.snapshot.nextDeadlineAtMillis)
                fixture.coordinator.onDeadline()
            } else fixture.error()
            assertFalse(fixture.coordinator.snapshot.mayUsePhoneToolsRemotely)
            assertFalse(fixture.coordinator.snapshot.isDisabledConfirmed)
            assertEquals(RemoteControlStatus.CONNECTED, fixture.coordinator.snapshot.connection?.status)
            assertEquals(if (timeout) RemoteControlIssue.TIMED_OUT else RemoteControlIssue.RPC_FAILED,
                fixture.coordinator.snapshot.issue)
        }
    }

    @Test fun writeFailureDoesNotLeakConsentOrReportEnabled() {
        val fixture = Fixture()
        fixture.ready()
        fixture.accepted = false
        assertFalse(fixture.coordinator.enableFromLocalUserConsent())
        assertFalse(fixture.coordinator.snapshot.localConsentGranted)
        assertFalse(fixture.coordinator.snapshot.isEnabledConfirmed)
        assertEquals(RemoteControlIssue.TRANSPORT_UNAVAILABLE, fixture.coordinator.snapshot.issue)
    }

    @Test fun pairRequiresLocalConsentAndServerProofAndSecretsNeverStringifyOrSerialize() {
        val fixture = Fixture()
        fixture.ready("connected") // A pre-existing upstream state does not grant local consent.
        assertFalse(fixture.coordinator.startPairingFromLocalUserAction())
        fixture.enable()
        assertTrue(fixture.coordinator.startPairingFromLocalUserAction())
        assertTrue(JSONObject(fixture.last.wireJson).getJSONObject("params").getBoolean("manualCode"))
        fixture.reply(fixture.pairResult())
        val snapshot = fixture.coordinator.snapshot
        assertNotNull(snapshot.pairing)
        assertFalse(snapshot.toString().contains("SECRET"))
        val bytes = ByteArrayOutputStream()
        ObjectOutputStream(bytes).use { it.writeObject(snapshot) }
        assertFalse(bytes.toString(Charsets.ISO_8859_1.name()).contains("SECRET"))
        assertTrue(fixture.coordinator.refreshPairingStatus())
        val params = JSONObject(fixture.last.wireJson).getJSONObject("params")
        assertTrue(params.has("pairingCode"))
        assertFalse(params.has("manualPairingCode"))
        assertFalse(fixture.last.toString().contains("SECRET"))
    }

    @Test fun pairingExpiryClaimAndDisableErasePairingSecrets() {
        val fixture = Fixture()
        fixture.ready()
        fixture.enable()
        fixture.coordinator.startPairingFromLocalUserAction()
        fixture.reply(fixture.pairResult())
        fixture.now = fixture.coordinator.snapshot.pairing!!.expiresAtMillis
        fixture.coordinator.onDeadline()
        assertNull(fixture.coordinator.snapshot.pairing)
        assertEquals(RemoteControlIssue.PAIRING_EXPIRED, fixture.coordinator.snapshot.issue)
        fixture.coordinator.startPairingFromLocalUserAction()
        fixture.reply(fixture.pairResult())
        fixture.coordinator.refreshPairingStatus()
        fixture.reply(JSONObject().put("claimed", true))
        assertNull(fixture.coordinator.snapshot.pairing)
        assertTrue(fixture.coordinator.snapshot.pairingClaimed)
        fixture.coordinator.startPairingFromLocalUserAction()
        fixture.reply(fixture.pairResult())
        fixture.coordinator.disableFromLocalUserAction()
        assertNull(fixture.coordinator.snapshot.pairing)
    }

    @Test fun expiredOrWrongEnvironmentPairingIsRejected() {
        for (wrongEnvironment in listOf(false, true)) {
            val fixture = Fixture()
            fixture.ready()
            fixture.enable()
            fixture.coordinator.startPairingFromLocalUserAction()
            val result = fixture.pairResult(if (wrongEnvironment) fixture.now / 1_000 + 60 else fixture.now / 1_000)
            if (wrongEnvironment) result.put("environmentId", "env_other")
            fixture.reply(result)
            assertNull(fixture.coordinator.snapshot.pairing)
            assertEquals(RemoteControlIssue.MALFORMED_RESPONSE, fixture.coordinator.snapshot.issue)
        }
    }

    @Test fun clientPaginationUsesExactEnvironmentAndDoesNotFakeCompleteness() {
        val fixture = Fixture()
        fixture.ready()
        fixture.coordinator.refreshClients()
        fixture.reply(fixture.clients("mac_1", cursor = "page-2"))
        assertFalse(fixture.coordinator.snapshot.clientsComplete)
        assertTrue(fixture.coordinator.loadMoreClients())
        assertEquals("page-2", JSONObject(fixture.last.wireJson).getJSONObject("params").getString("cursor"))
        fixture.reply(fixture.clients("mac_2"))
        assertEquals(listOf("mac_1", "mac_2"), fixture.coordinator.snapshot.clients.map { it.clientId })
        assertTrue(fixture.coordinator.snapshot.clientsComplete)
    }

    @Test fun revokeRequiresListedClientAndWaitsForAckBeforeRemoval() {
        val fixture = Fixture()
        fixture.ready()
        fixture.enable()
        fixture.coordinator.refreshClients()
        fixture.reply(fixture.clients("mac_1", "mac_2"))
        assertFalse(fixture.coordinator.revokeClientFromLocalUserAction("guessed_client"))
        assertTrue(fixture.coordinator.revokeClientFromLocalUserAction("mac_1"))
        assertEquals(2, fixture.coordinator.snapshot.clients.size)
        assertFalse(fixture.coordinator.snapshot.mayUsePhoneToolsRemotely)
        fixture.error()
        assertEquals(2, fixture.coordinator.snapshot.clients.size)
        assertFalse(fixture.coordinator.snapshot.localConsentGranted)
        assertTrue(fixture.coordinator.revokeClientFromLocalUserAction("mac_1"))
        fixture.reply(JSONObject())
        assertEquals(listOf("mac_2"), fixture.coordinator.snapshot.clients.map { it.clientId })
    }

    @Test fun authAndManagedPolicyErrorsAreSanitizedAndCapabilityGated() {
        for ((message, expected) in listOf("requires ChatGPT authentication token=SECRET" to RemoteControlIssue.AUTH_REQUIRED,
            "disabled by managed requirements SECRET" to RemoteControlIssue.POLICY_BLOCKED)) {
            val fixture = Fixture()
            fixture.coordinator.onRuntimeReady(1)
            fixture.error(-32600, message)
            assertEquals(expected, fixture.coordinator.snapshot.issue)
            assertFalse(fixture.coordinator.snapshot.toString().contains("SECRET"))
            assertFalse(fixture.coordinator.enableFromLocalUserConsent())
        }
    }

    @Test fun lateResponsesCannotReintroduceExpiredPairingCodes() {
        val fixture = Fixture()
        fixture.ready()
        fixture.enable()
        fixture.coordinator.startPairingFromLocalUserAction()
        val old = fixture.last.id
        fixture.now += 15_000
        fixture.coordinator.onDeadline()
        assertTrue(fixture.reply(fixture.pairResult(), old))
        assertNull(fixture.coordinator.snapshot.pairing)
    }

    companion object {
        private fun connection(status: String, environment: String = "env_1") = JSONObject()
            .put("status", status).put("installationId", "installation_1")
            .put("serverName", "Hans MP01").put("environmentId", environment)
    }
}
