package ai.hans.standard.fastphone.provisioning

import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FastPhoneKeyProvisioningProtocolTest {
    private var time = 1_000L
    private val protocol = FastPhoneKeyProvisioningProtocol({ time })
    private val synthetic = "synthetic-key-never-valid-at-openai".toByteArray()

    @Test fun hybridRoundTripReturnsCallerOwnedMutableCharacters() {
        val challenge = protocol.begin(true)
        assertEquals(120_000L, challenge.ttlMillis)
        assertEquals(32, Base64.getUrlDecoder().decode(challenge.challenge).size)
        val value = protocol.consume(encrypt(challenge), true)
        assertArrayEquals(String(synthetic).toCharArray(), value)
        value.fill('\u0000')
    }

    @Test fun acceptedEnvelopeCannotBeReplayed() {
        val envelope = encrypt(protocol.begin(true))
        protocol.consume(envelope, true).fill('\u0000')
        fails("no_pending_challenge") { protocol.consume(envelope, true) }
    }

    @Test fun expiryIsExclusiveAndConsumesTheChallenge() {
        val envelope = encrypt(protocol.begin(true))
        time += FastPhoneKeyProvisioningProtocol.TTL_MILLIS
        fails("challenge_expired") { protocol.consume(envelope, true) }
        time--
        fails("no_pending_challenge") { protocol.consume(envelope, true) }
    }

    @Test fun lockAtBeginOrCommitRejectsWithoutRetainingAUsableChallenge() {
        fails("device_locked") { protocol.begin(false) }
        val envelope = encrypt(protocol.begin(true))
        fails("device_locked") { protocol.consume(envelope, false) }
        fails("no_pending_challenge") { protocol.consume(envelope, true) }
    }

    @Test fun freshBeginInvalidatesThePreviousPublicChallenge() {
        val first = protocol.begin(true)
        val old = encrypt(first)
        val second = protocol.begin(true)
        assertNotEquals(first.challenge, second.challenge)
        fails("challenge_mismatch") { protocol.consume(old, true) }
        fails("no_pending_challenge") { protocol.consume(encrypt(second), true) }
    }

    @Test fun malformedInputConsumesTheOnlyAttempt() {
        val envelope = encrypt(protocol.begin(true))
        fails("invalid_envelope") { protocol.consume(envelope.copy(iv = "not:base64"), true) }
        fails("no_pending_challenge") { protocol.consume(envelope, true) }
    }

    @Test fun oversizedAndNonCanonicalBase64AreRejected() {
        var envelope = encrypt(protocol.begin(true))
        fails("invalid_envelope") { protocol.consume(envelope.copy(ciphertext = "A".repeat(4_097)), true) }
        envelope = encrypt(protocol.begin(true))
        fails("invalid_envelope") { protocol.consume(envelope.copy(iv = envelope.iv + "="), true) }
    }

    @Test fun ciphertextTamperingNeverReturnsPlaintext() {
        val envelope = encrypt(protocol.begin(true))
        val bytes = Base64.getUrlDecoder().decode(envelope.ciphertext)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        fails("invalid_envelope") { protocol.consume(envelope.copy(ciphertext = FastPhoneKeyProvisioningProtocol.encode(bytes)), true) }
    }

    @Test fun aadBindsOptInAndChallenge() {
        val challenge = protocol.begin(true)
        val wrongAad = "hans-fast-phone-key-provisioning-v1\u0000enable=false\u0000".toByteArray() +
            Base64.getUrlDecoder().decode(challenge.challenge)
        fails("invalid_envelope") { protocol.consume(encrypt(challenge, aad = wrongAad), true) }
    }

    @Test fun wrongMgfDigestIsRejectedWithoutProviderExceptionText() {
        val challenge = protocol.begin(true)
        val wrong = javax.crypto.spec.OAEPParameterSpec("SHA-256", "MGF1", java.security.spec.MGF1ParameterSpec.SHA1,
            javax.crypto.spec.PSource.PSpecified.DEFAULT)
        val failure = fails("invalid_envelope") { protocol.consume(encrypt(challenge, oaep = wrong), true) }
        assertEquals(null, failure.cause)
        assertFalse(failure.toString().contains(String(synthetic)))
    }

    @Test fun invalidAsciiKeyDoesNotReachStorage() {
        val challenge = protocol.begin(true)
        fails("invalid_key_format") { protocol.consume(encrypt(challenge, plaintext = ByteArray(20) { 10 }), true) }
    }

    @Test fun expiredCleanupCannotCancelANewerChallenge() {
        val old = protocol.begin(true)
        time += 60_000
        val current = protocol.begin(true)
        time += 60_000
        protocol.expire(old.challenge)
        val key = protocol.consume(encrypt(current), true)
        assertTrue(key.isNotEmpty())
        key.fill('\u0000')
    }

    private fun fails(code: String, block: () -> Unit): ProvisioningFailure {
        try { block() } catch (failure: ProvisioningFailure) { assertEquals(code, failure.code); return failure }
        throw AssertionError("Expected constant provisioning failure: $code")
    }

    private fun encrypt(
        challenge: ProvisioningChallenge,
        plaintext: ByteArray = synthetic,
        aad: ByteArray = FastPhoneKeyProvisioningProtocol.aad(Base64.getUrlDecoder().decode(challenge.challenge)),
        oaep: javax.crypto.spec.OAEPParameterSpec = FastPhoneKeyProvisioningProtocol.oaepParameters(),
    ): ProvisioningEnvelope {
        val random = SecureRandom()
        val aes = ByteArray(32).also(random::nextBytes)
        val iv = ByteArray(12).also(random::nextBytes)
        val public = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getUrlDecoder().decode(challenge.publicKey)))
        val wrap = Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.ENCRYPT_MODE, public, oaep) }
        val encrypt = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv)); updateAAD(aad)
        }
        return ProvisioningEnvelope(challenge.challenge, FastPhoneKeyProvisioningProtocol.encode(wrap.doFinal(aes)),
            FastPhoneKeyProvisioningProtocol.encode(iv), FastPhoneKeyProvisioningProtocol.encode(encrypt.doFinal(plaintext)))
            .also { aes.fill(0) }
    }
}
