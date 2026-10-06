package ai.hans.standard.voice.realtime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Keeps the production Android construction path connected to the independent Live setting. */
class LiveVoicePreferenceWiringTest {
    @Test
    fun runtimeSessionUsesOnlyTheIndependentLivePreferenceAndLiveResolver() {
        val source = runtimeSource().readText()
        val createSession = source.substringAfter("private fun createSession(")
            .substringBefore("internal const val ACTION_START")

        assertTrue(createSession.contains("voiceSelectionProvider = LiveVoiceVoiceSelectionProvider {"))
        assertTrue(createSession.contains("CodexLiveVoiceVoiceResolver.resolve(settings.read().codexLiveVoice)"))
        assertTrue(createSession.contains("CodexLiveVoiceSession("))
        assertFalse(createSession.contains("OpenAiLiveSessionProvider("))
        assertFalse(createSession.contains("loadBearerToken"))
        assertFalse(createSession.contains("taskExecutor ="))
        assertFalse(createSession.contains("settings.read().voice"))
        assertFalse(createSession.contains("LiveVoiceRealtimeVoiceMapper"))
    }

    @Test
    fun liveRuntimeDefaultIsNativeCoveWithoutChangingLegacyApiDefaults() {
        val defaults = runtimeSource().readText()
            .substringAfter("data class LiveVoiceRuntimeDependencies(")
            .substringBefore("fun interface LiveVoiceCaptureStartBarrier")

        assertTrue(defaults.contains("voice = CodexLiveVoiceVoiceResolver.DEFAULT_VOICE"))
        assertTrue(defaults.contains("model = CodexLiveVoiceSession.MODEL"))
    }

    private fun runtimeSource(): File = sequenceOf(
        File("src/main/java/ai/hans/standard/voice/realtime/HansLiveVoiceForegroundService.kt"),
        File("android/app/src/main/java/ai/hans/standard/voice/realtime/HansLiveVoiceForegroundService.kt"),
    ).firstOrNull(File::isFile) ?: error("HansLiveVoiceForegroundService.kt not found")
}
