package ai.hans.standard.notifications.hooks

import org.junit.Assert.*
import org.junit.Test

class NotificationAgentUpdateIdentityTest {
    private fun identity(text: String, ongoing: Boolean = true, category: String = "service"): String? {
        val event = NotificationEventLedgerTest.event(1, body = text).let {
            it.copy(snapshot = it.snapshot.copy(ongoing = ongoing, category = category, clearable = false))
        }
        return NotificationAgentUpdateIdentity.fromEvent(event)
    }
    @Test fun onlyCompleteProvenTrafficGrammarIsCanonicalized() {
        val meter = "↓ 1.00 MB | 2.00 KB/s  ↑ 3.00 MB | 4.00 B/s"
        val changed = "↓ 5.00 MB | 6.00 KB/s  ↑ 7.00 MB | 8.00 B/s"
        assertEquals(identity(meter), identity(changed))
        assertNotEquals(identity(meter, false), identity(changed, false))
        assertNotEquals(identity(meter, category = "msg"), identity(changed, category = "msg"))
        assertNotEquals(identity(meter), identity("$meter Connection lost"))
        assertNotEquals(identity(meter), identity(meter.replace("MB", "UNKNOWN")))
        assertNotEquals(identity("Jobs 10.00"), identity("Jobs 11.00"))
        assertNotEquals(identity("Balance 10.00"), identity("Balance 11.00"))
    }
    @Test fun localizedDecimalAndNonbreakingWhitespaceAreSupportedConservatively() {
        assertEquals(identity("↓ 1,00\u00a0МБ | 2,00\u00a0КБ/c  ↑ 3,00\u00a0МБ | 4,00\u00a0Б/c"),
            identity("↓ 5,00\u00a0МБ | 6,00\u00a0КБ/c  ↑ 7,00\u00a0МБ | 8,00\u00a0Б/c"))
    }
}
