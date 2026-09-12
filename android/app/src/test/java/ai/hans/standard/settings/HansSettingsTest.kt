package ai.hans.standard.settings

import ai.hans.standard.codex.DispatchOptions
import ai.hans.standard.phone.keys.ActionKeyTrigger
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class HansSettingsTest {
    @Test
    fun defaultsMatchTheProductContract() {
        val settings = HansSettings()

        assertEquals("gpt-6-astra", settings.model)
        assertEquals("medium", settings.reasoningEffort)
        assertEquals(HansSettings.DEFAULT_SERVICE_TIER, settings.serviceTier)
        assertEquals(ActiveTurnInputMode.STEER, settings.activeTurnInputMode)
        assertEquals("fable", settings.voice)
        assertEquals(1.25f, settings.speechRate)
        assertEquals(
            ReadAloudMode.ALL_VISIBLE_ASSISTANT_MESSAGES,
            settings.readAloudMode,
        )
        assertEquals(ActionKeyTrigger.PRESS, settings.dictationKeyTrigger)
        assertFalse(settings.cameraHoldToTalkEnabled)
    }

    @Test
    fun astraIsTheDefaultWithoutRemovingExistingModelChoices() {
        assertEquals("gpt-6-astra", HansSettings(model = "gpt-6-astra").model)
        assertEquals("gpt-6-astra", HansSettings().model)
        assertEquals(
            listOf("gpt-5.6-luna", "gpt-5.6-terra", "gpt-5.6-sol", "gpt-6-astra"),
            HansSettings.MODEL_ORDER,
        )
    }

    @Test
    fun assetAndWireDispatchDefaultsMatchFreshSettingsWithoutEnablingFast() {
        val relativePath = "android/app/src/main/assets/hans/default-settings.json"
        val start = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        val asset = generateSequence(start) { it.parentFile }
            .map { File(it, relativePath) }
            .first { it.isFile }
        val json = JSONObject(asset.readText())
        val settings = HansSettings()

        assertEquals(settings.model, json.getString("model"))
        assertEquals(settings.reasoningEffort, json.getString("reasoningEffort"))
        assertEquals(settings.model, DispatchOptions.DEFAULT.model)
        assertEquals(settings.reasoningEffort, DispatchOptions.DEFAULT.effort.wireValue)
        assertEquals(settings.serviceTier, DispatchOptions.DEFAULT.serviceTier)
        assertEquals("default", settings.serviceTier)
        assertEquals(settings.activeTurnInputMode.wireValue, json.getString("activeTurnInputMode"))
        assertEquals(settings.voice, json.getString("voice"))
        assertEquals(settings.speechRate.toDouble(), json.getDouble("speechRate"), 0.0)
        assertEquals(settings.readAloudMode.wireValue, json.getString("readAloudMode"))
    }

    @Test
    fun unsupportedRuntimeSelectionsCannotBePersistedAsConfirmed() {
        assertThrows(IllegalArgumentException::class.java) {
            HansSettings(model = "gpt-5.6-unknown")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HansSettings(reasoningEffort = "enormous")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HansSettings(serviceTier = "unadvertised")
        }
    }

    @Test
    fun voiceAndRateAreBounded() {
        assertThrows(IllegalArgumentException::class.java) {
            HansSettings(voice = "bad voice")
        }
        assertThrows(IllegalArgumentException::class.java) {
            HansSettings(speechRate = 2.1f)
        }
    }
}
