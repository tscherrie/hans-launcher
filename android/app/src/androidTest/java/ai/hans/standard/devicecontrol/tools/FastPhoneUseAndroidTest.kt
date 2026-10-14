package ai.hans.standard.devicecontrol.tools

import ai.hans.standard.codex.*
import ai.hans.standard.fastphone.*
import ai.hans.standard.phone.accessibility.*
import ai.hans.standard.phone.accessibility.android.AccessibilityCommandCallback
import ai.hans.standard.phone.accessibility.android.HansAccessibilitySession
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Run on API 31-36. Uses Android JSON and synthetic UI only, no paid requests or real app actions. */
@RunWith(AndroidJUnit4::class)
class FastPhoneUseAndroidTest {
    @Test fun androidJsonRejectsNullCoercionUnknownActionsAndExpandedArguments() {
        val invalid = listOf("null", "[]", "malformed", plan().put("goal", JSONObject.NULL).toString(),
            plan().put("launch", "true").toString(), plan().put("timeoutMs", 1_000.5).toString(),
            plan().put("allowedActions", JSONArray(listOf("coordinates"))).toString(),
            plan().put("extra", true).toString())
        invalid.forEachIndexed { index, args ->
            val fixture = Fixture()
            assertCode("invalid_arguments", fixture.result(call(args, "bad-$index")))
            assertEquals(0, fixture.paidRequests); assertTrue(fixture.actions.isEmpty())
        }
    }

    @Test fun defaultOffOrMissingCredentialMakesNoNetworkOrUiEffect() {
        val disabled = Fixture().apply { enabled = false }
        assertCode("fast_phone_disabled", disabled.result())
        assertEquals(0, disabled.paidRequests); assertTrue(disabled.actions.isEmpty())
        val missing = Fixture().apply { credentialAvailable = false }
        assertCode("fast_phone_key_missing", missing.result())
        assertEquals(0, missing.paidRequests); assertTrue(missing.actions.isEmpty())
    }

    @Test fun exactTextThenDoneUsesFreshHandlesAndCompactReceiptsAndReplayIsReadOnly() {
        val fixture = Fixture()
        fixture.views += snapshot(1, edit = true)
        fixture.views += snapshot(2, edit = false)
        fixture.answers += DecisionsOutcome.Selected("a0", 0.95)
        fixture.answers += DecisionsOutcome.Selected("done", 0.95)
        val request = call(plan().put("allowedActions", JSONArray(listOf("set_text")))
            .put("textToEnter", "synthetic exact text").toString())
        val result = fixture.result(request)
        assertTrue(result.success)
        assertEquals("synthetic exact text", (fixture.actions.single() as AccessibilityCommand.SetText).value)
        val receipt = JSONObject(result.contentText)
        assertFalse(receipt.getBoolean("intermediateViewsReturned"))
        assertFalse(receipt.getBoolean("externalTaskIndependentlyVerified"))
        assertFalse(result.contentText.contains("synthetic exact text"))
        assertTrue(fixture.keyCopies.single().all { it == '\u0000' })
        assertEquals(2, fixture.paidRequests)
        assertTrue(JSONObject(fixture.result(request).contentText).getBoolean("replayed"))
        assertEquals(2, fixture.paidRequests); assertEquals(1, fixture.actions.size)
    }

    @Test fun lateModelChoiceCannotCrossCredentialOrSessionGeneration() {
        val fixture = Fixture()
        fixture.views += snapshot(1, edit = true)
        fixture.answers += DecisionsOutcome.Selected("a0", 0.95)
        fixture.onRequest = { fixture.revision++ }
        assertCode("fast_phone_cancelled_or_context_changed", fixture.result(call(plan()
            .put("allowedActions", JSONArray(listOf("set_text"))).put("textToEnter", "synthetic").toString())))
        assertTrue(fixture.actions.isEmpty()); assertEquals(0, fixture.verifiedRequests)
        assertTrue(fixture.keyCopies.single().all { it == '\u0000' })
    }

    @Test fun quotaAndInvalidProviderResponsesReturnFixedActionableErrors() {
        listOf("fast_phone_quota_exhausted", "fast_phone_invalid_response").forEach { code ->
            val fixture = Fixture()
            fixture.views += snapshot(1, edit = false)
            fixture.answers += DecisionsOutcome.Failure(code)
            assertCode(code, fixture.result())
            assertEquals(listOf(code), fixture.failures); assertTrue(fixture.actions.isEmpty())
        }
        assertEquals(DecisionsOutcome.Failure("fast_phone_invalid_response"),
            OpenAiDecisionsClient.decode("{\"answers\":[{\"type\":\"choice\",\"name\":\"next_action\",\"choice\":\"shell\"}]}",
                setOf("a0", "done", "escalate")))
    }

    @Test(timeout = 8_000) fun cancellationPreservesOuterLeaseUntilPhysicalCallback() {
        val fixture = Fixture(queued = true)
        fixture.views += snapshot(1, edit = true)
        fixture.answers += DecisionsOutcome.Selected("a0", 0.95)
        fixture.holdCallback = true
        val handle = fixture.executor.executeCancellable(call(plan()
            .put("allowedActions", JSONArray(listOf("set_text"))).put("textToEnter", "synthetic").toString()),
            DynamicToolCancellation.NONE) { fail("Cancelled turn must not receive a late result") }
        val done = CountDownLatch(1)
        Thread { fixture.jobs.single().run(); done.countDown() }.start()
        assertTrue(fixture.submitted.await(3, TimeUnit.SECONDS))
        val quiescent = AtomicInteger(); handle.onQuiescent { quiescent.incrementAndGet() }
        assertEquals(DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED, handle.cancel())
        assertEquals(0, quiescent.get())
        fixture.deliverHeld!!.invoke()
        assertTrue(done.await(3, TimeUnit.SECONDS)); assertEquals(1, quiescent.get())
        assertEquals(1, fixture.actions.size)
    }

