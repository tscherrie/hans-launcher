package ai.hans.standard.ui

import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test

/**
 * A real owned Activity and the inherited production callback, with a fake session owner.
 * Dispatch is explicit, not an OS locale change: this does not claim framework locale delivery,
 * whole Launcher integration, device language mutation or process-recreation persistence.
 */
class LocaleActivityLifecycleTest {
    @get:Rule val compose = createAndroidComposeRule<LocaleConfigurationProbeActivity>()

    @Test
    fun directConfigurationCallbacksKeepActivityHostAndPendingComposerWithoutActions() {
        val originalActivity = compose.activity
        val originalHost = originalActivity.host
        for ((index, tags) in listOf("de-DE", "en-US", "ar-EG").withIndex()) {
            compose.activityRule.scenario.onActivity { activity ->
                activity.onConfigurationChanged(Configuration(activity.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(tags))
                })
            }
            compose.onNodeWithTag("composer")
                .assertTextEquals(LocaleConfigurationProbeActivity.DRAFT).assertIsEnabled()
            compose.onNodeWithText("Original attachment.jpg").assertExists()
            compose.onNodeWithTag("interrupt_codex_work")
                .assertContentDescriptionEquals(if (tags == "de-DE") "Unterbrechen angefordert" else "Stop requested")
                .assertIsNotEnabled()
            compose.activityRule.scenario.onActivity { activity ->
                assertSame(originalActivity, activity)
                assertSame(originalHost, activity.host)
                assertEquals(1, activity.contentInstallCount)
                assertEquals(index + 1, activity.refreshCount)
                assertEquals(0, activity.host.sendCount)
                assertEquals(0, activity.host.interruptCount)
                assertEquals(0, activity.host.liveStartCount)
                assertEquals(0, activity.host.editCount)
                assertEquals(44L, activity.host.state.workInterrupt.revision)
                assertEquals("opaque-retained-attachment", activity.host.state.composer.attachments.single().id)
            }
        }
    }
}
