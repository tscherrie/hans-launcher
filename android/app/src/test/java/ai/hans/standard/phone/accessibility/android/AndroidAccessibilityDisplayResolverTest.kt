package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.UiBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAccessibilityDisplayResolverTest {
    @Test
    fun resolvesDefaultPhoneDisplayWithoutConsultingAServiceContextDisplay() {
        val resolver = resolverWith(
            FakePhoneDisplay(
                displayId = 0,
                valid = true,
                bounds = UiBounds(0, 0, 1_080, 2_400),
            ),
        )

        val result = resolver.resolve(0)

        assertEquals(
            AndroidAccessibilityDisplayResolution.Available(
                AndroidAccessibilityDisplay(0, UiBounds(0, 0, 1_080, 2_400)),
            ),
            result,
        )
    }

    @Test
    fun missingOrThrowingDisplayManagerIsUnavailable() {
        assertFailure(
            AndroidAccessibilityDisplayResolver(AndroidPhoneDisplaySource { null }),
            0,
            AccessibilitySnapshotFailure.DISPLAY_UNAVAILABLE,
        )
        assertFailure(
            AndroidAccessibilityDisplayResolver(
                AndroidPhoneDisplaySource { throw IllegalStateException("not available") },
            ),
            0,
            AccessibilitySnapshotFailure.DISPLAY_UNAVAILABLE,
        )
    }

    @Test
    fun invalidOrNonDefaultDisplayFailsClosedBeforeWindowLookup() {
        val invalid = FakePhoneDisplay(0, valid = false)
        assertFailure(resolverWith(invalid), 0, AccessibilitySnapshotFailure.DISPLAY_INVALID)
        assertEquals(0, invalid.boundsReads)

        val secondary = FakePhoneDisplay(3, valid = true)
        assertFailure(resolverWith(secondary), 0, AccessibilitySnapshotFailure.DISPLAY_INVALID)
        assertEquals(0, secondary.boundsReads)
    }

    @Test
    fun unsupportedNonUiContextAccessBecomesSafeDisplayContextFailure() {
        val resolver = resolverWith(
            FakePhoneDisplay(
                displayId = 0,
                valid = true,
                boundsReader = {
                    throw UnsupportedOperationException("non-visual service context")
                },
            ),
        )

        assertFailure(resolver, 0, AccessibilitySnapshotFailure.DISPLAY_CONTEXT_FAILED)
    }

    @Test
    fun emptyWindowMetricsBecomeSafeBoundsFailure() {
        val resolver = resolverWith(
            FakePhoneDisplay(
                displayId = 0,
                valid = true,
                bounds = UiBounds(0, 0, 0, 2_400),
            ),
        )

        assertFailure(resolver, 0, AccessibilitySnapshotFailure.DISPLAY_BOUNDS_FAILED)
    }

    private fun resolverWith(display: AndroidPhoneDisplay) =
        AndroidAccessibilityDisplayResolver(AndroidPhoneDisplaySource { display })

    private fun assertFailure(
        resolver: AndroidAccessibilityDisplayResolver,
        displayId: Int,
        expected: AccessibilitySnapshotFailure,
    ) {
        val result = resolver.resolve(displayId)
        assertTrue(result is AndroidAccessibilityDisplayResolution.Failed)
        assertEquals(
            expected,
            (result as AndroidAccessibilityDisplayResolution.Failed).failure,
        )
    }

    private class FakePhoneDisplay(
        override val displayId: Int,
        private val valid: Boolean,
        private val bounds: UiBounds = UiBounds(0, 0, 1_080, 2_400),
        private val boundsReader: (() -> UiBounds)? = null,
    ) : AndroidPhoneDisplay {
        var boundsReads = 0
            private set

        override val isValid: Boolean
            get() = valid

        override fun windowBounds(): UiBounds {
            boundsReads += 1
            return boundsReader?.invoke() ?: bounds
        }
    }
}
