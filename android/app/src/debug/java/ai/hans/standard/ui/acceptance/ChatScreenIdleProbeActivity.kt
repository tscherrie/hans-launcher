package ai.hans.standard.ui.acceptance

import ai.hans.standard.BuildConfig
import ai.hans.standard.phone.display.DisplayMotionMode
import ai.hans.standard.ui.ChatMessageAuthor
import ai.hans.standard.ui.ChatMessageUiModel
import ai.hans.standard.ui.ChatScreen
import ai.hans.standard.ui.ChatUiCallbacks
import ai.hans.standard.ui.ChatUiState
import ai.hans.standard.ui.HansTheme
import ai.hans.standard.ui.LiveVoiceUiStatus
import ai.hans.standard.ui.RuntimeUiStatus
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only renderer probe. It renders the actual ChatScreen and theme, but owns no account,
 * runtime, microphone, device action or real message dispatch. Only explicit test actions replace
 * immutable UI state. In particular, there is no synthetic Compose clock, timer, frame request,
 * recomposition loop or animation-scale override here.
 *
 * The passive public frame listener receives frames the real Android window already produced;
 * attaching the listener does not request any frames. Its HandlerThread sleeps between callbacks.
 */
@OptIn(ExperimentalComposeUiApi::class)
class ChatScreenIdleProbeActivity : ComponentActivity() {
    private var chat by mutableStateOf(ChatUiState(runtimeStatus = RuntimeUiStatus.ONLINE))
    private val sentMessages = mutableListOf<String>()
    private val frameCallbacks = AtomicLong()
    private val droppedCallbacks = AtomicLong()
    private val invalidFrameMetrics = AtomicLong()
    private val lastIntendedVsync = AtomicLong()
    private val metricsThread = HandlerThread("hans-idle-probe-frame-metrics")
    private lateinit var frameListener: Window.OnFrameMetricsAvailableListener

    val instanceId: String = UUID.randomUUID().toString()
    var resumed: Boolean = false
        private set
    var resumeCount: Int = 0
        private set
    var pauseCount: Int = 0
        private set
    var focusLossCount: Int = 0
        private set
    private var focusedAtLeastOnce: Boolean = false

    val runId: String
        get() = requireNotNull(intent.getStringExtra(EXTRA_RUN_ID))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(BuildConfig.DEBUG) { "The renderer probe is debug-only" }
        check(savedInstanceState == null) { "A recreated renderer probe is not valid evidence" }
        check(UUID.fromString(runId).toString() == runId) { "A canonical owned run UUID is required" }

        metricsThread.start()
        frameListener = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            frameCallbacks.incrementAndGet()
            droppedCallbacks.addAndGet(dropped.toLong())
            val intendedVsync = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            if (intendedVsync <= 0 || metrics.getMetric(FrameMetrics.TOTAL_DURATION) < 0 || dropped < 0) {
                invalidFrameMetrics.incrementAndGet()
            }
            lastIntendedVsync.set(intendedVsync)
        }
        window.addOnFrameMetricsAvailableListener(frameListener, Handler(metricsThread.looper))
        setContent {
            HansTheme {
                Surface {
                    ChatScreen(
                        // This acceptance test measures the E-Ink contract on every API level,
                        // including normal-display emulators. System animation state stays real.
                        displayMotionMode = DisplayMotionMode.E_INK,
                        state = chat,
                        callbacks = ChatUiCallbacks(
                            onComposerChanged = { text ->
                                chat = chat.copy(composer = chat.composer.copy(text = text))
                            },
                            onSend = { text ->
                                check(sentMessages.size < 4) { "Unexpected duplicate probe sends" }
                                sentMessages += text
                                chat = chat.copy(
                                    composer = chat.composer.copy(text = ""),
                                    messages = chat.messages + ChatMessageUiModel(
                                        id = "probe-send-${sentMessages.size}",
                                        author = ChatMessageAuthor.USER,
                                        text = text,
                                    ),
                                    timelineRevision = chat.timelineRevision + 1,
                                )
                            },
                            onChooseMedia = { error("Renderer probe must not launch the camera") },
                            onRemoveAttachment = { error("Renderer probe has no attachments") },
                            onOpenApps = { error("Renderer probe must not open apps") },
                            onOpenPlugins = { error("Renderer probe must not open plugins") },
                            onOpenSettings = { error("Renderer probe must not open settings") },
                            onToggleLiveVoice = { error("Use the UI-only probe state, never a Live session") },
                        ),
                        // Public accessibility resource IDs let instrumentation inspect real text
                        // and focus without importing Compose's synthetic test-clock machinery.
                        modifier = Modifier.semantics { testTagsAsResourceId = true },
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        resumeCount += 1
    }

    override fun onPause() {
        resumed = false
        pauseCount += 1
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) focusedAtLeastOnce = true
        if (!hasFocus && focusedAtLeastOnce) focusLossCount += 1
    }

    /** Called on the main thread exactly once per explicit instrumentation phase transition. */
    fun showProbeState(working: Boolean = false, live: Boolean = false) {
        chat = chat.copy(
            isWorking = working,
            liveVoiceStatus = if (live) LiveVoiceUiStatus.LISTENING else null,
        )
    }

    /** A one-off FIFO barrier, never an idle polling loop or a request to render a frame. */
    fun drainFrameMetrics() {
        val drained = CountDownLatch(1)
        check(Handler(metricsThread.looper).post { drained.countDown() }) { "Frame collector stopped" }
        check(drained.await(2, TimeUnit.SECONDS)) { "Frame collector did not drain" }
    }

    /** Copies non-observable evidence; reading it cannot invalidate the Compose UI. */
    fun snapshot(): ProbeSnapshot = ProbeSnapshot(
        instanceId = instanceId,
        draft = chat.composer.text,
        sent = sentMessages.toList(),
        working = chat.isWorking,
        live = chat.liveVoiceStatus?.isActive == true,
        resumed = resumed,
        resumeCount = resumeCount,
        pauseCount = pauseCount,
        focusLossCount = focusLossCount,
        hasWindowFocus = hasWindowFocus(),
        hardwareAccelerated = window.decorView.isHardwareAccelerated,
        frameCallbacks = frameCallbacks.get(),
        droppedCallbacks = droppedCallbacks.get(),
        invalidFrameMetrics = invalidFrameMetrics.get(),
        lastIntendedVsync = lastIntendedVsync.get(),
    )

    override fun onDestroy() {
        if (::frameListener.isInitialized) window.removeOnFrameMetricsAvailableListener(frameListener)
        metricsThread.quitSafely()
        super.onDestroy()
    }

    data class ProbeSnapshot(
        val instanceId: String,
        val draft: String,
        val sent: List<String>,
        val working: Boolean,
        val live: Boolean,
        val resumed: Boolean,
        val resumeCount: Int,
        val pauseCount: Int,
        val focusLossCount: Int,
        val hasWindowFocus: Boolean,
        val hardwareAccelerated: Boolean,
        val frameCallbacks: Long,
        val droppedCallbacks: Long,
        val invalidFrameMetrics: Long,
        val lastIntendedVsync: Long,
    )

    companion object {
        const val EXTRA_RUN_ID = "hansIdleRunId"
    }
}
