package ai.hans.standard.work.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

internal data class RemoteWorkerConfigurationSnapshot(
    val revision: Long,
    val configuration: RemoteWorkerConfiguration?,
) {
    init {
        require(revision >= 0L)
        require(revision != 0L || configuration == null) {
            "Revision zero is reserved for an absent remote worker configuration"
        }
    }

    val digest: String? get() = configuration?.identity?.configurationDigest
}

/**
 * Safe, passive UI projection. It deliberately omits endpoint, certificate pin and key alias.
 * Reading it never performs network I/O and corrupt state is projected as disabled.
 */
internal data class RemoteWorkerPublicConfigurationState(
    val revision: Long,
    val configured: Boolean,
    val enabled: Boolean,
    val workerId: String?,
    val approvedAdapters: List<String>,
    val configurationDigest: String?,
    val storageHealthy: Boolean,
)

/**
 * Local-only edit projection for the explicit Settings form.
 *
 * The endpoint and certificate pin are connection metadata, not authentication credentials, but
 * they still never enter logs, tool output or [toString]. The Android-Keystore alias remains
 * private to the runtime and is deliberately absent.
 */
internal data class RemoteWorkerEditableConfigurationState(
    val revision: Long,
    val configured: Boolean,
    val enabled: Boolean,
    val workerId: String?,
    val endpoint: String?,
    val serverSpkiSha256: String?,
    val approvedAdapters: List<RemoteWorkAdapterApproval>,
    val storageHealthy: Boolean,
) {
    override fun toString(): String =
        "RemoteWorkerEditableConfigurationState(" +
            "revision=$revision, configured=$configured, enabled=$enabled, " +
            "adapterCount=${approvedAdapters.size}, storageHealthy=$storageHealthy, " +
            "connectionMetadata=<redacted>)"
}

internal enum class RemoteWorkerConfigurationMutationResult {
    APPLIED,
    REVISION_MISMATCH,
    CORRUPT,
    PERSISTENCE_FAILED,
}

/** Narrow deterministic seam for exercising otherwise device/filesystem-specific write failures. */
internal enum class RemoteWorkerPersistenceCheckpoint {
    BEFORE_ATOMIC_REPLACE,
    AFTER_ATOMIC_REPLACE,
    BEFORE_POST_WRITE_VERIFICATION,
}

internal fun interface RemoteWorkerPersistenceFaultInjector {
    fun check(checkpoint: RemoteWorkerPersistenceCheckpoint)
}

internal class RemoteWorkerConfigurationCorruptException(
    cause: Throwable,
) : IllegalStateException("Remote worker configuration is corrupt", cause)

internal interface RemoteWorkerConfigurationCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * Encrypted, no-backup, crash-safe remote-worker configuration.
 *
 * Absence is revision zero and means disabled. Every mutation is an exact revision CAS. A corrupt
 * document can neither enable remote work nor be silently overwritten by a stale settings screen.
 */
