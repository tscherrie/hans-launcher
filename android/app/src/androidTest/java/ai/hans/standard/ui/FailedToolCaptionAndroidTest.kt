package ai.hans.standard.ui

import android.content.res.Configuration
import android.os.LocaleList
import ai.hans.standard.codex.AccountPhase
import ai.hans.standard.codex.AccountUiSnapshot
import ai.hans.standard.codex.DeliveryUiSnapshot
import ai.hans.standard.codex.DispatchOptions
import ai.hans.standard.codex.SessionUiSnapshot
import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.integration.ClientSessionPhase
import ai.hans.standard.integration.ClientTimelineItem
import ai.hans.standard.integration.ClientTimelineRole
import ai.hans.standard.integration.ClientTimelineStatus
import ai.hans.standard.integration.CodexClientSnapshot
import ai.hans.standard.integration.DispatchSelection
import ai.hans.standard.localization.AndroidHansTextResolver
import ai.hans.standard.settings.HansSettings
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual packaged EN/DE resources and projector; no session, phone action or global locale change. */
class FailedToolCaptionAndroidTest {
    @Test fun failedToolTextUsesActionFailureRatherThanUnsentMessageInBothLanguages() {
        val original = "inspect_visual_ui\nvisual_capture_content_changed"
        languages.forEach { language ->
            val projected = project(language.locale, ClientTimelineRole.TOOL, original,
                ClientTimelineStatus.FAILED, complete = true)
            assertEquals("$original\n${language.failedAction}", projected.text)
            assertEquals(ChatMessageAuthor.SYSTEM, projected.author)
            assertTrue(projected.complete)
            assertFalse(projected.text.contains(language.notSent))
            assertFalse(projected.text.contains(language.running))
        }
    }

    @Test fun failedToolWithoutOutputNeverClaimsItIsStillRunningInEitherLanguage() {
        languages.forEach { language ->
            val failed = project(language.locale, ClientTimelineRole.TOOL, "  ",
                ClientTimelineStatus.FAILED, complete = true)
            assertEquals(language.failedAction, failed.text)
            assertEquals(ChatMessageAuthor.SYSTEM, failed.author)
            assertTrue(failed.complete)
            assertFalse(failed.text.contains(language.notSent))
            assertFalse(failed.text.contains(language.running))
            val running = project(language.locale, ClientTimelineRole.TOOL, "",
                ClientTimelineStatus.IN_PROGRESS, complete = false)
            assertEquals(language.running, running.text)
            assertFalse(running.complete)
        }
    }

    @Test fun failedUserMessageKeepsOriginalTextAndUnsentCaptionInBothLanguages() {
        val original = "My original message — unverändert — مرحبا"
        languages.forEach { language ->
            val projected = project(language.locale, ClientTimelineRole.USER, original,
                ClientTimelineStatus.FAILED, complete = true)
            assertEquals("$original\n${language.notSent}", projected.text)
            assertEquals(ChatMessageAuthor.USER, projected.author)
            assertTrue(projected.complete)
            assertFalse(projected.text.contains(language.failedAction))
            assertFalse(projected.text.contains(language.running))
        }
    }

    private fun project(locale: Locale, role: ClientTimelineRole, text: String,
        status: ClientTimelineStatus, complete: Boolean): ChatMessageUiModel {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(locale))
        })
        val client = CodexClientSnapshot(
            runtimePhase = ClientRuntimePhase.READY,
            sessionPhase = ClientSessionPhase.READY,
            generation = 1,
            session = SessionUiSnapshot(
                account = AccountUiSnapshot(AccountPhase.SIGNED_IN, null, null, null, null, null),
                currentThreadId = "caption-test-thread",
                threads = emptyList(),
                delivery = DeliveryUiSnapshot(1, 1, null, false),
            ),
            models = emptyList(),
            deviceCodeLogin = null,
            outboundTimeline = emptyList(),
            timeline = listOf(ClientTimelineItem("caption-test-item", role, text, 1, 0, complete, status)),
            pendingSelection = null,
            confirmedSelection = DispatchSelection(DispatchOptions.DEFAULT.model, DispatchOptions.DEFAULT.effort),
            problem = null,
        )
        return HansClientUiProjector.project(client, HansLocalUiState(), HansSettings(),
            AndroidHansTextResolver(context)).chat.messages.single()
    }

    private data class Language(val locale: Locale, val failedAction: String,
        val notSent: String, val running: String)

    private val languages = listOf(
        Language(Locale.ENGLISH, "Action failed.", "Not sent.", "Phone action in progress"),
        Language(Locale.GERMAN, "Aktion fehlgeschlagen.", "Nicht gesendet.", "Telefonaktion läuft"),
    )
}
