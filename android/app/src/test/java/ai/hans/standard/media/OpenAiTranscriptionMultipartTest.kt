package ai.hans.standard.media

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiTranscriptionMultipartTest {
    @Test
    fun multipartStreamsExactMediaBytesWithoutEmbeddingAPath() {
        val source = File.createTempFile("hans-media-source", ".m4a")
        val media = byteArrayOf(0, 1, 2, 3, -1, 10, 13)
        source.writeBytes(media)
        try {
            val output = ByteArrayOutputStream()
            OpenAiTranscriptionMultipart.write(
                output = output,
                boundary = "hans-test-boundary",
                model = "gpt-4o-transcribe",
                source = source,
                sourceContainsOnlyAudio = true,
            )
            val body = output.toByteArray()
            val text = body.toString(Charsets.ISO_8859_1)

            assertTrue(text.contains("name=\"model\""))
            assertTrue(text.contains("gpt-4o-transcribe"))
            assertTrue(text.contains("filename=\"media.m4a\""))
            assertTrue(text.contains("Content-Type: audio/mp4"))
            assertFalse(text.contains(source.absolutePath))
            val start = text.indexOf("Content-Type: audio/mp4\r\n\r\n") +
                "Content-Type: audio/mp4\r\n\r\n".length
            assertArrayEquals(media, body.copyOfRange(start, start + media.size))
        } finally {
            source.delete()
        }
    }
}
