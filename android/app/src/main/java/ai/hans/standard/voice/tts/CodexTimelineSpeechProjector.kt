package ai.hans.standard.voice.tts

import ai.hans.standard.localization.HansTextResolver

import ai.hans.standard.codex.AgentMessagePhase
import ai.hans.standard.integration.ClientSessionPhase
import ai.hans.standard.integration.ClientTimelineRole
import ai.hans.standard.integration.ClientTimelineItem
import ai.hans.standard.integration.CodexClientSnapshot
import ai.hans.standard.setup.HansSetupOutputSanitizer
import ai.hans.standard.text.AssistantMarkdown
import java.util.LinkedHashMap

/**
 * Converts monotonic Codex UI snapshots into monotonic TTS revisions. Existing
 * history at process startup and revisions observed while speech is disabled
 * are intentionally not replayed later.
 */
class CodexTimelineSpeechProjector(
    private val maxTrackedIds: Int = DEFAULT_MAX_TRACKED_IDS,
    private val text: HansTextResolver,
) {
    private val revisions = LinkedHashMap<String, Long>()
    private var baselineEstablished = false

    init {
        require(maxTrackedIds > 0)
    }

    @Synchronized
    fun accept(
        snapshot: CodexClientSnapshot,
        enabled: Boolean,
        speakAfterOrderExclusive: Long = Long.MIN_VALUE,
    ): List<TtsMessageRevision> {
        val hansItems = snapshot.timeline.filter { it.role == ClientTimelineRole.HANS }
        if (snapshot.sessionPhase !in setOf(ClientSessionPhase.READY, ClientSessionPhase.BUSY)) {
            // Recovery history can arrive after an earlier runtime generation already
            // established the TTS baseline. Absorb it while the session is non-interactive so
            // it cannot be replayed when READY returns.
            hansItems.forEach { revisions[it.id] = it.revision }
            trim(hansItems.mapTo(HashSet()) { it.id })
            return emptyList()
        }
        if (!baselineEstablished) {
            hansItems.forEach { revisions[it.id] = it.revision }
            baselineEstablished = true
            trim(hansItems.mapTo(HashSet()) { it.id })
            return emptyList()
        }

        val projected = ArrayList<TtsMessageRevision>()
        hansItems.forEach { item ->
            val previous = revisions[item.id]
            if (previous != null && item.revision <= previous) return@forEach
            revisions[item.id] = item.revision
            if (!enabled || item.order <= speakAfterOrderExclusive) return@forEach
            val kind = when (item.agentPhase) {
                AgentMessagePhase.FINAL_ANSWER -> TtsMessageKind.FINAL_OUTPUT
                AgentMessagePhase.COMMENTARY -> TtsMessageKind.INTERMEDIATE
                null -> if (item.complete) {
                    TtsMessageKind.FINAL_OUTPUT
                } else {
                    TtsMessageKind.INTERMEDIATE
                }
            }
            projected += TtsMessageRevision(
                messageId = TtsMessageId(item.id),
                revision = item.revision,
                text = AssistantMarkdown.parse(
                    HansSetupOutputSanitizer.sanitizeAssistantText(
                        item.text,
                        item.turnId != null && item.turnId in snapshot.setupTurnIds,
                        text,
                    ),
                    complete = item.complete,
                ).spokenText,
                kind = kind,
                isFinal = item.complete,
            )
        }
        trim(hansItems.mapTo(HashSet()) { it.id })
        return projected
    }

    private fun trim(visibleIds: Set<String>) {
        while (revisions.size > maxTrackedIds) {
            val removable = revisions.keys.firstOrNull { it !in visibleIds }
                ?: revisions.keys.first()
            revisions.remove(removable)
        }
    }

    companion object {
        const val DEFAULT_MAX_TRACKED_IDS = 2_048
    }
}
