package ai.hans.standard.ui

import android.view.KeyEvent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import ai.hans.standard.plugins.PluginConnectionActionKind
import ai.hans.standard.plugins.PluginRemoteMcpDeclaredHintsSnapshot
import ai.hans.standard.plugins.PluginRemoteMcpPolicyEffect
import ai.hans.standard.plugins.PluginRemoteMcpPolicyReviewSnapshot
import ai.hans.standard.plugins.PluginRemoteMcpPolicyReviewSubmission
import ai.hans.standard.plugins.PluginRemoteMcpPolicyToolSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PluginsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun catalogExposesMarketplaceRefreshAndManageIntent() {
        val id = "a".repeat(64)
        var refreshes = 0
        var openedHandle = ""
        compose.setContent {
            MaterialTheme {
                PluginsScreen(
                    state = PluginsUiState(
                        installed = listOf(PluginUiModel(id = id, name = "Telefon")),
                    ),
                    callbacks = callbacks(
                        onRefreshMarketplaces = { refreshes += 1 },
                        onOpenInstalled = { openedHandle = it },
                    ),
                )
            }
        }

        compose.onNodeWithTag("refresh_marketplaces").performClick()
        compose.onNodeWithTag("plugin_action_$id").performClick()
        compose.runOnIdle {
            assertEquals(1, refreshes)
            assertEquals(id, openedHandle)
        }
    }

    @Test
    fun detailsExposeSafeLinksAndConfirmedSkillToggleWithoutOptimism() {
        val id = "b".repeat(64)
        val openedLinks = mutableListOf<String>()
        var skillIntent: Triple<String, String, Boolean>? = null
        compose.setContent {
            MaterialTheme {
                PluginsScreen(
                    state = detailState(id),
                    callbacks = callbacks(
                        onOpenPluginLink = openedLinks::add,
                        onPluginSkillEnabledChanged = { handle, skill, enabled ->
                            skillIntent = Triple(handle, skill, enabled)
                        },
                    ),
                )
            }
        }

        compose.onNodeWithTag("plugin_details").assertExists()
        compose.onNodeWithContentDescription("Mit Drive verbinden").assertExists()
        compose.onNodeWithContentDescription("Skill Telefon").assertExists()
        compose.onNodeWithTag("connect_plugin_app_drive").performClick()
        compose.onNodeWithTag("open_plugin_share_link").performClick()
        compose.onNodeWithTag("plugin_details_body").performScrollToNode(
            hasTestTag("plugin_skill_toggle_phone-skill"),
        )
        compose.onNodeWithTag("plugin_skill_toggle_phone-skill")
            .assertIsOff()
            .performClick()
            .assertIsOff()

        compose.runOnIdle {
            assertEquals(
                listOf("https://example.com/connect", "https://example.com/plugin"),
                openedLinks,
            )
            assertEquals(Triple(id, "phone-skill", true), skillIntent)
        }
        compose.onNodeWithText(id).assertDoesNotExist()
    }

    @Test
    fun detailCloseAndSystemBackNeverLeaveThePluginScreenDirectly() {
        val id = "c".repeat(64)
        var closeDetails = 0
        var leavePlugins = 0
        compose.setContent {
            MaterialTheme {
                PluginsScreen(
                    state = detailState(id),
                    callbacks = callbacks(
                        onBack = { leavePlugins += 1 },
                        onCloseDetails = { closeDetails += 1 },
                    ),
                )
            }
        }

        compose.onNodeWithTag("navigate_back").performClick()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals(2, closeDetails)
            assertEquals(0, leavePlugins)
        }
    }

    @Test
    fun uninstallRequiresExplicitConfirmation() {
        val id = "d".repeat(64)
        var uninstalls = 0
        compose.setContent {
            MaterialTheme {
                PluginsScreen(
                    state = detailState(id),
                    callbacks = callbacks(onUninstallPlugin = { uninstalls += 1 }),
                )
            }
        }

        compose.onNodeWithTag("uninstall_plugin").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, uninstalls) }
        compose.onNodeWithTag("confirm_plugin_uninstall").assertIsDisplayed()
        compose.onNodeWithTag("cancel_plugin_uninstall").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, uninstalls) }

        compose.onNodeWithTag("uninstall_plugin").assertIsDisplayed().performClick()
        compose.onNodeWithTag("confirm_plugin_uninstall").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, uninstalls) }
    }

    @Test
    fun pendingMutationDisablesEveryConflictingControl() {
        val id = "e".repeat(64)
        compose.setContent {
            MaterialTheme {
                PluginsScreen(
                    state = detailState(id).copy(
                        selectedPlugin = detailState(id).selectedPlugin!!.copy(
                            skillChangePending = true,
                        ),
                    ),
                    callbacks = callbacks(),
                )
            }
        }

        compose.onNodeWithTag("plugin_details_body").performScrollToNode(
            hasTestTag("plugin_skill_toggle_phone-skill"),
        )
        compose.onNodeWithTag("plugin_skill_toggle_phone-skill").assertIsNotEnabled()
        compose.onNodeWithTag("uninstall_plugin").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithTag("skill_change_pending").assertIsDisplayed()
    }

    @Test
    fun largeFontAndOverflowingDetailsKeepMutationActionsReachable() {
        val id = "g".repeat(64)
        val base = detailState(id).selectedPlugin!!
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                MaterialTheme {
                    PluginsScreen(
                        state = detailState(id).copy(
                            selectedPlugin = base.copy(
                                apps = (1..12).map { index ->
                                    PluginAppUiModel(
                                        id = "app-$index",
                                        name = "Verbindung $index",
                                        description = "Ausführliche Beschreibung der Verbindung $index",
                                    )
                                },
                            ),
                        ),
                        callbacks = callbacks(),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        compose.onNodeWithTag("plugin_mutation_footer").assertIsDisplayed()
        compose.onNodeWithTag("uninstall_plugin").assertIsDisplayed().performClick()
        compose.onNodeWithTag("confirm_plugin_uninstall").assertIsDisplayed()
        compose.onNodeWithTag("cancel_plugin_uninstall").assertIsDisplayed()
    }

    @Test
    fun anotherPluginCannotOpenWhileAnyDetailReadIsPending() {
        val id = "f".repeat(64)
        compose.setContent {
            MaterialTheme {
                PluginsScreen(
                    state = PluginsUiState(
                        installed = listOf(PluginUiModel(id = id, name = "Telefon")),
                        pluginReadPending = true,
                    ),
                    callbacks = callbacks(),
                )
            }
        }

        compose.onNodeWithTag("plugin_read_pending").assertExists()
        compose.onNodeWithTag("plugin_action_$id").assertIsNotEnabled()
    }

    @Test
    fun remoteMcpConnectAndPostOAuthRetryAreExplicitSeparateActions() {
        var connected: Pair<String, String>? = null
        var retried: Pair<String, String>? = null
        val state = mutableStateOf(PluginsUiState(
            connectionAction = PluginConnectionActionUiModel(
                pluginId = "tasks-plugin",
                serverId = "tasks",
                kind = PluginConnectionActionKind.CONNECT_REMOTE_MCP,
                title = "Verbindung erforderlich",
                message = "Verbinde den Dienst.",
                actionLabel = "Verbinden",
            ),
        ))
        compose.setContent {
            MaterialTheme {
                PluginsScreen(
                    state = state.value,
                    callbacks = callbacks(
                        onConnectRemoteMcp = { pluginId, serverId ->
                            connected = pluginId to serverId
                        },
                        onRetryRemoteMcpInstall = { pluginId, serverId ->
                            retried = pluginId to serverId
                        },
                    ),
                )
            }
        }

        compose.onNodeWithTag("remote_mcp_connection_button").performClick()
        compose.runOnIdle {
            assertEquals("tasks-plugin" to "tasks", connected)
            assertEquals(null, retried)
            state.value = state.value.copy(
                connectionAction = state.value.connectionAction?.copy(
                    kind = PluginConnectionActionKind.RETRY_INSTALL,
                    actionLabel = "Installation erneut versuchen",
                ),
            )
        }
        compose.onNodeWithText("Installation erneut versuchen").performClick()
        compose.runOnIdle {
            assertEquals("tasks-plugin" to "tasks", retried)
        }
    }

    @Test
    fun remoteMcpPolicyRequiresAnExplicitDecisionForEveryToolAndLabelsServerConfirmation() {
        var submitted: PluginRemoteMcpPolicyReviewSubmission? = null
        val review = PluginRemoteMcpPolicyReviewSnapshot(
            configurationDigest = "a".repeat(64),
            sourceDigest = "b".repeat(64),
            catalogDigest = "c".repeat(64),
            policyStoreRevision = 7L,
            tools = listOf(
                PluginRemoteMcpPolicyToolSnapshot(
                    name = "tasks.create",
                    title = "Aufgabe erstellen",
                    declaredHints = PluginRemoteMcpDeclaredHintsSnapshot(
                        readOnly = false,
                        destructive = false,
                        idempotent = false,
                        openWorld = true,
                    ),
                    metadataDigest = "d".repeat(64),
                    description = "Erstellt eine Aufgabe im Serverkonto",
                ),
                PluginRemoteMcpPolicyToolSnapshot(
                    name = "tasks.read",
                    title = "Aufgaben lesen",
                    declaredHints = PluginRemoteMcpDeclaredHintsSnapshot(
                        readOnly = true,
                        destructive = false,
                        idempotent = true,
                        openWorld = true,
                    ),
                    metadataDigest = "e".repeat(64),
                ),
            ),
        )
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(density = 2.625f, fontScale = 1.15f),
            ) {
                MaterialTheme {
                    PluginsScreen(
                        state = PluginsUiState(
                            connectionAction = PluginConnectionActionUiModel(
                                pluginId = "tasks-plugin",
                                serverId = "tasks",
                                kind = PluginConnectionActionKind.REVIEW_REMOTE_MCP_POLICY,
                                title = "Werkzeugzugriffe prüfen",
                                message = "Wähle die Wirkung jedes Werkzeugs.",
                                actionLabel = "Zugriffe prüfen",
                                policyReview = review,
                            ),
                        ),
                        callbacks = callbacks(onReviewRemoteMcpPolicy = { submitted = it }),
                    )
                }
            }
        }

        compose.onNodeWithTag("remote_mcp_connection_button").performClick()
        compose.onNodeWithTag("remote_mcp_policy_review").assertIsDisplayed()
        compose.onNodeWithTag("remote_mcp_description_tasks.create").assertIsDisplayed()
        compose.onNodeWithText(
            "Serverbeschreibung (unbestätigt): Erstellt eine Aufgabe im Serverkonto",
        ).assertIsDisplayed()
        compose.onNodeWithTag("approve_remote_mcp_policy").assertIsNotEnabled()
        compose.onNodeWithTag("remote_mcp_mutating_tasks.create").performClick()
        compose.onNodeWithTag("remote_mcp_policy_feedback")
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
        compose.onNodeWithTag("remote_mcp_server_confirmation_tasks.create").assertIsDisplayed()
        compose.onNodeWithText(
            "Aufgabe erstellen: Erfolg wird vom verbundenen Server bestätigt; Hans beobachtet keine unabhängige Android-Nachwirkung.",
        ).assertIsDisplayed()
        compose.onNodeWithTag("approve_remote_mcp_policy").assertIsNotEnabled()
        compose.onNodeWithTag("remote_mcp_read_only_tasks.read")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.onNodeWithTag("remote_mcp_server_confirmation_tasks.create")
            .assertDoesNotExist()
        compose.onNodeWithTag("remote_mcp_policy_feedback").assertIsDisplayed()
        compose.onNodeWithTag("remote_mcp_read_only_warning_tasks.read").assertIsDisplayed()
        compose.onNodeWithText(
            "Aufgaben lesen: Das ist keine technische Schreibsperre. Wähle dies nur, wenn du dem Werkzeug vertraust, nichts zu verändern.",
        ).assertIsDisplayed()
        compose.onNodeWithTag("approve_remote_mcp_policy").assertIsEnabled().performClick()

        compose.runOnIdle {
            val exact = checkNotNull(submitted)
            assertEquals("tasks-plugin", exact.pluginId)
            assertEquals("tasks", exact.serverId)
            assertEquals(review, exact.review)
            assertEquals(
                listOf(
                    "tasks.create" to PluginRemoteMcpPolicyEffect.MUTATING,
                    "tasks.read" to PluginRemoteMcpPolicyEffect.READ_ONLY,
                ),
                exact.decisions.map { it.toolName to it.effect },
            )
        }
    }

    private fun detailState(id: String) = PluginsUiState(
        selectedPluginId = id,
        selectedPlugin = PluginDetailUiModel(
            id = id,
            name = "Telefon",
            description = "Telefonfunktionen",
            marketplaceName = "Hans",
            apps = listOf(
                PluginAppUiModel(
                    id = "drive",
                    name = "Drive",
                    description = "Dateien verbinden",
                    connectionUrl = "https://example.com/connect",
                ),
            ),
            skills = listOf(
                PluginSkillUiModel(
                    name = "phone-skill",
                    displayName = "Telefon",
                    description = "Steuert das Telefon",
                    enabled = false,
                ),
            ),
            hookCount = 1,
            mcpServerCount = 2,
            scheduledTaskCount = 3,
            shareUrl = "https://example.com/plugin",
        ),
    )

    private fun callbacks(
        onBack: () -> Unit = {},
        onOpenInstalled: (String) -> Unit = {},
        onCloseDetails: () -> Unit = {},
        onOpenPluginLink: (String) -> Unit = {},
        onPluginSkillEnabledChanged: (String, String, Boolean) -> Unit = { _, _, _ -> },
        onRefreshMarketplaces: () -> Unit = {},
        onUninstallPlugin: (String) -> Unit = {},
        onConnectRemoteMcp: (String, String) -> Unit = { _, _ -> },
        onReviewRemoteMcpPolicy: (PluginRemoteMcpPolicyReviewSubmission) -> Unit = {},
        onRetryRemoteMcpInstall: (String, String) -> Unit = { _, _ -> },
    ) = PluginsUiCallbacks(
        onBack = onBack,
        onListSelected = {},
        onOpenInstalled = onOpenInstalled,
        onInstallAvailable = {},
        onCloseDetails = onCloseDetails,
        onOpenPluginLink = onOpenPluginLink,
        onPluginSkillEnabledChanged = onPluginSkillEnabledChanged,
        onRefreshMarketplaces = onRefreshMarketplaces,
        onUninstallPlugin = onUninstallPlugin,
        onConnectRemoteMcp = onConnectRemoteMcp,
        onReviewRemoteMcpPolicy = onReviewRemoteMcpPolicy,
        onRetryRemoteMcpInstall = onRetryRemoteMcpInstall,
    )
}
