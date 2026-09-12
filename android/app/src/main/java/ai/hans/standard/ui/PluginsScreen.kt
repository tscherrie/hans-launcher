package ai.hans.standard.ui

import ai.hans.standard.plugins.PluginConnectionActionKind
import ai.hans.standard.plugins.PluginRemoteMcpPolicyDecisionSubmission
import ai.hans.standard.plugins.PluginRemoteMcpPolicyEffect
import ai.hans.standard.plugins.PluginRemoteMcpPolicyReviewSubmission
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun PluginsScreen(
    state: PluginsUiState,
    callbacks: PluginsUiCallbacks,
    modifier: Modifier = Modifier,
) {
    val showingDetails = state.selectedPluginId != null
    val navigateBack = if (showingDetails) callbacks.onCloseDetails else callbacks.onBack
    BackHandler(onBack = navigateBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ScreenHeader(
            title = state.selectedPlugin?.name ?: if (showingDetails) "Plugin" else "Plugins",
            onBack = navigateBack,
            backLabel = if (showingDetails) "Schließen" else "Zurück",
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)

        if (showingDetails) {
            PluginDetails(
                state = state,
                callbacks = callbacks,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PluginCatalog(
                state = state,
                callbacks = callbacks,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun PluginCatalog(
    state: PluginsUiState,
    callbacks: PluginsUiCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        PluginListSelector(
            selected = state.selectedList,
            onSelected = callbacks.onListSelected,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )

        OutlinedButton(
            onClick = callbacks.onRefreshMarketplaces,
            enabled = !state.marketplaceRefreshing,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp)
                .testTag("refresh_marketplaces"),
        ) {
            Text(
                if (state.marketplaceRefreshing) {
                    "Marketplaces werden aktualisiert …"
                } else {
                    "Marketplaces aktualisieren"
                },
            )
        }
        if (state.marketplaceMessage.isNotBlank()) {
            Text(
                text = state.marketplaceMessage,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp)
                    .testTag("marketplace_message"),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (state.pluginReadPending) {
            Text(
                text = "Plugin-Details werden noch geladen …",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp)
                    .testTag("plugin_read_pending"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        state.connectionAction?.let { action ->
            when (action.kind) {
                PluginConnectionActionKind.CONNECT_REMOTE_MCP -> RemoteMcpConnectionAction(
                    action = action,
                    onAction = {
                        callbacks.onConnectRemoteMcp(action.pluginId, action.serverId)
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
                PluginConnectionActionKind.REVIEW_REMOTE_MCP_POLICY ->
                    RemoteMcpPolicyReviewAction(
                        action = action,
                        submissionPending = state.remoteMcpPolicyReviewPending,
                        onSubmit = callbacks.onReviewRemoteMcpPolicy,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                PluginConnectionActionKind.RETRY_INSTALL -> RemoteMcpConnectionAction(
                    action = action,
                    onAction = {
                        callbacks.onRetryRemoteMcpInstall(action.pluginId, action.serverId)
                    },
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        val plugins = when (state.selectedList) {
            PluginListKind.INSTALLED -> state.installed
            PluginListKind.AVAILABLE -> state.available
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("plugin_list"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 14.dp,
                end = 14.dp,
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (plugins.isEmpty()) {
                item(key = "empty_plugins") {
                    EmptyPluginList(kind = state.selectedList)
                }
            } else {
                items(
                    items = plugins,
                    key = { it.id },
                ) { plugin ->
                    PluginCard(
                        plugin = plugin,
                        kind = state.selectedList,
                        isOperating = state.operationPluginId == plugin.id,
                        actionsBlocked = state.pluginReadPending &&
                            state.selectedList == PluginListKind.INSTALLED,
                        onAction = {
                            when (state.selectedList) {
                                PluginListKind.INSTALLED -> callbacks.onOpenInstalled(plugin.id)
                                PluginListKind.AVAILABLE -> callbacks.onInstallAvailable(plugin.id)
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RemoteMcpPolicyReviewAction(
    action: PluginConnectionActionUiModel,
    submissionPending: Boolean,
    onSubmit: (PluginRemoteMcpPolicyReviewSubmission) -> Unit,
    modifier: Modifier = Modifier,
) {
    val review = action.policyReview ?: return
    var showReview by remember(
        action.pluginId,
        action.serverId,
        review.catalogDigest,
        review.policyStoreRevision,
    ) { mutableStateOf(false) }
    RemoteMcpConnectionAction(
        action = action,
        onAction = { showReview = true },
        enabled = !submissionPending,
        modifier = modifier,
    )
    if (showReview) {
        var decisions by remember(
            action.pluginId,
            action.serverId,
            review.catalogDigest,
            review.policyStoreRevision,
        ) { mutableStateOf<Map<String, PluginRemoteMcpPolicyEffect>>(emptyMap()) }
        var latestDecisionToolName by remember(
            action.pluginId,
            action.serverId,
            review.catalogDigest,
            review.policyStoreRevision,
        ) { mutableStateOf<String?>(null) }
        Dialog(onDismissRequest = { if (!submissionPending) showReview = false }) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 680.dp)
                    .testTag("remote_mcp_policy_review"),
                shape = MaterialTheme.shapes.extraLarge,
                tonalElevation = 6.dp,
            ) {
                Column(Modifier.padding(18.dp)) {
                    Text(
                        "Werkzeugwirkung einstufen",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Stufe die Wirkung jedes Werkzeugs selbst ein. Angaben des Servers sind nur Hinweise und werden nie automatisch übernommen.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    latestDecisionToolName?.let { toolName ->
                        val effect = decisions[toolName] ?: return@let
                        val tool = review.tools.firstOrNull { it.name == toolName }
                            ?: return@let
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("remote_mcp_policy_feedback")
                                .semantics { liveRegion = LiveRegionMode.Polite },
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Text(
                                text = buildString {
                                    append(tool.title?.takeIf(String::isNotBlank) ?: tool.name)
                                    append(": ")
                                    append(
                                        if (effect == PluginRemoteMcpPolicyEffect.MUTATING) {
                                            "Erfolg wird vom verbundenen Server bestätigt; Hans beobachtet keine unabhängige Android-Nachwirkung."
                                        } else {
                                            "Das ist keine technische Schreibsperre. Wähle dies nur, wenn du dem Werkzeug vertraust, nichts zu verändern."
                                        },
                                    )
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier
                                    .padding(10.dp)
                                    .testTag(
                                        if (effect == PluginRemoteMcpPolicyEffect.MUTATING) {
                                            "remote_mcp_server_confirmation_$toolName"
                                        } else {
                                            "remote_mcp_read_only_warning_$toolName"
                                        },
                                    ),
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(review.tools, key = { it.name }) { tool ->
                            val selectedEffect = decisions[tool.name]
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("remote_mcp_policy_tool_${tool.name}"),
                                shape = MaterialTheme.shapes.small,
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(7.dp),
                                ) {
                                    Text(
                                        tool.title?.takeIf(String::isNotBlank) ?: tool.name,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    tool.description?.takeIf(String::isNotBlank)?.let { description ->
                                        Text(
                                            "Serverbeschreibung (unbestätigt): $description",
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.testTag(
                                                "remote_mcp_description_${tool.name}",
                                            ),
                                        )
                                    }
                                    Text(
                                        remoteMcpDeclaredHintsText(tool.declaredHints),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        PolicyEffectButton(
                                            label = "Als lesend vertrauen",
                                            selected = selectedEffect ==
                                                PluginRemoteMcpPolicyEffect.READ_ONLY,
                                            enabled = !submissionPending,
                                            onClick = {
                                                decisions = decisions +
                                                    (tool.name to PluginRemoteMcpPolicyEffect.READ_ONLY)
                                                latestDecisionToolName = tool.name
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("remote_mcp_read_only_${tool.name}"),
                                        )
                                        PolicyEffectButton(
                                            label = "Darf verändern",
                                            selected = selectedEffect ==
                                                PluginRemoteMcpPolicyEffect.MUTATING,
                                            enabled = !submissionPending,
                                            onClick = {
                                                decisions = decisions +
                                                    (tool.name to PluginRemoteMcpPolicyEffect.MUTATING)
                                                latestDecisionToolName = tool.name
                                            },
                                            modifier = Modifier
                                                .weight(1f)
                                                .testTag("remote_mcp_mutating_${tool.name}"),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = {
                            val exactDecisions = review.tools.map { tool ->
                                PluginRemoteMcpPolicyDecisionSubmission(
                                    toolName = tool.name,
                                    toolMetadataDigest = tool.metadataDigest,
                                    effect = requireNotNull(decisions[tool.name]),
                                )
                            }
                            onSubmit(
                                PluginRemoteMcpPolicyReviewSubmission(
                                    pluginId = action.pluginId,
                                    serverId = action.serverId,
                                    review = review,
                                    decisions = exactDecisions,
                                ),
                            )
                        },
                        enabled = !submissionPending && decisions.keys.containsAll(
                            review.tools.map { it.name },
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("approve_remote_mcp_policy"),
                    ) {
                        Text(if (submissionPending) "Wird geprüft …" else "Zugriffe speichern")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showReview = false },
                        enabled = !submissionPending,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cancel_remote_mcp_policy"),
                    ) {
                        Text("Abbrechen")
                    }
                }
            }
        }
    }
}

@Composable
private fun PolicyEffectButton(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    }
}

private fun remoteMcpDeclaredHintsText(
    hints: ai.hans.standard.plugins.PluginRemoteMcpDeclaredHintsSnapshot,
): String {
    val parts = buildList {
        hints.readOnly?.let { add(if (it) "laut Server nur lesend" else "laut Server nicht nur lesend") }
        hints.destructive?.let { add(if (it) "möglicherweise zerstörerisch" else "nicht als zerstörerisch markiert") }
        hints.idempotent?.let { add(if (it) "wiederholbar" else "nicht als wiederholbar markiert") }
        hints.openWorld?.let { add(if (it) "kann externe Daten nutzen" else "ohne offene Außenwelt markiert") }
    }
    return if (parts.isEmpty()) "Der Server liefert keine Wirkungshinweise." else
        "Serverhinweis: ${parts.joinToString(" · ")}"
}

@Composable
private fun RemoteMcpConnectionAction(
    action: PluginConnectionActionUiModel,
    onAction: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("remote_mcp_connection_action"),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = action.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = action.message,
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                onClick = onAction,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("remote_mcp_connection_button"),
            ) {
                Text(action.actionLabel)
            }
        }
    }
}

@Composable
private fun PluginDetails(
    state: PluginsUiState,
    callbacks: PluginsUiCallbacks,
    modifier: Modifier = Modifier,
) {
    val pluginId = state.selectedPluginId ?: return
    val detail = state.selectedPlugin
    when {
        detail != null -> PluginDetailContent(
            detail = detail,
            callbacks = callbacks,
            modifier = modifier,
        )
        state.selectedPluginLoading -> StaticPluginDetailNotice(
            text = "Plugin-Details werden geladen …",
            testTag = "plugin_details_loading",
            modifier = modifier,
        )
        else -> Column(
            modifier = modifier.padding(horizontal = 18.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = state.selectedPluginErrorMessage.ifBlank {
                    "Die Plugin-Details sind nicht verfügbar."
                },
                modifier = Modifier.testTag("plugin_details_error"),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
            OutlinedButton(
                onClick = { callbacks.onOpenInstalled(pluginId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("retry_plugin_details"),
            ) {
                Text("Erneut laden")
            }
        }
    }
}

@Composable
private fun StaticPluginDetailNotice(
    text: String,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 32.dp)
            .testTag(testTag),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
}

@Composable
private fun PluginDetailContent(
    detail: PluginDetailUiModel,
    callbacks: PluginsUiCallbacks,
    modifier: Modifier = Modifier,
) {
    var confirmUninstall by remember(detail.id) { mutableStateOf(false) }
    val mutationPending = detail.skillChangePending ||
        detail.uninstallPending ||
        detail.uninstallConfirmationPending

    LaunchedEffect(detail.id, mutationPending) {
        if (mutationPending) confirmUninstall = false
    }

    Column(modifier = modifier.testTag("plugin_details")) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("plugin_details_body"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 18.dp,
                bottom = 18.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item(key = "plugin_summary") {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (detail.description.isNotBlank()) {
                        Text(detail.description, style = MaterialTheme.typography.bodyLarge)
                    }
                    if (detail.marketplaceName.isNotBlank()) {
                        Text(
                            text = "Marketplace: ${detail.marketplaceName}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    Text(
                        text = "${detail.hookCount} Hooks · ${detail.mcpServerCount} MCP-Server · " +
                            "${detail.scheduledTaskCount} Automationen",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    detail.shareUrl?.let { url ->
                        OutlinedButton(
                            onClick = { callbacks.onOpenPluginLink(url) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("open_plugin_share_link"),
                        ) {
                            Text("Plugin-Link öffnen")
                        }
                    }
                }
            }

            item(key = "apps_heading") {
                DetailSectionHeading("Apps und Verbindungen")
            }
            if (detail.apps.isEmpty()) {
                item(key = "apps_empty") {
                    Text(
                        "Dieses Plugin meldet keine Apps.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(detail.apps, key = { "app:${it.id}" }) { app ->
                    PluginAppRow(app = app, onOpenLink = callbacks.onOpenPluginLink)
                }
            }

            item(key = "skills_heading") {
                DetailSectionHeading("Skills")
            }
            if (detail.skills.isEmpty()) {
                item(key = "skills_empty") {
                    Text(
                        "Dieses Plugin meldet keine Skills.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(detail.skills, key = { "skill:${it.name}" }) { skill ->
                    PluginSkillRow(
                        skill = skill,
                        enabled = !mutationPending,
                        onEnabledChanged = { desired ->
                            callbacks.onPluginSkillEnabledChanged(detail.id, skill.name, desired)
                        },
                    )
                }
            }

            if (detail.operationErrorMessage.isNotBlank()) {
                item(key = "operation_error") {
                    Text(
                        text = detail.operationErrorMessage,
                        modifier = Modifier.testTag("plugin_operation_error"),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        PluginMutationFooter(
            detail = detail,
            mutationPending = mutationPending,
            onRequestUninstall = { confirmUninstall = true },
        )
    }

    if (confirmUninstall && !mutationPending) {
        PluginUninstallConfirmation(
            pluginName = detail.name,
            onConfirm = {
                confirmUninstall = false
                callbacks.onUninstallPlugin(detail.id)
            },
            onCancel = { confirmUninstall = false },
        )
    }
}

@Composable
private fun PluginMutationFooter(
    detail: PluginDetailUiModel,
    mutationPending: Boolean,
    onRequestUninstall: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("plugin_mutation_footer"),
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (detail.skillChangePending) {
                StaticOperationNotice(
                    "Die Skill-Änderung wird bestätigt …",
                    "skill_change_pending",
                )
            }
            if (detail.uninstallPending || detail.uninstallConfirmationPending) {
                StaticOperationNotice(
                    if (detail.uninstallPending) {
                        "Die Deinstallation wird angefragt …"
                    } else {
                        "Die Deinstallation wird im Plugin-Katalog bestätigt …"
                    },
                    "plugin_uninstall_pending",
                )
            }
            OutlinedButton(
                onClick = onRequestUninstall,
                enabled = !mutationPending,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("uninstall_plugin"),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text("Plugin deinstallieren")
            }
        }
    }
}

@Composable
private fun PluginUninstallConfirmation(
    pluginName: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .testTag("plugin_uninstall_confirmation"),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Plugin deinstallieren?",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    Text(
                        text = "$pluginName wird erst nach deiner Bestätigung entfernt.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onConfirm,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("confirm_plugin_uninstall"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text("Deinstallieren")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cancel_plugin_uninstall"),
                ) {
                    Text("Abbrechen")
                }
            }
        }
    }
}

@Composable
private fun DetailSectionHeading(text: String) {
    Text(
        text = text,
        fontWeight = FontWeight.SemiBold,
        style = MaterialTheme.typography.headlineSmall,
    )
}

@Composable
private fun PluginAppRow(
    app: PluginAppUiModel,
    onOpenLink: (String) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("plugin_app_${app.id}"),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(app.name, fontWeight = FontWeight.SemiBold)
            if (app.description.isNotBlank()) {
                Text(
                    app.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            app.connectionUrl?.let { url ->
                OutlinedButton(
                    onClick = { onOpenLink(url) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("connect_plugin_app_${app.id}")
                        .semantics {
                            contentDescription = "Mit ${app.name} verbinden"
                        },
                ) {
                    Text("Verbinden")
                }
            }
        }
    }
}

@Composable
private fun PluginSkillRow(
    skill: PluginSkillUiModel,
    enabled: Boolean,
    onEnabledChanged: (Boolean) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("plugin_skill_${skill.name}"),
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(skill.displayName, fontWeight = FontWeight.SemiBold)
                if (skill.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        skill.description,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Switch(
                checked = skill.enabled,
                onCheckedChange = onEnabledChanged,
                enabled = enabled,
                modifier = Modifier
                    .testTag("plugin_skill_toggle_${skill.name}")
                    .semantics {
                        contentDescription = "Skill ${skill.displayName}"
                    },
            )
        }
    }
}

@Composable
private fun StaticOperationNotice(text: String, testTag: String) {
    Text(
        text = text,
        modifier = Modifier.testTag(testTag),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    backLabel: String = "Zurück",
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.testTag("navigate_back"),
        ) {
            Text(backLabel)
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.headlineLarge,
        )
    }
}

@Composable
private fun PluginListSelector(
    selected: PluginListKind,
    onSelected: (PluginListKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PluginListKind.entries.forEach { kind ->
            val label = when (kind) {
                PluginListKind.INSTALLED -> "Installiert"
                PluginListKind.AVAILABLE -> "Verfügbar"
            }
            if (selected == kind) {
                Button(
                    onClick = { onSelected(kind) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("plugins_${kind.name.lowercase()}"),
                ) {
                    Text(label)
                }
            } else {
                OutlinedButton(
                    onClick = { onSelected(kind) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("plugins_${kind.name.lowercase()}"),
                ) {
                    Text(label)
                }
            }
        }
    }
}

@Composable
private fun EmptyPluginList(kind: PluginListKind) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 30.dp),
    ) {
        Text(
            text = when (kind) {
                PluginListKind.INSTALLED -> "Noch keine Plugins installiert"
                PluginListKind.AVAILABLE -> "Keine weiteren Plugins verfügbar"
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Die Liste wird direkt aus der Codex-Runtime übernommen.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun PluginCard(
    plugin: PluginUiModel,
    kind: PluginListKind,
    isOperating: Boolean,
    actionsBlocked: Boolean,
    onAction: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("plugin_${plugin.id}"),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(15.dp)) {
            Text(
                text = plugin.name,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleLarge,
            )
            if (plugin.description.isNotBlank()) {
                Spacer(Modifier.height(5.dp))
                Text(
                    text = plugin.description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (plugin.statusLabel.isNotBlank() || isOperating) {
                Spacer(Modifier.height(9.dp))
                Text(
                    text = if (isOperating) "Wird angewendet …" else plugin.statusLabel,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onAction,
                enabled = plugin.actionEnabled && !isOperating && !actionsBlocked,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("plugin_action_${plugin.id}"),
            ) {
                Text(
                    when (kind) {
                        PluginListKind.INSTALLED -> "Verwalten"
                        PluginListKind.AVAILABLE -> "Installieren"
                    },
                )
            }
        }
    }
}
