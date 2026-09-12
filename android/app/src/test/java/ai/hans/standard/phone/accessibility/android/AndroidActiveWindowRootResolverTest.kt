package ai.hans.standard.phone.accessibility.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidActiveWindowRootResolverTest {
    private val resolver = AndroidActiveWindowRootResolver()

    @Test
    fun directRootUsesDisplayOfMatchingActiveApplicationWindow() {
        val direct = FakeAndroidAccessibilityNode(nodeWindowId = 11)
        val matching = FakeWindow(11, 2, active = true, focused = true)

        val resolved = resolver.resolve(
            directRoot = { direct },
            candidateWindows = { listOf(matching) },
        )

        assertSame(direct, resolved?.root)
        assertEquals(2, resolved?.displayId)
        assertEquals(0, matching.rootReads)
        assertEquals(1, matching.closeInvocations)
        assertFalse(direct.isClosed)
        checkNotNull(resolved).root.close()
    }

    @Test
    fun uncorrelatedDirectRootIsClosedAndFallsBackToActiveApplication() {
        val direct = FakeAndroidAccessibilityNode(nodeWindowId = 11)
        val fallbackRoot = FakeAndroidAccessibilityNode(nodeWindowId = 24)
        val fallback = FakeWindow(
            windowId = 24,
            displayId = 0,
            active = true,
            focused = false,
            root = fallbackRoot,
        )

        val resolved = resolver.resolve(
            directRoot = { direct },
            candidateWindows = { listOf(fallback) },
        )

        assertTrue(direct.isClosed)
        assertSame(fallbackRoot, resolved?.root)
        assertEquals(0, resolved?.displayId)
        assertEquals(1, fallback.rootReads)
        assertEquals(1, fallback.closeInvocations)
        checkNotNull(resolved).root.close()
    }

    @Test
    fun fallbackSelectsActiveApplicationAndClosesEveryWindow() {
        val focusedRoot = FakeAndroidAccessibilityNode(nodeWindowId = 23)
        val activeRoot = FakeAndroidAccessibilityNode(nodeWindowId = 24)
        val candidates = listOf(
            FakeWindow(
                21,
                0,
                active = true,
                focused = true,
                kind = AndroidAccessibilityWindowKind.OTHER,
            ),
            FakeWindow(22, 0, active = false, focused = false),
            FakeWindow(23, 0, active = false, focused = true, root = focusedRoot),
            FakeWindow(24, 1, active = true, focused = false, root = activeRoot),
        )

        val resolved = resolver.resolve(directRoot = { null }) { candidates }

        assertSame(activeRoot, resolved?.root)
        assertEquals(1, resolved?.displayId)
        assertTrue(candidates.all { it.closeInvocations == 1 })
        assertEquals(0, candidates[0].rootReads)
        assertEquals(0, candidates[1].rootReads)
        assertEquals(0, candidates[2].rootReads)
        assertEquals(1, candidates[3].rootReads)
        checkNotNull(resolved).root.close()
    }

    @Test
    fun fallbackTriesFocusedApplicationWhenActiveWindowHasNoAccessibleRoot() {
        val focusedRoot = FakeAndroidAccessibilityNode(nodeWindowId = 31)
        val active = FakeWindow(30, 0, active = true, focused = false, root = null)
        val focused = FakeWindow(31, 0, active = false, focused = true, root = focusedRoot)

        val resolved = resolver.resolve(directRoot = { null }) { listOf(focused, active) }

        assertSame(focusedRoot, resolved?.root)
        assertEquals(0, resolved?.displayId)
        assertEquals(1, active.rootReads)
        assertEquals(1, focused.rootReads)
        checkNotNull(resolved).root.close()
    }

    @Test
    fun duplicateWindowIdDoesNotGuessDisplayForDirectRoot() {
        val direct = FakeAndroidAccessibilityNode(nodeWindowId = 11)
        val firstRoot = FakeAndroidAccessibilityNode(nodeWindowId = 11)
        val first = FakeWindow(11, 0, active = true, focused = true, root = firstRoot)
        val duplicate = FakeWindow(11, 2, active = true, focused = true)

        val resolved = resolver.resolve(directRoot = { direct }) { listOf(first, duplicate) }

        assertTrue(direct.isClosed)
        assertSame(firstRoot, resolved?.root)
        assertEquals(0, resolved?.displayId)
        checkNotNull(resolved).root.close()
    }

    @Test
    fun failsClosedWhenNoApplicationWindowIsActiveOrFocused() {
        val candidates = listOf(
            FakeWindow(
                20,
                0,
                active = true,
                focused = true,
                kind = AndroidAccessibilityWindowKind.OTHER,
            ),
            FakeWindow(21, 0, active = false, focused = false),
        )

        val resolved = resolver.resolve(directRoot = { null }) { candidates }

        assertNull(resolved)
        assertTrue(candidates.all { it.closeInvocations == 1 })
        assertTrue(candidates.all { it.rootReads == 0 })
    }

    private class FakeWindow(
        override val windowId: Int,
        override val displayId: Int,
        override val active: Boolean,
        override val focused: Boolean,
        override val kind: AndroidAccessibilityWindowKind =
            AndroidAccessibilityWindowKind.APPLICATION,
        private val root: AndroidAccessibilityNode? = null,
    ) : AndroidAccessibilityWindow {
        var rootReads = 0
            private set
        var closeInvocations = 0
            private set

        override fun root(): AndroidAccessibilityNode? {
            rootReads += 1
            return root
        }

        override fun close() {
            closeInvocations += 1
        }
    }
}
