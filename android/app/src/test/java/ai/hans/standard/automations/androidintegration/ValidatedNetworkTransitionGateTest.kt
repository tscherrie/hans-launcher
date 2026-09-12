package ai.hans.standard.automations.androidintegration

import org.junit.Assert.assertEquals
import org.junit.Test

class ValidatedNetworkTransitionGateTest {
    @Test
    fun reportsOnlyValidatedNetworkEdges() {
        val gate = ValidatedNetworkTransitionGate(initialValidated = false)

        assertEquals(ValidatedNetworkTransition.UNCHANGED, gate.update(false))
        assertEquals(ValidatedNetworkTransition.RESTORED, gate.update(true))
        assertEquals(ValidatedNetworkTransition.UNCHANGED, gate.update(true))
        assertEquals(ValidatedNetworkTransition.UNAVAILABLE, gate.update(false))
    }
}
