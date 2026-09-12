package ai.hans.standard.voice.tts

/** A real coordinator barrier whose active physical playback refuses to confirm stop. */
internal fun throwingPhysicalPlayerCaptureBarrier(): () -> Boolean {
    val provider = RecordingProvider()
    val coordinator = StreamingTtsCoordinator(
        settingsSource = TtsSettingsSource { TtsPlaybackSettings() },
        provider = provider,
        player = ThrowingStopPlayer,
        audioFocus = object : TtsAudioFocusCoordinator {
            override fun request(
                onChange: (TtsAudioFocusChange) -> Unit,
            ): TtsAudioFocusRequestResult = TtsAudioFocusRequestResult.GRANTED

            override fun abandon() = Unit
        },
        dispatcher = ImmediateDispatcher,
        listener = object : TtsPlaybackListener {
            override fun onStateChanged(state: TtsPlaybackState) = Unit
            override fun onEvent(event: TtsPlaybackEvent) = Unit
        },
    )
    coordinator.submit(
        TtsMessageRevision(
            messageId = TtsMessageId("throwing-physical-stop-fixture"),
            revision = 1,
            text = "Aktive Audioausgabe.",
            kind = TtsMessageKind.FINAL_OUTPUT,
            isFinal = true,
        ),
    )
    checkNotNull(provider.listener).apply {
        onStreamReady(TtsAudioStreamFormat("audio/pcm"))
        onAudioChunk(TtsAudioChunk.create(0, byteArrayOf(0, 0)))
    }
    return { coordinator.stopAndAwait(1_000L) }
}

private class RecordingProvider : StreamingTtsProvider {
    var listener: StreamingTtsProvider.Listener? = null

    override fun start(
        request: TtsSynthesisRequest,
        listener: StreamingTtsProvider.Listener,
    ): StreamingTtsSynthesis {
        this.listener = listener
        return object : StreamingTtsSynthesis {
            override fun pause() = Unit
            override fun resume() = Unit
            override fun cancel() = Unit
        }
    }
}

private object ThrowingStopPlayer : StreamingTtsPlayer {
    override fun open(
        segmentId: TtsSegmentId,
        format: TtsAudioStreamFormat,
        listener: StreamingTtsPlayer.Listener,
    ): StreamingTtsPlayback = object : StreamingTtsPlayback {
        override fun write(chunk: TtsAudioChunk) = Unit
        override fun finishInput() = Unit
        override fun pause() = Unit
        override fun resume() = Unit
        override fun stop(): Unit = error("physical stop failed")
    }
}

private object ImmediateDispatcher : TtsTaskDispatcher {
    override fun dispatch(task: () -> Unit) = task()

    override fun dispatchControlAndAwait(
        timeoutMillis: Long,
        task: () -> Unit,
    ): Boolean = timeoutMillis > 0L && runCatching(task).isSuccess
}
