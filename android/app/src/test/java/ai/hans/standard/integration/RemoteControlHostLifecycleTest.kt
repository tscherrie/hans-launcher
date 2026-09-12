package ai.hans.standard.integration

import ai.hans.standard.phone.lifecycle.HansActiveWorkForegroundEvent
import ai.hans.standard.phone.lifecycle.HansActiveWorkForegroundSnapshot
import ai.hans.standard.phone.lifecycle.HansActiveWorkReason
import ai.hans.standard.phone.lifecycle.HansActiveWorkUpdate
import ai.hans.standard.phone.lifecycle.HansActiveWorkUpdateStatus
import ai.hans.standard.remotecontrol.RemoteControlCoordinator
import ai.hans.standard.remotecontrol.RemoteControlOperation
import ai.hans.standard.remotecontrol.RemoteControlProtocol
import ai.hans.standard.remotecontrol.RemoteControlRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Executes the actual host gate against the actual RPC coordinator, with deterministic clocks. */
class RemoteControlHostLifecycleTest {
    @Test fun consentWaitsForMatchingRealPromotionAndThenForEnableAcknowledgement() {
        val f = Fixture()
        f.ready()
        assertTrue(f.requestEnable())
        assertEquals(1, f.requests.size)
        f.gate.onForeground(f.foreground(revision = 9))
        assertEquals(1, f.requests.size)
        f.promote()
        assertEquals("remoteControl/enable", f.requests.last().method)
        assertFalse(f.core.snapshot.mayUsePhoneToolsRemotely)
        assertEquals(0, f.releases)
        f.gate.onRemoteChanged() // The pre-enable disabled state cannot release protection.
        assertTrue(f.gate.ownsProtection)
        f.reply(connection("connected"))
        assertTrue(f.core.snapshot.mayUsePhoneToolsRemotely)
        assertEquals(0, f.restarts)
        assertTrue(f.gate.ownsProtection)
        f.promote() // Duplicate protection events must never re-enable.
        assertEquals(2, f.requests.size)
    }

    @Test fun unsupportedCapabilityAndRejectedVisibleActivityAcquisitionNeverEnable() {
        val unsupported = Fixture()
        unsupported.core.onRuntimeReady(7)
        unsupported.error(-32601)
        assertFalse(unsupported.requestEnable())
        assertEquals(0, unsupported.acquisitions)
        val rejected = Fixture()
        rejected.ready()
        rejected.acquisitionAccepted = false
        assertFalse(rejected.requestEnable())
        assertTrue(rejected.timers.isEmpty())
        rejected.promote()
        assertEquals(1, rejected.requests.size)
    }

    @Test fun promotionFailureAndDeadlineCancelWithoutAnyNativeEnableOrBackgroundRestart() {
        listOf(HansActiveWorkForegroundEvent.PROMOTION_REJECTED, HansActiveWorkForegroundEvent.TIMED_OUT,
            HansActiveWorkForegroundEvent.STOPPED).forEach { event ->
            val f = Fixture()
            f.ready()
            f.requestEnable()
            f.gate.onForeground(f.foreground(event = event, protected = false))
            assertEquals(1, f.releases)
            assertEquals(0, f.restarts)
            assertEquals(1, f.requests.size)
        }
        val timed = Fixture()
        timed.ready()
        timed.requestEnable()
        assertEquals(5_000L, timed.timers.single().delay)
        timed.timers.single().callback()
        assertEquals(1, timed.releases)
        timed.promote()
        assertEquals(1, timed.requests.size)
    }

    @Test fun stalePromotionDeadlineCannotCancelANewerConsentAttempt() {
        val f = Fixture()
        f.ready()
        f.requestEnable()
        val old = f.timers.single()
        assertTrue(f.gate.requestDisable())
        assertTrue(old.cancelled)
        f.requestEnable()
        old.callback() // Deliberately emulate an already queued callback after cancellation.
        assertTrue(f.gate.ownsProtection)
        f.promote()
        assertEquals("remoteControl/enable", f.requests.last().method)
    }

    @Test fun notificationStopDuringPromotionCancelsAndNeverEnables() {
        val f = Fixture()
        f.ready()
        f.gate.seedStopSequence(5)
        f.requestEnable()
        f.gate.onForeground(f.foreground(stopSequence = 6))
        assertEquals(1, f.releases)
        assertEquals(1, f.requests.size)
        f.promote()
        assertEquals(1, f.requests.size)
    }