    private class Fixture(queued: Boolean = false) {
        var enabled = true; var credentialAvailable = true; var revision = 1L
        var paidRequests = 0; var verifiedRequests = 0; var onRequest: () -> Unit = {}
        var holdCallback = false; var deliverHeld: (() -> Unit)? = null
        val submitted = CountDownLatch(1)
        val failures = mutableListOf<String>(); val keyCopies = mutableListOf<CharArray>()
        val views = ArrayDeque<SemanticUiSnapshot>(); var current: SemanticUiSnapshot? = null
        val answers = ArrayDeque<DecisionsOutcome>(); val actions = mutableListOf<AccessibilityCommand>()
        val jobs = mutableListOf<Runnable>()
        val session = object : HansAccessibilitySession {
            override val sessionId = SESSION
            override fun currentSnapshot() = current
            override fun refreshSnapshot() = current
            override fun retainSnapshotForCommands(correlation: UiSnapshotCorrelation) = current?.correlation == correlation
            override fun awaitSnapshot(timeoutMillis: Long, cancelled: () -> Boolean,
                predicate: (SemanticUiSnapshot) -> Boolean): SemanticUiSnapshot? {
                while (!cancelled() && views.isNotEmpty()) {
                    val view = views.removeFirst().also { current = it }
                    if (predicate(view)) return view
                }
                return null
            }
            override fun submit(command: AccessibilityCommand, approval: AccessibilityUserApproval?,
                callback: AccessibilityCommandCallback) = error("Guarded boundary required")
            override fun submitGuarded(command: AccessibilityCommand, cancelled: () -> Boolean,
                callback: AccessibilityCommandCallback): Boolean {
                if (cancelled()) return false
                actions += command
                val correlation = (command as AccessibilityCommand.SetText).handle.correlation
                val result = AccessibilityExecutionResult(command.idempotencyKey, AccessibilityExecutionStatus.SUCCEEDED,
                    false, null, AccessibilityPostcondition(AccessibilityPostconditionKind.NODE_ACTION,
                        AccessibilityPostconditionStatus.VERIFIED, "synthetic_text_matches", correlation, correlation,
                        UiDataTrust.LOCAL_SYSTEM))
                if (holdCallback) deliverHeld = { callback.onResult(result) } else callback.onResult(result)
                submitted.countDown()
                return true
            }
        }
        val access = object : DecisionsAccess {
            override fun isEnabled() = enabled
            override fun revision() = revision
            override fun readApiKey() = if (credentialAvailable) "synthetic-key".toCharArray().also(keyCopies::add) else null
            override fun recordSuccess(expectedRevision: Long) { if (expectedRevision == revision) verifiedRequests++ }
            override fun recordFailure(code: String, expectedRevision: Long) { if (expectedRevision == revision) failures += code }
        }
        val executor = FastPhoneUseExecutor(
            backgroundExecutor = if (queued) Executor { jobs += it } else Executor(Runnable::run),
            launch = { _, _ -> error("Synthetic tests never launch apps") },
            client = DecisionsClient { _, _, _ ->
                paidRequests++
                object : DecisionsCall {
                    override fun execute(): DecisionsOutcome { onRequest(); return answers.removeFirst() }
                    override fun cancel() = Unit
                }
            }, access = access, sessions = { session }, evidenceEpoch = { 1L }, nowMillis = { 0L },
            availability = UiInteractionAvailabilityProbe { UiInteractionAvailability.AVAILABLE })
        fun result(call: DynamicToolCallParams = call()): DynamicToolExecutionResult {
            var result: DynamicToolExecutionResult? = null
            executor.execute(call) { result = it }
            return checkNotNull(result)
        }
    }
    private companion object {
        const val PACKAGE = "synthetic.fastphone"
        val SESSION = AccessibilitySessionId("synthetic-fastphone-session")
        fun plan() = JSONObject().put("packageName", PACKAGE).put("goal", "Complete synthetic task")
            .put("allowedActions", JSONArray(listOf("click")))
        fun call(args: String = plan().toString(), id: String = "call") =
            DynamicToolCallParams("synthetic-thread", "synthetic-turn", id, "android_ui", "perform_goal", args)
        fun snapshot(id: Long, edit: Boolean) = BoundedSemanticUiSnapshotFactory().build(RawSemanticUiSnapshot(
            UiSnapshotCorrelation(SESSION, AccessibilityWindowId(1), AccessibilitySnapshotId(id)),
            UiBounds(0, 0, 400, 800), id, listOf(RawSemanticUiNode(packageName = PACKAGE,
                text = if (edit) "" else "Done", contentDescription = if (edit) "Message" else null,
                role = if (edit) SemanticUiRole.EDIT_TEXT else SemanticUiRole.TEXT,
                bounds = UiBounds(1, 1, 300, 100), editable = edit,
                actions = if (edit) setOf(SemanticUiAction.SET_TEXT) else emptySet()))))
        fun assertCode(code: String, result: DynamicToolExecutionResult) {
            assertFalse(result.success); assertEquals(code, JSONObject(result.contentText).getString("errorCode"))
        }
    }
}
