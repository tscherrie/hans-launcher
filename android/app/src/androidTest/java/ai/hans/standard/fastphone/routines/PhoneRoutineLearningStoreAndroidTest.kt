package ai.hans.standard.fastphone.routines

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual Android AtomicFile, in a unique disposable no-backup file, never the user's store. */
@RunWith(AndroidJUnit4::class)
class PhoneRoutineLearningStoreAndroidTest {
    private val files = mutableListOf<File>()

    @After fun cleanupExactTestFiles() {
        files.forEach { file -> listOf(file, File(file.path + ".bak"), File(file.path + ".new")).forEach(File::delete) }
    }

    @Test fun atomicTemplatesRemainInNoBackupStorageAndSurviveReload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = testFile(context)
        val store = PhoneRoutineLearningStore(AtomicPhoneRoutineStorage(file), nowMillis = { 86_400_000 })
        repeat(3) { assertTrue(store.observeCompleted(observation())) }
        val reloaded = PhoneRoutineLearningStore(AtomicPhoneRoutineStorage(file), nowMillis = { 86_400_001 })
        val hint = JSONObject(checkNotNull(reloaded.promptData())).getJSONArray("candidates").getJSONObject(0)
        assertEquals(3, hint.getInt("helperCompletionCount"))
        assertTrue(file.canonicalPath.startsWith(context.noBackupFilesDir.canonicalPath + File.separator))
        assertTrue(file.length() <= PhoneRoutineLearningStore.MAX_BYTES)
        assertFalse(file.readText().contains("textToEnter"))
        assertFalse(file.readText().contains("screenshot"))
    }

    @Test fun atomicForgetAndMalformedStorageFailClosed() {
        val storage = AtomicPhoneRoutineStorage(testFile(ApplicationProvider.getApplicationContext()))
        val store = PhoneRoutineLearningStore(storage, nowMillis = { 86_400_000 })
        repeat(3) { assertTrue(store.observeCompleted(observation())) }
        assertNotNull(store.promptData()); assertTrue(store.clear()); assertNull(store.promptData())
        storage.writeAtomically("malformed".toByteArray())
        assertNull(store.promptData())
        assertFalse(store.observeCompleted(observation()))
    }

    private fun testFile(context: Context) = File(context.noBackupFilesDir,
        "routine-learning-test-${UUID.randomUUID()}.json").also(files::add)
    private fun observation() = RoutineObservation("synthetic.learning", "Open the garage", setOf("click"), 4)
}
