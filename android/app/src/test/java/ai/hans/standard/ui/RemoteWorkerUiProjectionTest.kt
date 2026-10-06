package ai.hans.standard.ui

import ai.hans.standard.localization.TestResourceTextResolver
import java.util.Locale

import ai.hans.standard.settings.HansSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWorkerUiProjectionTest {
    private val localizationText by lazy { TestResourceTextResolver(Locale.GERMAN) }

    @Test
    fun passiveRemoteWorkerStateIsProjectedWithoutInventingEffectiveness() {
        val localState = RemoteWorkerSettingsUiState(
            revision = 8L,
            loaded = true,
            configured = true,
            requestedEnabled = true,
            effective = false,
            status = RemoteWorkerSettingsUiStatus.NEEDS_ACTIVATION,
            workerId = "gx10-1",
            httpsOrigin = "https://worker.example/",
            serverSpkiSha256 = "b".repeat(64),
            approvedAdapters = listOf(RemoteWorkerAdapterUiDraft("build.gradle", "2.0")),
        )

        val projected = HansClientUiProjector.project(
            client = null,
            local = HansLocalUiState(remoteWorker = localState),
            settings = HansSettings(), text = localizationText).settings.remoteWorker

        assertEquals(localState, projected)
        assertTrue(projected.requestedEnabled)
        assertFalse(projected.effective)
    }
}
