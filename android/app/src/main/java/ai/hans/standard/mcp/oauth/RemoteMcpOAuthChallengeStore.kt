package ai.hans.standard.mcp.oauth

import ai.hans.standard.mcp.AndroidKeystoreRemoteMcpOAuthCipher
import ai.hans.standard.mcp.RemoteMcpOAuthCipher
import ai.hans.standard.mcp.RemoteMcpOAuthCipherEnvelope
import ai.hans.standard.mcp.RemoteMcpOAuthCredentialIdentity
import ai.hans.standard.mcp.RemoteMcpPrivateStateFile
import ai.hans.standard.mcp.RemoteMcpPrivateStateLock
import ai.hans.standard.plugins.runtime.OAuthCredentialHandle
import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

internal enum class RemoteMcpOAuthChallengePhase { PENDING, CLAIMED }

internal class RemoteMcpOAuthPendingChallenge(
    val spec: RemoteMcpOAuthConnectionSpec,
    val client: RemoteMcpOAuthResolvedClient,
    scopes: Set<String>,
    val issuer: String,
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val state: String,
    val nonce: String,
    val verifier: String,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val expectedCredentialGeneration: Long?,
    val authorizationResponseIssuerRequired: Boolean,
) {
    val scopes: Set<String> = scopes.toSortedSet()

    init {
        requireOAuthHttpsUri(issuer)
        requireOAuthHttpsUri(authorizationEndpoint)
        requireOAuthHttpsUri(tokenEndpoint)
        require(state.matches(SAFE_STATE))
        require(nonce.matches(SAFE_TOKEN))
        require(verifier.matches(SAFE_TOKEN) && verifier.length in 43..128)
        require(createdAtEpochMillis >= 0L && expiresAtEpochMillis > createdAtEpochMillis)
        expectedCredentialGeneration?.let { require(it > 0L) }
        require(this.scopes.size <= 32)
    }

    override fun toString(): String =
        "RemoteMcpOAuthPendingChallenge(pluginId=${spec.identity.pluginId}, " +
            "serverId=${spec.identity.serverId})"
}

internal class RemoteMcpOAuthChallengeClaim internal constructor(
    val challenge: RemoteMcpOAuthPendingChallenge,
    internal val claimId: String,
) {
    override fun toString(): String =
        "RemoteMcpOAuthChallengeClaim(pluginId=${challenge.spec.identity.pluginId}, " +
            "serverId=${challenge.spec.identity.serverId})"
}

internal sealed interface RemoteMcpOAuthChallengeClaimResult {
    data class Claimed(val claim: RemoteMcpOAuthChallengeClaim) :
        RemoteMcpOAuthChallengeClaimResult
    data object Missing : RemoteMcpOAuthChallengeClaimResult
    data object Expired : RemoteMcpOAuthChallengeClaimResult
    data object Mismatch : RemoteMcpOAuthChallengeClaimResult
    data object Replayed : RemoteMcpOAuthChallengeClaimResult
    data object Unavailable : RemoteMcpOAuthChallengeClaimResult
}

/**
 * One process-death-safe, encrypted OAuth challenge. A callback atomically moves PENDING to
 * CLAIMED before network I/O. A CLAIMED exchange is never replayed after process death because the
 * authorization code may already have been consumed remotely.
 */
