package ai.hans.standard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class IdleNodeFreshnessTest {
    @Test fun refreshPrecedesContentRead() {
        val calls = mutableListOf<String>()
        val actual = readFreshIdleNode({ calls += "refresh"; true }) { calls += "read"; "observed" }
        assertEquals(listOf("refresh", "read"), calls)
        assertEquals("observed", actual)
    }

    @Test fun falseRefreshNeverReadsCachedContentOrRetries() {
        val calls = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) {
            readFreshIdleNode({ calls += "refresh"; false }) { calls += "read"; "cached" }
        }
        assertEquals(listOf("refresh"), calls)
    }

    @Test fun refreshExceptionIsPreservedWithoutReadOrFallback() {
        val calls = mutableListOf<String>()
        val primary = IllegalArgumentException("refresh failed")
        val actual = assertThrows(IllegalArgumentException::class.java) {
            readFreshIdleNode({ calls += "refresh"; throw primary }) { calls += "read"; "cached" }
        }
        assertSame(primary, actual)
        assertEquals(listOf("refresh"), calls)
    }
}
