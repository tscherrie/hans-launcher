package ai.hans.standard.phone.keys

internal data class GlobalActionKeyCaptureRoutingDecision(
    val consume: Boolean,
    val deliverToSink: Boolean,
)

/**
 * Owns complete press streams accepted during a capture lease. If the lease
 * ends after DOWN (timeout, cancellation, lifecycle teardown), its matching UP
 * is still consumed so the foreground app never receives an orphan release.
 */
internal class GlobalActionKeyCaptureRoutingEngine {
    private val ownedPresses = mutableListOf<OwnedCapturePress>()

    @Synchronized
    fun requiresFrameworkFiltering(captureActive: Boolean): Boolean =
        captureActive || ownedPresses.isNotEmpty()

    @Synchronized
    fun onDeliveredEvent(
        event: ObservableAndroidKeyEvent,
        captureActive: Boolean,
    ): GlobalActionKeyCaptureRoutingDecision {
        val owned = ownedPresses.firstOrNull { it.matches(event) }
        if (captureActive) {
            when (event.phase) {
                ObservableKeyPhase.DOWN -> if (owned == null) {
                    ownedPresses += OwnedCapturePress.from(event)
                }
                ObservableKeyPhase.UP -> if (owned != null) {
                    ownedPresses.remove(owned)
                }
            }
            return GlobalActionKeyCaptureRoutingDecision(
                consume = true,
                deliverToSink = true,
            )
        }
        if (owned == null) {
            return GlobalActionKeyCaptureRoutingDecision(
                consume = false,
                deliverToSink = false,
            )
        }
        if (event.phase == ObservableKeyPhase.UP) ownedPresses.remove(owned)
        return GlobalActionKeyCaptureRoutingDecision(
            consume = true,
            deliverToSink = false,
        )
    }

    @Synchronized
    fun close() {
        ownedPresses.clear()
    }

    private data class OwnedCapturePress(
        val device: PhysicalKeyDeviceSelector?,
        val source: Int,
        val scanCode: Int,
        val keyCode: Int,
        val downTimeMillis: Long,
    ) {
        fun matches(event: ObservableAndroidKeyEvent): Boolean =
            device == event.physicalDevice?.selector() &&
                source == event.source &&
                scanCode == event.scanCode &&
                keyCode == event.keyCode &&
                downTimeMillis == event.downTimeMillis

        companion object {
            fun from(event: ObservableAndroidKeyEvent) = OwnedCapturePress(
                device = event.physicalDevice?.selector(),
                source = event.source,
                scanCode = event.scanCode,
                keyCode = event.keyCode,
                downTimeMillis = event.downTimeMillis,
            )
        }
    }
}
