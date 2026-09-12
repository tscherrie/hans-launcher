package ai.hans.standard.automations

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.BasicFileAttributes
import java.util.Collections
import java.util.UUID

/**
 * Debug-fixture data only. The caller must verify DEBUG, FLAG_DEBUGGABLE, public emulator
 * identity and the actual base-APK hash before bootstrap. This codec cannot authorize a run.
 * No descriptor-supplied paths or production storage names are accepted.
 */
data class AutomationProcessRecoveryDescriptor(
    val fixtureId: String,
    val scenario: String,
    val appApkSha256: String,
    val initialScheduleNonce: String,
    val schema: String = AutomationProcessRecoveryProtocol.SCHEMA,
    val executionJobId: Int = AutomationProcessRecoveryProtocol.EXECUTION_JOB_ID,
    val wakeupJobId: Int = AutomationProcessRecoveryProtocol.WAKEUP_JOB_ID,
) {
    init {
        fixtureCheck(schema == AutomationProcessRecoveryProtocol.SCHEMA, "descriptor_schema")
        fixtureCheck(canonicalUuid(fixtureId), "descriptor_fixture_id")
        fixtureCheck(canonicalUuid(initialScheduleNonce), "descriptor_nonce")
        fixtureCheck(
            scenario in setOf(
                "process-pre-fence", "process-post-fence",
                "reboot-pre-fence", "reboot-post-fence",
            ),
            "descriptor_scenario",
        )
        fixtureCheck(appApkSha256.matches(Regex("[0-9a-f]{64}")), "descriptor_apk_hash")
        fixtureCheck(executionJobId == AutomationProcessRecoveryProtocol.EXECUTION_JOB_ID, "descriptor_execution_id")
        fixtureCheck(wakeupJobId == AutomationProcessRecoveryProtocol.WAKEUP_JOB_ID, "descriptor_wakeup_id")
    }

    val databaseName: String get() = "automation-process-" + fixtureId + ".db"
    val effectsDatabaseName: String get() = "automation-process-" + fixtureId + "-effects.db"
    val workPreferencesName: String get() = "automation-process-" + fixtureId + "-work"
    val checkpointDirectoryName: String get() = "automation-process-" + fixtureId
    val bootExecutionJobId: Int get() = executionJobId + 0x10
}

