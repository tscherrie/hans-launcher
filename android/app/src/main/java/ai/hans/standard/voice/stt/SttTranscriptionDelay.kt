package ai.hans.standard.voice.stt

/** Supported dictation choices, snapshotted once for each new transcription session. */
enum class SttTranscriptionDelay(val wireValue: String) {
    LOW("low"),
    MINIMAL("minimal"),
    ;

    companion object {
        fun fromWireValue(value: String?): SttTranscriptionDelay? =
            entries.firstOrNull { it.wireValue == value }
    }
}
