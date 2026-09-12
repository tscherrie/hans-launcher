package ai.hans.standard.phone.notifications

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NotificationConnectionBaselineStoreTest {
    private lateinit var context: Context
    private lateinit var fileName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        fileName = "notification-baseline-test-${System.nanoTime()}"
    }

    @After
    fun tearDown() {
        context.noBackupFilesDir.resolve(fileName).delete()
        context.noBackupFilesDir.resolve("$fileName.bak").delete()
    }

    @Test
    fun establishedBaselineSurvivesProcessObjectRecreation() {
        val first = AtomicFileNotificationConnectionBaselineStore(context, fileName)
        assertFalse(first.isEstablished())
        assertTrue(first.markEstablished())

        val reopened = AtomicFileNotificationConnectionBaselineStore(context, fileName)
        assertTrue(reopened.isEstablished())
    }
}
