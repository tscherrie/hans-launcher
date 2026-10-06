package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.SemanticUiRole
import ai.hans.standard.phone.accessibility.UiBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RetainedAccessibilityFrameStoreTest {
    @Test
    fun crossTurnInvalidationDropsBothAuthorityCachesWithoutLosingCurrentDisplay() {
        val store = RetainedAccessibilityFrameStore(elapsedRealtimeMillis = { 1_000L })
        val value = frame(1)
        store.publish(value)
        assertTrue(store.retainCurrentForCommand(value.correlation))
        assertTrue(store.retainCurrentForReceipt(value.correlation))
        store.clearRetainedEvidence()
        assertNotNull(store.currentFrame())
        assertNull(store.commandFrame(value.correlation))
        assertNull(store.receiptFrame(value.correlation))
        assertFalse(store.promoteReceiptForCommands(value.correlation))
        assertFalse(store.extendCommandFrameForConfirmation(value.correlation))
    }

    @Test
    fun commandFramesAreBoundedAccessOrderedAndExpireWithoutPolling() {
        var now = 1_000L
        val store = RetainedAccessibilityFrameStore(
            elapsedRealtimeMillis = { now },
            commandCapacity = 3,
            commandTtlMillis = 60_000L,
        )
        val first = frame(1)
        val second = frame(2)
        val third = frame(3)
        val fourth = frame(4)

        listOf(first, second, third).forEach { value ->
            store.publish(value)
            assertTrue(store.retainCurrentForCommand(value.correlation))
            now += 1
        }
        assertNotNull(store.commandFrame(first.correlation)) // first becomes most recently used
        store.publish(fourth)
        assertTrue(store.retainCurrentForCommand(fourth.correlation))

        assertNull(store.commandFrame(second.correlation))
        assertNotNull(store.commandFrame(first.correlation))
        assertEquals(3, store.retainedCommandCount())

        now += 60_000L
        assertNull(store.commandFrame(first.correlation))
        assertEquals(0, store.retainedCommandCount())
    }

    @Test
    fun retentionIsAtomicWithCurrentFrameAndClearAllDropsEveryGeneration() {
        val store = RetainedAccessibilityFrameStore(elapsedRealtimeMillis = { 1_000L })
        val inspected = frame(1)
        val newer = frame(2)

        store.publish(inspected)
        store.publish(newer)
        assertFalse(store.retainCurrentForCommand(inspected.correlation))
        assertTrue(store.retainCurrentForCommand(newer.correlation))
        store.clearCurrent()

        assertNull(store.current())
        assertNotNull(store.commandFrame(newer.correlation))
        store.clearAll()
        assertNull(store.commandFrame(newer.correlation))
        assertNull(store.currentFrame())
    }

    @Test
    fun oneTimeConfirmationRenewsExactFrameForPromptAndExecutionGraceOnly() {
        var now = 5_000L
        val store = RetainedAccessibilityFrameStore(
            elapsedRealtimeMillis = { now },
            commandTtlMillis = 10_000L,
        )
        val inspected = frame(1)
        val unrelated = frame(2)
        store.publish(inspected)
        assertTrue(store.retainCurrentForCommand(inspected.correlation))
        now += 9_000L
        store.publish(unrelated)

        assertTrue(
            store.extendCommandFrameForConfirmation(
                inspected.correlation,
                validityMillis = 65_000L,
            ),
        )
        now += 60_000L
        assertNotNull(store.commandFrame(inspected.correlation))
        now += 5_000L
        assertNull(store.commandFrame(inspected.correlation))
        assertFalse(store.extendCommandFrameForConfirmation(inspected.correlation))
    }

    @Test
    fun receiptFramesCannotAuthorizeCommandsAndHaveIndependentShortTtl() {
        var now = 10L
        val store = RetainedAccessibilityFrameStore(
            elapsedRealtimeMillis = { now },
            receiptTtlMillis = 1_000L,
        )
        val after = frame(7)
        store.publish(after)

        assertTrue(store.retainCurrentForReceipt(after.correlation))
        assertNotNull(store.receiptFrame(after.correlation))
        assertNull(store.snapshotForCorrelation(after.correlation))

        now += 1_000L
        assertNull(store.receiptFrame(after.correlation))
    }

    @Test
    fun newCommandSurvivesWhenReceiptFramesAlreadyFillTheTotalCapacity() {
        val store = RetainedAccessibilityFrameStore(elapsedRealtimeMillis = { 100L })
        val receipts = (1L..8L).map { frame(it) }
        receipts.forEach { receipt ->
            store.publish(receipt)
            assertTrue(store.retainCurrentForReceipt(receipt.correlation))
        }
        val command = frame(9)
        store.publish(command)

        assertTrue(store.retainCurrentForCommand(command.correlation))
        assertNotNull(store.commandFrame(command.correlation))
        assertEquals(1, store.retainedCommandCount())
        assertNull(store.receiptFrame(receipts.first().correlation))
    }

    @Test
    fun explicitPromotionRetainsOnlyTheExactReceiptEvenAfterTheCurrentWindowAdvances() {
        val store = RetainedAccessibilityFrameStore(elapsedRealtimeMillis = { 100L })
        val receipt = frame(2)
        val newer = frame(3, window = 9)
        store.publish(receipt)
        assertTrue(store.retainCurrentForReceipt(receipt.correlation))
        store.publish(newer)
        assertNull(store.snapshotForCorrelation(receipt.correlation))
        assertEquals(receipt, store.receiptFrame(receipt.correlation))

        assertTrue(store.promoteReceiptForCommands(receipt.correlation))

        assertEquals(receipt, store.commandFrame(receipt.correlation))
        assertEquals(newer, store.currentFrame())
        assertNull(store.commandFrame(newer.correlation))
        assertNull(store.receiptFrame(receipt.correlation))
        assertFalse(store.promoteReceiptForCommands(receipt.correlation))
    }

    @Test
    fun withdrawingUndeliveredPromotionRevokesOnlyItsExactFrameAndNeverResurrectsReceipt() {
        val store = RetainedAccessibilityFrameStore(elapsedRealtimeMillis = { 100L })
        val inspected = frame(1)
        val receipt = frame(2)
        store.publish(inspected)
        assertTrue(store.retainCurrentForCommand(inspected.correlation))
        store.publish(receipt)
        assertTrue(store.retainCurrentForReceipt(receipt.correlation))
        assertTrue(store.promoteReceiptForCommands(receipt.correlation))

        store.withdrawReceiptPromotion(receipt.correlation)
        store.withdrawReceiptPromotion(inspected.correlation)

        assertNull(store.commandFrame(receipt.correlation))
        assertNull(store.receiptFrame(receipt.correlation))
        assertFalse(store.promoteReceiptForCommands(receipt.correlation))
        assertNotNull(store.commandFrame(inspected.correlation))

        assertTrue(store.retainCurrentForCommand(receipt.correlation))
        store.withdrawReceiptPromotion(receipt.correlation)
        assertNotNull(store.commandFrame(receipt.correlation))
    }

    @Test
    fun concurrentInspectionKeepsItsOriginalLeaseWhenReceiptPromotionIsRefused() {
        var now = 1_000L
        val store = RetainedAccessibilityFrameStore(elapsedRealtimeMillis = { now })
        val receipt = frame(2)
        store.publish(receipt)
        assertTrue(store.retainCurrentForReceipt(receipt.correlation))
        assertTrue(store.retainCurrentForCommand(receipt.correlation))
        now = 2_000L

        assertFalse(store.promoteReceiptForCommands(receipt.correlation))
        assertNull(store.receiptFrame(receipt.correlation))
        store.withdrawReceiptPromotion(receipt.correlation)
        assertEquals(receipt, store.commandFrame(receipt.correlation))
        assertFalse(store.promoteReceiptForCommands(receipt.correlation))

        now = 61_000L
        assertNull(store.commandFrame(receipt.correlation))
        assertFalse(store.promoteReceiptForCommands(receipt.correlation))
    }

    @Test
    fun duplicatePromotionCannotExtendTheCommandLeaseOrResurrectAnExpiredFrame() {
        var now = 1_000L
        val store = RetainedAccessibilityFrameStore(elapsedRealtimeMillis = { now })
        val receipt = frame(2)
        store.publish(receipt)
        assertTrue(store.retainCurrentForReceipt(receipt.correlation))
        assertTrue(store.promoteReceiptForCommands(receipt.correlation))
        now = 60_000L
        assertFalse(store.promoteReceiptForCommands(receipt.correlation))
        assertNotNull(store.commandFrame(receipt.correlation))
        now = 61_000L
        assertNull(store.commandFrame(receipt.correlation))
        assertFalse(store.promoteReceiptForCommands(receipt.correlation))
    }

    @Test
    fun expiredMissingClearedAndForeignSessionReceiptsCannotBePromoted() {
        var now = 1_000L
        val store = RetainedAccessibilityFrameStore(elapsedRealtimeMillis = { now })
        val receipt = frame(2)
        store.publish(receipt)
        assertFalse(store.promoteReceiptForCommands(receipt.correlation))
        assertTrue(store.retainCurrentForReceipt(receipt.correlation))
        now += 5_000L
        assertFalse(store.promoteReceiptForCommands(receipt.correlation))
        assertNull(store.commandFrame(receipt.correlation))
        assertTrue(store.retainCurrentForReceipt(receipt.correlation))
        store.publish(frame(1, session = "different-service-session"))
        assertFalse(store.promoteReceiptForCommands(receipt.correlation))
        store.clearAll()
        store.publish(receipt)
        assertFalse(store.promoteReceiptForCommands(receipt.correlation))
        assertNull(store.commandFrame(receipt.correlation))
    }

    @Test
    fun receiptPromotionUsesExistingCommandAndSharedRetentionBounds() {
        val store = RetainedAccessibilityFrameStore(
            elapsedRealtimeMillis = { 100L },
            commandCapacity = 2,
            receiptCapacity = 2,
            totalRetainedCapacity = 2,
        )
        val frames = (1L..3L).map { frame(it) }
        frames.forEach {
            store.publish(it)
            assertTrue(store.retainCurrentForReceipt(it.correlation))
            assertTrue(store.promoteReceiptForCommands(it.correlation))
            assertTrue(store.retainedCommandCount() <= 2)
        }
        assertNull(store.commandFrame(frames.first().correlation))
        assertNotNull(store.commandFrame(frames.last().correlation))
    }

    private fun frame(
        snapshotId: Long,
        window: Int = 7,
        session: String = "accessibility-session-0001",
    ): AndroidAccessibilityFrame {
        val snapshot = semanticSnapshot(
            correlation = testCorrelation(snapshot = snapshotId, window = window, session = session),
            roots = listOf(rawNode(text = "screen-$snapshotId")),
        )
        val node = snapshot.nodes.single()
        return AndroidAccessibilityFrame(
            snapshot = snapshot,
            displayId = 0,
            locators = listOf(
                AndroidNodeLocator(
                    nodeOrdinal = node.handle.nodeOrdinal,
                    displayId = 0,
                    windowId = snapshot.correlation.windowId.value,
                    uniqueId = "node-$snapshotId",
                    viewIdResourceName = null,
                    structuralPath = emptyList(),
                    fingerprint = AndroidNodeFingerprint(
                        packageName = node.packageName?.value,
                        className = node.className?.value,
                        role = SemanticUiRole.TEXT,
                        bounds = UiBounds(0, 0, 100, 100),
                        visible = true,
                        enabled = true,
                        clickable = false,
                        editable = false,
                        scrollable = false,
                        actions = emptySet(),
                    ),
                ),
            ),
        )
    }
}
