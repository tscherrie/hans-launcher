package ai.hans.standard.phone.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalActionKeyCaptureGateTest {
    @Test
    fun exclusiveLeaseDeliversOnlyWhileActiveAndStaleCloseCannotEndReplacement() {
        val session = CaptureSessionId(41)
        val delivered = mutableListOf<Pair<CaptureSessionId, ObservableAndroidKeyEvent>>()
        val snapshots = mutableListOf<GlobalActionKeyCaptureSnapshot>()
        val registration = GlobalActionKeyCaptureGate.observe(snapshots::add)
        val lease = GlobalActionKeyCaptureGate.acquire(session) { id, event ->
            delivered += id to event
        }
        var replacementLease: GlobalActionKeyCaptureLease? = null

        try {
            assertTrue(lease.isActive())
            assertEquals(session, GlobalActionKeyCaptureGate.snapshot().sessionId)
            assertThrows(IllegalStateException::class.java) {
                GlobalActionKeyCaptureGate.acquire(CaptureSessionId(42)) { _, _ -> Unit }
            }
            assertTrue(GlobalActionKeyCaptureGate.deliver(KeyTestFixtures.event()))
            assertEquals(listOf(session), delivered.map { it.first })

            lease.close()
            assertFalse(lease.isActive())
            assertFalse(GlobalActionKeyCaptureGate.deliver(KeyTestFixtures.event()))

            val replacement = GlobalActionKeyCaptureGate.acquire(
                CaptureSessionId(43),
            ) { _, _ -> Unit }
            replacementLease = replacement
            lease.close()
            assertTrue(replacement.isActive())
            replacement.close()
            replacementLease = null

            assertNull(GlobalActionKeyCaptureGate.snapshot().sessionId)
            val expectedSnapshots =
                listOf(null, session, null, CaptureSessionId(43), null)
            assertEquals(expectedSnapshots, snapshots.map { it.sessionId })

            registration.close()
            GlobalActionKeyCaptureGate.acquire(CaptureSessionId(44)) { _, _ -> Unit }.close()
            assertEquals(expectedSnapshots, snapshots.map { it.sessionId })
        } finally {
            replacementLease?.close()
            lease.close()
            registration.close()
            GlobalActionKeyCaptureGate.snapshot().sessionId?.let { active ->
                error("Capture lease $active leaked from test")
            }
        }
    }
}
