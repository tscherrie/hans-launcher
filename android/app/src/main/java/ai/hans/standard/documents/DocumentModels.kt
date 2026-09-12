package ai.hans.standard.documents

import ai.hans.standard.artifacts.ArtifactHandle

/** Formats handled by Hans' deliberately narrow, local document adapter. */
enum class DocumentFormat(
    val mimeType: String,
    val fileExtension: String,
) {
    PDF("application/pdf", "pdf"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx"),
    XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx"),
    PPTX("application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx"),
}

data class DocumentLimits(
    val maxInputBytes: Long = 32L * 1024L * 1024L,
    val maxGeneratedBytes: Int = 16 * 1024 * 1024,
    val maxZipEntries: Int = 512,
    val maxZipEntryBytes: Int = 16 * 1024 * 1024,
    val maxZipExpandedBytes: Long = 64L * 1024L * 1024L,
    val maxCompressionRatio: Int = 100,
    val compressionRatioGraceBytes: Long = 1024L * 1024L,
    val maxXmlBytes: Int = 8 * 1024 * 1024,
    val maxXmlDepth: Int = 128,
    val maxExtractedTextCharacters: Int = 1_000_000,
    val maxPdfObjects: Int = 20_000,
    val maxDocumentUnits: Int = 2_000,
    val maxTableCells: Int = 100_000,
) {
    init {
        require(maxInputBytes in 1..ABSOLUTE_MAX_INPUT_BYTES)
        require(maxGeneratedBytes in 1..ABSOLUTE_MAX_GENERATED_BYTES)
        require(maxZipEntries in 1..ABSOLUTE_MAX_ZIP_ENTRIES)
        require(maxZipEntryBytes in 1..ABSOLUTE_MAX_ZIP_ENTRY_BYTES)
        require(maxZipExpandedBytes in maxZipEntryBytes.toLong()..ABSOLUTE_MAX_ZIP_EXPANDED_BYTES)
        require(maxCompressionRatio in 1..ABSOLUTE_MAX_COMPRESSION_RATIO)
        require(compressionRatioGraceBytes in 0..ABSOLUTE_MAX_COMPRESSION_GRACE_BYTES)
        require(maxXmlBytes in 1..maxZipEntryBytes)
        require(maxXmlDepth in 1..ABSOLUTE_MAX_XML_DEPTH)
        require(maxExtractedTextCharacters in 1..ABSOLUTE_MAX_EXTRACTED_TEXT_CHARACTERS)
        require(maxPdfObjects in 1..ABSOLUTE_MAX_PDF_OBJECTS)
        require(maxDocumentUnits in 1..ABSOLUTE_MAX_DOCUMENT_UNITS)
        require(maxTableCells in 1..ABSOLUTE_MAX_TABLE_CELLS)
    }

    private companion object {
        const val ABSOLUTE_MAX_INPUT_BYTES = 128L * 1024L * 1024L
        const val ABSOLUTE_MAX_GENERATED_BYTES = 64 * 1024 * 1024
        const val ABSOLUTE_MAX_ZIP_ENTRIES = 4_096
        const val ABSOLUTE_MAX_ZIP_ENTRY_BYTES = 64 * 1024 * 1024
        const val ABSOLUTE_MAX_ZIP_EXPANDED_BYTES = 256L * 1024L * 1024L
        const val ABSOLUTE_MAX_COMPRESSION_RATIO = 1_000
        const val ABSOLUTE_MAX_COMPRESSION_GRACE_BYTES = 8L * 1024L * 1024L
        const val ABSOLUTE_MAX_XML_DEPTH = 512
        const val ABSOLUTE_MAX_EXTRACTED_TEXT_CHARACTERS = 4_000_000
        const val ABSOLUTE_MAX_PDF_OBJECTS = 100_000
        const val ABSOLUTE_MAX_DOCUMENT_UNITS = 10_000
        const val ABSOLUTE_MAX_TABLE_CELLS = 1_000_000
    }
}

fun interface DocumentCancellation {
    fun isCancellationRequested(): Boolean

    companion object {
        val NONE = DocumentCancellation { false }
    }
}

data class DocumentInspection(
    val artifactHandle: ArtifactHandle,
    val format: DocumentFormat,
    val byteCount: Long,
    val sha256: String,
    val unitKind: String,
    val unitCount: Int,
    val textCharacterCount: Int,
    val limitations: List<String>,
)

data class DocumentTextExtraction(
    val artifactHandle: ArtifactHandle,
    val format: DocumentFormat,
    val text: String,
    val truncated: Boolean,
    val limitations: List<String>,
)

data class CreatedDocument(
    val artifactHandle: ArtifactHandle,
    val format: DocumentFormat,
    val displayName: String,
    val byteCount: Long,
    val sha256: String,
    val unitCount: Int,
)

data class PresentationSlide(
    val title: String,
    val body: List<String>,
) {
    init {
        require(title.length <= MAX_TITLE_CHARACTERS && '\u0000' !in title)
        require(body.size <= MAX_BODY_LINES)
        require(body.all { it.length <= MAX_BODY_LINE_CHARACTERS && '\u0000' !in it })
    }

    private companion object {
        const val MAX_TITLE_CHARACTERS = 2_000
        const val MAX_BODY_LINES = 500
        const val MAX_BODY_LINE_CHARACTERS = 8_000
    }
}

internal class DocumentContractException(val code: String) : IllegalArgumentException(code)

internal fun documentFailure(code: String): Nothing = throw DocumentContractException(code)

internal fun DocumentCancellation.checkDocumentCancellation() {
    if (runCatching(::isCancellationRequested).getOrDefault(true)) {
        documentFailure("document_operation_cancelled")
    }
}
