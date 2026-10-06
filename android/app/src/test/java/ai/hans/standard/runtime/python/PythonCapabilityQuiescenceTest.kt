package ai.hans.standard.runtime.python

import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PythonCapabilityQuiescenceTest {
    @Test fun parentResultAndCancelWaitForEveryPhysicalChildReceipt() {
        val results = mutableListOf<PythonExecutionResult>()
        val scope = PythonCapabilityExecutionScope(INLINE, results::add)
        val first = ChildHandle()
        val second = ChildHandle()
        scope.publish(checkNotNull(scope.begin(1)), first)
        scope.publish(checkNotNull(scope.begin(2)), second)
        var quiet = false
        scope.onQuiescent { quiet = true }
        scope.finish(RESULT, workerPhysicallyFinished = true)
        assertEquals(1, first.cancels)
        assertEquals(1, second.cancels)
        assertTrue(results.isEmpty())
        assertFalse(quiet)
        first.finish()
        assertTrue(results.isEmpty())
        second.finish()
        assertEquals(listOf(RESULT), results)
        assertTrue(quiet)
        second.finish()
        assertEquals(1, results.size)
    }

    @Test fun inDispatchChildIsReservedBeforeItsHandleOrSynchronousResultIsPublished() {
        val results = mutableListOf<PythonExecutionResult>()
        val scope = PythonCapabilityExecutionScope(INLINE, results::add)
        val reservation = checkNotNull(scope.begin(1))
        reservation.responded.set(true) // Callback-before-handle must not remove this reservation.
        scope.finish(RESULT, workerPhysicallyFinished = true)
        assertTrue(results.isEmpty())
        val handle = ChildHandle()
        scope.publish(reservation, handle)
        assertEquals(1, handle.cancels)
        assertTrue(results.isEmpty())
        handle.finish()
        assertEquals(listOf(RESULT), results)
    }

    @Test fun missingChildReceiptSupportAndThrownDispatchStayExplicitlyUnknown() {
        val results = mutableListOf<PythonExecutionResult>()
        val scope = PythonCapabilityExecutionScope(INLINE, results::add)
        scope.publish(checkNotNull(scope.begin(1)), ChildHandle(supported = false))
        val failed = checkNotNull(scope.begin(2))
        scope.dispatchFailed(failed)
        var quiet = false
        assertTrue(scope.onQuiescent { quiet = true })
        scope.finish(RESULT, workerPhysicallyFinished = true)
        scope.cancelChildren()
        scope.finish(RESULT, workerPhysicallyFinished = true)
        assertTrue(scope.hasUnknownChildren)
        assertTrue(results.isEmpty())
        assertFalse(quiet)
    }

    @Test fun uncertainWorkerFailureRequiresActualWorkerTerminationEvenAfterChildrenFinish() {
        val results = mutableListOf<PythonExecutionResult>()
        val scope = PythonCapabilityExecutionScope(INLINE, results::add)
        val child = ChildHandle()
        scope.publish(checkNotNull(scope.begin(1)), child)
        scope.finish(RESULT, workerPhysicallyFinished = false)
        child.finish()
        assertFalse(scope.isWorkerFinished())
        assertTrue(results.isEmpty())
        scope.finish(RESULT, workerPhysicallyFinished = true)
        assertEquals(listOf(RESULT), results)
    }

    @Test fun receiptWaitsForActualParentCallbackExecutionAndNeverInvokesObserversUnderScopeLock() {
        val callbacks = mutableListOf<Runnable>()
        var quiet = false
        val scope = PythonCapabilityExecutionScope(Executor(callbacks::add)) {
            assertFalse(quiet)
        }
        scope.onQuiescent {
            val queried = CountDownLatch(1)
            Thread { scope.hasUnknownChildren; queried.countDown() }.start()
            assertTrue(queried.await(2, TimeUnit.SECONDS))
            quiet = true
        }
        scope.finish(RESULT, workerPhysicallyFinished = true)
        assertFalse(quiet)
        assertEquals(1, callbacks.size)
        callbacks.single().run()
        assertTrue(quiet)
        var late = false
        scope.onQuiescent { late = true }
        assertTrue(late)
    }

    @Test fun stopClosesAdmissionAndDuplicateOrExcessiveChildrenCannotDispatch() {
        val scope = PythonCapabilityExecutionScope(INLINE) {}
        assertNull(scope.begin(-1))
        assertNotNull(scope.begin(0))
        assertNull(scope.begin(0))
        (1L..63L).forEach { assertNotNull(scope.begin(it)) }
        assertNull(scope.begin(64))
        scope.cancelChildren()
        assertFalse(scope.acceptsResponses())
        assertNull(scope.begin(65))
    }

    @Test fun syntheticProcessDiedCallbackCannotSubstituteForTheDelegatePhysicalReceipt() {
        val receipt = PythonPhysicalExecutionReceipt()
        val runtime = RuntimeHandle()
        var quiet = false
        receipt.onQuiescent { quiet = true }
        receipt.callbackFinished() // Supervisor's synthetic failure, possibly before handle.
        assertFalse(quiet)
        receipt.publish(runtime)
        runtime.cancel()
        assertFalse(quiet)
        runtime.finish()
        assertTrue(quiet)
    }

    @Test fun physicalDelegateReceiptStillWaitsForOuterCallbackAndUnsupportedDelegateNeverFallsBack() {
        val receipt = PythonPhysicalExecutionReceipt()
        val runtime = RuntimeHandle()
        var quiet = false
        receipt.onQuiescent { quiet = true }
        receipt.publish(runtime)
        runtime.finish()
        assertFalse(quiet)
        receipt.callbackFinished()
        assertTrue(quiet)

        val unknown = PythonPhysicalExecutionReceipt()
        var unknownQuiet = false
        unknown.onQuiescent { unknownQuiet = true }
        unknown.callbackFinished()
        unknown.publish(object : PythonExecutionHandle { override fun cancel() = true })
        assertTrue(unknown.hasUnknownSupport)
        assertFalse(unknownQuiet)
    }

    @Test fun provenNoDispatchCanFinishOnlyAfterItsCallbackReturns() {
        val receipt = PythonPhysicalExecutionReceipt()
        var quiet = false
        receipt.onQuiescent { quiet = true }
        receipt.notDispatched()
        assertFalse(quiet)
        receipt.callbackFinished()
        assertTrue(quiet)
    }

    private class ChildHandle(private val supported: Boolean = true) : PythonCapabilityHandle {
        private val receipt = PythonQuiescenceReceipt()
        var cancels = 0
        override fun cancel() { cancels++ }
        override fun onQuiescent(listener: () -> Unit) = if (supported) receipt.onQuiescent(listener) else false
        fun finish() = receipt.complete()
    }

    private class RuntimeHandle : PythonExecutionHandle {
        private val receipt = PythonQuiescenceReceipt()
        override fun cancel() = true
        override fun onQuiescent(listener: () -> Unit) = receipt.onQuiescent(listener)
        fun finish() = receipt.complete()
    }

    companion object {
        private val INLINE = Executor(Runnable::run)
        private val RESULT = PythonExecutionResult("python-join-test", PythonExecutionStatus.CANCELLED)
    }
}
