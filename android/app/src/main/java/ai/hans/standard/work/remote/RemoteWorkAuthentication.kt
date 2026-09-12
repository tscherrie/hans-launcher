package ai.hans.standard.work.remote

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

internal data class RemoteWorkAuthenticationProof(
    val configurationDigest: String,
    val publicKeySpkiSha256: String,
    val timestampEpochMillis: Long,
    val nonce: String,
    val bodySha256: String,
    val signatureBase64Url: String,
) {
    init {
        require(SHA_256.matches(configurationDigest))
        require(SHA_256.matches(publicKeySpkiSha256))
        require(timestampEpochMillis >= 0L)
        require(nonce.matches(NONCE))
        require(SHA_256.matches(bodySha256))
        require(signatureBase64Url.matches(SIGNATURE))
    }

    override fun toString(): String =
        "RemoteWorkAuthenticationProof(configurationDigest=$configurationDigest, credentials=<redacted>)"
}

internal interface RemoteWorkRequestAuthenticator {
    fun publicKeySpki(identity: RemoteWorkerConnectionIdentity): ByteArray

    fun authenticate(
        identity: RemoteWorkerConnectionIdentity,
        method: String,
        path: String,
        body: ByteArray,
    ): RemoteWorkAuthenticationProof
}

internal interface RemoteWorkSigningKeyProvider {
    fun publicKeySpki(alias: String): ByteArray
    fun sign(alias: String, canonicalRequest: ByteArray): ByteArray
}

/**
 * Application-level client authentication backed by a non-exportable Android Keystore key.
 *
 * The pinned TLS peer authenticates the worker to Hans. The worker registers the returned public
 * SPKI once and verifies every request signature, which authenticates Hans to the worker without
 * storing a reusable bearer token. No alias, private key or signature input is exposed to Codex.
 */
internal class AndroidKeystoreRemoteWorkAuthenticator(
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val nonceFactory: () -> ByteArray = {
        ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
    },
    private val signingKeys: RemoteWorkSigningKeyProvider = AndroidKeystoreSigningKeyProvider,
) : RemoteWorkRequestAuthenticator {
    override fun publicKeySpki(identity: RemoteWorkerConnectionIdentity): ByteArray =
        signingKeys.publicKeySpki(identity.deviceKeyAlias).copyOf()

    override fun authenticate(
        identity: RemoteWorkerConnectionIdentity,
        method: String,
        path: String,
        body: ByteArray,
    ): RemoteWorkAuthenticationProof {
        require(method in ALLOWED_METHODS) { "Unsupported remote worker method" }
        require(path.matches(SAFE_PATH) && ".." !in path) { "Unsafe remote worker path" }
        require(body.size <= MAX_SIGNED_BODY_BYTES) { "Remote worker body is too large" }
        val timestamp = nowEpochMillis().also { require(it >= 0L) }
        val nonceBytes = nonceFactory().also { require(it.size == NONCE_BYTES) }
        val nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(nonceBytes)
        val bodyDigest = sha256(body)
        val publicSpki = publicKeySpki(identity)
        val publicDigest = sha256(publicSpki)
        val canonical = canonicalSignatureInput(
            configurationDigest = identity.configurationDigest,
            publicKeySpkiSha256 = publicDigest,
            timestampEpochMillis = timestamp,
            nonce = nonce,
            method = method,
            path = path,
            bodySha256 = bodyDigest,
        )
        val signature = signingKeys.sign(identity.deviceKeyAlias, canonical)
        require(signature.size in MIN_SIGNATURE_BYTES..MAX_SIGNATURE_BYTES) {
            "Invalid remote worker request signature"
        }
        return RemoteWorkAuthenticationProof(
            configurationDigest = identity.configurationDigest,
            publicKeySpkiSha256 = publicDigest,
            timestampEpochMillis = timestamp,
            nonce = nonce,
            bodySha256 = bodyDigest,
            signatureBase64Url = Base64.getUrlEncoder().withoutPadding().encodeToString(signature),
        )
    }

    private companion object {
        const val NONCE_BYTES = 24
        const val MAX_SIGNED_BODY_BYTES = 4 * 1024 * 1024
        const val MIN_SIGNATURE_BYTES = 64
        const val MAX_SIGNATURE_BYTES = 256
        val ALLOWED_METHODS = setOf("GET", "POST", "PUT")
        val SAFE_PATH = Regex("/[A-Za-z0-9._~!$&'()*+,;=:@%/-]{1,2047}")
    }
}

private object AndroidKeystoreSigningKeyProvider : RemoteWorkSigningKeyProvider {
    override fun publicKeySpki(alias: String): ByteArray = keyPair(alias).public.encoded.copyOf()

    override fun sign(alias: String, canonicalRequest: ByteArray): ByteArray =
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initSign(keyPair(alias).private)
            update(canonicalRequest)
            sign()
        }

    private fun keyPair(alias: String): KeyPair {
        require(SAFE_KEY_ALIAS.matches(alias)) { "Invalid remote worker key alias" }
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val existing = keyStore.getEntry(alias, null) as? KeyStore.PrivateKeyEntry
        if (existing != null) return KeyPair(existing.certificate.publicKey, existing.privateKey)
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE)
        generator.initialize(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            )
                .setAlgorithmParameterSpec(ECGenParameterSpec(EC_CURVE))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKeyPair()
    }

    private const val KEYSTORE = "AndroidKeyStore"
    private const val EC_CURVE = "secp256r1"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
}

internal fun canonicalSignatureInput(
    configurationDigest: String,
    publicKeySpkiSha256: String,
    timestampEpochMillis: Long,
    nonce: String,
    method: String,
    path: String,
    bodySha256: String,
): ByteArray {
    require(SHA_256.matches(configurationDigest))
    require(SHA_256.matches(publicKeySpkiSha256))
    require(timestampEpochMillis >= 0L)
    require(nonce.matches(NONCE))
    require(method.matches(Regex("[A-Z]{3,8}")))
    require(path.startsWith('/') && ".." !in path && '\n' !in path && '\r' !in path)
    require(SHA_256.matches(bodySha256))
    return listOf(
        "hans-remote-worker-request-v1",
        configurationDigest,
        publicKeySpkiSha256,
        timestampEpochMillis.toString(),
        nonce,
        method,
        path,
        bodySha256,
    ).joinToString("\n", postfix = "\n").toByteArray(StandardCharsets.UTF_8)
}

private val NONCE = Regex("[A-Za-z0-9_-]{32}")
private val SIGNATURE = Regex("[A-Za-z0-9_-]{64,256}")
