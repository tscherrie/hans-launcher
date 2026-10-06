package ai.hans.standard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import ai.hans.standard.phone.keys.ActionKeyTrigger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HansSettingsStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun clearPreferences() {
        context.getSharedPreferences("hans_settings_v1", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun missingPreferencesUseAstraMediumWithoutPretendingToPersistConfirmation() {
        val preferences = context.getSharedPreferences("hans_settings_v1", Context.MODE_PRIVATE)
        val settings = SharedPreferencesHansSettingsStore(context).read()

        assertEquals("gpt-6-astra", settings.model)
        assertEquals("medium", settings.reasoningEffort)
        assertEquals(HansSettings.DEFAULT_SERVICE_TIER, settings.serviceTier)
        assertFalse(preferences.contains("model"))
        assertFalse(preferences.contains("reasoning_effort"))
    }

    @Test
    fun existingConfirmedSelectionsAreNotReplacedByNewProductDefaults() {
        val preferences = context.getSharedPreferences("hans_settings_v1", Context.MODE_PRIVATE)
        HansSettings.MODEL_ORDER.forEach { model ->
            preferences.edit()
                .putInt("schema_version", 1)
                .putString("model", model)
                .putString("reasoning_effort", "high")
                .putString("service_tier", HansSettings.FAST_SERVICE_TIER)
                .commit()

            val settings = SharedPreferencesHansSettingsStore(context).read()

            assertEquals(model, settings.model)
            assertEquals("high", settings.reasoningEffort)
            assertEquals(HansSettings.FAST_SERVICE_TIER, settings.serviceTier)
            assertEquals(model, preferences.getString("model", null))
            assertEquals("high", preferences.getString("reasoning_effort", null))
        }
    }

    @Test
    fun legacyAndCurrentSavedModelsPreserveVoiceInputSpeedAndReadAloudPreferencesWithoutMigration() {
        val preferences = context.getSharedPreferences("hans_settings_v1", Context.MODE_PRIVATE)
        for (model in HansSettings.MODEL_ORDER) {
            assertTrue(preferences.edit().clear().putInt("schema_version", 1).putString("model", model)
                .putString("reasoning_effort", "high").putString("service_tier", HansSettings.FAST_SERVICE_TIER)
                .putString("voice", "nova").putFloat("speech_rate", 1.5f)
                .putString("read_aloud_mode", ReadAloudMode.FINAL_ONLY.wireValue)
                .putString("dictation_key_trigger", ActionKeyTrigger.HOLD_TO_TALK.name)
                .putBoolean("camera_hold_to_talk", true).commit())
            val settings = SharedPreferencesHansSettingsStore(context).read()
            assertEquals(model, settings.model)
            assertEquals("high", settings.reasoningEffort)
            assertEquals(HansSettings.FAST_SERVICE_TIER, settings.serviceTier)
            assertEquals("nova", settings.voice)
            assertEquals(1.5f, settings.speechRate)
            assertEquals(ReadAloudMode.FINAL_ONLY, settings.readAloudMode)
            assertEquals(ActionKeyTrigger.HOLD_TO_TALK, settings.dictationKeyTrigger)
            assertTrue(settings.cameraHoldToTalkEnabled)
            assertEquals(model, preferences.getString("model", null))
        }
    }

    @Test
    fun missingUpgradeKeysKeepToggleAndCameraOnlyDefaults() {
        val preferences = context.getSharedPreferences("hans_settings_v1", Context.MODE_PRIVATE)
        preferences.edit()
            .putInt("schema_version", 1)
            .putString("voice", "fable")
            .commit()

        val settings = SharedPreferencesHansSettingsStore(context).read()

        assertEquals(ActionKeyTrigger.PRESS, settings.dictationKeyTrigger)
        assertFalse(settings.cameraHoldToTalkEnabled)
        assertEquals(HansSettings.DEFAULT_SERVICE_TIER, settings.serviceTier)
    }

    @Test
    fun inputControlsRoundTripTogetherWithoutChangingVoiceSettings() {
        val store = SharedPreferencesHansSettingsStore(context)
        store.saveVoice("nova", 1.5f, ReadAloudMode.FINAL_ONLY)

        val updated = store.saveInputControls(ActionKeyTrigger.HOLD_TO_TALK, true)
        val restored = SharedPreferencesHansSettingsStore(context).read()

        assertEquals(ActionKeyTrigger.HOLD_TO_TALK, updated.dictationKeyTrigger)
        assertTrue(restored.cameraHoldToTalkEnabled)
        assertEquals("nova", restored.voice)
        assertEquals(1.5f, restored.speechRate)
        assertEquals(ReadAloudMode.FINAL_ONLY, restored.readAloudMode)
    }

    @Test
    fun fastModePersistsOnlyThroughConfirmedDispatchWrites() {
        val store = SharedPreferencesHansSettingsStore(context)

        assertEquals(HansSettings.DEFAULT_SERVICE_TIER, store.read().serviceTier)
        val fast = store.saveConfirmedDispatch(
            "gpt-5.6-luna",
            "max",
            HansSettings.FAST_SERVICE_TIER,
        )
        assertEquals(HansSettings.FAST_SERVICE_TIER, fast.serviceTier)
        assertEquals(
            HansSettings.FAST_SERVICE_TIER,
            SharedPreferencesHansSettingsStore(context).read().serviceTier,
        )

        store.saveConfirmedDispatch(
            "gpt-5.6-luna",
            "max",
            HansSettings.DEFAULT_SERVICE_TIER,
        )
        assertEquals(
            HansSettings.DEFAULT_SERVICE_TIER,
            SharedPreferencesHansSettingsStore(context).read().serviceTier,
        )
    }

    @Test
    fun legacySpeechPreferenceMigratesToIndependentRippleLiveDefault() {
        val preferences = context.getSharedPreferences("hans_settings_v1", Context.MODE_PRIVATE)
        preferences.edit()
            .putInt("schema_version", 1)
            .putString("voice", "nova")
            .putFloat("speech_rate", 1.5f)
            .putString("read_aloud_mode", ReadAloudMode.FINAL_ONLY.wireValue)
            .commit()

        val restored = SharedPreferencesHansSettingsStore(context).read()

        assertEquals("ripple", restored.liveVoice)
        assertEquals("nova", restored.voice)
        assertEquals(1.5f, restored.speechRate)
        assertEquals(ReadAloudMode.FINAL_ONLY, restored.readAloudMode)
        assertFalse(preferences.contains("live_voice"))
    }

    @Test
    fun allLiveChoicesPersistWithoutChangingSpeechOrConfirmedDispatchOrInputControls() {
        val store = SharedPreferencesHansSettingsStore(context)
        store.saveConfirmedDispatch("gpt-5.6-terra", "high", HansSettings.FAST_SERVICE_TIER)
        store.saveVoice("nova", 1.5f, ReadAloudMode.FINAL_ONLY)
        val before = store.saveInputControls(ActionKeyTrigger.HOLD_TO_TALK, true)

        HansSettings.SUPPORTED_LIVE_VOICES.forEach { voice ->
            assertEquals(before.copy(liveVoice = voice), store.saveLiveVoice(voice))
            assertEquals(before.copy(liveVoice = voice), SharedPreferencesHansSettingsStore(context).read())
        }
    }

    @Test
    fun changingSpeechNeverChangesTheSavedLivePreference() {
        val store = SharedPreferencesHansSettingsStore(context)
        store.saveLiveVoice("willow")

        val saved = store.saveVoice("onyx", 0.75f, ReadAloudMode.FINAL_ONLY)

        assertEquals("willow", saved.liveVoice)
        assertEquals(saved, SharedPreferencesHansSettingsStore(context).read())
    }

    @Test
    fun malformedLivePreferenceDefaultsOnlyThatFieldWithoutResettingSpeech() {
        val store = SharedPreferencesHansSettingsStore(context)
        val before = store.saveVoice("nova", 1.5f, ReadAloudMode.FINAL_ONLY)
        val preferences = context.getSharedPreferences("hans_settings_v1", Context.MODE_PRIVATE)

        preferences.edit().putString("live_voice", "fable").commit()
        assertEquals(before.copy(liveVoice = "ripple"), store.read())
        preferences.edit().putInt("live_voice", 42).commit()
        assertEquals(before.copy(liveVoice = "ripple"), SharedPreferencesHansSettingsStore(context).read())
    }

    @Test
    fun restoredPreferencesSaveBothVoicesButCannotClaimAnUnconfirmedModelChange() {
        val store = SharedPreferencesHansSettingsStore(context)
        val confirmed = store.saveConfirmedDispatch("gpt-5.6-terra", "high", HansSettings.FAST_SERVICE_TIER)
        val backup = HansSettings(voice = "nova", liveVoice = "willow", speechRate = 1.5f)

        val restored = store.saveRestoredNonDispatchPreferences(backup)

        assertEquals(
            backup.copy(
                model = confirmed.model,
                reasoningEffort = confirmed.reasoningEffort,
                serviceTier = confirmed.serviceTier,
            ),
            restored,
        )
        assertEquals(restored, SharedPreferencesHansSettingsStore(context).read())
    }

    @Test
    fun invalidLiveWriteLeavesAllPreviouslySavedPreferencesUnchanged() {
        val store = SharedPreferencesHansSettingsStore(context)
        store.saveVoice("nova", 1.5f, ReadAloudMode.FINAL_ONLY)
        val before = store.saveLiveVoice("willow")

        assertThrows(IllegalArgumentException::class.java) { store.saveLiveVoice("fable") }

        assertEquals(before, SharedPreferencesHansSettingsStore(context).read())
    }
}
