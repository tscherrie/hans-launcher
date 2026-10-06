package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver
import ai.hans.standard.localization.AndroidHansTextResolver
import ai.hans.standard.localization.rememberHansTextResolver
import androidx.compose.ui.res.stringResource

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
    val uiText = rememberHansTextResolver()
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
            backLabel = if (showingDetails) uiText.text(R.string.ui_close_b808f6) else uiText.text(R.string.ui_back_548611),
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
    val uiText = rememberHansTextResolver()
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
                    uiText.text(R.string.ui_refreshing_marketplaces_511714)
                } else {
                    uiText.text(R.string.ui_refresh_marketplaces_0f281f)
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
                text = stringResource(R.string.ui_plugin_details_are_still_loading_c4d14a),
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
    val uiText = rememberHansTextResolver()
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
                        stringResource(R.string.ui_classify_tool_effects_570f6f),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.ui_classify_each_tool_s_effects_yourself_server_dec_bc70bc),
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
                                            uiText.text(R.string.ui_success_is_confirmed_by_the_connected_server_han_aeb8f4)
                                        } else {
                                            uiText.text(R.string.ui_this_is_not_a_technical_write_restriction_select_c92d06)
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
                                            stringResource(R.string.ui_server_description_unverified_value_8e5c77, description),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.testTag(
                                                "remote_mcp_description_${tool.name}",
                                            ),
                                        )
                                    }
                                    Text(
                                        remoteMcpDeclaredHintsText(tool.declaredHints, uiText),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        PolicyEffectButton(
                                            label = uiText.text(R.string.ui_trust_as_read_only_a0f2a1),
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
                                            label = uiText.text(R.string.ui_may_make_changes_2391a4),
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
                        Text(if (submissionPending) uiText.text(R.string.ui_checking_490232) else uiText.text(R.string.ui_save_access_rules_3f5d14))
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { showReview = false },
                        enabled = !submissionPending,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cancel_remote_mcp_policy"),
                    ) {
                        Text(stringResource(R.string.ui_cancel_f7ff11))
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
    val uiText = rememberHansTextResolver()
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(label) }
    }
}

private fun remoteMcpDeclaredHintsText(
    hints: ai.hans.standard.plugins.PluginRemoteMcpDeclaredHintsSnapshot,
    uiText: HansTextResolver,
): String {
    val parts = buildList {
        hints.readOnly?.let { add(if (it) uiText.text(R.string.ui_read_only_according_to_the_server_4e680f) else uiText.text(R.string.ui_not_read_only_according_to_the_server_439387)) }
        hints.destructive?.let { add(if (it) uiText.text(R.string.ui_potentially_destructive_e27435) else uiText.text(R.string.ui_not_marked_as_destructive_4b46b4)) }
        hints.idempotent?.let { add(if (it) uiText.text(R.string.ui_repeatable) else uiText.text(R.string.ui_not_marked_as_repeatable_bd8955)) }
        hints.openWorld?.let { add(if (it) uiText.text(R.string.ui_may_use_external_data_0a945c) else uiText.text(R.string.ui_marked_as_not_open_world_e11ae8)) }
    }
    return if (parts.isEmpty()) uiText.text(R.string.ui_the_server_provides_no_effect_hints_868f01) else
        uiText.text(R.string.ui_server_hint, parts.joinToString(" · "))
}

