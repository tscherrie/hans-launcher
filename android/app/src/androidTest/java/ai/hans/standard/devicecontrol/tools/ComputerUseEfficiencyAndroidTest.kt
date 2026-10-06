package ai.hans.standard.devicecontrol.tools

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.phone.accessibility.*
import ai.hans.standard.phone.accessibility.android.AccessibilityCommandCallback
import ai.hans.standard.phone.accessibility.android.HansAccessibilitySession
import ai.hans.standard.phone.accessibility.resume.UiTaskContinuationCheckpointer
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic app state only: no real phone control, network, credentials or garage actions. */
@RunWith(AndroidJUnit4::class)
class ComputerUseEfficiencyAndroidTest {
    @Test
    fun acceptedClickReturnsFreshViewWithoutClaimingVerifiedOutcome() {
        val fixture = Fixture()
        val result = fixture.click(fixture.handle(), "first")
        assertTrue(result.success)
        val body = JSONObject(result.contentText)
        assertEquals("observed_not_verified", body.getJSONObject("postcondition").getString("status"))
        val next = body.getJSONObject("nextObservation")
        assertEquals("succeeded", next.getString("status"))
        assertEquals(2L, next.getJSONObject("correlation").getLong("snapshotId"))
        assertEquals(1, fixture.actions)
        assertEquals(0, fixture.refreshes)
        assertEquals(1, fixture.promotions)
    }

    @Test
    fun followUpHandleCanDriveNextStepWithoutAnotherInspect() {
        val fixture = Fixture()
        val first = JSONObject(fixture.click(fixture.handle(), "first").contentText)
        val nextHandle = first.getJSONObject("nextObservation").getJSONArray("nodes")
            .getJSONObject(0).getJSONObject("handle")
        val second = fixture.click(nextHandle, "second")
        assertTrue(second.success)
        assertEquals(3L, JSONObject(second.contentText).getJSONObject("nextObservation")
            .getJSONObject("correlation").getLong("snapshotId"))
        assertEquals(listOf(1L, 2L), fixture.commandSnapshots)
        assertEquals(2, fixture.actions)
        assertEquals(0, fixture.refreshes)
    }

    @Test
    fun missingReceiptDoesNotRepeatActionOrCaptureUnrelatedScreen() {
        val fixture = Fixture(missingReceipt = true)
        val result = fixture.click(fixture.handle(), "missing")
        assertTrue(result.success)
        val next = JSONObject(result.contentText).getJSONObject("nextObservation")
        assertEquals("unavailable", next.getString("status"))
        assertFalse(next.getBoolean("retryAction"))
        assertFalse(next.has("nodes"))
        assertEquals(1, fixture.actions)
        assertEquals(0, fixture.refreshes)
        assertEquals(0, fixture.promotions)
    }

    @Test
    fun lockingAfterActionWithholdsFollowUpWithoutRepeatingAction() {
        val fixture = Fixture(lockAfterAction = true)
        val result = fixture.click(fixture.handle(), "lock")
        assertTrue(result.success)
        val next = JSONObject(result.contentText).getJSONObject("nextObservation")
        assertEquals("unavailable", next.getString("status"))
        assertFalse(next.has("nodes"))
        assertFalse(next.getBoolean("retryAction"))
        assertEquals(1, fixture.actions)
        assertEquals(0, fixture.promotions)
    }

    private class Fixture(
        private val missingReceipt: Boolean = false,
        private val lockAfterAction: Boolean = false,
    ) : HansAccessibilitySession {
        override val sessionId = AccessibilitySessionId("synthetic-cu-session")
        private var current = snapshot(1)
        private var available = true
        var actions = 0
        var refreshes = 0
        var promotions = 0
        val commandSnapshots = mutableListOf<Long>()
        private val executor = AndroidAccessibilityDynamicToolExecutor(
            backgroundExecutor = Executor(Runnable::run),
            sessions = AccessibilitySessionSource { this },
            serviceConnection = AccessibilityServiceConnectionProbe { true },
            specialAccess = AccessibilitySpecialAccessProbe { true },
            uiAvailability = UiInteractionAvailabilityProbe {
                if (available) UiInteractionAvailability.AVAILABLE else UiInteractionAvailability.DEVICE_LOCKED
            },
            continuationCheckpointer = UiTaskContinuationCheckpointer.NONE,
        )

        override fun currentSnapshot() = current
        override fun refreshSnapshot(): SemanticUiSnapshot { refreshes++; return current }
        override fun receiptSnapshotForObservation(correlation: UiSnapshotCorrelation) =
            current.takeIf { !missingReceipt && it.correlation == correlation }
        override fun retainReceiptSnapshotForCommands(correlation: UiSnapshotCorrelation): Boolean {
            if (correlation != current.correlation) return false
            promotions++
            return true
        }

        override fun submit(
            command: AccessibilityCommand,
            approval: AccessibilityUserApproval?,
            callback: AccessibilityCommandCallback,
        ): Boolean {
            val click = command as AccessibilityCommand.Click
            assertEquals(current.correlation, click.handle.correlation)
            commandSnapshots += click.handle.correlation.snapshotId.value
            actions++
            val before = current.correlation
            current = snapshot(before.snapshotId.value + 1)
            if (lockAfterAction) available = false
            callback.onResult(AccessibilityExecutionResult(
                command.idempotencyKey, AccessibilityExecutionStatus.SUCCEEDED, false,
                AccessibilityObservation.ActionReceipt(null, "synthetic_click_accepted", current.correlation, UiDataTrust.LOCAL_SYSTEM),
                AccessibilityPostcondition(
                    AccessibilityPostconditionKind.NODE_ACTION,
                    AccessibilityPostconditionStatus.OBSERVED_NOT_VERIFIED,
                    "synthetic_click_accepted", before, current.correlation, UiDataTrust.LOCAL_SYSTEM,
                ),
            ))
            return true
        }

        fun handle(): JSONObject = JSONObject().put("correlation", JSONObject()
            .put("sessionId", sessionId.value).put("windowId", 1)
            .put("snapshotId", current.correlation.snapshotId.value)).put("nodeOrdinal", 0)

        fun click(handle: JSONObject, id: String): DynamicToolExecutionResult {
            var result: DynamicToolExecutionResult? = null
            executor.execute(DynamicToolCallParams(
                "synthetic-thread", "synthetic-turn", id, "android_ui", "click_ui",
                JSONObject().put("handle", handle).toString(),
            )) { result = it }
            return checkNotNull(result)
        }

        private fun snapshot(id: Long): SemanticUiSnapshot = BoundedSemanticUiSnapshotFactory().build(
            RawSemanticUiSnapshot(
                UiSnapshotCorrelation(sessionId, AccessibilityWindowId(1), AccessibilitySnapshotId(id)),
                UiBounds(0, 0, 400, 800), id,
                listOf(RawSemanticUiNode(
                    packageName = "synthetic.example", className = "android.widget.Button",
                    text = "Next", role = SemanticUiRole.BUTTON, bounds = UiBounds(10, 10, 110, 60),
                    clickable = true, actions = setOf(SemanticUiAction.CLICK),
                )),
            ),
        )
    }
}
