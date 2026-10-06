package ai.hans.standard.remotecontrol

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.devicecontrol.tools.VisualFallbackProofStore
import ai.hans.standard.phone.accessibility.AccessibilitySessionId
import ai.hans.standard.phone.accessibility.AccessibilitySnapshotId
import ai.hans.standard.phone.accessibility.AccessibilityWindowId
import ai.hans.standard.phone.accessibility.BoundedSemanticUiSnapshotFactory
import ai.hans.standard.phone.accessibility.RawSemanticUiNode
import ai.hans.standard.phone.accessibility.RawSemanticUiSnapshot
import ai.hans.standard.phone.accessibility.SemanticUiAction
import ai.hans.standard.phone.accessibility.SemanticUiRole
import ai.hans.standard.phone.accessibility.UiBounds
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import ai.hans.standard.phone.accessibility.android.AndroidAccessibilityFrame
import ai.hans.standard.phone.accessibility.android.AndroidNodeFingerprint
import ai.hans.standard.phone.accessibility.android.AndroidNodeLocator
import ai.hans.standard.phone.accessibility.android.RetainedAccessibilityFrameStore
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Android contract for real RAM-only semantic frames and visual fallback proofs. All executors
 * are fakes: these tests neither obtain Accessibility permission nor send or open anything.
 */
class CrossTurnPhoneToolFenceAndroidTest {
    @Test fun crossThreadPublicActionInvalidatesRetainedSendAndFallback() {
        val fixture = Fixture()
        assertTrue(run(fixture.screen, call("thread-a", "turn-a", "inspect")).success)
        assertNotNull(fixture.frames.commandFrame(fixture.frame.correlation))

        // A public API action can change the screen before an Accessibility event arrives.
        // Two independently wrapped executors must still share the same ownership fence.
        assertTrue(run(fixture.apps, call("thread-b", "turn-b", "open_app", namespace = "hans_apps")).success)
        assertEquals(1, fixture.publicAppChanges)
        assertNull(fixture.frames.commandFrame(fixture.frame.correlation))

        val send = run(fixture.screen, call("thread-a", "turn-a", "send"))
        val fallback = run(fixture.screen, call("thread-a", "turn-a", "fallback"))
        assertFalse(send.success)
        assertEquals("stale_snapshot", JSONObject(send.contentText).getString("errorCode"))
        assertFalse(fallback.success)
        assertEquals("invalid_fallback", JSONObject(fallback.contentText).getString("errorCode"))
        assertEquals(0, fixture.sent)
        assertEquals(0, fixture.coordinateActions)
    }

    @Test fun sameTurnRetainsObservationButNewTurnInvalidatesIt() {
        val fixture = Fixture()
        assertTrue(run(fixture.screen, call("thread-a", "turn-a", "inspect")).success)
        val invalidationsAfterInspect = fixture.invalidations
        assertTrue(run(fixture.screen, call("thread-a", "turn-a", "send")).success)
        assertTrue(run(fixture.screen, call("thread-a", "turn-a", "fallback")).success)
        assertEquals(invalidationsAfterInspect, fixture.invalidations)
        assertEquals(1, fixture.sent)
        assertEquals(1, fixture.coordinateActions)
        assertNotNull(fixture.frames.commandFrame(fixture.frame.correlation))

        val oldSendInNewTurn = run(fixture.screen, call("thread-a", "turn-next", "send"))
        assertFalse(oldSendInNewTurn.success)
        assertEquals("stale_snapshot", JSONObject(oldSendInNewTurn.contentText).getString("errorCode"))
        assertTrue(fixture.invalidations > invalidationsAfterInspect)
        assertNull(fixture.frames.commandFrame(fixture.frame.correlation))
        assertEquals(1, fixture.sent)
    }

    private class Fixture {
        val frames = RetainedAccessibilityFrameStore(SystemClock::elapsedRealtime)
        private val proofs = VisualFallbackProofStore(
            elapsedRealtimeMillis = SystemClock::elapsedRealtime,
            tokenFactory = { "fallback:android-fence-test" },
        )
        val frame = retainedFrame()
        var invalidations = 0
            private set
        var sent = 0
            private set
        var coordinateActions = 0
            private set
        var publicAppChanges = 0
            private set
        private var fallbackToken: String? = null
        private val fence = CrossTurnPhoneToolFence {
            invalidations++
            frames.clearAll()
            proofs.clear()
        }

