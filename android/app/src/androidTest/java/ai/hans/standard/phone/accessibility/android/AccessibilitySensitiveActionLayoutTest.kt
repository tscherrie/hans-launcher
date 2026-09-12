package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.AccessibilityConfirmationRisk
import ai.hans.standard.phone.accessibility.AccessibilityIdempotencyKey
import ai.hans.standard.phone.accessibility.AccessibilitySessionId
import ai.hans.standard.phone.accessibility.AccessibilitySnapshotId
import ai.hans.standard.phone.accessibility.AccessibilityWindowId
import ai.hans.standard.phone.accessibility.UiSnapshotCorrelation
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import ai.hans.standard.phone.platform.AndroidPlatformCompatibilityProbeActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilitySensitiveActionLayoutTest {
    @Test
    fun smallHeightAndLargeFontKeepActionsPinnedOutsideScrollableBody() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val configuration = Configuration(base.resources.configuration).apply { fontScale = 2f }
        val context = base.createConfigurationContext(configuration)
        val confirmation = AndroidAccessibilitySensitiveActionConfirmation(
            context = context,
            windowManager = checkNotNull(base.getSystemService(WindowManager::class.java)),
            mainHandler = Handler(Looper.getMainLooper()),
            serviceGenerationNonce = GENERATION,
            contextStillMatches = { true },
        )
        val content = confirmation.contentForLayoutTest(prompt()) as LinearLayout

        assertEquals(3, content.childCount)
        assertTrue(content.getChildAt(1) is ScrollView)
        assertTrue(content.getChildAt(2) is LinearLayout)
        val bodyParams = content.getChildAt(1).layoutParams as LinearLayout.LayoutParams
        assertEquals(0, bodyParams.height)
        assertEquals(1f, bodyParams.weight)

        val density = context.resources.displayMetrics.density
        content.measure(
            View.MeasureSpec.makeMeasureSpec((360 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((480 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        content.layout(0, 0, content.measuredWidth, content.measuredHeight)
        val actions = content.getChildAt(2)
        assertTrue(actions.top >= 0)
        assertTrue(actions.bottom <= content.height)
        assertEquals(LinearLayout.VERTICAL, (actions as LinearLayout).orientation)
        assertEquals(2, actions.childCount)
        repeat(actions.childCount) { index ->
            val action = actions.getChildAt(index)
            assertTrue(action.top >= 0)
            assertTrue(action.bottom <= actions.height)
            assertEquals(View.VISIBLE, action.visibility)
        }
    }

    @Test
    fun predictiveBackOnAndroid13PlusDismissesTheAttachedOverlayWithoutFinishingItsHost() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val denied = CountDownLatch(1)
        val denialCount = AtomicInteger(0)
        val rootReference = AtomicReference<
            AndroidAccessibilitySensitiveActionConfirmation.AccessibilityConfirmationRootView,
            >()

        ActivityScenario.launch(AndroidPlatformCompatibilityProbeActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val root = AndroidAccessibilitySensitiveActionConfirmation
                    .AccessibilityConfirmationRootView(activity) {
                        denialCount.incrementAndGet()
                        denied.countDown()
                    }
                rootReference.set(root)
                activity.content.removeAllViews()
                activity.content.addView(
                    root,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                root.requestFocus()
                assertTrue(root.hasPredictiveBackRegistrationForTest())
            }
            instrumentation.waitForIdleSync()

            executeShellAndDrain("input keyevent KEYCODE_BACK")

            assertTrue(
                "The Android 13+ back dispatcher did not dismiss the confirmation",
                denied.await(5, TimeUnit.SECONDS),
            )
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertFalse(activity.isFinishing)
                val root = checkNotNull(rootReference.get())
                assertEquals(1, root.predictiveBackInvocationCountForTest())
                assertEquals(1, denialCount.get())
            }
        }
    }

    @Test
    fun legacyBackKeyPathStillConsumesDownAndUpAndDeniesExactlyOnce() {
        val denialCount = AtomicInteger(0)
        val root = AndroidAccessibilitySensitiveActionConfirmation.AccessibilityConfirmationRootView(
            ApplicationProvider.getApplicationContext<Context>(),
        ) { denialCount.incrementAndGet() }
        val down = KeyEvent(10, 10, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
        val repeat = KeyEvent(10, 20, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 1)
        val up = KeyEvent(10, 30, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)

        assertTrue(root.dispatchKeyEvent(down))
        assertTrue(root.dispatchKeyEvent(repeat))
        assertTrue(root.dispatchKeyEvent(up))
        assertEquals(1, denialCount.get())
        assertEquals(0, root.predictiveBackInvocationCountForTest())
    }

    private fun executeShellAndDrain(command: String) {
        val descriptor: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream -> stream.readBytes() }
    }

    private fun prompt() = AccessibilitySensitiveActionPrompt(
        promptId = "prompt:12345678-1234-1234-1234-123456789abc",
        idempotencyKey = AccessibilityIdempotencyKey("call:layout-test"),
        commandFingerprint = "a".repeat(64),
        risk = AccessibilityConfirmationRisk.CREDENTIAL_UI,
        correlation = UiSnapshotCorrelation(
            AccessibilitySessionId("session-layout-test"),
            AccessibilityWindowId(7),
            AccessibilitySnapshotId(1),
        ),
        serviceGenerationNonce = GENERATION,
        actionKind = AccessibilitySensitiveActionKind.SET_TEXT,
        appLabel = "A very long but trusted application label for a small display",
        packageName = "org.example.application.with.a.long.package.name",
    )

    private companion object {
        const val GENERATION = "service:12345678-1234-1234-1234-123456789abc"
    }
}
