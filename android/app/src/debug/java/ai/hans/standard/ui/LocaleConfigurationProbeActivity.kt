package ai.hans.standard.ui

import ai.hans.standard.BuildConfig
import ai.hans.standard.localization.HansLocaleAwareActivity
import ai.hans.standard.phone.display.DisplayMotionMode
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext

/**
 * Debug-only, non-exported target-process host for ActivityScenario. Never binds the Hans
 * runtime, opens a microphone or executes a real action; absent from the release source set.
 */
class LocaleConfigurationProbeActivity : HansLocaleAwareActivity() {
    val host = FakeLocaleSessionHost()
    var contentInstallCount = 0
        private set
    var refreshCount = 0
        private set
    private var presentationConfiguration by mutableStateOf(Configuration())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DEBUG)
        presentationConfiguration = Configuration(resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags("en-US"))
        }
        contentInstallCount++
        setContent {
            val configuration = presentationConfiguration
            val context = remember(configuration) {
                this@LocaleConfigurationProbeActivity.createConfigurationContext(configuration)
            }
            CompositionLocalProvider(
                LocalContext provides context,
                LocalConfiguration provides context.resources.configuration,
            ) {
                MaterialTheme {
                    ChatScreen(host.state, host.callbacks, displayMotionMode = DisplayMotionMode.E_INK)
                }
            }
        }
    }

    override fun refreshLocalizedPresentation(newConfig: Configuration) {
        refreshCount++
        presentationConfiguration = Configuration(newConfig)
    }

    companion object { const val DRAFT = "Unsent draft العربية %s" }
}

class FakeLocaleSessionHost {
    val state = ChatUiState(
        composer = ComposerUiState(
            text = LocaleConfigurationProbeActivity.DRAFT,
            attachments = listOf(ComposerAttachmentUiModel("opaque-retained-attachment", "Original attachment.jpg")),
        ),
        isWorking = true,
        workInterrupt = WorkInterruptUiState(visible = true, pending = true, revision = 44),
    )
    var editCount = 0
    var sendCount = 0
    var interruptCount = 0
    var liveStartCount = 0
    val callbacks = ChatUiCallbacks(
        onComposerChanged = { editCount++ }, onSend = { sendCount++ }, onChooseMedia = {},
        onRemoveAttachment = {}, onOpenApps = {}, onOpenPlugins = {}, onOpenSettings = {},
        onToggleLiveVoice = { liveStartCount++ }, onInterruptWork = { interruptCount++; true },
    )
}
