package ai.hans.standard.diagnostics

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteDatabaseLockedException
import android.os.Process
import android.system.Os
import android.system.OsConstants
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.FileInputStream
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.atomic.AtomicBoolean

/** On-demand same-UID read only. No helpers, migrations, checkpoint, corruption repair or replay.
 * Never follows filename guesses: a revert can select a different immutable rollout for one ID.
 */
internal class AndroidNativeToolHistoryReader {
    fun read(dataRoot: File, codexHome: File, sqliteHome: File): NativeToolHistoryResult {
      return try {
        requireSafeDirectory(dataRoot, dataRoot)
        requireSafeDirectory(dataRoot, codexHome)
        requireSafeDirectory(dataRoot, sqliteHome)
        val thread = selectedThread(dataRoot)
        val database = File(sqliteHome, "state_5.sqlite")
        for (file in listOf(database, File(database.path + "-wal"), File(database.path + "-shm"))) {
            if (file.exists()) requireSafeFile(dataRoot, file, MAX_DATABASE_BYTES)
        }
        if (!database.exists()) return result(NativeToolHistoryStatus.MISSING)
        val databaseIdentity = Os.lstat(database.path)
        val corrupt = AtomicBoolean(false)
        SQLiteDatabase.openDatabase(database.path, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            DatabaseErrorHandler { corrupt.set(true) }).use { db ->
            check(db.isReadOnly)
            db.rawQuery("SELECT type FROM sqlite_master WHERE name = 'threads'", null).use {
                if (!it.moveToFirst() || it.getString(0) != "table" || it.moveToNext()) {
                    return result(NativeToolHistoryStatus.CORRUPT)
                }
            }
            val path = selectedPath(db, thread) ?: return result(NativeToolHistoryStatus.MISSING)
            val rollout = File(path)
            // No compressed or archived/inherited history traversal, and no arbitrary private files.
            val sessions = File(codexHome, "sessions")
            requireSafeDirectory(dataRoot, sessions)
            if (!rollout.isAbsolute || !rollout.toPath().startsWith(sessions.toPath()) ||
                !ROLLOUT_NAME.matches(rollout.name)) return result(NativeToolHistoryStatus.UNSAFE_PATH)
            val parsed = readRollout(dataRoot, rollout, thread)
            val after = Os.lstat(database.path)
            if (corrupt.get()) result(NativeToolHistoryStatus.CORRUPT)
            else if (databaseIdentity.st_dev != after.st_dev || databaseIdentity.st_ino != after.st_ino ||
                selectedThread(dataRoot) != thread || selectedPath(db, thread) != path) result(NativeToolHistoryStatus.BUSY)
            else parsed
        }
    } catch (_: InterruptedException) { result(NativeToolHistoryStatus.TIMEOUT)
    } catch (_: SQLiteDatabaseLockedException) { result(NativeToolHistoryStatus.BUSY)
    } catch (_: SQLiteDatabaseCorruptException) { result(NativeToolHistoryStatus.CORRUPT)
    } catch (_: UnsafeHistoryPath) { result(NativeToolHistoryStatus.UNSAFE_PATH)
    } catch (_: PendingHistoryWrite) { result(NativeToolHistoryStatus.BUSY)
    } catch (_: java.io.FileNotFoundException) { result(NativeToolHistoryStatus.MISSING)
    } catch (_: Exception) { result(NativeToolHistoryStatus.IO) }
    }

    private fun selectedPath(db: SQLiteDatabase, thread: String): String? {
        interrupted()
        return db.rawQuery("SELECT rollout_path, typeof(rollout_path) FROM threads WHERE id = ? AND archived = 0 LIMIT 2",
            arrayOf(thread)).use { cursor ->
            if (!cursor.moveToFirst()) null
            else {
                check(cursor.getString(1) == "text")
                val value = cursor.getString(0)
                check(value.length in 1..4096 && !cursor.moveToNext())
                value
            }
        }
    }

