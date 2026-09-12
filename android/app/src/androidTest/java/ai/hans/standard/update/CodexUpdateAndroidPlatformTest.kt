package ai.hans.standard.update

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CodexUpdateAndroidPlatformTest {
    @Test
    fun androidPlatformBuildsOneExactNewTaskActionViewIntent() {
        val started = mutableListOf<Intent>()
        val context = object : ContextWrapper(targetContext()) {
            override fun startActivity(intent: Intent) {
                started += Intent(intent)
            }
        }

        AndroidCodexUpdatePlatform(context).open(UPDATE_URL)

        assertEquals(1, started.size)
        assertEquals(Intent.ACTION_VIEW, started.single().action)
        assertEquals(UPDATE_URL, started.single().dataString)
        assertEquals(
            Intent.FLAG_ACTIVITY_NEW_TASK,
            started.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK,
        )
    }

    private fun targetContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext

    private companion object {
        const val UPDATE_URL = "https://updates.example/hans"
    }
}
