package ai.hans.standard.phone.keys

import android.content.Context
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import org.json.JSONObject

/**
 * Device-local persistence for the explicit MP01 vendor-action acknowledgement.
 *
 * The file lives below noBackupFilesDir, so neither cloud restore nor Android
 * device transfer can silently assert that a different phone's vendor action
 * was neutralized. Both the exact Hans mapping and the observable Minimal build
 * state must still match whenever the value is read.
 */
class Mp01VendorActionOverrideConfirmationStore(
    context: Context,
    fileName: String = FILE_NAME,
) {
    init {
        require(FILE_NAME_PATTERN.matches(fileName)) { "Invalid MP01 confirmation file name" }
    }

    private val file = File(context.applicationContext.noBackupFilesDir, fileName)
    private val atomicFile = AtomicFile(file)
    private val observerKey = file.absolutePath

    fun confirmation(): Mp01VendorActionOverrideConfirmation? = synchronized(FILE_LOCK) {
        readLocked()
    }

    /** Registers a process-local, event-driven invalidation observer. */
    fun observe(observer: () -> Unit): Closeable {
        synchronized(OBSERVER_LOCK) {
            observersByPath.getOrPut(observerKey, ::linkedSetOf).add(observer)
        }
        runCatching(observer)
        return Closeable {
            synchronized(OBSERVER_LOCK) {
                observersByPath[observerKey]?.let { observers ->
                    observers.remove(observer)
                    if (observers.isEmpty()) observersByPath.remove(observerKey)
                }
            }
        }
    }

    fun confirm(
        mappings: List<ActionKeyMapping>,
        evidence: Mp01VendorPackageEvidence,
    ) {
        val vendorStateFingerprint = checkNotNull(Mp01VendorStateFingerprint.of(evidence)) {
            "An inactive Minimal AccessibilityService cannot be acknowledged"
        }
        update(
            Mp01VendorActionOverrideConfirmation(
                mappingSetFingerprint = Mp01ActionMappingSetFingerprint.of(mappings),
                vendorStateFingerprint = vendorStateFingerprint,
            ),
        )
    }

    /** Clears a stale acknowledgement after any mapping or vendor-state change. */
    fun synchronize(
        mappings: List<ActionKeyMapping>,
        evidence: Mp01VendorPackageEvidence,
    ) {
        val vendorFingerprint = Mp01VendorStateFingerprint.of(evidence)
        val expected = if (mappings.isNotEmpty() && vendorFingerprint != null) {
            Mp01VendorActionOverrideConfirmation(
                mappingSetFingerprint = Mp01ActionMappingSetFingerprint.of(mappings),
                vendorStateFingerprint = vendorFingerprint,
            )
        } else {
            null
        }
        val current = confirmation() ?: return
        if (current != expected) update(null)
    }

    fun clear() {
        update(null)
    }

    internal fun storageFileForTest(): File = file

    private fun update(next: Mp01VendorActionOverrideConfirmation?) {
        val changed = synchronized(FILE_LOCK) {
            val current = readLocked()
            if (current == next) {
                false
            } else {
                if (next == null) {
                    atomicFile.delete()
                    check(!file.exists()) { "Could not clear MP01 vendor-action confirmation" }
                } else {
                    writeLocked(next)
                }
                true
            }
        }
        if (changed) notifyObservers()
    }

    private fun readLocked(): Mp01VendorActionOverrideConfirmation? {
        if (!file.exists()) return null
        return runCatching {
            val bytes = atomicFile.openRead().use(::readBounded)
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            require(json.keys().asSequence().toSet() == JSON_KEYS)
            require(json.getInt("version") == FORMAT_VERSION)
            val mappingSetFingerprint = json.getString("mappingSetFingerprint")
            val vendorStateFingerprint = json.getString("vendorStateFingerprint")
            require(SHA256_PATTERN.matches(mappingSetFingerprint))
            require(SHA256_PATTERN.matches(vendorStateFingerprint))
            Mp01VendorActionOverrideConfirmation(
                mappingSetFingerprint = mappingSetFingerprint,
                vendorStateFingerprint = vendorStateFingerprint,
            )
        }.getOrElse {
            atomicFile.delete()
            null
        }
    }

    private fun writeLocked(confirmation: Mp01VendorActionOverrideConfirmation) {
        val encoded = JSONObject()
            .put("version", FORMAT_VERSION)
            .put("mappingSetFingerprint", confirmation.mappingSetFingerprint)
            .put("vendorStateFingerprint", confirmation.vendorStateFingerprint)
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(encoded.size <= MAX_FILE_BYTES)
        val output = atomicFile.startWrite()
        try {
            output.write(encoded)
            atomicFile.finishWrite(output)
        } catch (failure: Throwable) {
            atomicFile.failWrite(output)
            throw IllegalStateException(
                "Could not persist the MP01 vendor-action confirmation",
                failure,
            )
        }
    }

    private fun readBounded(input: java.io.InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(256)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= MAX_FILE_BYTES) {
                "MP01 confirmation file exceeds its byte limit"
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun notifyObservers() {
        val observers = synchronized(OBSERVER_LOCK) {
            observersByPath[observerKey].orEmpty().toList()
        }
        observers.forEach { observer -> runCatching(observer) }
    }

    private companion object {
        const val FILE_NAME = "hans_mp01_vendor_action_v3.json"
        const val FORMAT_VERSION = 3
        const val MAX_FILE_BYTES = 1_024
        val FILE_NAME_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,95}")
        val SHA256_PATTERN = Regex("[a-f0-9]{64}")
        val JSON_KEYS = setOf("version", "mappingSetFingerprint", "vendorStateFingerprint")
        val FILE_LOCK = Any()
        val OBSERVER_LOCK = Any()
        val observersByPath = mutableMapOf<String, MutableSet<() -> Unit>>()
    }
}
