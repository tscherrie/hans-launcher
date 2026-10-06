package ai.hans.standard.codex

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class DynamicToolExecutionQuiescenceTest {
    @Test fun cancellationReceiptCannotReopenWhenParentSignalReturnsFalse() {
        val cancelled = AtomicBoolean(true)
        var ran = false
        var receipts = 0
        val gate = DynamicToolExecutionGate(DynamicToolCancellation(cancelled::get)) {}
        gate.onQuiescent { receipts++ }
        gate.schedule(Executor(Runnable::run)) { ran = true }
        assertEquals(1, receipts)
        cancelled.set(false)
        assertFalse(gate.markExternalEffectStarted())
        gate.schedule(Executor(Runnable::run)) { ran = true }
        assertFalse(ran)
    }

    @Test fun completionReceiptSurvivesCancellationButResultDoesNot() {
        var results = 0
        var receipts = 0
        val gate = DynamicToolExecutionGate(DynamicToolCancellation.NONE) { results++ }
        gate.markExternalEffectStarted()
        assertTrue(gate.onQuiescent { receipts++ })
        assertEquals(DynamicToolCancellationDisposition.EXTERNAL_EFFECT_MAY_HAVE_STARTED, gate.cancel())
        assertEquals(0, receipts)
        gate.complete(OK)
        gate.complete(OK)
        assertEquals(0, results)
        assertEquals(1, receipts)
        gate.onQuiescent { receipts++ }
        assertEquals(2, receipts)
    }

    @Test fun queuedBeforeEffectCancellationPreventsTaskAndImmediatelyProvesQuiescence() {
        val queued = mutableListOf<Runnable>()
        var effects = 0
        var receipts = 0
        val gate = DynamicToolExecutionGate(DynamicToolCancellation.NONE) {}
        gate.schedule(Executor(queued::add)) { effects++ }
        gate.onQuiescent { receipts++ }
        assertEquals(DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT, gate.cancel())
        assertEquals(1, receipts)
        queued.single().run()
        assertEquals(0, effects)
        assertEquals(1, receipts)
    }

    @Test fun runningReadOnlyWorkCannotPublishQuiescenceBeforeItsLateRetentionFinishes() {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val done = CountDownLatch(1)
        var retained = false
        var receipts = 0
        val gate = DynamicToolExecutionGate(DynamicToolCancellation.NONE) {}
        gate.onQuiescent { receipts++ }
        gate.schedule(Executor { work -> Thread { work.run(); done.countDown() }.start() }) {
            started.countDown()
            assertTrue(finish.await(2, TimeUnit.SECONDS))
            retained = true
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        assertEquals(DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT, gate.cancel())
        assertEquals(0, receipts)
        finish.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue(retained)
        assertEquals(1, receipts)
    }

    @Test fun duplicateCompleteDuringFirstCallbackCannotPublishEarlyReceipt() {
        val entered = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val done = CountDownLatch(1)
        var receipts = 0
        val gate = DynamicToolExecutionGate(DynamicToolCancellation.NONE) {
            entered.countDown()
            leave.await(2, TimeUnit.SECONDS)
        }
        gate.onQuiescent { receipts++ }
        Thread { gate.complete(OK); done.countDown() }.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        gate.complete(OK)
        gate.cancel()
        assertEquals(0, receipts)
        leave.countDown()
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(1, receipts)
    }

    @Test fun taskTailAfterLogicalCompletionStillPrecedesPhysicalReceipt() {
        val queued = mutableListOf<Runnable>()
        val order = mutableListOf<String>()
        val gate = DynamicToolExecutionGate(DynamicToolCancellation.NONE) { order += "callback" }
        gate.onQuiescent { order += "quiescent" }
        gate.schedule(Executor(queued::add)) {
            gate.complete(OK)
            order += "retention-tail"
        }
        queued.single().run()
        assertEquals(listOf("callback", "retention-tail", "quiescent"), order)
        assertFalse(gate.markExternalEffectStarted())
    }

    @Test fun innerCompletionCannotFinishOuterGateAndObserversRunOutsideItsLocks() {
        var outerReceipts = 0
        var lockWasFree = false
        val outer = DynamicToolExecutionGate(DynamicToolCancellation.NONE) {}
        val inner = DynamicToolExecutionGate(DynamicToolCancellation.NONE) {}
        outer.onQuiescent {
            outerReceipts++
            val completed = CountDownLatch(1)
            Thread { outer.onQuiescent {}; completed.countDown() }.start()
            lockWasFree = completed.await(2, TimeUnit.SECONDS)
        }
        inner.complete(OK)
        assertEquals(0, outerReceipts)
        outer.complete(OK)
        assertEquals(1, outerReceipts)
        assertTrue(lockWasFree)
    }

    private companion object { val OK = DynamicToolExecutionResult("{\"ok\":true}", true) }
}
