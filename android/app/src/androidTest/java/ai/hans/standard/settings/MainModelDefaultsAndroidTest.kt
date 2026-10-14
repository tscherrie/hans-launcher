package ai.hans.standard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Disposable-emulator preference proofs. Never run this class against the owner's phone. */
class MainModelDefaultsAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences get() = context.getSharedPreferences("hans_settings_v1", Context.MODE_PRIVATE)

    @Before @After fun clearOnlyDisposableSettings() {
        assertTrue(preferences.edit().clear().commit())
    }

    @Test fun freshSettingsUseSol61HighFastWithoutPretendingRuntimeConfirmation() {
        val settings = SharedPreferencesHansSettingsStore(context).read()
        assertEquals("gpt-6.1-sol", settings.model)
        assertEquals("high", settings.reasoningEffort)
        assertEquals("priority", settings.serviceTier)
        for (key in listOf("model", "reasoning_effort", "service_tier")) {
            assertFalse(preferences.contains(key))
        }
    }

    @Test fun oldSavedDispatchWithoutTierRetainsStandardAndDoesNotWriteMigration() {
        for ((key, value) in listOf("model" to "gpt-6-astra", "reasoning_effort" to "medium")) {
            assertTrue(preferences.edit().clear().putInt("schema_version", 1)
                .putString(key, value).putString("voice", "nova").commit())
            val settings = SharedPreferencesHansSettingsStore(context).read()
            assertEquals("default", settings.serviceTier)
            assertEquals("nova", settings.voice)
            assertFalse(preferences.contains("service_tier"))
            assertEquals(value, preferences.getString(key, null))
        }
    }

    @Test fun explicitSolStandardAndAstraUltraRemainSavedAcrossFreshDefaultChange() {
        val store = SharedPreferencesHansSettingsStore(context)
        for ((model, effort) in listOf("gpt-6.1-sol" to "high", "gpt-6-astra" to "ultra")) {
            store.saveConfirmedDispatch(model, effort, HansSettings.STANDARD_SERVICE_TIER)
            val settings = SharedPreferencesHansSettingsStore(context).read()
            assertEquals(model, settings.model)
            assertEquals(effort, settings.reasoningEffort)
            assertEquals("default", settings.serviceTier)
        }
    }
}
