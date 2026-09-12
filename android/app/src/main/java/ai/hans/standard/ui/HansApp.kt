package ai.hans.standard.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Navigation and every setting remain runtime-owned. The root keeps only the call's local
 * expanded/compact presentation across navigation, never the call or microphone state.
 */
@Composable
fun HansApp(
    state: HansUiState,
    callbacks: HansUiCallbacks,
    modifier: Modifier = Modifier,
) {
    val liveCallMinimized = rememberLiveCallMinimized(state.chat.liveVoiceStatus != null)
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
            when (state.destination) {
                HansDestination.AUTH_GATE -> AuthGateScreen(
                    state = state.authGate,
                    callbacks = callbacks.authGate,
                    modifier = Modifier.widthIn(max = 760.dp),
                )

                HansDestination.CHAT, HansDestination.SETTINGS -> ChatScreen(
                    state = state.chat,
                    callbacks = callbacks.chat,
                    sidebarSettings = state.settings,
                    sidebarCallbacks = callbacks.settings,
                    displayMotionMode = state.settings.displayMotionMode,
                    sidebarRequested = state.destination == HansDestination.SETTINGS,
                    onSidebarClosed = callbacks.settings.onBack,
                    liveCallMinimizedState = liveCallMinimized,
                    modifier = Modifier.widthIn(max = 760.dp),
                )

                HansDestination.APPS -> AppsScreen(
                    state = state.apps,
                    callbacks = callbacks.apps,
                    modifier = Modifier.widthIn(max = 760.dp),
                )

                HansDestination.PLUGINS -> PluginsScreen(
                    state = state.plugins,
                    callbacks = callbacks.plugins,
                    modifier = Modifier.widthIn(max = 760.dp),
                )

                HansDestination.AUTOMATIONS -> AutomationsScreen(
                    state = state.automations,
                    callbacks = callbacks.automations,
                    modifier = Modifier.widthIn(max = 760.dp),
                )

                HansDestination.WORKBENCH -> WorkbenchScreen(
                    state = state.workbench,
                    callbacks = callbacks.workbench,
                    modifier = Modifier.widthIn(max = 760.dp),
                )

            }
        }
    }
}
