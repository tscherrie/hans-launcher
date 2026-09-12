package ai.hans.standard.phone.consent

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned, local scopes that a user may approve beyond one exact tool call.
 *
 * These values are the authority. User-visible labels, tool arguments and app
 * supplied text are never interpreted as consent identifiers.
 */
enum class PersistentAndroidConsentScope(val wireName: String) {
    INSTALLED_APPS_READ("installed_apps_read"),
    OPEN_APP("open_app"),
    OPEN_SAFE_NAVIGATION("open_safe_navigation"),
    OPEN_SETTINGS_PAGE("open_settings_page"),
    READ_LOCATION("read_location"),
    READ_CONTACTS("read_contacts"),
    READ_CALENDAR("read_calendar"),
    READ_MEDIA("read_media"),
    READ_SENSORS("read_sensors"),
    READ_REPLYABLE_NOTIFICATIONS("read_replyable_notifications"),
    /** Separate opt-in: a destination server observes the metadata request IP and time. */
    NOTIFICATION_LINK_METADATA("notification_link_metadata"),
    OPEN_CAMERA("open_camera"),
    PREPARE_CALENDAR_EVENT("prepare_calendar_event"),
    ;

    companion object {
        fun fromWireName(value: String): PersistentAndroidConsentScope? =
            entries.firstOrNull { it.wireName == value }
    }
}

/**
 * The deliberately bounded, root-free everyday grant offered during setup.
 *
 * This inventory contains reads and user-visible hand-offs only. It must never
 * grow to include a direct mutation, message/reply, purchase, deletion,
 * credential/security surface, package installer, PermissionController, or
 * visual coordinate fallback merely because a new tool is added elsewhere.
 */
object PersistentAndroidConsentCatalog {
    val EVERYDAY_SCOPES: Set<PersistentAndroidConsentScope> = linkedSetOf(
        PersistentAndroidConsentScope.INSTALLED_APPS_READ,
        PersistentAndroidConsentScope.OPEN_APP,
        PersistentAndroidConsentScope.OPEN_SAFE_NAVIGATION,
        PersistentAndroidConsentScope.OPEN_SETTINGS_PAGE,
        PersistentAndroidConsentScope.READ_LOCATION,
        PersistentAndroidConsentScope.READ_CONTACTS,
        PersistentAndroidConsentScope.READ_CALENDAR,
        PersistentAndroidConsentScope.READ_MEDIA,
        PersistentAndroidConsentScope.READ_SENSORS,
        PersistentAndroidConsentScope.READ_REPLYABLE_NOTIFICATIONS,
        PersistentAndroidConsentScope.OPEN_CAMERA,
        PersistentAndroidConsentScope.PREPARE_CALENDAR_EVENT,
    )

    val EVERYDAY_DESCRIPTORS: Set<PersistentAndroidConsentDescriptor> =
        EVERYDAY_SCOPES.mapTo(linkedSetOf()) {
            PersistentAndroidConsentDescriptor.category(it)
        }

    init {
        require(EVERYDAY_SCOPES.size == 12)
    }
}

/**
 * Durable authority is category-only. Raw coordinate gestures deliberately
 * have no durable descriptor because a coordinate has no trusted semantic
 * consequence and can move onto Send, Delete, Buy, password or security UI.
 */
data class PersistentAndroidConsentDescriptor(
    val scope: PersistentAndroidConsentScope,
) {
    /** Stable local UI key. It is never parsed back into authority. */
    fun localDisplayKey(): String = scope.wireName

    companion object {
        fun category(scope: PersistentAndroidConsentScope): PersistentAndroidConsentDescriptor {
            return PersistentAndroidConsentDescriptor(scope)
        }
    }
}

interface PersistentAndroidConsentStore {
    /** Every read fails closed. */
    fun contains(descriptor: PersistentAndroidConsentDescriptor): Boolean

    /** Returns false when the exact durable state could not be committed and verified. */
    fun grant(descriptor: PersistentAndroidConsentDescriptor): Boolean

    /** Atomically adds every descriptor or leaves the previous state untouched. */
    fun grantAll(descriptors: Set<PersistentAndroidConsentDescriptor>): Boolean = false

    /** Returns false when revocation could not be committed and verified. */
    fun revoke(descriptor: PersistentAndroidConsentDescriptor): Boolean

    /** Returns false when clearing the durable state could not be committed and verified. */
    fun revokeAll(): Boolean

    /** Corrupt, unknown or unsupported data is projected as an empty set. */
    fun active(): Set<PersistentAndroidConsentDescriptor>

    /** True only when every current, versioned everyday descriptor is durable. */
    fun hasEverydayBundle(): Boolean =
        PersistentAndroidConsentCatalog.EVERYDAY_DESCRIPTORS.all(::contains)

    companion object {
        val NONE = object : PersistentAndroidConsentStore {
            override fun contains(descriptor: PersistentAndroidConsentDescriptor) = false
            override fun grant(descriptor: PersistentAndroidConsentDescriptor) = false
            override fun grantAll(
                descriptors: Set<PersistentAndroidConsentDescriptor>,
            ) = false
            override fun revoke(descriptor: PersistentAndroidConsentDescriptor) = false
            override fun revokeAll() = false
            override fun active(): Set<PersistentAndroidConsentDescriptor> = emptySet()
        }
    }
}

