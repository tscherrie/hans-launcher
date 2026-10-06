package ai.hans.standard.ui

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale

/** Preserve legacy German-copy assertions without changing the device or process locale. */
internal fun germanUiTestContext(): Context {
    val base = InstrumentationRegistry.getInstrumentation().targetContext
    return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
        setLocales(LocaleList(Locale.GERMAN))
    })
}

internal fun ComposeContentTestRule.setGermanContent(content: @Composable () -> Unit) {
    val context = germanUiTestContext()
    setContent {
        CompositionLocalProvider(
            LocalContext provides context,
            LocalConfiguration provides context.resources.configuration,
            // Popup/Dialog Android owners use the host View's context. Keep their
            // stringResource calls on this fixture locale as well as the main content.
            LocalResources provides context.resources,
            content = content,
        )
    }
}
