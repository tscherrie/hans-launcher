package ai.hans.standard.documents

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class DecodedDocument(
    val format: DocumentFormat,
    val unitKind: String,
    val unitCount: Int,
    val text: String,
    val limitations: List<String>,
)

internal object DocumentCodecs {
    fun decode(
        bytes: ByteArray,
        declaredMimeType: String,
        limits: DocumentLimits,
        cancellation: DocumentCancellation,
    ): DecodedDocument {
        cancellation.checkDocumentCancellation()
        if (bytes.size.toLong() > limits.maxInputBytes) documentFailure("document_input_size_limit")
        return if (bytes.startsWith(PDF_HEADER)) {
            requireCompatibleMime(DocumentFormat.PDF, declaredMimeType)
            PdfCodec.decode(bytes, limits, cancellation)
        } else if (bytes.startsWith(ZIP_HEADER) || bytes.startsWith(ZIP_EMPTY_HEADER)) {
            val zip = SafeDocumentZip.read(bytes, limits, cancellation)
            val format = detectOoxmlFormat(zip)
            requireCompatibleMime(format, declaredMimeType)
            validateOoxml(zip, format, limits, cancellation)
            when (format) {
                DocumentFormat.DOCX -> decodeDocx(zip, limits, cancellation)
                DocumentFormat.XLSX -> decodeXlsx(zip, limits, cancellation)
                DocumentFormat.PPTX -> decodePptx(zip, limits, cancellation)
                DocumentFormat.PDF -> documentFailure("document_format_invalid")
            }
        } else {
            documentFailure("document_format_unsupported")
        }
    }

    fun createPdf(
        title: String,
        paragraphs: List<String>,
        limits: DocumentLimits,
        cancellation: DocumentCancellation,
    ): Pair<ByteArray, Int> = PdfCodec.create(title, paragraphs, limits, cancellation)

    fun createDocx(
        title: String,
        paragraphs: List<String>,
        limits: DocumentLimits,
        cancellation: DocumentCancellation,
    ): Pair<ByteArray, Int> {
        validateParagraphInput(title, paragraphs, limits)
        val content = buildString {
            append(XML_DECLARATION)
            append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
            append("<w:body>")
            (listOf(title) + paragraphs).forEach { paragraph ->
                cancellation.checkDocumentCancellation()
                append("<w:p><w:r><w:t xml:space=\"preserve\">")
                append(xmlEscape(paragraph))
                append("</w:t></w:r></w:p>")
            }
            append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/></w:sectPr>")
            append("</w:body></w:document>")
        }
        val parts = mapOf(
            "[Content_Types].xml" to utf8(
                XML_DECLARATION +
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/word/document.xml\" ContentType=\"$DOCX_MAIN_CONTENT_TYPE\"/>" +
                    "</Types>",
            ),
            "_rels/.rels" to utf8(rootRelationship("word/document.xml")),
            "word/document.xml" to utf8(content),
        )
        return SafeDocumentZip.write(parts, limits, cancellation) to (paragraphs.size + 1)
    }