class AutomationProcessRecoveryEvent internal constructor(
    val fixtureId: String,
    val event: String,
    val sequence: Long,
    val pid: Int,
    val uid: Int,
    val bootId: String,
    val timestamp: Long,
    fields: Map<String, Any>,
) {
    val schema: String = AutomationProcessRecoveryProtocol.EVENT_SCHEMA
    val fields: Map<String, Any> = immutableFixtureFields(fields)

    init {
        fixtureCheck(canonicalUuid(fixtureId), "event_fixture_id")
        fixtureCheck(event.matches(Regex("[a-z][a-z0-9_]{0,79}")), "event_name")
        fixtureCheck(sequence in 1..AutomationProcessRecoveryProtocol.MAX_EVENTS.toLong(), "event_sequence")
        fixtureCheck(pid > 0 && uid >= 0, "event_process_identity")
        fixtureCheck(bootId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")), "event_boot_identity")
        fixtureCheck(timestamp >= 0, "event_timestamp")
    }
}

class AutomationProcessRecoveryProtocolException(
    val reason: String,
) : IOException(reason)

object AutomationProcessRecoveryProtocol {
    const val SCHEMA = "hans.automation-process-fixture.v1"
    const val EVENT_SCHEMA = "hans.automation-process-event.v1"
    const val DESCRIPTOR_FILE_NAME = "hans-automation-process-fixture-v1.json"
    const val EXECUTION_JOB_ID = 0x4854F101
    const val WAKEUP_JOB_ID = 0x4854F102
    const val MAX_DESCRIPTOR_BYTES = 8_192
    const val MAX_EVENT_BYTES = 4_096
    const val MAX_JOURNAL_BYTES = 262_144
    const val MAX_EVENTS = 512
    const val MAX_FIELDS = 48
    const val MAX_FIELD_STRING_CHARS = 2_048
    const val OWNER_FILE_NAME = "owner.json"
    const val JOURNAL_FILE_NAME = "events.jsonl"
    const val CHECKPOINT_FILE_NAME = "last.json"
    internal const val CHECKPOINT_PENDING_FILE_NAME = ".last.json.pending"

    private val descriptorKeys = setOf(
        "schema", "fixtureId", "scenario", "appApkSha256",
        "executionJobId", "wakeupJobId", "initialScheduleNonce",
    )
    private val eventKeys = setOf(
        "schema", "fixtureId", "event", "sequence", "pid", "uid", "bootId", "timestamp", "fields",
    )

    /** A missing descriptor is the only unarmed result. Corruption and I/O errors propagate. */
    fun readDescriptor(noBackupDir: File): AutomationProcessRecoveryDescriptor? {
        val path = fixtureRoot(noBackupDir).resolve(DESCRIPTOR_FILE_NAME)
        if (fixtureAttributes(path) == null) return null
        return decodeDescriptor(readFixtureFile(path, MAX_DESCRIPTOR_BYTES))
    }

    fun encodeDescriptor(descriptor: AutomationProcessRecoveryDescriptor): ByteArray =
        boundedJson(
            linkedMapOf(
                "schema" to descriptor.schema,
                "fixtureId" to descriptor.fixtureId,
                "scenario" to descriptor.scenario,
                "appApkSha256" to descriptor.appApkSha256,
                "executionJobId" to descriptor.executionJobId.toLong(),
                "wakeupJobId" to descriptor.wakeupJobId.toLong(),
                "initialScheduleNonce" to descriptor.initialScheduleNonce,
            ),
            MAX_DESCRIPTOR_BYTES,
        )

    fun decodeDescriptor(bytes: ByteArray): AutomationProcessRecoveryDescriptor {
        val value = FixtureJson.decode(bytes, MAX_DESCRIPTOR_BYTES)
        fixtureCheck(value.keys == descriptorKeys, "descriptor_fields")
        return AutomationProcessRecoveryDescriptor(
            fixtureId = value.fixtureString("fixtureId"),
            scenario = value.fixtureString("scenario"),
            appApkSha256 = value.fixtureString("appApkSha256"),
            initialScheduleNonce = value.fixtureString("initialScheduleNonce"),
            schema = value.fixtureString("schema"),
            executionJobId = value.fixtureInt("executionJobId"),
            wakeupJobId = value.fixtureInt("wakeupJobId"),
        )
    }

    fun encodeEvent(event: AutomationProcessRecoveryEvent): ByteArray = boundedJson(
        linkedMapOf(
            "schema" to event.schema,
            "fixtureId" to event.fixtureId,
            "event" to event.event,
            "sequence" to event.sequence,
            "pid" to event.pid.toLong(),
            "uid" to event.uid.toLong(),
            "bootId" to event.bootId,
            "timestamp" to event.timestamp,
            "fields" to event.fields,
        ),
        MAX_EVENT_BYTES,
    )

    fun decodeEvent(bytes: ByteArray): AutomationProcessRecoveryEvent {
        val value = FixtureJson.decode(bytes, MAX_EVENT_BYTES)
        fixtureCheck(value.keys == eventKeys, "event_fields")
        fixtureCheck(value.fixtureString("schema") == EVENT_SCHEMA, "event_schema")
        val rawFields = value["fields"]
        fixtureCheck(rawFields is Map<*, *>, "event_fields_type")
        @Suppress("UNCHECKED_CAST")
        return AutomationProcessRecoveryEvent(
            fixtureId = value.fixtureString("fixtureId"),
            event = value.fixtureString("event"),
            sequence = value.fixtureLong("sequence"),
            pid = value.fixtureInt("pid"),
            uid = value.fixtureInt("uid"),
            bootId = value.fixtureString("bootId"),
            timestamp = value.fixtureLong("timestamp"),
            fields = rawFields as Map<String, Any>,
        )
    }

    private fun boundedJson(value: Map<String, Any>, limit: Int): ByteArray {
        val bytes = FixtureJson.encode(value).toByteArray(Charsets.UTF_8)
        fixtureCheck(bytes.size <= limit, "json_byte_limit")
        return bytes
    }
}

/**
 * One bounded, append-only journal. Each complete event is forced before its atomic last.json
 * projection. An exact older checkpoint can be reconstructed from a validated complete journal;
 * a torn tail, foreign checkpoint or unexpected file is never truncated or silently ignored.
 *
 * Operations serialize both within the app process and through an OS file lock. No open handle
 * survives an operation, so a killed process cannot retain a journal lock. No cleanup of fixture
 * files or production state is performed by this class.
 */
class AutomationProcessRecoveryJournal private constructor(
    private val root: Path,
    private val descriptor: AutomationProcessRecoveryDescriptor,
) {
    val directory: File get() = root.resolve(descriptor.checkpointDirectoryName).toFile()
    private val directoryPath: Path get() = directory.toPath()
    private val journalPath: Path get() = directoryPath.resolve(AutomationProcessRecoveryProtocol.JOURNAL_FILE_NAME)
    private val checkpointPath: Path get() = directoryPath.resolve(AutomationProcessRecoveryProtocol.CHECKPOINT_FILE_NAME)
    private val pendingPath: Path get() = directoryPath.resolve(AutomationProcessRecoveryProtocol.CHECKPOINT_PENDING_FILE_NAME)

    fun events(): List<AutomationProcessRecoveryEvent> = withJournal { channel ->
        readAndReconcile(channel)
    }

    fun lastCheckpoint(): AutomationProcessRecoveryEvent? = events().lastOrNull()

    fun append(
        event: String,
        pid: Int,
        uid: Int,
        bootId: String,
        timestamp: Long,
        fields: Map<String, Any> = emptyMap(),
    ): AutomationProcessRecoveryEvent = withJournal { channel ->
        val previous = readAndReconcile(channel)
        fixtureCheck(previous.size < AutomationProcessRecoveryProtocol.MAX_EVENTS, "journal_event_limit")
        val next = AutomationProcessRecoveryEvent(
            descriptor.fixtureId, event, previous.size.toLong() + 1, pid, uid, bootId, timestamp, fields,
        )
        val bytes = AutomationProcessRecoveryProtocol.encodeEvent(next)
        fixtureCheck(
            channel.size() + bytes.size + 1 <= AutomationProcessRecoveryProtocol.MAX_JOURNAL_BYTES,
            "journal_byte_limit",
        )
        channel.position(channel.size())
        writeFully(channel, bytes + byteArrayOf('\n'.code.toByte()))
        channel.force(true)
        writeCheckpoint(bytes)
        next
    }

    private fun <T> withJournal(action: (FileChannel) -> T): T = synchronized(processLock) {
        validateDirectory()
        requireFixtureRegularFile(journalPath)
        FileChannel.open(journalPath, READ, WRITE, NOFOLLOW_LINKS).use { channel ->
            channel.lock().use {
                validateOwnership()
                action(channel)
            }
        }
    }

    private fun validateDirectory() {
        fixtureCheck(fixtureAttributes(root)?.isDirectory == true, "journal_root_type")
        fixtureCheck(fixtureAttributes(directoryPath)?.isDirectory == true, "journal_directory_type")
    }

    private fun validateOwnership() {
        validateDirectory()
        val armed = AutomationProcessRecoveryProtocol.readDescriptor(root.toFile())
        fixtureCheck(armed == descriptor, "journal_armed_descriptor_mismatch")
        val owned = AutomationProcessRecoveryProtocol.decodeDescriptor(
            readFixtureFile(
                directoryPath.resolve(AutomationProcessRecoveryProtocol.OWNER_FILE_NAME),
                AutomationProcessRecoveryProtocol.MAX_DESCRIPTOR_BYTES,
            ),
        )
        fixtureCheck(owned == descriptor, "journal_owner_mismatch")
        val allowed = setOf(
            AutomationProcessRecoveryProtocol.OWNER_FILE_NAME,
            AutomationProcessRecoveryProtocol.JOURNAL_FILE_NAME,
            AutomationProcessRecoveryProtocol.CHECKPOINT_FILE_NAME,
            AutomationProcessRecoveryProtocol.CHECKPOINT_PENDING_FILE_NAME,
        )
        Files.newDirectoryStream(directoryPath).use { entries ->
            for (entry in entries) {
                fixtureCheck(entry.fileName.toString() in allowed, "journal_unexpected_file")
                requireFixtureRegularFile(entry)
            }
        }
    }

    private fun readAndReconcile(channel: FileChannel): List<AutomationProcessRecoveryEvent> {
        val bytes = readFixtureChannel(channel, AutomationProcessRecoveryProtocol.MAX_JOURNAL_BYTES)
        fixtureCheck(bytes.isEmpty() || bytes.last() == '\n'.code.toByte(), "journal_incomplete_tail")
        val events = if (bytes.isEmpty()) {
            emptyList()
        } else {
            val lines = strictUtf8(bytes).split('\n').dropLast(1)
            fixtureCheck(lines.size <= AutomationProcessRecoveryProtocol.MAX_EVENTS, "journal_event_limit")
            lines.mapIndexed { index, line ->
                val entry = AutomationProcessRecoveryProtocol.decodeEvent(line.toByteArray(Charsets.UTF_8))
                fixtureCheck(entry.fixtureId == descriptor.fixtureId, "journal_fixture_mismatch")
                fixtureCheck(entry.sequence == index.toLong() + 1, "journal_sequence")
                entry
            }
        }
        val checkpoint = readOptionalEvent(checkpointPath)
        if (checkpoint != null) {
            val index = checkpoint.sequence - 1
            fixtureCheck(index >= 0 && index < events.size, "checkpoint_not_in_journal")
            fixtureCheck(sameEvent(checkpoint, events[index.toInt()]), "checkpoint_not_journal_prefix")
        }
        val pending = readOptionalEvent(pendingPath)
        if (pending != null) {
            fixtureCheck(events.isNotEmpty() && sameEvent(pending, events.last()), "checkpoint_pending_mismatch")
        }
        fixtureCheck(events.isNotEmpty() || checkpoint == null && pending == null, "checkpoint_without_journal")
        if (events.isNotEmpty() && (checkpoint == null || !sameEvent(checkpoint, events.last()) || pending != null)) {
            // Only a complete, strictly validated journal is authoritative. Force it again before
            // repairing its projection; never infer an event from a partial line or a temp file.
            channel.force(true)
            val lastBytes = AutomationProcessRecoveryProtocol.encodeEvent(events.last())
            if (pending != null) {
                forceFixtureFile(pendingPath)
                Files.move(pendingPath, checkpointPath, ATOMIC_MOVE, REPLACE_EXISTING)
                forceFixtureDirectory(directoryPath)
            } else {
                writeCheckpoint(lastBytes)
            }
        }
        return Collections.unmodifiableList(events)
    }

    private fun readOptionalEvent(path: Path): AutomationProcessRecoveryEvent? {
        if (fixtureAttributes(path) == null) return null
        val event = AutomationProcessRecoveryProtocol.decodeEvent(
            readFixtureFile(path, AutomationProcessRecoveryProtocol.MAX_EVENT_BYTES),
        )
        fixtureCheck(event.fixtureId == descriptor.fixtureId, "checkpoint_fixture_mismatch")
        return event
    }

    private fun writeCheckpoint(bytes: ByteArray) {
        if (fixtureAttributes(checkpointPath) != null) requireFixtureRegularFile(checkpointPath)
        fixtureCheck(fixtureAttributes(pendingPath) == null, "checkpoint_pending_exists")
        createFixtureFile(pendingPath, bytes)
        Files.move(pendingPath, checkpointPath, ATOMIC_MOVE, REPLACE_EXISTING)
        forceFixtureDirectory(directoryPath)
    }

    companion object {
        private val processLock = Any()

        fun open(
            noBackupDir: File,
            descriptor: AutomationProcessRecoveryDescriptor,
        ): AutomationProcessRecoveryJournal = synchronized(processLock) {
            val root = fixtureRoot(noBackupDir)
            fixtureCheck(
                AutomationProcessRecoveryProtocol.readDescriptor(root.toFile()) == descriptor,
                "journal_armed_descriptor_mismatch",
            )
            val directory = root.resolve(descriptor.checkpointDirectoryName)
            if (fixtureAttributes(directory) == null) {
                Files.createDirectory(directory)
                forceFixtureDirectory(root)
                // CREATE_NEW refuses existing files even if another actor races first use.
                // A failed initialization is left for inspection, never treated as unarmed.
                createFixtureFile(
                    directory.resolve(AutomationProcessRecoveryProtocol.OWNER_FILE_NAME),
                    AutomationProcessRecoveryProtocol.encodeDescriptor(descriptor),
                )
                createFixtureFile(
                    directory.resolve(AutomationProcessRecoveryProtocol.JOURNAL_FILE_NAME),
                    byteArrayOf(),
                )
                forceFixtureDirectory(directory)
            }
            val journal = AutomationProcessRecoveryJournal(root, descriptor)
            journal.events() // Validate ownership and repair only an exact complete prefix.
            journal
        }
    }
}

private fun canonicalUuid(value: String): Boolean =
    value.length == 36 && runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

private fun immutableFixtureFields(fields: Map<String, Any>): Map<String, Any> {
    fixtureCheck(fields.size <= AutomationProcessRecoveryProtocol.MAX_FIELDS, "event_field_count")
    val copy = linkedMapOf<String, Any>()
    for ((key, raw) in fields) {
        fixtureCheck(key.matches(Regex("[A-Za-z][A-Za-z0-9_]{0,47}")), "event_field_name")
        val value: Any = when (raw) {
            is String -> {
                fixtureCheck(raw.length <= AutomationProcessRecoveryProtocol.MAX_FIELD_STRING_CHARS, "event_field_string_limit")
                fixtureCheck(validSurrogates(raw), "event_field_unicode")
                raw
            }
            is Boolean -> raw
            is Byte -> raw.toLong()
            is Short -> raw.toLong()
            is Int -> raw.toLong()
            is Long -> raw
            else -> fixtureFailure("event_field_type")
        }
        copy[key] = value
    }
    return Collections.unmodifiableMap(copy)
}

private fun fixtureRoot(directory: File): Path {
    val path = directory.toPath().toAbsolutePath().normalize()
    fixtureCheck(fixtureAttributes(path)?.isDirectory == true, "fixture_root_type")
    // Context supplies the root. Resolve OS-owned parent aliases, but reject a symlink root.
    return path.toRealPath()
}

private fun fixtureAttributes(path: Path): BasicFileAttributes? = try {
    Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
} catch (_: NoSuchFileException) {
    null
}

private fun requireFixtureRegularFile(path: Path) {
    fixtureCheck(fixtureAttributes(path)?.isRegularFile == true, "fixture_file_type")
}

private fun readFixtureFile(path: Path, limit: Int): ByteArray {
    requireFixtureRegularFile(path)
    return FileChannel.open(path, READ, NOFOLLOW_LINKS).use { readFixtureChannel(it, limit) }
}

private fun readFixtureChannel(channel: FileChannel, limit: Int): ByteArray {
    fixtureCheck(channel.size() <= limit, "file_byte_limit")
    channel.position(0)
    val output = ByteArrayOutputStream()
    val buffer = ByteBuffer.allocate(8_192)
    while (true) {
        buffer.clear()
        val count = channel.read(buffer)
        if (count == -1) break
        fixtureCheck(output.size() + count <= limit, "file_byte_limit")
        output.write(buffer.array(), 0, count)
    }
    return output.toByteArray()
}

private fun createFixtureFile(path: Path, bytes: ByteArray) {
    FileChannel.open(path, CREATE_NEW, WRITE, NOFOLLOW_LINKS).use { channel ->
        writeFully(channel, bytes)
        channel.force(true)
    }
}

private fun writeFully(channel: FileChannel, bytes: ByteArray) {
    val buffer = ByteBuffer.wrap(bytes)
    while (buffer.hasRemaining()) channel.write(buffer)
}

private fun forceFixtureFile(path: Path) {
    requireFixtureRegularFile(path)
    FileChannel.open(path, WRITE, NOFOLLOW_LINKS).use { it.force(true) }
}

private fun forceFixtureDirectory(path: Path) {
    fixtureCheck(fixtureAttributes(path)?.isDirectory == true, "fixture_directory_type")
    FileChannel.open(path, READ, NOFOLLOW_LINKS).use { it.force(true) }
}

private fun sameEvent(left: AutomationProcessRecoveryEvent, right: AutomationProcessRecoveryEvent): Boolean =
    left.schema == right.schema && left.fixtureId == right.fixtureId &&
        left.event == right.event && left.sequence == right.sequence &&
        left.pid == right.pid && left.uid == right.uid && left.bootId == right.bootId &&
        left.timestamp == right.timestamp && left.fields == right.fields

private fun Map<String, Any>.fixtureString(key: String): String =
    this[key] as? String ?: fixtureFailure("json_string_required")

private fun Map<String, Any>.fixtureLong(key: String): Long =
    this[key] as? Long ?: fixtureFailure("json_integer_required")

private fun Map<String, Any>.fixtureInt(key: String): Int {
    val value = fixtureLong(key)
    fixtureCheck(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong(), "json_integer_range")
    return value.toInt()
}

private fun strictUtf8(bytes: ByteArray): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
} catch (_: java.nio.charset.CharacterCodingException) {
    fixtureFailure("json_utf8")
}

