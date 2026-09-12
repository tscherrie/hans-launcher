package ai.hans.standard.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InternetConnectivityTest {
    @Test
    fun mobileSignalWithoutDefaultDataNetworkIsOffline() {
        val state = DefaultInternetNetworkState()
        state.seed(null, InternetStatus.OFFLINE)
        assertEquals(InternetStatus.OFFLINE, state.snapshot.status)
        assertFalse(state.snapshot.status.permitsExplicitRequest)
        assertTrue(state.snapshot.status.notice.contains("Datenroaming"))
        assertFalse(state.snapshot.status.notice.contains("ist ausgeschaltet"))
    }

    @Test
    fun internetCapabilityWithoutValidationIsLimitedNotReady() {
        val state = DefaultInternetNetworkState()
        state.available(10)
        state.capabilities(10, internet = true, validated = false, captivePortal = false)
        assertEquals(InternetStatus.LIMITED, state.snapshot.status)
        // VPNs can block Android's validation endpoint while OpenAI remains reachable.
        assertTrue(state.snapshot.status.permitsExplicitRequest)
    }

    @Test
    fun captivePortalHasSpecificGuidanceRatherThanBeingCalledOnline() {
        val state = DefaultInternetNetworkState()
        state.available(10)
        state.capabilities(10, internet = true, validated = true, captivePortal = true)
        assertEquals(InternetStatus.CAPTIVE_PORTAL, state.snapshot.status)
        assertTrue(state.snapshot.status.notice.contains("Anmeldung"))
    }

    @Test
    fun handoverIgnoresLostCapabilitiesAndBlockedCallbacksFromOldNetwork() {
        val state = DefaultInternetNetworkState()
        state.available(10)
        state.capabilities(10, true, true, false)
        state.available(20)
        state.capabilities(20, true, true, false)
        val online = state.snapshot
        assertFalse(state.lost(10))
        assertFalse(state.capabilities(10, false, false, false))
        assertFalse(state.blocked(10, true))
        assertEquals(online, state.snapshot)
    }

    @Test
    fun validationLossAndRecoveryEmitOncePerChangeWithoutIdleTicks() {
        val state = DefaultInternetNetworkState()
        state.available(10)
        state.capabilities(10, true, true, false)
        val online = state.snapshot
        repeat(20) { assertFalse(state.capabilities(10, true, true, false)) }
        assertEquals(online, state.snapshot)
        state.capabilities(10, true, false, false)
        val limited = state.snapshot
        assertEquals(InternetStatus.LIMITED, limited.status)
        assertTrue(limited.revision > online.revision)
        state.lost(10)
        assertEquals(InternetStatus.OFFLINE, state.snapshot.status)
        state.available(20)
        state.capabilities(20, true, true, false)
        assertEquals(InternetStatus.ONLINE, state.snapshot.status)
        // The reducer emits state only. It has no dispatcher, message, retry or task API.
    }

    @Test
    fun appBlockedByAndroidOverridesValidatedNetworkUntilUnblocked() {
        val state = DefaultInternetNetworkState()
        state.available(10)
        state.capabilities(10, true, true, false)
        state.blocked(10, true)
        assertEquals(InternetStatus.BLOCKED, state.snapshot.status)
        assertFalse(state.snapshot.status.permitsExplicitRequest)
        state.capabilities(10, true, true, false)
        assertEquals(InternetStatus.BLOCKED, state.snapshot.status)
        state.blocked(10, false)
        assertEquals(InternetStatus.ONLINE, state.snapshot.status)
    }

    @Test
    fun unknownIsNotInventedOfflineAndPermitsExplicitTransportAttempt() {
        assertTrue(InternetStatus.UNKNOWN.permitsExplicitRequest)
        assertTrue(internetWorkNotice(InternetStatus.OFFLINE, true).contains("nicht erneut gesendet"))
        assertEquals("", internetWorkNotice(InternetStatus.ONLINE, true))
    }

    @Test
    fun initialSnapshotRaceCannotPublishOldOnlineAfterNewerLoss() {
        val observed = mutableListOf<InternetSnapshot>()
        val observer = InternetSnapshotObserver { observed += it }
        observer.deliver(InternetSnapshot(InternetStatus.OFFLINE, 3))
        observer.deliver(InternetSnapshot(InternetStatus.ONLINE, 2))
        observer.deliver(InternetSnapshot(InternetStatus.OFFLINE, 3))
        observer.deliver(InternetSnapshot(InternetStatus.ONLINE, 4))
        observer.close()
        observer.deliver(InternetSnapshot(InternetStatus.OFFLINE, 5))
        assertEquals(listOf(3L, 4L), observed.map { it.revision })
        assertEquals(listOf(InternetStatus.OFFLINE, InternetStatus.ONLINE), observed.map { it.status })
    }

    @Test
    fun registeredNetworkDisappearingBeforeBootstrapCannotStrandOldOnlineState() {
        val state = DefaultInternetNetworkState()
        // Registration precedes the read. Even an onLost for a not-yet-seeded network wins.
        state.lost(10)
        assertFalse(state.seed(10, InternetStatus.ONLINE))
        assertEquals(InternetStatus.UNKNOWN, state.snapshot.status)
        assertTrue(state.snapshot.status.permitsExplicitRequest)

        val noDefaultAtRegistration = DefaultInternetNetworkState()
        noDefaultAtRegistration.seed(null, InternetStatus.OFFLINE)
        assertEquals(InternetStatus.OFFLINE, noDefaultAtRegistration.snapshot.status)
    }

    @Test
    fun newerCallbackStateCannotBeReplacedByStaleInitialGetter() {
        val state = DefaultInternetNetworkState()
        state.available(20)
        state.capabilities(20, true, true, false)
        val callbackSnapshot = state.snapshot
        assertFalse(state.seed(null, InternetStatus.OFFLINE))
        assertEquals(callbackSnapshot, state.snapshot)
        state.lost(20)
        assertEquals(InternetStatus.OFFLINE, state.snapshot.status)
    }
}