internal class RemoteMcpOAuthChallengeStore internal constructor(
    directory: File,
    private val cipher: RemoteMcpOAuthCipher,
    private val nowEpochMillis: () -> Long,
    private val random: RemoteMcpOAuthRandomSource,
) {
    constructor(context: Context) : this(
        directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME),
        cipher = AndroidKeystoreRemoteMcpOAuthCipher(CHALLENGE_KEY_ALIAS),
        nowEpochMillis = System::currentTimeMillis,
        random = SecureRemoteMcpOAuthRandomSource,
    )

    private val stateFile = RemoteMcpPrivateStateFile(directory, FILE_NAME, MAX_DOCUMENT_BYTES)

    fun put(challenge: RemoteMcpOAuthPendingChallenge): Boolean =
        synchronized(RemoteMcpPrivateStateLock.process) {
            val existing = runCatching(::read).getOrElse { return@synchronized false }
            if (existing != null && existing.expiresAtEpochMillis > nowEpochMillis()) {
                return@synchronized false
            }
            if (existing != null) runCatching(stateFile::delete)
            runCatching { write(challenge, RemoteMcpOAuthChallengePhase.PENDING, null) }.isSuccess
        }

    fun claim(callbackState: String): RemoteMcpOAuthChallengeClaimResult =
        synchronized(RemoteMcpPrivateStateLock.process) {
            if (!callbackState.matches(SAFE_STATE)) {
                return@synchronized RemoteMcpOAuthChallengeClaimResult.Mismatch
            }
            val stored = runCatching(::read).getOrElse {
                return@synchronized RemoteMcpOAuthChallengeClaimResult.Unavailable
            } ?: return@synchronized RemoteMcpOAuthChallengeClaimResult.Missing
            if (stored.expiresAtEpochMillis <= nowEpochMillis()) {
                runCatching(stateFile::delete)
                return@synchronized RemoteMcpOAuthChallengeClaimResult.Expired
            }
            if (!constantTimeEquals(stored.stateDigest, stateDigest(callbackState))) {
                return@synchronized RemoteMcpOAuthChallengeClaimResult.Mismatch
            }
            val challenge = runCatching { decrypt(stored) }.getOrElse {
                return@synchronized RemoteMcpOAuthChallengeClaimResult.Unavailable
            }
            if (!constantTimeEquals(challenge.state, callbackState)) {
                return@synchronized RemoteMcpOAuthChallengeClaimResult.Mismatch
            }
            if (stored.phase == RemoteMcpOAuthChallengePhase.CLAIMED) {
                return@synchronized RemoteMcpOAuthChallengeClaimResult.Replayed
            }
            val claimId = random.bytes(24).let { bytes ->
                try {
                    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
                } finally {
                    bytes.fill(0)
                }
            }
            return@synchronized try {
                write(challenge, RemoteMcpOAuthChallengePhase.CLAIMED, claimId)
                RemoteMcpOAuthChallengeClaimResult.Claimed(
                    RemoteMcpOAuthChallengeClaim(challenge, claimId),
                )
            } catch (_: Throwable) {
                RemoteMcpOAuthChallengeClaimResult.Unavailable
            }
        }

    fun finish(claim: RemoteMcpOAuthChallengeClaim): Boolean =
        synchronized(RemoteMcpPrivateStateLock.process) {
            val current = runCatching(::read).getOrElse { return@synchronized false }
                ?: return@synchronized false
            if (current.phase != RemoteMcpOAuthChallengePhase.CLAIMED ||
                current.claimId != claim.claimId ||
                current.stateDigest != stateDigest(claim.challenge.state)
            ) {
                return@synchronized false
            }
            runCatching(stateFile::delete).isSuccess
        }

    fun cancelPending(pluginId: String, serverId: String): Boolean =
        synchronized(RemoteMcpPrivateStateLock.process) {
            val current = runCatching(::read).getOrElse { return@synchronized false }
                ?: return@synchronized true
            if (current.phase != RemoteMcpOAuthChallengePhase.PENDING) return@synchronized false
            val challenge = runCatching { decrypt(current) }.getOrElse { return@synchronized false }
            if (challenge.spec.identity.pluginId != pluginId ||
                challenge.spec.identity.serverId != serverId
            ) {
                return@synchronized false
            }
            runCatching(stateFile::delete).isSuccess
        }

    private fun read(): StoredChallenge? {
        val bytes = stateFile.readOrNull() ?: return null
        return try {
            StoredChallenge.decode(bytes.toString(StandardCharsets.UTF_8))
        } finally {
            bytes.fill(0)
        }
    }

    private fun write(
        challenge: RemoteMcpOAuthPendingChallenge,
        phase: RemoteMcpOAuthChallengePhase,
        claimId: String?,
    ) {
        require((phase == RemoteMcpOAuthChallengePhase.CLAIMED) == (claimId != null))
        val payload = ChallengeCodec.encode(challenge).toByteArray(StandardCharsets.UTF_8)
        val metadata = StoredChallenge.metadata(
            phase = phase,
            claimId = claimId,
            stateDigest = stateDigest(challenge.state),
            expiresAtEpochMillis = challenge.expiresAtEpochMillis,
        )
        val aad = metadata.aad()
        try {
            val envelope = cipher.encrypt(payload, aad)
            try {
                val encoded = metadata.copy(
                    iv = Base64.getEncoder().encodeToString(envelope.iv),
                    ciphertext = Base64.getEncoder().encodeToString(envelope.ciphertext),
                ).encode().toByteArray(StandardCharsets.UTF_8)
                try {
                    stateFile.write(encoded)
                } finally {
                    encoded.fill(0)
                }
            } finally {
                envelope.erase()
            }
        } finally {
            payload.fill(0)
            aad.fill(0)
        }
    }

    private fun decrypt(stored: StoredChallenge): RemoteMcpOAuthPendingChallenge {
        val envelope = RemoteMcpOAuthCipherEnvelope(
            Base64.getDecoder().decode(stored.iv),
            Base64.getDecoder().decode(stored.ciphertext),
        )
        val aad = stored.aad()
        return try {
            val plaintext = cipher.decrypt(envelope, aad)
            try {
                ChallengeCodec.decode(plaintext.toString(StandardCharsets.UTF_8))
            } finally {
                plaintext.fill(0)
            }
        } finally {
            envelope.erase()
            aad.fill(0)
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "remote-mcp/oauth-challenge"
        const val FILE_NAME = "pending-v1.json"
        const val CHALLENGE_KEY_ALIAS = "ai.hans.standard.remote-mcp-oauth-challenge.v1"
        const val MAX_DOCUMENT_BYTES = 128 * 1024
    }
}