private fun validSurrogates(value: String): Boolean {
    var index = 0
    while (index < value.length) {
        val current = value[index++]
        if (current.isHighSurrogate()) {
            if (index >= value.length || !value[index++].isLowSurrogate()) return false
        } else if (current.isLowSurrogate()) return false
    }
    return true
}

private fun fixtureCheck(condition: Boolean, reason: String) {
    if (!condition) fixtureFailure(reason)
}

private fun fixtureFailure(reason: String): Nothing = throw AutomationProcessRecoveryProtocolException(reason)

/**
 * Deliberately closed JSON subset: objects, strings, signed integers and booleans. Android's
 * permissive JSONTokener differs from the host implementation on duplicates/trailing syntax.
 * Do not reuse this fixture parser as a general product JSON framework.
 */
private object FixtureJson {
    fun decode(bytes: ByteArray, limit: Int): Map<String, Any> {
        fixtureCheck(bytes.isNotEmpty() && bytes.size <= limit, "json_byte_limit")
        val parser = Parser(strictUtf8(bytes))
        val value = parser.objectValue(0)
        parser.whitespace()
        fixtureCheck(parser.finished(), "json_trailing_data")
        return value
    }

    fun encode(value: Map<String, Any>): String = buildString { appendObject(value) }

    private fun StringBuilder.appendObject(value: Map<String, Any>) {
        append('{')
        value.entries.forEachIndexed { index, (key, entry) ->
            if (index != 0) append(',')
            appendQuoted(key)
            append(':')
            when (entry) {
                is String -> appendQuoted(entry)
                is Long -> append(entry)
                is Boolean -> append(entry)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    appendObject(entry as Map<String, Any>)
                }
                else -> fixtureFailure("json_value_type")
            }
        }
        append('}')
    }

    private fun StringBuilder.appendQuoted(value: String) {
        fixtureCheck(validSurrogates(value), "json_unicode")
        append('"')
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }

    private class Parser(private val text: String) {
        private var position = 0

        fun finished(): Boolean = position == text.length

        fun whitespace() {
            while (position < text.length && text[position] in " \t\r\n") position++
        }

        fun objectValue(depth: Int): Map<String, Any> {
            fixtureCheck(depth <= 1, "json_depth")
            whitespace()
            expect('{')
            whitespace()
            val result = linkedMapOf<String, Any>()
            if (consume('}')) return result
            while (true) {
                whitespace()
                val key = stringValue()
                fixtureCheck(!result.containsKey(key), "json_duplicate_key")
                fixtureCheck(result.size < AutomationProcessRecoveryProtocol.MAX_FIELDS, "json_field_count")
                whitespace()
                expect(':')
                whitespace()
                result[key] = when (peek()) {
                    '"' -> stringValue()
                    '{' -> objectValue(depth + 1)
                    't' -> literal("true", true)
                    'f' -> literal("false", false)
                    '-', in '0'..'9' -> integerValue()
                    else -> fixtureFailure("json_value")
                }
                whitespace()
                if (consume('}')) return result
                expect(',')
            }
        }

        private fun stringValue(): String {
            expect('"')
            val result = StringBuilder()
            while (position < text.length) {
                val character = text[position++]
                if (character == '"') {
                    val value = result.toString()
                    fixtureCheck(validSurrogates(value), "json_unicode")
                    return value
                }
                if (character == '\\') {
                    fixtureCheck(position < text.length, "json_escape")
                    result.append(
                        when (val escape = text[position++]) {
                            '"', '\\', '/' -> escape
                            'b' -> '\b'
                            'f' -> '\u000c'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> {
                                fixtureCheck(position + 4 <= text.length, "json_unicode_escape")
                                val digits = text.substring(position, position + 4)
                                fixtureCheck(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }, "json_unicode_escape")
                                position += 4
                                digits.toInt(16).toChar()
                            }
                            else -> fixtureFailure("json_escape")
                        },
                    )
                } else {
                    fixtureCheck(character.code >= 0x20, "json_unescaped_control")
                    result.append(character)
                }
                fixtureCheck(result.length <= AutomationProcessRecoveryProtocol.MAX_FIELD_STRING_CHARS, "json_string_limit")
            }
            fixtureFailure("json_unterminated_string")
        }

        private fun integerValue(): Long {
            val start = position
            consume('-')
            if (consume('0')) {
                fixtureCheck(peek() !in '0'..'9', "json_leading_zero")
            } else {
                fixtureCheck(peek() in '1'..'9', "json_integer")
                while (peek() in '0'..'9') position++
            }
            return text.substring(start, position).toLongOrNull() ?: fixtureFailure("json_integer_range")
        }

        private fun literal(expected: String, value: Boolean): Boolean {
            fixtureCheck(text.startsWith(expected, position), "json_literal")
            position += expected.length
            return value
        }

        private fun peek(): Char = text.getOrNull(position) ?: '\u0000'

        private fun expect(character: Char) {
            fixtureCheck(consume(character), "json_syntax")
        }

        private fun consume(character: Char): Boolean {
            if (peek() != character) return false
            position++
            return true
        }
    }
}