    fun createXlsx(
        sheetName: String,
        rows: List<List<String>>,
        limits: DocumentLimits,
        cancellation: DocumentCancellation,
    ): Pair<ByteArray, Int> {
        validateText(sheetName, MAX_TITLE_CHARACTERS, allowEmpty = false)
        if (rows.size > limits.maxDocumentUnits) documentFailure("document_unit_limit")
        var cells = 0
        val sheetXml = buildString {
            append(XML_DECLARATION)
            append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
            rows.forEachIndexed { rowIndex, row ->
                cancellation.checkDocumentCancellation()
                if (row.size > MAX_TABLE_COLUMNS) documentFailure("document_table_column_limit")
                cells = Math.addExact(cells, row.size)
                if (cells > limits.maxTableCells) documentFailure("document_table_cell_limit")
                append("<row r=\"").append(rowIndex + 1).append("\">")
                row.forEachIndexed { columnIndex, value ->
                    validateText(value, MAX_CELL_CHARACTERS, allowEmpty = true)
                    append("<c r=\"").append(columnName(columnIndex + 1)).append(rowIndex + 1)
                        .append("\" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                    append(xmlEscape(value))
                    append("</t></is></c>")
                }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
        val parts = mapOf(
            "[Content_Types].xml" to utf8(
                XML_DECLARATION +
                    "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                    "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                    "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
                    "<Override PartName=\"/xl/workbook.xml\" ContentType=\"$XLSX_MAIN_CONTENT_TYPE\"/>" +
                    "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
                    "</Types>",
            ),
            "_rels/.rels" to utf8(rootRelationship("xl/workbook.xml")),
            "xl/workbook.xml" to utf8(
                XML_DECLARATION +
                    "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" " +
                    "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
                    "<sheets><sheet name=\"${xmlEscape(sheetName)}\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>",
            ),
            "xl/_rels/workbook.xml.rels" to utf8(
                relationships(
                    listOf(Relationship("rId1", WORKSHEET_RELATIONSHIP, "worksheets/sheet1.xml")),
                ),
            ),
            "xl/worksheets/sheet1.xml" to utf8(sheetXml),
        )
        return SafeDocumentZip.write(parts, limits, cancellation) to 1
    }

    fun createPptx(
        slides: List<PresentationSlide>,
        limits: DocumentLimits,
        cancellation: DocumentCancellation,
    ): Pair<ByteArray, Int> {
        if (slides.isEmpty() || slides.size > limits.maxDocumentUnits) documentFailure("document_unit_limit")
        val parts = linkedMapOf<String, ByteArray>()
        val overrides = buildString {
            append("<Override PartName=\"/ppt/presentation.xml\" ContentType=\"$PPTX_MAIN_CONTENT_TYPE\"/>")
            append("<Override PartName=\"/ppt/slideMasters/slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml\"/>")
            append("<Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/>")
            append("<Override PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.theme+xml\"/>")
            slides.indices.forEach { index ->
                append("<Override PartName=\"/ppt/slides/slide${index + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>")
            }
        }
        parts["[Content_Types].xml"] = utf8(
            XML_DECLARATION +
                "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
                "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
                "<Default Extension=\"xml\" ContentType=\"application/xml\"/>$overrides</Types>",
        )
        parts["_rels/.rels"] = utf8(rootRelationship("ppt/presentation.xml"))
        parts["ppt/presentation.xml"] = utf8(presentationXml(slides.size))
        val presentationRelationships = mutableListOf(
            Relationship("rId1", SLIDE_MASTER_RELATIONSHIP, "slideMasters/slideMaster1.xml"),
        )
        slides.indices.forEach { index ->
            presentationRelationships += Relationship(
                "rId${index + 2}",
                SLIDE_RELATIONSHIP,
                "slides/slide${index + 1}.xml",
            )
        }
        parts["ppt/_rels/presentation.xml.rels"] = utf8(relationships(presentationRelationships))
        parts["ppt/slideMasters/slideMaster1.xml"] = utf8(slideMasterXml())
        parts["ppt/slideMasters/_rels/slideMaster1.xml.rels"] = utf8(
            relationships(
                listOf(
                    Relationship("rId1", SLIDE_LAYOUT_RELATIONSHIP, "../slideLayouts/slideLayout1.xml"),
                    Relationship("rId2", THEME_RELATIONSHIP, "../theme/theme1.xml"),
                ),
            ),
        )
        parts["ppt/slideLayouts/slideLayout1.xml"] = utf8(slideLayoutXml())
        parts["ppt/slideLayouts/_rels/slideLayout1.xml.rels"] = utf8(
            relationships(
                listOf(Relationship("rId1", SLIDE_MASTER_RELATIONSHIP, "../slideMasters/slideMaster1.xml")),
            ),
        )
        parts["ppt/theme/theme1.xml"] = utf8(themeXml())
        slides.forEachIndexed { index, slide ->
            cancellation.checkDocumentCancellation()
            validateText(slide.title, MAX_TITLE_CHARACTERS, allowEmpty = true)
            slide.body.forEach { validateText(it, MAX_PARAGRAPH_CHARACTERS, allowEmpty = true) }
            parts["ppt/slides/slide${index + 1}.xml"] = utf8(slideXml(slide))
            parts["ppt/slides/_rels/slide${index + 1}.xml.rels"] = utf8(
                relationships(
                    listOf(Relationship("rId1", SLIDE_LAYOUT_RELATIONSHIP, "../slideLayouts/slideLayout1.xml")),
                ),
            )
        }
        return SafeDocumentZip.write(parts, limits, cancellation) to slides.size
    }

    private fun detectOoxmlFormat(zip: SafeZipPackage): DocumentFormat {
        val roots = buildList {
            if ("word/document.xml" in zip.entries) add(DocumentFormat.DOCX)
            if ("xl/workbook.xml" in zip.entries) add(DocumentFormat.XLSX)
            if ("ppt/presentation.xml" in zip.entries) add(DocumentFormat.PPTX)
        }
        if (roots.size != 1) documentFailure("document_ooxml_root_invalid")
        return roots.single()
    }

    private fun validateOoxml(
        zip: SafeZipPackage,
        format: DocumentFormat,
        limits: DocumentLimits,
        cancellation: DocumentCancellation,
    ) {
        REQUIRED_PACKAGE_PARTS.forEach { if (it !in zip.entries) documentFailure("document_part_missing") }
        zip.entries.forEach { (name, bytes) ->
            cancellation.checkDocumentCancellation()
            val lower = name.lowercase(Locale.ROOT)
            if (isActiveOrEmbeddedPart(lower)) documentFailure("document_active_content_unsupported")
            if (lower.endsWith(".xml") || lower.endsWith(".rels")) {
                SafeDocumentXml.parse(bytes, limits, cancellation)
            }
        }
        zip.entries.filterKeys { it.lowercase(Locale.ROOT).endsWith(".rels") }.forEach { (_, bytes) ->
            SafeDocumentXml.parse(bytes, limits, cancellation, object : SafeXmlHandler {
                override fun start(element: SafeXmlElement) {
                    if (element.localName == "Relationship") {
                        val targetMode = element.attributeByLocalName("TargetMode")
                        if (targetMode.equals("External", ignoreCase = true)) {
                            documentFailure("document_external_relationship_unsupported")
                        }
                    }
                }
            })
        }
        val expectedMainPart = when (format) {
            DocumentFormat.DOCX -> "/word/document.xml" to DOCX_MAIN_CONTENT_TYPE
            DocumentFormat.XLSX -> "/xl/workbook.xml" to XLSX_MAIN_CONTENT_TYPE
            DocumentFormat.PPTX -> "/ppt/presentation.xml" to PPTX_MAIN_CONTENT_TYPE
            DocumentFormat.PDF -> documentFailure("document_format_invalid")
        }
        var mainTypeProved = false
        SafeDocumentXml.parse(zip["[Content_Types].xml"], limits, cancellation, object : SafeXmlHandler {
            override fun start(element: SafeXmlElement) {
                if (
                    element.localName == "Override" &&
                    element.attributeByLocalName("PartName") == expectedMainPart.first &&
                    element.attributeByLocalName("ContentType") == expectedMainPart.second
                ) {
                    mainTypeProved = true
                }
            }
        })
        if (!mainTypeProved) documentFailure("document_ooxml_content_type_invalid")
    }

    private fun decodeDocx(
        zip: SafeZipPackage,
        limits: DocumentLimits,
        cancellation: DocumentCancellation,
    ): DecodedDocument {
        val output = BoundedDocumentText(limits.maxExtractedTextCharacters)
        var textDepth = 0
        var paragraphCount = 0
        SafeDocumentXml.parse(zip["word/document.xml"], limits, cancellation, object : SafeXmlHandler {
            override fun start(element: SafeXmlElement) {
                when (element.localName) {
                    "p" -> {
                        paragraphCount++
                        if (paragraphCount > limits.maxDocumentUnits) documentFailure("document_unit_limit")
                    }
                    "t" -> textDepth++
                }
            }

            override fun text(value: String) {
                if (textDepth > 0) output.append(value)
            }

            override fun end(element: SafeXmlElement) {
                when (element.localName) {
                    "t" -> textDepth--
                    "p" -> output.appendLine()
                }
            }
        })
        return DecodedDocument(
            DocumentFormat.DOCX,
            "paragraphs",
            paragraphCount,
            output.value(),
            listOf("text_only_no_styles_comments_or_media"),
        )
    }

    private fun decodePptx(
        zip: SafeZipPackage,
        limits: DocumentLimits,
        cancellation: DocumentCancellation,
    ): DecodedDocument {
        val slides = zip.entries.keys.mapNotNull { name ->
            SLIDE_PATH.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.let { it to name }
        }.sortedBy { it.first }
        if (slides.isEmpty() || slides.size > limits.maxDocumentUnits) documentFailure("document_unit_limit")
        val output = BoundedDocumentText(limits.maxExtractedTextCharacters)
        slides.forEachIndexed { slideIndex, (_, name) ->
            cancellation.checkDocumentCancellation()
            if (slideIndex > 0) output.appendLine()
            var textDepth = 0
            SafeDocumentXml.parse(zip[name], limits, cancellation, object : SafeXmlHandler {
                override fun start(element: SafeXmlElement) {
                    if (element.localName == "t") textDepth++
                }

                override fun text(value: String) {
                    if (textDepth > 0) output.append(value)
                }

                override fun end(element: SafeXmlElement) {
                    if (element.localName == "t") {
                        textDepth--
                        output.appendLine()
                    }
                }
            })
        }
        return DecodedDocument(
            DocumentFormat.PPTX,
            "slides",
            slides.size,
            output.value(),
            listOf("text_only_no_layout_notes_animations_or_media"),
        )
    }

    private fun decodeXlsx(
        zip: SafeZipPackage,
        limits: DocumentLimits,
        cancellation: DocumentCancellation,
    ): DecodedDocument {
        val sharedStrings = zip.entries["xl/sharedStrings.xml"]?.let {
            decodeSharedStrings(it, limits, cancellation)
        }.orEmpty()
        val sheets = zip.entries.keys.mapNotNull { name ->
            SHEET_PATH.matchEntire(name)?.groupValues?.get(1)?.toIntOrNull()?.let { it to name }
        }.sortedBy { it.first }
        if (sheets.isEmpty() || sheets.size > limits.maxDocumentUnits) documentFailure("document_unit_limit")
        val output = BoundedDocumentText(limits.maxExtractedTextCharacters)
        var totalCells = 0
        sheets.forEachIndexed { sheetIndex, (_, name) ->
            cancellation.checkDocumentCancellation()
            if (sheetIndex > 0) output.appendLine()
            output.append("Sheet ${sheetIndex + 1}")
            output.appendLine()
            var cellType: String? = null
            var cellColumn = -1
            var captureDepth = 0
            var captured = StringBuilder()
            var rowCells = sortedMapOf<Int, String>()
            SafeDocumentXml.parse(zip[name], limits, cancellation, object : SafeXmlHandler {
                override fun start(element: SafeXmlElement) {
                    when (element.localName) {
                        "row" -> rowCells = sortedMapOf()
                        "c" -> {
                            cellType = element.attributeByLocalName("t")
                            cellColumn = parseCellColumn(element.attributeByLocalName("r"))
                            captured = StringBuilder()
                        }
                        "t", "v" -> if (cellColumn >= 0) captureDepth++
                    }
                }

                override fun text(value: String) {
                    if (captureDepth > 0) captured.append(value)
                }

                override fun end(element: SafeXmlElement) {
                    when (element.localName) {
                        "t", "v" -> if (captureDepth > 0) captureDepth--
                        "c" -> {
                            totalCells++
                            if (totalCells > limits.maxTableCells) documentFailure("document_table_cell_limit")
                            val raw = captured.toString()
                            val value = if (cellType == "s") {
                                val index = raw.toIntOrNull() ?: documentFailure("document_shared_string_invalid")
                                sharedStrings.getOrNull(index) ?: documentFailure("document_shared_string_invalid")
                            } else {
                                raw
                            }
                            rowCells[cellColumn] = value
                            cellColumn = -1
                            cellType = null
                        }
                        "row" -> {
                            val last = rowCells.lastKeyOrNull() ?: -1
                            if (last >= MAX_TABLE_COLUMNS) documentFailure("document_table_column_limit")
                            for (column in 0..last) {
                                if (column > 0) output.append("\t")
                                output.append(rowCells[column].orEmpty())
                            }
                            output.appendLine()
                        }
                    }
                }
            })
        }
        return DecodedDocument(
            DocumentFormat.XLSX,
            "sheets",
            sheets.size,
            output.value(),
            listOf("display_values_only_no_formulas_styles_charts_or_media"),
        )
    }

    private fun decodeSharedStrings(
        bytes: ByteArray,
        limits: DocumentLimits,
        cancellation: DocumentCancellation,
    ): List<String> {
        val values = mutableListOf<String>()
        var insideItem = false
        var textDepth = 0
        var current = StringBuilder()
        SafeDocumentXml.parse(bytes, limits, cancellation, object : SafeXmlHandler {
            override fun start(element: SafeXmlElement) {
                when (element.localName) {
                    "si" -> {
                        insideItem = true
                        current = StringBuilder()
                    }
                    "t" -> if (insideItem) textDepth++
                }
            }

            override fun text(value: String) {
                if (insideItem && textDepth > 0) current.append(value)
            }

            override fun end(element: SafeXmlElement) {
                when (element.localName) {
                    "t" -> if (insideItem && textDepth > 0) textDepth--
                    "si" -> {
                        values += current.toString()
                        if (values.size > limits.maxTableCells) documentFailure("document_shared_string_limit")
                        insideItem = false
                    }
                }
            }
        })
        return values
    }

    private fun requireCompatibleMime(format: DocumentFormat, declared: String) {
        val normalized = declared.substringBefore(';').trim().lowercase(Locale.ROOT)
        if (normalized !in setOf(format.mimeType, "application/octet-stream", "application/zip")) {
            documentFailure("document_mime_mismatch")
        }
    }

    private fun isActiveOrEmbeddedPart(lowerPath: String): Boolean =
        lowerPath.endsWith(".bin") ||
            "/vbaproject" in lowerPath ||
            lowerPath.startsWith("activex/") ||
            "/activex/" in lowerPath ||
            lowerPath.startsWith("embeddings/") ||
            "/embeddings/" in lowerPath ||
            lowerPath.startsWith("customui/") ||
            "/customui/" in lowerPath ||
            "oleobject" in lowerPath

    private fun validateParagraphInput(title: String, paragraphs: List<String>, limits: DocumentLimits) {
        validateText(title, MAX_TITLE_CHARACTERS, allowEmpty = true)
        if (paragraphs.size + 1 > limits.maxDocumentUnits) documentFailure("document_unit_limit")
        paragraphs.forEach { validateText(it, MAX_PARAGRAPH_CHARACTERS, allowEmpty = true) }
    }

    private fun validateText(value: String, maxCharacters: Int, allowEmpty: Boolean) {
        if ((!allowEmpty && value.isBlank()) || value.length > maxCharacters || '\u0000' in value) {
            documentFailure("document_text_invalid")
        }
        if (value.any { it.isISOControl() && it !in "\t\r\n" }) documentFailure("document_text_invalid")
    }

    private fun columnName(column: Int): String {
        if (column !in 1..MAX_TABLE_COLUMNS) documentFailure("document_table_column_limit")
        var value = column
        return buildString {
            while (value > 0) {
                value--
                insert(0, ('A'.code + value % 26).toChar())
                value /= 26
            }
        }
    }

    private fun parseCellColumn(reference: String?): Int {
        if (reference == null) return 0
        val letters = reference.takeWhile(Char::isLetter)
        if (letters.isEmpty() || letters.length > 4) documentFailure("document_cell_reference_invalid")
        var value = 0
        letters.uppercase(Locale.ROOT).forEach { character ->
            if (character !in 'A'..'Z') documentFailure("document_cell_reference_invalid")
            value = Math.addExact(Math.multiplyExact(value, 26), character.code - 'A'.code + 1)
        }
        return value - 1
    }

    private fun SafeXmlElement.attributeByLocalName(name: String): String? =
        attributes.entries.singleOrNull { it.key.substringAfterLast(':') == name }?.value

    private fun Map<Int, String>.lastKeyOrNull(): Int? = keys.lastOrNull()

    private fun rootRelationship(target: String) = relationships(
        listOf(Relationship("rId1", OFFICE_DOCUMENT_RELATIONSHIP, target)),
    )

    private fun relationships(values: List<Relationship>) = buildString {
        append(XML_DECLARATION)
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        values.forEach { relationship ->
            append("<Relationship Id=\"").append(xmlEscape(relationship.id))
                .append("\" Type=\"").append(xmlEscape(relationship.type))
                .append("\" Target=\"").append(xmlEscape(relationship.target)).append("\"/>")
        }
        append("</Relationships>")
    }

    private fun presentationXml(slideCount: Int) = buildString {
        append(XML_DECLARATION)
        append("<p:presentation xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" ")
        append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" ")
        append("xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">")
        append("<p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/></p:sldMasterIdLst><p:sldIdLst>")
        repeat(slideCount) { index ->
            append("<p:sldId id=\"").append(256 + index).append("\" r:id=\"rId").append(index + 2).append("\"/>")
        }
        append("</p:sldIdLst><p:sldSz cx=\"12192000\" cy=\"6858000\"/><p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>")
    }

    private fun slideXml(slide: PresentationSlide) = buildString {
        append(XML_DECLARATION)
        append("<p:sld xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" ")
        append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" ")
        append("xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\"><p:cSld><p:spTree>")
        append(GROUP_SHAPE)
        append(textBox(2, "Title", slide.title, 457200, 274638, 11277600, 1143000, 2400))
        append(textBox(3, "Body", slide.body.joinToString("\n"), 685800, 1600200, 10820400, 4572000, 1800))
        append("</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>")
    }

    private fun textBox(
        id: Int,
        name: String,
        text: String,
        x: Int,
        y: Int,
        cx: Int,
        cy: Int,
        fontSize: Int,
    ) = buildString {
        append("<p:sp><p:nvSpPr><p:cNvPr id=\"").append(id).append("\" name=\"").append(xmlEscape(name))
            .append("\"/><p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr>")
        append("<a:xfrm><a:off x=\"").append(x).append("\" y=\"").append(y)
            .append("\"/><a:ext cx=\"").append(cx).append("\" cy=\"").append(cy).append("\"/></a:xfrm>")
        append("<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom><a:noFill/></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/>")
        text.split('\n').forEach { line ->
            append("<a:p><a:r><a:rPr lang=\"de-DE\" sz=\"").append(fontSize).append("\"/><a:t>")
            append(xmlEscape(line)).append("</a:t></a:r><a:endParaRPr lang=\"de-DE\"/></a:p>")
        }
        append("</p:txBody></p:sp>")
    }

    private fun slideMasterXml() =
        XML_DECLARATION +
            "<p:sldMaster xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\">" +
            "<p:cSld><p:spTree>$GROUP_SHAPE</p:spTree></p:cSld><p:clrMap accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\"accent4\" accent5=\"accent5\" accent6=\"accent6\" bg1=\"lt1\" bg2=\"lt2\" folHlink=\"folHlink\" hlink=\"hlink\" tx1=\"dk1\" tx2=\"dk2\"/>" +
            "<p:sldLayoutIdLst><p:sldLayoutId id=\"1\" r:id=\"rId1\"/></p:sldLayoutIdLst><p:txStyles><p:titleStyle/><p:bodyStyle/><p:otherStyle/></p:txStyles></p:sldMaster>"

    private fun slideLayoutXml() =
        XML_DECLARATION +
            "<p:sldLayout xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" type=\"blank\" preserve=\"1\">" +
            "<p:cSld name=\"Blank\"><p:spTree>$GROUP_SHAPE</p:spTree></p:cSld><p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sldLayout>"

    private fun themeXml() =
        XML_DECLARATION +
            "<a:theme xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" name=\"Hans\"><a:themeElements>" +
            "<a:clrScheme name=\"Hans\"><a:dk1><a:srgbClr val=\"000000\"/></a:dk1><a:lt1><a:srgbClr val=\"FFFFFF\"/></a:lt1>" +
            "<a:dk2><a:srgbClr val=\"222222\"/></a:dk2><a:lt2><a:srgbClr val=\"EEEEEE\"/></a:lt2>" +
            "<a:accent1><a:srgbClr val=\"4472C4\"/></a:accent1><a:accent2><a:srgbClr val=\"ED7D31\"/></a:accent2>" +
            "<a:accent3><a:srgbClr val=\"A5A5A5\"/></a:accent3><a:accent4><a:srgbClr val=\"FFC000\"/></a:accent4>" +
            "<a:accent5><a:srgbClr val=\"5B9BD5\"/></a:accent5><a:accent6><a:srgbClr val=\"70AD47\"/></a:accent6>" +
            "<a:hlink><a:srgbClr val=\"0563C1\"/></a:hlink><a:folHlink><a:srgbClr val=\"954F72\"/></a:folHlink></a:clrScheme>" +
            "<a:fontScheme name=\"Hans\"><a:majorFont><a:latin typeface=\"Arial\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont>" +
            "<a:minorFont><a:latin typeface=\"Arial\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont></a:fontScheme>" +
            "<a:fmtScheme name=\"Hans\"><a:fillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:fillStyleLst>" +
            "<a:lnStyleLst><a:ln w=\"9525\"><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:ln></a:lnStyleLst>" +
            "<a:effectStyleLst><a:effectStyle><a:effectLst/></a:effectStyle></a:effectStyleLst><a:bgFillStyleLst><a:solidFill><a:schemeClr val=\"phClr\"/></a:solidFill></a:bgFillStyleLst></a:fmtScheme>" +
            "</a:themeElements></a:theme>"

    private data class Relationship(val id: String, val type: String, val target: String)

    private class BoundedDocumentText(private val maxCharacters: Int) {
        private val builder = StringBuilder(minOf(maxCharacters, 8 * 1024))

        fun append(value: String) {
            if (builder.length > maxCharacters - value.length) documentFailure("document_text_limit")
            builder.append(value)
        }

        fun appendLine() {
            if (builder.length >= maxCharacters) documentFailure("document_text_limit")
            if (builder.isNotEmpty() && builder.last() != '\n') builder.append('\n')
        }

        fun value(): String = builder.toString().trimEnd()
    }

    private fun utf8(value: String): ByteArray = value.toByteArray(StandardCharsets.UTF_8)

    private val PDF_HEADER = "%PDF-".toByteArray(StandardCharsets.US_ASCII)
    private val ZIP_HEADER = byteArrayOf(0x50, 0x4b, 0x03, 0x04)
    private val ZIP_EMPTY_HEADER = byteArrayOf(0x50, 0x4b, 0x05, 0x06)
    private val SLIDE_PATH = Regex("ppt/slides/slide([1-9][0-9]*)\\.xml")
    private val SHEET_PATH = Regex("xl/worksheets/sheet([1-9][0-9]*)\\.xml")
    private val REQUIRED_PACKAGE_PARTS = setOf("[Content_Types].xml", "_rels/.rels")
    private const val XML_DECLARATION = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
    private const val OFFICE_DOCUMENT_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument"
    private const val WORKSHEET_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet"
    private const val SLIDE_MASTER_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster"
    private const val SLIDE_LAYOUT_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout"
    private const val SLIDE_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide"
    private const val THEME_RELATIONSHIP = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme"
    private const val DOCX_MAIN_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"
    private const val XLSX_MAIN_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"
    private const val PPTX_MAIN_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"
    private const val MAX_TITLE_CHARACTERS = 2_000
    private const val MAX_PARAGRAPH_CHARACTERS = 64 * 1024
    private const val MAX_CELL_CHARACTERS = 32 * 1024
    private const val MAX_TABLE_COLUMNS = 16_384
    private const val GROUP_SHAPE = "<p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"0\" cy=\"0\"/><a:chOff x=\"0\" y=\"0\"/><a:chExt cx=\"0\" cy=\"0\"/></a:xfrm></p:grpSpPr>"
}

private object PdfCodec {
    fun decode(
        bytes: ByteArray,
        limits: DocumentLimits,
        cancellation: DocumentCancellation,
    ): DecodedDocument {
        cancellation.checkDocumentCancellation()
        if (bytes.size.toLong() > limits.maxInputBytes) documentFailure("document_input_size_limit")
        val ascii = String(bytes, StandardCharsets.ISO_8859_1)
        if (!ascii.startsWith("%PDF-1.") && !ascii.startsWith("%PDF-2.")) {
            documentFailure("document_pdf_header_invalid")
        }
        val tail = ascii.takeLast(minOf(ascii.length, 4_096))
        if ("%%EOF" !in tail) documentFailure("document_pdf_eof_missing")
        validatePdfNames(ascii)
        val objectCount = PDF_OBJECT.findAll(ascii).take(limits.maxPdfObjects + 1).count()
        if (objectCount == 0 || objectCount > limits.maxPdfObjects) documentFailure("document_pdf_object_limit")
        val pageCount = maxOf(1, PDF_PAGE.findAll(ascii).take(limits.maxDocumentUnits + 1).count())
        if (pageCount > limits.maxDocumentUnits) documentFailure("document_unit_limit")
        val text = extractPdfText(ascii, limits, cancellation)
        return DecodedDocument(
            DocumentFormat.PDF,
            "pages",
            pageCount,
            text,
            listOf("basic_unencrypted_text_only_no_layout_forms_scripts_or_attachments"),
        )
    }

    fun create(
        title: String,
        paragraphs: List<String>,
        limits: DocumentLimits,
        cancellation: DocumentCancellation,
    ): Pair<ByteArray, Int> {
        validatePdfInput(title, paragraphs, limits)
        val lines = mutableListOf<PdfLine>()
        if (title.isNotEmpty()) wrap(title, TITLE_WRAP).forEach { lines += PdfLine(it, true) }
        paragraphs.forEach { paragraph ->
            cancellation.checkDocumentCancellation()
            if (lines.isNotEmpty()) lines += PdfLine("", false)
            wrap(paragraph, BODY_WRAP).forEach { lines += PdfLine(it, false) }
        }
        if (lines.isEmpty()) lines += PdfLine("", false)
        val pages = lines.chunked(LINES_PER_PAGE)
        if (pages.size > limits.maxDocumentUnits) documentFailure("document_unit_limit")
        val objects = mutableListOf<ByteArray>()
        objects += ascii("<< /Type /Catalog /Pages 2 0 R >>")
        val pageObjectNumbers = pages.indices.map { 4 + it * 2 }
        objects += ascii(
            "<< /Type /Pages /Count ${pages.size} /Kids [${pageObjectNumbers.joinToString(" ") { "$it 0 R" }}] >>",
        )
        objects += ascii("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        pages.forEachIndexed { index, pageLines ->
            cancellation.checkDocumentCancellation()
            val contentObject = 5 + index * 2
            objects += ascii(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R >> >> /Contents $contentObject 0 R >>",
            )
            val stream = buildPdfPage(pageLines)
            objects += concat(ascii("<< /Length ${stream.size} >>\nstream\n"), stream, ascii("\nendstream"))
        }
        val output = ByteArrayOutputStream()
        output.write("%PDF-1.4\n%\u00e2\u00e3\u00cf\u00d3\n".toByteArray(StandardCharsets.ISO_8859_1))
        val offsets = IntArray(objects.size + 1)
        objects.forEachIndexed { index, body ->
            cancellation.checkDocumentCancellation()
            offsets[index + 1] = output.size()
            output.write(ascii("${index + 1} 0 obj\n"))
            output.write(body)
            output.write(ascii("\nendobj\n"))
            if (output.size() > limits.maxGeneratedBytes) documentFailure("document_generated_size_limit")
        }
        val xrefOffset = output.size()
        output.write(ascii("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n"))
        for (index in 1..objects.size) {
            output.write(ascii("${offsets[index].toString().padStart(10, '0')} 00000 n \n"))
        }
        output.write(
            ascii(
                "trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xrefOffset\n%%EOF\n",
            ),
        )
        return output.toByteArray().also {
            if (it.size > limits.maxGeneratedBytes) documentFailure("document_generated_size_limit")
        } to pages.size
    }

    private fun validatePdfNames(ascii: String) {
        PDF_NAME.findAll(ascii).forEach { match ->
            val decoded = decodePdfName(match.groupValues[1]).lowercase(Locale.ROOT)
            if (decoded in FORBIDDEN_PDF_NAMES) documentFailure("document_pdf_active_content_unsupported")
        }
    }

    private fun decodePdfName(value: String): String {
        val output = StringBuilder(value.length)
        var cursor = 0
        while (cursor < value.length) {
            if (value[cursor] == '#' && cursor + 2 < value.length) {
                val byte = value.substring(cursor + 1, cursor + 3).toIntOrNull(16)
                if (byte != null) {
                    output.append(byte.toChar())
                    cursor += 3
                    continue
                }
            }
            output.append(value[cursor++])
        }
        return output.toString()
    }

    private fun extractPdfText(
        ascii: String,
        limits: DocumentLimits,
        cancellation: DocumentCancellation,
    ): String {
        val output = StringBuilder(minOf(limits.maxExtractedTextCharacters, 8 * 1024))
        PDF_TEXT_BLOCK.findAll(ascii).forEach { block ->
            cancellation.checkDocumentCancellation()
            var cursor = block.range.first + 2
            val end = block.range.last - 1
            while (cursor < end) {
                when (ascii[cursor]) {
                    '(' -> {
                        val (value, next) = parsePdfLiteral(ascii, cursor, end)
                        appendBounded(output, value, limits.maxExtractedTextCharacters)
                        appendBounded(output, "\n", limits.maxExtractedTextCharacters)
                        cursor = next
                    }
                    '<' -> {
                        if (cursor + 1 < end && ascii[cursor + 1] != '<') {
                            val close = ascii.indexOf('>', cursor + 1).takeIf { it in (cursor + 1)..end }
                            if (close != null) {
                                val hex = ascii.substring(cursor + 1, close).filterNot(Char::isWhitespace)
                                if (hex.length % 2 == 0 && hex.matches(Regex("[0-9A-Fa-f]*"))) {
                                    appendBounded(
                                        output,
                                        decodePdfBytes(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()),
                                        limits.maxExtractedTextCharacters,
                                    )
                                    appendBounded(output, "\n", limits.maxExtractedTextCharacters)
                                    cursor = close + 1
                                } else cursor++
                            } else cursor++
                        } else cursor++
                    }
                    else -> cursor++
                }
            }
        }
        return output.toString().trim()
    }

    private fun parsePdfLiteral(source: String, start: Int, bound: Int): Pair<String, Int> {
        val bytes = ByteArrayOutputStream()
        var depth = 1
        var cursor = start + 1
        while (cursor < bound && depth > 0) {
            val character = source[cursor++]
            when (character) {
                '\\' -> {
                    if (cursor >= bound) documentFailure("document_pdf_string_invalid")
                    when (val escaped = source[cursor++]) {
                        'n' -> bytes.write('\n'.code)
                        'r' -> bytes.write('\r'.code)
                        't' -> bytes.write('\t'.code)
                        'b' -> bytes.write('\b'.code)
                        'f' -> bytes.write(12)
                        '(', ')', '\\' -> bytes.write(escaped.code)
                        '\n' -> Unit
                        '\r' -> if (cursor < bound && source[cursor] == '\n') cursor++
                        in '0'..'7' -> {
                            var octal = escaped.toString()
                            repeat(2) {
                                if (cursor < bound && source[cursor] in '0'..'7') octal += source[cursor++]
                            }
                            bytes.write(octal.toInt(8) and 0xff)
                        }
                        else -> bytes.write(escaped.code and 0xff)
                    }
                }
                '(' -> {
                    depth++
                    bytes.write('('.code)
                }
                ')' -> {
                    depth--
                    if (depth > 0) bytes.write(')'.code)
                }
                else -> bytes.write(character.code and 0xff)
            }
        }
        if (depth != 0) documentFailure("document_pdf_string_invalid")
        return decodePdfBytes(bytes.toByteArray()) to cursor
    }

    private fun decodePdfBytes(bytes: ByteArray): String {
        if (bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()) {
            if ((bytes.size - 2) % 2 != 0) documentFailure("document_pdf_text_encoding_invalid")
            val decoder = StandardCharsets.UTF_16BE.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return runCatching { decoder.decode(ByteBuffer.wrap(bytes, 2, bytes.size - 2)).toString() }
                .getOrElse { documentFailure("document_pdf_text_encoding_invalid") }
        }
        return String(bytes, WIN_1252)
    }

    private fun buildPdfPage(lines: List<PdfLine>): ByteArray = buildString {
        append("BT\n/F1 12 Tf\n72 790 Td\n")
        lines.forEachIndexed { index, line ->
            val size = if (line.title) 18 else 12
            if (index > 0) append("0 -18 Td\n")
            append("/F1 ").append(size).append(" Tf\n(")
            append(String(escapePdfBytes(encodeWin1252(line.text)), StandardCharsets.ISO_8859_1))
            append(") Tj\n")
        }
        append("ET")
    }.toByteArray(StandardCharsets.ISO_8859_1)

    private fun validatePdfInput(title: String, paragraphs: List<String>, limits: DocumentLimits) {
        if (title.length > MAX_PDF_LINE_CHARACTERS || paragraphs.size + 1 > limits.maxDocumentUnits) {
            documentFailure("document_unit_limit")
        }
        encodeWin1252(title)
        paragraphs.forEach {
            if (it.length > MAX_PDF_PARAGRAPH_CHARACTERS || '\u0000' in it) documentFailure("document_text_invalid")
            encodeWin1252(it)
        }
    }

    private fun encodeWin1252(value: String): ByteArray {
        if (value.any { it.isISOControl() && it !in "\t\r\n" }) documentFailure("document_text_invalid")
        val encoder = WIN_1252.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching {
            val buffer = encoder.encode(java.nio.CharBuffer.wrap(value))
            ByteArray(buffer.remaining()).also(buffer::get)
        }.getOrElse { documentFailure("document_pdf_text_unsupported") }
    }

    private fun escapePdfBytes(bytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(bytes.size)
        bytes.forEach { raw ->
            when (val value = raw.toInt() and 0xff) {
                '('.code, ')'.code, '\\'.code -> {
                    output.write('\\'.code)
                    output.write(value)
                }
                '\n'.code -> output.write("\\n".toByteArray(StandardCharsets.US_ASCII))
                '\r'.code -> output.write("\\r".toByteArray(StandardCharsets.US_ASCII))
                else -> output.write(value)
            }
        }
        return output.toByteArray()
    }

    private fun wrap(value: String, width: Int): List<String> {
        if (value.isEmpty()) return listOf("")
        val output = mutableListOf<String>()
        value.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { sourceLine ->
            var remaining = sourceLine
            if (remaining.isEmpty()) output += ""
            while (remaining.length > width) {
                val candidate = remaining.take(width + 1)
                val split = candidate.lastIndexOf(' ').takeIf { it > 0 } ?: width
                output += remaining.take(split).trimEnd()
                remaining = remaining.drop(split).trimStart()
            }
            if (remaining.isNotEmpty()) output += remaining
        }
        return output
    }

    private fun appendBounded(target: StringBuilder, value: String, limit: Int) {
        if (target.length > limit - value.length) documentFailure("document_text_limit")
        target.append(value)
    }

    private fun ascii(value: String) = value.toByteArray(StandardCharsets.US_ASCII)

    private fun concat(vararg values: ByteArray): ByteArray {
        val output = ByteArrayOutputStream(values.sumOf(ByteArray::size))
        values.forEach(output::write)
        return output.toByteArray()
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private data class PdfLine(val text: String, val title: Boolean)

    private val WIN_1252 = java.nio.charset.Charset.forName("windows-1252")
    private val PDF_OBJECT = Regex("(?m)(?:^|\\s)[0-9]+\\s+[0-9]+\\s+obj(?:\\s|$)")
    private val PDF_PAGE = Regex("/Type\\s*/Page(?!s)[^A-Za-z0-9]")
    private val PDF_NAME = Regex("/([A-Za-z0-9#]+)")
    private val PDF_TEXT_BLOCK = Regex("(?s)\\bBT\\b.*?\\bET\\b")
    private val FORBIDDEN_PDF_NAMES = setOf(
        "encrypt",
        "javascript",
        "js",
        "launch",
        "openaction",
        "aa",
        "richmedia",
        "embeddedfile",
        "xfa",
        "submitform",
        "importdata",
    )
    private const val TITLE_WRAP = 55
    private const val BODY_WRAP = 90
    private const val LINES_PER_PAGE = 39
    private const val MAX_PDF_LINE_CHARACTERS = 64 * 1024
    private const val MAX_PDF_PARAGRAPH_CHARACTERS = 64 * 1024
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
