package ai.hans.standard.ui

import ai.hans.standard.localization.TestResourceTextResolver
import ai.hans.standard.settings.HansSettings
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictationTranscriptPlaceholderTest {
    @Test fun currentSessionWaitShowsDotsInAllActiveDictationPhases() {
        listOf(DictationUiStatus.PREPARING, DictationUiStatus.LISTENING,
            DictationUiStatus.FINALIZING).forEach { status ->
            assertTrue(ChatUiState(dictationStatus = status,
                dictationAwaitingFirstTranscript = true).showDictationTranscriptPlaceholder())
        }
    }

    @Test fun endedFailedOrUnconfirmedSessionNeverShowsPhantomInput() {
        listOf(null, DictationUiStatus.FAILED, DictationUiStatus.WAITING_TO_SEND).forEach { status ->
            assertFalse(ChatUiState(dictationStatus = status,
                dictationAwaitingFirstTranscript = true).showDictationTranscriptPlaceholder())
        }
        assertFalse(ChatUiState(dictationStatus = DictationUiStatus.PREPARING)
            .showDictationTranscriptPlaceholder())
    }

    @Test fun firstTextOrOwnSessionEventRemovesDotsWithoutDependingOnHistory() {
        val history = listOf(ChatMessageUiModel("old", ChatMessageAuthor.USER, "Previous request"))
        val pending = ChatUiState(messages = history, dictationStatus = DictationUiStatus.LISTENING,
            dictationAwaitingFirstTranscript = true)
        assertTrue(pending.showDictationTranscriptPlaceholder())
        assertTrue(pending.copy(dictationPreview = " \n\t").showDictationTranscriptPlaceholder())
        assertFalse(pending.copy(dictationPreview = "Current partial").showDictationTranscriptPlaceholder())
        assertFalse(pending.copy(dictationAwaitingFirstTranscript = false)
            .showDictationTranscriptPlaceholder())
        assertEquals(history, pending.messages)
        assertEquals("", pending.composer.text)
    }

    @Test fun telephoneAndDictationRemainSeparateEvenWithStalePlaceholderMetadata() {
        LiveVoiceUiStatus.entries.forEach { status ->
            assertFalse(ChatUiState(dictationStatus = DictationUiStatus.PREPARING,
                dictationAwaitingFirstTranscript = true, liveVoiceStatus = status)
                .showDictationTranscriptPlaceholder())
        }
    }

    @Test fun projectionPreservesPendingTranscriptAsPresentationOnly() {
        val local = HansLocalUiState(dictationStatus = DictationUiStatus.PREPARING,
            dictationAwaitingFirstTranscript = true, text = "Unsent keyboard draft")
        val chat = HansClientUiProjector.project(null, local, HansSettings(),
            TestResourceTextResolver(Locale.ENGLISH)).chat
        assertTrue(chat.showDictationTranscriptPlaceholder())
        assertTrue(chat.messages.isEmpty())
        assertEquals(local.text, chat.composer.text)
        assertTrue(chat.pendingDictations.isEmpty())
    }

    @Test fun acceptedNewDictationReplacesOnlyAnObsoletePhoneFailure() {
        listOf(DictationUiStatus.PREPARING, DictationUiStatus.LISTENING,
            DictationUiStatus.FINALIZING).forEach { phase ->
            assertEquals(null, LiveVoiceUiStatus.FAILED.afterDictationPublication(phase))
            LiveVoiceUiStatus.entries.filter { it != LiveVoiceUiStatus.FAILED }.forEach { phone ->
                assertEquals(phone, phone.afterDictationPublication(phase))
            }
        }
        listOf(null, DictationUiStatus.FAILED, DictationUiStatus.WAITING_TO_SEND).forEach { phase ->
            assertEquals(LiveVoiceUiStatus.FAILED,
                LiveVoiceUiStatus.FAILED.afterDictationPublication(phase))
        }
    }

    @Test fun lateFailedPhoneReplayCannotHideAcceptedDictationAfterActivityRecreation() {
        // Launcher subscribes to the dictation runtime before the Live observer hub. The
        // latter can still replay the previous PHONE failure before native startup begins.
        val accepted = ChatUiState(dictationStatus = DictationUiStatus.PREPARING,
            dictationAwaitingFirstTranscript = true)
        val replayed = accepted.copy(liveVoiceStatus =
            LiveVoiceUiStatus.FAILED.afterDictationPublication(accepted.dictationStatus))
        assertTrue(replayed.showDictationTranscriptPlaceholder())
        assertEquals(null, replayed.liveVoiceStatus)
        assertTrue(replayed.messages.isEmpty())
        assertEquals("", replayed.composer.text)

        // Bind the regression to the real terminal-PHONE integration branch, rather than
        // only synthesizing the already reconciled UI state used above.
        val launcher = sequenceOf(File("src/main/java/ai/hans/standard/LauncherActivity.kt"),
            File("android/app/src/main/java/ai/hans/standard/LauncherActivity.kt"))
            .first(File::isFile).readText()
            .substringAfter("private fun acceptLiveVoiceSnapshot(")
            .substringBefore("private fun acceptLiveVoiceTranscript(")
        assertTrue(launcher.contains("LiveVoicePhase.FAILED -> " +
            "LiveVoiceUiStatus.FAILED.afterDictationPublication(localUi.dictationStatus)"))
        assertTrue(launcher.contains("LiveVoicePhase.LISTENING -> LiveVoiceUiStatus.LISTENING"))
    }
}
