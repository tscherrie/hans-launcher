package ai.hans.standard.mcp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import ai.hans.standard.plugins.runtime.OAuthCredentialHandle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

internal data class RemoteMcpOAuthCredentialIdentity(
    val pluginId: String,
    val serverId: String,
    val configurationDigest: String,
    val handle: OAuthCredentialHandle,
) {
    init {
        RemoteMcpActivationIdentity(pluginId, serverId, configurationDigest)
    }

    internal fun matches(entry: StoredOAuthCredential): Boolean =
        pluginId == entry.pluginId &&
            serverId == entry.serverId &&
            configurationDigest == entry.configurationDigest &&
            handle == entry.handle
}

internal enum class RemoteMcpOAuthVaultStatusState {
    AVAILABLE,
    MISSING,
    EXPIRED,
    STALE_IDENTITY,
    UNAVAILABLE,
}

/** Safe passive projection. Opaque handles, configuration digests and token data are omitted. */
internal data class RemoteMcpOAuthCredentialStatus(
    val pluginId: String,
    val serverId: String,
    val state: RemoteMcpOAuthVaultStatusState,
    val generation: Long?,
    val expiresAtEpochMillis: Long?,
)

internal enum class RemoteMcpOAuthMutationResult {
    STORED,
    ROTATED,
    REMOVED,
    MISSING,
    ALREADY_EXISTS,
    STALE_IDENTITY,
    STALE_GENERATION,
}

internal class RemoteMcpOAuthCipherEnvelope(
    iv: ByteArray,
    ciphertext: ByteArray,
) {
    val iv: ByteArray = iv.copyOf()
    val ciphertext: ByteArray = ciphertext.copyOf()

    init {
        require(this.iv.size == GCM_IV_BYTES) { "Invalid Remote MCP OAuth IV" }
        require(this.ciphertext.size in 1..MAX_CIPHERTEXT_BYTES) {
            "Invalid Remote MCP OAuth ciphertext"
        }
    }

    fun erase() {
        iv.fill(0)
        ciphertext.fill(0)
    }

    override fun toString(): String = "RemoteMcpOAuthCipherEnvelope(redacted)"

    internal companion object {
        const val GCM_IV_BYTES = 12
        const val MAX_CIPHERTEXT_BYTES = 64 * 1024
    }
}

/** Small seam that lets host tests verify envelope behavior without emulating AndroidKeyStore. */
internal interface RemoteMcpOAuthCipher {
    fun encrypt(plaintext: ByteArray, authenticatedMetadata: ByteArray): RemoteMcpOAuthCipherEnvelope
    fun decrypt(
        envelope: RemoteMcpOAuthCipherEnvelope,
        authenticatedMetadata: ByteArray,
    ): ByteArray

    fun deleteKey()
}

