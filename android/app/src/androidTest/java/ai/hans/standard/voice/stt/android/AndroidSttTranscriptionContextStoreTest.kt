package ai.hans.standard.voice.stt.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSttTranscriptionContextStoreTest {
    private lateinit var directory: File
    private lateinit var file: File
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        directory = Files.createTempDirectory(context.cacheDir.toPath(), "stt-glossary-test-")
            .toFile()
        file = directory.resolve("confirmed.json")
    }

    @After
    fun tearDown() {
        check(directory.parentFile == context.cacheDir && directory.name.startsWith("stt-glossary-test-"))
        assertTrue(directory.deleteRecursively())
    }

    @Test
    fun writeRequiresAnExplicitUserConfirmation() {
        val store = AtomicFileConfirmedSttGlossary(context, file)

        assertThrows(IllegalArgumentException::class.java) {
            store.replaceConfirmedTerms(listOf("Grenzebach"), explicitUserConfirmation = false)
        }

        assertFalse(file.exists())
        assertTrue(store.readConfirmedTerms().isEmpty())
    }

    @Test
    fun durableEditsAreSanitizedAndVisibleToTheNextContextSnapshot() {
        val store = AtomicFileConfirmedSttGlossary(context, file)
        val source = AndroidSttTranscriptionContextSource(
            context = context,
            profileSummary = { "Bestätigtes Profil" },
            glossary = store,
        )
        store.replaceConfirmedTerms(
            listOf(" Grenzebach ", "GRENZEBACH", "Skill\tMe\u0000Now"),
            explicitUserConfirmation = true,
        )

        val firstRecording = source.snapshot()

        assertEquals(listOf("Grenzebach", "Skill Me Now"), firstRecording.confirmedGlossaryTerms)
        assertEquals("Bestätigtes Profil", firstRecording.confirmedProfileSummary)

        AtomicFileConfirmedSttGlossary(context, file).replaceConfirmedTerms(
            listOf("Gamsbart"),
            explicitUserConfirmation = true,
        )

        assertEquals(listOf("Gamsbart"), source.snapshot().confirmedGlossaryTerms)
        assertEquals(listOf("Gamsbart"), AtomicFileConfirmedSttGlossary(context, file).readConfirmedTerms())
    }

    @Test
    fun damagedOptionalGlossaryFailsClosedWithoutBlockingTranscriptionContext() {
        file.writeText("{broken")
        val source = AndroidSttTranscriptionContextSource(
            context = context,
            profileSummary = { "Bestätigtes Profil" },
            glossary = AtomicFileConfirmedSttGlossary(context, file),
        )

        val snapshot = source.snapshot()

        assertTrue(snapshot.confirmedGlossaryTerms.isEmpty())
        assertEquals("Bestätigtes Profil", snapshot.confirmedProfileSummary)
    }
}
