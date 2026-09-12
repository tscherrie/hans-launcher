package ai.hans.standard.ui

import ai.hans.standard.codex.DispatchOptions
import ai.hans.standard.codex.ThreadRuntimeStatus
import ai.hans.standard.codex.ThreadStatusSnapshot
import ai.hans.standard.codex.ThreadUiSnapshot
import ai.hans.standard.integration.ClientRuntimePhase
import ai.hans.standard.integration.ClientSessionPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaCleanupRecoveryTest {
    @Test
    fun onlyReadyRehydratedIdleCurrentThreadAuthorizesRecoveryCleanup() {
        assertEquals(
            setOf("thread-1"),
            idleThreads(),
        )
        assertTrue(idleThreads(runtimePhase = ClientRuntimePhase.RESTARTING).isEmpty())
        assertTrue(idleThreads(sessionPhase = ClientSessionPhase.RECOVERING_THREAD).isEmpty())
        assertTrue(idleThreads(rehydrationRequired = true).isEmpty())
        assertTrue(idleThreads(threadStatus = ThreadRuntimeStatus.ACTIVE).isEmpty())
        assertTrue(idleThreads(threadStatus = ThreadRuntimeStatus.NOT_LOADED).isEmpty())
        assertTrue(idleThreads(currentThreadId = "thread-other").isEmpty())
        assertTrue(idleThreads(currentThreadId = null).isEmpty())
    }

    private fun idleThreads(
        runtimePhase: ClientRuntimePhase = ClientRuntimePhase.READY,
        sessionPhase: ClientSessionPhase = ClientSessionPhase.READY,
        rehydrationRequired: Boolean = false,
        currentThreadId: String? = "thread-1",
        threadStatus: ThreadRuntimeStatus = ThreadRuntimeStatus.IDLE,
    ): Set<String> = authoritativeIdleMediaThreadIds(
        runtimePhase = runtimePhase,
        sessionPhase = sessionPhase,
        rehydrationRequired = rehydrationRequired,
        currentThreadId = currentThreadId,
        threads = listOf(
            ThreadUiSnapshot(
                threadId = "thread-1",
                name = null,
                preview = "",
                runtimeStatus = ThreadStatusSnapshot(threadStatus),
                effectiveOptions = DispatchOptions.DEFAULT,
                currentTurn = null,
                messages = emptyList(),
                tools = emptyList(),
            ),
        ),
    )
}
