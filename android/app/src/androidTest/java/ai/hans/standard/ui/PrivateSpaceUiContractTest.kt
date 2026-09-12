package ai.hans.standard.ui

import ai.hans.standard.phone.capabilities.LaunchProfileType
import ai.hans.standard.phone.capabilities.PERSONAL_LAUNCH_PROFILE_ID
import ai.hans.standard.phone.capabilities.PrivateSpaceAvailability
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PrivateSpaceUiContractTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun appDrawerShowsSeparateContainerAndEmitsOpaqueLockAndHideActions() {
        val state = privateAppsState(locked = true, visible = true)
        var lockRequest: Pair<String, Boolean>? = null
        var visibleRequest: Boolean? = null
        compose.setContent {
            MaterialTheme {
                AppsScreen(
                    state = state,
                    callbacks = AppsUiCallbacks(
                        onBack = {},
                        onQueryChanged = {},
                        onRefresh = {},
                        onLaunch = { _, _ -> },
                        onPrivateSpaceVisibilityChanged = { visibleRequest = it },
                        onPrivateSpaceLockChanged = { profile, locked ->
                            lockRequest = profile to locked
                        },
                    ),
                )
            }
        }

        compose.onNodeWithTag("profile_private").assertExists()
        compose.onNodeWithTag("private_profile_locked").assertExists()
        compose.onNodeWithTag("unlock_private_space").performClick()
        // Callbacks run on the UI thread. Synchronize before reading their results
        // and before sending the next independent pointer action.
        compose.runOnIdle {
            assertEquals(PRIVATE_ID to false, lockRequest)
        }
        compose.onNodeWithTag("hide_private_space").performClick()
        compose.runOnIdle {
            assertEquals(false, visibleRequest)
        }
    }

    @Test
    fun hiddenContainerCannotBeDiscoveredInDrawerButSettingsCanReenableIt() {
        val privateState = privateAppsState(locked = false, visible = false).privateSpace
        val showSettings = androidx.compose.runtime.mutableStateOf(false)
        var visibleRequest: Boolean? = null
        compose.setContent {
            MaterialTheme {
                if (showSettings.value) {
                    SettingsScreen(
                        state = SettingsUiState(privateSpace = privateState),
                        callbacks = settingsCallbacks(
                            onVisibilityChanged = { visibleRequest = it },
                        ),
                    )
                } else {
                    AppsScreen(
                        state = privateAppsState(locked = false, visible = false),
                        callbacks = AppsUiCallbacks({}, {}, {}, { _, _ -> }),
                    )
                }
            }
        }
        compose.onNodeWithTag("profile_private").assertDoesNotExist()
        compose.onNodeWithTag("app_${PRIVATE_ID}_org.private").assertDoesNotExist()

        compose.runOnIdle { showSettings.value = true }
        compose.onNodeWithTag("settings_group_permissions").performScrollTo().performClick()
        compose.onNodeWithTag("show_private_space_container")
            .performScrollTo()
            .assertExists()
            .performClick()
        compose.runOnIdle {
            assertEquals(true, visibleRequest)
        }
    }

    @Test
    fun settingsExplainsMissingHomeRoleWithoutExposingPrivateControls() {
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        privateSpace = PrivateSpaceUiState(
                            availability = PrivateSpaceAvailability.HOME_ROLE_REQUIRED,
                        ),
                    ),
                    callbacks = settingsCallbacks(),
                )
            }
        }

        compose.onNodeWithTag("settings_group_permissions").performScrollTo().performClick()
        compose.onNodeWithTag("private_space_settings_status")
            .performScrollTo()
            .assertExists()
        compose.onNodeWithTag("show_private_space_container").assertDoesNotExist()
        compose.onNodeWithTag("settings_unlock_private_space").assertDoesNotExist()
    }

    @Test
    fun androidHiddenLockedEntrypointIsAbsentFromAppsButExplainedInSettings() {
        val state = privateAppsState(locked = true, visible = true).let { source ->
            source.copy(
                privateSpace = source.privateSpace.copy(entrypointHiddenWhenLocked = true),
            )
        }
        val showSettings = androidx.compose.runtime.mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                if (showSettings.value) {
                    SettingsScreen(
                        state = SettingsUiState(privateSpace = state.privateSpace),
                        callbacks = settingsCallbacks(),
                    )
                } else {
                    AppsScreen(
                        state = state,
                        callbacks = AppsUiCallbacks({}, {}, {}, { _, _ -> }),
                    )
                }
            }
        }
        compose.onNodeWithTag("profile_private").assertDoesNotExist()

        compose.runOnIdle { showSettings.value = true }
        compose.onNodeWithTag("settings_group_permissions").performScrollTo().performClick()
        compose.onNodeWithText(
            "Android blendet den gesperrten privaten Bereich systemweit aus. " +
                "Nach dem Entsperren erscheint er wieder im App-Bereich.",
        ).performScrollTo().assertExists()
        compose.onNodeWithTag("settings_unlock_private_space")
            .performScrollTo()
            .assertExists()
    }

    private fun privateAppsState(locked: Boolean, visible: Boolean): AppsUiState = AppsUiState(
        apps = listOf(
            AppUiModel("org.personal", "org.personal/.Main", "Personal"),
            AppUiModel(
                "org.private",
                "org.private/.Main",
                "Private",
                profileId = PRIVATE_ID,
                profileType = LaunchProfileType.PRIVATE,
            ),
        ),
        profiles = listOf(
            AppProfileUiModel(
                PERSONAL_LAUNCH_PROFILE_ID,
                LaunchProfileType.PERSONAL,
                locked = false,
            ),
            AppProfileUiModel(PRIVATE_ID, LaunchProfileType.PRIVATE, locked),
        ),
        privateSpace = PrivateSpaceUiState(
            availability = PrivateSpaceAvailability.AVAILABLE,
            profileId = PRIVATE_ID,
            locked = locked,
            containerVisible = visible,
        ),
    )

    private fun settingsCallbacks(
        onVisibilityChanged: (Boolean) -> Unit = {},
    ) = SettingsUiCallbacks(
        onBack = {},
        onStartGettingToKnow = {},
        onModelSelected = {},
        onReasoningEffortSelected = {},
        onVoiceSelected = {},
        onSpeechRateSelected = {},
        onReadAloudModeSelected = {},
        onPreviewVoice = {},
        onStartActionKeySetup = {},
        onStartModelToggleKeySetup = {},
        onCancelActionKeySetup = {},
        onClearActionKey = {},
        onClearModelToggleKey = {},
        onCapabilityAccessRequested = {},
        onPrivateSpaceVisibilityChanged = onVisibilityChanged,
    )

    private companion object {
        const val PRIVATE_ID = "profile_0123456789abcdef0123456789abcdef"
    }
}
