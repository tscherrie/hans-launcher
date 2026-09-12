package ai.hans.standard.phone.accessibility.android

import java.util.concurrent.atomic.AtomicReference

/**
 * Fixed, privacy-safe reasons why the service could not publish a semantic snapshot.
 *
 * These values intentionally contain no package names, UI text, exception classes/messages,
 * window titles, node identifiers or other data originating outside Hans. They are safe to
 * expose as bounded diagnostic metadata in an App Server tool receipt.
 */
enum class AccessibilitySnapshotFailure(val detailCode: String) {
    DISPLAY_UNAVAILABLE("display_unavailable"),
    DISPLAY_INVALID("display_invalid"),
    DISPLAY_CONTEXT_FAILED("display_context_failed"),
    NO_ACTIVE_ROOT("no_active_root"),
    INVALID_WINDOW("invalid_window"),
    DISPLAY_BOUNDS_FAILED("display_bounds_failed"),
    PROJECTION_ROOT_READ_FAILED("projection_root_read_failed"),
    PROJECTION_EMPTY("projection_empty"),
    PROJECTION_FAILED("projection_failed"),
    SNAPSHOT_FACTORY_FAILED("snapshot_factory_failed"),
    SNAPSHOT_PUBLICATION_FAILED("snapshot_publication_failed"),
    SNAPSHOT_ID_EXHAUSTED("snapshot_id_exhausted"),
    CAPTURE_FAILED("capture_failed"),
    UNCLASSIFIED("snapshot_failure_unclassified"),
}

/** Process-local last-failure register. A successful publication clears stale diagnostics. */
internal class AccessibilitySnapshotDiagnostics {
    private val latest = AtomicReference<AccessibilitySnapshotFailure?>()

    fun record(failure: AccessibilitySnapshotFailure) {
        latest.set(failure)
    }

    fun clear() {
        latest.set(null)
    }

    fun latest(): AccessibilitySnapshotFailure? = latest.get()
}
