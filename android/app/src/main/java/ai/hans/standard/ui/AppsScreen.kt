package ai.hans.standard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import ai.hans.standard.phone.capabilities.LaunchProfileType

internal val AppsSearchCursorBlinkEnabledKey =
    SemanticsPropertyKey<Boolean>("HansAppsSearchCursorBlinkEnabled")

private var SemanticsPropertyReceiver.appsSearchCursorBlinkEnabled by
    AppsSearchCursorBlinkEnabledKey

@Composable
fun AppsScreen(
    state: AppsUiState,
    callbacks: AppsUiCallbacks,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = callbacks.onBack)
    val searchFocusRequester = remember { FocusRequester() }
    val cursorBlinkEnabled = LocalCursorBlinkEnabled.current
    val normalizedQuery = state.query.trim()
    val sections = appUiSections(state)

    // Every navigation entry is a fresh lookup. This effect belongs to the lifetime of
    // this destination, so catalogue/query recompositions while the user stays here do
    // not erase active typing or steal focus back from another control. Keep Compose's
    // normal caret here; the home Composer disables it locally.
    LaunchedEffect(Unit) {
        if (state.query.isNotEmpty()) callbacks.onQueryChanged("")
        searchFocusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        ScreenHeader(title = "Apps", onBack = callbacks.onBack)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        OutlinedTextField(
            value = state.query,
            onValueChange = callbacks.onQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .testTag("app_search")
                .semantics { appsSearchCursorBlinkEnabled = cursorBlinkEnabled }
                .focusRequester(searchFocusRequester),
            placeholder = { Text("App suchen") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )

        when {
            state.loading -> AppDrawerNotice("Apps werden geladen …")
            state.errorMessage.isNotBlank() -> {
                Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 20.dp)) {
                    Text(
                        text = state.errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = callbacks.onRefresh) {
                        Text("Erneut laden")
                    }
                }
            }
            sections.none { it.locked || it.apps.isNotEmpty() } -> AppDrawerNotice(
                if (normalizedQuery.isEmpty()) "Keine startbaren Apps gefunden."
                else "Keine passende App gefunden.",
            )
            else -> LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("app_drawer"),
            ) {
                sections.forEach { section ->
                    item(key = "profile_header_${section.profileId}") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 12.dp)
                                .testTag("profile_${section.type.name.lowercase()}"),
                        ) {
                            Text(
                                text = section.title,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (section.type == LaunchProfileType.PRIVATE) {
                                Spacer(Modifier.height(6.dp))
                                PrivateSpaceActions(
                                    state = state.privateSpace,
                                    callbacks = callbacks,
                                )
                            }
                        }
                    }
                    if (section.locked) {
                        item(key = "profile_locked_${section.profileId}") {
                            Text(
                                text = if (section.type == LaunchProfileType.PRIVATE) {
                                    "Privater Bereich gesperrt."
                                } else {
                                    "${section.title} gesperrt."
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 15.dp)
                                    .testTag("private_profile_locked"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    } else if (
                        section.type == LaunchProfileType.PRIVATE && section.apps.isEmpty()
                    ) {
                        item(key = "profile_empty_${section.profileId}") {
                            Text(
                                text = "Keine Apps im privaten Bereich.",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp, vertical = 15.dp)
                                    .testTag("private_profile_empty"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    } else {
                        items(
                            items = section.apps,
                            key = { app -> "${app.profileId}:${app.componentName}" },
                        ) { app ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        callbacks.onLaunch(app.packageName, app.profileId)
                                    }
                                    .padding(horizontal = 20.dp, vertical = 15.dp)
                                    .testTag("app_${app.profileId}_${app.packageName}"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = app.label.ifBlank { app.packageName },
                                        fontWeight = FontWeight.SemiBold,
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    Text(
                                        text = app.packageName,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }
}

internal data class AppUiSection(
    val profileId: String,
    val type: LaunchProfileType,
    val title: String,
    val locked: Boolean,
    val apps: List<AppUiModel>,
)

/** Pure fail-closed projection: locked or unknown profiles never reach search or rendering. */
internal fun appUiSections(state: AppsUiState): List<AppUiSection> {
    val query = state.query.trim()
    val profiles = state.profiles
        .distinctBy(AppProfileUiModel::profileId)
        .sortedWith(
            compareBy<AppProfileUiModel>(
                { it.type != LaunchProfileType.PERSONAL },
                { it.type.ordinal },
                { it.profileId },
            ),
        )
    return profiles.mapNotNull { profile ->
        if (
            profile.type == LaunchProfileType.PRIVATE &&
            (!state.privateSpace.effectiveContainerVisible ||
                state.privateSpace.profileId != profile.profileId ||
                !state.privateSpace.profilePresent)
        ) {
            return@mapNotNull null
        }
        val apps = if (profile.locked) {
            emptyList()
        } else {
            state.apps
                .asSequence()
                .filter { it.profileId == profile.profileId && it.profileType == profile.type }
                .filter { app ->
                    query.isEmpty() ||
                        app.label.contains(query, ignoreCase = true) ||
                        app.packageName.contains(query, ignoreCase = true)
                }
                .sortedWith(compareBy({ it.label.lowercase() }, { it.packageName }, { it.componentName }))
                .toList()
        }
        if (
            apps.isEmpty() && !profile.locked && profile.type != LaunchProfileType.PRIVATE
        ) return@mapNotNull null
        AppUiSection(
            profileId = profile.profileId,
            type = profile.type,
            title = when (profile.type) {
                LaunchProfileType.PERSONAL -> "Persönlich"
                LaunchProfileType.PRIVATE -> "Privater Bereich"
                LaunchProfileType.WORK -> "Arbeitsprofil"
                LaunchProfileType.CLONE -> "Klonprofil"
                LaunchProfileType.OTHER -> "Weiteres Profil"
            },
            locked = profile.locked,
            apps = apps,
        )
    }
}

@Composable
private fun PrivateSpaceActions(
    state: PrivateSpaceUiState,
    callbacks: AppsUiCallbacks,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val profileId = state.profileId
        TextButton(
            onClick = { callbacks.onPrivateSpaceVisibilityChanged(false) },
            enabled = !state.operationInProgress,
            modifier = Modifier.testTag("hide_private_space"),
        ) {
            Text("Ausblenden")
        }
        if (profileId != null) {
            TextButton(
                onClick = {
                    callbacks.onPrivateSpaceLockChanged(profileId, !state.locked)
                },
                enabled = state.canChangeLock,
                modifier = Modifier.testTag(
                    if (state.locked) "unlock_private_space" else "lock_private_space",
                ),
            ) {
                Text(if (state.locked) "Entsperren" else "Sperren")
            }
        }
        if (state.settingsAvailable) {
            TextButton(
                onClick = callbacks.onOpenPrivateSpaceSettings,
                enabled = !state.operationInProgress,
                modifier = Modifier.testTag("open_private_space_settings"),
            ) {
                Text("Verwalten")
            }
        }
    }
    if (state.operationInProgress) {
        Text(
            text = "Privater Bereich wird aktualisiert …",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("private_space_operation_pending"),
        )
    } else if (state.notice.isNotBlank()) {
        Text(
            text = state.notice,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("private_space_notice"),
        )
    }
}

@Composable
private fun AppDrawerNotice(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyLarge,
    )
}
