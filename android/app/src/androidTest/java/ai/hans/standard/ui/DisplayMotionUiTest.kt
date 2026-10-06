package ai.hans.standard.ui

import ai.hans.standard.phone.display.DisplayMotionMode
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsAnimation
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalCursorBlinkEnabled
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName

/**
 * Real rendered pixels under a controlled Compose clock, not just policy/semantics assertions.
 * The injected event source changes only this fixture, never Android's global animation settings.
 */
@OptIn(ExperimentalTestApi::class)
class DisplayMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = TestName()
    @Volatile private var compositionView: View? = null

    @Test
    fun normalDisplayCaretActuallyBlinksAndSendStillReturnsToIdleFocus() {
        ImeLayoutBarrier().use { platformLayout ->
            val fixture = fixture(working = false)
            typeFromIdle()
            assertComposerBlink(true)
            assertChangingPixels("composer")

            compose.onNodeWithTag("composer").performKeyInput { pressKey(Key.Enter) }
            platformLayout.awaitHiddenAndLaidOut()
            compose.onNodeWithTag("chat_screen").assertIsFocused()
            compose.onNodeWithTag("composer").assertIsNotFocused()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("")))
            compose.runOnIdle { assertEquals(listOf("a"), fixture.sent) }
            assertStaticPixels("composer")
        }
    }

    @Test
    fun einkCaretAndWorkingDotsAreVisibleButPixelStaticWithoutPolling() {
        val fixture = fixture(mode = DisplayMotionMode.E_INK)
        typeFromIdle()
        assertComposerBlink(false)
        assertWorkingAnimation(false)
        val readsBefore = fixture.source.reads
        assertStaticPixels("composer")
        assertStaticPixels("working_indicator_dots")
        compose.runOnIdle {
            assertEquals("Advancing idle time must not poll Android", readsBefore, fixture.source.reads)
            assertTrue(fixture.sent.isEmpty())
        }
    }

    @Test
    fun normalWorkingDotsActuallyAnimateAndDisappearWhenWorkFinishes() {
        val fixture = fixture()
        assertWorkingAnimation(true)
        assertChangingPixels("working_indicator_dots")
        compose.runOnIdle { fixture.chat.value = fixture.chat.value.copy(isWorking = false) }
        settle()
        compose.onNodeWithTag("working_indicator").assertDoesNotExist()
        compose.onNodeWithTag("composer").assertIsNotFocused()
        assertStaticPixels("composer")
    }

    @Test
    fun systemAnimationEventsStopAndRestartActualHomeMotionAndLeaveTheCallSurfaceStatic() {
        ImeLayoutBarrier().use { platformLayout ->
            val fixture = fixture()
            typeFromIdle()
            assertChangingPixels("working_indicator_dots")
            assertChangingPixels("composer")

            compose.runOnIdle { fixture.source.setEnabled(false) }
            settle()
            assertComposerBlink(false)
            assertWorkingAnimation(false)
            assertStaticPixels("composer")
            assertStaticPixels("working_indicator_dots")

            compose.runOnIdle { fixture.source.setEnabled(true) }
            settle()
            assertComposerBlink(true)
            assertWorkingAnimation(true)
            assertChangingPixels("composer")
            assertChangingPixels("working_indicator_dots")

            // A Live call is now a separate phone surface. It deliberately replaces the
            // home composer and its working animation instead of rendering all three at once.
            showLiveCall(fixture)
            platformLayout.awaitHiddenAndLaidOut()
            assertStaticPixels("live_call_avatar")
            compose.runOnIdle { fixture.source.setEnabled(false) }
            settle()
            assertStaticPixels("live_call_avatar")
        }
    }

    @Test
    fun einkCallSurfaceReplacesIdleHomeAndUpdatesOnlyForCallStateEvents() {
        val fixture = fixture(mode = DisplayMotionMode.E_INK)
        assertWorkingAnimation(false)
        assertStaticPixels("working_indicator_dots")

        showLiveCall(fixture)
        val readsBefore = fixture.source.reads
        assertStaticPixels("live_call_avatar")
        compose.runOnIdle {
            assertEquals("Advancing idle time must not poll Android", readsBefore, fixture.source.reads)
            fixture.chat.value = fixture.chat.value.copy(liveVoiceStatus = LiveVoiceUiStatus.HANS_SPEAKING)
        }
        settle()
        compose.onNodeWithTag("live_call_status").assertTextEquals("Hans spricht")
        assertStaticPixels("live_call_avatar")
    }

    @Test
    fun changingTheDisplayOverrideImmediatelyChangesActualMotion() {
        val fixture = fixture(mode = DisplayMotionMode.E_INK)
        typeFromIdle()
        assertComposerBlink(false)
        assertComposerEditingState("a", TextRange(1))
        assertStaticPixels("composer")
        assertStaticPixels("working_indicator_dots")

        compose.runOnIdle { fixture.mode.value = DisplayMotionMode.STANDARD }
        settle()
        assertComposerBlink(true)
        assertComposerEditingState("a", TextRange(1))
        assertChangingPixels("composer")
        assertChangingPixels("working_indicator_dots")

        compose.runOnIdle { fixture.mode.value = DisplayMotionMode.E_INK }
        settle()
        assertComposerBlink(false)
        assertComposerEditingState("a", TextRange(1))
        assertStaticPixels("composer")
        assertStaticPixels("working_indicator_dots")
        compose.runOnIdle { assertTrue(fixture.sent.isEmpty()) }
    }

    @Test
    fun displayOverridePreservesFocusedTextAndSelection() {
        val fixture = fixture(working = false)
        typeFromIdle()
        compose.onNodeWithTag("composer").performKeyInput {
            pressKey(Key.B)
            pressKey(Key.C)
        }
        settle()
        val selection = TextRange(1, 2)
        compose.onNodeWithTag("composer").performTextInputSelection(selection)
        settle()
        assertComposerEditingState("abc", selection)
        assertComposerBlink(true)

        // Do not refocus, type or reselect after changing the mode: that could hide a reset.
        compose.runOnIdle { fixture.mode.value = DisplayMotionMode.E_INK }
        settle()
        assertComposerBlink(false)
        assertComposerEditingState("abc", selection)

        compose.runOnIdle { fixture.mode.value = DisplayMotionMode.STANDARD }
        settle()
        assertComposerBlink(true)
        assertComposerEditingState("abc", selection)
        compose.runOnIdle {
            assertEquals("abc", fixture.chat.value.composer.text)
            assertTrue(fixture.sent.isEmpty())
        }
    }

    @Test
    fun pauseStopsHomeMotionResumeRefreshesItAndDisposalUnsubscribes() {
        val fixture = fixture()
        assertWorkingAnimation(true)
        compose.runOnIdle { fixture.owner.registry.currentState = Lifecycle.State.STARTED }
        settle()
        assertWorkingAnimation(false)
        assertStaticPixels("working_indicator_dots")

        // A setting changed while hidden must not restart animation until resume.
        compose.runOnIdle {
            fixture.source.setEnabled(false)
            fixture.source.setEnabled(true)
        }
        settle()
        assertWorkingAnimation(false)
        compose.runOnIdle { fixture.owner.registry.currentState = Lifecycle.State.RESUMED }
        settle()
        assertWorkingAnimation(true)
        assertChangingPixels("working_indicator_dots")

        compose.runOnIdle {
            assertEquals(1, fixture.source.listeners.size)
            fixture.visible.value = false
        }
        settle()
        compose.runOnIdle {
            assertTrue(fixture.source.listeners.isEmpty())
            assertEquals(1, fixture.source.closedSubscriptions)
            val reads = fixture.source.reads
            fixture.source.setEnabled(false)
            assertEquals(reads, fixture.source.reads)
        }
    }

    @Test
    fun anAncestorCanStillSuppressTheNormalDisplayCaret() {
        fixture(working = false, inheritedCursorBlink = false)
        typeFromIdle()
        assertComposerBlink(false)
        assertStaticPixels("composer")
    }

    @Test
    fun openingTheMenuStopsCoveredHomeAnimationsUntilItCloses() {
        fixture()
        assertWorkingAnimation(true)
        compose.onNodeWithTag("open_chat_navigation").performClick()
        settle()
        assertWorkingAnimation(false)
        assertStaticPixels("working_indicator_dots")
        compose.onNodeWithTag("close_chat_navigation").performClick()
        settle()
        assertWorkingAnimation(true)
        assertChangingPixels("working_indicator_dots")
    }

    @Test
    fun displayOptionWaitsForOwnerCommitAndThenAppliesInTheInputGroup() {
        val fixture = fixture(mode = DisplayMotionMode.E_INK, sidebarSettings = true)
        compose.onNodeWithTag("open_chat_navigation").performClick()
        settle()
        // Scroll semantics await frames; only this sidebar interaction uses automatic time.
        val previousAutoAdvance = compose.mainClock.autoAdvance
        try {
            compose.mainClock.autoAdvance = true
            compose.onNodeWithTag("settings_group_input").performScrollTo().performClick()
            compose.onNodeWithTag("display_motion_standard").performScrollTo().performClick()
            compose.waitForIdle()
        } finally {
            compose.mainClock.autoAdvance = previousAutoAdvance
        }
        assertFalse("Pixel assertions require the manual clock", compose.mainClock.autoAdvance)
        settle()
        compose.onNodeWithTag("display_motion_standard").assertIsNotSelected()
        compose.onNodeWithTag("display_motion_e_ink").assertIsSelected()
        compose.runOnIdle { assertEquals(listOf(DisplayMotionMode.STANDARD), fixture.requestedModes) }

        // The Activity publishes this state only after the device-local store commit/readback.
        compose.runOnIdle { fixture.mode.value = DisplayMotionMode.STANDARD }
        settle()
        compose.onNodeWithTag("display_motion_standard").assertIsSelected()
        compose.onNodeWithTag("display_motion_effective").assertTextEquals(
            "Normale Darstellung aktiv: Cursor und Arbeitspunkte animieren im sichtbaren Chat.",
        )
        compose.onNodeWithTag("chat_navigation_panel").assertExists()
        compose.onNodeWithTag("close_chat_navigation").performClick()
        settle()
        assertWorkingAnimation(true)
        assertChangingPixels("working_indicator_dots")
    }

    @Test
    fun rejectedObserverRegistrationKeepsTheChatUsableAndStatic() {
        ImeLayoutBarrier().use { platformLayout ->
            val fixture = fixture(observerRegistrationFails = true)
            typeFromIdle()
            assertComposerBlink(false)
            assertWorkingAnimation(false)
            assertStaticPixels("composer")
            assertStaticPixels("working_indicator_dots")

            // The same fail-closed observer policy must not make the replacement call
            // surface unusable, and the call must not resurrect hidden home animations.
            showLiveCall(fixture)
            platformLayout.awaitHiddenAndLaidOut()
            assertStaticPixels("live_call_avatar")
        }
    }

    @Test
    fun publicAndroidSourceReadsTheEffectiveSettingWithoutWritingGlobalState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val before = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        compose.runOnUiThread {
            val source = AndroidSystemAnimationSource(context)
            val subscription = source.observe { }
            try {
                val scale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
                val powerSave = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
                val expected = scale.isFinite() && scale > 0f && !powerSave &&
                    (Build.VERSION.SDK_INT < 33 || ValueAnimator.areAnimatorsEnabled())
                assertEquals(expected, source.animationsEnabled())
            } finally {
                subscription.close()
                subscription.close() // Disposing twice must not unregister someone else's receiver.
            }
        }
        assertEquals(before, Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE))
    }

    private fun fixture(
        mode: DisplayMotionMode = DisplayMotionMode.STANDARD,
        working: Boolean = true,
        inheritedCursorBlink: Boolean = true,
        sidebarSettings: Boolean = false,
        observerRegistrationFails: Boolean = false,
    ): Fixture {
        lateinit var fixture: Fixture
        compose.runOnUiThread { fixture = Fixture(mode, working, observerRegistrationFails) }
        compose.mainClock.autoAdvance = false
        compose.setGermanContent {
            compositionView = LocalView.current
            CompositionLocalProvider(
                LocalSystemAnimationSource provides fixture.source,
                LocalLifecycleOwner provides fixture.owner,
                LocalCursorBlinkEnabled provides inheritedCursorBlink,
            ) {
                if (fixture.visible.value) {
                    MaterialTheme {
                        ChatScreen(
                            state = fixture.chat.value,
                            displayMotionMode = fixture.mode.value,
                            sidebarSettings = if (sidebarSettings) {
                                SettingsUiState(displayMotionMode = fixture.mode.value)
                            } else {
                                null
                            },
                            sidebarCallbacks = if (sidebarSettings) settingsCallbacks(fixture) else null,
                            callbacks = ChatUiCallbacks(
                                onComposerChanged = { text ->
                                    fixture.chat.value = fixture.chat.value.let {
                                        it.copy(composer = it.composer.copy(text = text))
                                    }
                                },
                                onSend = { text ->
                                    fixture.sent += text
                                    fixture.chat.value = fixture.chat.value.let {
                                        it.copy(composer = it.composer.copy(text = ""))
                                    }
                                },
                                onChooseMedia = {},
                                onRemoveAttachment = {},
                                onOpenApps = {},
                                onOpenPlugins = {},
                                onOpenSettings = {},
                                onToggleLiveVoice = {},
                            ),
                        )
                    }
                }
            }
        }
        settle()
        return fixture
    }

    private fun settingsCallbacks(fixture: Fixture) = SettingsUiCallbacks(
        onBack = {},
        onStartGettingToKnow = {},
        onModelSelected = {},
        onReasoningEffortSelected = {},
        onVoiceSelected = {},
        onSpeechRateSelected = {},
        onReadAloudModeSelected = {},
        onPreviewVoice = {},
        onStartActionKeySetup = {},
        onStartModelToggleKeySetup = {},
        onCancelActionKeySetup = {},
        onClearActionKey = {},
        onClearModelToggleKey = {},
        onCapabilityAccessRequested = {},
        onDisplayMotionModeSelected = fixture.requestedModes::add,
    )

    private fun typeFromIdle() {
        compose.onNodeWithTag("chat_screen").assertIsFocused()
            .performKeyInput { pressKey(Key.A) }
        settle()
        compose.onNodeWithTag("composer").assertIsFocused().assertTextEquals("a")
    }

    private fun showLiveCall(fixture: Fixture) {
        compose.runOnIdle {
            fixture.chat.value = fixture.chat.value.copy(liveVoiceStatus = LiveVoiceUiStatus.LISTENING)
        }
        settle()
        compose.onNodeWithTag("live_call_screen").assertExists()
        compose.onNodeWithTag("composer").assertDoesNotExist()
        compose.onNodeWithTag("working_indicator").assertDoesNotExist()
        compose.onNodeWithTag("live_recording_indicator", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun settle() {
        // Focus semantics change before Foundation asynchronously emits Unfocus to
        // Material's interaction collector. Drain that event and start its finite
        // decoration transition before advancing the controlled animation clock.
        // The platform keyboard/layout clock is independent; the post-send test
        // also waits for that boundary before collecting its unchanged pixel samples.
        compose.waitForIdle()
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(320)
        compose.waitForIdle()
    }

    /**
     * Compose's manual clock does not advance Android's IME animation. In addition,
     * captureToImage reads the crop before requesting its committed draw. Wait for
     * the final platform layout before comparing post-send pixels, not for a guessed
     * delay and not by discarding a first screenshot or relaxing pixel equality.
     *
     * Observe this fresh test Activity's content parent, never Compose's own View:
     * CONTINUE_ON_SUBTREE preserves Compose's insets animation callback and behavior.
     */
    private inner class ImeLayoutBarrier : AutoCloseable {
        private lateinit var host: ViewGroup
        private val activeAnimations = mutableSetOf<WindowInsetsAnimation>()
        private var prepared = 0
        private var ended = 0
        private var preDrawCalls = 0
        private var preDraw: ViewTreeObserver.OnPreDrawListener? = null

        init {
            compose.runOnUiThread {
                host = compose.activity.findViewById(android.R.id.content)
                host.setWindowInsetsAnimationCallback(object : WindowInsetsAnimation.Callback(
                    WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE,
                ) {
                    override fun onPrepare(animation: WindowInsetsAnimation) {
                        if (animation.typeMask and WindowInsets.Type.ime() != 0) {
                            prepared += 1
                            activeAnimations += animation
                        }
                    }

                    override fun onProgress(
                        insets: WindowInsets,
                        runningAnimations: MutableList<WindowInsetsAnimation>,
                    ): WindowInsets = insets

                    override fun onEnd(animation: WindowInsetsAnimation) {
                        if (activeAnimations.remove(animation)) ended += 1
                    }
                })
            }
        }

        fun awaitHiddenAndLaidOut() {
            // Already-hidden keyboards and disabled/instant animations are valid:
            // do not require an onEnd event that Android has no reason to emit.
            awaitPlatform("IME hidden with no unfinished animation") { hiddenAndFinished() }
            settle()
            val laidOut = AtomicBoolean(false)
            compose.runOnUiThread {
                val listener = object : ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        preDrawCalls += 1
                        if (hiddenAndFinished() && !hasPendingLayout()) {
                            laidOut.set(true)
                            host.viewTreeObserver.removeOnPreDrawListener(this)
                            preDraw = null
                        }
                        return true
                    }
                }
                preDraw = listener
                host.viewTreeObserver.addOnPreDrawListener(listener)
                host.invalidate()
            }
            awaitPlatform("post-IME layout reached pre-draw") { laidOut.get() && hiddenAndFinished() }
            compose.waitForIdle()
        }

        private fun hiddenAndFinished(): Boolean {
            val insets = host.rootWindowInsets ?: return false
            return activeAnimations.isEmpty() && !insets.isVisible(WindowInsets.Type.ime()) &&
                insets.getInsets(WindowInsets.Type.ime()).bottom == 0
        }

        // Observe the public composition owner and its real layout ancestors.
        // Compose 1.9 adds an intentionally unmeasured ARR helper View on API35+;
        // that internal 0x0 child can keep isLayoutRequested forever. Walking all
        // descendants is not a valid layout-idle contract. Compose's existing
        // waitForIdle handles its own virtual layout nodes separately.
        private fun layoutOwners(): Sequence<View> =
            generateSequence(checkNotNull(compositionView)) { it.parent as? View }

        private fun hasPendingLayout(): Boolean =
            layoutOwners().any { it.visibility != View.GONE && it.isLayoutRequested }

        private fun pendingLayoutOwners(): List<String> =
            layoutOwners().filter { it.visibility != View.GONE && it.isLayoutRequested }
                .map { "${it.javaClass.simpleName}(visibility=${it.visibility},${it.width}x${it.height})" }
                .toList()

        private fun awaitPlatform(description: String, condition: () -> Boolean) {
            try {
                compose.waitUntil(timeoutMillis = 5_000) { compose.runOnUiThread(condition) }
            } catch (failure: ComposeTimeoutException) {
                val state = compose.runOnUiThread {
                    val insets = host.rootWindowInsets
                    "prepared=$prepared ended=$ended active=${activeAnimations.size} preDrawCalls=$preDrawCalls " +
                        "imeVisible=${insets?.isVisible(WindowInsets.Type.ime())} " +
                        "imeBottom=${insets?.getInsets(WindowInsets.Type.ime())?.bottom} " +
                        "layoutPending=${pendingLayoutOwners()}"
                }
                throw AssertionError("Timed out awaiting $description: $state", failure)
            }
        }

        override fun close() {
            compose.runOnUiThread {
                preDraw?.let { host.viewTreeObserver.removeOnPreDrawListener(it) }
                preDraw = null
                host.setWindowInsetsAnimationCallback(null)
            }
        }
    }

    private fun assertComposerBlink(expected: Boolean) {
        compose.onNodeWithTag("composer")
            .assert(SemanticsMatcher.expectValue(ComposerCursorBlinkEnabledKey, expected))
    }

    private fun assertComposerEditingState(text: String, selection: TextRange) {
        compose.onNodeWithTag("composer").assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString(text)))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.TextSelectionRange, selection))
    }

    private fun assertWorkingAnimation(expected: Boolean) {
        compose.onNodeWithTag("working_indicator")
            .assert(SemanticsMatcher.expectValue(WorkingIndicatorAnimatedKey, expected))
    }

    private fun assertStaticPixels(tag: String) {
        val probe = probeRequested()
        val samples = pixelSamples(tag, collectProbeMetadata = probe)
        val unchanged = samples.all { it.samePixelsAs(samples.first()) }
        try {
            assertTrue(
                "$tag must remain visibly static" + if (unchanged) "" else pixelDifferences(samples),
                unchanged,
            )
        } catch (failure: AssertionError) {
            if (probe) {
                try {
                    writeFailureProbe(tag, samples)
                } catch (diagnosticFailure: Throwable) {
                    failure.addSuppressed(diagnosticFailure)
                }
            }
            throw failure
        }
    }

    private fun assertChangingPixels(tag: String) {
        val samples = pixelSamples(tag)
        assertTrue(
            "$tag must not change size during its animation",
            samples.all { it.width == samples.first().width && it.height == samples.first().height },
        )
        assertFalse("$tag must actually animate, not just report enabled", samples.all { it.samePixelsAs(samples.first()) })
    }

    private fun pixelSamples(tag: String, collectProbeMetadata: Boolean = false): List<PixelSample> = List(8) {
        compose.mainClock.advanceTimeBy(160)
        compose.waitForIdle()
        val pixels = compose.onNodeWithTag(tag, useUnmergedTree = true).captureToImage().toPixelMap()
        val platform = if (collectProbeMetadata) probePlatformMetadata() else null
        val argb = IntArray(pixels.width * pixels.height) { index ->
            pixels[index % pixels.width, index / pixels.width].toArgb()
        }
        PixelSample(compose.mainClock.currentTime, pixels.width, pixels.height, argb, platform)
    }

    private fun probeRequested(): Boolean =
        testName.methodName == "normalDisplayCaretActuallyBlinksAndSendStillReturnsToIdleFocus" &&
            InstrumentationRegistry.getArguments().getString("hansDisplayMotionProbe") == "true"

    private fun probePlatformMetadata(): JSONObject = try {
        // Best-effort public getters AFTER capture. No extra UI dispatch, wait or clock advance.
        val root = checkNotNull(compositionView).rootView
        val location = IntArray(2).also(root::getLocationOnScreen)
        val insets = root.rootWindowInsets
        JSONObject()
            .put("rootWidth", root.width).put("rootHeight", root.height)
            .put("rootX", location[0]).put("rootY", location[1])
            .put("layoutRequested", root.isLayoutRequested)
            .put("imeVisible", insets?.isVisible(WindowInsets.Type.ime()) ?: JSONObject.NULL)
            .put("imeBottom", insets?.getInsets(WindowInsets.Type.ime())?.bottom ?: JSONObject.NULL)
    } catch (failure: Throwable) {
        JSONObject().put("unavailable", failure.javaClass.simpleName)
    }

    private fun writeFailureProbe(tag: String, samples: List<PixelSample>) {
        // The guarded host runner enables this only on a fresh owned emulator. Never overwrite.
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
            .toPath().resolve("hans-display-motion-probe")
        Files.createDirectory(directory)
        listOf("first.png" to samples.first(), "last.png" to samples.last()).forEach { (name, sample) ->
            val bitmap = Bitmap.createBitmap(sample.argb, sample.width, sample.height, Bitmap.Config.ARGB_8888)
            try {
                Files.newOutputStream(directory.resolve(name), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                    .use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
            } finally {
                bitmap.recycle()
            }
        }
        val metadata = JSONObject()
            .put("method", testName.methodName).put("tag", tag).put("api", Build.VERSION.SDK_INT)
            .put("sampleCount", samples.size)
            .put("samples", JSONArray(samples.mapIndexed { index, sample ->
                JSONObject().put("index", index).put("clockMs", sample.timeMs)
                    .put("width", sample.width).put("height", sample.height)
                    .put("firstDiff", probePixelDifference(samples.first(), sample))
                    .put("predecessorDiff", if (index == 0) JSONObject.NULL else probePixelDifference(samples[index - 1], sample))
                    .put("platformAfterCapture", sample.platform ?: JSONObject.NULL)
            }))
        Files.newOutputStream(directory.resolve("metadata.json"), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
            .use { it.write(metadata.toString(2).toByteArray(Charsets.UTF_8)) }
    }

    private fun probePixelDifference(before: PixelSample, after: PixelSample): JSONObject {
        val sameSize = before.width == after.width && before.height == after.height
        return JSONObject().put("sameSize", sameSize)
            .put("changedPixels", if (sameSize) after.argb.indices.count { after.argb[it] != before.argb[it] } else JSONObject.NULL)
    }

    private fun pixelDifferences(samples: List<PixelSample>): String {
        val first = samples.first()
        return samples.drop(1).filterNot { it.samePixelsAs(first) }.joinToString(prefix = ": ") { sample ->
            if (sample.width != first.width || sample.height != first.height) {
                "t=${sample.timeMs}ms size ${first.width}x${first.height} -> ${sample.width}x${sample.height}"
            } else {
                var changed = 0
                var minX = sample.width
                var minY = sample.height
                var maxX = -1
                var maxY = -1
                sample.argb.indices.forEach { index ->
                    if (sample.argb[index] != first.argb[index]) {
                        changed += 1
                        val x = index % sample.width
                        val y = index / sample.width
                        minX = minOf(minX, x)
                        minY = minOf(minY, y)
                        maxX = maxOf(maxX, x)
                        maxY = maxOf(maxY, y)
                    }
                }
                "t=${sample.timeMs}ms changed=$changed bounds=($minX,$minY)-($maxX,$maxY)"
            }
        }
    }

    private data class PixelSample(
        val timeMs: Long,
        val width: Int,
        val height: Int,
        val argb: IntArray,
        val platform: JSONObject? = null,
    ) {
        fun samePixelsAs(other: PixelSample): Boolean =
            width == other.width && height == other.height && argb.contentEquals(other.argb)
    }

    private class Fixture(
        mode: DisplayMotionMode,
        working: Boolean,
        observerRegistrationFails: Boolean,
    ) {
        val source = EventAnimationSource(observerRegistrationFails)
        val owner = MotionLifecycleOwner()
        val mode = mutableStateOf(mode)
        val visible = mutableStateOf(true)
        val sent = mutableListOf<String>()
        val requestedModes = mutableListOf<DisplayMotionMode>()
        val chat = mutableStateOf(
            ChatUiState(
                runtimeStatus = RuntimeUiStatus.ONLINE,
                isWorking = working,
            ),
        )
    }

    private class MotionLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle = registry
    }

    private class EventAnimationSource(private val registrationFails: Boolean = false) : SystemAnimationSource {
        private var enabled = true
        var reads = 0
        var closedSubscriptions = 0
        val listeners = mutableSetOf<() -> Unit>()

        override fun animationsEnabled(): Boolean {
            reads += 1
            return enabled
        }

        override fun observe(onChanged: () -> Unit): AutoCloseable {
            check(!registrationFails) { "Fixture: Android declined observer registration" }
            listeners += onChanged
            return AutoCloseable {
                if (listeners.remove(onChanged)) closedSubscriptions += 1
            }
        }

        fun setEnabled(value: Boolean) {
            enabled = value
            listeners.toList().forEach { it() }
        }
    }
}
