package ai.hans.standard

import ai.hans.standard.codex.AgentMessagePhase
import ai.hans.standard.codex.CodexInput
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.codex.TurnStatus
import ai.hans.standard.integration.ClientSessionPhase
import ai.hans.standard.integration.ClientTimelineRole
import ai.hans.standard.integration.CodexClientObserver
import ai.hans.standard.integration.CodexClientSnapshot
import ai.hans.standard.integration.DispatchSelection
import ai.hans.standard.integration.OutboundMessageStatus
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit live regression for the launcher setting that previously changed only its label.
 *
 * Unlike a sequential model smoke, this fixture changes both model and reasoning while the first
 * turn is still running. It accepts the switch only after App Server correlates a distinct second
 * turn and [CodexClientSnapshot.confirmedSelection] reports Luna/Max for that accepted turn.
 */
@RunWith(AndroidJUnit4::class)
class LoggedInCodexModelSwitchTest {
    @Test
    fun lunaMaxIsServerConfirmedAfterSwitchingDuringAnActiveSolUltraTurn() {
        assumeTrue(
            "Live model switching spends account quota and requires an authenticated runtime.",
            InstrumentationRegistry.getArguments().getString(ARG_RUN_LIVE_SWITCH) == "true",
        )
        val host = ApplicationProvider.getApplicationContext<HansApplication>().sessionHost
        val solUltra = DispatchSelection("gpt-5.6-sol", ReasoningEffort.ULTRA)
        val lunaMax = DispatchSelection("gpt-5.6-luna", ReasoningEffort.MAX)
        try {
            awaitSnapshot(host, 90, TimeUnit.SECONDS, "Codex session did not become ready") {
                it.sessionPhase == ClientSessionPhase.READY
            }

            val firstMessageId = host.dispatch(
                input = listOf(
                    CodexInput.Text(
                        "Dies ist ein automatischer Hans-Modellwechseltest. Führe jetzt exakt " +
                            "den harmlosen Terminalbefehl `/system/bin/sleep 30` aus und antworte " +
                            "erst danach mit einem kurzen Satz.",
                    ),
                ),
                selection = solUltra,
                readResponseAloud = false,
            )
            assertNotNull("Codex rejected the initial Sol/Ultra turn", firstMessageId)

            val activeFirst = awaitSnapshot(
                host,
                2,
                TimeUnit.MINUTES,
                "Sol/Ultra never became an accepted active turn",
            ) { snapshot ->
                val outbound = snapshot.outboundTimeline.firstOrNull {
                    it.clientUserMessageId == firstMessageId
                }
                snapshot.sessionPhase == ClientSessionPhase.BUSY &&
                    snapshot.confirmedSelection == solUltra &&
                    outbound?.status == OutboundMessageStatus.SENT &&
                    outbound.turnId != null
            }
            val firstTurnId = checkNotNull(
                activeFirst.outboundTimeline.first {
                    it.clientUserMessageId == firstMessageId
                }.turnId,
            )

            // This dispatch happens while the first server-correlated turn is still BUSY. Because
            // model/effort differ, production dispatch policy must interrupt the boundary and
            // start a fresh turn instead of steering the old Sol/Ultra turn.
            val secondMessageId = host.dispatch(
                input = listOf(
                    CodexInput.Text(
                        "Der laufende Modellwechseltest ist jetzt auf Luna/Max umgestellt. " +
                            "Antworte nur mit: Luna Max bestätigt.",
                    ),
                ),
                selection = lunaMax,
                readResponseAloud = false,
            )
            assertNotNull("Codex rejected the active-turn Luna/Max switch", secondMessageId)
            assertNotEquals(firstMessageId, secondMessageId)

            val acceptedSecond = awaitSnapshot(
                host,
                2,
                TimeUnit.MINUTES,
                "App Server never accepted a distinct Luna/Max turn",
            ) { snapshot ->
                val outbound = snapshot.outboundTimeline.firstOrNull {
                    it.clientUserMessageId == secondMessageId
                }
                snapshot.confirmedSelection == lunaMax &&
                    snapshot.pendingSelection == null &&
                    outbound?.status == OutboundMessageStatus.SENT &&
                    outbound.turnId != null &&
                    outbound.turnId != firstTurnId
            }
            val secondTurnId = checkNotNull(
                acceptedSecond.outboundTimeline.first {
                    it.clientUserMessageId == secondMessageId
                }.turnId,
            )
            assertNotEquals("The model switch reused the active Sol turn", firstTurnId, secondTurnId)
            assertEquals("gpt-5.6-luna", acceptedSecond.confirmedSelection.model)
            assertEquals(ReasoningEffort.MAX, acceptedSecond.confirmedSelection.effort)

            val completedSecond = awaitSnapshot(
                host,
                5,
                TimeUnit.MINUTES,
                "No terminal Luna/Max response arrived for the accepted second turn",
            ) { snapshot ->
                snapshot.sessionPhase == ClientSessionPhase.READY &&
                    snapshot.confirmedSelection == lunaMax &&
                    snapshot.timeline.any { item ->
                        item.turnId == secondTurnId &&
                            item.role == ClientTimelineRole.HANS &&
                            item.agentPhase == AgentMessagePhase.FINAL_ANSWER &&
                            item.complete &&
                            item.text.isNotBlank()
                    } &&
                    snapshot.terminalTurns.any {
                        it.turnId == secondTurnId && it.status == TurnStatus.COMPLETED
                    }
            }
            assertEquals("gpt-5.6-luna", completedSecond.confirmedSelection.model)
            assertEquals(ReasoningEffort.MAX, completedSecond.confirmedSelection.effort)
        } finally {
            if (host.snapshot()?.sessionPhase == ClientSessionPhase.BUSY) {
                host.interrupt()
            }
        }
    }

    private fun awaitSnapshot(
        host: ai.hans.standard.integration.AndroidCodexSessionHost,
        timeout: Long,
        unit: TimeUnit,
        failureMessage: String,
        predicate: (CodexClientSnapshot) -> Boolean,
    ): CodexClientSnapshot {
        val matched = AtomicReference<CodexClientSnapshot>()
        val latch = CountDownLatch(1)
        val observer = CodexClientObserver { snapshot ->
            if (predicate(snapshot) && matched.compareAndSet(null, snapshot)) latch.countDown()
        }
        host.addObserver(observer)
        try {
            val arrived = latch.await(timeout, unit)
            assertTrue(
                "$failureMessage; " + snapshotDiagnostic(host.snapshot()),
                arrived,
            )
            return checkNotNull(matched.get())
        } finally {
            host.removeObserver(observer)
        }
    }

    private fun snapshotDiagnostic(snapshot: CodexClientSnapshot?): String =
        if (snapshot == null) {
            "snapshot=absent"
        } else {
            "phase=${snapshot.sessionPhase}, problem=${snapshot.problem?.code}, " +
                "confirmed=${snapshot.confirmedSelection.model}/" +
                snapshot.confirmedSelection.effort.wireValue
        }

    private companion object {
        const val ARG_RUN_LIVE_SWITCH = "runLiveCodexModelSwitch"
    }
}
