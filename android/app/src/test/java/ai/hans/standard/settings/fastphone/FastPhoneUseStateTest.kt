package ai.hans.standard.settings.fastphone

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FastPhoneUseStateTest {
    @Test fun startsDisabledWithoutCredentialOrServiceClaims() {
        val state = FastPhoneUseSettingsSnapshot()
        assertFalse(state.requestedEnabled)
        assertFalse(state.requestsAllowed)
        assertFalse(state.verifiedActive)
        assertEquals(FastPhoneCredentialStatus.MISSING, state.credentialStatus)
        assertEquals(FastPhoneServiceStatus.NOT_VERIFIED, state.serviceStatus)
    }

    @Test fun savingKeyDoesNotEnablePaidRequestsOrProveAccess() {
        val saved = FastPhoneUseSettingsSnapshot(credentialStatus = FastPhoneCredentialStatus.AVAILABLE)
        assertFalse(saved.requestsAllowed)
        assertFalse(saved.verifiedActive)
        val optedIn = saved.copy(requestedEnabled = true)
        assertTrue(optedIn.requestsAllowed)
        assertFalse(optedIn.verifiedActive)
        assertTrue(optedIn.copy(serviceStatus = FastPhoneServiceStatus.VERIFIED).verifiedActive)
    }

    @Test fun missingLockedDisabledAndRejectedAccessNeverAppearActive() {
        val active = FastPhoneUseSettingsSnapshot(true, FastPhoneCredentialStatus.AVAILABLE,
            FastPhoneServiceStatus.VERIFIED)
        assertTrue(active.verifiedActive)
        assertFalse(active.copy(requestedEnabled = false).verifiedActive)
        assertFalse(active.copy(credentialStatus = FastPhoneCredentialStatus.MISSING).requestsAllowed)
        assertFalse(active.copy(credentialStatus = FastPhoneCredentialStatus.TEMPORARILY_UNAVAILABLE).requestsAllowed)
        assertFalse(active.copy(serviceStatus = FastPhoneServiceStatus.INVALID_KEY).requestsAllowed)
        assertFalse(active.copy(serviceStatus = FastPhoneServiceStatus.OUT_OF_CREDITS).requestsAllowed)
        assertTrue(active.copy(serviceStatus = FastPhoneServiceStatus.RATE_LIMITED).requestsAllowed)
        assertFalse(active.copy(serviceStatus = FastPhoneServiceStatus.RATE_LIMITED).verifiedActive)
    }

    @Test fun providerFailuresUseFixedNonsecretCodes() {
        assertEquals(FastPhoneServiceStatus.INVALID_KEY, fastPhoneServiceFailure("fast_phone_key_rejected"))
        assertEquals(FastPhoneServiceStatus.INVALID_KEY, fastPhoneServiceFailure("fast_phone_access_denied"))
        assertEquals(FastPhoneServiceStatus.OUT_OF_CREDITS, fastPhoneServiceFailure("fast_phone_quota_exhausted"))
        assertEquals(FastPhoneServiceStatus.RATE_LIMITED, fastPhoneServiceFailure("fast_phone_rate_limited"))
        assertEquals(FastPhoneServiceStatus.NETWORK_UNAVAILABLE, fastPhoneServiceFailure("fast_phone_request_timed_out"))
        assertEquals(FastPhoneServiceStatus.SERVICE_UNAVAILABLE, fastPhoneServiceFailure("untrusted provider body"))
        assertFalse(FastPhoneUseSettingsSnapshot(serviceStatus = fastPhoneServiceFailure("private token body"))
            .toString().contains("private token body"))
    }

    @Test fun absentOrMalformedLocalPreferenceDefaultsOffAndValidWriteIsReadBack() {
        val storage = MemoryPreferenceStorage()
        val store = FastPhoneUsePreferenceStore(storage)
        assertFalse(store.read())
        storage.bytes = byteArrayOf(1, 1)
        assertFalse(store.read())
        storage.bytes = "true".toByteArray()
        assertFalse(store.read())
        assertTrue(store.save(true))
        assertTrue(store.read())
        assertFalse(store.save(false))
        assertFalse(store.read())
    }

    @Test fun failedAtomicWriteRetainsPreviousConfirmedPreference() {
        val storage = MemoryPreferenceStorage()
        val store = FastPhoneUsePreferenceStore(storage)
        store.save(true)
        storage.failWrites = true
        assertTrue(runCatching { store.save(false) }.isFailure)
        assertTrue(store.read())
    }

    @Test fun discardedWriteCannotBeReportedAsSaved() {
        val storage = MemoryPreferenceStorage()
        val store = FastPhoneUsePreferenceStore(storage)
        storage.discardWrites = true
        assertTrue(runCatching { store.save(true) }.isFailure)
        assertFalse(store.read())
    }

    private class MemoryPreferenceStorage : FastPhoneUsePreferenceStorage {
        var bytes: ByteArray? = null
        var failWrites = false
        var discardWrites = false
        override fun read(): ByteArray? = bytes?.copyOf()
        override fun writeAtomically(bytes: ByteArray) {
            if (failWrites) throw IOException("synthetic persistence failure")
            if (!discardWrites) this.bytes = bytes.copyOf()
        }
    }
}
