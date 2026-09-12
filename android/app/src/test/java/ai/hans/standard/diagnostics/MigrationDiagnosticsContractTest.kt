package ai.hans.standard.diagnostics

import android.app.Activity
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationDiagnosticsContractTest {
    private val requestId = "68bb6a75-0618-4a47-94ca-bf60a58ee0c8"
    private val keys = setOf("protocolVersion", "requestId", "phase")

    @Test
    fun exactRequestParsesAndEveryMalformedVariantFailsClosed() {
        val expected = MigrationDiagnosticsRequest(
            1,
            requestId,
            MigrationDiagnosticsPhase.AFTER_TRANSITION,
        )
        assertEquals(
            expected,
            parse(
                action = MigrationDiagnosticsContract.ACTION,
                componentMatches = true,
                version = 1,
                id = requestId,
                phase = "after_transition",
            ),
        )
        assertNull(parse(action = "other", componentMatches = true, 1, requestId, "after_transition"))
        assertNull(parse(MigrationDiagnosticsContract.ACTION, false, 1, requestId, "after_transition"))
        assertNull(parse(MigrationDiagnosticsContract.ACTION, true, 2, requestId, "after_transition"))
        assertNull(parse(MigrationDiagnosticsContract.ACTION, true, 1L, requestId, "after_transition"))
        assertNull(parse(MigrationDiagnosticsContract.ACTION, true, 1, requestId.uppercase(), "after_transition"))
        assertNull(parse(MigrationDiagnosticsContract.ACTION, true, 1, requestId, "root"))
        assertNull(
            MigrationDiagnosticsContract.parseFields(
                MigrationDiagnosticsContract.ACTION,
                true,
                1,
                requestId,
                "after_transition",
                keys + "unexpected",
            ),
        )
    }

    @Test
    fun onlyOrderedDeliveryPassesTheDumpProtectedReceiverGate() {
        assertEquals(
            MigrationDiagnosticsDispatchDecision.REJECT_NOT_ORDERED,
            MigrationDiagnosticsDispatchGate.evaluate(false),
        )
        assertEquals(
            MigrationDiagnosticsDispatchDecision.ACCEPT,
            MigrationDiagnosticsDispatchGate.evaluate(true),
        )
    }

    @Test
    fun pendingResultCanFinishExactlyOnce() {
        val calls = AtomicInteger()
        var observedCode = 0
        var observedData = ""
        val completion = MigrationPendingResultCompletion { code, data ->
            calls.incrementAndGet()
            observedCode = code
            observedData = data
        }

        assertTrue(completion.complete(Activity.RESULT_OK, "first"))
        assertFalse(completion.complete(Activity.RESULT_CANCELED, "second"))
        assertEquals(1, calls.get())
        assertEquals(Activity.RESULT_OK, observedCode)
        assertEquals("first", observedData)
    }

    @Test
    fun timedOutAttemptCannotHeadOfLineBlockTheNextRequest() {
        val firstStarted = CountDownLatch(1)
        val first = MigrationDiagnosticsAttempt.create()
        assertTrue(
            first.submit(
                work = {
                    firstStarted.countDown()
                    try {
                        CountDownLatch(1).await()
                        "late"
                    } catch (_: InterruptedException) {
                        null
                    }
                },
                deliver = {},
            ),
        )
        assertTrue(firstStarted.await(2, TimeUnit.SECONDS))
        first.cancel()

        val secondFinished = CountDownLatch(1)
        var secondResult: String? = null
        val second = MigrationDiagnosticsAttempt.create()
        assertTrue(
            second.submit(
                work = { "success" },
                deliver = {
                    secondResult = it
                    secondFinished.countDown()
                },
            ),
        )
        assertTrue(secondFinished.await(2, TimeUnit.SECONDS))
        second.cancel()
        assertEquals("success", secondResult)
    }

    @Test
    fun canonicalJsonAndDomainHashesAreStableAndBound() {
        val first = MigrationCanonicalJson.encode(
            linkedMapOf("z" to listOf(true, null), "a" to "Grüße\n"),
        )
        val second = MigrationCanonicalJson.encode(
            linkedMapOf("a" to "Grüße\n", "z" to listOf(true, null)),
        )

        assertEquals("{\"a\":\"Grüße\\n\",\"z\":[true,null]}", first)
        assertEquals(first, second)
        assertEquals(64, MigrationDiagnosticsHashes.payload(first).length)
        assertEquals(
            MigrationDiagnosticsHashes.thread("thread-a"),
            MigrationDiagnosticsHashes.thread("thread-a"),
        )
        assertFalse(
            MigrationDiagnosticsHashes.thread("thread-a") ==
                MigrationDiagnosticsHashes.thread("thread-b"),
        )
    }

    private fun parse(
        action: String?,
        componentMatches: Boolean,
        version: Any?,
        id: Any?,
        phase: Any?,
    ): MigrationDiagnosticsRequest? = MigrationDiagnosticsContract.parseFields(
        action,
        componentMatches,
        version,
        id,
        phase,
        keys,
    )
}