        val screen = fence.wrap(FakeExecutor("hans_screen", listOf("inspect", "send", "fallback")) { call ->
            when (call.tool) {
                "inspect" -> {
                    frames.publish(frame)
                    check(frames.retainCurrentForCommand(frame.correlation))
                    fallbackToken = proofs.issue(call, frame.correlation)
                    success()
                }
                "send" -> {
                    if (frames.commandFrame(frame.correlation) == null) failure("stale_snapshot")
                    else { sent++; success() }
                }
                "fallback" -> {
                    // Keep the apparent correlation unchanged to prove invalidation itself,
                    // rather than an unrelated new screenshot or token expiry, rejects reuse.
                    val claim = fallbackToken?.let { proofs.claim(it, call, frame.correlation, frame.correlation) }
                    if (claim == null) failure("invalid_fallback")
                    else { claim.consume(); coordinateActions++; success() }
                }
                else -> failure("unknown_tool")
            }
        })
        val apps = fence.wrap(FakeExecutor("hans_apps", listOf("open_app")) {
            publicAppChanges++
            success()
        })
    }

    private class FakeExecutor(
        namespace: String,
        tools: List<String>,
        private val action: (DynamicToolCallParams) -> DynamicToolExecutionResult,
    ) : DynamicToolExecutor {
        override val specs = listOf(DynamicToolNamespaceSpec(namespace, "Android fence test only",
            tools.map { DynamicToolFunctionSpec(it, "No real phone action", "{\"type\":\"object\"}") }))

        override fun execute(call: DynamicToolCallParams, completion: (DynamicToolExecutionResult) -> Unit) {
            completion(action(call))
        }

        override fun failureResult(call: DynamicToolCallParams, code: String) = failure(code)
    }

    companion object {
        private fun call(thread: String, turn: String, tool: String, namespace: String = "hans_screen") =
            DynamicToolCallParams(thread, turn, "$turn-$tool", namespace, tool, "{}")

        private fun run(executor: DynamicToolExecutor, call: DynamicToolCallParams): DynamicToolExecutionResult {
            val completed = CountDownLatch(1)
            val result = AtomicReference<DynamicToolExecutionResult>()
            executor.executeCancellable(call, DynamicToolCancellation.NONE) { result.set(it); completed.countDown() }
            assertTrue("Fence did not return a result", completed.await(5, TimeUnit.SECONDS))
            return checkNotNull(result.get())
        }

        private fun success() = DynamicToolExecutionResult("{\"ok\":true}", true)
        private fun failure(code: String) = DynamicToolExecutionResult(JSONObject().put("errorCode", code).toString(), false)

        private fun retainedFrame(): AndroidAccessibilityFrame {
            val bounds = UiBounds(0, 0, 100, 100)
            val correlation = UiSnapshotCorrelation(AccessibilitySessionId("android-fence-session"),
                AccessibilityWindowId(7), AccessibilitySnapshotId(1))
            val snapshot = BoundedSemanticUiSnapshotFactory().build(RawSemanticUiSnapshot(
                correlation, UiBounds(0, 0, 600, 800), SystemClock.elapsedRealtime(), listOf(RawSemanticUiNode(
                    packageName = "ai.hans.test.fixture", className = "android.widget.Button", text = "Send",
                    role = SemanticUiRole.BUTTON, bounds = bounds, clickable = true, actions = setOf(SemanticUiAction.CLICK),
                )),
            ))
            return AndroidAccessibilityFrame(snapshot, 0, listOf(AndroidNodeLocator(
                nodeOrdinal = snapshot.nodes.single().handle.nodeOrdinal, displayId = 0, windowId = 7,
                uniqueId = "test-send", viewIdResourceName = "test:id/send", structuralPath = emptyList(),
                fingerprint = AndroidNodeFingerprint("ai.hans.test.fixture", "android.widget.Button", SemanticUiRole.BUTTON,
                    bounds, visible = true, enabled = true, clickable = true, editable = false, scrollable = false,
                    actions = setOf(SemanticUiAction.CLICK)),
            )))
        }
    }
}
