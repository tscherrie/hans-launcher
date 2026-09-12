package ai.hans.standard.phone.accessibility.android

import android.content.Context
import android.view.Display
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidDefaultPhoneDisplaySourceTest {
    @Test
    fun nonUiApplicationContextResolvesDefaultPhoneDisplayThroughWindowContext() {
        val nonUiContext = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(!nonUiContext.isUiContext)

        val result = AndroidAccessibilityDisplayResolver(
            AndroidDisplayManagerSource(nonUiContext),
        ).resolve(Display.DEFAULT_DISPLAY)

        assertTrue(result is AndroidAccessibilityDisplayResolution.Available)
        val display = (result as AndroidAccessibilityDisplayResolution.Available).display
        assertEquals(Display.DEFAULT_DISPLAY, display.displayId)
        assertTrue(display.bounds.width > 0)
        assertTrue(display.bounds.height > 0)
    }
}
