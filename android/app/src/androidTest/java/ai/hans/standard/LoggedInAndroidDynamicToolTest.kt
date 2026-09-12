package ai.hans.standard

import ai.hans.standard.codex.AgentMessagePhase
import ai.hans.standard.codex.CodexInput
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.codex.TurnStatus
import ai.hans.standard.integration.ClientSessionPhase
import ai.hans.standard.integration.ClientTimelineItem
import ai.hans.standard.integration.ClientTimelineRole
import ai.hans.standard.integration.ClientTimelineStatus
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
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit real-account proof that the packaged App Server invokes a Hans Android tool. */
@RunWith(AndroidJUnit4::class)
class LoggedInAndroidDynamicToolTest {
    @Test
    fun readBatteryProducesCorrelatedToolReceiptAndCompletedAnswer() {
        assumeTrue(
            "Live dynamic-tool acceptance spends account quota and requires an authenticated runtime.",
            InstrumentationRegistry.getArguments()
                .getString(ARG_RUN_LIVE_ANDROID_TOOL) == "true",
        )
        val host = ApplicationProvider.getApplicationContext<HansApplication>().sessionHost
        val lunaMax = DispatchSelection("gpt-5.6-luna", ReasoningEffort.MAX)
        try {
            awaitSnapshot(host, 90, TimeUnit.SECONDS, "Codex session did not become ready") {
                it.sessionPhase == ClientSessionPhase.READY
            }
            val messageId = host.dispatch(
                input = listOf(
                    CodexInput.Text(
                        "Dies ist ein automatischer Hans-Gerätetest. Verwende zwingend genau das " +
                            "dynamische Telefonwerkzeug `android.read_battery`; verwende kein " +
                            "Shell-, MCP- oder anderes Werkzeug. Antworte danach in einem kurzen " +
                            "natürlichen Satz, dass der Batteriestatus gelesen wurde.",
                    ),
                ),
                selection = lunaMax,
                readResponseAloud = false,
            )
            assertNotNull("Codex rejected the Android dynamic-tool test turn", messageId)

            val accepted = awaitSnapshot(
                host,
                2,
                TimeUnit.MINUTES,
                "App Server never accepted the Android dynamic-tool test turn",
            ) { snapshot ->
                val outbound = snapshot.outboundTimeline.firstOrNull {
                    it.clientUserMessageId == messageId
                }
                snapshot.confirmedSelection == lunaMax &&
                    outbound?.status == OutboundMessageStatus.SENT &&
                    outbound.turnId != null
            }
            val turnId = checkNotNull(
                accepted.outboundTimeline.first {
                    it.clientUserMessageId == messageId
                }.turnId,
            )

            val completed = awaitSnapshot(
                host,
                5,
                TimeUnit.MINUTES,
                "No correlated read_battery tool receipt and final answer arrived",
            ) { snapshot ->
                val tool = snapshot.readBatteryTool(turnId)
                val answer = snapshot.timeline.firstOrNull { item ->
                    item.turnId == turnId &&
                        item.role == ClientTimelineRole.HANS &&
                        item.agentPhase == AgentMessagePhase.FINAL_ANSWER &&
                        item.complete &&
                        item.text.isNotBlank() &&
                        (tool == null || item.order > tool.order)
                }
                snapshot.sessionPhase == ClientSessionPhase.READY &&
                    snapshot.confirmedSelection == lunaMax &&
                    tool?.complete == true &&
                    tool.status == ClientTimelineStatus.COMPLETE &&
                    answer != null &&
                    snapshot.terminalTurns.any {
                        it.turnId == turnId && it.status == TurnStatus.COMPLETED
                    }
            }
            val tool = checkNotNull(completed.readBatteryTool(turnId))
            assertEquals("read_battery", tool.text.lineSequence().first())
            assertEquals(ClientTimelineStatus.COMPLETE, tool.status)
            assertTrue(tool.complete)
            assertTrue(
                completed.timeline.any { item ->
                    item.turnId == turnId &&
                        item.role == ClientTimelineRole.HANS &&
                        item.agentPhase == AgentMessagePhase.FINAL_ANSWER &&
                        item.complete &&
                        item.text.isNotBlank() &&
                        item.order > tool.order
                },
            )
        } finally {
            if (host.snapshot()?.sessionPhase == ClientSessionPhase.BUSY) {
                host.interrupt()
            }
        }
    }

    private fun CodexClientSnapshot.readBatteryTool(turnId: String): ClientTimelineItem? =
        timeline.firstOrNull { item ->
            item.turnId == turnId &&
                item.role == ClientTimelineRole.TOOL &&
                item.text.lineSequence().firstOrNull() == "read_battery"
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
                "pendingTools=${snapshot.pendingDynamicToolCalls}"
        }

    private companion object {
        const val ARG_RUN_LIVE_ANDROID_TOOL = "runLiveAndroidToolInvocation"
    }
}
