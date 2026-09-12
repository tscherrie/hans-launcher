package ai.hans.standard.ui

import ai.hans.standard.integration.OutboundMessageStatus
import ai.hans.standard.integration.OutboundUserMessageUi
import ai.hans.standard.network.InternetSnapshot

/** Null allows an explicit attempt; an offline rejection never consumes or submits the draft. */
internal fun blockOfflineComposerSubmission(
    local: HansLocalUiState,
    internet: InternetSnapshot,
    message: String = "Nicht gesendet. Dein Text und deine Anhänge bleiben im Eingabefeld erhalten.",
): HansLocalUiState? = if (internet.status.permitsExplicitRequest) {
    null
} else {
    local.copy(
        internet = internet,
        connectionFailureMessage = message,
        revision = local.revision + 1,
    )
}

/** Exact UI draft held until the App Server correlates the submitted message to a real turn. */
internal data class PendingComposerDispatch(
    val messageId: String,
    val draftText: String,
    val attachments: List<HansPendingAttachment>,
)

internal sealed interface ComposerDispatchReconciliation {
    data object Waiting : ComposerDispatchReconciliation
    data class Rejected(val local: HansLocalUiState) : ComposerDispatchReconciliation
    data class Committed(
        val local: HansLocalUiState,
        /** Exact thread/turn pair that owns the media until its terminal receipt arrives. */
        val threadId: String,
        val turnId: String,
        val consumedImportIds: Set<String>,
    ) : ComposerDispatchReconciliation
}

internal fun reconcileComposerDispatch(
    local: HansLocalUiState,
    pending: PendingComposerDispatch,
    outbound: List<OutboundUserMessageUi>,
): ComposerDispatchReconciliation {
    val exact = outbound.firstOrNull { it.clientUserMessageId == pending.messageId }
        ?: return ComposerDispatchReconciliation.Waiting
    return when (exact.status) {
        OutboundMessageStatus.PENDING -> ComposerDispatchReconciliation.Waiting
        OutboundMessageStatus.FAILED -> ComposerDispatchReconciliation.Rejected(
            local.copy(
                pendingComposerMessageId = null,
                revision = local.revision + 1,
            ),
        )
        OutboundMessageStatus.SENT -> {
            val turnId = exact.turnId?.takeIf(String::isNotBlank)
                ?: return ComposerDispatchReconciliation.Waiting
            val dispatchedAttachmentIds =
                pending.attachments.mapTo(mutableSetOf(), HansPendingAttachment::id)
            ComposerDispatchReconciliation.Committed(
                local = local.copy(
                    text = local.text.takeUnless { it == pending.draftText }.orEmpty(),
                    attachments = local.attachments.filterNot { it.id in dispatchedAttachmentIds },
                    pendingComposerMessageId = null,
                    revision = local.revision + 1,
                ),
                threadId = exact.threadId,
                turnId = turnId,
                consumedImportIds = pending.attachments
                    .mapNotNullTo(mutableSetOf(), HansPendingAttachment::importId),
            )
        }
    }
}
