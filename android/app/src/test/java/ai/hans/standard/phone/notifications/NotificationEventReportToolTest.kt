package ai.hans.standard.phone.notifications

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolCancellationDisposition
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.phone.tools.DynamicToolConfirmationProvider
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEventReportToolTest {
    @Test
    fun optionalCatalogAddsOnlyTheSourceBoundReportInsideTheExistingNamespace() {
        val disabled = executor()
        val enabled = executor(NotificationEventReportPort { _, _, _, admitEffect ->
            check(admitEffect())
            presented()
        })
        val oldNamespace = NotificationInboxDynamicToolCatalog.namespace

        assertEquals(listOf(oldNamespace), disabled.specs)
        assertEquals(listOf(oldNamespace.name), enabled.specs.map { it.name })
        assertEquals(
            oldNamespace.tools.map { it.name } + "report_event",
            enabled.specs.single().tools.map { it.name },
        )
        val schema = JSONObject(enabled.specs.single().tools.single { it.name == "report_event" }.inputSchemaJson)
        assertFalse(schema.getBoolean("additionalProperties"))
        assertEquals(setOf("eventId", "text"), schema.getJSONObject("properties").keys().asSequence().toSet())
        assertEquals(setOf("eventId", "text"), (0 until schema.getJSONArray("required").length())
            .map { schema.getJSONArray("required").getString(it) }.toSet())
        assertEquals(128, schema.getJSONObject("properties").getJSONObject("eventId").getInt("maxLength"))
        assertEquals(1_200, schema.getJSONObject("properties").getJSONObject("text").getInt("maxLength"))
        val result = execute(disabled, call(arguments("event", "A summary. Shall I act?")))
        assertFalse(result.success)
        assertEquals("unknown_notification_tool", JSONObject(result.contentText).getString("errorCode"))
    }

    @Test
    fun strictArgumentsRejectUnknownNullEmptyWrongTypesControlsAndBoundsWithoutCallingThePort() {
        var reports = 0
        val enabled = executor(NotificationEventReportPort { _, _, _, admitEffect ->
            check(admitEffect())
            reports++
            presented()
        })
        val invalid = listOf(
            "{}", "null", "[]",
            "{\"eventId\":\"event\"}", "{\"text\":\"summary\"}",
            "{\"eventId\":null,\"text\":\"summary\"}",
            "{\"eventId\":7,\"text\":\"summary\"}",
            "{\"eventId\":\"event\",\"text\":null}",
            "{\"eventId\":\"event\",\"text\":true}",
            JSONObject(arguments("event", "summary")).put("speech", true).toString(),
            arguments("", "summary"), arguments(" ", "summary"),
            arguments("event\n", "summary"), arguments("event\u0000", "summary"),
            arguments("x".repeat(129), "summary"),
            arguments("event", ""), arguments("event", " \n "),
            arguments("event", "x\u0000y"), arguments("event", "x\ty"),
            arguments("event", "x\ry"), arguments("event", "x\u0085y"),
            arguments("event", "x".repeat(1_201)),
            arguments("event", "\uD83D\uDE00".repeat(1_025)),
            arguments("event", "\uD800"), arguments("\uDC00", "summary"),
        )

        invalid.forEach { json ->
            val result = execute(enabled, call(json))
            assertFalse("Arguments unexpectedly accepted: $json", result.success)
            assertEquals("notification_report_arguments_invalid", JSONObject(result.contentText).getString("errorCode"))
        }
        assertEquals(0, reports)
    }

    @Test
    fun originalCallAndExactUnicodeTextReachThePortUnchangedIncludingMaximumBoundaries() {
        val received = mutableListOf<Triple<DynamicToolCallParams, String, String>>()
        val enabled = executor(NotificationEventReportPort { call, eventId, text, admitEffect ->
            check(admitEffect())
            received += Triple(call, eventId, text)
            presented()
        })
        val pairs = listOf(
            "event" to "A new message.\nShall I draft a reply? \uD83D\uDC4B",
            "x".repeat(128) to "x".repeat(1_200),
            "event-utf8-bound" to "\uD83D\uDE00".repeat(1_024),
        )

        pairs.forEach { (eventId, text) ->
            val original = call(arguments(eventId, text))
            assertTrue(execute(enabled, original).success)
            val actual = received.last()
            assertSame(original, actual.first)
            assertEquals(eventId, actual.second)
            assertEquals(text, actual.third)
        }
        assertEquals(3, received.size)
    }

    @Test
    fun presentedReceiptReportsChatAndQueueStateNeverPlayedAudibleOrScreenVisibility() {
        listOf(false, true).forEach { queued ->
            listOf(false, true).forEach { replay ->
                val enabled = executor(NotificationEventReportPort { _, _, _, admitEffect ->
                    check(admitEffect())
                    NotificationEventReportResult.Presented("report-one", queued, replay)
                })
                val result = execute(enabled, call(arguments("event", "Summary. Shall I help?")))
                assertTrue(result.success)
                val json = JSONObject(result.contentText)
                assertEquals(setOf("status", "inChat", "reportId", "speechStatus", "replay"), json.keys().asSequence().toSet())
                assertEquals("presented", json.getString("status"))
                assertTrue(json.getBoolean("inChat"))
                assertEquals("report-one", json.getString("reportId"))
                assertEquals(if (queued) "queued" else "not_queued", json.getString("speechStatus"))
                assertEquals(replay, json.getBoolean("replay"))
            }
        }
    }

    @Test
    fun backgroundCannotExposeOrGuessReportAndInteractiveOriginIsFreshPerCall() {
        var reports = 0
        var originChecks = 0
        var allowed = false
        val tools = NotificationInboxToolExecutors(
            source = NoQueries,
            executor = Executor { it.run() },
            confirmation = DynamicToolConfirmationProvider.NONE,
            isInteractive = { originChecks++; allowed },
            reportPort = NotificationEventReportPort { _, _, _, admitEffect ->
                check(admitEffect())
                reports++
                presented()
            },
        )
        assertFalse(tools.background.specs.single().tools.any { it.name == "report_event" })
        assertTrue(tools.interactive.specs.single().tools.any { it.name == "report_event" })
        listOf(false, true).forEach { cancellable ->
            val denied = execute(tools.background, call("{\"unknown\":true}"), cancellable)
            assertFalse(denied.success)
            assertEquals("background_notification_management_forbidden", JSONObject(denied.contentText).getString("errorCode"))
        }
        assertEquals(0, originChecks)
        assertEquals(0, reports)
        val valid = call(arguments("event", "Summary. Shall I help?"))
        assertFalse(execute(tools.interactive, valid).success)
        allowed = true
        assertTrue(execute(tools.interactive, valid, cancellable = true).success)
        allowed = false
        assertFalse(execute(tools.interactive, valid, cancellable = true).success)
        assertEquals(3, originChecks)
        assertEquals(1, reports)
    }

    @Test
    fun queuedCancellationWinsBeforeTheReportBoundaryAndProvidesQuiescence() {
        var reports = 0
        var callbacks = 0
        var quiescent = false
        val queued = ManualExecutor()
        val enabled = executor(NotificationEventReportPort { _, _, _, admitEffect ->
            check(admitEffect())
            reports++
            presented()
        }, queued)
        val handle = enabled.executeCancellable(
            call(arguments("event", "Summary. Shall I help?")),
            DynamicToolCancellation.NONE,
        ) { callbacks++ }
        assertTrue(handle.onQuiescent { quiescent = true })

        assertEquals(DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT, handle.cancel())
        queued.runNext()

        assertEquals(0, reports)
        assertEquals(0, callbacks)
        assertTrue(quiescent)
    }

    @Test
    fun cancellationInsideThePortIsMarkedAmbiguousAndSuppressesTheCallback() {
        var callbacks = 0
        var reports = 0
        var quiescent = false
        var disposition: DynamicToolCancellationDisposition? = null
        val queued = ManualExecutor()
        lateinit var handle: DynamicToolExecutionHandle
        val enabled = executor(NotificationEventReportPort { _, _, _, admitEffect ->
            check(admitEffect())
            reports++
            disposition = handle.cancel()
            presented()
        }, queued)
        handle = enabled.executeCancellable(
            call(arguments("event", "Summary. Shall I help?")),
            DynamicToolCancellation.NONE,
        ) { callbacks++ }
        assertTrue(handle.onQuiescent { quiescent = true })

        queued.runNext()

        assertEquals(DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED, disposition)
        assertEquals(1, reports)
        assertEquals(0, callbacks)
        assertTrue(quiescent)
    }

    @Test
    fun rejectionAndInvalidHostReceiptsNeverExposeRawFailuresOrClaimDelivery() {
        listOf<Pair<NotificationEventReportResult, String>>(
            NotificationEventReportResult.Rejected("notification_event_not_accepted") to "notification_event_not_accepted",
            NotificationEventReportResult.Rejected("token=secret\nraw failure") to "notification_query_failed",
            NotificationEventReportResult.Presented("", true) to "notification_report_receipt_invalid",
            NotificationEventReportResult.Presented("report\u0000", true) to "notification_report_receipt_invalid",
            NotificationEventReportResult.Presented("x".repeat(129), true) to "notification_report_receipt_invalid",
        ).forEach { (receipt, expectedCode) ->
            val result = execute(executor(NotificationEventReportPort { _, _, _, admitEffect ->
                if (receipt is NotificationEventReportResult.Presented) check(admitEffect())
                receipt
            }),
                call(arguments("event", "Summary. Shall I help?")))
            assertFalse(result.success)
            val json = JSONObject(result.contentText)
            assertEquals(setOf("status", "errorCode"), json.keys().asSequence().toSet())
            assertEquals("failed", json.getString("status"))
            assertEquals(expectedCode, json.getString("errorCode"))
            assertFalse(result.contentText.contains("secret"))
        }
        val thrown = execute(executor(NotificationEventReportPort { _, _, _, _ -> error("token=secret") }),
            call(arguments("event", "Summary. Shall I help?")))
        assertFalse(thrown.success)
        assertEquals("notification_query_failed", JSONObject(thrown.contentText).getString("errorCode"))
        assertFalse(thrown.contentText.contains("secret"))
    }

    @Test
    fun cancellationWhileThePortWaitsForAckWinsBeforeItsSyntheticEffectBoundary() {
        val enteredWait = CountDownLatch(1)
        val releaseWait = CountDownLatch(1)
        val quiescent = CountDownLatch(1)
        val reports = AtomicInteger()
        val callbacks = AtomicInteger()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val enabled = executor(NotificationEventReportPort { _, _, _, admitEffect ->
                enteredWait.countDown()
                check(releaseWait.await(2, TimeUnit.SECONDS))
                if (!admitEffect()) {
                    NotificationEventReportResult.Rejected("dynamic_tool_cancelled")
                } else {
                    reports.incrementAndGet()
                    presented()
                }
            }, worker)
            val handle = enabled.executeCancellable(
                call(arguments("event", "Summary. Shall I help?")),
                DynamicToolCancellation.NONE,
            ) { callbacks.incrementAndGet() }
            assertTrue(handle.onQuiescent { quiescent.countDown() })
            assertTrue(enteredWait.await(2, TimeUnit.SECONDS))
            assertEquals(DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT, handle.cancel())
            releaseWait.countDown()
            assertTrue(quiescent.await(2, TimeUnit.SECONDS))
            assertEquals(0, reports.get())
            assertEquals(0, callbacks.get())
        } finally {
            releaseWait.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(2, TimeUnit.SECONDS))
        }
    }

    private fun executor(
        reportPort: NotificationEventReportPort? = null,
        background: Executor = Executor { it.run() },
    ) = NotificationInboxDynamicToolExecutor(NoQueries, background, reportPort = reportPort)

    private fun execute(
        executor: DynamicToolExecutor,
        call: DynamicToolCallParams,
        cancellable: Boolean = false,
    ): DynamicToolExecutionResult {
        var result: DynamicToolExecutionResult? = null
        if (cancellable) executor.executeCancellable(call, DynamicToolCancellation.NONE) { result = it }
        else executor.execute(call) { result = it }
        return requireNotNull(result)
    }

    private fun call(argumentsJson: String) = DynamicToolCallParams(
        threadId = "native-thread",
        turnId = "native-turn",
        callId = "native-report-call",
        namespace = NotificationInboxDynamicToolCatalog.NAMESPACE,
        tool = NotificationInboxDynamicToolCatalog.REPORT_EVENT,
        argumentsJson = argumentsJson,
    )

    private fun arguments(eventId: String, text: String): String = JSONObject()
        .put("eventId", eventId).put("text", text).toString()

    private fun presented() = NotificationEventReportResult.Presented("report-one", false)

    private object NoQueries : NotificationInboxQuerySource {
        override fun queryPage(afterSequenceExclusive: Long, limit: Int): NotificationPage =
            error("report must not query the inbox")

        override fun queryDigest(afterSequenceExclusive: Long, maxEvents: Int, maxUtf8Bytes: Int): NotificationDigest =
            error("report must not query the inbox")
    }

    private class ManualExecutor : Executor {
        private val work = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { work.add(command) }
        fun runNext() { work.removeFirst().run() }
    }
}
