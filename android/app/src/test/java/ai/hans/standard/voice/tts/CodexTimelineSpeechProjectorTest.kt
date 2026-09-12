package ai.hans.standard.voice.tts

import ai.hans.standard.codex.AgentMessagePhase
import ai.hans.standard.codex.CodexSessionReducer
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.integration.ClientSessionPhase
import ai.hans.standard.integration.ClientTimelineItem
import ai.hans.standard.integration.ClientTimelineRole
import ai.hans.standard.integration.ClientTimelineStatus
import ai.hans.standard.integration.CodexClientSnapshot
import ai.hans.standard.integration.DispatchSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexTimelineSpeechProjectorTest {
    @Test
    fun recoveredHistoryFormsABaselineAndIsNeverReadAloud() {
        val projector = CodexTimelineSpeechProjector()
        val existing = hans("old", 4, "Earlier answer", complete = true)

        assertTrue(projector.accept(snapshot(ClientSessionPhase.RECOVERING_THREAD, existing), true).isEmpty())
        assertTrue(projector.accept(snapshot(ClientSessionPhase.READY, existing), true).isEmpty())
        assertTrue(projector.accept(snapshot(ClientSessionPhase.READY, existing), true).isEmpty())
    }

    @Test
    fun laterRuntimeRecoveryIsAbsorbedEvenAfterSpeechBaselineAlreadyExists() {
        val projector = CodexTimelineSpeechProjector()
        projector.accept(snapshot(ClientSessionPhase.READY), enabled = true)
        val recovered = hans("recovered-later", 1, "Nicht erneut vorlesen", complete = true)

        assertTrue(
            projector.accept(
                snapshot(ClientSessionPhase.RECOVERING_THREAD, recovered),
                enabled = true,
            ).isEmpty(),
        )
        assertTrue(
            projector.accept(snapshot(ClientSessionPhase.READY, recovered), enabled = true)
                .isEmpty(),
        )

        val live = hans("new-live", 1, "Das ist neu.", complete = true).copy(order = 20)
        val spoken = projector.accept(
            snapshot(ClientSessionPhase.READY, recovered, live),
            enabled = true,
        ).single()
        assertEquals("Das ist neu.", spoken.text)
    }

    @Test
    fun streamedRevisionsAreMonotonicAndKeepTheServerMessagePhase() {
        val projector = CodexTimelineSpeechProjector()
        projector.accept(snapshot(ClientSessionPhase.READY), enabled = true)

        val first = projector.accept(
            snapshot(
                ClientSessionPhase.BUSY,
                hans(
                    id = "answer",
                    revision = 1,
                    text = "Ich schaue kurz nach.",
                    complete = false,
                    phase = AgentMessagePhase.COMMENTARY,
                ),
            ),
            enabled = true,
        ).single()
        assertEquals(TtsMessageKind.INTERMEDIATE, first.kind)
        assertEquals(false, first.isFinal)

        assertTrue(
            projector.accept(
                snapshot(
                    ClientSessionPhase.BUSY,
                    hans(
                        "answer",
                        1,
                        "conflicting stale text",
                        false,
                        AgentMessagePhase.COMMENTARY,
                    ),
                ),
                enabled = true,
            ).isEmpty(),
        )

        val final = projector.accept(
            snapshot(
                ClientSessionPhase.READY,
                hans(
                    "answer",
                    2,
                    "Erledigt.",
                    true,
                    AgentMessagePhase.FINAL_ANSWER,
                ),
            ),
            enabled = true,
        ).single()
        assertEquals(TtsMessageKind.FINAL_OUTPUT, final.kind)
        assertEquals(true, final.isFinal)
    }

    @Test
    fun revisionsSeenWithoutCredentialAreNotReplayedWhenSpeechBecomesAvailable() {
        val projector = CodexTimelineSpeechProjector()
        projector.accept(snapshot(ClientSessionPhase.READY), enabled = false)
        val answer = hans("silent", 1, "Do not replay", complete = true)

        assertTrue(projector.accept(snapshot(ClientSessionPhase.READY, answer), false).isEmpty())
        assertTrue(projector.accept(snapshot(ClientSessionPhase.READY, answer), true).isEmpty())

        val update = projector.accept(
            snapshot(
                ClientSessionPhase.READY,
                answer.copy(revision = 2, text = "Only this update"),
            ),
            enabled = true,
        ).single()
        assertEquals("Only this update", update.text)
    }

    @Test
    fun userAndToolItemsNeverEnterSpeechProjection() {
        val projector = CodexTimelineSpeechProjector()
        projector.accept(snapshot(ClientSessionPhase.READY), enabled = true)
        val result = projector.accept(
            snapshot(
                ClientSessionPhase.READY,
                ClientTimelineItem(
                    id = "user",
                    role = ClientTimelineRole.USER,
                    text = "User text",
                    order = 1,
                    revision = 1,
                    complete = true,
                    status = ClientTimelineStatus.SENT,
                ),
                ClientTimelineItem(
                    id = "tool",
                    role = ClientTimelineRole.TOOL,
                    text = "https://private.example/path",
                    order = 2,
                    revision = 1,
                    complete = true,
                    status = ClientTimelineStatus.COMPLETE,
                ),
            ),
            enabled = true,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun dispatchBoundarySuppressesOlderTurnRevisionsWithoutReplayingThem() {
        val projector = CodexTimelineSpeechProjector()
        projector.accept(snapshot(ClientSessionPhase.READY), enabled = true)
        val old = hans("old", 1, "Old turn", complete = false).copy(order = 4)
        val current = hans("current", 1, "Current turn", complete = true).copy(order = 6)

        val first = projector.accept(
            snapshot(ClientSessionPhase.BUSY, old, current),
            enabled = true,
            speakAfterOrderExclusive = 5,
        )
        assertEquals(listOf("Current turn"), first.map { it.text })

        val updatedOld = old.copy(revision = 2, text = "Old turn continued")
        assertTrue(
            projector.accept(
                snapshot(ClientSessionPhase.READY, updatedOld, current),
                enabled = true,
                speakAfterOrderExclusive = 5,
            ).isEmpty(),
        )
        assertTrue(
            projector.accept(
                snapshot(ClientSessionPhase.READY, updatedOld, current),
                enabled = true,
            ).isEmpty(),
        )
    }

    @Test
    fun activeSetupSpeechNeverReceivesRawImplementationValues() {
        val projector = CodexTimelineSpeechProjector()
        projector.accept(snapshot(ClientSessionPhase.READY), enabled = true)

        val spoken = projector.accept(
            snapshot(
                ClientSessionPhase.READY,
                hans(
                    "setup-answer",
                    1,
                    "Der aktuelle Schritt ist `intro` und wartet noch auf deine Entscheidung. " +
                        "Soll ich die Hans-Einrichtung jetzt starten?",
                    complete = true,
                    turnId = "setup-turn",
                ),
            ).copy(setupTurnIds = setOf("setup-turn")),
            enabled = true,
        ).single()

        assertEquals(
            "Die Einrichtung ist bereit. Soll ich die Hans-Einrichtung jetzt starten?",
            spoken.text,
        )
    }

    @Test
    fun nonSetupSpeechKeepsCodeContentWithoutSpeakingBacktickMarkers() {
        val projector = CodexTimelineSpeechProjector()
        projector.accept(snapshot(ClientSessionPhase.READY), enabled = true)
        val original = "In der Dokumentation heißt das Beispiel `intro`."

        val spoken = projector.accept(
            snapshot(ClientSessionPhase.READY, hans("ordinary", 1, original, complete = true)),
            enabled = true,
        ).single()

        assertEquals("In der Dokumentation heißt das Beispiel intro.", spoken.text)
    }

    @Test
    fun assistantUrlProjectionDoesNotMutateTheSourceTimeline() {
        val projector = CodexTimelineSpeechProjector()
        projector.accept(snapshot(ClientSessionPhase.READY), enabled = true)
        val raw = hans("web-answer", 1, "Hier ist [die Route](https://maps.example/route).", complete = true)
        val source = snapshot(ClientSessionPhase.READY, raw)

        val spoken = projector.accept(source, enabled = true).single()

        assertEquals("Hier ist die Route.", spoken.text)
        assertEquals("Hier ist [die Route](https://maps.example/route).", source.timeline.single().text)
        assertEquals(raw, source.timeline.single())
    }

    @Test
    fun emptyFinalProjectionIsStillDeliveredSoItCanReleaseTheSpeechQueue() {
        val projector = CodexTimelineSpeechProjector()
        projector.accept(snapshot(ClientSessionPhase.READY), enabled = true)
        val raw = hans("empty-answer", 1, "", complete = false)
        val streaming = projector.accept(snapshot(ClientSessionPhase.BUSY, raw), enabled = true).single()
        val complete = projector.accept(
            snapshot(ClientSessionPhase.READY, raw.copy(revision = 2, complete = true)),
            enabled = true,
        ).single()

        assertEquals("", streaming.text)
        assertEquals(false, streaming.isFinal)
        assertEquals("", complete.text)
        assertEquals(true, complete.isFinal)
        assertEquals(2L, complete.revision)
    }

    @Test
    fun namedLinksAndMarkdownHaveOneReadableSpokenProjection() {
        val projector = CodexTimelineSpeechProjector()
        projector.accept(snapshot(ClientSessionPhase.READY), enabled = true)
        val source = hans(
            "formatted-answer", 1,
            "**Wichtig:** *Heute* findest du die `Route` bei " +
                "[Google Maps](https://maps.example/route?private=value).",
            complete = true,
        )

        val spoken = projector.accept(snapshot(ClientSessionPhase.READY, source), true).single()

        assertEquals("Wichtig: Heute findest du die Route bei Google Maps.", spoken.text)
        assertTrue(source.text.contains("**Wichtig:**"))
        assertTrue(source.text.contains("https://maps.example/route?private=value"))
    }

    @Test
    fun bareWebAddressSpeaksOnlyItsHonestSiteNameWhenItFinishes() {
        val projector = CodexTimelineSpeechProjector()
        projector.accept(snapshot(ClientSessionPhase.READY), enabled = true)
        val source = hans("web-site", 1, "https://example.org/private?q=value", complete = false)

        val streaming = projector.accept(snapshot(ClientSessionPhase.BUSY, source), true).single()
        val finished = projector.accept(
            snapshot(ClientSessionPhase.READY, source.copy(revision = 2, complete = true)), true,
        ).single()

        assertEquals("", streaming.text)
        assertEquals("example.org", finished.text)
        assertTrue(finished.isFinal)
    }

    private fun snapshot(
        phase: ClientSessionPhase,
        vararg timeline: ClientTimelineItem,
    ) = CodexClientSnapshot(
        runtimePhase = ClientRuntimePhase.READY,
        sessionPhase = phase,
        generation = 1,
        session = CodexSessionReducer().snapshot(),
        models = emptyList(),
        deviceCodeLogin = null,
        outboundTimeline = emptyList(),
        timeline = timeline.toList(),
        pendingSelection = null,
        confirmedSelection = DispatchSelection("gpt-5.6-luna", ReasoningEffort.MAX),
        problem = null,
    )

    private fun hans(
        id: String,
        revision: Long,
        text: String,
        complete: Boolean,
        phase: AgentMessagePhase? = AgentMessagePhase.FINAL_ANSWER,
        turnId: String? = null,
    ) = ClientTimelineItem(
        id = id,
        role = ClientTimelineRole.HANS,
        text = text,
        order = 1,
        revision = revision,
        complete = complete,
        status = if (complete) ClientTimelineStatus.COMPLETE else ClientTimelineStatus.STREAMING,
        agentPhase = phase,
        turnId = turnId,
    )
}
