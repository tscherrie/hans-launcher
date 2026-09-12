package ai.hans.standard.voice.realtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LiveVoiceDiagnosticsTest {
    @Test
    fun opaqueIdentifiersAreLoggedOnlyAsStableShortReceipts() {
        val raw = "turn-private-opaque-value"
        val receipt = LiveVoiceDiagnostics.safeId(raw)

        assertEquals(10, receipt.length)
        assertFalse(receipt.contains(raw))
        assertEquals(receipt, LiveVoiceDiagnostics.safeId(raw))
    }
}