    @Test fun stopDuringEnablePreemptsLateEnableAndKeepsProtectionUntilDisableAck() {
        val f = Fixture()
        f.ready()
        f.requestEnable()
        f.promote()
        val enableId = f.requests.last().id
        assertTrue(f.gate.requestDisable())
        val disableId = f.requests.last().id
        assertFalse(f.core.snapshot.localConsentGranted)
        f.reply(connection("connected"), enableId)
        assertEquals(RemoteControlOperation.DISABLE, f.core.snapshot.pendingOperation)
        assertTrue(f.gate.ownsProtection)
        assertTrue(f.gate.requestDisable())
        assertEquals(disableId, f.requests.last().id)
        f.reply(connection("disabled"), disableId)
        assertEquals(1, f.releases)
        assertEquals(0, f.restarts)
        assertFalse(f.gate.ownsProtection)
    }

    @Test fun lostForegroundRequestsDisableAndItsTimeoutTerminatesOnlyTheNativeRuntime() {
        val f = Fixture()
        f.connected()
        f.gate.onForeground(f.foreground(event = HansActiveWorkForegroundEvent.STOPPED, protected = false))
        assertEquals("remoteControl/disable", f.requests.last().method)
        assertFalse(f.core.snapshot.mayUsePhoneToolsRemotely)
        f.expireRpc()
        assertEquals(listOf("restart", "release"), f.effects)
        assertEquals(1, f.restarts)
        assertFalse(f.core.snapshot.isDisabledConfirmed)
    }

    @Test fun failedEnableWriteAndEnableTimeoutCannotLeaveAnUnownedNativeRelay() {
        val write = Fixture()
        write.ready()
        write.requestEnable()
        write.acceptWrites = false
        write.promote()
        assertEquals(listOf("restart", "release"), write.effects)
        assertFalse(write.gate.ownsProtection)
        val timeout = Fixture()
        timeout.ready()
        timeout.requestEnable()
        timeout.promote()
        timeout.expireRpc()
        assertEquals(1, timeout.restarts)
        assertEquals(1, timeout.releases)
    }

    @Test fun failedDisableWriteAndWrongStateAcknowledgementTerminateTheNativeRuntime() {
        val write = Fixture()
        write.connected()
        write.acceptWrites = false
        assertFalse(write.gate.requestDisable())
        assertEquals(1, write.restarts)
        val wrong = Fixture()
        wrong.connected()
        wrong.gate.requestDisable()
        wrong.reply(connection("connected"))
        assertEquals(1, wrong.restarts)
        assertFalse(wrong.core.snapshot.isDisabledConfirmed)
    }

    @Test fun nativeRuntimeLossAndGenerationOrClientReplacementNeverReplayConsent() {
        for (replacement in listOf("loss", "generation", "client")) {
            val f = Fixture()
            f.connected()
            when (replacement) {
                "loss" -> f.core.onRuntimeUnavailable()
                "generation" -> f.core.onRuntimeReady(8)
                "client" -> f.identity = Any() // A replacement client can reuse a numeric epoch.
            }
            f.gate.onRemoteChanged()
            assertFalse(f.gate.ownsProtection)
            assertEquals(1, f.releases)
            assertEquals(0, f.restarts)
            val count = f.requests.size
            f.promote()
            assertEquals(count, f.requests.size)
        }
    }

    @Test fun generationChangeBetweenConsentAndPromotionCannotEnableTheNewRuntime() {
        val f = Fixture()
        f.ready()
        f.requestEnable()
        f.core.onRuntimeReady(8)
        f.promote()
        assertFalse(f.gate.ownsProtection)
        assertFalse(f.requests.any { it.method == "remoteControl/enable" })
    }

    @Test fun staleForegroundLossCannotRevokeANewerConfirmedProtectionSnapshot() {
        val f = Fixture()
        f.connected()
        f.gate.onForeground(f.foreground().copy(sequence = 12L))
        val count = f.requests.size
        f.gate.onForeground(f.foreground(protected = false, event = HansActiveWorkForegroundEvent.STOPPED)
            .copy(sequence = 11L))
        assertEquals(count, f.requests.size)
        assertTrue(f.gate.ownsProtection)
    }

    @Test fun revocationKeepsForegroundAndRestoresAuthorityOnlyAfterItsAck() {
        val f = Fixture()
        f.connected()
        f.core.refreshClients()
        f.reply(JSONObject().put("data", JSONArray().put(JSONObject().put("clientId", "mac_1")))
            .put("nextCursor", JSONObject.NULL))
        assertTrue(f.core.revokeClientFromLocalUserAction("mac_1"))
        f.gate.onRemoteChanged()
        assertFalse(f.core.snapshot.mayUsePhoneToolsRemotely)
        assertTrue(f.gate.ownsProtection)
        f.reply(JSONObject())
        assertTrue(f.core.snapshot.mayUsePhoneToolsRemotely)
        assertEquals(0, f.restarts)
        assertEquals(0, f.releases)
    }

