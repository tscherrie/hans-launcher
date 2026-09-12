package ai.hans.standard.ui

import ai.hans.standard.ui.acceptance.ChatScreenIdleProbeActivity
import android.accessibilityservice.AccessibilityServiceInfo
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.HardwareRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Diagnostics and cleanup must never replace the assertion that caused failure. */
internal fun retainIdleFailure(primary: Throwable?, action: () -> Unit) {
    try {
        action()
    } catch (secondary: Throwable) {
        if (primary == null) throw secondary
        if (secondary !== primary) primary.addSuppressed(secondary)
    }
}

/**
 * Opt-in, accountless renderer acceptance, not a whole-launcher, E-Ink or battery benchmark.
 * Uses the real Android event loop and physical-key dispatch path. No createComposeRule,
 * MainTestClock, Choreographer loop, gfxinfo reset, forced redraw or animation-scale write.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ChatScreenIdleRendererAcceptanceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private lateinit var runId: String
    private lateinit var directory: File
    private lateinit var receipt: JSONObject
    private lateinit var scenario: ActivityScenario<ChatScreenIdleProbeActivity>
    private val stages = JSONArray()
    private val keyEvents = JSONArray()
    private var previousGfx: IdleRendererGfxInfo? = null
    private var instanceId: String? = null
    private var lastRawProbe: JSONObject? = null
    private var lastRawAccessibility: JSONObject? = null
    private var currentStage = "initialization"
    private val drawingOutput = IdleDrawingOutput(
        supported = android.os.Build.VERSION.SDK_INT >= 33,
        read = {
            if (android.os.Build.VERSION.SDK_INT >= 33) HardwareRenderer.isDrawingEnabled()
            else error("Public drawing output getter is unavailable")
        },
        write = { enabled ->
            if (android.os.Build.VERSION.SDK_INT >= 33) HardwareRenderer.setDrawingEnabled(enabled)
            else error("Public drawing output setter is unavailable")
        },
    )

    @Before
    fun requireExplicitOwnedEmulator() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Real-clock renderer acceptance requires an explicitly owned emulator",
            arguments.getString("hansIdleRendererAcceptance") == "true",
        )
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "The renderer acceptance must not run on the physical phone"
        }
        runId = requireNotNull(arguments.getString("hansIdleRunId")) { "Missing owned run UUID" }
        check(UUID.fromString(runId).toString() == runId) { "Noncanonical owned run UUID" }
        directory = File(context.filesDir, "idle-renderer-acceptance/$runId")
        check(!directory.exists() && directory.mkdirs()) { "Renderer evidence directory already exists" }
    }

    @Test(timeout = 240_000)
    fun realFrameClockIdleAndPhysicalTyping() {
        val relativePath = "files/idle-renderer-acceptance/$runId/receipt.json"
        instrumentation.addResults(Bundle().apply {
            putString("hansIdleRendererReceiptPath", relativePath)
        })
        receipt = JSONObject()
            .put("schema", "hans.chat-idle-renderer.android.v5")
            .put("status", "running")
            .put("runId", runId)
            .put("scope", "isolated-real-clock-chat-renderer")
            .put("productionRuntimeAuthenticated", false)
            .put("microphoneStarted", false)
            .put("packageName", context.packageName)
            .put("api", android.os.Build.VERSION.SDK_INT)
            .put("pageSize", Os.sysconf(OsConstants._SC_PAGESIZE))
            .put("pid", Process.myPid())
            .put("activity", IdleRendererGfxInfo.ACTIVITY)
            .put("startedElapsedRealtimeMs", SystemClock.elapsedRealtime())
            .put("stages", stages)
            .put("keyEvents", keyEvents)
        val oldAccessibilityFlags = automation.serviceInfo.flags
        var primaryFailure: Throwable? = null
        try {
            drawingOutput.prepare()
            receipt.put("drawingOutput", drawingOutput.json())
            automation.serviceInfo = automation.serviceInfo.apply {
                flags = flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            }
            scenario = ActivityScenario.launch(
                Intent(context, ChatScreenIdleProbeActivity::class.java)
                    .putExtra(ChatScreenIdleProbeActivity.EXTRA_RUN_ID, runId),
            )
            // Only a fixed initial settling period; it does not advance a synthetic UI clock.
            SystemClock.sleep(3_000)
            // WindowManagerGlobal synchronizes ValueAnimator's process-wide scale when the
            // Activity opens its real window session. Do not query its static initial value
            // before that happens and mislabel it as effective runtime evidence.
            val beforeScales = animationScales("before")
            observe("idle", text = "", focused = false, sentCount = 0)

            injectKey(KeyEvent.KEYCODE_A)
            SystemClock.sleep(2_000)
            val afterA = readUi(text = "a", focused = true, sentCount = 0)
            observe("focused_after_a", text = "a", focused = true, sentCount = 0)

            injectKey(KeyEvent.KEYCODE_B)
            SystemClock.sleep(500)
            val afterB = readUi(text = "ab", focused = true, sentCount = 0)
            injectKey(KeyEvent.KEYCODE_ENTER)
            SystemClock.sleep(2_000)
            val afterSend = readUi(text = "", focused = false, sentCount = 1)
            assertEquals(listOf("ab"), probe().sent)
            receipt.put("typing", JSONObject()
                .put("afterA", afterA.getString("composerText"))
                .put("afterB", afterB.getString("composerText"))
                .put("sent", JSONArray(probe().sent))
                .put("composerAfterSend", afterSend.getString("composerText"))
                .put("focusedAfterA", afterA.getBoolean("composerFocused"))
                .put("focusedAfterSend", afterSend.getBoolean("composerFocused")))
            observe("idle_after_send", text = "", focused = false, sentCount = 1)

            scenario.onActivity { it.showProbeState(working = true) }
            SystemClock.sleep(2_000)
            observe("working", text = "", focused = false, sentCount = 1, working = true)

            // This is deliberately only UI state. There is no microphone or real Live session.
            scenario.onActivity { it.showProbeState(live = true) }
            SystemClock.sleep(2_000)
            observe("live_positive", text = "", focused = false, sentCount = 1, live = true)

            scenario.onActivity { it.showProbeState() }
            SystemClock.sleep(2_000)
            observe("idle_after_live", text = "", focused = false, sentCount = 1)
            assertEquals(listOf("ab"), probe().sent)
            val afterScales = animationScales("after")
            afterScales.requireUnchanged(beforeScales)
            drawingOutput.observationFinished()
            receipt.put("status", "passed")
        } catch (failure: Throwable) {
            primaryFailure = failure
            receipt.put("status", "failed")
                .put("failureType", failure.javaClass.name)
                .put("failureMessage", failure.message.orEmpty().take(1_024))
            retainIdleFailure(failure) { retainFailureDiagnostics() }
            throw failure
        } finally {
            var finishingFailure = primaryFailure
            fun finish(action: () -> Unit) {
                try {
                    retainIdleFailure(finishingFailure, action)
                } catch (error: Throwable) {
                    finishingFailure = error
                }
            }
            finish { if (::scenario.isInitialized) scenario.close() }
            finish { drawingOutput.restore() }
            finish { automation.serviceInfo = automation.serviceInfo.apply { flags = oldAccessibilityFlags } }
            finish {
                receipt.put("drawingOutput", drawingOutput.json())
                finishingFailure?.let { error ->
                    receipt.put("status", "failed")
                        .put("failureType", error.javaClass.name)
                        .put("failureMessage", error.message.orEmpty().take(1_024))
                }
                receipt.put("finishedElapsedRealtimeMs", SystemClock.elapsedRealtime())
                val bytes = receipt.toString(2).toByteArray(Charsets.UTF_8)
                check(bytes.size <= 131_072) { "Renderer receipt exceeded its fixed bound" }
                writeNewFile("receipt.json", bytes)
            }
            if (primaryFailure == null) finishingFailure?.let { throw it }
        }
    }

    private fun observe(
        id: String,
        text: String,
        focused: Boolean,
        sentCount: Int,
        working: Boolean = false,
        live: Boolean = false,
    ) {
        currentStage = id
        val beforeUi = readUi(text, focused, sentCount, working, live)
        val before = gfxSnapshot("$id-before.gfxinfo.txt", allowPendingReusedTail = live,
            allowSkippedReusedTail = live || id == "idle_after_live")
        val startingProbe = probe(drain = true)
        val cpuStart = Process.getElapsedCpuTime()
        val started = SystemClock.elapsedRealtime()
        val duration = if (live) 4_000L else 8_000L
        // Instrumentation sleeps off the UI thread. No polling or repeated state reads take
        // place in this bounded, real-time observation window.
        SystemClock.sleep(duration)
        val finished = SystemClock.elapsedRealtime()
        val cpuEnd = Process.getElapsedCpuTime()
        val endingProbe = probe(drain = true)
        val after = gfxSnapshot("$id-after.gfxinfo.txt", allowPendingReusedTail = live,
            allowSkippedReusedTail = live || id == "idle_after_live")
        val afterUi = readUi(text, focused, sentCount, working, live)
        val delta = after.getLong("totalFramesRendered") - before.getLong("totalFramesRendered")
        val stage = JSONObject()
            .put("id", id)
            .put("expectedQuiet", !live)
            .put("requestedDurationMs", duration)
            .put("startedElapsedRealtimeMs", started)
            .put("finishedElapsedRealtimeMs", finished)
            .put("cpuStartMs", cpuStart)
            .put("cpuEndMs", cpuEnd)
            .put("framesStart", before.getLong("totalFramesRendered"))
            .put("framesEnd", after.getLong("totalFramesRendered"))
            .put("frameDelta", delta)
            .put("metricsFramesStart", startingProbe.frameCallbacks)
            .put("metricsFramesEnd", endingProbe.frameCallbacks)
            .put("metricsDroppedStart", startingProbe.droppedCallbacks)
            .put("metricsDroppedEnd", endingProbe.droppedCallbacks)
            .put("before", before)
            .put("after", after)
            .put("uiBefore", beforeUi)
            .put("uiAfter", afterUi)
        stages.put(stage)
        check(finished - started in duration..(duration + 2_500)) { "Observation window was not bounded" }
        check(cpuStart >= 0 && cpuEnd >= cpuStart) { "Invalid process CPU observation" }
        check(startingProbe.droppedCallbacks == endingProbe.droppedCallbacks) { "Frame-metric callbacks were dropped" }
        check(startingProbe.invalidFrameMetrics == 0L && endingProbe.invalidFrameMetrics == 0L) {
            "Platform frame metrics are unsupported or invalid"
        }
        val metricsDelta = endingProbe.frameCallbacks - startingProbe.frameCallbacks
        if (live) {
            check(delta >= 30 && metricsDelta >= 30) { "Live positive control produced insufficient real frames" }
        } else {
            check(before.getString("profileDataSha256") == after.getString("profileDataSha256")) {
                "Quiet stage $id changed raw frame-profile evidence"
            }
            check(delta == 0L && metricsDelta == 0L) {
                "Quiet stage $id kept rendering: gfx=$delta, frameMetrics=$metricsDelta"
            }
        }
    }

    private fun probe(drain: Boolean = false): ChatScreenIdleProbeActivity.ProbeSnapshot {
        var snapshot: ChatScreenIdleProbeActivity.ProbeSnapshot? = null
        scenario.onActivity { activity ->
            if (drain) activity.drainFrameMetrics()
            snapshot = activity.snapshot()
        }
        return requireNotNull(snapshot).also { evidence ->
            // Keep the real unvalidated observation, including zeros. Only failures
            // publish this diagnostic; it is never accepted as measurement evidence.
            lastRawProbe = JSONObject()
                .put("stage", currentStage)
                .put("observedElapsedRealtimeMs", SystemClock.elapsedRealtime())
                .put("instanceId", evidence.instanceId)
                .put("draft", evidence.draft)
                .put("sent", JSONArray(evidence.sent))
                .put("working", evidence.working).put("live", evidence.live)
                .put("resumed", evidence.resumed).put("resumeCount", evidence.resumeCount)
                .put("pauseCount", evidence.pauseCount).put("focusLossCount", evidence.focusLossCount)
                .put("hasWindowFocus", evidence.hasWindowFocus)
                .put("hardwareAccelerated", evidence.hardwareAccelerated)
                .put("frameCallbacks", evidence.frameCallbacks)
                .put("droppedCallbacks", evidence.droppedCallbacks)
                .put("invalidFrameMetrics", evidence.invalidFrameMetrics)
                .put("lastIntendedVsync", evidence.lastIntendedVsync)
            drawingOutput.requireEnabled()
            if (instanceId == null) {
                instanceId = evidence.instanceId
                receipt.put("activityInstanceId", evidence.instanceId)
            }
            check(evidence.instanceId == instanceId && evidence.resumed && evidence.resumeCount == 1 &&
                evidence.pauseCount == 0 && evidence.focusLossCount == 0 && evidence.hasWindowFocus &&
                evidence.hardwareAccelerated) { "Renderer activity/window identity or lifecycle changed" }
            check(evidence.frameCallbacks > 0 && evidence.lastIntendedVsync > 0) {
                "No public real-window frame-metric evidence"
            }
        }
    }

    private fun retainFailureDiagnostics() {
        val diagnostics = JSONObject().put("scope", "failure-diagnostics-only")
            .put("acceptance", false).put("stage", currentStage)
            .put("lastRawProbe", lastRawProbe ?: JSONObject.NULL)
            .put("lastRawAccessibility", lastRawAccessibility ?: JSONObject.NULL)
        receipt.put("failureDiagnostics", diagnostics)
        val name = "idle-before.gfxinfo.txt"
        if (File(directory, name).exists()) {
            diagnostics.put("gfxRetention", "existing-file-preserved")
            return
        }
        try {
            check(::scenario.isInitialized) { "No renderer Activity exists" }
            var alive = false
            scenario.onActivity { alive = !it.isFinishing && !it.isDestroyed }
            check(alive) { "Renderer Activity is no longer alive" }
            // Existing fixed allowlist path, original bytes, no parse or frame reset.
            val bytes = readShellSnapshot("dumpsys gfxinfo ${Process.myPid()} framestats", name)
            diagnostics.put("gfxRetention", rawFileDescription(name, bytes))
        } catch (error: Throwable) {
            diagnostics.put("gfxRetentionError", JSONObject().put("type", error.javaClass.name)
                .put("message", error.message.orEmpty().take(1_024)))
        }
    }

    @Suppress("DEPRECATION")
    private fun readUi(
        text: String,
        focused: Boolean,
        sentCount: Int,
        working: Boolean = false,
        live: Boolean = false,
    ): JSONObject {
        val state = probe()
        val root = requireNotNull(automation.rootInActiveWindow) { "No active accessibility window" }
        var nodes = 0
        val composers = mutableListOf<Pair<String, Boolean>>()
        val headers = mutableListOf<IdleHeaderEvidence>()
        val rawNodes = JSONArray()
        val rawAccessibility = JSONObject().put("stage", currentStage).put("nodes", rawNodes)
            .put("nodeLimit", 64).put("truncated", false)
        lastRawAccessibility = rawAccessibility
        val service = automation.serviceInfo
        rawAccessibility.put("serviceInfo", JSONObject().put("eventTypes", service.eventTypes)
            .put("flags", service.flags).put("feedbackType", service.feedbackType))
        var workingVisible = false
        fun diagnosticNode(node: AccessibilityNodeInfo): JSONObject = JSONObject()
            .put("package", node.packageName?.toString()?.take(128) ?: JSONObject.NULL)
            .put("resourceId", node.viewIdResourceName?.take(128) ?: JSONObject.NULL)
            .put("description", node.contentDescription?.toString()?.take(256) ?: JSONObject.NULL)
            .put("text", node.text?.toString()?.take(128) ?: JSONObject.NULL)
            .put("visible", node.isVisibleToUser).put("enabled", node.isEnabled)
            .put("clickable", node.isClickable).put("focused", node.isFocused)
        fun visit(node: AccessibilityNodeInfo, enclosingHeader: IdleHeaderEvidence? = null, parent: Int = -1) {
            check(++nodes <= 512) { "Unbounded accessibility tree" }
            val nodeIndex = nodes - 1
            rawAccessibility.put("visitedNodes", nodes)
            val raw = JSONObject().put("index", nodeIndex).put("parent", parent)
                .put("beforeRefresh", diagnosticNode(node)).put("refreshSucceeded", JSONObject.NULL)
            retainIdleAccessibilityNode(rawAccessibility, raw)
            readFreshIdleNode(refresh = {
                try {
                    node.refresh().also { raw.put("refreshSucceeded", it) }
                } catch (failure: Throwable) {
                    raw.put("refreshFailureType", failure.javaClass.name)
                    throw failure
                }
            }) { raw.put("afterRefresh", diagnosticNode(node)) }
            if (parent == -1) {
                check(node.packageName?.toString() == context.packageName) { "Another app owns the active window" }
            }
            val header = if (node.viewIdResourceName == "hans_title") {
                IdleHeaderEvidence(node.isVisibleToUser, node.isEnabled, node.isClickable).also(headers::add)
            } else enclosingHeader
            if (node.isVisibleToUser) node.contentDescription?.toString()?.let { header?.descriptions?.add(it) }
            when (node.viewIdResourceName) {
                "composer" -> composers += node.text?.toString().orEmpty() to node.isFocused
                "working_indicator" -> workingVisible = node.isVisibleToUser
            }
            for (childIndex in 0 until node.childCount) {
                node.getChild(childIndex)?.let { child ->
                    try { visit(child, header, nodeIndex) } finally { child.recycle() }
                }
            }
        }
        try {
            visit(root)
        } finally {
            root.recycle()
        }
        check(composers.size == 1) { "Missing/ambiguous accessible composer" }
        val composer = composers.single()
        assertEquals(text, composer.first)
        assertEquals(focused, composer.second)
        assertEquals(text, state.draft)
        assertEquals(sentCount, state.sent.size)
        assertEquals(working, state.working)
        assertEquals(working, workingVisible)
        assertEquals(live, state.live)
        requireIdleHeader(headers, live)
        return JSONObject()
            .put("composerText", composer.first)
            .put("composerFocused", composer.second)
            .put("isWorking", state.working)
            .put("liveActive", state.live)
            .put("sentCount", state.sent.size)
            .put("drawingOutputEnabled", drawingOutput.requireEnabled() ?: JSONObject.NULL)
    }

    private fun injectKey(keyCode: Int) {
        val downTime = SystemClock.uptimeMillis()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val eventTime = SystemClock.uptimeMillis()
            val event = KeyEvent(
                downTime, eventTime, action, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD,
            )
            check(automation.injectInputEvent(event, true)) { "Android rejected the hardware-key event" }
            keyEvents.put(JSONObject()
                .put("action", action).put("keyCode", keyCode)
                .put("downTimeMs", downTime).put("eventTimeMs", eventTime)
                .put("source", InputDevice.SOURCE_KEYBOARD))
        }
    }

    private fun animationScales(phase: String): IdleRendererAnimationEvidence {
        check(phase == "before" || phase == "after")
        val stored = linkedMapOf(
            "animator" to Settings.Global.getString(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE),
            "transition" to Settings.Global.getString(context.contentResolver, Settings.Global.TRANSITION_ANIMATION_SCALE),
            "window" to Settings.Global.getString(context.contentResolver, Settings.Global.WINDOW_ANIMATION_SCALE),
        )
        var enabled: Boolean? = null
        var publicDurationScale: Float? = null
        scenario.onActivity {
            enabled = ValueAnimator.areAnimatorsEnabled()
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                publicDurationScale = ValueAnimator.getDurationScale()
            }
        }
        val file = "animation-$phase.window.txt"
        // WMS emits its animation-state line only in a full (-a) diagnostic dump.
        val bytes = readShellSnapshot("dumpsys window -a", file)
        val evidence = IdleRendererAnimationEvidence.resolve(
            api = android.os.Build.VERSION.SDK_INT,
            stored = stored,
            publicAnimatorDurationScale = publicDurationScale,
            animatorsEnabled = requireNotNull(enabled),
            windowManagerDump = bytes.decodeToString(throwOnInvalidSequence = true),
        )
        val suffix = if (phase == "before") "Before" else "After"
        receipt.put("animationScales$suffix", JSONObject().apply {
            evidence.scalesForReceipt().forEach { (key, value) -> put(key, value) }
        })
        receipt.put("animationEvidence$suffix", rawFileDescription(file, bytes)
            .put("stored", JSONObject().apply {
                evidence.stored.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
            })
            .put("animatorsEnabled", evidence.animatorsEnabled)
            .put("publicAnimatorDurationScale", evidence.publicScaleForReceipt() ?: JSONObject.NULL)
            .put("windowManagerDisabled", false)
            .put("windowManagerLine", evidence.windowManagerLine))
        return evidence
    }

    private fun gfxSnapshot(fileName: String, allowPendingReusedTail: Boolean = false,
                            allowSkippedReusedTail: Boolean = false): JSONObject {
        val bytes = readShellSnapshot("dumpsys gfxinfo ${Process.myPid()} framestats", fileName)
        val parsed = IdleRendererGfxInfo.parse(bytes.decodeToString(throwOnInvalidSequence = true), Process.myPid(), context.packageName, allowPendingReusedTail, allowSkippedReusedTail)
        previousGfx?.let(parsed::requireContinuation)
        previousGfx = parsed
        receipt.put("window", parsed.window)
        return rawFileDescription(fileName, bytes)
            .put("pid", parsed.pid)
            .put("window", parsed.window)
            .put("statsSinceNanos", parsed.statsSinceNanos)
            .put("totalFramesRendered", parsed.totalFramesRendered)
            .put("profileDataRows", parsed.profileDataRows)
            .put("completedProfileDataRows", parsed.completedProfileDataRows)
            .put("pendingReusedProfileDataRows", parsed.pendingReusedProfileDataRows)
            .put("skippedReusedProfileDataRows", parsed.skippedReusedProfileDataRows)
            .put("profileDataSha256", parsed.profileDataSha256)
            .put("lastFrameCompletedNanos", parsed.lastFrameCompletedNanos)
    }

    /** Bounded, read-only diagnostics; writes their original bytes only to the owned evidence dir. */
    private fun readShellSnapshot(command: String, fileName: String): ByteArray {
        check(command == "dumpsys window -a" || command == "dumpsys gfxinfo ${Process.myPid()} framestats") {
            "Unrecognized renderer diagnostic command"
        }
        val descriptor = automation.executeShellCommand(command)
        val reader = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "hans-idle-diagnostic-reader").apply { isDaemon = true }
        }
        val bytes = try {
            reader.submit<ByteArray> {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(8_192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        check(output.size() + count <= IdleRendererGfxInfo.MAX_BYTES) { "Renderer diagnostic exceeded its byte limit" }
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                }
            }.get(15, TimeUnit.SECONDS)
        } finally {
            descriptor.close()
            reader.shutdownNow()
        }
        // Preserve original bytes before parsing, including unsupported evidence on failure.
        writeNewFile(fileName, bytes)
        return bytes
    }

    private fun rawFileDescription(fileName: String, bytes: ByteArray): JSONObject = JSONObject()
            .put("file", fileName)
            .put("sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
            .put("bytes", bytes.size)

    private fun writeNewFile(name: String, bytes: ByteArray) {
        check(name.matches(Regex("[a-z0-9_-]+(?:\\.gfxinfo\\.txt|\\.json)")) ||
            name in setOf("animation-before.window.txt", "animation-after.window.txt")) { "Invalid evidence filename" }
        val file = File(directory, name)
        check(file.createNewFile()) { "Existing renderer evidence must not be overwritten" }
        FileOutputStream(file).use { output -> output.write(bytes); output.fd.sync() }
        check(file.isFile && file.length() == bytes.size.toLong()) { "Renderer evidence is not a complete regular file" }
    }
}
