package ai.hans.standard.integration

import ai.hans.standard.voice.tts.TtsMessageId
import ai.hans.standard.voice.tts.TtsMessageKind
import ai.hans.standard.voice.tts.TtsMessageRevision
import ai.hans.standard.voice.tts.CodexReadAloudDelivery
import ai.hans.standard.voice.tts.CodexReadAloudDeliveryPhase
import ai.hans.standard.voice.tts.CodexReadAloudSessionPort
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexSpeechAdmissionTest {
    @Test fun lateDispatchCompletionCannotLeaveDictationPendingOrReplaceItsIntent() {
        val f = Fixture()
        val old = f.admission.beginDispatch()
        f.hold(true, baseline = 7)
        assertFalse(f.admission.dispatchPending)
        val dictationEpoch = f.admission.epoch
        assertEquals(CodexSpeechAdmission.Completion.SUPERSEDED, f.finish(old))
        assertFalse(f.admission.dispatchPending)
        assertFalse(f.admission.rejectDispatch(old))
        f.project("dictation-final")
        assertEquals(listOf(Emission("dictation-final", dictationEpoch)), f.sink.emissions)
        assertEquals(7L, f.lastIntent!!.baselineOrder)
    }

    @Test fun failedOrThrowingDispatchReleasesOnlyItsOwnPendingGate() {
        val f = Fixture()
        f.acceptedTurn(baseline = 2)
        val pending = f.admission.beginDispatch()
        f.project("blocked")
        assertTrue(f.sink.emissions.isEmpty())
        assertTrue(f.admission.rejectDispatch(pending)) // Host's exception path.
        f.project("resumed")
        assertEquals("resumed", f.sink.emissions.single().id)
        assertEquals(2L, f.lastIntent!!.baselineOrder)
        val old = f.admission.beginDispatch()
        val fresh = f.admission.beginDispatch()
        assertFalse(f.admission.rejectDispatch(old))
        assertTrue(f.admission.dispatchPending)
        assertEquals(CodexSpeechAdmission.Completion.REJECTED, f.finish(fresh, accepted = false))
        assertFalse(f.admission.dispatchPending)
    }

    @Test fun rejectedDispatchNeverRollsBackPreviewEpoch() {
        val f = Fixture()
        f.acceptedTurn(baseline = 9)
        val pending = f.admission.beginDispatch()
        f.admission.preview(message("preview"))
        val previewEpoch = f.admission.epoch
        assertEquals(CodexSpeechAdmission.Completion.REJECTED, f.finish(pending, accepted = false))
        f.project("answer")
        assertEquals(previewEpoch, f.lastIntent!!.epoch)
        assertEquals(9L, f.lastIntent!!.baselineOrder)
        assertEquals(listOf(Emission("preview", previewEpoch), Emission("answer", previewEpoch)), f.sink.emissions)
    }

    @Test fun accountOrRuntimeRevocationCannotBeUndoneByLateAcceptedDispatch() {
        val f = Fixture()
        f.acceptedTurn()
        val pending = f.admission.beginDispatch()
        f.admission.revoke()
        val revokedEpoch = f.admission.epoch
        assertFalse(f.admission.dispatchPending)
        assertEquals(CodexSpeechAdmission.Completion.SUPERSEDED, f.finish(pending))
        f.project("old-account-answer")
        assertFalse(f.lastIntent!!.readAloud)
        assertEquals(revokedEpoch, f.lastIntent!!.epoch)
        assertTrue(f.sink.emissions.isEmpty())
        assertFalse(f.sink.enabled)
    }

    @Test fun dictationReleaseUsesIndependentRevisionAfterRevocationAndAllowsNextPreview() {
        val f = Fixture()
        f.hold(true)
        f.admission.revoke()
        val revokedEpoch = f.admission.epoch
        assertTrue(f.admission.inputHeld)
        f.hold(false)
        assertFalse(f.admission.inputHeld)
        assertEquals(listOf(true to 1L, false to 2L), f.sink.holds)
        assertEquals(revokedEpoch, f.admission.epoch)
        f.admission.preview(message("preview-after-recovery"))
        assertFalse(f.sink.inputHeld)
        assertTrue(f.sink.enabled)
        assertEquals("preview-after-recovery", f.sink.emissions.single().id)
    }

    @Test fun repeatedCaptureStateDoesNotCreateNewEpochOrLoseHeldAnswer() {
        val f = Fixture()
        assertTrue(f.hold(true))
        f.project("final-while-held")
        val epoch = f.admission.epoch
        assertFalse(f.hold(true))
        assertEquals(epoch, f.admission.epoch)
        assertEquals(1, f.sink.holds.size)
        assertEquals(1, f.sink.emissions.size)
        assertTrue(f.hold(false))
        assertEquals(listOf(true to 1L, false to 2L), f.sink.holds)
    }

    @Test fun previewPreservesAutomaticFinalAndBaselineOnItsNewEpoch() {
        val f = Fixture()
        f.acceptedTurn(baseline = 42)
        f.admission.preview(message("voice-preview"))
        val epoch = f.admission.epoch
        f.project("normal-final")
        assertTrue(f.lastIntent!!.readAloud)
        assertEquals(42L, f.lastIntent!!.baselineOrder)
        assertEquals(listOf(Emission("voice-preview", epoch), Emission("normal-final", epoch)), f.sink.emissions)
    }

    @Test fun silentTurnSnapshotDoesNotDisableExplicitPreview() {
        val f = Fixture()
        f.admission.preview(message("explicit"))
        val configCount = f.sink.commands.count { it.startsWith("configure") }
        f.project("automatic-disabled")
        assertEquals(configCount, f.sink.commands.count { it.startsWith("configure") })
        assertTrue(f.sink.enabled)
        assertEquals(listOf("explicit"), f.sink.emissions.map { it.id })
        f.admission.project(false, true, false) { _, enabled -> assertFalse(enabled); emptyList() }
        assertFalse(f.sink.enabled)
    }

    @Test fun globalStopInitializesDisabledEpochAndCancelsPendingConsistently() {
        val f = Fixture()
        f.acceptedTurn()
        val pending = f.admission.beginDispatch()
        f.admission.revoke()
        val stoppedEpoch = f.admission.epoch
        assertEquals(stoppedEpoch, f.sink.epoch)
        assertFalse(f.sink.enabled)
        assertFalse(f.admission.dispatchPending)
        f.finish(pending)
        f.project("do-not-resume")
        assertTrue(f.sink.emissions.isEmpty())
        assertEquals(stoppedEpoch, f.admission.epoch)
    }

    @Test fun finalSnapshotCannotObserveIntentBeforeBeginAndConfigureAreEnqueued() {
        val f = Fixture()
        val inBegin = CountDownLatch(1)
        val releaseBegin = CountDownLatch(1)
        val projectionEntered = CountDownLatch(1)
        f.sink.beforeBegin = {
            inBegin.countDown()
            check(releaseBegin.await(2, TimeUnit.SECONDS))
        }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val token = f.admission.beginDispatch()
            val dispatch = pool.submit { f.finish(token) }
            assertTrue(inBegin.await(2, TimeUnit.SECONDS))
            val projection = pool.submit {
                f.admission.project(true, false, false) { intent, enabled ->
                    projectionEntered.countDown()
                    assertTrue(enabled)
                    assertEquals(f.sink.epoch, intent.epoch)
                    assertTrue(f.sink.enabled)
                    listOf(message("fast-final"))
                }
            }
            assertFalse(projectionEntered.await(40, TimeUnit.MILLISECONDS))
            releaseBegin.countDown()
            dispatch.get(2, TimeUnit.SECONDS)
            projection.get(2, TimeUnit.SECONDS)
            assertEquals(listOf(Emission("fast-final", f.admission.epoch)), f.sink.emissions)
            val submit = f.sink.commands.indexOfFirst { it.startsWith("submit") }
            val configure = f.sink.commands.indexOfFirst { it.startsWith("configure:true") }
            assertTrue(configure in 0 until submit)
        } finally { releaseBegin.countDown(); pool.shutdownNow() }
    }

    @Test fun projectionConsumptionAndSubmitCannotStraddlePreviewEpoch() {
        val f = Fixture()
        f.acceptedTurn()
        val previousEpoch = f.admission.epoch
        val inProjection = CountDownLatch(1)
        val releaseProjection = CountDownLatch(1)
        val previewFinished = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val projected = pool.submit {
                f.admission.project(true, false, false) { _, _ ->
                    inProjection.countDown()
                    check(releaseProjection.await(2, TimeUnit.SECONDS))
                    listOf(message("existing-final"))
                }
            }
            assertTrue(inProjection.await(2, TimeUnit.SECONDS))
            val preview = pool.submit {
                f.admission.preview(message("preview"))
                previewFinished.countDown()
            }
            assertFalse(previewFinished.await(40, TimeUnit.MILLISECONDS))
            releaseProjection.countDown()
            projected.get(2, TimeUnit.SECONDS)
            preview.get(2, TimeUnit.SECONDS)
            assertEquals(listOf(Emission("existing-final", previousEpoch),
                Emission("preview", f.admission.epoch)), f.sink.emissions)
        } finally { releaseProjection.countDown(); pool.shutdownNow() }
    }

    @Test fun dictationEpochPostsPhysicalHoldBeforeEnablingOrProjectingAnswer() {
        val f = Fixture()
        f.hold(true)
        f.project("answer")
        assertEquals(listOf("begin:1", "hold:true:1", "configure:true:1"), f.sink.commands.take(3))
        assertTrue(f.sink.inputHeld)
        f.hold(false)
        assertTrue(f.sink.commands.indexOf("hold:false:2") < f.sink.commands.lastIndexOf("prepare:1"))
    }

    @Test fun actualDeliveryReceivesImmediateFinalAfterAcceptedDispatchExactlyOnce() = Combined().use { f ->
        f.admission.finishDispatch(f.admission.beginDispatch(), true, true, 0, true, false)
        f.final("fast-final") // No wait between intent completion and an immediately completed answer.
        f.awaitSpoken(1)
        f.final("fast-final")
        f.drain()
        assertEquals(listOf("fast-final"), f.spoken.toList())
    }

    @Test fun actualPhysicalBarrierPreservesFinalWhileHeldAcrossRuntimeRevocation() = Combined().use { f ->
        f.admission.setInputHeld(true, 0, true, false, true)
        f.admission.revoke()
        f.admission.setInputHeld(false, 0, true, false, true)
        f.admission.setInputHeld(true, 0, true, false, true)
        f.final("held-final")
        assertTrue(f.delivery.stopOutputAndAwait(1_000))
        assertTrue(f.spoken.isEmpty())
        f.admission.setInputHeld(false, 0, true, false, true)
        f.awaitSpoken(1)
        assertEquals(listOf("held-final"), f.spoken.toList())
    }

    @Test fun actualPreviewAndStillRunningAutomaticFinalUseTheSameNewEpoch() = Combined().use { f ->
        f.admission.finishDispatch(f.admission.beginDispatch(), true, true, 0, true, false)
        f.admission.preview(message("preview").copy(text = "preview"))
        f.final("automatic-final")
        f.awaitSpoken(1)
        assertEquals(listOf("preview"), f.spoken.toList())
        f.ports.last().finished()
        f.awaitSpoken(2)
        assertEquals(listOf("preview", "automatic-final"), f.spoken.toList())
    }

    private class Combined : AutoCloseable {
        val executor = Executors.newSingleThreadExecutor()
        val spoken = CopyOnWriteArrayList<String>()
        val ports = CopyOnWriteArrayList<Port>()
        val delivery = CodexReadAloudDelivery(sessionFactory = { _, callback ->
            Port(callback, spoken).also(ports::add)
        }, serialExecutor = executor)
        val admission = CodexSpeechAdmission(Any(), object : CodexSpeechAdmission.Sink {
            override fun begin(epoch: Long) { delivery.beginTurn(epoch) }
            override fun configure(enabled: Boolean, allowIntermediate: Boolean, epoch: Long) {
                delivery.configure(enabled, allowIntermediate, epoch)
            }
            override fun held(active: Boolean, inputRevision: Long) { delivery.setDictationHeld(active, inputRevision) }
            override fun prepare(epoch: Long) { delivery.prepare(epoch) }
            override fun submit(revision: TtsMessageRevision, epoch: Long) { delivery.submit(revision, epoch) }
        })
        fun final(id: String) = admission.project(true, false, false) { _, enabled ->
            if (enabled) listOf(message(id).copy(text = id)) else emptyList()
        }
        fun drain() { repeat(6) { executor.submit {}.get(2, TimeUnit.SECONDS) } }
        fun awaitSpoken(count: Int) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (spoken.size < count && System.nanoTime() < deadline) { drain(); Thread.sleep(2) }
            assertEquals(count, spoken.size)
        }
        override fun close() {
            delivery.stopAndAwait(1_000)
            delivery.close()
            drain()
            executor.shutdownNow()
        }
        class Port(private val callback: (CodexReadAloudDeliveryPhase) -> Unit,
            private val spoken: MutableList<String>) : CodexReadAloudSessionPort {
            override fun prepare() { callback(CodexReadAloudDeliveryPhase.READY) }
            override fun speak(text: String): Boolean { spoken.add(text); return true }
            override fun stop() = Unit
            override fun stopAndAwait(timeoutMillis: Long) = true
            override fun close() = Unit
            fun finished() { callback(CodexReadAloudDeliveryPhase.STOPPED) }
        }
    }

    private class Fixture {
        val sink = Sink()
        val admission = CodexSpeechAdmission(Any(), sink)
        var lastIntent: CodexSpeechAdmission.Intent? = null
        fun finish(token: CodexSpeechAdmission.Dispatch, accepted: Boolean = true, baseline: Long = 0) =
            admission.finishDispatch(token, accepted, true, baseline, true, false)
        fun acceptedTurn(baseline: Long = 0) { finish(admission.beginDispatch(), baseline = baseline) }
        fun hold(active: Boolean, baseline: Long = 0) =
            admission.setInputHeld(active, baseline, true, false, true)
        fun project(id: String) = admission.project(true, false, false) { intent, enabled ->
            lastIntent = intent
            if (enabled) listOf(message(id)) else emptyList()
        }
    }
    private data class Emission(val id: String, val epoch: Long)
    private class Sink : CodexSpeechAdmission.Sink {
        val commands = CopyOnWriteArrayList<String>()
        val emissions = CopyOnWriteArrayList<Emission>()
        val holds = CopyOnWriteArrayList<Pair<Boolean, Long>>()
        var beforeBegin: (() -> Unit)? = null
        var epoch = 0L
        var enabled = false
        var inputHeld = false
        override fun begin(epoch: Long) {
            beforeBegin?.invoke()
            this.epoch = epoch
            enabled = false
            commands += "begin:$epoch"
        }
        override fun configure(enabled: Boolean, allowIntermediate: Boolean, epoch: Long) {
            assertEquals(this.epoch, epoch)
            this.enabled = enabled
            commands += "configure:$enabled:$epoch"
        }
        override fun held(active: Boolean, inputRevision: Long) {
            holds += active to inputRevision
            inputHeld = active
            commands += "hold:$active:$inputRevision"
        }
        override fun prepare(epoch: Long) { assertEquals(this.epoch, epoch); commands += "prepare:$epoch" }
        override fun submit(revision: TtsMessageRevision, epoch: Long) {
            assertEquals(this.epoch, epoch)
            assertTrue(enabled)
            emissions += Emission(revision.messageId.value, epoch)
            commands += "submit:${revision.messageId.value}:$epoch"
        }
    }
    companion object {
        private fun message(id: String) = TtsMessageRevision(TtsMessageId(id), 1, "Visible answer",
            TtsMessageKind.FINAL_OUTPUT, true)
    }
}
