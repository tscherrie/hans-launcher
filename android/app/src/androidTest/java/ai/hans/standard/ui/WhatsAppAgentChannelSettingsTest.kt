package ai.hans.standard.ui

import ai.hans.standard.R
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** UI-only fixtures; no enrollment, notifications, credentials or WhatsApp/device interaction. */
class WhatsAppAgentChannelSettingsTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun channelStartsClosedAndConfigureClickDoesNotPretendEnrollmentSucceeded() {
        val events = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                WhatsAppAgentChannelSettings(WhatsAppAgentChannelUiState(),
                    onConfigure = { events += "configure" }, onDisable = { events += "disable" })
            }
        }

        compose.onNodeWithTag("whatsapp_agent_channel_status")
            .assertTextEquals(context.getString(R.string.agent_channel_disabled))
        compose.onNodeWithTag("disable_whatsapp_agent_channel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), events) }
        compose.onNodeWithTag("configure_whatsapp_agent_channel").assertIsEnabled().performClick()
        compose.onNodeWithTag("whatsapp_agent_channel_status")
            .assertTextEquals(context.getString(R.string.agent_channel_disabled))
        compose.runOnIdle { assertEquals(listOf("configure"), events) }
    }

    @Test fun effectiveEnabledStateShowsCountsAndDisableOnlyChangesAfterStateUpdate() {
        val state = mutableStateOf(WhatsAppAgentChannelUiState(enabled = true, pendingCount = 2, uncertainCount = 1, lookupRequiredCount = 1))
        val events = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                WhatsAppAgentChannelSettings(state.value,
                    onConfigure = { events += "configure" }, onDisable = { events += "disable" })
            }
        }

        compose.onNodeWithTag("whatsapp_agent_channel_status")
            .assertTextEquals(context.getString(R.string.agent_channel_enabled))
        compose.onNodeWithText(context.getString(R.string.agent_channel_pending, 2)).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.agent_channel_uncertain, 1)).assertIsDisplayed()
        compose.onNodeWithTag("whatsapp_agent_channel_incomplete")
            .assertTextEquals(context.getString(R.string.agent_channel_lookup_required))
        compose.onNodeWithTag("disable_whatsapp_agent_channel").performClick()
        compose.onNodeWithTag("whatsapp_agent_channel_status")
            .assertTextEquals(context.getString(R.string.agent_channel_enabled))
        compose.runOnIdle {
            assertEquals(listOf("disable"), events)
            state.value = WhatsAppAgentChannelUiState()
        }
        compose.onNodeWithTag("whatsapp_agent_channel_status")
            .assertTextEquals(context.getString(R.string.agent_channel_disabled))
        compose.onNodeWithTag("disable_whatsapp_agent_channel").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.agent_channel_pending, 2)).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.agent_channel_uncertain, 1)).assertDoesNotExist()
        compose.onNodeWithTag("whatsapp_agent_channel_incomplete").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf("disable"), events) }
    }

    @Test fun unavailableStatePreventsConfigurationAndDoesNotReportEnabled() {
        val events = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                WhatsAppAgentChannelSettings(WhatsAppAgentChannelUiState(available = false),
                    onConfigure = { events += "configure" }, onDisable = { events += "disable" })
            }
        }

        compose.onNodeWithTag("whatsapp_agent_channel_status")
            .assertTextEquals(context.getString(R.string.agent_channel_unavailable))
        compose.onNodeWithTag("configure_whatsapp_agent_channel").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("disable_whatsapp_agent_channel").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<String>(), events) }
    }

    @Test fun smallSettingsViewportAndLargeFontKeepDisclosureAndDisableReachable() {
        val events = mutableListOf<String>()
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).height(320.dp)) {
                        // SettingsScreen owns scrolling; the section must remain fully scrollable.
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            WhatsAppAgentChannelSettings(
                                WhatsAppAgentChannelUiState(enabled = true, pendingCount = 2, uncertainCount = 1),
                                onConfigure = { events += "configure" }, onDisable = { events += "disable" })
                        }
                    }
                }
            }
        }

        compose.onNodeWithText(context.getString(R.string.agent_channel_disclosure)).assertExists()
        compose.onNodeWithTag("whatsapp_agent_channel_status").performScrollTo()
            .assertIsDisplayed().assertTextEquals(context.getString(R.string.agent_channel_enabled))
        compose.onNodeWithTag("configure_whatsapp_agent_channel").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("disable_whatsapp_agent_channel").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf("disable"), events) }
    }
}
