package ai.hans.standard.ui

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
import ai.hans.standard.integration.createNotificationReport
import ai.hans.standard.localization.AndroidHansTextResolver
import ai.hans.standard.notifications.hooks.AtomicFileNotificationEventStorage
import ai.hans.standard.notifications.hooks.NotificationEventLedger
import ai.hans.standard.phone.notifications.NotificationEventKind
import ai.hans.standard.phone.notifications.NotificationInboxEvent
import ai.hans.standard.phone.notifications.NotificationSnapshot
import ai.hans.standard.settings.HansSettings
import android.content.Context
import android.util.AtomicFile
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Real AtomicFile and chat rendering for admitted notification text, with no audio or model call. */
class NotificationChatHistoryTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val fileName = "notification-chat-history-${System.nanoTime()}.json"
    private val text = "Your appointment starts in ten minutes."

    @After fun cleanFixture() {
        AtomicFile(File(context.noBackupFilesDir, fileName)).delete()
        AtomicFile(File(context.noBackupFilesDir, "$fileName.initialized")).delete()
    }

    @Test fun dismissedImportantNoticeRemainsOnceAfterReopenAndPrivacyClearRemovesIt() {
        val original = ledger()
        assertTrue(original.activateAfter(0))
        assertTrue(original.accept(event(1, NotificationEventKind.POSTED)))
        val claim = assertNotNullValue(original.claim(original.pending().single().eventId, "main-thread"))
        assertTrue(original.accepted(claim, "main-thread", "notification-turn"))
        val report = createNotificationReport("hans-notice-history", text, emptyList())
        assertEquals(report, original.commitReport(claim.eventId, "main-thread", "notification-turn", report))
        assertTrue(original.accept(event(2, NotificationEventKind.REMOVED)))

        val reopened = ledger()
        val retained = reopened.reports("main-thread")
        assertEquals(listOf(report), retained)
        assertTrue(reopened.reports("other-thread").isEmpty())
        assertFalse(reopened.hasLiveReportSource(claim.eventId, "main-thread", "ai.hans.fixture", "fixture-key"))
        assertEquals(0L, reopened.status().outcomes.speechQueuedCount)
        val state = mutableStateOf(project(snapshot(), retained.map { notice(it.id, it.text, it.timelineAnchorId) }))
        compose.setGermanContent { MaterialTheme { ChatScreen(state.value, callbacks()) } }
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onAllNodesWithText(text).assertCountEquals(1)

        compose.runOnIdle {
            // Another read/recomposition must reuse the same durable presentation identity.
            state.value = project(snapshot(), ledger().reports("main-thread").map { notice(it.id, it.text, it.timelineAnchorId) })
        }
        compose.onAllNodesWithText(text).assertCountEquals(1)
        compose.runOnIdle {
            assertTrue(reopened.clearPrivateData())
            state.value = project(snapshot(), ledger().reports("main-thread").map { notice(it.id, it.text, it.timelineAnchorId) })
        }
        compose.onNodeWithText(text).assertDoesNotExist()
    }

    @Test fun freshImportantNoticeIsVisibleAtTailAfterRunningToolActivity() {
        val history = (1L..24L).map { order -> ClientTimelineItem("older-$order", ClientTimelineRole.HANS,
            "Earlier conversation message $order", order, 1, true, ClientTimelineStatus.COMPLETE) }
        val tool = ClientTimelineItem("current-tool", ClientTimelineRole.TOOL, "Checking calendar", 25, 1,
            false, ClientTimelineStatus.IN_PROGRESS)
        val current = snapshot(*(history + tool).toTypedArray())
        val state = mutableStateOf(project(current, emptyList()))
        compose.setGermanContent { MaterialTheme { ChatScreen(state.value, callbacks()) } }
        compose.runOnIdle {
            val report = createNotificationReport("hans-notice-new", text, current.timeline)
            state.value = project(current, listOf(notice(report.id, report.text, report.timelineAnchorId)))
        }
        compose.onNodeWithText(text).assertIsDisplayed()
        compose.onAllNodesWithText(text).assertCountEquals(1)
    }

    private fun ledger() = NotificationEventLedger(AtomicFileNotificationEventStorage(context, fileName))

    private fun event(sequence: Long, kind: NotificationEventKind) = NotificationInboxEvent(
        sequence = sequence,
        kind = kind,
        observedAtEpochMillis = System.currentTimeMillis(),
        removalReason = null,
        snapshot = NotificationSnapshot(
            androidKey = "fixture-key",
            packageName = "ai.hans.fixture",
            title = "Fixture",
            text = "Synthetic private source text",
            postTimeEpochMillis = System.currentTimeMillis(),
            notificationWhenEpochMillis = System.currentTimeMillis(),
            subtext = "",
            category = "message",
            channelId = "fixture-channel",
            ongoing = false,
            clearable = true,
            actions = emptyList(),
        ),
    )

    private fun notice(id: String, content: String, anchor: String?) = ChatMessageUiModel(
        id, ChatMessageAuthor.HANS, content, localTimelineAnchorId = anchor, localArrivalOrder = 1)

    private fun project(client: CodexClientSnapshot, reports: List<ChatMessageUiModel>) =
        HansClientUiProjector.project(client, HansLocalUiState(notificationReportMessages = reports,
            notificationReportThreadId = "main-thread"), HansSettings(), AndroidHansTextResolver(germanUiTestContext())).chat

    private fun snapshot(vararg timeline: ClientTimelineItem) = CodexClientSnapshot(
        ClientRuntimePhase.READY, ClientSessionPhase.READY, 1,
        SessionUiSnapshot(AccountUiSnapshot(AccountPhase.SIGNED_IN, null, null, null, null, null),
            "main-thread", emptyList(), DeliveryUiSnapshot(1, 1, null, false)),
        emptyList(), null, emptyList(), timeline.toList(), null,
        DispatchSelection(DispatchOptions.DEFAULT.model, DispatchOptions.DEFAULT.effort), null)

    private fun callbacks() = ChatUiCallbacks(onComposerChanged = {}, onSend = {}, onChooseMedia = {},
        onRemoveAttachment = {}, onOpenApps = {}, onOpenPlugins = {}, onOpenSettings = {}, onToggleLiveVoice = {})

    private fun <T : Any> assertNotNullValue(value: T?): T {
        assertNotNull(value)
        return checkNotNull(value)
    }
}
