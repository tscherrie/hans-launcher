package ai.hans.standard.ui

import ai.hans.standard.phone.consent.PersistentAndroidConsentDescriptor
import ai.hans.standard.phone.consent.PersistentAndroidConsentScope
import ai.hans.standard.phone.consent.HansPhoneActionPolicy
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class SettingsPersistentAndroidConsentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun fullAccessExplainsAndroidBoundaryAndHidesIneffectiveEverydayControls() {
        val everyday = PersistentAndroidConsentDescriptor.category(
            PersistentAndroidConsentScope.INSTALLED_APPS_READ,
        )
        var requested = 0
        val revoked = mutableListOf<PersistentAndroidConsentDescriptor>()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        phoneActionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
                        persistentAndroidConsents = listOf(
                            PersistentAndroidConsentUiModel(everyday, "Installierte Apps lesen", "Bis Widerruf"),
                        ),
                    ),
                    callbacks = callbacks(
                        onRevoke = revoked::add,
                        onEverydayRequested = { error("Full access must not modify everyday grants") },
                        onLinkMetadataRequested = { requested += 1 },
                    ),
                )
            }
        }

        compose.onNodeWithTag("settings_group_permissions").performScrollTo().performClick()
        compose.onNodeWithText("Vollzugriff / YOLO").performScrollTo().assertExists()
        compose.onNodeWithText(
            "Keine zusätzlichen Hans-Rückfragen für deine Aufträge. Android-Berechtigungen und Bedienungshilfen bleiben erforderlich und in den Android-Einstellungen widerrufbar. Die optionale Link-Vorschau bleibt eine separate Einwilligung.",
        ).performScrollTo().assertExists()
        compose.onNodeWithTag("enable_everyday_access_bundle").assertDoesNotExist()
        compose.onNodeWithTag("persistent_android_consent_${everyday.localDisplayKey()}").assertDoesNotExist()
        compose.onNodeWithTag("revoke_all_persistent_android_consents").assertDoesNotExist()
        compose.onNodeWithTag("enable_notification_link_metadata").performScrollTo().performClick()
        assertEquals(1, requested)
        assertEquals(emptyList<PersistentAndroidConsentDescriptor>(), revoked)
    }

    @Test
    fun fullAccessStillAllowsExactLinkConsentRevocationAndExplicitReenable() {
        val link = PersistentAndroidConsentDescriptor.category(
            PersistentAndroidConsentScope.NOTIFICATION_LINK_METADATA,
        )
        val everyday = PersistentAndroidConsentDescriptor.category(PersistentAndroidConsentScope.OPEN_APP)
        val revoked = mutableListOf<PersistentAndroidConsentDescriptor>()
        var active by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        phoneActionPolicy = HansPhoneActionPolicy.USER_AUTHORIZED_FULL_ACCESS,
                        persistentAndroidConsents = buildList {
                            add(PersistentAndroidConsentUiModel(everyday, "Apps öffnen", "Alltag"))
                            if (active) add(PersistentAndroidConsentUiModel(link, "Link-Vorschau", "Separate Einwilligung"))
                        },
                    ),
                    callbacks = callbacks(onRevoke = {
                        revoked += it
                        active = false
                    }),
                )
            }
        }

        compose.onNodeWithTag("settings_group_permissions").performScrollTo().performClick()
        compose.onNodeWithTag("enable_notification_link_metadata").assertDoesNotExist()
        compose.onNodeWithTag("revoke_persistent_android_consent_${link.localDisplayKey()}")
            .performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(link), revoked)
        compose.onNodeWithTag("enable_notification_link_metadata").performScrollTo().assertExists()
        compose.onNodeWithTag("revoke_all_persistent_android_consents").assertDoesNotExist()
        compose.onNodeWithTag("enable_everyday_access_bundle").assertDoesNotExist()
    }

    @Test
    fun activeConsentExplainsSystemBoundaryAndCanBeRevokedExactly() {
        val descriptor = PersistentAndroidConsentDescriptor.category(
            PersistentAndroidConsentScope.INSTALLED_APPS_READ,
        )
        val revoked = mutableListOf<PersistentAndroidConsentDescriptor>()
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(
                        persistentAndroidConsents = listOf(
                            PersistentAndroidConsentUiModel(
                                descriptor,
                                "Installierte Apps lesen",
                                "Gilt bis zum Widerruf.",
                            ),
                        ),
                    ),
                    callbacks = callbacks(onRevoke = revoked::add),
                )
            }
        }

        compose.onNodeWithTag("settings_group_permissions").performScrollTo().performClick()

        compose.onNodeWithText(
            "Einzelne Hans-Freigaben sind aktiv. Android-Systemberechtigungen sind davon getrennt und werden nie automatisch bestätigt.",
        ).performScrollTo().assertExists()
        compose.onNodeWithTag(
            "revoke_persistent_android_consent_${descriptor.localDisplayKey()}",
        ).performScrollTo().performClick()
        assertEquals(listOf(descriptor), revoked)
    }

    @Test
    fun everydayBundleCanBeExplicitlyEnabledAndActiveStateIsVisible() {
        var requested = 0
        var active by mutableStateOf(false)
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(everydayAccessBundleActive = active),
                    callbacks = callbacks(
                        onRevoke = {},
                        onEverydayRequested = {
                            requested += 1
                            active = true
                        },
                    ),
                )
            }
        }

        compose.onNodeWithTag("settings_group_permissions").performScrollTo().performClick()

        compose.onNodeWithTag("enable_everyday_access_bundle")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, requested)
        compose.waitForIdle()
        compose.onNodeWithTag("enable_everyday_access_bundle")
            .performScrollTo()
            .assertIsNotEnabled()
        compose.onNodeWithText("Alltagszugriff aktiv").assertExists()
    }

    @Test
    fun linkMetadataIsSeparateDefaultOffOptInWithNetworkDisclosure() {
        var requested = 0
        compose.setContent {
            MaterialTheme {
                SettingsScreen(
                    state = SettingsUiState(),
                    callbacks = callbacks(
                        onRevoke = {},
                        onLinkMetadataRequested = { requested += 1 },
                    ),
                )
            }
        }

        compose.onNodeWithTag("settings_group_permissions").performScrollTo().performClick()

        compose.onNodeWithTag("notification_link_metadata_disclosure")
            .performScrollTo()
            .assertExists()
        compose.onNodeWithText(
            "Optionale Link-Vorschau für wichtige Pushs: Hans kann sichere öffentliche HTTPS-Seiten ohne Cookies oder JavaScript nur für Titel und Beschreibung abrufen. Der Zielserver sieht dabei IP-Adresse und Zeitpunkt. Unsichere oder aktionsartige Links bleiben stumm.",
        ).assertExists()
        compose.onNodeWithTag("enable_notification_link_metadata")
            .performScrollTo()
            .performClick()
        assertEquals(1, requested)
    }

    private fun callbacks(
        onRevoke: (PersistentAndroidConsentDescriptor) -> Unit,
        onEverydayRequested: () -> Unit = {},
        onLinkMetadataRequested: () -> Unit = {},
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
        onEverydayAccessBundleRequested = onEverydayRequested,
        onNotificationLinkMetadataConsentRequested = onLinkMetadataRequested,
        onPersistentAndroidConsentRevoked = onRevoke,
    )
}
