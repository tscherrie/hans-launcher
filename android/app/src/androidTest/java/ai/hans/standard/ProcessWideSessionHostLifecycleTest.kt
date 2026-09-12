package ai.hans.standard

import ai.hans.standard.integration.AndroidCodexSessionHost
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards only the architectural boundary between the launcher Activity and
 * the application-owned session host. Background-process survival while an
 * external app is foregrounded is covered separately by the active-work
 * service device gate.
 */
@RunWith(AndroidJUnit4::class)
class ProcessWideSessionHostLifecycleTest {
    @Test
    fun launcherRecreationKeepsTheApplicationOwnedSessionHost() {
        val application = ApplicationProvider.getApplicationContext<HansApplication>()
        val processHost: AndroidCodexSessionHost = application.sessionHost

        ActivityScenario.launch(LauncherActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertSame(processHost, (activity.application as HansApplication).sessionHost)
            }
            scenario.recreate()
            scenario.onActivity { recreated ->
                assertSame(processHost, (recreated.application as HansApplication).sessionHost)
            }
        }

        assertSame(processHost, application.sessionHost)
    }
}
