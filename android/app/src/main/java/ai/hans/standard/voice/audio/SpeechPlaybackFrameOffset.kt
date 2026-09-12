package ai.hans.standard.voice.audio

/** AudioTrack's playback head and marker positions are unsigned 32-bit frame counters. */
class SpeechPlaybackFrameOffset {
    private var silentFrames = 0L
    private var lastHead = 0L
    private var wraps = 0L
    fun addSilence(frames: Int) {
        require(frames >= 0)
        silentFrames += frames
        check(silentFrames <= MASK)
    }
    fun speechFrames(rawUnsignedFrames: Long): Long {
        require(rawUnsignedFrames in 0..MASK)
        if (lastHead - rawUnsignedFrames > (MASK / 2)) wraps += MASK + 1
        lastHead = rawUnsignedFrames
        return (wraps + rawUnsignedFrames - silentFrames).coerceAtLeast(0)
    }
    fun marker(speechFrame: Int): Int? {
        if (speechFrame <= 0) return null
        val target = (speechFrame.toLong() + silentFrames) and MASK
        return if (target == 0L) null else target.toInt()
    }
    companion object { private const val MASK = 0xffff_ffffL }
}