internal class AppPrivateRemoteWorkerConfigurationStore private constructor(
    private val directory: File,
    private val cipher: RemoteWorkerConfigurationCipher,
    private val fileName: String,
    private val faultInjector: RemoteWorkerPersistenceFaultInjector,
) {
    constructor(
        context: Context,
        cipher: RemoteWorkerConfigurationCipher = AndroidKeystoreRemoteWorkerConfigurationCipher(),
        fileName: String = FILE_NAME,
    ) : this(
        context.applicationContext.noBackupFilesDir,
        cipher,
        fileName,
        RemoteWorkerPersistenceFaultInjector {},
    )

    /** Test constructor. Production always uses [Context.noBackupFilesDir]. */
    internal constructor(
        directory: File,
        cipher: RemoteWorkerConfigurationCipher,
        faultInjector: RemoteWorkerPersistenceFaultInjector =
            RemoteWorkerPersistenceFaultInjector {},
    ) : this(directory, cipher, FILE_NAME, faultInjector)

    private val target = File(directory, fileName)
    private val staging = File(directory, "$fileName.tmp")
    private val rollback = File(directory, "$fileName.rollback")
    private val transaction = File(directory, "$fileName.transaction")

    init {
        require(fileName == File(fileName).name && FILE_NAME_PATTERN.matches(fileName))
    }

    fun readExact(): RemoteWorkerConfigurationSnapshot = synchronized(PROCESS_LOCK) {
        readLocked()
    }

    fun passiveState(): RemoteWorkerPublicConfigurationState = synchronized(PROCESS_LOCK) {
        try {
            readLocked().toPublicState(storageHealthy = true)
        } catch (_: RemoteWorkerConfigurationCorruptException) {
            RemoteWorkerConfigurationSnapshot(0L, null).toPublicState(storageHealthy = false)
        }
    }

    /** App-private storage read only; it cannot contact, authenticate or activate a worker. */
    fun passiveEditableState(): RemoteWorkerEditableConfigurationState = synchronized(PROCESS_LOCK) {
        try {
            readLocked().toEditableState(storageHealthy = true)
        } catch (_: RemoteWorkerConfigurationCorruptException) {
            RemoteWorkerConfigurationSnapshot(0L, null).toEditableState(storageHealthy = false)
        }
    }

    fun compareAndSet(
        expectedRevision: Long,
        replacement: RemoteWorkerConfiguration,
    ): RemoteWorkerConfigurationMutationResult = synchronized(PROCESS_LOCK) {
        require(expectedRevision >= 0L)
        val current = try {
            readLocked()
        } catch (_: RemoteWorkerConfigurationCorruptException) {
            return@synchronized RemoteWorkerConfigurationMutationResult.CORRUPT
        }
        if (current.revision != expectedRevision) {
            return@synchronized RemoteWorkerConfigurationMutationResult.REVISION_MISMATCH
        }
        val next = try {
            RemoteWorkerConfigurationSnapshot(
                revision = Math.addExact(expectedRevision, 1L),
                configuration = replacement,
            )
        } catch (_: Throwable) {
            return@synchronized RemoteWorkerConfigurationMutationResult.PERSISTENCE_FAILED
        }
        try {
            writeLocked(current, next)
            RemoteWorkerConfigurationMutationResult.APPLIED
        } catch (_: Throwable) {
            val previousIsExact = runCatching { readLocked() == current }.getOrDefault(false)
            if (previousIsExact) {
                RemoteWorkerConfigurationMutationResult.PERSISTENCE_FAILED
            } else {
                RemoteWorkerConfigurationMutationResult.CORRUPT
            }
        }
    }

    private fun readLocked(): RemoteWorkerConfigurationSnapshot {
        try {
            requireStorageDirectory()
            recoverInterruptedWriteLocked()
        } catch (failure: RemoteWorkerConfigurationCorruptException) {
            throw failure
        } catch (failure: Throwable) {
            throw RemoteWorkerConfigurationCorruptException(failure)
        }
        return readFileLocked(target, absentAllowed = true)
    }

    private fun readFileLocked(
        file: File,
        absentAllowed: Boolean,
    ): RemoteWorkerConfigurationSnapshot {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            require(absentAllowed) { "Remote worker configuration file is absent" }
            return RemoteWorkerConfigurationSnapshot(0L, null)
        }
        return try {
            requirePrivateRegularFile(file, "configuration")
            val size = Files.size(path)
            require(size in 1..MAX_ENCRYPTED_BYTES.toLong()) {
                "Invalid remote worker configuration size"
            }
            val encrypted = FileChannel.open(
                path,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val buffer = ByteBuffer.allocate(size.toInt())
                while (buffer.hasRemaining()) {
                    check(channel.read(buffer) >= 0) { "Truncated remote worker configuration" }
                }
                check(channel.read(ByteBuffer.allocate(1)) < 0) {
                    "Remote worker configuration changed while reading"
                }
                buffer.array()
            }
            val plaintext = cipher.decrypt(encrypted)
            require(plaintext.size in 1..MAX_PLAINTEXT_BYTES)
            RemoteWorkerConfigurationCodec.decode(plaintext.toString(StandardCharsets.UTF_8))
        } catch (failure: RemoteWorkerConfigurationCorruptException) {
            throw failure
        } catch (failure: Throwable) {
            throw RemoteWorkerConfigurationCorruptException(failure)
        }
    }

    /**
     * Commits [next] with [previous] retained as an atomic rollback image until the exact
     * post-write read succeeds. The transaction marker is the commit boundary: after a process
     * crash, its presence always restores [previous], while its absence keeps the verified target.
     */
    private fun writeLocked(
        previous: RemoteWorkerConfigurationSnapshot,
        next: RemoteWorkerConfigurationSnapshot,
    ) {
        requireStorageDirectory()
        recoverInterruptedWriteLocked()
        val plaintext = RemoteWorkerConfigurationCodec.encode(next)
            .toByteArray(StandardCharsets.UTF_8)
        require(plaintext.size in 1..MAX_PLAINTEXT_BYTES)
        val encrypted = cipher.encrypt(plaintext)
        require(encrypted.size in 1..MAX_ENCRYPTED_BYTES)
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            requirePrivateRegularFile(target, "configuration")
        }
        prepareTransientPaths()
        try {
            Files.createFile(staging.toPath())
            requirePrivateRegularFile(staging, "configuration staging file")
            FileChannel.open(
                staging.toPath(),
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS,
            ).use { channel ->
                val buffer = ByteBuffer.wrap(encrypted)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            check(staging.length() == encrypted.size.toLong()) {
                "Remote worker configuration staging write was incomplete"
            }
            check(readFileLocked(staging, absentAllowed = false) == next) {
                "Remote worker configuration staging verification failed"
            }
            if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                Files.copy(
                    target.toPath(),
                    rollback.toPath(),
                )
                requirePrivateRegularFile(rollback, "rollback file")
                forceFileLocked(rollback)
                check(readFileLocked(rollback, absentAllowed = false) == previous) {
                    "Remote worker configuration rollback verification failed"
                }
            }
            // The previous target (if any) is durably copied before the transaction marker is
            // created. Therefore marker + rollback always means restore, while marker without a
            // rollback unambiguously means the previous state was revision zero (absent).
            Files.createFile(transaction.toPath())
            requirePrivateRegularFile(transaction, "transaction marker")
            forceFileLocked(transaction)
            faultInjector.check(RemoteWorkerPersistenceCheckpoint.BEFORE_ATOMIC_REPLACE)
            Files.move(
                staging.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
            faultInjector.check(RemoteWorkerPersistenceCheckpoint.AFTER_ATOMIC_REPLACE)
            requirePrivateRegularFile(target, "configuration")
            faultInjector.check(RemoteWorkerPersistenceCheckpoint.BEFORE_POST_WRITE_VERIFICATION)
            check(readFileLocked(target, absentAllowed = false) == next) {
                "Remote worker configuration verification failed"
            }

            // Deleting the marker commits the already verified target. A stale rollback image is
            // harmless after this point and is removed here or by the next passive read.
            Files.delete(transaction.toPath())
            Files.deleteIfExists(rollback.toPath())
        } catch (failure: Throwable) {
            try {
                restorePreviousLocked(previous)
            } catch (restorationFailure: Throwable) {
                failure.addSuppressed(restorationFailure)
            }
            throw failure
        } finally {
            deletePrivateTransientFileIfPresent(staging, "configuration staging file")
        }
    }

    private fun prepareTransientPaths() {
        listOf(staging, rollback, transaction).forEach { file ->
            val path = file.toPath()
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@forEach
            requirePrivateRegularFile(file, "transient configuration file")
            Files.delete(path)
        }
    }

    /** Restores an interrupted or rejected commit without allocating another configuration file. */
    private fun restorePreviousLocked(previous: RemoteWorkerConfigurationSnapshot) {
        if (Files.exists(rollback.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            requirePrivateRegularFile(rollback, "rollback file")
            if (runCatching { readFileLocked(rollback, absentAllowed = false) == previous }
                    .getOrDefault(false)
            ) {
                deletePrivateTransientFileIfPresent(target, "uncommitted configuration")
                Files.move(
                    rollback.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } else {
                // A failed pre-transaction copy cannot replace the still-valid old target.
                deletePrivateTransientFileIfPresent(rollback, "invalid rollback file")
            }
        } else if (previous.configuration == null) {
            deletePrivateTransientFileIfPresent(target, "uncommitted configuration")
        }
        deletePrivateTransientFileIfPresent(staging, "configuration staging file")
        deletePrivateTransientFileIfPresent(transaction, "transaction marker")
        check(readFileLocked(target, absentAllowed = true) == previous) {
            "Previous remote worker configuration could not be restored"
        }
    }

    private fun recoverInterruptedWriteLocked() {
        val transactionPresent = Files.exists(transaction.toPath(), LinkOption.NOFOLLOW_LINKS)
        if (transactionPresent) {
            requirePrivateRegularFile(transaction, "transaction marker")
            if (Files.exists(rollback.toPath(), LinkOption.NOFOLLOW_LINKS)) {
                requirePrivateRegularFile(rollback, "rollback file")
                deletePrivateTransientFileIfPresent(target, "uncommitted configuration")
                Files.move(
                    rollback.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } else {
                // A marked transaction without a rollback began from the absent revision zero.
                deletePrivateTransientFileIfPresent(target, "uncommitted configuration")
            }
            deletePrivateTransientFileIfPresent(transaction, "transaction marker")
        } else {
            // The marker is deleted only after verification; a surviving rollback is stale.
            deletePrivateTransientFileIfPresent(rollback, "stale rollback file")
        }
        deletePrivateTransientFileIfPresent(staging, "stale staging file")
    }

    private fun deletePrivateTransientFileIfPresent(file: File, label: String) {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return
        requirePrivateRegularFile(file, label)
        Files.delete(path)
    }

    private fun forceFileLocked(file: File) {
        FileChannel.open(
            file.toPath(),
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS,
        ).use { channel -> channel.force(true) }
    }

    private fun requireStorageDirectory() {
        val path = directory.toPath()
        require(Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            "Remote worker configuration directory is absent"
        }
        require(!Files.isSymbolicLink(path) && Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            "Remote worker configuration directory is not private"
        }
        require(
            target.parentFile == directory &&
                staging.parentFile == directory &&
                rollback.parentFile == directory &&
                transaction.parentFile == directory
        ) {
            "Remote worker configuration escaped its storage directory"
        }
    }

    private fun requirePrivateRegularFile(file: File, label: String) {
        val path = file.toPath()
        require(!Files.isSymbolicLink(path) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            "Remote worker $label is not a regular file"
        }
    }

    private fun RemoteWorkerConfigurationSnapshot.toPublicState(
        storageHealthy: Boolean,
    ): RemoteWorkerPublicConfigurationState {
        val value = configuration
        return RemoteWorkerPublicConfigurationState(
            revision = revision,
            configured = value != null,
            enabled = storageHealthy && value?.enabled == true,
            workerId = value?.identity?.workerId?.value,
            approvedAdapters = value?.approvedAdapters.orEmpty().map { it.id.value }.sorted(),
            configurationDigest = value?.identity?.configurationDigest,
            storageHealthy = storageHealthy,
        )
    }

    private fun RemoteWorkerConfigurationSnapshot.toEditableState(
        storageHealthy: Boolean,
    ): RemoteWorkerEditableConfigurationState {
        val value = configuration
        return RemoteWorkerEditableConfigurationState(
            revision = revision,
            configured = value != null,
            enabled = storageHealthy && value?.enabled == true,
            workerId = value?.identity?.workerId?.value,
            endpoint = value?.identity?.endpoint,
            serverSpkiSha256 = value?.identity?.serverSpkiSha256,
            approvedAdapters = value?.approvedAdapters.orEmpty()
                .sortedBy { it.id.value },
            storageHealthy = storageHealthy,
        )
    }

    private companion object {
        const val FILE_NAME = "remote-worker-configuration-v1.enc"
        const val MAX_PLAINTEXT_BYTES = 128 * 1024
        const val MAX_ENCRYPTED_BYTES = 160 * 1024
        val FILE_NAME_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        val PROCESS_LOCK = Any()
    }
}

/** Strict schema: any unknown, missing or mistyped field invalidates the complete configuration. */
internal object RemoteWorkerConfigurationCodec {
    private const val SCHEMA = "hans.remote-worker-configuration"
    private const val VERSION = 1

    fun encode(snapshot: RemoteWorkerConfigurationSnapshot): String {
        require(snapshot.revision > 0L && snapshot.configuration != null)
        val configuration = snapshot.configuration
        return JSONObject()
            .put("schema", SCHEMA)
            .put("version", VERSION)
            .put("revision", snapshot.revision)
            .put(
                "configuration",
                JSONObject()
                    .put("workerId", configuration.identity.workerId.value)
                    .put("endpoint", configuration.identity.endpoint)
                    .put("serverSpkiSha256", configuration.identity.serverSpkiSha256)
                    .put("deviceKeyAlias", configuration.identity.deviceKeyAlias)
                    .put("enabled", configuration.enabled)
                    .put(
                        "approvedAdapters",
                        JSONArray().also { array ->
                            configuration.approvedAdapters
                                .sortedBy { it.id.value }
                                .forEach { approval ->
                                    array.put(
                                        JSONObject()
                                            .put("id", approval.id.value)
                                            .put("version", approval.version),
                                    )
                                }
                        },
                    ),
            )
            .toString()
    }

    fun decode(encoded: String): RemoteWorkerConfigurationSnapshot {
        val root = JSONObject(encoded)
        root.requireOnlyKeys(setOf("schema", "version", "revision", "configuration"))
        require(root.getString("schema") == SCHEMA)
        require(root.getInt("version") == VERSION)
        val revision = root.getLong("revision").also { require(it > 0L) }
        val raw = root.getJSONObject("configuration")
        raw.requireOnlyKeys(
            setOf(
                "workerId",
                "endpoint",
                "serverSpkiSha256",
                "deviceKeyAlias",
                "enabled",
                "approvedAdapters",
            ),
        )
        val adapters = raw.getJSONArray("approvedAdapters")
        require(adapters.length() <= 64)
        val approvals = buildList {
            repeat(adapters.length()) { index ->
                val item = adapters.getJSONObject(index)
                item.requireOnlyKeys(setOf("id", "version"))
                add(
                    RemoteWorkAdapterApproval(
                        RemoteWorkAdapterId(item.getString("id")),
                        item.getString("version"),
                    ),
                )
            }
        }
        return RemoteWorkerConfigurationSnapshot(
            revision = revision,
            configuration = RemoteWorkerConfiguration(
                identity = RemoteWorkerConnectionIdentity(
                    workerId = RemoteWorkerId(raw.getString("workerId")),
                    endpoint = raw.getString("endpoint"),
                    serverSpkiSha256 = raw.getString("serverSpkiSha256"),
                    deviceKeyAlias = raw.getString("deviceKeyAlias"),
                ),
                approvedAdapters = approvals,
                enabled = raw.getBoolean("enabled"),
            ),
        )
    }

    private fun JSONObject.requireOnlyKeys(expected: Set<String>) {
        require(keys().asSequence().toSet() == expected)
    }
}

/** AES-GCM key is non-exportable and generated lazily inside Android Keystore. */
internal class AndroidKeystoreRemoteWorkerConfigurationCipher(
    private val keyAlias: String = KEY_ALIAS,
    private val random: SecureRandom = SecureRandom(),
) : RemoteWorkerConfigurationCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        require(plaintext.size in 1..MAX_PLAINTEXT_BYTES)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(AAD)
        }
        val encrypted = cipher.doFinal(plaintext)
        return ByteBuffer.allocate(MAGIC.size + nonce.size + encrypted.size)
            .put(MAGIC)
            .put(nonce)
            .put(encrypted)
            .array()
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size in MIN_ENCRYPTED_BYTES..MAX_ENCRYPTED_BYTES)
        val buffer = ByteBuffer.wrap(ciphertext)
        val magic = ByteArray(MAGIC.size).also(buffer::get)
        require(magic.contentEquals(MAGIC))
        val nonce = ByteArray(NONCE_BYTES).also(buffer::get)
        val encrypted = ByteArray(buffer.remaining()).also(buffer::get)
        return Cipher.getInstance(TRANSFORMATION).run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(AAD)
            doFinal(encrypted)
        }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "hans.remote-worker-configuration.v1"
        const val KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        const val MAX_PLAINTEXT_BYTES = 128 * 1024
        const val MAX_ENCRYPTED_BYTES = 160 * 1024
        const val MIN_ENCRYPTED_BYTES = 4 + NONCE_BYTES + TAG_BITS / 8
        val MAGIC = byteArrayOf('H'.code.toByte(), 'R'.code.toByte(), 'W'.code.toByte(), 1)
        val AAD = "hans.remote-worker-configuration.v1".toByteArray(StandardCharsets.US_ASCII)
    }
}