/** Non-exportable AES-GCM key; a missing or invalid key never falls back to plaintext storage. */
internal class AndroidKeystoreRemoteMcpOAuthCipher(
    private val keyAlias: String = DEFAULT_KEY_ALIAS,
) : RemoteMcpOAuthCipher {
    override fun encrypt(
        plaintext: ByteArray,
        authenticatedMetadata: ByteArray,
    ): RemoteMcpOAuthCipherEnvelope {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(authenticatedMetadata)
        return RemoteMcpOAuthCipherEnvelope(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(
        envelope: RemoteMcpOAuthCipherEnvelope,
        authenticatedMetadata: ByteArray,
    ): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            existingKey(),
            GCMParameterSpec(GCM_TAG_BITS, envelope.iv),
        )
        cipher.updateAAD(authenticatedMetadata)
        return cipher.doFinal(envelope.ciphertext)
    }

    override fun deleteKey() {
        KeyStore.getInstance(KEYSTORE).apply { load(null) }.deleteEntry(keyAlias)
    }

    private fun getOrCreateKey(): SecretKey = existingKeyOrNull() ?: run {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        generator.generateKey()
    }

    private fun existingKey(): SecretKey = existingKeyOrNull()
        ?: throw RemoteMcpFailure("mcp_oauth_key_unavailable")

    private fun existingKeyOrNull(): SecretKey? {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        val key = keyStore.getKey(keyAlias, null) ?: return null
        return key as? SecretKey ?: throw RemoteMcpFailure("mcp_oauth_key_unavailable")
    }

    private companion object {
        const val DEFAULT_KEY_ALIAS = "ai.hans.standard.remote-mcp-oauth.v1"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}

/**
 * Android-Keystore-backed OAuth vault for Remote MCP.
 *
 * Only authenticated ciphertext and bounded, non-secret routing metadata are stored in the
 * no-backup directory. All reads authenticate the complete identity and generation before an
 * access token enters the short-lived callback required by [RemoteMcpOAuthVault].
 */
internal class AndroidKeystoreRemoteMcpOAuthVault internal constructor(
    directory: File,
    private val cipher: RemoteMcpOAuthCipher,
    private val nowEpochMillis: () -> Long,
) : RemoteMcpOAuthVault {
    constructor(context: Context) : this(
        directory = File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME),
        cipher = AndroidKeystoreRemoteMcpOAuthCipher(),
        nowEpochMillis = System::currentTimeMillis,
    )

    private val stateFile = RemoteMcpPrivateStateFile(directory, FILE_NAME, MAX_DOCUMENT_BYTES)

    override fun state(identity: RemoteMcpOAuthCredentialIdentity): OAuthCredentialState =
        synchronized(RemoteMcpPrivateStateLock.process) {
            val entries = runCatching { readCredentials() }.getOrElse {
                return@synchronized OAuthCredentialState.UNAVAILABLE
            }
            val entry = entries.singleOrNull { it.handle == identity.handle }
                ?: return@synchronized OAuthCredentialState.MISSING
            if (!identity.matches(entry)) {
                return@synchronized OAuthCredentialState.STALE_IDENTITY
            }
            val payload = runCatching { decrypt(entry) }.getOrNull()
                ?: return@synchronized OAuthCredentialState.UNAVAILABLE
            payload.erase()
            if (entry.expiresAtEpochMillis != null && entry.expiresAtEpochMillis <= nowEpochMillis()) {
                OAuthCredentialState.EXPIRED
            } else {
                OAuthCredentialState.AVAILABLE
            }
        }

    override fun <T> withBearerToken(
        identity: RemoteMcpOAuthCredentialIdentity,
        block: (String) -> T,
    ): T {
        // Never hold the persistence lock while the transport callback performs network I/O.
        val payload = synchronized(RemoteMcpPrivateStateLock.process) {
            val entry = safeOperation("mcp_oauth_vault_unavailable") {
                readCredentials().singleOrNull { it.handle == identity.handle }
                    ?: throw RemoteMcpFailure("mcp_oauth_missing")
            }
            if (!identity.matches(entry)) {
                throw RemoteMcpFailure("mcp_oauth_stale_identity")
            }
            if (entry.expiresAtEpochMillis != null &&
                entry.expiresAtEpochMillis <= nowEpochMillis()
            ) {
                throw RemoteMcpFailure("mcp_oauth_expired")
            }
            safeOperation("mcp_oauth_vault_unavailable") { decrypt(entry) }
        }
        try {
            val token = payload.accessToken.toString(StandardCharsets.US_ASCII)
            return block(token)
        } finally {
            payload.erase()
        }
    }

    fun status(identity: RemoteMcpOAuthCredentialIdentity): RemoteMcpOAuthCredentialStatus =
        synchronized(RemoteMcpPrivateStateLock.process) {
            val entries = runCatching { readCredentials() }.getOrElse {
                return@synchronized identity.status(RemoteMcpOAuthVaultStatusState.UNAVAILABLE)
            }
            val entry = entries.singleOrNull { it.handle == identity.handle }
                ?: return@synchronized identity.status(RemoteMcpOAuthVaultStatusState.MISSING)
            if (!identity.matches(entry)) {
                return@synchronized identity.status(RemoteMcpOAuthVaultStatusState.STALE_IDENTITY)
            }
            val valid = runCatching { decrypt(entry).also(PlainOAuthPayload::erase) }.isSuccess
            if (!valid) {
                return@synchronized identity.status(
                    RemoteMcpOAuthVaultStatusState.UNAVAILABLE,
                    entry,
                )
            }
            identity.status(
                if (entry.expiresAtEpochMillis != null &&
                    entry.expiresAtEpochMillis <= nowEpochMillis()
                ) {
                    RemoteMcpOAuthVaultStatusState.EXPIRED
                } else {
                    RemoteMcpOAuthVaultStatusState.AVAILABLE
                },
                entry,
            )
        }

    fun store(
        identity: RemoteMcpOAuthCredentialIdentity,
        accessToken: CharArray,
        refreshToken: CharArray?,
        expiresAtEpochMillis: Long?,
    ): RemoteMcpOAuthMutationResult = synchronized(RemoteMcpPrivateStateLock.process) {
        safeOperation("mcp_oauth_vault_unavailable") {
            val current = readCredentials()
            if (current.any { it.handle == identity.handle }) {
                return@synchronized RemoteMcpOAuthMutationResult.ALREADY_EXISTS
            }
            require(current.size < MAX_CREDENTIALS) { "Remote MCP OAuth vault is full" }
            val issuedAt = nowEpochMillis()
            requireExpiry(expiresAtEpochMillis, issuedAt)
            val stored = encrypt(
                identity = identity,
                generation = 1L,
                issuedAtEpochMillis = issuedAt,
                expiresAtEpochMillis = expiresAtEpochMillis,
                accessToken = accessToken,
                refreshToken = refreshToken,
            )
            writeCredentials(current + stored)
            RemoteMcpOAuthMutationResult.STORED
        }
    }

    /** A null refresh token retains the previously authenticated refresh token, if present. */
    fun refresh(
        identity: RemoteMcpOAuthCredentialIdentity,
        expectedGeneration: Long,
        accessToken: CharArray,
        refreshToken: CharArray?,
        expiresAtEpochMillis: Long?,
    ): RemoteMcpOAuthMutationResult = synchronized(RemoteMcpPrivateStateLock.process) {
        safeOperation("mcp_oauth_vault_unavailable") {
            val current = readCredentials()
            val index = current.indexOfFirst { it.handle == identity.handle }
            if (index < 0) return@synchronized RemoteMcpOAuthMutationResult.MISSING
            val previous = current[index]
            if (!identity.matches(previous)) {
                return@synchronized RemoteMcpOAuthMutationResult.STALE_IDENTITY
            }
            if (previous.generation != expectedGeneration) {
                return@synchronized RemoteMcpOAuthMutationResult.STALE_GENERATION
            }
            require(previous.generation < Long.MAX_VALUE) { "Remote MCP OAuth generation exhausted" }
            val priorPayload = decrypt(previous)
            val retainedRefresh = if (refreshToken == null) priorPayload.refreshToken else null
            try {
                val issuedAt = nowEpochMillis()
                requireExpiry(expiresAtEpochMillis, issuedAt)
                val replacement = encrypt(
                    identity = identity,
                    generation = previous.generation + 1L,
                    issuedAtEpochMillis = issuedAt,
                    expiresAtEpochMillis = expiresAtEpochMillis,
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    refreshTokenBytes = retainedRefresh,
                )
                writeCredentials(current.toMutableList().also { it[index] = replacement })
                RemoteMcpOAuthMutationResult.ROTATED
            } finally {
                priorPayload.erase()
            }
        }
    }

    /** Removal is exact and generation-bound, but remains possible for a tampered ciphertext. */
    fun remove(
        identity: RemoteMcpOAuthCredentialIdentity,
        expectedGeneration: Long,
    ): RemoteMcpOAuthMutationResult = synchronized(RemoteMcpPrivateStateLock.process) {
        safeOperation("mcp_oauth_vault_unavailable") {
            val current = readCredentials()
            val index = current.indexOfFirst { it.handle == identity.handle }
            if (index < 0) return@synchronized RemoteMcpOAuthMutationResult.MISSING
            val previous = current[index]
            if (!identity.matches(previous)) {
                return@synchronized RemoteMcpOAuthMutationResult.STALE_IDENTITY
            }
            if (previous.generation != expectedGeneration) {
                return@synchronized RemoteMcpOAuthMutationResult.STALE_GENERATION
            }
            val remaining = current.filterIndexed { candidate, _ -> candidate != index }
            writeCredentials(remaining)
            if (remaining.isEmpty()) runCatching(cipher::deleteKey)
            RemoteMcpOAuthMutationResult.REMOVED
        }
    }

    private fun encrypt(
        identity: RemoteMcpOAuthCredentialIdentity,
        generation: Long,
        issuedAtEpochMillis: Long,
        expiresAtEpochMillis: Long?,
        accessToken: CharArray,
        refreshToken: CharArray?,
        refreshTokenBytes: ByteArray? = null,
    ): StoredOAuthCredential {
        val access = tokenBytes(accessToken, MAX_ACCESS_TOKEN_BYTES)
        val refresh = refreshToken?.let { tokenBytes(it, MAX_REFRESH_TOKEN_BYTES) }
            ?: refreshTokenBytes?.copyOf()
        val metadata = StoredOAuthCredential.metadataOnly(
            identity,
            generation,
            issuedAtEpochMillis,
            expiresAtEpochMillis,
        )
        val plaintext = encodePayload(access, refresh)
        val aad = authenticatedMetadata(metadata)
        try {
            val envelope = cipher.encrypt(plaintext, aad)
            try {
                return metadata.copy(
                    iv = Base64.getEncoder().encodeToString(envelope.iv),
                    ciphertext = Base64.getEncoder().encodeToString(envelope.ciphertext),
                )
            } finally {
                envelope.erase()
            }
        } finally {
            access.fill(0)
            refresh?.fill(0)
            plaintext.fill(0)
            aad.fill(0)
        }
    }

    private fun decrypt(entry: StoredOAuthCredential): PlainOAuthPayload {
        val envelope = RemoteMcpOAuthCipherEnvelope(
            canonicalBase64(entry.iv, RemoteMcpOAuthCipherEnvelope.GCM_IV_BYTES),
            canonicalBase64(entry.ciphertext, RemoteMcpOAuthCipherEnvelope.MAX_CIPHERTEXT_BYTES),
        )
        val aad = authenticatedMetadata(entry)
        return try {
            val plaintext = cipher.decrypt(envelope, aad)
            try {
                decodePayload(plaintext)
            } finally {
                plaintext.fill(0)
            }
        } finally {
            envelope.erase()
            aad.fill(0)
        }
    }

    private fun readCredentials(): List<StoredOAuthCredential> {
        val bytes = stateFile.readOrNull() ?: return emptyList()
        return try {
            OAuthVaultCodec.decode(bytes.toString(StandardCharsets.UTF_8))
        } finally {
            bytes.fill(0)
        }
    }

    private fun writeCredentials(credentials: List<StoredOAuthCredential>) {
        val bytes = OAuthVaultCodec.encode(credentials).toByteArray(StandardCharsets.UTF_8)
        try {
            stateFile.write(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun RemoteMcpOAuthCredentialIdentity.status(
        state: RemoteMcpOAuthVaultStatusState,
        entry: StoredOAuthCredential? = null,
    ) = RemoteMcpOAuthCredentialStatus(
        pluginId = pluginId,
        serverId = serverId,
        state = state,
        generation = entry?.generation,
        expiresAtEpochMillis = entry?.expiresAtEpochMillis,
    )

    private inline fun <T> safeOperation(code: String, block: () -> T): T = try {
        block()
    } catch (failure: RemoteMcpFailure) {
        throw failure
    } catch (_: Throwable) {
        throw RemoteMcpFailure(code)
    }

    private companion object {
        const val DIRECTORY_NAME = "remote-mcp"
        const val FILE_NAME = "oauth-vault-v1.json"
        const val MAX_DOCUMENT_BYTES = 512 * 1024
        const val MAX_CREDENTIALS = 64
        const val MAX_ACCESS_TOKEN_BYTES = 16 * 1024
        const val MAX_REFRESH_TOKEN_BYTES = 32 * 1024
    }
}

internal data class StoredOAuthCredential(
    val handle: OAuthCredentialHandle,
    val pluginId: String,
    val serverId: String,
    val configurationDigest: String,
    val generation: Long,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long?,
    val iv: String,
    val ciphertext: String,
) {
    init {
        RemoteMcpActivationIdentity(pluginId, serverId, configurationDigest)
        require(generation > 0L && issuedAtEpochMillis >= 0L)
        require(expiresAtEpochMillis == null || expiresAtEpochMillis > issuedAtEpochMillis)
        require(iv.length <= 64 && ciphertext.length <= 96 * 1024)
    }

    companion object {
        fun metadataOnly(
            identity: RemoteMcpOAuthCredentialIdentity,
            generation: Long,
            issuedAtEpochMillis: Long,
            expiresAtEpochMillis: Long?,
        ) = StoredOAuthCredential(
            handle = identity.handle,
            pluginId = identity.pluginId,
            serverId = identity.serverId,
            configurationDigest = identity.configurationDigest,
            generation = generation,
            issuedAtEpochMillis = issuedAtEpochMillis,
            expiresAtEpochMillis = expiresAtEpochMillis,
            iv = "",
            ciphertext = "",
        )
    }
}

private object OAuthVaultCodec {
    private const val SCHEMA = "hans.remote-mcp-oauth-vault"
    private const val VERSION = 1

    fun encode(credentials: List<StoredOAuthCredential>): String {
        require(credentials.size <= 64)
        require(credentials.map { it.handle.value }.distinct().size == credentials.size)
        return JSONObject()
            .put("schema", SCHEMA)
            .put("version", VERSION)
            .put(
                "credentials",
                JSONArray(credentials.sortedBy { it.handle.value }.map(::encodeCredential)),
            )
            .toString()
    }

    fun decode(raw: String): List<StoredOAuthCredential> {
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= 512 * 1024)
        val root = JSONObject(raw)
        requireExactKeys(root, "schema", "version", "credentials")
        require(root.getString("schema") == SCHEMA && root.getInt("version") == VERSION)
        val array = root.getJSONArray("credentials")
        require(array.length() <= 64)
        val credentials = (0 until array.length()).map { decodeCredential(array.getJSONObject(it)) }
        require(credentials.map { it.handle.value }.distinct().size == credentials.size)
        require(credentials.map { it.handle.value } == credentials.map { it.handle.value }.sorted())
        credentials.forEach {
            canonicalBase64(it.iv, RemoteMcpOAuthCipherEnvelope.GCM_IV_BYTES)
            canonicalBase64(it.ciphertext, RemoteMcpOAuthCipherEnvelope.MAX_CIPHERTEXT_BYTES)
        }
        return credentials
    }

    private fun encodeCredential(entry: StoredOAuthCredential) = JSONObject()
        .put("handle", entry.handle.value)
        .put("pluginId", entry.pluginId)
        .put("serverId", entry.serverId)
        .put("configurationDigest", entry.configurationDigest)
        .put("generation", entry.generation)
        .put("issuedAtEpochMillis", entry.issuedAtEpochMillis)
        .put("expiresAtEpochMillis", entry.expiresAtEpochMillis ?: JSONObject.NULL)
        .put("iv", entry.iv)
        .put("ciphertext", entry.ciphertext)

    private fun decodeCredential(value: JSONObject): StoredOAuthCredential {
        requireExactKeys(
            value,
            "handle",
            "pluginId",
            "serverId",
            "configurationDigest",
            "generation",
            "issuedAtEpochMillis",
            "expiresAtEpochMillis",
            "iv",
            "ciphertext",
        )
        require(value.get("generation") is Number && value.get("issuedAtEpochMillis") is Number)
        if (!value.isNull("expiresAtEpochMillis")) {
            require(value.get("expiresAtEpochMillis") is Number)
        }
        return StoredOAuthCredential(
            handle = OAuthCredentialHandle(value.getString("handle")),
            pluginId = value.getString("pluginId"),
            serverId = value.getString("serverId"),
            configurationDigest = value.getString("configurationDigest"),
            generation = value.getLong("generation"),
            issuedAtEpochMillis = value.getLong("issuedAtEpochMillis"),
            expiresAtEpochMillis = if (value.isNull("expiresAtEpochMillis")) {
                null
            } else {
                value.getLong("expiresAtEpochMillis")
            },
            iv = value.getString("iv"),
            ciphertext = value.getString("ciphertext"),
        )
    }
}

private class PlainOAuthPayload(
    val accessToken: ByteArray,
    val refreshToken: ByteArray?,
) {
    fun erase() {
        accessToken.fill(0)
        refreshToken?.fill(0)
    }
}

private fun tokenBytes(token: CharArray, maxBytes: Int): ByteArray {
    require(token.size in 1..maxBytes && token.all { it.code in 0x21..0x7e }) {
        "Remote MCP OAuth token is invalid"
    }
    return ByteArray(token.size) { index -> token[index].code.toByte() }
}

private fun encodePayload(accessToken: ByteArray, refreshToken: ByteArray?): ByteArray {
    val output = ByteArrayOutputStream(accessToken.size + (refreshToken?.size ?: 0) + 16)
    DataOutputStream(output).use { data ->
        data.writeInt(PAYLOAD_VERSION)
        data.writeInt(accessToken.size)
        data.writeInt(refreshToken?.size ?: -1)
        data.write(accessToken)
        refreshToken?.let(data::write)
    }
    return output.toByteArray()
}

private fun decodePayload(plaintext: ByteArray): PlainOAuthPayload {
    require(plaintext.size in 13..(48 * 1024 + 16))
    DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
        require(input.readInt() == PAYLOAD_VERSION)
        val accessLength = input.readInt()
        val refreshLength = input.readInt()
        require(accessLength in 1..(16 * 1024))
        require(refreshLength == -1 || refreshLength in 1..(32 * 1024))
        val access = ByteArray(accessLength)
        val refresh = refreshLength.takeIf { it >= 0 }?.let(::ByteArray)
        try {
            input.readFully(access)
            refresh?.let(input::readFully)
            require(input.read() < 0)
            require(access.all { it.toInt() and 0xff in 0x21..0x7e })
            require(refresh == null || refresh.all { it.toInt() and 0xff in 0x21..0x7e })
            return PlainOAuthPayload(access, refresh)
        } catch (failure: Throwable) {
            access.fill(0)
            refresh?.fill(0)
            throw failure
        }
    }
}

private fun authenticatedMetadata(entry: StoredOAuthCredential): ByteArray {
    val output = ByteArrayOutputStream(512)
    DataOutputStream(output).use { data ->
        data.writeUTF("hans.remote-mcp-oauth-envelope.v1")
        data.writeUTF(entry.handle.value)
        data.writeUTF(entry.pluginId)
        data.writeUTF(entry.serverId)
        data.writeUTF(entry.configurationDigest)
        data.writeLong(entry.generation)
        data.writeLong(entry.issuedAtEpochMillis)
        data.writeBoolean(entry.expiresAtEpochMillis != null)
        entry.expiresAtEpochMillis?.let(data::writeLong)
    }
    return output.toByteArray()
}

private fun canonicalBase64(value: String, maxDecodedBytes: Int): ByteArray {
    require(value.isNotEmpty() && value.length <= ((maxDecodedBytes + 2) / 3) * 4 + 4)
    val decoded = Base64.getDecoder().decode(value)
    require(decoded.size in 1..maxDecodedBytes)
    require(Base64.getEncoder().encodeToString(decoded) == value)
    return decoded
}

private fun requireExpiry(expiresAtEpochMillis: Long?, issuedAtEpochMillis: Long) {
    require(expiresAtEpochMillis == null || expiresAtEpochMillis > issuedAtEpochMillis) {
        "Remote MCP OAuth expiry is invalid"
    }
}

private const val PAYLOAD_VERSION = 1
