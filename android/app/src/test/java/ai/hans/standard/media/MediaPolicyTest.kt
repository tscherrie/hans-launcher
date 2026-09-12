package ai.hans.standard.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class MediaPolicyTest {
    @Test
    fun declaredMimeTypeMustBeImageOrVideo() {
        assertEquals(MediaKind.IMAGE, MediaPolicy.kindForDeclaredMimeType(" IMAGE/JPEG "))
        assertEquals(MediaKind.VIDEO, MediaPolicy.kindForDeclaredMimeType("video/mp4; codec=avc1"))
        val failure = assertThrows(MediaImportException::class.java) {
            MediaPolicy.kindForDeclaredMimeType("application/pdf")
        }
        assertEquals(MediaImportFailureCode.UNSUPPORTED_DECLARED_TYPE, failure.code)
    }

    @Test
    fun providerSizeIsOnlyAPreflightButCannotExceedPolicy() {
        MediaPolicy.requireReportedSizeWithinLimit(null, 100)
        MediaPolicy.requireReportedSizeWithinLimit(-1, 100)
        MediaPolicy.requireReportedSizeWithinLimit(100, 100)
        val failure = assertThrows(MediaImportException::class.java) {
            MediaPolicy.requireReportedSizeWithinLimit(101, 100)
        }
        assertEquals(MediaImportFailureCode.DECLARED_SIZE_EXCEEDED, failure.code)
    }

    @Test
    fun boundedOutputRejectsWholeWriteBeforeLeakingPastLimit() {
        val target = ByteArrayOutputStream()
        val output = BoundedOutputStream(target, 4)
        output.write(byteArrayOf(1, 2, 3), 0, 3)
        val failure = assertThrows(MediaImportException::class.java) {
            output.write(byteArrayOf(4, 5), 0, 2)
        }
        assertEquals(MediaImportFailureCode.READ_LIMIT_EXCEEDED, failure.code)
        assertEquals(listOf<Byte>(1, 2, 3), target.toByteArray().toList())
        assertEquals(3L, output.byteCount)
    }

    @Test
    fun representativeFramesAreBoundedDistinctAndAvoidExactEnd() {
        assertEquals(listOf(0L), MediaPolicy.representativeFrameTimes(0, 5))
        assertEquals(listOf(499L), MediaPolicy.representativeFrameTimes(1_000, 1))

        val times = MediaPolicy.representativeFrameTimes(10_000, 5)
        assertEquals(5, times.size)
        assertEquals(times.sorted(), times)
        assertEquals(times.distinct(), times)
        assertTrue(times.first() >= 0)
        assertTrue(times.last() < 10_000)
    }

    @Test
    fun tinyVideosDoNotCreateDuplicateFrameRequests() {
        val times = MediaPolicy.representativeFrameTimes(2, 5)
        assertEquals(times.distinct(), times)
        assertTrue(times.all { it in 0 until 2 })
    }

    @Test
    fun labelsDropControlCharactersAndAreBounded() {
        val label = MediaPolicy.safeDisplayLabel(
            "  holiday\u0000\n\tvideo  " + "x".repeat(100),
            fallback = "Video",
            maxLength = 20,
        )
        assertFalse(label.any(Char::isISOControl))
        assertTrue(label.length <= 20)
        assertTrue(label.startsWith("holiday video"))
        assertEquals("Foto", MediaPolicy.safeDisplayLabel("\u0000", "Foto"))
    }

    @Test
    fun pixelAndDurationLimitsUseOverflowSafeLongMath() {
        val pixels = assertThrows(MediaImportException::class.java) {
            MediaPolicy.requirePixelCount(Int.MAX_VALUE, Int.MAX_VALUE, 10)
        }
        assertEquals(MediaImportFailureCode.PIXEL_LIMIT_EXCEEDED, pixels.code)

        val duration = assertThrows(MediaImportException::class.java) {
            MediaPolicy.requireVideoDuration(1_001, 1_000)
        }
        assertEquals(MediaImportFailureCode.DURATION_LIMIT_EXCEEDED, duration.code)
    }

    @Test
    fun noAudioHasNoTranscriptionRequest() {
        val video = videoResult(VideoAudioHandoff.NoAudio)
        assertNull(video.toTranscriptionRequest())
    }

    @Test
    fun extractedAudioAndSourceTrackBothProvideExplicitSttInput() {
        val track = audioTrack()
        val audio = ImportedMediaFile("/private/audio.m4a", "audio/mp4", 10)
        val extracted = videoResult(VideoAudioHandoff.ExtractedAudio(audio, track))
            .toTranscriptionRequest()
        assertEquals(audio, extracted?.source)
        assertTrue(extracted?.sourceContainsOnlyAudio == true)

        val source = ImportedMediaFile("/private/source.mp4", "video/mp4", 20)
        val fallback = videoResult(
            VideoAudioHandoff.SourceTrack(source, track, "codec_requires_decoder"),
        ).toTranscriptionRequest()
        assertEquals(source, fallback?.source)
        assertFalse(fallback?.sourceContainsOnlyAudio ?: true)
        assertEquals(1, fallback?.track?.trackIndex)
    }

    private fun videoResult(audio: VideoAudioHandoff) = MediaImportResult.Video(
        importId = "id",
        displayLabel = "Video",
        sourceVideo = ImportedMediaFile("/private/source.mp4", "video/mp4", 20),
        metadata = VideoMetadata(100, 100, 0, 1_000, "video/mp4", 20, 30f),
        representativeFrames = listOf(
            VideoFrame(0, ImportedMediaFile("/private/frame.jpg", "image/jpeg", 5)),
        ),
        audioHandoff = audio,
        manifestPath = "/private/manifest.json",
    )

    private fun audioTrack() = AudioTrackMetadata(
        trackIndex = 1,
        mimeType = "audio/mp4a-latm",
        durationMillis = 1_000,
        sampleRateHz = 48_000,
        channelCount = 2,
    )
}
