package ai.hans.standard

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HansApplicationLiveVoiceHapticsWiringTest {
    @Test
    fun liveVoiceResponsesAreObservedWithoutTriggeringAnswerHaptics() {
        val source = applicationSource().readText()
        val codexObserver = source
            .substringAfter("val observer = CodexClientObserver")
            .substringBefore("responseReadySessionObserver = observer")

        assertTrue(source.contains("feedback.acceptLive(event, enabled = false)"))
        assertFalse(source.contains("feedback.acceptLive(event)"))
        assertTrue(codexObserver.contains("feedback.acceptCodex("))
        assertTrue(codexObserver.contains("enabled = true"))
    }

    private fun applicationSource(): File = sequenceOf(
        File("src/main/java/ai/hans/standard/HansApplication.kt"),
        File("android/app/src/main/java/ai/hans/standard/HansApplication.kt"),
    ).firstOrNull(File::isFile) ?: error("HansApplication.kt not found")
}
