package ai.hans.standard.ui

import ai.hans.standard.localization.TestResourceTextResolver

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteWorkerConfigurationUiDraftTest {
    @Test
    fun exactHttpsPinAndAdapterVersionAreAccepted() {
        val draft = validDraft()

        assertNull(draft.validationMessage(TestResourceTextResolver()))
        assertTrue(draft.canSave)
    }

    @Test
    fun connectionMetadataNeverEntersDraftOrStateToString() {
        val draft = validDraft()
        val state = RemoteWorkerSettingsUiState(
            workerId = draft.workerId,
            httpsOrigin = draft.httpsOrigin,
            serverSpkiSha256 = draft.serverSpkiSha256,
            approvedAdapters = draft.adapters,
        )

        listOf(draft.toString(), state.toString()).forEach { rendered ->
            assertFalse(rendered.contains("worker.example"))
            assertFalse(rendered.contains("a".repeat(64)))
            assertTrue(rendered.contains("connectionMetadata=<redacted>"))
        }
        assertEquals(1, draft.adapters.size)
    }

    @Test
    fun pathPinAndPartialAdapterRowsFailClosed() {
        assertFalse(validDraft().copy(httpsOrigin = "https://worker.example/path").canSave)
        assertFalse(validDraft().copy(serverSpkiSha256 = "a".repeat(63)).canSave)
        assertFalse(
            validDraft().copy(
                adapters = listOf(RemoteWorkerAdapterUiDraft("build.gradle", "")),
            ).canSave,
        )
    }

    @Test
    fun duplicateAdaptersAndEnabledEmptyAllowListAreRejected() {
        val adapter = RemoteWorkerAdapterUiDraft("build.gradle", "1.2.3")
        assertFalse(validDraft().copy(adapters = listOf(adapter, adapter)).canSave)
        assertFalse(validDraft().copy(adapters = emptyList()).canSave)
        assertTrue(
            validDraft().copy(
                requestedEnabled = false,
                adapters = emptyList(),
            ).canSave,
        )
        assertFalse(
            validDraft().copy(
                adapters = listOf(RemoteWorkerAdapterUiDraft("phone.shell", "1")),
            ).canSave,
        )
    }

    private fun validDraft() = RemoteWorkerConfigurationUiDraft(
        workerId = "gx10-1",
        httpsOrigin = "https://worker.example",
        serverSpkiSha256 = "a".repeat(64),
        adapters = listOf(RemoteWorkerAdapterUiDraft("build.gradle", "1.2.3")),
        requestedEnabled = true,
    )
}
