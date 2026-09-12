package ai.hans.standard.integration

import ai.hans.standard.codex.AccountPhase
import ai.hans.standard.codex.AccountUiSnapshot
import ai.hans.standard.codex.CodexSessionReducer
import ai.hans.standard.codex.DeliveryUiSnapshot
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.voice.realtime.LiveVoicePhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveVoiceContextRefreshGateTest {
    @Test
    fun manyStreamingRevisionsProduceOnlyOneRefreshAfterCompletion() {
        val gate = LiveVoiceContextRefreshGate()
        val baseline = item(
            id = "user-one",
            text = "Start",
            order = 1,
            complete = true,
            status = ClientTimelineStatus.SENT,
        )
        assertTrue(gate.shouldRefresh(LiveVoicePhase.LISTENING, snapshot(baseline), 1))

        val refreshes = (1L..50L).count { revision ->
            gate.shouldRefresh(
                LiveVoicePhase.HANS_SPEAKING,
                snapshot(
                    baseline,
                    item(
                        id = "hans-stream",
                        text = "Teil $revision",
                        order = 2,
                        revision = revision,
                        complete = false,
                        status = ClientTimelineStatus.STREAMING,
                        role = ClientTimelineRole.HANS,
                    ),
                ),
                1,
            )
        }
        assertEquals(0, refreshes)

        val completed = item(
            id = "hans-stream",
            text = "Fertige Antwort",
            order = 2,
            revision = 51,
            complete = true,
            status = ClientTimelineStatus.COMPLETE,
            role = ClientTimelineRole.HANS,
        )
        assertTrue(gate.shouldRefresh(LiveVoicePhase.LISTENING, snapshot(baseline, completed), 1))
        assertFalse(gate.shouldRefresh(LiveVoicePhase.LISTENING, snapshot(baseline, completed), 1))
    }

    @Test
    fun oldFailedMessageDoesNotBlockANewerCompletedMessage() {
        val gate = LiveVoiceContextRefreshGate()
        val failed = item(
            id = "old-failed",
            text = "Alt",
            order = 1,
            complete = true,
            status = ClientTimelineStatus.FAILED,
        )
        val completed = item(
            id = "new-complete",
            text = "Neu",
            order = 2,
            complete = true,
            status = ClientTimelineStatus.COMPLETE,
            role = ClientTimelineRole.HANS,
        )

        assertTrue(
            gate.shouldRefresh(
                LiveVoicePhase.LISTENING,
                snapshot(failed, completed),
                setupRevision = 2,
            ),
        )
    }

    @Test
    fun latestFailedItemDoesNotBlockWorkflowThreadOrConfirmedProfileRefreshes() {
        val gate = LiveVoiceContextRefreshGate()
        val stable = item(
            id = "stable",
            text = "Fertig",
            order = 1,
            complete = true,
            status = ClientTimelineStatus.COMPLETE,
            role = ClientTimelineRole.HANS,
        )
        val failed = item(
            id = "failed-later",
            text = "Fehlgeschlagen",
            order = 2,
            complete = true,
            status = ClientTimelineStatus.FAILED,
            role = ClientTimelineRole.HANS,
        )
        val emptyProfile = LiveVoiceProfileContext(revision = 0, confirmedSummaryDigest = null)
        val confirmedProfile = LiveVoiceProfileContext(
            revision = 7,
            confirmedSummaryDigest = "a".repeat(64),
        )

        assertTrue(
            gate.shouldRefresh(
                LiveVoicePhase.LISTENING,
                snapshot(stable, failed),
                setupRevision = 1,
                profileContext = emptyProfile,
            ),
        )
        assertTrue(
            gate.shouldRefresh(
                LiveVoicePhase.LISTENING,
                snapshot(stable, failed),
                setupRevision = 2,
                profileContext = emptyProfile,
            ),
        )
        assertTrue(
            gate.shouldRefresh(
                LiveVoicePhase.LISTENING,
                snapshot(stable, failed, threadId = "thread-two"),
                setupRevision = 2,
                profileContext = emptyProfile,
            ),
        )
        assertTrue(
            gate.shouldRefresh(
                LiveVoicePhase.LISTENING,
                snapshot(stable, failed, threadId = "thread-two"),
                setupRevision = 2,
                profileContext = confirmedProfile,
            ),
        )
        assertTrue(
            gate.shouldRefresh(
                LiveVoicePhase.LISTENING,
                snapshot(stable, failed, threadId = "thread-two"),
                setupRevision = 2,
                profileContext = emptyProfile,
            ),
        )
    }

    @Test
    fun workflowChangeWhileUserSpeaksIsForwardedForBoundaryBuffering() {
        val gate = LiveVoiceContextRefreshGate()
        val stable = item(
            id = "stable",
            text = "Aktueller Kontext",
            order = 1,
            complete = true,
            status = ClientTimelineStatus.COMPLETE,
            role = ClientTimelineRole.HANS,
        )

        assertTrue(
            gate.shouldRefresh(
                LiveVoicePhase.USER_SPEAKING,
                snapshot(stable),
                setupRevision = 3,
            ),
        )
        assertFalse(
            gate.shouldRefresh(
                LiveVoicePhase.USER_SPEAKING,
                snapshot(stable),
                setupRevision = 3,
            ),
        )
    }

    private fun item(
        id: String,
        text: String,
        order: Long,
        revision: Long = 1,
        complete: Boolean,
        status: ClientTimelineStatus,
        role: ClientTimelineRole = ClientTimelineRole.USER,
    ) = ClientTimelineItem(
        id = id,
        role = role,
        text = text,
        order = order,
        revision = revision,
        complete = complete,
        status = status,
    )

    private fun snapshot(
        vararg timeline: ClientTimelineItem,
        threadId: String = "thread-one",
    ) = CodexClientSnapshot(
        runtimePhase = ClientRuntimePhase.READY,
        sessionPhase = ClientSessionPhase.READY,
        generation = 1,
        session = CodexSessionReducer().snapshot().copy(
            account = AccountUiSnapshot(
                phase = AccountPhase.UNKNOWN,
                identity = null,
                authMode = null,
                planType = null,
                pendingLoginId = null,
                error = null,
            ),
            currentThreadId = threadId,
            delivery = DeliveryUiSnapshot(
                generation = 1,
                lastSequence = 1,
                pendingGeneration = null,
                rehydrationRequired = false,
            ),
        ),
        models = emptyList(),
        deviceCodeLogin = null,
        outboundTimeline = emptyList(),
        timeline = timeline.toList(),
        pendingSelection = null,
        confirmedSelection = DispatchSelection("gpt-5.6-luna", ReasoningEffort.MAX),
        problem = null,
    )
}
