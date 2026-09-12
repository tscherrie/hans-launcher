package ai.hans.standard.documents

import ai.hans.standard.artifacts.ArtifactHandle
import ai.hans.standard.artifacts.ArtifactOrigin
import ai.hans.standard.artifacts.AtomicArtifactStore
import ai.hans.standard.codex.DynamicToolCallParams
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executor
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DocumentArtifactAdapterTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun pdfDocxXlsxAndPptxRoundTripThroughOpaqueArtifacts() {
        val fixture = fixture()

        val pdf = fixture.adapter.createPdf(
            "brief.pdf",
            "Überblick",
            listOf("Hans arbeitet lokal.", "Zweite Zeile."),
        )
        val docx = fixture.adapter.createDocx(
            "brief.docx",
            "Überblick",
            listOf("Hans arbeitet lokal.", "Zweite Zeile."),
        )
        val xlsx = fixture.adapter.createXlsx(
            "zahlen.xlsx",
            "Daten",
            listOf(listOf("Name", "Wert"), listOf("Hans", "42")),
        )
        val pptx = fixture.adapter.createPptx(
            "ideen.pptx",
            listOf(
                PresentationSlide("Start", listOf("Erster Punkt", "Zweiter Punkt")),
                PresentationSlide("Ende", listOf("Fertig")),
            ),
        )

        assertEquals(DocumentFormat.PDF, fixture.adapter.inspect(pdf.artifactHandle).format)
        assertTrue(fixture.adapter.extractText(pdf.artifactHandle, 10_000).text.contains("Überblick"))
        assertTrue(fixture.adapter.extractText(pdf.artifactHandle, 10_000).text.contains("Hans arbeitet lokal."))
        assertEquals("paragraphs", fixture.adapter.inspect(docx.artifactHandle).unitKind)
        assertTrue(fixture.adapter.extractText(docx.artifactHandle, 10_000).text.contains("Zweite Zeile."))
        assertEquals("sheets", fixture.adapter.inspect(xlsx.artifactHandle).unitKind)
        assertTrue(fixture.adapter.extractText(xlsx.artifactHandle, 10_000).text.contains("Hans\t42"))
        assertEquals(2, fixture.adapter.inspect(pptx.artifactHandle).unitCount)
        assertTrue(fixture.adapter.extractText(pptx.artifactHandle, 10_000).text.contains("Zweiter Punkt"))

        listOf(pdf, docx, xlsx, pptx).forEach { created ->
            assertTrue(created.artifactHandle.value.startsWith("art_"))
            assertFalse(created.artifactHandle.value.contains('/'))
            assertEquals(created.sha256, fixture.store.metadata(created.artifactHandle).sha256)
        }
    }

    @Test
    fun generatedPayloadsAreDeterministicEvenThoughArtifactHandlesAreOpaqueAndUnique() {
        val fixture = fixture()

        val first = fixture.adapter.createDocx("same.docx", "Titel", listOf("Text"))
        val second = fixture.adapter.createDocx("same.docx", "Titel", listOf("Text"))

        assertNotEquals(first.artifactHandle, second.artifactHandle)
        assertEquals(first.sha256, second.sha256)
        assertTrue(
            fixture.store.open(first.artifactHandle).use { it.readBytes() }
                .contentEquals(fixture.store.open(second.artifactHandle).use { it.readBytes() }),
        )
    }

    @Test
    fun dynamicToolsReturnHandlesAndSafeMetadataWithoutAnyFilesystemPath() {
        val fixture = fixture()
        val executor = DocumentDynamicToolExecutor(fixture.adapter, Executor(Runnable::run))

        val created = execute(
            executor,
            "create_docx",
            JSONObject()
                .put("displayName", "bericht.docx")
                .put("title", "Bericht")
                .put("paragraphs", JSONArray(listOf("Inhalt"))),
        )
        val createdJson = JSONObject(created.contentText)
        val handle = createdJson.getString("artifactHandle")
        assertTrue(created.success)
        assertFalse(created.contentText.contains(temporary.root.absolutePath))
        assertFalse(created.contentText.contains("file:"))
        assertFalse(created.contentText.contains("content:"))

        val extracted = execute(
            executor,
            "extract_document_text",
            JSONObject().put("artifactHandle", handle),
        )
        assertTrue(extracted.success)
        assertTrue(JSONObject(extracted.contentText).getString("text").contains("Inhalt"))
        assertFalse(extracted.contentText.contains(temporary.root.absolutePath))
    }

    @Test
    fun traversalDuplicateAndCaseFoldedZipPathsAreRejectedBeforePartsAreUsed() {
        val fixture = fixture()
        val traversal = rawZip(listOf("../word/document.xml" to "bad".toByteArray()))
        assertDocumentFailure("document_zip_path_invalid") {
            fixture.adapter.inspect(fixture.put("bad.docx", DocumentFormat.DOCX.mimeType, traversal))
        }

        val duplicate = rawZip(
            listOf(
                "word/a.xml" to "one".toByteArray(),
                "word/b.xml" to "two".toByteArray(),
            ),
        ).also { replaceAsciiOccurrences(it, "word/b.xml", "word/a.xml") }
        assertDocumentFailure("document_zip_duplicate_entry") {
            fixture.adapter.inspect(fixture.put("duplicate.docx", DocumentFormat.DOCX.mimeType, duplicate))
        }

        val folded = rawZip(
            listOf(
                "word/document.xml" to "one".toByteArray(),
                "WORD/document.xml" to "two".toByteArray(),
            ),
        )
        assertDocumentFailure("document_zip_duplicate_entry") {
            fixture.adapter.inspect(fixture.put("folded.docx", DocumentFormat.DOCX.mimeType, folded))
        }
    }

    @Test
    fun unixSymlinkAndCompressionBombEntriesAreRejected() {
        val fixture = fixture(
            limits = DocumentLimits(
                maxCompressionRatio = 2,
                compressionRatioGraceBytes = 1_024,
            ),
        )
        val ordinary = rawZip(listOf("word/document.xml" to "target".toByteArray()))
        val symlink = ordinary.copyOf().also(::markFirstCentralEntryAsUnixSymlink)
        assertDocumentFailure("document_zip_symlink_entry") {
            fixture.adapter.inspect(fixture.put("symlink.docx", DocumentFormat.DOCX.mimeType, symlink))
        }

        val bomb = rawZip(listOf("word/document.xml" to ByteArray(2 * 1024 * 1024) { 'A'.code.toByte() }))
        assertDocumentFailure("document_zip_compression_ratio_limit") {
            fixture.adapter.inspect(fixture.put("bomb.docx", DocumentFormat.DOCX.mimeType, bomb))
        }
    }

    @Test
    fun xxeExternalRelationshipsMacrosAndActivePdfContentAreRejected() {
        val fixture = fixture()
        val generated = fixture.adapter.createDocx("safe.docx", "Titel", listOf("Text"))
        val safeBytes = fixture.store.open(generated.artifactHandle).use { it.readBytes() }
        val safePackage = SafeDocumentZip.read(safeBytes, DocumentLimits(), DocumentCancellation.NONE)

        val xxeParts = safePackage.entries.toMutableMap().apply {
            this["word/document.xml"] = (
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<!DOCTYPE w:document [<!ENTITY xxe SYSTEM \"file:///data/data/secret\">]>" +
                    "<w:document xmlns:w=\"urn:test\"><w:body><w:p><w:r><w:t>&xxe;</w:t></w:r></w:p></w:body></w:document>"
                ).toByteArray()
        }
        assertDocumentFailure("document_xml_declaration_forbidden") {
            fixture.adapter.inspect(
                fixture.put(
                    "xxe.docx",
                    DocumentFormat.DOCX.mimeType,
                    SafeDocumentZip.write(xxeParts, DocumentLimits(), DocumentCancellation.NONE),
                ),
            )
        }

        val externalParts = safePackage.entries.toMutableMap().apply {
            this["word/_rels/document.xml.rels"] = (
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
                    "<Relationship Id=\"rId9\" Type=\"urn:test\" Target=\"https://example.com/leak\" TargetMode=\"External\"/>" +
                    "</Relationships>"
                ).toByteArray()
        }
        assertDocumentFailure("document_external_relationship_unsupported") {
            fixture.adapter.inspect(
                fixture.put(
                    "external.docx",
                    DocumentFormat.DOCX.mimeType,
                    SafeDocumentZip.write(externalParts, DocumentLimits(), DocumentCancellation.NONE),
                ),
            )
        }

        val macroParts = safePackage.entries.toMutableMap().apply {
            this["word/vbaProject.bin"] = byteArrayOf(1, 2, 3)
        }
        assertDocumentFailure("document_active_content_unsupported") {
            fixture.adapter.inspect(
                fixture.put(
                    "macro.docx",
                    DocumentFormat.DOCX.mimeType,
                    SafeDocumentZip.write(macroParts, DocumentLimits(), DocumentCancellation.NONE),
                ),
            )
        }

        val activePdf = (
            "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /OpenAction 2 0 R >>\nendobj\n" +
                "2 0 obj\n<< /S /Java#53cript /JS (bad) >>\nendobj\n%%EOF\n"
            ).toByteArray(StandardCharsets.ISO_8859_1)
        assertDocumentFailure("document_pdf_active_content_unsupported") {
            fixture.adapter.inspect(fixture.put("active.pdf", DocumentFormat.PDF.mimeType, activePdf))
        }
    }

    @Test
    fun cancellationLeavesNoGeneratedArtifactAndStopsInspection() {
        val fixture = fixture()
        val before = fixture.store.listMetadata().size
        assertDocumentFailure("document_operation_cancelled") {
            fixture.adapter.createPptx(
                "cancelled.pptx",
                listOf(PresentationSlide("Titel", listOf("Text"))),
                DocumentCancellation { true },
            )
        }
        assertEquals(before, fixture.store.listMetadata().size)

        val document = fixture.adapter.createDocx("inspect.docx", "Titel", List(20) { "Text $it" })
        var checks = 0
        assertDocumentFailure("document_operation_cancelled") {
            fixture.adapter.inspect(
                document.artifactHandle,
                DocumentCancellation { ++checks > 4 },
            )
        }
    }

    @Test
    fun formatMimeMismatchAndComplexPdfEncryptionFailClosed() {
        val fixture = fixture()
        val docx = fixture.adapter.createDocx("safe.docx", "Titel", listOf("Text"))
        val bytes = fixture.store.open(docx.artifactHandle).use { it.readBytes() }
        val wrongMime = fixture.put("wrong.pdf", DocumentFormat.PDF.mimeType, bytes)
        assertDocumentFailure("document_mime_mismatch") { fixture.adapter.inspect(wrongMime) }

        val encrypted = (
            "%PDF-1.4\n1 0 obj\n<< /Type /Catalog /Encrypt 2 0 R >>\nendobj\n%%EOF\n"
            ).toByteArray(StandardCharsets.ISO_8859_1)
        assertDocumentFailure("document_pdf_active_content_unsupported") {
            fixture.adapter.inspect(fixture.put("encrypted.pdf", DocumentFormat.PDF.mimeType, encrypted))
        }
    }

    private fun fixture(limits: DocumentLimits = DocumentLimits()): Fixture {
        val boundary = temporary.newFolder("documents-${System.nanoTime()}")
        val store = AtomicArtifactStore(boundary.resolve("artifacts"), boundary)
        return Fixture(store, DocumentArtifactAdapter(store, limits))
    }

    private fun execute(
        executor: DocumentDynamicToolExecutor,
        tool: String,
        arguments: JSONObject,
    ): ai.hans.standard.codex.DynamicToolExecutionResult {
        lateinit var result: ai.hans.standard.codex.DynamicToolExecutionResult
        executor.execute(
            DynamicToolCallParams(
                threadId = "thread",
                turnId = "turn",
                callId = "call",
                namespace = DocumentDynamicToolCatalog.NAMESPACE,
                tool = tool,
                argumentsJson = arguments.toString(),
            ),
        ) { result = it }
        return result
    }

    private fun rawZip(parts: List<Pair<String, ByteArray>>): ByteArray =
        ByteArrayOutputStream().also { output ->
            ZipOutputStream(output).use { zip ->
                parts.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }.toByteArray()

    private fun markFirstCentralEntryAsUnixSymlink(bytes: ByteArray) {
        val signature = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val central = bytes.indices.firstOrNull { index ->
            index <= bytes.size - signature.size && signature.indices.all { bytes[index + it] == signature[it] }
        } ?: error("central directory missing")
        // version-made-by host byte: Unix (3)
        bytes[central + 5] = 3
        // external attributes: Unix mode 0120777 in the upper 16 bits
        bytes[central + 38] = 0
        bytes[central + 39] = 0
        bytes[central + 40] = 0xff.toByte()
        bytes[central + 41] = 0xa1.toByte()
    }

    private fun replaceAsciiOccurrences(bytes: ByteArray, source: String, replacement: String) {
        val from = source.toByteArray(StandardCharsets.US_ASCII)
        val to = replacement.toByteArray(StandardCharsets.US_ASCII)
        require(from.size == to.size)
        var replacements = 0
        for (index in 0..bytes.size - from.size) {
            if (from.indices.all { bytes[index + it] == from[it] }) {
                to.copyInto(bytes, index)
                replacements++
            }
        }
        require(replacements == 2) { "expected local and central ZIP names" }
    }

    private fun assertDocumentFailure(expected: String, operation: () -> Unit) {
        val error = assertThrows(DocumentContractException::class.java) { operation() }
        assertEquals(expected, error.code)
    }

    private data class Fixture(
        val store: AtomicArtifactStore,
        val adapter: DocumentArtifactAdapter,
    ) {
        fun put(name: String, mimeType: String, bytes: ByteArray): ArtifactHandle = store.put(
            name,
            mimeType,
            ArtifactOrigin.USER_IMPORT,
            ByteArrayInputStream(bytes),
        ).handle
    }
}
