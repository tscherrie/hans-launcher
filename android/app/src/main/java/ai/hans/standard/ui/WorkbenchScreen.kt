package ai.hans.standard.ui

import ai.hans.standard.R
import ai.hans.standard.localization.HansTextResolver
import ai.hans.standard.localization.AndroidHansTextResolver
import ai.hans.standard.localization.rememberHansTextResolver
import androidx.compose.ui.res.stringResource

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Explicit, event-driven inventory of Hans work products. The owner refreshes this state only
 * when the screen opens, after a completed operation, or when the user taps refresh. This
 * composable contains no timer, watcher, animation or runtime-start side effect.
 */
@Composable
fun WorkbenchScreen(
    state: WorkbenchUiState,
    callbacks: WorkbenchUiCallbacks,
    modifier: Modifier = Modifier,
) {
    val uiText = rememberHansTextResolver()
    BackHandler(onBack = callbacks.onBack)
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("workbench_screen"),
    ) {
        ScreenHeader(title = uiText.text(R.string.ui_workbench_a16db3), onBack = callbacks.onBack)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        OutlinedButton(
            onClick = callbacks.onRefresh,
            enabled = !state.loading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("refresh_workbench"),
        ) {
            Text(if (state.loading) uiText.text(R.string.ui_checking_workbench_da99c6) else uiText.text(R.string.ui_check_again_adbde2))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("workbench_inventory"),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.loading) {
                item(key = "loading") {
                    WorkbenchNotice(
                        text = uiText.text(R.string.ui_checking_local_workspaces_f0e523),
                        testTag = "workbench_loading",
                    )
                }
            }
            if (state.errorMessage.isNotBlank()) {
                item(key = "error") {
                    WorkbenchNotice(
                        text = state.errorMessage,
                        testTag = "workbench_error",
                        error = true,
                    )
                }
            }

            item(key = "workspace_header") {
                WorkbenchSectionHeader(
                    title = uiText.text(R.string.ui_workspaces_06cdd6),
                    summary = inventorySummary(
                        count = state.workspaces.size,
                        bytes = state.workspaces.sumOf(WorkbenchWorkspaceUiModel::byteCount),
                        truncated = state.workspacesTruncated,
                        uiText = uiText,
                    ),
                    testTag = "workbench_workspaces_header",
                )
            }
            if (state.workspaces.isEmpty() && !state.loading) {
                item(key = "workspace_empty") {
                    WorkbenchNotice(
                        text = uiText.text(R.string.ui_no_workspaces_yet_d9a4f5),
                        testTag = "workbench_workspaces_empty",
                    )
                }
            } else {
                items(state.workspaces, key = { it.handle }) { workspace ->
                    WorkbenchCard(
                        title = uiText.text(R.string.ui_workspace_value_6656aa, shortHandle(workspace.handle)),
                        lines = listOf(
                            "Handle: ${workspace.handle}",
                            uiText.quantity(R.plurals.ui_workspace_files, workspace.fileCount, workspace.fileCount, formatWorkbenchBytes(workspace.byteCount, uiText.locale)),
                            uiText.text(R.string.ui_content_verified_206be2),
                        ),
                        testTag = "workspace_${workspace.handle}",
                    )
                }
            }

            item(key = "artifact_header") {
                WorkbenchSectionHeader(
                    title = uiText.text(R.string.ui_results_12ba6a),
                    summary = inventorySummary(
                        count = state.artifacts.size,
                        bytes = state.artifacts.sumOf(WorkbenchArtifactUiModel::byteCount),
                        truncated = state.artifactsTruncated,
                        uiText = uiText,
                    ),
                    testTag = "workbench_artifacts_header",
                )
            }
            if (state.artifacts.isEmpty() && !state.loading) {
                item(key = "artifact_empty") {
                    WorkbenchNotice(
                        text = uiText.text(R.string.ui_no_results_yet_23be1a),
                        testTag = "workbench_artifacts_empty",
                    )
                }
            } else {
                items(state.artifacts, key = { it.handle }) { artifact ->
                    WorkbenchCard(
                        title = artifact.displayName,
                        lines = buildList {
                            add("Handle: ${artifact.handle}")
                            add("${artifact.mimeType} · ${formatWorkbenchBytes(artifact.byteCount, uiText.locale)}")
                            add(uiText.text(R.string.ui_source_value_content_verified_b09dd4, artifact.originLabel))
                            artifact.workspaceHandle?.let { add(uiText.text(R.string.ui_workspace_value_1411de, it)) }
                        },
                        testTag = "artifact_${artifact.handle}",
                    )
                }
            }

            item(key = "python") {
                WorkbenchSectionHeader(
                    title = "Python",
                    summary = state.python.phaseLabel.ifBlank { uiText.text(R.string.presentation_python_not_started) },
                    testTag = "workbench_python_header",
                )
                val runtimeLines = buildList {
                    if (!state.python.initialized) {
                        add(uiText.text(R.string.ui_this_view_did_not_start_the_runtime_3ff385))
                    } else {
                        add(
                            when {
                                state.python.ready -> uiText.text(R.string.ui_runtime_verified_and_ready_f699c1)
                                state.python.startsOnDemand ->
                                    uiText.text(R.string.ui_starts_and_is_verified_automatically_for_the_nex_42604a)
                                else -> uiText.text(R.string.ui_runtime_not_ready_e138c1)
                            },
                        )
                        state.python.pythonVersion.takeIf(String::isNotBlank)?.let {
                            add("Python $it")
                        }
                        state.python.detail.takeIf(String::isNotBlank)?.let(::add)
                    }
                }
                runtimeLines.forEach { line ->
                    Text(
                        text = line,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            item(key = "git") {
                WorkbenchSectionHeader(
                    title = "Git",
                    summary = uiText.text(R.string.ui_local_git_tools_available_508a63),
                    testTag = "workbench_git_header",
                )
                Text(
                    text = stringResource(R.string.ui_repositories_are_not_yet_inventoried_passively_t_d0578f),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun WorkbenchSectionHeader(
    title: String,
    summary: String,
    testTag: String,
) {
    val uiText = rememberHansTextResolver()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp)
            .semantics(mergeDescendants = true) {
                heading()
                this[SemanticsProperties.TestTag] = testTag
            },
    ) {
        Text(text = title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
        Text(
            text = summary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun WorkbenchCard(
    title: String,
    lines: List<String>,
    testTag: String,
) {
    val uiText = rememberHansTextResolver()
    Surface(
        modifier = Modifier.fillMaxWidth().testTag(testTag),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            lines.forEach { line ->
                Text(
                    text = line,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun WorkbenchNotice(
    text: String,
    testTag: String,
    error: Boolean = false,
) {
    val uiText = rememberHansTextResolver()
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp).testTag(testTag),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
}

internal fun formatWorkbenchBytes(bytes: Long, locale: Locale): String {
    require(bytes >= 0L)
    if (bytes < 1_024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1_024.0 && unit < units.lastIndex) {
        value /= 1_024.0
        unit += 1
    }
    return String.format(locale, if (value >= 10.0) "%.0f %s" else "%.1f %s", value, units[unit])
}

private fun inventorySummary(count: Int, bytes: Long, truncated: Boolean, uiText: HansTextResolver): String =
    uiText.text(if (truncated) R.string.ui_inventory_at_least else R.string.ui_inventory_verified,
        count, formatWorkbenchBytes(bytes, uiText.locale))

private fun shortHandle(handle: String): String =
    if (handle.length <= 12) handle else "${handle.take(8)}…${handle.takeLast(4)}"
