package ai.hans.standard.backup

import ai.hans.standard.BuildConfig
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Only a fixed, fresh, host-written debug descriptor can arm the backup startup fixture. */
internal data class BackupRecoveryFixtureDescriptor(
    val fixtureId: String,
    val scenario: String,
    val appApkSha256: String,
)

internal object BackupRecoveryFixtureProtocol {
    const val SCHEMA = "hans.backup-recovery.fixture.v1"
    const val DESCRIPTOR_FILE_NAME = "hans-backup-recovery-fixture-v1.json"
    const val JOURNAL_FILE_NAME = "hans-backup-import-journal-v1.json"
    const val MAX_DESCRIPTOR_BYTES = 4_096
    val scenarios = setOf("safe", "malformed", "orphan-new", "legacy-unsettled")
    private val keys = setOf("schema", "fixtureId", "scenario", "appApkSha256")

    fun readDescriptor(context: Context): BackupRecoveryFixtureDescriptor? {
        val root = context.noBackupFilesDir.toPath()
        check(Files.readAttributes(root, BasicFileAttributes::class.java, NOFOLLOW_LINKS).isDirectory)
        val file = root.resolve(DESCRIPTOR_FILE_NAME)
        val attributes = try {
            Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return null
        }
        check(attributes.isRegularFile && attributes.size() in 1..MAX_DESCRIPTOR_BYTES.toLong()) {
            "backup_fixture_descriptor_type"
        }
        val bytes = FileChannel.open(file, READ, NOFOLLOW_LINKS).use { channel ->
            check(channel.size() in 1..MAX_DESCRIPTOR_BYTES.toLong())
            val buffer = ByteBuffer.allocate(MAX_DESCRIPTOR_BYTES + 1)
            while (channel.read(buffer) > 0) {
                check(buffer.position() <= MAX_DESCRIPTOR_BYTES) { "backup_fixture_descriptor_size" }
            }
            buffer.array().copyOf(buffer.position())
        }
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString().trim()
        check(text.startsWith('{') && text.endsWith('}')) { "backup_fixture_descriptor_json" }
        // The four allowed values need no JSON escapes. Reject duplicate fields, trailing data,
        // nested values and escapes instead of accepting JSONObject's last-duplicate-wins rule.
        val values = linkedMapOf<String, String>()
        val body = text.substring(1, text.lastIndex)
        val member = Regex("\\s*\"([A-Za-z][A-Za-z0-9]*)\"\\s*:\\s*\"([A-Za-z0-9._-]+)\"\\s*")
        var cursor = 0
        while (cursor < body.length) {
            val match = checkNotNull(member.find(body, cursor)) { "backup_fixture_descriptor_json" }
            check(match.range.first == cursor) { "backup_fixture_descriptor_json" }
            check(values.put(match.groupValues[1], match.groupValues[2]) == null) {
                "backup_fixture_descriptor_duplicate"
            }
            cursor = match.range.last + 1
            if (cursor == body.length) break
            check(body[cursor] == ',' && cursor + 1 < body.length) { "backup_fixture_descriptor_json" }
            cursor += 1
        }
        check(values.keys == keys && values["schema"] == SCHEMA) { "backup_fixture_descriptor_fields" }
        val fixtureId = values.getValue("fixtureId")
        check(UUID.fromString(fixtureId).toString() == fixtureId) { "backup_fixture_descriptor_uuid" }
        val scenario = values.getValue("scenario")
        check(scenario in scenarios) { "backup_fixture_descriptor_scenario" }
        val hash = values.getValue("appApkSha256")
        check(hash.matches(Regex("[0-9a-f]{64}"))) { "backup_fixture_descriptor_apk" }
        return BackupRecoveryFixtureDescriptor(fixtureId, scenario, hash)
    }

    fun requireBoundDebugEmulator(context: Context, descriptor: BackupRecoveryFixtureDescriptor) {
        check(BuildConfig.DEBUG && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(context.packageName == "ai.hans.standard" && Build.VERSION.SDK_INT in 31..36)
        val identity = listOf(Build.PRODUCT, Build.MODEL, Build.FINGERPRINT)
            .joinToString(" ").lowercase(Locale.ROOT)
        check(Build.HARDWARE.lowercase(Locale.ROOT) in setOf("ranchu", "goldfish") && "sdk" in identity) {
            "backup_fixture_requires_disposable_emulator"
        }
        check(context.applicationInfo.splitSourceDirs.isNullOrEmpty()) { "backup_fixture_base_apk_required" }
        check(sha256(File(context.applicationInfo.sourceDir)) == descriptor.appApkSha256) {
            "backup_fixture_apk_mismatch"
        }
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { source ->
            val buffer = ByteArray(128 * 1_024)
            while (true) {
                val count = source.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
