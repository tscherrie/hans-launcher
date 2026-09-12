package ai.hans.standard.phone.display

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class DisplayMotionPreferencesStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "hans_display_motion_test_${UUID.randomUUID()}"
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @After
    fun removeOnlyOwnedFixturePreferences() {
        assertTrue(context.deleteSharedPreferences(name))
    }

    @Test
    fun newInstallAndExistingInstallWithoutTheNewPreferenceDefaultToAutomatic() {
        assertEquals(DisplayMotionMode.AUTOMATIC, DisplayMotionPreferencesStore(preferences).read())
        preferences.edit().putString("unrelated", "preserve me").commit()
        assertEquals(DisplayMotionMode.AUTOMATIC, DisplayMotionPreferencesStore(preferences).read())
        assertEquals("preserve me", preferences.getString("unrelated", null))
    }

    @Test
    fun everyConfirmedOverrideSurvivesStoreRecreationWithoutChangingOtherPreferences() {
        preferences.edit().putString("unrelated", "preserve me").commit()
        DisplayMotionMode.entries.forEach { mode ->
            assertEquals(mode, DisplayMotionPreferencesStore(preferences).save(mode))
            assertEquals(mode, DisplayMotionPreferencesStore(preferences).read())
            assertEquals("preserve me", preferences.getString("unrelated", null))
        }
    }

    @Test
    fun unknownStoredValueFallsBackToAutomaticWithoutRewritingIt() {
        preferences.edit().putString("display_motion_mode", "unknown-future-mode").commit()
        assertEquals(DisplayMotionMode.AUTOMATIC, DisplayMotionPreferencesStore(preferences).read())
        assertEquals("unknown-future-mode", preferences.getString("display_motion_mode", null))
    }

    @Test
    fun failedCommitDoesNotReturnAnUnconfirmedOverride() {
        DisplayMotionPreferencesStore(preferences).save(DisplayMotionMode.E_INK)
        var failedCommitCalls = 0
        val refusingPreferences = object : SharedPreferences by preferences {
            override fun edit(): SharedPreferences.Editor {
                val editor = preferences.edit()
                return object : SharedPreferences.Editor by editor {
                    override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }

                    override fun commit(): Boolean {
                        failedCommitCalls += 1
                        return false
                    }
                }
            }
        }
        assertTrue(
            runCatching {
                DisplayMotionPreferencesStore(refusingPreferences).save(DisplayMotionMode.STANDARD)
            }.exceptionOrNull() is IllegalStateException,
        )
        assertEquals("The refusing commit must be reached exactly once", 1, failedCommitCalls)
        assertEquals(DisplayMotionMode.E_INK, DisplayMotionPreferencesStore(preferences).read())
    }
}
