package ai.hans.standard.localization

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * For calling pure presenters from Compose. Static screen copy should use stringResource directly.
 * Reading LocalConfiguration makes locale updates observable even when no runtime snapshot changes.
 * This performs no polling, resource mutation or other side effect.
 */
@Composable
fun rememberHansTextResolver(): HansTextResolver {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    return remember(context, configuration) { AndroidHansTextResolver(context) }
}
