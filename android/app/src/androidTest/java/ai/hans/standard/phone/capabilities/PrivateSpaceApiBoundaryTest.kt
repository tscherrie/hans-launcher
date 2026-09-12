package ai.hans.standard.phone.capabilities

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateSpaceApiBoundaryTest {
    @Test
    fun androidTwelvePathLoadsLauncherWithoutResolvingApi36CallbackSignature() {
        assertNull(
            LauncherUserConfigCallbackFactory.createIfSupported(apiLevel = 31) {
                error("API 31 must not create the API 36 callback")
            },
        )

        val loader = javaClass.classLoader
        assertNotNull(Class.forName("ai.hans.standard.LauncherActivity", false, loader))
        assertNotNull(
            Class.forName(
                "ai.hans.standard.phone.capabilities.FrameworkAndroidLauncherProfilePlatform",
                false,
                loader,
            ),
        )
    }

    @Test
    fun api36ConfigCallbackDispatchesAuthoritativeRefreshSignal() {
        if (Build.VERSION.SDK_INT < 36) return
        var refreshes = 0
        val callback = LauncherUserConfigCallbackFactory.createIfSupported(apiLevel = 36) {
            refreshes += 1
        }

        assertTrue(callback is LauncherUserConfigRefreshSignal)
        (callback as LauncherUserConfigRefreshSignal).dispatchRefreshSignal()
        assertEquals(1, refreshes)
    }
}
