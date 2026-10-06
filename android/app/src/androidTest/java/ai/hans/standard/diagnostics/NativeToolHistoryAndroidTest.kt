package ai.hans.standard.diagnostics

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
class NativeToolHistoryAndroidTest {
    private val thread = "00000000-0000-0000-0000-000000000001"

    @Test fun readsOnlySelectedDurableFailureAndDoesNotChangeHistory() = fixture { root, home, rollout ->
        val before = rollout.readBytes()
        val result = AndroidNativeToolHistoryReader().read(root, home, home)
        assertEquals(NativeToolHistoryStatus.AVAILABLE, result.status)
        assertEquals(1, result.failuresSeen)
        assertEquals(ToolFailureCode.SEMANTIC_FALLBACK_PROOF_REQUIRED, result.entries.single().failure.code)
        assertArrayEquals(before, rollout.readBytes())
        assertFalse(result.encode().contains(thread))
    }

    @Test fun rejectsSymlinkRolloutWithoutFollowingIt() = fixture { root, home, rollout ->
        val target = File(rollout.parentFile, "private-target")
        assertTrue(rollout.renameTo(target))
        Os.symlink(target.path, rollout.path)
        assertEquals(NativeToolHistoryStatus.UNSAFE_PATH, AndroidNativeToolHistoryReader().read(root, home, home).status)
        assertTrue(target.isFile)
    }

    @Test fun pendingPreferenceBackupDoesNotRestoreOrChooseStaleThread() = fixture { root, home, _ ->
        val backup = File(root, "shared_prefs/hans_codex_session_v1.xml.bak")
        backup.writeText("private")
        assertEquals(NativeToolHistoryStatus.BUSY, AndroidNativeToolHistoryReader().read(root, home, home).status)
        assertEquals("private", backup.readText())
    }

    @Test fun corruptDatabaseIsNotDeletedOrRepaired() = fixture { root, home, _ ->
        val database = File(home, "state_5.sqlite")
        val bytes = ByteArray(512) { 7 }
        database.writeBytes(bytes)
        val result = AndroidNativeToolHistoryReader().read(root, home, home)
        assertTrue(result.status == NativeToolHistoryStatus.CORRUPT || result.status == NativeToolHistoryStatus.IO)
        assertArrayEquals(bytes, database.readBytes())
    }

    @Test fun skipsOverlongAndIncompleteLinesButKeepsRecentFailure() = fixture { root, home, rollout ->
        rollout.appendText("x".repeat(NativeToolHistoryParser.MAX_LINE_BYTES + 1) + "\n" + failure() + "\n{incomplete")
        val result = AndroidNativeToolHistoryReader().read(root, home, home)
        assertEquals(NativeToolHistoryStatus.PARTIAL, result.status)
        assertEquals(2, result.skippedLines)
        assertEquals(2, result.failuresSeen)
    }

    @Test fun missingSelectedPathDoesNotScanOtherMatchingRollouts() = fixture { root, home, rollout ->
        SQLiteDatabase.openDatabase(File(home, "state_5.sqlite").path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("UPDATE threads SET rollout_path = ?", arrayOf(rollout.path + ".missing"))
        }
        assertEquals(NativeToolHistoryStatus.UNSAFE_PATH, AndroidNativeToolHistoryReader().read(root, home, home).status)
        assertTrue(rollout.isFile)
    }

    @Test fun boundedLineWindowStillPrefersNewestFailure() = fixture { root, home, rollout ->
        rollout.appendText("{\"type\":\"response_item\"}\n".repeat(NativeToolHistoryParser.MAX_LINES + 1) + failure() + "\n")
        val result = AndroidNativeToolHistoryReader().read(root, home, home)
        assertEquals(NativeToolHistoryStatus.PARTIAL, result.status)
        assertEquals(1, result.failuresSeen)
        assertTrue(result.skippedLines > 0)
        assertEquals("inspect_visual_ui", result.entries.single().tool)
    }

    @Test fun boundedByteTailAndEntryCountRemainContentFree() = fixture { root, home, rollout ->
        rollout.appendText(("{\"type\":\"response_item\",\"private\":\"" + "a".repeat(2000) + "\"}\n").repeat(1100))
        rollout.appendText((failure() + "\n").repeat(30))
        val result = AndroidNativeToolHistoryReader().read(root, home, home)
        assertEquals(NativeToolHistoryStatus.PARTIAL, result.status)
        assertTrue(result.tailOnly)
        assertEquals(30, result.failuresSeen)
        assertEquals(24, result.entries.size)
        assertTrue(result.encode().toByteArray().size < 8192)
    }

    private fun fixture(block: (File, File, File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "native-history-test-").toFile().canonicalFile
        try {
            val home = File(root, "codex/home").apply { mkdirs() }
            File(root, "shared_prefs").mkdirs()
            File(root, "shared_prefs/hans_codex_session_v1.xml").writeText(
                "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map><string name=\"thread_id\">$thread</string></map>")
            val rollout = File(home, "sessions/2026/09/24/rollout-2026-09-24T00-00-00-$thread.jsonl")
            rollout.parentFile!!.mkdirs()
            rollout.writeText("{\"type\":\"session_meta\",\"payload\":{\"id\":\"$thread\",\"history_mode\":\"paginated\"}}\n" + failure() + "\n")
            SQLiteDatabase.openOrCreateDatabase(File(home, "state_5.sqlite"), null).use {
                it.execSQL("CREATE TABLE threads(id TEXT PRIMARY KEY, rollout_path TEXT NOT NULL, archived INTEGER NOT NULL)")
                it.execSQL("INSERT INTO threads VALUES(?, ?, 0)", arrayOf(thread, rollout.path))
            }
            block(root, home, rollout)
        } finally { root.deleteRecursively() }
    }

    private fun failure() = """{"type":"event_msg","payload":{"type":"item_completed","thread_id":"$thread","item":{"type":"DynamicToolCall","tool":"inspect_visual_ui","status":"failed","success":false,"content_items":[{"type":"inputText","text":"{\"errorCode\":\"semantic_fallback_proof_required\"}"}]}}}"""
}
