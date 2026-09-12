package ai.hans.standard.voice

/**
 * Converts arbitrary AudioRecord read sizes into fixed-size PCM chunks while
 * retaining at most one chunk of mutable audio in memory.
 */
class FixedPcmChunker(private val fixedChunkBytes: Int) {
    private val pending = ByteArray(fixedChunkBytes)
    private var pendingBytes = 0
    private var finished = false

    init {
        require(fixedChunkBytes > 0)
    }

    @Synchronized
    fun append(
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): List<ByteArray> {
        check(!finished) { "PCM chunker is already finished" }
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        if (length == 0) return emptyList()

        val completed = ArrayList<ByteArray>((pendingBytes + length) / fixedChunkBytes)
        var sourceOffset = offset
        var remaining = length
        while (remaining > 0) {
            val copyCount = minOf(remaining, fixedChunkBytes - pendingBytes)
            bytes.copyInto(
                destination = pending,
                destinationOffset = pendingBytes,
                startIndex = sourceOffset,
                endIndex = sourceOffset + copyCount,
            )
            pendingBytes += copyCount
            sourceOffset += copyCount
            remaining -= copyCount
            if (pendingBytes == fixedChunkBytes) {
                completed += pending.copyOf()
                pendingBytes = 0
            }
        }
        return completed
    }

    /** Returns the sole short final chunk, or an empty final marker. */
    @Synchronized
    fun finish(): ByteArray {
        check(!finished) { "PCM chunker is already finished" }
        finished = true
        return pending.copyOf(pendingBytes)
    }

    @Synchronized
    fun bufferedBytes(): Int = pendingBytes
}
