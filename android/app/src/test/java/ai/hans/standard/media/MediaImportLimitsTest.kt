package ai.hans.standard.media

import org.junit.Assert.assertThrows
import org.junit.Test

class MediaImportLimitsTest {
    @Test
    fun rejectsUnboundedOrNonsensicalConfiguration() {
        assertThrows(IllegalArgumentException::class.java) {
            MediaImportLimits(maxVideoInputBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaImportLimits(maxVideoDurationMillis = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaImportLimits(representativeFrameCount = 100)
        }
        assertThrows(IllegalArgumentException::class.java) {
            MediaImportLimits(maxDecodedImageEdge = 100_000)
        }
    }

    @Test
    fun defaultsRemainExplicitlyBounded() {
        val limits = MediaImportLimits()
        check(limits.maxImageInputBytes == 20L * 1_024L * 1_024L)
        check(limits.maxVideoInputBytes == 128L * 1_024L * 1_024L)
        check(limits.maxVideoDurationMillis == 15L * 60L * 1_000L)
        check(limits.representativeFrameCount == 5)
    }
}
