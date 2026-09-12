package ai.hans.standard.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Does not read/write Android settings or start animations; tests interpretation only. */
class IdleRendererAnimationEvidenceTest {
    @Test
    fun absentStoredValuesUseIndependentlyObservedDefaultsWithoutConvertingAbsenceToZero() {
        for (api in listOf(31, 32, 33, 34, 35, 36)) {
            val result = evidence(api = api)
            assertEquals(mapOf("animator" to 1f, "transition" to 1f, "window" to 1f), result.scales)
            assertEquals(3, result.stored.size)
            result.stored.values.forEach(::assertNull)
            assertEquals(if (api >= 33) 1f else null, result.publicAnimatorDurationScale)
        }
    }

    @Test
    fun missingTransitionOverrideDoesNotInventOneInsteadOfObservingResourceOverride() {
        val result = evidence(line = "Animation settings: disabled=false window=1.0 transition=0.75 animator=1.0")
        assertEquals(0.75f, result.scales.getValue("transition"))
        assertNull(result.stored["transition"])
    }

    @Test
    fun storedExplicitValidValuesMustAgreeWithEffectiveWindowManagerAndPublicApi() {
        val result = evidence(
            stored = mapOf("animator" to "1.25", "transition" to "0.5", "window" to "2.0"),
            publicScale = 1.25f,
            line = "Animation settings: disabled=false window=2.0 transition=0.5 animator=1.25",
        )
        assertEquals(1.25f, result.scales.getValue("animator"))
        assertEquals(2f, result.scales.getValue("window"))
    }

    @Test
    fun storedZeroNegativeInvalidNonFiniteAndOutOfRangeAreNeverDefaulted() {
        for (key in IdleRendererAnimationEvidence.KEYS) {
            for (raw in listOf("0", "-0.0", "-1", "NaN", "Infinity", "", "bad", "11", "21", "1e100", "0x1.0p0", "1.0f")) {
                assertThrows(IllegalStateException::class.java) {
                    evidence(stored = absent + (key to raw))
                }
            }
        }
    }

    @Test
    fun acceptedEnvironmentBoundIsTenAndDoesNotClaimThePlatformMaximum() {
        evidence(
            publicScale = 10f,
            line = "Animation settings: disabled=false window=10.0 transition=10.0 animator=10.0",
        )
        assertThrows(IllegalStateException::class.java) {
            evidence(publicScale = 11f, line = "Animation settings: disabled=false window=11.0 transition=11.0 animator=11.0")
        }
    }

    @Test
    fun platformFloatPrecisionHasCanonicalDecimalEvidenceWithoutDoublePromotionArtifacts() {
        val result = evidence(
            stored = absent + ("animator" to "0.10000000149011612"), publicScale = 0.1f,
            line = normal.replace("animator=1.0", "animator=0.1"),
        )
        assertEquals(0.1, result.scalesForReceipt().getValue("animator"), 0.0)
        assertEquals(0.1, requireNotNull(result.publicScaleForReceipt()), 0.0)
        assertThrows(IllegalStateException::class.java) {
            evidence(
                stored = absent + ("animator" to "0.10000000894069672"), publicScale = 0.1f,
                line = normal.replace("animator=1.0", "animator=0.1"),
            )
        }
    }

    @Test
    fun actualDisabledAnimationsAreRejectedEvenWithAbsentStoredSettings() {
        assertThrows(IllegalStateException::class.java) { evidence(enabled = false) }
        assertThrows(IllegalStateException::class.java) {
            evidence(line = "Animation settings: disabled=true window=1.0 transition=1.0 animator=1.0")
        }
        for (key in listOf("window", "transition", "animator")) {
            assertThrows(IllegalStateException::class.java) { evidence(line = normal.replace("$key=1.0", "$key=0.0")) }
        }
    }

    @Test
    fun mismatchingMissingAndInvalidPublicOrStoredEvidenceFailsClosed() {
        for (value in listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, 2f)) {
            assertThrows(IllegalStateException::class.java) { evidence(publicScale = value) }
        }
        assertThrows(IllegalStateException::class.java) { evidence(api = 31, publicScale = 1f) }
        assertThrows(IllegalStateException::class.java) { evidence(stored = absent + ("animator" to "0.5")) }
        assertThrows(IllegalStateException::class.java) { evidence(stored = emptyMap()) }
    }

    @Test
    fun unknownMissingAndDuplicateWindowManagerStateIsRejected() {
        for (dump in listOf("", "Something else", normal + "\n" + normal, normal + " unexpected", normal.replace("disabled=false", "disabled=unknown"))) {
            assertThrows(IllegalStateException::class.java) { evidence(line = dump) }
        }
    }

    @Test
    fun beforeAfterMustRetainBothEffectiveAndStoredState() {
        val before = evidence()
        evidence().requireUnchanged(before)
        assertThrows(IllegalStateException::class.java) {
            evidence(stored = absent + ("animator" to "1.0")).requireUnchanged(before)
        }
        assertThrows(IllegalStateException::class.java) {
            evidence(line = normal.replace("transition=1.0", "transition=0.75")).requireUnchanged(before)
        }
    }

    private fun evidence(
        api: Int = 34,
        stored: Map<String, String?> = absent,
        publicScale: Float? = if (api >= 33) 1f else null,
        enabled: Boolean = true,
        line: String = normal,
    ) = IdleRendererAnimationEvidence.resolve(api, stored, publicScale, enabled, line)

    companion object {
        private val absent = mapOf("animator" to null, "transition" to null, "window" to null)
        private const val normal = "Animation settings: disabled=false window=1.0 transition=1.0 animator=1.0"
    }
}
