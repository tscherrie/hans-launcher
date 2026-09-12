package ai.hans.standard.documents

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import ai.hans.standard.artifacts.AtomicArtifactStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentArtifactAndroidSmokeTest {
    @Test
    fun ooxmlAndPdfRoundTripOnAndroidRuntimeWithoutExposingPaths() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val boundary = File(context.cacheDir, "document-smoke-${System.nanoTime()}")
        try {
            assertTrue(boundary.mkdirs())
            val store = AtomicArtifactStore(File(boundary, "artifacts"), boundary)
            val adapter = DocumentArtifactAdapter(store)

            val pdf = adapter.createPdf("android.pdf", "Hans", listOf("Lokal auf Android"))
            val docx = adapter.createDocx("android.docx", "Hans", listOf("Lokal auf Android"))

            assertEquals(DocumentFormat.PDF, adapter.inspect(pdf.artifactHandle).format)
            assertEquals(DocumentFormat.DOCX, adapter.inspect(docx.artifactHandle).format)
            assertTrue(adapter.extractText(pdf.artifactHandle, 10_000).text.contains("Lokal auf Android"))
            assertTrue(adapter.extractText(docx.artifactHandle, 10_000).text.contains("Lokal auf Android"))
            assertTrue(pdf.artifactHandle.value.matches(Regex("art_[0-9a-f]{64}")))
            assertTrue(docx.artifactHandle.value.matches(Regex("art_[0-9a-f]{64}")))
        } finally {
            boundary.deleteRecursively()
        }
    }
}
