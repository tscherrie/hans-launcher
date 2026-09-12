package ai.hans.standard.phone.keys

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalActionKeyCaptureRoutingEngineTest {
    @Test
    fun activeCaptureOwnsAndDeliversCompleteDownUpStream() {
        val engine = GlobalActionKeyCaptureRoutingEngine()

        assertTrue(engine.requiresFrameworkFiltering(captureActive = true))
        val down = engine.onDeliveredEvent(
            KeyTestFixtures.event(),
            captureActive = true,
        )
        val repeat = engine.onDeliveredEvent(
            KeyTestFixtures.event(eventTimeMillis = 140, repeatCount = 1),
            captureActive = true,
        )
        val up = engine.onDeliveredEvent(
            KeyTestFixtures.event(phase = ObservableKeyPhase.UP, eventTimeMillis = 180),
            captureActive = true,
        )

        assertTrue(down.consume && down.deliverToSink)
        assertTrue(repeat.consume && repeat.deliverToSink)
        assertTrue(up.consume && up.deliverToSink)
        assertFalse(engine.requiresFrameworkFiltering(captureActive = false))
    }

    @Test
    fun leaseEndingAfterDownKeepsFilterUntilOwnedReleaseWithoutDeliveringIt() {
        val engine = GlobalActionKeyCaptureRoutingEngine()
        engine.onDeliveredEvent(KeyTestFixtures.event(), captureActive = true)

        assertTrue(engine.requiresFrameworkFiltering(captureActive = false))
        val repeat = engine.onDeliveredEvent(
            KeyTestFixtures.event(eventTimeMillis = 150, repeatCount = 1),
            captureActive = false,
        )
        val up = engine.onDeliveredEvent(
            KeyTestFixtures.event(phase = ObservableKeyPhase.UP, eventTimeMillis = 200),
            captureActive = false,
        )

        assertTrue(repeat.consume)
        assertFalse(repeat.deliverToSink)
        assertTrue(up.consume)
        assertFalse(up.deliverToSink)
        assertFalse(engine.requiresFrameworkFiltering(captureActive = false))
        assertFalse(
            engine.onDeliveredEvent(
                KeyTestFixtures.event(
                    phase = ObservableKeyPhase.UP,
                    eventTimeMillis = 210,
                ),
                captureActive = false,
            ).consume,
        )
    }
}
