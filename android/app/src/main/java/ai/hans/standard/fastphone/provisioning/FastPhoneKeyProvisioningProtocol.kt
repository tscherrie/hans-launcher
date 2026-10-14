package ai.hans.standard.fastphone.provisioning

import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.MGF1ParameterSpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec

/** Ephemeral transport only. No private key, API key or pending request is persisted. */
internal class FastPhoneKeyProvisioningProtocol(
    private val nowMillis: () -> Long,
    private val random: SecureRandom = SecureRandom(),
) {
    private class Pending(val pair: KeyPair, val challenge: ByteArray, val deadline: Long)
    private val lock = Any()
    private var pending: Pending? = null
    private var generation = 0L

    fun begin(unlocked: Boolean): ProvisioningChallenge {
        val attempt = synchronized(lock) { clearLocked(); generation }
        if (!unlocked) fail("device_locked")
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048, random) }.generateKeyPair()
        val challenge = ByteArray(CHALLENGE_BYTES).also(random::nextBytes)
        val receipt = ProvisioningChallenge(encode(pair.public.encoded), encode(challenge), TTL_MILLIS)
        val deadline = Math.addExact(nowMillis(), TTL_MILLIS)
        synchronized(lock) {
            if (generation != attempt) { challenge.fill(0); fail("challenge_cancelled") }
            pending = Pending(pair, challenge, deadline)
        }
        return receipt
    }

    /** Every commit attempt consumes the pending challenge, including malformed or expired input. */
    fun consume(envelope: ProvisioningEnvelope, unlocked: Boolean): CharArray {
        val request = synchronized(lock) {
            val current = pending ?: fail("no_pending_challenge")
            pending = null
            generation++
            current
        }
        var wrapped: ByteArray? = null
        var iv: ByteArray? = null
        var ciphertext: ByteArray? = null
        var aes: ByteArray? = null
        var plaintext: ByteArray? = null
        var receivedChallenge: ByteArray? = null
        try {
            if (!unlocked) fail("device_locked")
            if (nowMillis() >= request.deadline) fail("challenge_expired")
            receivedChallenge = decode(envelope.challenge, CHALLENGE_BYTES)
            if (!MessageDigest.isEqual(request.challenge, receivedChallenge)) fail("challenge_mismatch")
            wrapped = decode(envelope.wrappedKey, RSA_BYTES)
            iv = decode(envelope.iv, IV_BYTES)
            ciphertext = decode(envelope.ciphertext, null, MIN_KEY_BYTES + TAG_BYTES, MAX_KEY_BYTES + TAG_BYTES)
            val unwrap = Cipher.getInstance("RSA/ECB/OAEPPadding")
            unwrap.init(Cipher.DECRYPT_MODE, request.pair.private, oaepParameters())
            aes = unwrap.doFinal(wrapped)
            if (aes.size != AES_BYTES) fail("invalid_envelope")
            val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
            decrypt.init(Cipher.DECRYPT_MODE, SecretKeySpec(aes, "AES"), GCMParameterSpec(128, iv))
            decrypt.updateAAD(aad(request.challenge))
            plaintext = decrypt.doFinal(ciphertext)
            if (plaintext.size !in MIN_KEY_BYTES..MAX_KEY_BYTES || plaintext.any { it.toInt() !in 0x21..0x7e }) {
                fail("invalid_key_format")
            }
            if (nowMillis() >= request.deadline) fail("challenge_expired")
            return CharArray(plaintext.size) { plaintext[it].toInt().toChar() }
        } catch (failure: ProvisioningFailure) {
            throw failure
        } catch (_: Exception) {
            // Provider messages and exception chains can contain input. Never expose them.
            fail("invalid_envelope")
        } finally {
            listOf(wrapped, iv, ciphertext, aes, plaintext, receivedChallenge, request.challenge).forEach { it?.fill(0) }
        }
    }

    fun clear() = synchronized(lock) { clearLocked() }

    private fun clearLocked() {
        pending?.challenge?.fill(0)
        pending = null
        generation++
    }

    fun expire(challenge: String) {
        synchronized(lock) {
            val current = pending ?: return
            if (nowMillis() >= current.deadline && encode(current.challenge) == challenge) clearLocked()
        }
    }

    companion object {
        const val TTL_MILLIS = 120_000L
        const val MIN_KEY_BYTES = 16
        const val MAX_KEY_BYTES = 1_024
        const val CHALLENGE_BYTES = 32
        const val AES_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
        const val RSA_BYTES = 256
        const val MAX_ENVELOPE_CHARACTERS = 4_096
        private val encoder = Base64.getUrlEncoder().withoutPadding()
        private val decoder = Base64.getUrlDecoder()
        private val domain = "hans-fast-phone-key-provisioning-v1\u0000enable=true\u0000".toByteArray(StandardCharsets.US_ASCII)

        fun encode(value: ByteArray): String = encoder.encodeToString(value)
        fun aad(challenge: ByteArray): ByteArray = domain + challenge
        fun oaepParameters() = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT)

        private fun decode(value: String, exact: Int?, minimum: Int = 0, maximum: Int = MAX_KEY_BYTES + TAG_BYTES): ByteArray {
            if (value.isEmpty() || value.length > MAX_ENVELOPE_CHARACTERS || value.any { !it.isLetterOrDigit() && it != '-' && it != '_' }) {
                fail("invalid_envelope")
            }
            val decoded = decoder.decode(value)
            if (encode(decoded) != value || (exact != null && decoded.size != exact) ||
                (exact == null && decoded.size !in minimum..maximum)
            ) {
                decoded.fill(0)
                fail("invalid_envelope")
            }
            return decoded
        }

        private fun fail(code: String): Nothing = throw ProvisioningFailure(code)
    }
}

internal data class ProvisioningChallenge(val publicKey: String, val challenge: String, val ttlMillis: Long)
internal data class ProvisioningEnvelope(val challenge: String, val wrappedKey: String, val iv: String, val ciphertext: String)
internal class ProvisioningFailure(val code: String) : IllegalArgumentException(code)
