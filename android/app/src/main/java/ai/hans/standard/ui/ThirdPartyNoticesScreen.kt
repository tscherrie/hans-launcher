package ai.hans.standard.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ThirdPartyNoticesScreen(onBack: () -> Unit, loaderOverride: ThirdPartyNoticesLoader? = null) {
    val assets = LocalContext.current.applicationContext.assets
    val loader = remember(assets, loaderOverride) { loaderOverride ?: ThirdPartyNoticesLoader(assets::open) }
    var components by remember { mutableStateOf<List<ThirdPartyNoticeComponent>?>(null) }
    var selected by remember { mutableStateOf<ThirdPartyNoticeText?>(null) }
    var content by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(loader, selected) {
        content = null
        failed = false
        try {
            val current = selected
            if (current == null) components = withContext(Dispatchers.IO) { loader.loadIndex() }
            else content = withContext(Dispatchers.IO) { loader.loadText(current) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            failed = true
        }
    }
    val back: () -> Unit = { if (selected != null) selected = null else onBack() }
    BackHandler(onBack = back)
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).testTag("third_party_notices")) {
        ScreenHeader(title = "Open-Source-Lizenzen", onBack = back)
        Text("Originaltexte der enthaltenen Quellen. Diese Ansicht ist keine Aussage über eine vollständige Lizenzprüfung.",
            modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
        if (failed) {
            Text("Die Lizenztexte sind derzeit nicht verfügbar oder konnten nicht geprüft werden. Bitte zurückgehen und später erneut versuchen.",
                Modifier.padding(16.dp).testTag("third_party_notices_unavailable"))
        } else if (selected != null) {
            val text = content
            if (text == null) Text("Lizenztext wird geladen …", Modifier.padding(16.dp))
            else SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text(text, modifier = Modifier.testTag("third_party_notice_original"), style = MaterialTheme.typography.bodySmall)
            }
        } else {
            val list = components
            if (list == null) Text("Lizenzübersicht wird geladen …", Modifier.padding(16.dp))
            else LazyColumn(Modifier.weight(1f).testTag("third_party_notice_list")) {
                items(list, key = { it.id }) { component ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text(component.label, style = MaterialTheme.typography.titleMedium)
                        component.texts.forEach { text ->
                            TextButton(onClick = { selected = text }) { Text(text.label) }
                        }
                    }
                }
            }
        }
    }
}
