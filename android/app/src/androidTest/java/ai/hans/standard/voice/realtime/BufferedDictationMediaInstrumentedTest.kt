package ai.hans.standard.voice.realtime

import android.media.AudioManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.AudioTrackSink
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.RtpReceiver
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.audio.JavaAudioDeviceModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/** Two local native peers only: no account, external service, microphone capture or speaker output. */
@RunWith(AndroidJUnit4::class)
class BufferedDictationMediaInstrumentedTest {
    @Test fun primaryBufferedPcmReachesLocalPeerWithoutAnyPhysicalMicrophone() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(AudioManager::class.java)
        val originalMode = manager.mode
        val originalRoute = manager.communicationDevice?.id
        val originalRecorders = manager.activeRecordingConfigurations.map { it.clientAudioSessionId }.toSet()
        val opened = CountDownLatch(1)
        val appended = CountDownLatch(1)
        val appendResult = AtomicReference<Result<Unit>?>()
        val failure = AtomicReference<LiveVoiceFailure?>()
        val provider = LocalAnswerProvider(collectAudio = true)
        val transport = AndroidWebRtcRealtimeTransport(context, provider,
            mediaMode = LiveVoiceMediaMode.BUFFERED_DICTATION_PRIMARY_SILENT)
        val pcm = ByteArray(48_000)
        repeat(24_000) { index ->
            val sample = (sin(2.0 * PI * 440.0 * index / 24_000.0) * 12_000).toInt()
            pcm[index * 2] = sample.toByte()
            pcm[index * 2 + 1] = (sample shr 8).toByte()
        }
        try {
            // Deliberately enqueue BEFORE connect. Neither negotiation nor unmute may consume
            // the prefix until confirmSessionStarted establishes the session-ready boundary.
            assertTrue(transport.appendInputAudio(pcm, 24_000) {
                appendResult.set(it); appended.countDown()
            })
            pcm.fill(0)
            assertTrue(transport.setUserInputMuted(false))
            transport.connect(LiveSessionSetup(LiveVoiceSessionConfig(), "Synthetic primary RTP test"),
                object : LiveVoiceTransport.Listener {
                    override fun onOpen() { opened.countDown() }
                    override fun onEvent(event: String) = Unit
                    override fun onClosed(problem: LiveVoiceFailure?) { failure.set(problem); opened.countDown() }
                })
            provider.awaitLocalConnection(opened, failure)
            assertNull("Transport failed before readiness", failure.get())
            assertEquals("Prefix was consumed before readiness", 1L, appended.count)
            assertTrue(transport.confirmSessionStarted())
            assertTrue("PCM did not cross the ADM callback", appended.await(10, TimeUnit.SECONDS))
            assertTrue("PCM submission failed", appendResult.get()?.isSuccess == true)
            assertTrue("Local RTP receiver did not observe nonzero decoded PCM",
                provider.nonzeroAudio.await(10, TimeUnit.SECONDS))
            // Mute gates injected media too; it never means "only disable the physical mic".
            // A queued chunk must remain untouched until unmute, then complete without a new
            // session or physical recorder. This catches the old provider's mute=true startup.
            assertTrue(transport.setUserInputMuted(true))
            val resumed = CountDownLatch(1)
            val resumedResult = AtomicReference<Result<Unit>?>()
            assertTrue(transport.appendInputAudio(ByteArray(480) { 3 }, 24_000) {
                resumedResult.set(it); resumed.countDown()
            })
            assertFalse("Muted PCM was consumed", resumed.await(100, TimeUnit.MILLISECONDS))
            assertTrue(transport.setUserInputMuted(false))
            assertTrue("Unmuted PCM did not resume", resumed.await(5, TimeUnit.SECONDS))
            assertTrue(resumedResult.get()?.isSuccess == true)
            assertNull("Transport failed", failure.get())
            val audioSection = provider.offer.get().orEmpty().substringAfter("m=audio ")
                .substringBefore("\r\nm=")
            assertTrue(audioSection.contains("a=sendrecv") || audioSection.contains("a=sendonly"))
            assertTrue(audioSection.contains("a=msid:"))
            assertEquals(originalMode, manager.mode)
            assertEquals(originalRoute, manager.communicationDevice?.id)
            assertEquals(originalRecorders, manager.activeRecordingConfigurations.map { it.clientAudioSessionId }.toSet())
        } finally {
            pcm.fill(0)
            transport.close()
            provider.close()
        }
        assertEquals(originalMode, manager.mode)
        assertEquals(originalRoute, manager.communicationDevice?.id)
    }

    @Test fun silentReceiveOnlyOfferNegotiatesDataChannelWithoutTakingAudioOwnership() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(AudioManager::class.java)
        val originalMode = manager.mode
        val originalRoute = manager.communicationDevice?.id
        val originalRecorders = manager.activeRecordingConfigurations.map { it.clientAudioSessionId }.toSet()
        val opened = CountDownLatch(1)
        val message = CountDownLatch(1)
        val failure = AtomicReference<LiveVoiceFailure?>()
        val received = AtomicReference<String?>()
        val provider = LocalAnswerProvider()
        val transport = AndroidWebRtcRealtimeTransport(context, provider,
            mediaMode = LiveVoiceMediaMode.BUFFERED_DICTATION_SILENT)
        try {
            transport.connect(LiveSessionSetup(LiveVoiceSessionConfig(), "Synthetic media test"),
                object : LiveVoiceTransport.Listener {
                    override fun onOpen() { opened.countDown() }
                    override fun onEvent(event: String) { received.set(event); message.countDown() }
                    override fun onClosed(problem: LiveVoiceFailure?) { failure.set(problem); opened.countDown() }
                })
            provider.awaitLocalConnection(opened, failure)
            assertNull("Transport failed", failure.get())
            assertTrue(transport.confirmSessionStarted())
            val audioSection = provider.offer.get().orEmpty().substringAfter("m=audio ")
                .substringBefore("\r\nm=")
            assertTrue(audioSection.contains("a=recvonly"))
            assertFalse(audioSection.contains("a=sendrecv"))
            assertFalse(audioSection.contains("a=sendonly"))
            assertFalse(audioSection.contains("a=msid:"))
            repeat(3) {
                assertTrue(transport.setUserInputMuted(false))
                assertTrue(transport.setInputAudioEnabled(true))
                assertTrue(transport.confirmSessionStarted())
                assertTrue(transport.setUserInputMuted(true))
            }
            assertFalse(transport.setAudioActivityMonitoringEnabled(true))
            assertTrue(transport.sendUtf8("{\"type\":\"local_probe\"}"))
            assertTrue("Local peer did not receive data", provider.received.await(10, TimeUnit.SECONDS))
            assertEquals("{\"type\":\"local_probe\"}", provider.lastMessage.get())
            assertTrue(provider.send("{\"type\":\"local_answer\"}"))
            assertTrue("Transport did not receive data", message.await(10, TimeUnit.SECONDS))
            assertEquals("{\"type\":\"local_answer\"}", received.get())
            assertEquals(originalMode, manager.mode)
            assertEquals(originalRoute, manager.communicationDevice?.id)
            assertEquals(originalRecorders, manager.activeRecordingConfigurations.map { it.clientAudioSessionId }.toSet())
        } finally {
            transport.close()
            provider.close()
        }
        assertEquals(originalMode, manager.mode)
        assertEquals(originalRoute, manager.communicationDevice?.id)
    }

    @Test fun unmuteCannotProveReadinessBeforePeerConnectionAndClosedMediaStaysClosed() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = object : LiveSessionProvider {
            override fun create(setup: LiveSessionSetup, offerSdp: String,
                callback: LiveSessionProvider.Callback): LiveVoiceCancellation = error("Unexpected connection")
        }
        val transport = AndroidWebRtcRealtimeTransport(context, provider,
            mediaMode = LiveVoiceMediaMode.BUFFERED_DICTATION_SILENT)
        try {
            assertTrue(transport.setUserInputMuted(false))
            assertTrue(transport.setInputAudioEnabled(true))
            assertFalse(transport.confirmSessionStarted())
            assertFalse(transport.setAudioActivityMonitoringEnabled(true))
        } finally { transport.close() }
        assertFalse(transport.setUserInputMuted(false))
        assertFalse(transport.confirmSessionStarted())
    }

    private class LocalAnswerProvider(private val collectAudio: Boolean = false) : LiveSessionProvider, AutoCloseable {
        val offer = AtomicReference<String?>()
        val lastMessage = AtomicReference<String?>()
        val received = CountDownLatch(1)
        val nonzeroAudio = CountDownLatch(1)
        private val sinks = CopyOnWriteArrayList<Pair<AudioTrack, AudioTrackSink>>()
        private val answerSent = AtomicBoolean(false)
        private val offerReceived = CountDownLatch(1)
        private val answerResolved = CountDownLatch(1)
        private val sdpFailure = AtomicReference<String?>()
        private val trace = CopyOnWriteArrayList<String>()
        @Volatile private var offerCandidateCount = 0
        @Volatile private var answerCandidateCount = 0
        @Volatile private var gatheredCandidateCount = 0
        @Volatile private var gatheringState = "NEW"
        @Volatile private var iceState = "NEW"
        @Volatile private var peerState = "NEW"
        @Volatile private var dataState = "ABSENT"
        private val closed = AtomicBoolean(false)
        private val channel = AtomicReference<DataChannel?>()
        @Volatile private var peer: PeerConnection? = null
        @Volatile private var factory: PeerConnectionFactory? = null
        @Volatile private var callback: LiveSessionProvider.Callback? = null

        override fun create(setup: LiveSessionSetup, offerSdp: String,
            callback: LiveSessionProvider.Callback): LiveVoiceCancellation {
            offer.set(offerSdp)
            offerCandidateCount = candidateCount(offerSdp)
            record("offer_received")
            offerReceived.countDown()
            this.callback = callback
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            // Initialization has already occurred in the production transport. This answering
            // peer has no media sender/source and explicitly never starts capture or playout.
            record("answer_audio_module_creating")
            val module = JavaAudioDeviceModule.builder(context)
                .setEnableVolumeLogger(false).createAudioDeviceModule().apply {
                    setAudioRecordEnabled(false)
                    setMicrophoneMute(true)
                    setSpeakerMute(true)
                }
            record("answer_factory_creating")
            val createdFactory = try {
                PeerConnectionFactory.builder().setAudioDeviceModule(module).createPeerConnectionFactory()
            } finally { module.release() }
            record("answer_factory_created")
            factory = createdFactory
            val configuration = PeerConnection.RTCConfiguration(emptyList()).apply {
                sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            }
            val createdPeer = checkNotNull(createdFactory.createPeerConnection(configuration, observer()))
            record("answer_peer_created")
            peer = createdPeer
            createdPeer.setAudioRecording(false)
            createdPeer.setAudioPlayout(false)
            record("remote_offer_setting")
            createdPeer.setRemoteDescription(object : Observer("remote_offer") {
                override fun onSetSuccess() {
                    record("remote_offer_set")
                    createdPeer.createAnswer(object : Observer("create_answer") {
                        override fun onCreateSuccess(description: SessionDescription) {
                            record("answer_created")
                            createdPeer.setLocalDescription(object : Observer("local_answer") {
                                override fun onSetSuccess() {
                                    record("local_answer_set")
                                    deliverAnswerIfReady()
                                }
                            }, description)
                        }
                    }, MediaConstraints())
                }
            }, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
            return LiveVoiceCancellation.NONE
        }

        private fun deliverAnswerIfReady() {
            val current = peer ?: return
            if (closed.get() || current.iceGatheringState() != PeerConnection.IceGatheringState.COMPLETE) return
            val answer = current.localDescription?.description ?: return
            if (answerSent.compareAndSet(false, true)) {
                answerCandidateCount = candidateCount(answer)
                record("answer_delivered")
                callback?.onCreated(LiveSessionAnswer("local-test", answer))
                answerResolved.countDown()
            }
        }

        private fun observer() = object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) { record("signaling_$state") }
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                iceState = state.name
                record("ice_$state")
                if (state == PeerConnection.IceConnectionState.FAILED) fail("local_ice_failed")
            }
            override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
                peerState = state.name
                record("peer_$state")
                if (state == PeerConnection.PeerConnectionState.FAILED) fail("local_peer_failed")
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                gatheringState = state.name
                record("gathering_$state")
                if (state == PeerConnection.IceGatheringState.COMPLETE) deliverAnswerIfReady()
            }
            override fun onIceCandidate(candidate: IceCandidate) { gatheredCandidateCount++ }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
            override fun onAddStream(stream: MediaStream) { stream.audioTracks.forEach(::observeRemoteAudio) }
            override fun onRemoveStream(stream: MediaStream) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                (receiver.track() as? AudioTrack)?.let(::observeRemoteAudio)
            }
            override fun onDataChannel(incoming: DataChannel) {
                channel.set(incoming)
                dataState = incoming.state().name
                record("data_channel_$dataState")
                incoming.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(previousAmount: Long) = Unit
                    override fun onStateChange() {
                        dataState = incoming.state().name
                        record("data_channel_$dataState")
                    }
                    override fun onMessage(buffer: DataChannel.Buffer) {
                        val bytes = ByteArray(buffer.data.remaining())
                        buffer.data.slice().get(bytes)
                        lastMessage.set(String(bytes, StandardCharsets.UTF_8))
                        received.countDown()
                    }
                })
            }
        }

        private fun observeRemoteAudio(track: AudioTrack) {
            if (!collectAudio) { track.setEnabled(false); return }
            if (sinks.any { it.first.id() == track.id() }) return
            val sink = AudioTrackSink { buffer, _, _, _, _, _ ->
                val samples = buffer.slice()
                while (samples.hasRemaining()) {
                    if (samples.get() != 0.toByte()) { nonzeroAudio.countDown(); break }
                }
            }
            track.addSink(sink)
            sinks.add(track to sink)
            track.setEnabled(true)
        }

        fun send(text: String): Boolean = channel.get()?.send(DataChannel.Buffer(
            ByteBuffer.wrap(text.toByteArray(StandardCharsets.UTF_8)), false)) == true

        /** One shared 30 s deadline, with earlier assertions for absent SDP progress. */
        fun awaitLocalConnection(opened: CountDownLatch, failure: AtomicReference<LiveVoiceFailure?>) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
            val gotOffer = offerReceived.await(8, TimeUnit.SECONDS)
            assertTrue("Production offer was not delivered; ${diagnostics(failure)}", gotOffer)
            val resolved = answerResolved.await(12, TimeUnit.SECONDS)
            assertTrue("Local SDP/ICE answer was never resolved; ${diagnostics(failure)}", resolved)
            assertNull("Local negotiation failed; ${diagnostics(failure)}", sdpFailure.get())
            assertTrue("Local answer was not delivered; ${diagnostics(failure)}", answerSent.get())
            val connected = opened.await((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            assertTrue("Local data channel did not open; ${diagnostics(failure)}", connected)
            assertNull("Production transport failed; ${diagnostics(failure)}", failure.get())
        }

        // Synthetic phase names and candidate counts only: no SDP, addresses, fingerprints or PCM.
        private fun diagnostics(failure: AtomicReference<LiveVoiceFailure?>): String =
            "offerCandidates=$offerCandidateCount answerCandidates=$answerCandidateCount " +
                "gatheredCandidates=$gatheredCandidateCount answerSent=${answerSent.get()} " +
                "gathering=$gatheringState ice=$iceState peer=$peerState data=$dataState " +
                "localFailure=${sdpFailure.get()} transportFailure=${failure.get()?.code} " +
                "trace=${trace.joinToString(",")}"

        private fun record(phase: String) {
            if (trace.size < 48) trace.add(phase)
        }

        private fun candidateCount(sdp: String): Int = sdp.lineSequence().count { it.startsWith("a=candidate:") }

        private fun fail(phase: String) {
            if (closed.get() || !sdpFailure.compareAndSet(null, phase)) return
            record(phase)
            callback?.onFailure(LiveVoiceFailure("local_peer_sdp_failed", false))
            answerResolved.countDown()
        }

        private open inner class Observer(private val phase: String) : SdpObserver {
            override fun onCreateSuccess(description: SessionDescription) = Unit
            override fun onSetSuccess() = Unit
            override fun onCreateFailure(error: String) { fail("${phase}_create_failed") }
            override fun onSetFailure(error: String) { fail("${phase}_set_failed") }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            sinks.forEach { (track, sink) -> track.removeSink(sink) }
            sinks.clear()
            channel.getAndSet(null)?.let { it.unregisterObserver(); it.close(); it.dispose() }
            peer?.let { it.close(); it.dispose() }
            factory?.dispose()
        }
    }
}