/**
 * Atomic no-backup JSON store. Unknown versions, keys, scopes or malformed
 * descriptors invalidate the complete file rather than partially granting it.
 */
class AtomicPersistentAndroidConsentStore(
    private val file: File,
) : PersistentAndroidConsentStore {
    @Synchronized
    override fun contains(descriptor: PersistentAndroidConsentDescriptor): Boolean =
        descriptor in (readValidState() ?: emptySet())

    @Synchronized
    override fun active(): Set<PersistentAndroidConsentDescriptor> =
        readValidState() ?: emptySet()

    @Synchronized
    override fun grant(descriptor: PersistentAndroidConsentDescriptor): Boolean {
        val current = readValidState() ?: return false
        val next = current.toMutableSet().apply { add(descriptor) }
        return writeAndVerify(next)
    }

    @Synchronized
    override fun grantAll(
        descriptors: Set<PersistentAndroidConsentDescriptor>,
    ): Boolean {
        if (descriptors.isEmpty() || descriptors.size > MAX_CONSENTS) return false
        val current = readValidState() ?: return false
        val next = current + descriptors
        if (next.size > MAX_CONSENTS) return false
        return writeAndVerify(next)
    }

    @Synchronized
    override fun revoke(descriptor: PersistentAndroidConsentDescriptor): Boolean {
        val current = readValidState() ?: return false
        val next = current.toMutableSet().apply { remove(descriptor) }
        return writeAndVerify(next)
    }

    @Synchronized
    override fun revokeAll(): Boolean = writeAndVerify(emptySet())

    private fun readValidState(): Set<PersistentAndroidConsentDescriptor>? = runCatching {
        val path = file.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return@runCatching emptySet()
        val parent = file.parentFile ?: error("missing consent parent")
        require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
        require(!Files.isSymbolicLink(path))
        require(file.canonicalFile.parentFile == parent.canonicalFile)
        require(file.length() in 1..MAX_FILE_BYTES)
        val root = JSONObject(file.readText(StandardCharsets.UTF_8))
        requireOnlyKeys(root, setOf("schemaVersion", "policyVersion", "consents"))
        require(root.getInt("schemaVersion") == SCHEMA_VERSION)
        require(root.getInt("policyVersion") == POLICY_VERSION)
        val values = root.getJSONArray("consents")
        require(values.length() <= MAX_CONSENTS)
        buildSet {
            repeat(values.length()) { index ->
                val item = values.getJSONObject(index)
                requireOnlyKeys(item, setOf("scope"))
                val scope = PersistentAndroidConsentScope.fromWireName(item.getString("scope"))
                    ?: error("unknown consent scope")
                add(PersistentAndroidConsentDescriptor(scope))
            }
        }.also { require(it.size == values.length()) }
    }.getOrNull()

    private fun writeAndVerify(
        values: Set<PersistentAndroidConsentDescriptor>,
    ): Boolean = runCatching {
        require(values.size <= MAX_CONSENTS)
        val parent = file.parentFile ?: error("missing consent parent")
        check(parent.mkdirs() || parent.isDirectory)
        val encoded = encode(values).toByteArray(StandardCharsets.UTF_8)
        check(encoded.size in 1..MAX_FILE_BYTES)
        val temporary = File(
            parent,
            ".${file.name}.${System.identityHashCode(Thread.currentThread())}.${System.nanoTime()}.tmp",
        )
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(encoded)
                output.fd.sync()
            }
            temporary.setReadable(false, false)
            temporary.setWritable(false, false)
            check(temporary.setReadable(true, true))
            check(temporary.setWritable(true, true))
            runCatching {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            if (temporary.exists()) temporary.delete()
        }
        readValidState() == values
    }.getOrDefault(false)

    private fun encode(values: Set<PersistentAndroidConsentDescriptor>): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("policyVersion", POLICY_VERSION)
        .put(
            "consents",
            JSONArray().also { array ->
                values.sortedBy(PersistentAndroidConsentDescriptor::localDisplayKey).forEach { value ->
                    array.put(
                        JSONObject()
                            .put("scope", value.scope.wireName),
                    )
                }
            },
        )
        .toString()

    private fun requireOnlyKeys(value: JSONObject, expected: Set<String>) {
        require(value.keys().asSequence().toSet() == expected)
    }

    companion object {
        const val FILE_NAME = "persistent-android-consents-v2.json"
        const val SCHEMA_VERSION = 2
        const val POLICY_VERSION = 2
        private const val MAX_CONSENTS = 64
        private const val MAX_FILE_BYTES = 64L * 1_024L
    }
}

/** Central fail-closed policy for turning trusted local metadata into durable authority. */
object PersistentAndroidConsentPolicy {
    fun categoryDescriptor(
        scope: PersistentAndroidConsentScope?,
    ): PersistentAndroidConsentDescriptor? = scope?.let(
        PersistentAndroidConsentDescriptor::category,
    )
}