@Composable
private fun RemoteMcpConnectionAction(
    action: PluginConnectionActionUiModel,
    onAction: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val uiText = rememberHansTextResolver()
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
    val uiText = rememberHansTextResolver()
    val pluginId = state.selectedPluginId ?: return
    val detail = state.selectedPlugin
    when {
        detail != null -> PluginDetailContent(
            detail = detail,
            callbacks = callbacks,
            modifier = modifier,
        )
        state.selectedPluginLoading -> StaticPluginDetailNotice(
            text = uiText.text(R.string.ui_loading_plugin_details_09cdf2),
            testTag = "plugin_details_loading",
            modifier = modifier,
        )
        else -> Column(
            modifier = modifier.padding(horizontal = 18.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = state.selectedPluginErrorMessage.ifBlank {
                    uiText.text(R.string.ui_plugin_details_are_unavailable_b73a37)
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
                Text(stringResource(R.string.ui_reload_1b8b90))
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
    val uiText = rememberHansTextResolver()
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
    val uiText = rememberHansTextResolver()
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
                        text = stringResource(R.string.ui_value_hooks_value_mcp_servers_d1a170, detail.hookCount, detail.mcpServerCount) +
                            uiText.text(R.string.ui_value_automations_bcaf87, detail.scheduledTaskCount),
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
                            Text(stringResource(R.string.ui_open_plugin_link_6153e1))
                        }
                    }
                }
            }

            item(key = "apps_heading") {
                DetailSectionHeading(uiText.text(R.string.ui_apps_and_connections_7b4773))
            }
            if (detail.apps.isEmpty()) {
                item(key = "apps_empty") {
                    Text(
                        stringResource(R.string.ui_this_plugin_reports_no_apps_fe075f),
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
                        stringResource(R.string.ui_this_plugin_reports_no_skills_8a3945),
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
    val uiText = rememberHansTextResolver()
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
                    uiText.text(R.string.ui_confirming_the_skill_change_883c99),
                    "skill_change_pending",
                )
            }
            if (detail.uninstallPending || detail.uninstallConfirmationPending) {
                StaticOperationNotice(
                    if (detail.uninstallPending) {
                        uiText.text(R.string.ui_requesting_uninstall_48c979)
                    } else {
                        uiText.text(R.string.ui_confirming_uninstall_in_the_plugin_catalog_82d652)
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
                Text(stringResource(R.string.ui_uninstall_plugin_9a3079))
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
    val uiText = rememberHansTextResolver()
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
                    text = stringResource(R.string.ui_uninstall_plugin_ff2901),
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
                        text = stringResource(R.string.ui_value_will_be_removed_only_after_you_confirm_1afccc, pluginName),
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
                    Text(stringResource(R.string.ui_uninstall_22d6c6))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cancel_plugin_uninstall"),
                ) {
                    Text(stringResource(R.string.ui_cancel_f7ff11))
                }
            }
        }
    }
}

@Composable
private fun DetailSectionHeading(text: String) {
    val uiText = rememberHansTextResolver()
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
    val uiText = rememberHansTextResolver()
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
                            contentDescription = uiText.text(R.string.ui_connect_to_value_11cc40, app.name)
                        },
                ) {
                    Text(stringResource(R.string.ui_connect_0eefbb))
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
    val uiText = rememberHansTextResolver()
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
    val uiText = rememberHansTextResolver()
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
    backLabel: String? = null,
) {
    val uiText = rememberHansTextResolver()
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
            Text(backLabel ?: stringResource(R.string.ui_back_548611))
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
    val uiText = rememberHansTextResolver()
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PluginListKind.entries.forEach { kind ->
            val label = when (kind) {
                PluginListKind.INSTALLED -> uiText.text(R.string.ui_installed_ec9a9b)
                PluginListKind.AVAILABLE -> uiText.text(R.string.ui_available_3aa552)
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
    val uiText = rememberHansTextResolver()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 30.dp),
    ) {
        Text(
            text = when (kind) {
                PluginListKind.INSTALLED -> uiText.text(R.string.ui_no_plugins_installed_yet_1eaea0)
                PluginListKind.AVAILABLE -> uiText.text(R.string.ui_no_additional_plugins_available_72aa62)
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.ui_this_list_comes_directly_from_the_codex_runtime_c5cb89),
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
    val uiText = rememberHansTextResolver()
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
                    text = if (isOperating) uiText.text(R.string.ui_applying_9cd183) else plugin.statusLabel,
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
                        PluginListKind.INSTALLED -> uiText.text(R.string.ui_manage_9e2096)
                        PluginListKind.AVAILABLE -> uiText.text(R.string.ui_install_f56804)
                    },
                )
            }
        }
    }
}
