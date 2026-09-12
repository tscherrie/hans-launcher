package ai.hans.standard.phone.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalActionKeyAccessibilityReceiptTest {
    @Test
    fun receiptIsDeliveredOnlyToCurrentlyAttachedObserversAndIsNeverReplayed() {
        val receipt = GlobalActionKeyAccessibilityReceipt(
            mappingId = "dictation",
            command = ActionKeyCommand.ToggleDictation,
            event = KeyTestFixtures.event(
                phase = ObservableKeyPhase.UP,
                eventTimeMillis = 180,
            ),
        )
        val first = mutableListOf<GlobalActionKeyAccessibilityReceipt>()
        val firstRegistration = GlobalActionKeyAccessibilityReceiptCenter.observe(first::add)

        GlobalActionKeyAccessibilityReceiptCenter.publish(receipt)
        firstRegistration.close()
        GlobalActionKeyAccessibilityReceiptCenter.publish(receipt.copy(mappingId = "ignored"))

        val second = mutableListOf<GlobalActionKeyAccessibilityReceipt>()
        val secondRegistration = GlobalActionKeyAccessibilityReceiptCenter.observe(second::add)
        try {
            assertEquals(listOf(receipt), first)
            assertTrue(second.isEmpty())
        } finally {
            secondRegistration.close()
        }
    }
}
