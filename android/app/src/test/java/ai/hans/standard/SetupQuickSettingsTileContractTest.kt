package ai.hans.standard

import android.app.StatusBarManager
import ai.hans.standard.setup.SetupUiCommandResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SetupQuickSettingsTileContractTest {
    @Test
    fun api31And32StayHonestAboutManualUnverifiableSetup() {
        assertEquals(
            SetupUiCommandResult.Rejected("quick_settings_tile_manual_add_required"),
            SetupQuickSettingsTileContract.receipt(31, null),
        )
        assertEquals(
            SetupUiCommandResult.Rejected("quick_settings_tile_manual_add_required"),
            SetupQuickSettingsTileContract.receipt(32, null),
        )
    }

    @Test
    fun api33OnlyReturnsEffectiveReceiptForAddedOrAlreadyAdded() {
        assertEquals(
            SetupUiCommandResult.Accepted(
                settingsOpened = true,
                liveVerificationAccepted = true,
            ),
            SetupQuickSettingsTileContract.receipt(
                33,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
            ),
        )
        assertEquals(
            SetupUiCommandResult.Accepted(
                settingsOpened = true,
                liveVerificationAccepted = true,
            ),
            SetupQuickSettingsTileContract.receipt(
                33,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED,
            ),
        )
        assertEquals(
            SetupUiCommandResult.Accepted(
                settingsOpened = true,
                liveVerificationAccepted = false,
            ),
            SetupQuickSettingsTileContract.receipt(
                33,
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED,
            ),
        )
    }
}
