package ai.hans.standard.diagnostics

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassiveInitializedValueTest {
    @Test
    fun passiveSnapshotNeverInitializesLazyHost() {
        val constructions = AtomicInteger()
        val lazyHost = lazy { constructions.incrementAndGet(); "host" }
        val passive = PassiveInitializedValue(lazyHost)

        assertNull(passive.snapshot { it.uppercase() })
        assertFalse(passive.isInitialized())
        assertEquals(0, constructions.get())

        assertEquals("host", lazyHost.value)
        assertTrue(passive.isInitialized())
        assertEquals("HOST", passive.snapshot { it.uppercase() })
        assertEquals(1, constructions.get())
    }
}
