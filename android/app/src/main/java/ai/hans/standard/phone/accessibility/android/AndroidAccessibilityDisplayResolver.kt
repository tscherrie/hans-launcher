package ai.hans.standard.phone.accessibility.android

import ai.hans.standard.phone.accessibility.UiBounds
import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.WindowManager

/**
 * A bounded description of the phone display used by root-free app control.
 *
 * AccessibilityService is not guaranteed to be a UI/display Context. In particular,
 * Context.display may throw from a service Context on Android 12+. Always resolve the display id
 * that owns the active Accessibility window through DisplayManager, then obtain window metrics
 * from an explicit window Context.
 */
internal data class AndroidAccessibilityDisplay(
    val displayId: Int,
    val bounds: UiBounds,
)

internal sealed interface AndroidAccessibilityDisplayResolution {
    data class Available(
        val display: AndroidAccessibilityDisplay,
    ) : AndroidAccessibilityDisplayResolution

    data class Failed(
        val failure: AccessibilitySnapshotFailure,
    ) : AndroidAccessibilityDisplayResolution
}

/** Short-lived, testable view of a framework Display. */
internal interface AndroidPhoneDisplay {
    val displayId: Int
    val isValid: Boolean
    fun windowBounds(): UiBounds
}

internal fun interface AndroidPhoneDisplaySource {
    fun display(displayId: Int): AndroidPhoneDisplay?
}

/**
 * Converts every platform failure into one fixed, privacy-safe capture reason. No exception
 * type/message or display metadata crosses the tool boundary.
 */
internal class AndroidAccessibilityDisplayResolver(
    private val source: AndroidPhoneDisplaySource,
) {
    fun resolve(displayId: Int): AndroidAccessibilityDisplayResolution {
        if (displayId < 0) {
            return AndroidAccessibilityDisplayResolution.Failed(
                AccessibilitySnapshotFailure.DISPLAY_INVALID,
            )
        }
        val candidate = try {
            source.display(displayId)
        } catch (_: Exception) {
            return AndroidAccessibilityDisplayResolution.Failed(
                AccessibilitySnapshotFailure.DISPLAY_UNAVAILABLE,
            )
        } ?: return AndroidAccessibilityDisplayResolution.Failed(
            AccessibilitySnapshotFailure.DISPLAY_UNAVAILABLE,
        )

        val resolvedDisplayId = try {
            candidate.displayId
        } catch (_: Exception) {
            return AndroidAccessibilityDisplayResolution.Failed(
                AccessibilitySnapshotFailure.DISPLAY_INVALID,
            )
        }
        val valid = try {
            candidate.isValid
        } catch (_: Exception) {
            return AndroidAccessibilityDisplayResolution.Failed(
                AccessibilitySnapshotFailure.DISPLAY_INVALID,
            )
        }
        if (!valid || resolvedDisplayId != displayId) {
            return AndroidAccessibilityDisplayResolution.Failed(
                AccessibilitySnapshotFailure.DISPLAY_INVALID,
            )
        }

        val bounds = try {
            candidate.windowBounds()
        } catch (_: Exception) {
            return AndroidAccessibilityDisplayResolution.Failed(
                AccessibilitySnapshotFailure.DISPLAY_CONTEXT_FAILED,
            )
        }
        if (bounds.width <= 0 || bounds.height <= 0) {
            return AndroidAccessibilityDisplayResolution.Failed(
                AccessibilitySnapshotFailure.DISPLAY_BOUNDS_FAILED,
            )
        }
        return AndroidAccessibilityDisplayResolution.Available(
            AndroidAccessibilityDisplay(resolvedDisplayId, bounds),
        )
    }
}

/** Public-API implementation for Android 12+. It never reads Context.display. */
internal class AndroidDisplayManagerSource(
    private val context: Context,
) : AndroidPhoneDisplaySource {
    private var cachedDisplayId = Display.INVALID_DISPLAY
    private var cachedWindowContext: Context? = null

    override fun display(displayId: Int): AndroidPhoneDisplay? {
        val manager = context.getSystemService(DisplayManager::class.java) ?: return null
        val display = manager.getDisplay(displayId) ?: return null
        return FrameworkPhoneDisplay(display) { windowBounds(display) }
    }

    @Synchronized
    private fun windowBounds(display: Display): UiBounds {
        val windowContext = cachedWindowContext
            ?.takeIf { cachedDisplayId == display.displayId }
            ?: context.createWindowContext(
                display,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                null,
            ).also {
                cachedDisplayId = display.displayId
                cachedWindowContext = it
            }
        val manager = windowContext.getSystemService(WindowManager::class.java)
            ?: error("window_manager_unavailable")
        val bounds = manager.maximumWindowMetrics.bounds
        return UiBounds(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    private class FrameworkPhoneDisplay(
        private val display: Display,
        private val bounds: () -> UiBounds,
    ) : AndroidPhoneDisplay {
        override val displayId: Int
            get() = display.displayId
        override val isValid: Boolean
            get() = display.isValid

        override fun windowBounds(): UiBounds = bounds()
    }
}
