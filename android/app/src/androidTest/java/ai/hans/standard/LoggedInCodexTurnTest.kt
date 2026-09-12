package ai.hans.standard

import ai.hans.standard.codex.CodexInput
import ai.hans.standard.codex.ReasoningEffort
import ai.hans.standard.integration.ClientSessionPhase
import ai.hans.standard.integration.ClientTimelineRole
import ai.hans.standard.integration.CodexClientObserver
import ai.hans.standard.integration.CodexClientSnapshot
import ai.hans.standard.integration.DispatchSelection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Explicit, opt-in real-account gate. It is skipped in ordinary device suites
 * and runs only when the installer passes `runLiveCodexTurn=true`.
 */
@RunWith(AndroidJUnit4::class)
class LoggedInCodexTurnTest {
    @Test
    fun loggedInRootFreeRuntimeStreamsOneLunaMaxTurn() {
        assumeTrue(
            InstrumentationRegistry.getArguments().getString(ARG_RUN_LIVE_TURN) == "true",
        )
        val application = ApplicationProvider.getApplicationContext<HansApplication>()
        val host = application.sessionHost
        val ready = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val baselineOrder = AtomicLong(Long.MAX_VALUE)
        val latest = AtomicReference<CodexClientSnapshot>()
        val observer = CodexClientObserver { snapshot ->
            latest.set(snapshot)
            if (snapshot.sessionPhase == ClientSessionPhase.READY) ready.countDown()
            val baseline = baselineOrder.get()
            if (
                baseline != Long.MAX_VALUE &&
                snapshot.timeline.any { item ->
                    item.order > baseline &&
                        item.role == ClientTimelineRole.HANS &&
                        item.complete &&
                        item.text.isNotBlank()
                }
            ) {
                completed.countDown()
            }
        }
        host.addObserver(observer)
        try {
            assertTrue("Codex session did not become ready", ready.await(90, TimeUnit.SECONDS))
            val before = checkNotNull(latest.get())
            baselineOrder.set(before.timeline.maxOfOrNull { it.order } ?: 0L)
            val accepted = host.dispatch(
                input = listOf(
                    CodexInput.Text(
                        "Dies ist ein automatischer Hans-Gerätetest. " +
                            "Antworte bitte mit genau einem kurzen natürlichen Satz.",
                    ),
                ),
                selection = DispatchSelection("gpt-5.6-luna", ReasoningEffort.MAX),
                readResponseAloud = false,
            )
            assertNotNull("Codex rejected the live test turn", accepted)
            assertTrue(
                "No complete streamed Hans answer arrived",
                completed.await(5, TimeUnit.MINUTES),
            )
            val after = checkNotNull(latest.get())
            assertEquals("gpt-5.6-luna", after.confirmedSelection.model)
            assertEquals(ReasoningEffort.MAX, after.confirmedSelection.effort)
        } finally {
            host.removeObserver(observer)
        }
    }

    private companion object {
        const val ARG_RUN_LIVE_TURN = "runLiveCodexTurn"
    }
}
