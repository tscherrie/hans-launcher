package ai.hans.standard.ui

import ai.hans.standard.integration.OutboundMessageStatus
import ai.hans.standard.integration.OutboundUserMessageUi
import ai.hans.standard.network.InternetSnapshot
import ai.hans.standard.network.InternetStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingComposerDispatchTest {
    private val attachment = HansPendingAttachment(
        id = "attachment-1",
        label = "Bild",
        absolutePath = "/private/image.jpg",
        importId = "import-1",
    )
    private val pending = PendingComposerDispatch(
        messageId = "composer-1",
        draftText = "Meine Nachricht",
        attachments = listOf(attachment),
    )
    private val local = HansLocalUiState(
        text = "Meine Nachricht",
        attachments = listOf(attachment),
        pendingComposerMessageId = pending.messageId,
    )

    @Test
    fun offlineSubmissionPreservesExactDraftAndAttachmentsWithoutCreatingPendingSend() {
        val draft = local.copy(pendingComposerMessageId = null)
        val rejected = checkNotNull(
            blockOfflineComposerSubmission(draft, InternetSnapshot(InternetStatus.OFFLINE, 1)),
        )
        assertEquals(draft.text, rejected.text)
        assertEquals(draft.attachments, rejected.attachments)
        assertEquals(null, rejected.pendingComposerMessageId)
        assertTrue(rejected.connectionFailureMessage.contains("Nicht gesendet"))
    }

    @Test
    fun reconnectOnlyChangesAvailabilityAndDoesNotConsumeOrResendDraft() {
        val draft = local.copy(pendingComposerMessageId = null)
        val offline = checkNotNull(
            blockOfflineComposerSubmission(draft, InternetSnapshot(InternetStatus.OFFLINE, 1)),
        )
        val online = offline.copy(internet = InternetSnapshot(InternetStatus.ONLINE, 2))
        assertEquals(null, blockOfflineComposerSubmission(online, online.internet))
        assertEquals(draft.text, online.text)
        assertEquals(draft.attachments, online.attachments)
        assertEquals(null, online.pendingComposerMessageId)
    }

    @Test
    fun unknownAndUnvalidatedVpnAllowExplicitAttemptWhileBlockedAppDoesNot() {
        assertEquals(null, blockOfflineComposerSubmission(local, InternetSnapshot(InternetStatus.UNKNOWN)))
        assertEquals(null, blockOfflineComposerSubmission(local, InternetSnapshot(InternetStatus.LIMITED)))
        assertTrue(blockOfflineComposerSubmission(local, InternetSnapshot(InternetStatus.BLOCKED)) != null)
    }

    @Test
    fun pendingOrUncorrelatedSentKeepsDraftAttachmentsAndSubmitLock() {
        assertSame(
            ComposerDispatchReconciliation.Waiting,
            reconcileComposerDispatch(local, pending, listOf(outbound(OutboundMessageStatus.PENDING))),
        )
        assertSame(
            ComposerDispatchReconciliation.Waiting,
            reconcileComposerDispatch(
                local,
                pending,
                listOf(outbound(OutboundMessageStatus.SENT, turnId = null)),
            ),
        )
        assertEquals("composer-1", local.pendingComposerMessageId)
        assertEquals("Meine Nachricht", local.text)
        assertEquals(listOf(attachment), local.attachments)
    }

    @Test
    fun asynchronousRejectUnlocksButRetainsExactDraftAndAttachments() {
        val result = reconcileComposerDispatch(
            local,
            pending,
            listOf(outbound(OutboundMessageStatus.FAILED)),
        ) as ComposerDispatchReconciliation.Rejected

        assertEquals(null, result.local.pendingComposerMessageId)
        assertEquals("Meine Nachricht", result.local.text)
        assertEquals(listOf(attachment), result.local.attachments)
    }

    @Test
    fun alreadySentSnapshotCommitsExactlyOnceAndMakesNextSteerPossible() {
        val result = reconcileComposerDispatch(
            local,
            pending,
            listOf(outbound(OutboundMessageStatus.SENT, turnId = "turn-1")),
        ) as ComposerDispatchReconciliation.Committed

        assertEquals(null, result.local.pendingComposerMessageId)
        assertEquals("", result.local.text)
        assertTrue(result.local.attachments.isEmpty())
        assertEquals("thread-1", result.threadId)
        assertEquals("turn-1", result.turnId)
        assertEquals(setOf("import-1"), result.consumedImportIds)
    }

    private fun outbound(
        status: OutboundMessageStatus,
        turnId: String? = if (status == OutboundMessageStatus.SENT) "turn-1" else null,
    ) = OutboundUserMessageUi(
        clientUserMessageId = pending.messageId,
        threadId = "thread-1",
        displayText = pending.draftText,
        status = status,
        retryable = status == OutboundMessageStatus.FAILED,
        turnId = turnId,
    )
}
