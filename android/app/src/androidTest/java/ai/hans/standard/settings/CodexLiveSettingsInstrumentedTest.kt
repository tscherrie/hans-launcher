package ai.hans.standard.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** Disposable-emulator only: exercises actual Android persistence without voice/network. */
class CodexLiveSettingsInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val preferences get() = context.getSharedPreferences("hans_settings_v1", Context.MODE_PRIVATE)
    private val before = preferences.all

    @After
    fun restoreTestPreferences() {
        val editor = preferences.edit().clear()
        before.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        check(editor.commit())
    }

    @Test
    fun upgradeAndCorruptCodexPreferencePreserveExistingApiVoicesAndModel() {
        check(preferences.edit().clear().commit())
        val store = SharedPreferencesHansSettingsStore(context)
        store.saveConfirmedDispatch("gpt-6-astra", "ultra", HansSettings.FAST_SERVICE_TIER)
        store.saveVoice("nova", 1.5f, ReadAloudMode.FINAL_ONLY)
        val saved = store.saveLiveVoice("willow")
        assertEquals("cove", saved.codexLiveVoice)
        assertFalse(preferences.contains("codex_live_voice"))
        check(preferences.edit().putString("codex_live_voice", "ripple").commit())
        assertEquals(saved, store.read())
        check(preferences.edit().putInt("codex_live_voice", 7).commit())
        assertEquals(saved, SharedPreferencesHansSettingsStore(context).read())
    }

    @Test
    fun everyNativeVoiceRoundTripsWithoutChangingApiSettings() {
        val store = SharedPreferencesHansSettingsStore(context)
        val original = store.read()
        HansSettings.SUPPORTED_CODEX_LIVE_VOICES.forEach { voice ->
            val expected = original.copy(codexLiveVoice = voice)
            assertEquals(expected, store.saveCodexLiveVoice(voice))
            assertEquals(expected, SharedPreferencesHansSettingsStore(context).read())
        }
        val saved = store.read()
        assertThrows(IllegalArgumentException::class.java) { store.saveCodexLiveVoice("willow") }
        assertEquals(saved, store.read())
    }

    @Test
    fun backupRestoreIncludesCodexVoiceWithoutChangingConfirmedModel() {
        val store = SharedPreferencesHansSettingsStore(context)
        val original = store.read()
        val restored = store.saveRestoredNonDispatchPreferences(
            original.copy(model = "gpt-5.6-luna", reasoningEffort = "low", codexLiveVoice = "ember"),
        )
        assertEquals(original.copy(codexLiveVoice = "ember"), restored)
        assertEquals(restored, SharedPreferencesHansSettingsStore(context).read())
    }
}
