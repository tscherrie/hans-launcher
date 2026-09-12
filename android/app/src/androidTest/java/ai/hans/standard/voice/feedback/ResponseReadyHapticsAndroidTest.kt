package ai.hans.standard.voice.feedback

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Handler
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.hans.standard.voice.realtime.LiveVoicePhase
import ai.hans.standard.voice.realtime.LiveVoiceResponseReady
import ai.hans.standard.voice.realtime.LiveVoiceSnapshot
import java.util.concurrent.Executor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** API 31–36 compatibility tests: no physical vibration or Android setting/grant mutation. */
@RunWith(AndroidJUnit4::class)
class ResponseReadyHapticsAndroidTest {
    @Test
    fun applicationContextCanReadPublicHardwarePolicyAndManifestHasOnlyNormalVibratePermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        @Suppress("DEPRECATION")
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertTrue(Manifest.permission.VIBRATE in permissions)
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.VIBRATE))
        val haptics = AndroidResponseReadyHaptics(context)
        assertNotNull(haptics.probe()) // probe only; this test never calls the physical vibrator
        val attributes = AndroidResponseReadyHaptics.audioAttributes()
        assertEquals(AudioAttributes.USAGE_NOTIFICATION, attributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SONIFICATION, attributes.contentType)
        assertEquals(0, attributes.flags)
    }

    @Test
    fun applicationOwnedFeedbackNeedsNoActivityAndQueuedStopPreventsThePulse() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val handler = Handler(Looper.getMainLooper())
        var pulses = 0
        val controller = ResponseReadyFeedbackController(
            haptics = ResponseReadyHaptics { pulses += 1 },
            executor = Executor { handler.post(it) },
        )
        try {
            instrumentation.runOnMainSync {
                controller.onLiveSnapshot(LiveVoiceSnapshot(LiveVoicePhase.HANS_SPEAKING, generation = 1))
                controller.acceptLive(LiveVoiceResponseReady("session", 1, "cancelled"))
                // The effect is queued on the real MainLooper. No Activity is created or visible.
                controller.onLiveSnapshot(LiveVoiceSnapshot(LiveVoicePhase.STOPPED, generation = 1))
            }
            instrumentation.waitForIdleSync()
            assertEquals(0, pulses)
            instrumentation.runOnMainSync {
                controller.onLiveSnapshot(LiveVoiceSnapshot(LiveVoicePhase.HANS_SPEAKING, generation = 2))
                val ready = LiveVoiceResponseReady("session", 2, "new")
                controller.acceptLive(ready)
                controller.acceptLive(ready)
            }
            instrumentation.waitForIdleSync()
            assertEquals(1, pulses)
        } finally {
            controller.close()
        }
    }
}