private data class StoredChallenge(
    val phase: RemoteMcpOAuthChallengePhase,
    val claimId: String?,
    val stateDigest: String,
    val expiresAtEpochMillis: Long,
    val iv: String,
    val ciphertext: String,
) {
    init {
        require((phase == RemoteMcpOAuthChallengePhase.CLAIMED) == (claimId != null))
        claimId?.let { require(it.matches(SAFE_TOKEN)) }
        require(stateDigest.matches(Regex("[0-9a-f]{64}")))
        require(expiresAtEpochMillis >= 0L)
        require(iv.length <= 64 && ciphertext.length <= 96 * 1024)
    }

    fun aad(): ByteArray = JSONObject()
        .put("schema", SCHEMA)
        .put("version", VERSION)
        .put("phase", phase.name)
        .put("claimId", claimId ?: JSONObject.NULL)
        .put("stateDigest", stateDigest)
        .put("expiresAtEpochMillis", expiresAtEpochMillis)
        .toString()
        .toByteArray(StandardCharsets.UTF_8)

    fun encode(): String = JSONObject()
        .put("schema", SCHEMA)
        .put("version", VERSION)
        .put("phase", phase.name)
        .put("claimId", claimId ?: JSONObject.NULL)
        .put("stateDigest", stateDigest)
        .put("expiresAtEpochMillis", expiresAtEpochMillis)
        .put("iv", iv)
        .put("ciphertext", ciphertext)
        .toString()

    companion object {
        const val SCHEMA = "hans.remote-mcp-oauth-challenge"
        const val VERSION = 1

        fun metadata(
            phase: RemoteMcpOAuthChallengePhase,
            claimId: String?,
            stateDigest: String,
            expiresAtEpochMillis: Long,
        ) = StoredChallenge(phase, claimId, stateDigest, expiresAtEpochMillis, "", "")

        fun decode(raw: String): StoredChallenge {
            val value = JSONObject(raw)
            require(
                value.keys().asSequence().toSet() == setOf(
                    "schema", "version", "phase", "claimId", "stateDigest",
                    "expiresAtEpochMillis", "iv", "ciphertext",
                ),
            )
            require(value.getString("schema") == SCHEMA && value.getInt("version") == VERSION)
            return StoredChallenge(
                phase = RemoteMcpOAuthChallengePhase.valueOf(value.getString("phase")),
                claimId = if (value.isNull("claimId")) null else value.getString("claimId"),
                stateDigest = value.getString("stateDigest"),
                expiresAtEpochMillis = value.getLong("expiresAtEpochMillis"),
                iv = value.getString("iv"),
                ciphertext = value.getString("ciphertext"),
            )
        }
    }
}

