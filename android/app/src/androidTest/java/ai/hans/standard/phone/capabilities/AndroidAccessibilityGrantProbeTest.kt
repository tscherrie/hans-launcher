package ai.hans.standard.phone.capabilities

import android.content.ComponentName
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.hans.standard.phone.accessibility.android.HansAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only public-API coverage, executed on every supported API by the Android matrix. */
@RunWith(AndroidJUnit4::class)
class AndroidAccessibilityGrantProbeTest {
    @Test
    fun durableGrantSettingIsReadableAndMatchesOnlyHansComponent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val read = runCatching {
            queryAccessibilityGrantSetting {
                context.contentResolver.query(
                    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                    arrayOf(Settings.NameValueTable.VALUE),
                    null,
                    null,
                    null,
                )
            }
        }
        assertTrue("The public Accessibility grant setting must be readable", read.isSuccess)
        assertNotNull("The public grant query must return a cursor", read.getOrNull())
        val expectedComponent = ComponentName(context, HansAccessibilityService::class.java)
        val expectedGranted = read.getOrNull()?.enabledServices
            ?.split(':')
            ?.mapNotNull(ComponentName::unflattenFromString)
            ?.any { it == expectedComponent } == true
        val environment = AndroidCapabilityEnvironment(context)

        assertEquals(
            if (expectedGranted) AccessibilityGrantState.GRANTED else AccessibilityGrantState.NOT_GRANTED,
            environment.accessibilityGrantState(),
        )
        assertEquals(
            expectedGranted,
            environment.hasSpecialAccess(AndroidSpecialAccess.ACCESSIBILITY_SERVICE),
        )
    }
}
