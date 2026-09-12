package ai.hans.standard.integration

import ai.hans.standard.runtime.AppServerSessionContract
import ai.hans.standard.runtime.IAppServerSessionCallback
import ai.hans.standard.runtime.IRuntimeService
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the actual Android Binder adapter without launching a process or reading app data. */
@RunWith(AndroidJUnit4::class)
class BinderSessionRuntimeTransportTest {
    @Test
    fun repeatedPreflightFailuresAreOperationCorrelatedWithoutConsumingStreamSequence() {
        val fixture = Fixture()
        fixture.transport.start(1, fixture.listener)
        fixture.emit(1, 0, 0, AppServerSessionContract.STATE_FAILED)
        fixture.transport.start(2, fixture.listener)
        fixture.emit(1, 0, 0, AppServerSessionContract.STATE_FAILED) // Delayed prior reply.
        fixture.emit(2, 0, 0, AppServerSessionContract.STATE_FAILED)
        fixture.emit(2, 0, 0, AppServerSessionContract.STATE_FAILED) // Duplicate.
        assertEquals(listOf(1L, 2L), fixture.listener.states.map { it.operation })

        fixture.transport.start(3, fixture.listener)
        fixture.emit(3, 1, 1, AppServerSessionContract.STATE_STARTING)
        fixture.emit(3, 1, 2, AppServerSessionContract.STATE_READY)
        assertEquals(AppServerSessionContract.STATE_READY, fixture.listener.states.last().state)
        assertTrue(fixture.listener.failures.isEmpty())
    }

    @Test
    fun preflightFailureAfterAnExitedGenerationDoesNotInvalidateSubsequentGeneration() {
        val fixture = Fixture()
        fixture.transport.start(1, fixture.listener)
        fixture.emit(1, 1, 1, AppServerSessionContract.STATE_STARTING)
        fixture.emit(1, 1, 2, AppServerSessionContract.STATE_READY)
        fixture.emit(0, 1, 3, AppServerSessionContract.STATE_EXITED)

        fixture.transport.start(2, fixture.listener)
        fixture.emit(2, 0, 0, AppServerSessionContract.STATE_FAILED)
        assertEquals(State(2, 0, AppServerSessionContract.STATE_FAILED), fixture.listener.states.last())
        fixture.transport.start(3, fixture.listener)
        fixture.emit(3, 2, 1, AppServerSessionContract.STATE_STARTING)
        fixture.emit(3, 2, 2, AppServerSessionContract.STATE_READY)
        fixture.transport.sendFrame(2, "{}".toByteArray())
        assertEquals(1, fixture.sentFrames)
        assertTrue(fixture.listener.failures.isEmpty())
    }

    @Test
    fun restartStoppingReceiptDoesNotConsumeThePendingReplacementResult() {
        val fixture = Fixture()
        fixture.transport.start(1, fixture.listener)
        fixture.emit(1, 1, 1, AppServerSessionContract.STATE_STARTING)
        fixture.emit(1, 1, 2, AppServerSessionContract.STATE_READY)
        fixture.transport.restart(2, 1)
        fixture.emit(2, 1, 3, AppServerSessionContract.STATE_STOPPING)
        fixture.emit(2, 1, 4, AppServerSessionContract.STATE_STOPPED)
        fixture.emit(2, 0, 0, AppServerSessionContract.STATE_FAILED)

        assertEquals(State(2, 0, AppServerSessionContract.STATE_FAILED), fixture.listener.states.last())
        assertTrue(fixture.listener.failures.isEmpty())
    }

    @Test
    fun malformedAndLateStandaloneEventsCannotMasqueradeAsProcessLifecycle() {
        val fixture = Fixture()
        fixture.transport.start(1, fixture.listener)
        fixture.emit(1, 0, 0, AppServerSessionContract.STATE_READY)
        fixture.emit(1, 0, 1, AppServerSessionContract.STATE_FAILED)
        fixture.emit(99, 0, 0, AppServerSessionContract.STATE_FAILED)
        assertTrue(fixture.listener.states.isEmpty())
        fixture.emit(1, 1, 1, AppServerSessionContract.STATE_STARTING)
        fixture.emit(1, 0, 0, AppServerSessionContract.STATE_FAILED) // Start is already proven.
        fixture.emit(1, 1, 2, AppServerSessionContract.STATE_READY)
        assertEquals(2, fixture.listener.states.size)
        assertTrue(fixture.listener.failures.isEmpty())
    }

    private class Fixture {
        private lateinit var callback: IAppServerSessionCallback
        val listener = Listener()
        var sentFrames = 0
        private val runtime = Proxy.newProxyInstance(
            IRuntimeService::class.java.classLoader,
            arrayOf(IRuntimeService::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "getSessionProtocolVersion" -> AppServerSessionContract.PROTOCOL_VERSION
                "startAppServerSession" -> { callback = arguments!![1] as IAppServerSessionCallback; null }
                "sendAppServerFrameChunk" -> { sentFrames += 1; null }
                else -> null
            }
        } as IRuntimeService
        val transport = BinderSessionRuntimeTransport(runtime)

        fun emit(operation: Long, generation: Long, sequence: Long, state: Int) {
            callback.onSessionState(operation, generation, sequence, state, 123, "synthetic")
        }
    }

    private data class State(val operation: Long, val generation: Long, val state: Int)

    private class Listener : RuntimeSessionListener {
        val states = mutableListOf<State>()
        val failures = mutableListOf<TransportProtocolFailure>()
        override fun onSessionState(operationId: Long, generation: Long, eventSequence: Long, state: Int) {
            states += State(operationId, generation, state)
        }
        override fun onServerFrame(generation: Long, eventSequence: Long, frame: ByteArray) = Unit
        override fun onTransportNotice(generation: Long, eventSequence: Long, code: Int, relatedSequence: Long) = Unit
        override fun onTransportProtocolFailure(failure: TransportProtocolFailure) { failures += failure }
    }
}
