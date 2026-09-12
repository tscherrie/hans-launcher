package ai.hans.standard.ui

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
    BackHandler(onBack = callbacks.onBack)
    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .testTag("workbench_screen"),
    ) {
        ScreenHeader(title = "Werkbank", onBack = callbacks.onBack)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        OutlinedButton(
            onClick = callbacks.onRefresh,
            enabled = !state.loading,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .testTag("refresh_workbench"),
        ) {
            Text(if (state.loading) "Werkbank wird geprüft …" else "Neu prüfen")
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("workbench_inventory"),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.loading) {
                item(key = "loading") {
                    WorkbenchNotice(
                        text = "Lokale Arbeitsstände werden geprüft …",
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
                    title = "Arbeitsstände",
                    summary = inventorySummary(
                        count = state.workspaces.size,
                        bytes = state.workspaces.sumOf(WorkbenchWorkspaceUiModel::byteCount),
                        truncated = state.workspacesTruncated,
                    ),
                    testTag = "workbench_workspaces_header",
                )
            }
            if (state.workspaces.isEmpty() && !state.loading) {
                item(key = "workspace_empty") {
                    WorkbenchNotice(
                        text = "Noch keine Arbeitsstände vorhanden.",
                        testTag = "workbench_workspaces_empty",
                    )
                }
            } else {
                items(state.workspaces, key = { it.handle }) { workspace ->
                    WorkbenchCard(
                        title = "Arbeitsstand ${shortHandle(workspace.handle)}",
                        lines = listOf(
                            "Handle: ${workspace.handle}",
                            "${workspace.fileCount} Dateien · ${formatWorkbenchBytes(workspace.byteCount)}",
                            "Inhalt geprüft",
                        ),
                        testTag = "workspace_${workspace.handle}",
                    )
                }
            }

            item(key = "artifact_header") {
                WorkbenchSectionHeader(
                    title = "Ergebnisse",
                    summary = inventorySummary(
                        count = state.artifacts.size,
                        bytes = state.artifacts.sumOf(WorkbenchArtifactUiModel::byteCount),
                        truncated = state.artifactsTruncated,
                    ),
                    testTag = "workbench_artifacts_header",
                )
            }
            if (state.artifacts.isEmpty() && !state.loading) {
                item(key = "artifact_empty") {
                    WorkbenchNotice(
                        text = "Noch keine Ergebnisse vorhanden.",
                        testTag = "workbench_artifacts_empty",
                    )
                }
            } else {
                items(state.artifacts, key = { it.handle }) { artifact ->
                    WorkbenchCard(
                        title = artifact.displayName,
                        lines = buildList {
                            add("Handle: ${artifact.handle}")
                            add("${artifact.mimeType} · ${formatWorkbenchBytes(artifact.byteCount)}")
                            add("Quelle: ${artifact.originLabel} · Inhalt geprüft")
                            artifact.workspaceHandle?.let { add("Arbeitsstand: $it") }
                        },
                        testTag = "artifact_${artifact.handle}",
                    )
                }
            }

            item(key = "python") {
                WorkbenchSectionHeader(
                    title = "Python",
                    summary = state.python.phaseLabel,
                    testTag = "workbench_python_header",
                )
                val runtimeLines = buildList {
                    if (!state.python.initialized) {
                        add("Die Laufzeit wurde durch diese Ansicht nicht gestartet.")
                    } else {
                        add(
                            when {
                                state.python.ready -> "Laufzeit geprüft und bereit"
                                state.python.startsOnDemand ->
                                    "Wird bei der nächsten Python-Aufgabe automatisch gestartet und geprüft."
                                else -> "Laufzeit nicht bereit"
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
                    summary = "Lokale Git-Werkzeuge verfügbar",
                    testTag = "workbench_git_header",
                )
                Text(
                    text = "Der Repository-Bestand wird noch nicht passiv erfasst. Diese Ansicht behauptet daher nicht, dass keine Repositories vorhanden sind.",
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
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 12.dp).testTag(testTag),
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
}

internal fun formatWorkbenchBytes(bytes: Long): String {
    require(bytes >= 0L)
    if (bytes < 1_024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1_024.0 && unit < units.lastIndex) {
        value /= 1_024.0
        unit += 1
    }
    return String.format(Locale.GERMANY, if (value >= 10.0) "%.0f %s" else "%.1f %s", value, units[unit])
}

private fun inventorySummary(count: Int, bytes: Long, truncated: Boolean): String =
    "${if (truncated) "Mindestens " else ""}$count · ${formatWorkbenchBytes(bytes)} · geprüft"

private fun shortHandle(handle: String): String =
    if (handle.length <= 12) handle else "${handle.take(8)}…${handle.takeLast(4)}"
