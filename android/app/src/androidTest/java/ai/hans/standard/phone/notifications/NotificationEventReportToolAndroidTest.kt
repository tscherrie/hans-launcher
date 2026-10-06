package ai.hans.standard.phone.notifications

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.phone.tools.DynamicToolConfirmationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.ArrayDeque
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Android's JSON implementation must enforce the same report boundary as the JVM artifact. */
@RunWith(AndroidJUnit4::class)
class NotificationEventReportToolAndroidTest {
    @Test
    fun androidJsonNeverCoercesNullOrNumbersAndPassesValidTextAndCallerUnchanged() {
        val received = mutableListOf<Triple<DynamicToolCallParams, String, String>>()
        val executor = NotificationInboxDynamicToolExecutor(
            NoQueries,
            Executor { it.run() },
            reportPort = NotificationEventReportPort { call, eventId, text, admitEffect ->
                check(admitEffect())
                received += Triple(call, eventId, text)
                NotificationEventReportResult.Presented("android-report", true, replay = true)
            },
        )
        listOf(
            "{\"eventId\":null,\"text\":\"summary\"}",
            "{\"eventId\":7,\"text\":\"summary\"}",
            "{\"eventId\":\"event\",\"text\":null}",
            "{\"eventId\":\"event\",\"text\":3}",
            "{\"eventId\":\"event\",\"text\":\"summary\",\"speak\":true}",
            JSONObject().put("eventId", "event").put("text", "summary\u0000").toString(),
        ).forEach { arguments ->
            var result: DynamicToolExecutionResult? = null
            executor.execute(call(arguments)) { result = it }
            assertFalse(requireNotNull(result).success)
            assertEquals("notification_report_arguments_invalid",
                JSONObject(requireNotNull(result).contentText).getString("errorCode"))
        }
        assertTrue(received.isEmpty())
        val text = "New message.\nShall I draft a reply? \uD83D\uDC4B"
        val original = call(JSONObject().put("eventId", "event").put("text", text).toString())
        var result: DynamicToolExecutionResult? = null
        executor.executeCancellable(original, DynamicToolCancellation.NONE) { result = it }

        assertSame(original, received.single().first)
        assertEquals("event", received.single().second)
        assertEquals(text, received.single().third)
        val receipt = JSONObject(requireNotNull(result).contentText)
        assertTrue(requireNotNull(result).success)
        assertEquals("presented", receipt.getString("status"))
        assertTrue(receipt.getBoolean("inChat"))
        assertEquals("queued", receipt.getString("speechStatus"))
        assertTrue(receipt.getBoolean("replay"))
        assertFalse(receipt.has("played"))
        assertFalse(receipt.has("audible"))
    }

    @Test
    fun androidBackgroundDeniesGuessedReportAndQueuedCancellationPreventsDelivery() {
        var reports = 0
        var callbacks = 0
        val queued = ArrayDeque<Runnable>()
        val tools = NotificationInboxToolExecutors(
            source = NoQueries,
            executor = Executor { queued.add(it) },
            confirmation = DynamicToolConfirmationProvider.NONE,
            isInteractive = { true },
            reportPort = NotificationEventReportPort { _, _, _, admitEffect ->
                check(admitEffect())
                reports++
                NotificationEventReportResult.Presented("android-report", false)
            },
        )
        assertFalse(tools.background.specs.single().tools.any { it.name == "report_event" })
        listOf(false, true).forEach { cancellable ->
            var denial: DynamicToolExecutionResult? = null
            val guessed = call("{\"unknown\":true}")
            if (cancellable) tools.background.executeCancellable(guessed, DynamicToolCancellation.NONE) { denial = it }
            else tools.background.execute(guessed) { denial = it }
            assertFalse(requireNotNull(denial).success)
            assertEquals("background_notification_management_forbidden",
                JSONObject(requireNotNull(denial).contentText).getString("errorCode"))
        }
        assertTrue(queued.isEmpty())
        val handle = tools.interactive.executeCancellable(
            call(JSONObject().put("eventId", "event").put("text", "Summary. Shall I help?").toString()),
            DynamicToolCancellation.NONE,
        ) { callbacks++ }

        assertEquals(DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT, handle.cancel())
        queued.removeFirst().run()

        assertEquals(0, reports)
        assertEquals(0, callbacks)
    }

    private fun call(argumentsJson: String) = DynamicToolCallParams(
        "native-thread", "native-turn", "native-report-call",
        NotificationInboxDynamicToolCatalog.NAMESPACE,
        NotificationInboxDynamicToolCatalog.REPORT_EVENT,
        argumentsJson,
    )

    private object NoQueries : NotificationInboxQuerySource {
        override fun queryPage(afterSequenceExclusive: Long, limit: Int): NotificationPage = error("unexpected inbox query")
        override fun queryDigest(afterSequenceExclusive: Long, maxEvents: Int, maxUtf8Bytes: Int): NotificationDigest =
            error("unexpected inbox query")
    }
}