    private fun selectedThread(root: File): String {
        interrupted()
        val file = File(root, "shared_prefs/hans_codex_session_v1.xml")
        // SharedPreferences in this separate process can be stale; never initialize the session
        // store (its Context constructor creates a workspace), nor restore AtomicFile backups.
        if (File(file.path + ".bak").exists()) throw PendingHistoryWrite()
        val raw = openChecked(root, file, MAX_PREFERENCES_BYTES).use { stream ->
            val length = stream.channel.size().toInt()
            val bytes = ByteArray(length)
            readFully(stream, bytes)
            strictUtf8(bytes)
        }
        check(!raw.contains("<!")) // No DTD/entity declarations or external resource loading.
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(raw))
        var selected: String? = null
        var event = parser.next()
        var events = 0
        while (event != XmlPullParser.END_DOCUMENT) {
            interrupted()
            check(++events <= 8192 && parser.depth <= 4)
            if (event == XmlPullParser.START_TAG && parser.depth == 2 &&
                parser.getAttributeValue(null, "name") == "thread_id") {
                check(parser.name == "string" && selected == null)
                selected = parser.nextText()
            }
            event = parser.next()
        }
        if (File(file.path + ".bak").exists()) throw PendingHistoryWrite()
        return selected?.takeIf(THREAD_ID::matches) ?: throw java.io.FileNotFoundException()
    }

    private fun readRollout(root: File, file: File, thread: String): NativeToolHistoryResult {
        return openChecked(root, file, MAX_ROLLOUT_BYTES).use { stream ->
            val before = Os.fstat(stream.fd)
            val head = ByteArray(minOf(before.st_size, NativeToolHistoryParser.MAX_LINE_BYTES + 1L).toInt())
            readFully(stream, head)
            val metadataEnd = head.indexOf('\n'.code.toByte())
            if (metadataEnd < 0) return result(NativeToolHistoryStatus.CORRUPT)
            when (NativeToolHistoryParser.metadata(strictUtf8(head.copyOfRange(0, metadataEnd)), thread)) {
                null -> return result(NativeToolHistoryStatus.CORRUPT)
                false -> return result(NativeToolHistoryStatus.UNSUPPORTED_HISTORY)
                true -> Unit
            }
            val start = (before.st_size - NativeToolHistoryParser.MAX_TAIL_BYTES).coerceAtLeast(0)
            stream.channel.position(start)
            val bytes = ByteArray((before.st_size - start).toInt())
            readFully(stream, bytes)
            val entries = ArrayDeque<NativeToolHistoryEntry>()
            var failures = 0
            var skipped = 0
            var lineStart = if (start > 0) bytes.indexOf('\n'.code.toByte()).let { if (it < 0) bytes.size else it + 1 } else 0
            // Prefer the newest bounded lines, not the first lines of a busy tail window.
            var newlineCount = 0
            for (index in bytes.lastIndex downTo lineStart) {
                if (bytes[index] == '\n'.code.toByte() && ++newlineCount > NativeToolHistoryParser.MAX_LINES) {
                    if (newlineCount == NativeToolHistoryParser.MAX_LINES + 1) lineStart = index + 1
                    skipped++
                }
            }
            for (index in lineStart until bytes.size) {
                if (bytes[index] != '\n'.code.toByte()) continue
                interrupted()
                if (index - lineStart > NativeToolHistoryParser.MAX_LINE_BYTES) {
                    skipped++
                } else try {
                    NativeToolHistoryParser.failure(strictUtf8(bytes.copyOfRange(lineStart, index)), thread)?.let {
                        failures++
                        if (entries.size == NativeToolHistoryParser.MAX_ENTRIES) entries.removeFirst()
                        entries.addLast(it)
                    }
                } catch (_: Exception) { skipped++ }
                lineStart = index + 1
            }
            if (lineStart < bytes.size) skipped++ // Ignore a concurrent incomplete final record.
            val after = Os.lstat(file.path)
            if (before.st_dev != after.st_dev || before.st_ino != after.st_ino || after.st_size < before.st_size) {
                return result(NativeToolHistoryStatus.BUSY)
            }
            NativeToolHistoryResult(if (start > 0 || skipped > 0) NativeToolHistoryStatus.PARTIAL else NativeToolHistoryStatus.AVAILABLE,
                entries.toList(), failures, skipped, start > 0)
        }
    }

    private fun openChecked(root: File, file: File, maxBytes: Long): FileInputStream {
        requireSafeFile(root, file, maxBytes)
        val descriptor = Os.open(file.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
        return try {
            val stat = Os.fstat(descriptor)
            if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != Process.myUid() || stat.st_size !in 0..maxBytes) {
                throw UnsafeHistoryPath()
            }
            FileInputStream(descriptor)
        } catch (error: Exception) { Os.close(descriptor); throw error }
    }

    private fun requireSafeDirectory(root: File, file: File) {
        requireCanonical(root, file)
        val stat = Os.lstat(file.path)
        if (!OsConstants.S_ISDIR(stat.st_mode) || stat.st_uid != Process.myUid()) throw UnsafeHistoryPath()
    }

    private fun requireSafeFile(root: File, file: File, maxBytes: Long) {
        requireCanonical(root, file)
        val stat = Os.lstat(file.path)
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_uid != Process.myUid() || stat.st_size !in 0..maxBytes) {
            throw UnsafeHistoryPath()
        }
    }

    private fun requireCanonical(root: File, file: File) {
        if (!root.isAbsolute || root.canonicalFile != root.absoluteFile || !file.isAbsolute ||
            file.canonicalFile != file.absoluteFile || !file.toPath().startsWith(root.toPath())) throw UnsafeHistoryPath()
    }

    private fun readFully(stream: FileInputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            interrupted()
            val read = stream.read(bytes, offset, bytes.size - offset)
            if (read <= 0) throw PendingHistoryWrite()
            offset += read
        }
    }

    private fun strictUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun interrupted() { if (Thread.currentThread().isInterrupted) throw InterruptedException() }
    private class UnsafeHistoryPath : Exception()
    private class PendingHistoryWrite : Exception()

    companion object {
        private const val MAX_PREFERENCES_BYTES = 262_144L
        private const val MAX_DATABASE_BYTES = 134_217_728L
        private const val MAX_ROLLOUT_BYTES = 536_870_912L
        private val THREAD_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val ROLLOUT_NAME = Regex("rollout-[0-9T-]+-[0-9a-f-]{36}(?:_[0-9a-f-]{36})?\\.jsonl")
        private fun result(status: NativeToolHistoryStatus) = NativeToolHistoryResult(status)
    }
}
