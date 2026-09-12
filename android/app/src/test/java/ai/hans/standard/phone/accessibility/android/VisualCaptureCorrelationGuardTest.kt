package ai.hans.standard.phone.accessibility.android

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualCaptureCorrelationGuardTest {
    @Test
    fun rejectsDisplayChangeEvenWhenSessionWindowAndContentAreUnchanged() {
        val before = semanticSnapshot(correlation = testCorrelation(snapshot = 1))
        val after = semanticSnapshot(correlation = testCorrelation(snapshot = 2))

        assertFalse(
            VisualCaptureCorrelationGuard.matches(
                before = before,
                after = after,
                screenshotDisplayId = 0,
                afterDisplayId = 2,
            ),
        )
    }

    @Test
    fun acceptsFreshCorrelationOnTheSameDisplayWhenContentIsUnchanged() {
        val before = semanticSnapshot(correlation = testCorrelation(snapshot = 1))
        val after = semanticSnapshot(correlation = testCorrelation(snapshot = 2))

        assertTrue(
            VisualCaptureCorrelationGuard.matches(
                before = before,
                after = after,
                screenshotDisplayId = 0,
                afterDisplayId = 0,
            ),
        )
    }
}