    @Test fun failedClientRevocationWithdrawsAllAccessAndWaitsForDisableProof() {
        val f = Fixture()
        f.connected()
        f.core.refreshClients()
        f.reply(JSONObject().put("data", JSONArray().put(JSONObject().put("clientId", "mac_1")))
            .put("nextCursor", JSONObject.NULL))
        f.core.revokeClientFromLocalUserAction("mac_1")
        f.error()
        assertEquals("remoteControl/disable", f.requests.last().method)
        assertTrue(f.gate.ownsProtection)
        f.reply(connection("disabled"))
        assertEquals(1, f.releases)
    }

    @Test fun malformedStatusDuringPendingPairingFailsClosedInsteadOfWaitingForUnrelatedRpc() {
        val f = Fixture()
        f.connected()
        f.core.startPairingFromLocalUserAction()
        f.core.onStatusNotification(7, JSONObject().put("method", RemoteControlProtocol.STATUS_NOTIFICATION)
            .put("params", connection("invalid-status")).toString())
        f.gate.onRemoteChanged()
        assertEquals(1, f.restarts)
    }

    @Test fun explicitStopOfDiscoveredNativeAccessAlsoTerminatesOnTimeoutWithoutLocalEnableHistory() {
        val f = Fixture()
        f.ready("connected")
        assertFalse(f.core.snapshot.localConsentGranted)
        assertTrue(f.gate.requestDisable())
        f.expireRpc()
        assertEquals(1, f.restarts)
        assertFalse(f.core.snapshot.isDisabledConfirmed)
    }

    private class Fixture {
        var now = 1_800_000_000_000L
        var identity = Any()
        var acceptWrites = true
        var acquisitionAccepted = true
        var acquisitions = 0
        var releases = 0
        var restarts = 0
        val effects = mutableListOf<String>()
        val requests = mutableListOf<RemoteControlRequest>()
        val timers = mutableListOf<Timer>()
        private var requestSequence = 0
        val core = RemoteControlCoordinator(sendRequest = { requests += it; acceptWrites },
            newRequestId = { "host_test_${++requestSequence}" }, nowMillis = { now })
        val gate = RemoteControlHostLifecycle(
            runtime = { RemoteControlHostRuntime(identity, core.snapshot) },
            enable = { _: Any -> core.enableFromLocalUserConsent() },
            disable = { _: Any -> core.disableFromLocalUserAction() },
            restart = { _: Any -> restarts++; effects += "restart"; core.onRuntimeUnavailable() },
            releaseForeground = { releases++; effects += "release" },
            schedulePromotionDeadline = { delay, callback ->
                val timer = Timer(delay, callback)
                timers += timer
                val cancel: () -> Unit = { timer.cancelled = true }
                cancel
            },
        )
        fun ready(status: String = "disabled") { core.onRuntimeReady(7); reply(connection(status)) }
        fun connected() { ready(); requestEnable(); promote(); reply(connection("connected")) }
        fun requestEnable(): Boolean = gate.requestEnable {
            acquisitions++
            HansActiveWorkUpdate(if (acquisitionAccepted) HansActiveWorkUpdateStatus.APPLIED else HansActiveWorkUpdateStatus.REJECTED,
                if (acquisitionAccepted) setOf(HansActiveWorkReason.REMOTE_CONTROL) else emptySet(), 10L)
        }
        fun foreground(revision: Long = 10L, protected: Boolean = true,
            event: HansActiveWorkForegroundEvent = HansActiveWorkForegroundEvent.PROTECTED,
            stopSequence: Long = 0L) = HansActiveWorkForegroundSnapshot(revision = revision,
                protectedReasons = if (protected) setOf(HansActiveWorkReason.REMOTE_CONTROL) else emptySet(),
                event = event, remoteStopRequestSequence = stopSequence)
        fun promote() = gate.onForeground(foreground())
        fun reply(result: JSONObject, id: String = requests.last().id) {
            assertTrue(core.onRpcResponse(7, JSONObject().put("id", id).put("result", result).toString()))
            gate.onRemoteChanged()
        }
        fun error(code: Int = -32603) {
            assertTrue(core.onRpcResponse(7, JSONObject().put("id", requests.last().id)
                .put("error", JSONObject().put("code", code).put("message", "failed")).toString()))
            gate.onRemoteChanged()
        }
        fun expireRpc() { now += 15_000; core.onDeadline(); gate.onRemoteChanged() }
    }

    private class Timer(val delay: Long, val callback: () -> Unit, var cancelled: Boolean = false)
    private companion object {
        fun connection(status: String) = JSONObject().put("status", status)
            .put("installationId", "test-installation").put("serverName", "Test phone").put("environmentId", "env_1")
    }
}
