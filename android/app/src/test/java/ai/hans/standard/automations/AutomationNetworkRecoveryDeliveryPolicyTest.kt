package ai.hans.standard.automations

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationNetworkRecoveryDeliveryPolicyTest {
    @Test
    fun consumesOneShotOnlyWhenAssignedAndDefaultNetworksAreValidated() {
        assertFalse(
            AutomationNetworkRecoveryDeliveryPolicy.ready(
                assignedNetworkValidated = false,
                defaultNetworkValidated = false,
            ),
        )
        assertFalse(
            AutomationNetworkRecoveryDeliveryPolicy.ready(
                assignedNetworkValidated = true,
                defaultNetworkValidated = false,
            ),
        )
        assertFalse(
            AutomationNetworkRecoveryDeliveryPolicy.ready(
                assignedNetworkValidated = false,
                defaultNetworkValidated = true,
            ),
        )
        assertTrue(
            AutomationNetworkRecoveryDeliveryPolicy.ready(
                assignedNetworkValidated = true,
                defaultNetworkValidated = true,
            ),
        )
    }
}