private object ChallengeCodec {
    fun encode(challenge: RemoteMcpOAuthPendingChallenge): String = JSONObject()
        .put("pluginId", challenge.spec.identity.pluginId)
        .put("serverId", challenge.spec.identity.serverId)
        .put("configurationDigest", challenge.spec.identity.configurationDigest)
        .put("handle", challenge.spec.identity.handle.value)
        .put("resourceEndpoint", challenge.spec.resourceEndpoint)
        .put("clientId", challenge.client.clientId)
        .put("redirectUri", challenge.client.redirect.uri)
        .put("redirectKind", challenge.client.redirect.kind.name)
        .put("registrationKind", challenge.client.kind.name)
        .put("registrationGeneration", challenge.client.registrationGeneration ?: JSONObject.NULL)
        .put("scopes", JSONArray(challenge.scopes.toList()))
        .put("issuer", challenge.issuer)
        .put("authorizationEndpoint", challenge.authorizationEndpoint)
        .put("tokenEndpoint", challenge.tokenEndpoint)
        .put("state", challenge.state)
        .put("nonce", challenge.nonce)
        .put("verifier", challenge.verifier)
        .put("createdAtEpochMillis", challenge.createdAtEpochMillis)
        .put("expiresAtEpochMillis", challenge.expiresAtEpochMillis)
        .put("expectedCredentialGeneration", challenge.expectedCredentialGeneration ?: JSONObject.NULL)
        .put("authorizationResponseIssuerRequired", challenge.authorizationResponseIssuerRequired)
        .toString()

    fun decode(raw: String): RemoteMcpOAuthPendingChallenge {
        val value = JSONObject(raw)
        val expected = setOf(
            "pluginId", "serverId", "configurationDigest", "handle", "resourceEndpoint",
            "clientId", "redirectUri", "redirectKind", "registrationKind",
            "registrationGeneration", "scopes", "issuer", "authorizationEndpoint",
            "tokenEndpoint", "state", "nonce", "verifier", "createdAtEpochMillis",
            "expiresAtEpochMillis", "expectedCredentialGeneration",
            "authorizationResponseIssuerRequired",
        )
        require(value.keys().asSequence().toSet() == expected)
        val scopes = value.getJSONArray("scopes")
        require(scopes.length() in 0..32)
        val scopeSet = (0 until scopes.length()).map(scopes::getString).toSet()
        val identity = RemoteMcpOAuthCredentialIdentity(
            pluginId = value.getString("pluginId"),
            serverId = value.getString("serverId"),
            configurationDigest = value.getString("configurationDigest"),
            handle = OAuthCredentialHandle(value.getString("handle")),
        )
        return RemoteMcpOAuthPendingChallenge(
            spec = RemoteMcpOAuthConnectionSpec(
                identity = identity,
                resourceEndpoint = value.getString("resourceEndpoint"),
                clientOptions = RemoteMcpOAuthClientOptions(),
            ),
            client = RemoteMcpOAuthResolvedClient(
                clientId = value.getString("clientId"),
                redirect = RemoteMcpOAuthRedirectRoute(
                    value.getString("redirectUri"),
                    RemoteMcpOAuthRedirectKind.valueOf(value.getString("redirectKind")),
                ),
                kind = RemoteMcpOAuthClientRegistrationKind.valueOf(
                    value.getString("registrationKind"),
                ),
                registrationGeneration = if (value.isNull("registrationGeneration")) {
                    null
                } else {
                    value.getLong("registrationGeneration")
                },
            ),
            scopes = scopeSet,
            issuer = value.getString("issuer"),
            authorizationEndpoint = value.getString("authorizationEndpoint"),
            tokenEndpoint = value.getString("tokenEndpoint"),
            state = value.getString("state"),
            nonce = value.getString("nonce"),
            verifier = value.getString("verifier"),
            createdAtEpochMillis = value.getLong("createdAtEpochMillis"),
            expiresAtEpochMillis = value.getLong("expiresAtEpochMillis"),
            expectedCredentialGeneration = if (value.isNull("expectedCredentialGeneration")) {
                null
            } else {
                value.getLong("expectedCredentialGeneration")
            },
            authorizationResponseIssuerRequired = value.getBoolean(
                "authorizationResponseIssuerRequired",
            ),
        )
    }
}

private fun stateDigest(state: String): String = MessageDigest.getInstance("SHA-256")
    .digest(state.toByteArray(StandardCharsets.US_ASCII))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun constantTimeEquals(left: String, right: String): Boolean = MessageDigest.isEqual(
    left.toByteArray(StandardCharsets.US_ASCII),
    right.toByteArray(StandardCharsets.US_ASCII),
)

private val SAFE_STATE = Regex("[A-Za-z0-9._~-]{80,256}")
private val SAFE_TOKEN = Regex("[A-Za-z0-9._~-]{16,256}")
