package ai.hans.standard.profile

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AtomicFileUserProfileStorageTest {
    @Test
    fun appPrivateProfileSurvivesReopenAndClears() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, "profile-test-${UUID.randomUUID()}.json")
        val document = UserProfileDocument(
            revision = 4,
            interviewActive = true,
            draftAnswers = listOf(ProfileAnswer("name", "Name?", "Alex")),
            confirmedSummary = "Existing profile",
            updatedAtMillis = 12,
        )
        try {
            AtomicFileUserProfileStorage(context, file).write(document)
            assertEquals(document, AtomicFileUserProfileStorage(context, file).read())
            AtomicFileUserProfileStorage(context, file).clear()
            assertFalse(file.exists())
            assertEquals(
                UserProfileDocument(),
                AtomicFileUserProfileStorage(context, file).read(),
            )
        } finally {
            file.delete()
            File(file.path + ".bak").delete()
            File(file.path + ".new").delete()
        }
    }
}
