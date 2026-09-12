package ai.hans.standard.phone.keys

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real-device acceptance for the restored Minimal stock firmware contract. */
@RunWith(AndroidJUnit4::class)
class Mp01StockSystemPolicyProbeTest {
    @Test
    fun trustedStockButtonRemainsEnabledOnlyAsShortPressToggle() {
        assumeTrue(
            Build.MANUFACTURER.equals("ALONG", ignoreCase = true) &&
                Build.BRAND.equals("Minimal_Phone", ignoreCase = true) &&
                Build.MODEL.equals("MP01", ignoreCase = true) &&
                Build.DEVICE.equals("MP01", ignoreCase = true),
        )
        val context = ApplicationProvider.getApplicationContext<Context>()
        val evidence = AndroidMp01VendorActionRemediation(context).probe()

        assertTrue(evidence.trustedSystemPackage)
        assertEquals(
            Mp01VendorActionConflictKind.STOCK_SYSTEM_POLICY,
            evidence.conflictKind,
        )
        assertTrue(evidence.settingsActivityLaunchable)

        val stored = ActionKeyMappingPreferencesStore(context).read()
        val stockDictation = stored.mappings.single { mapping ->
            mapping.action == KeySemanticAction.DICTATION &&
                mapping.scanCode == Mp01VendorActionConflictResolver.ACTION_SCAN_CODE &&
                mapping.keyCode == Mp01VendorActionConflictResolver.STOCK_AREFRESH_KEY_CODE
        }
        assertEquals(ActionKeyTrigger.PRESS, stockDictation.trigger)

        val gate = Mp01VendorActionConflictResolver.gate(
            stored,
            evidence,
            Mp01VendorActionOverrideConfirmationStore(context).confirmation(),
        )
        assertTrue(gate.conflict?.stockPressToggleCompatible == true)
        assertFalse(gate.conflict?.replacementRequired == true)
        assertTrue(gate.effectiveMappings.mappings.contains(stockDictation))
    }
}
