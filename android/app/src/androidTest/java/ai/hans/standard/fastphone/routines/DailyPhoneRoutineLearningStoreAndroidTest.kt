package ai.hans.standard.fastphone.routines

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real private/no-backup AtomicFile proofs; no account, network, app actions or user files. */
@RunWith(AndroidJUnit4::class)
class DailyPhoneRoutineLearningStoreAndroidTest {
    private val files = mutableListOf<File>()

    @After fun cleanupExactDisposableFiles() {
        files.forEach { file -> listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach(File::delete) }
    }

    @Test fun atomicDailyGroupsSurviveReloadRemainBoundedAndAccountPrivate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dailyFile = testFile(context); val historyFile = testFile(context)
        val history = PhoneRoutineLearningStore(AtomicPhoneRoutineStorage(historyFile))
        var owner: String? = "a".repeat(64)
        fun newStore() = DailyPhoneRoutineLearningStore(AtomicDailyPhoneRoutineStorage(dailyFile), history, ownerProvider = { owner })
        val store = newStore()
        assertFalse(store.snapshot().enabled); assertTrue(store.setEnabled(true))
        repeat(3) { assertTrue(store.observeCompleted(RoutineObservation("synthetic.learning", "Open private garage", setOf("click"), 4))) }
        val claim = checkNotNull(store.claim())
        val id = claim.input.evidence.single().id
        val output = JSONObject().put("version", 1).put("groups", JSONArray().put(JSONObject()
            .put("evidenceIds", JSONArray(listOf(id))).put("templateEvidenceId", id))).toString()
        assertTrue(store.complete(claim, output))
        val reloaded = newStore()
        assertTrue(JSONObject(reloaded.promptData()).getBoolean("active"))
        assertEquals(1, reloaded.snapshot().candidateCount)
        assertTrue(dailyFile.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        assertTrue(dailyFile.length() <= 32 * 1_024)
        assertFalse(dailyFile.readText().contains("private garage"))
        owner = "b".repeat(64)
        assertFalse(JSONObject(reloaded.promptData()).getBoolean("active"))
        assertFalse(reloaded.snapshot().enabled)
        assertEquals(0, history.currentAnalysisEvidence()!!.size)
    }

    @Test fun atomicForgetFencesPendingOutputAndMalformedStateFailsClosed() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = AtomicDailyPhoneRoutineStorage(testFile(context))
        val history = PhoneRoutineLearningStore(AtomicPhoneRoutineStorage(testFile(context)))
        val store = DailyPhoneRoutineLearningStore(storage, history, ownerProvider = { "a".repeat(64) })
        assertTrue(store.setEnabled(true))
        repeat(3) { assertTrue(store.observeCompleted(RoutineObservation("synthetic.learning", "Open garage", setOf("click"), 4))) }
        val pending = checkNotNull(store.claim())
        assertTrue(store.forget()); assertTrue(store.snapshot().enabled)
        assertFalse(store.complete(pending, "{\"version\":1,\"groups\":[]}"))
        assertFalse(JSONObject(store.promptData()).getBoolean("active"))
        storage.writeAtomically("malformed".toByteArray())
        val corrupted = DailyPhoneRoutineLearningStore(storage, history, ownerProvider = { "a".repeat(64) })
        assertFalse(corrupted.snapshot().enabled); assertNull(corrupted.claim())
        assertEquals("learning_storage_invalid", corrupted.snapshot().errorCode)
        assertTrue(corrupted.forget()); assertFalse(corrupted.snapshot().enabled)
    }

    private fun testFile(context: Context) = File(context.noBackupFilesDir,
        "daily-learning-test-${UUID.randomUUID()}.json").also(files::add)
}
