package ai.hans.standard

import ai.hans.standard.voice.android.DictationRuntimeObserver
import ai.hans.standard.voice.android.DictationUiPhase
import ai.hans.standard.voice.android.HansDictationRuntime
import ai.hans.standard.voice.android.HansDictationService
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit live-device gate for the real foreground microphone lifecycle. */
@RunWith(AndroidJUnit4::class)
class HansDictationServiceDeviceTest {
    @Test
    fun foregroundServiceCapturesWhileLauncherStaysAliveAndStopsCleanly() {
        if (InstrumentationRegistry.getArguments().getString(ARGUMENT_NAME) != "true") {
            assertTrue(true)
            return
        }
        val app = ApplicationProvider.getApplicationContext<HansApplication>()
        assertEquals(
            PackageManager.PERMISSION_GRANTED,
            app.checkSelfPermission(Manifest.permission.RECORD_AUDIO),
        )
        val listening = CountDownLatch(1)
        val finalizing = CountDownLatch(1)
        val observer = DictationRuntimeObserver { snapshot ->
            when (snapshot.phase) {
                DictationUiPhase.LISTENING -> listening.countDown()
                DictationUiPhase.FINALIZING -> finalizing.countDown()
                else -> Unit
            }
        }
        HansDictationRuntime.addObserver(observer)
        try {
            ActivityScenario.launch(LauncherActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> HansDictationService.start(activity) }
                assertTrue(
                    "Real microphone service did not reach LISTENING",
                    listening.await(20, TimeUnit.SECONDS),
                )
                scenario.onActivity { activity -> HansDictationService.stop(activity) }
                assertTrue(
                    "Real microphone service did not reach FINALIZING",
                    finalizing.await(10, TimeUnit.SECONDS),
                )
            }
        } finally {
            HansDictationRuntime.removeObserver(observer)
            app.stopService(Intent(app, HansDictationService::class.java))
            HansDictationRuntime.resetIdle()
        }
    }

    companion object {
        const val ARGUMENT_NAME = "runLiveDictationSmoke"
    }
}
