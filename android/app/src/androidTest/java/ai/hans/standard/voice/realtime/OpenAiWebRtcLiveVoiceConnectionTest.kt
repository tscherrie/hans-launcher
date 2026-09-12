package ai.hans.standard.voice.realtime

import android.Manifest
import ai.hans.standard.voice.audio.AndroidSpeechAudioRouteController
import ai.hans.standard.voice.tts.android.AndroidKeystoreSpeechCredentialStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenAiWebRtcLiveVoiceConnectionTest {
    @Test
    fun audioProcessingCapabilitiesCanBeProbedOnDevice() {
        val capabilities = AndroidWebRtcRealtimeTransport.probeAudioCapabilities()
        // Hardware effects vary by handset. Reaching this point verifies that
        // the pinned native AAR loaded; software communication processing is
        // still enabled by the transport when either value is false.
        assertNotNull(capabilities)
    }

    @Test
    fun optInLiveConnectionUsesKeystoreWithoutExposingCredential() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Set instrumentation argument runLiveVoiceSmoke=true to make a real API call.",
            InstrumentationRegistry.getArguments().getString("runLiveVoiceSmoke") == "true",
        )
        val context = instrumentation.targetContext
        assumeTrue(
            "Grant microphone permission explicitly before opting into live smoke.",
            androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
        val store = AndroidKeystoreSpeechCredentialStore(context)
        assumeTrue("No test credential is present in Android Keystore.", store.hasCredential())
        val baseInstructions = context.assets.open("hans/live-voice-instructions.md")
            .bufferedReader()
            .use { it.readText() }
        val instructions = LiveVoiceContextBuilder().build(
            baseInstructions = baseInstructions,
            snapshot = null,
            capabilitySummary = "No optional phone capabilities are enabled in this smoke test.",
        )
        val ready = CountDownLatch(1)
        var observedFailure: LiveVoiceFailure? = null
        val session = LiveApiVoiceSession(
            transportFactory = LiveVoiceTransportFactory {
                AndroidWebRtcRealtimeTransport(
                    context,
                    sessionProvider = OpenAiLiveSessionProvider(LiveVoiceStandardKeySource(store::loadBearerToken)),
                    audioRoutes = AndroidSpeechAudioRouteController(context),
                )
            },
            taskExecutor = object : LiveVoiceTaskExecutor {
                override fun execute(
                    request: LiveVoiceTaskRequest,
                    listener: LiveVoiceTaskExecutor.Listener,
                ): LiveVoiceTaskHandle {
                    listener.onFailure(LiveVoiceTaskFailure("smoke_tool_disabled", false))
                    return LiveVoiceTaskHandle.NONE
                }
            },
            instructionsProvider = { instructions },
            observer = object : LiveVoiceObserver {
                override fun onSnapshot(snapshot: LiveVoiceSnapshot) {
                    if (snapshot.phase == LiveVoicePhase.LISTENING) ready.countDown()
                }

                override fun onFailure(failure: LiveVoiceFailure) {
                    observedFailure = failure
                }
            },
        )
        try {
            session.start()
            val connected = ready.await(60, TimeUnit.SECONDS)
            assertTrue(
                "Live smoke did not become ready; code=${observedFailure?.code}",
                connected,
            )
            assertNull(observedFailure)
        } finally {
            session.close()
        }
    }
}
