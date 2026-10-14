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

    @Test
    fun emptyWindowEnumerationUsesDirectRootsPublicOwnerWithoutGuessingDisplay() {
        val node = FakeAndroidAccessibilityNode(nodeWindowId = 41)
        val owner = FakeWindow(41, 7, active = true, focused = false)
        val direct = withOwner(node) { owner }

        val resolved = resolver.resolve({ direct }) { emptyList() }

        assertSame(direct, resolved?.root)
        assertEquals(7, resolved?.displayId)
        assertEquals(0, node.closeInvocations)
        assertEquals(0, owner.rootReads)
        assertEquals(1, owner.closeInvocations)
        checkNotNull(resolved).root.close()
        assertEquals(1, node.closeInvocations)
    }

    @Test
    fun throwingWindowEnumerationStillUsesIndependentlyValidatedPublicOwner() {
        val node = FakeAndroidAccessibilityNode(nodeWindowId = 42)
        val owner = FakeWindow(42, 3, active = false, focused = true)
        val direct = withOwner(node) { owner }

        val resolved = resolver.resolve({ direct }) { error("synthetic enumeration failure") }

        assertSame(direct, resolved?.root)
        assertEquals(3, resolved?.displayId)
        assertEquals(1, owner.closeInvocations)
        checkNotNull(resolved).root.close()
    }

    @Test
    fun ownWindowMetadataMustMatchIdApplicationActivityAndExplicitDisplay() {
        val invalidOwners = listOf(
            FakeWindow(50, -1, true, true),
            FakeWindow(-1, 0, true, true),
            FakeWindow(51, 0, true, true),
            FakeWindow(50, 0, false, false),
            FakeWindow(50, 0, true, true, AndroidAccessibilityWindowKind.OTHER),
        )
        invalidOwners.forEach { owner ->
            val node = FakeAndroidAccessibilityNode(nodeWindowId = 50)
            assertNull(resolver.resolve({ withOwner(node) { owner } }) { emptyList() })
            assertEquals(1, owner.closeInvocations)
            assertEquals(1, node.closeInvocations)
        }
    }

    @Test
    fun unreadableOwnerMetadataIsClosedAndNeverAuthorizesDirectRoot() {
        val node = FakeAndroidAccessibilityNode(nodeWindowId = 60)
        val owner = FakeWindow(60, 0, true, true)
        val unreadable = object : AndroidAccessibilityWindow by owner {
            override val displayId: Int get() = error("synthetic metadata failure")
        }

        assertNull(resolver.resolve({ withOwner(node) { unreadable } }) { emptyList() })
        assertEquals(1, owner.closeInvocations)
        assertEquals(1, node.closeInvocations)
    }

    @Test
    fun failingPublicOwnerLookupDoesNotPreventValidatedCandidateFallback() {
        val node = FakeAndroidAccessibilityNode(nodeWindowId = 61)
        val fallbackRoot = FakeAndroidAccessibilityNode(nodeWindowId = 62)
        val fallback = FakeWindow(62, 4, true, true, root = fallbackRoot)

        val resolved = resolver.resolve(
            { withOwner(node) { error("synthetic getWindow failure") } },
        ) { listOf(fallback) }

        assertSame(fallbackRoot, resolved?.root)
        assertEquals(4, resolved?.displayId)
        assertEquals(1, node.closeInvocations)
        assertEquals(1, fallback.closeInvocations)
        checkNotNull(resolved).root.close()
    }

    @Test
    fun fallbackRootWithMismatchedWindowIdIsClosedBeforeTryingAnotherWindow() {
        val mismatchedRoot = FakeAndroidAccessibilityNode(nodeWindowId = 999)
        val acceptedRoot = FakeAndroidAccessibilityNode(nodeWindowId = 72)
        val wrong = FakeWindow(71, 0, true, true, root = mismatchedRoot)
        val accepted = FakeWindow(72, 2, true, false, root = acceptedRoot)

        val resolved = resolver.resolve({ null }) { listOf(wrong, accepted) }

        assertSame(acceptedRoot, resolved?.root)
        assertEquals(2, resolved?.displayId)
        assertEquals(1, mismatchedRoot.closeInvocations)
        assertEquals(1, wrong.closeInvocations)
        assertEquals(1, accepted.closeInvocations)
        checkNotNull(resolved).root.close()
    }

    @Test
    fun unreadableFallbackRootIsClosedExactlyOnce() {
        val root = FakeAndroidAccessibilityNode(nodeWindowId = 80, failOnRead = true)
        val candidate = FakeWindow(80, 0, true, true, root = root)

        assertNull(resolver.resolve({ null }) { listOf(candidate) })
        assertEquals(1, root.closeInvocations)
        assertEquals(1, candidate.closeInvocations)
    }

    private fun withOwner(
        node: AndroidAccessibilityNode,
        owner: () -> AndroidAccessibilityWindow?,
    ): AndroidAccessibilityNode = object : AndroidAccessibilityNode by node {
        override fun window(): AndroidAccessibilityWindow? = owner()
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
