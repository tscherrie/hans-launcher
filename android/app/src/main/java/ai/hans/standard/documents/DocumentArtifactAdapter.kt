package ai.hans.standard.documents

import ai.hans.standard.artifacts.ArtifactHandle
import ai.hans.standard.artifacts.ArtifactMetadata
import ai.hans.standard.artifacts.ArtifactOrigin
import ai.hans.standard.artifacts.AtomicArtifactStore
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.util.Locale

/**
 * Document boundary over [AtomicArtifactStore]. No filesystem path, SAF URI, or raw binary payload
 * crosses this API; callers receive only verified metadata and opaque artifact handles.
 */
class DocumentArtifactAdapter(
    private val artifactStore: AtomicArtifactStore,
    private val limits: DocumentLimits = DocumentLimits(),
) {
    fun inspect(
        handle: ArtifactHandle,
        cancellation: DocumentCancellation = DocumentCancellation.NONE,
    ): DocumentInspection {
        val (metadata, decoded) = decodeArtifact(handle, cancellation)
        return DocumentInspection(
            artifactHandle = handle,
            format = decoded.format,
            byteCount = metadata.byteCount,
            sha256 = metadata.sha256,
            unitKind = decoded.unitKind,
            unitCount = decoded.unitCount,
            textCharacterCount = decoded.text.length,
            limitations = decoded.limitations,
        )
    }

    fun extractText(
        handle: ArtifactHandle,
        maxCharacters: Int,
        cancellation: DocumentCancellation = DocumentCancellation.NONE,
    ): DocumentTextExtraction {
        if (maxCharacters !in 1..limits.maxExtractedTextCharacters) {
            documentFailure("document_text_limit_invalid")
        }
        val (_, decoded) = decodeArtifact(handle, cancellation)
        val truncated = decoded.text.length > maxCharacters
        val text = if (truncated) decoded.text.take(maxCharacters) else decoded.text
        return DocumentTextExtraction(handle, decoded.format, text, truncated, decoded.limitations)
    }

    fun createPdf(
        displayName: String,
        title: String,
        paragraphs: List<String>,
        cancellation: DocumentCancellation = DocumentCancellation.NONE,
    ): CreatedDocument {
        val (bytes, units) = DocumentCodecs.createPdf(title, paragraphs, limits, cancellation)
        return commitGenerated(displayName, DocumentFormat.PDF, bytes, units, cancellation)
    }

    fun createDocx(
        displayName: String,
        title: String,
        paragraphs: List<String>,
        cancellation: DocumentCancellation = DocumentCancellation.NONE,
    ): CreatedDocument {
        val (bytes, units) = DocumentCodecs.createDocx(title, paragraphs, limits, cancellation)
        return commitGenerated(displayName, DocumentFormat.DOCX, bytes, units, cancellation)
    }

    fun createXlsx(
        displayName: String,
        sheetName: String,
        rows: List<List<String>>,
        cancellation: DocumentCancellation = DocumentCancellation.NONE,
    ): CreatedDocument {
        val (bytes, units) = DocumentCodecs.createXlsx(sheetName, rows, limits, cancellation)
        return commitGenerated(displayName, DocumentFormat.XLSX, bytes, units, cancellation)
    }

    fun createPptx(
        displayName: String,
        slides: List<PresentationSlide>,
        cancellation: DocumentCancellation = DocumentCancellation.NONE,
    ): CreatedDocument {
        val (bytes, units) = DocumentCodecs.createPptx(slides, limits, cancellation)
        return commitGenerated(displayName, DocumentFormat.PPTX, bytes, units, cancellation)
    }

    private fun decodeArtifact(
        handle: ArtifactHandle,
        cancellation: DocumentCancellation,
    ): Pair<ArtifactMetadata, DecodedDocument> {
        cancellation.checkDocumentCancellation()
        val metadata = runCatching { artifactStore.metadata(handle) }
            .getOrElse { documentFailure("document_artifact_unavailable") }
        if (metadata.byteCount > limits.maxInputBytes) documentFailure("document_input_size_limit")
        val bytes = artifactStore.open(handle).use { input ->
            input.readBounded(metadata.byteCount, limits.maxInputBytes, cancellation)
        }
        cancellation.checkDocumentCancellation()
        return metadata to DocumentCodecs.decode(bytes, metadata.mimeType, limits, cancellation)
    }

    private fun commitGenerated(
        rawDisplayName: String,
        format: DocumentFormat,
        bytes: ByteArray,
        units: Int,
        cancellation: DocumentCancellation,
    ): CreatedDocument {
        val displayName = validateDisplayName(rawDisplayName, format)
        cancellation.checkDocumentCancellation()
        // Fail closed on a bug in our own writer before the result becomes durable.
        val proof = DocumentCodecs.decode(bytes, format.mimeType, limits, cancellation)
        if (proof.format != format || proof.unitCount != units) documentFailure("document_generated_proof_failed")
        cancellation.checkDocumentCancellation()
        val metadata = artifactStore.put(
            displayName = displayName,
            mimeType = format.mimeType,
            origin = ArtifactOrigin.CODEX,
            source = CancellationCheckingInputStream(ByteArrayInputStream(bytes), cancellation),
            maxBytes = limits.maxGeneratedBytes.toLong(),
        )
        return CreatedDocument(
            metadata.handle,
            format,
            metadata.displayName,
            metadata.byteCount,
            metadata.sha256,
            units,
        )
    }

    private fun validateDisplayName(raw: String, format: DocumentFormat): String {
        if (
            raw.isBlank() ||
            raw.length > 255 ||
            raw.any { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() } ||
            !raw.lowercase(Locale.ROOT).endsWith(".${format.fileExtension}")
        ) {
            documentFailure("document_display_name_invalid")
        }
        return raw
    }

    private fun InputStream.readBounded(
        expectedBytes: Long,
        maxBytes: Long,
        cancellation: DocumentCancellation,
    ): ByteArray {
        if (expectedBytes !in 0..maxBytes || expectedBytes > Int.MAX_VALUE) {
            documentFailure("document_input_size_limit")
        }
        val output = ByteArrayOutputStream(minOf(expectedBytes.toInt(), 8 * 1024))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            cancellation.checkDocumentCancellation()
            val count = read(buffer)
            if (count < 0) break
            if (count == 0) continue
            total = Math.addExact(total, count.toLong())
            if (total > maxBytes) documentFailure("document_input_size_limit")
            output.write(buffer, 0, count)
        }
        if (total != expectedBytes) documentFailure("document_artifact_size_mismatch")
        return output.toByteArray()
    }

    private class CancellationCheckingInputStream(
        delegate: InputStream,
        private val cancellation: DocumentCancellation,
    ) : FilterInputStream(delegate) {
        override fun read(): Int {
            cancellation.checkDocumentCancellation()
            return super.read()
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            cancellation.checkDocumentCancellation()
            return super.read(buffer, offset, length)
        }
    }
}
