package ai.hans.standard.documents

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import java.util.TreeMap
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

internal data class SafeZipEntryMetadata(
    val name: String,
    val compressedBytes: Long,
    val expandedBytes: Long,
    val directory: Boolean,
)

internal data class SafeZipPackage(
    val entries: Map<String, ByteArray>,
    val metadata: List<SafeZipEntryMetadata>,
) {
    operator fun get(name: String): ByteArray = entries[name] ?: documentFailure("document_part_missing")
}

internal object SafeDocumentZip {
    fun read(
        bytes: ByteArray,
        limits: DocumentLimits,
        cancellation: DocumentCancellation,
    ): SafeZipPackage {
        cancellation.checkDocumentCancellation()
        val central = parseCentralDirectory(bytes, limits, cancellation)
        val byName = central.associateBy(SafeZipEntryMetadata::name)
        val output = TreeMap<String, ByteArray>()
        var expandedTotal = 0L
        var observedEntries = 0
        ZipInputStream(ByteArrayInputStream(bytes), StandardCharsets.UTF_8).use { zip ->
            while (true) {
                cancellation.checkDocumentCancellation()
                val entry = runCatching(zip::getNextEntry).getOrElse {
                    documentFailure("document_zip_invalid")
                } ?: break
                observedEntries = Math.addExact(observedEntries, 1)
                if (observedEntries > limits.maxZipEntries) documentFailure("document_zip_entry_limit")
                val name = validateZipPath(entry.name, entry.isDirectory)
                val expected = byName[name] ?: documentFailure("document_zip_directory_mismatch")
                if (expected.directory != entry.isDirectory) documentFailure("document_zip_directory_mismatch")
                if (!entry.isDirectory) {
                    if (output.containsKey(name)) documentFailure("document_zip_duplicate_entry")
                    val part = readZipEntry(zip, limits, cancellation) { count ->
                        expandedTotal = Math.addExact(expandedTotal, count.toLong())
                        if (expandedTotal > limits.maxZipExpandedBytes) {
                            documentFailure("document_zip_expanded_limit")
                        }
                    }
                    if (expected.expandedBytes >= 0 && expected.expandedBytes != part.size.toLong()) {
                        documentFailure("document_zip_size_mismatch")
                    }
                    output[name] = part
                }
                runCatching(zip::closeEntry).getOrElse { documentFailure("document_zip_invalid") }
            }
        }
        val expectedFiles = central.count { !it.directory }
        if (observedEntries != central.size || output.size != expectedFiles) {
            documentFailure("document_zip_directory_mismatch")
        }
        return SafeZipPackage(output, central.sortedBy(SafeZipEntryMetadata::name))
    }

    fun write(
        parts: Map<String, ByteArray>,
        limits: DocumentLimits,
        cancellation: DocumentCancellation,
    ): ByteArray {
        if (parts.isEmpty() || parts.size > limits.maxZipEntries) documentFailure("document_zip_entry_limit")
        val normalized = TreeMap<String, ByteArray>()
        var expandedTotal = 0L
        parts.forEach { (rawName, bytes) ->
            cancellation.checkDocumentCancellation()
            val name = validateZipPath(rawName, directory = false)
            if (normalized.put(name, bytes) != null) documentFailure("document_zip_duplicate_entry")
            if (bytes.size > limits.maxZipEntryBytes) documentFailure("document_zip_entry_size_limit")
            expandedTotal = Math.addExact(expandedTotal, bytes.size.toLong())
            if (expandedTotal > limits.maxZipExpandedBytes) documentFailure("document_zip_expanded_limit")
        }
        val output = ByteArrayOutputStream(minOf(limits.maxGeneratedBytes, expandedTotal.toInt().coerceAtLeast(1)))
        ZipOutputStream(output, StandardCharsets.UTF_8).use { zip ->
            normalized.forEach { (name, bytes) ->
                cancellation.checkDocumentCancellation()
                val crc = CRC32().also { it.update(bytes) }.value
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = bytes.size.toLong()
                    compressedSize = bytes.size.toLong()
                    this.crc = crc
                    time = 0L
                    comment = null
                    extra = null
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
                if (output.size() > limits.maxGeneratedBytes) documentFailure("document_generated_size_limit")
            }
        }
        return output.toByteArray().also {
            if (it.size > limits.maxGeneratedBytes) documentFailure("document_generated_size_limit")
        }
    }

    private fun parseCentralDirectory(
        bytes: ByteArray,
        limits: DocumentLimits,
        cancellation: DocumentCancellation,
    ): List<SafeZipEntryMetadata> {
        if (bytes.size < EOCD_MIN_BYTES) documentFailure("document_zip_invalid")
        val eocd = findEndOfCentralDirectory(bytes)
        val disk = u16(bytes, eocd + 4)
        val centralDisk = u16(bytes, eocd + 6)
        val diskEntries = u16(bytes, eocd + 8)
        val entryCount = u16(bytes, eocd + 10)
        val centralSize = u32(bytes, eocd + 12)
        val centralOffset = u32(bytes, eocd + 16)
        val commentLength = u16(bytes, eocd + 20)
        if (disk != 0 || centralDisk != 0 || diskEntries != entryCount) {
            documentFailure("document_zip_multidisk_unsupported")
        }
        if (entryCount == 0xffff || centralSize == 0xffffffffL || centralOffset == 0xffffffffL) {
            documentFailure("document_zip64_unsupported")
        }
        if (entryCount == 0 || entryCount > limits.maxZipEntries) documentFailure("document_zip_entry_limit")
        if (eocd + EOCD_MIN_BYTES + commentLength != bytes.size) documentFailure("document_zip_invalid")
        val centralEnd = Math.addExact(centralOffset, centralSize)
        if (centralOffset > bytes.size || centralEnd > eocd || centralEnd > bytes.size) {
            documentFailure("document_zip_invalid")
        }
        var cursor = centralOffset.toInt()
        val entries = ArrayList<SafeZipEntryMetadata>(entryCount)
        val exactNames = linkedSetOf<String>()
        val foldedNames = linkedSetOf<String>()
        repeat(entryCount) {
            cancellation.checkDocumentCancellation()
            requireRange(bytes, cursor, CENTRAL_FIXED_BYTES)
            if (u32(bytes, cursor) != CENTRAL_SIGNATURE) documentFailure("document_zip_invalid")
            val madeBy = u16(bytes, cursor + 4)
            val flags = u16(bytes, cursor + 8)
            val method = u16(bytes, cursor + 10)
            val compressed = u32(bytes, cursor + 20)
            val expanded = u32(bytes, cursor + 24)
            val nameLength = u16(bytes, cursor + 28)
            val extraLength = u16(bytes, cursor + 30)
            val entryCommentLength = u16(bytes, cursor + 32)
            val diskStart = u16(bytes, cursor + 34)
            val externalAttributes = u32(bytes, cursor + 38)
            val localOffset = u32(bytes, cursor + 42)
            if (flags and 0x1 != 0) documentFailure("document_zip_encryption_unsupported")
            if (method !in setOf(ZipEntry.STORED, ZipEntry.DEFLATED)) {
                documentFailure("document_zip_compression_unsupported")
            }
            if (compressed == 0xffffffffL || expanded == 0xffffffffL || localOffset == 0xffffffffL) {
                documentFailure("document_zip64_unsupported")
            }
            if (diskStart != 0) documentFailure("document_zip_multidisk_unsupported")
            val variableLength = Math.addExact(Math.addExact(nameLength, extraLength), entryCommentLength)
            requireRange(bytes, cursor + CENTRAL_FIXED_BYTES, variableLength)
            val rawName = bytes.copyOfRange(
                cursor + CENTRAL_FIXED_BYTES,
                cursor + CENTRAL_FIXED_BYTES + nameLength,
            )
            val name = decodeZipName(rawName, flags)
            val directory = name.endsWith('/')
            validateZipPath(name, directory)
            if (!exactNames.add(name) || !foldedNames.add(name.lowercase(Locale.ROOT))) {
                documentFailure("document_zip_duplicate_entry")
            }
            val host = madeBy ushr 8
            if (host == UNIX_HOST) {
                val mode = (externalAttributes ushr 16).toInt() and 0xffff
                val kind = mode and UNIX_FILE_TYPE_MASK
                if (kind == UNIX_SYMLINK) documentFailure("document_zip_symlink_entry")
                if (kind != 0 && kind != UNIX_REGULAR && kind != UNIX_DIRECTORY) {
                    documentFailure("document_zip_special_entry")
                }
                if (kind == UNIX_DIRECTORY && !directory) documentFailure("document_zip_directory_mismatch")
                if (kind == UNIX_REGULAR && directory) documentFailure("document_zip_directory_mismatch")
            }
            if (!directory && expanded > limits.maxZipEntryBytes) {
                documentFailure("document_zip_entry_size_limit")
            }
            if (
                !directory &&
                expanded > limits.compressionRatioGraceBytes &&
                (compressed == 0L || expanded > compressed * limits.maxCompressionRatio.toLong())
            ) {
                documentFailure("document_zip_compression_ratio_limit")
            }
            entries += SafeZipEntryMetadata(name, compressed, expanded, directory)
            cursor = Math.addExact(cursor, CENTRAL_FIXED_BYTES + variableLength)
        }
        if (cursor.toLong() != centralEnd) documentFailure("document_zip_invalid")
        return entries
    }

    private fun findEndOfCentralDirectory(bytes: ByteArray): Int {
        val minimum = maxOf(0, bytes.size - MAX_EOCD_SEARCH_BYTES)
        for (index in bytes.size - EOCD_MIN_BYTES downTo minimum) {
            if (u32(bytes, index) == EOCD_SIGNATURE) return index
        }
        documentFailure("document_zip_invalid")
    }

    private fun readZipEntry(
        input: ZipInputStream,
        limits: DocumentLimits,
        cancellation: DocumentCancellation,
        observed: (Int) -> Unit,
    ): ByteArray {
        val output = ByteArrayOutputStream(minOf(8 * 1024, limits.maxZipEntryBytes))
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            cancellation.checkDocumentCancellation()
            val count = runCatching { input.read(buffer) }.getOrElse {
                documentFailure("document_zip_invalid")
            }
            if (count < 0) break
            if (count == 0) continue
            if (output.size() > limits.maxZipEntryBytes - count) {
                documentFailure("document_zip_entry_size_limit")
            }
            output.write(buffer, 0, count)
            observed(count)
        }
        return output.toByteArray()
    }

    private fun validateZipPath(rawName: String, directory: Boolean): String {
        if (rawName.isEmpty() || rawName.length > MAX_ZIP_NAME_CHARACTERS) {
            documentFailure("document_zip_path_invalid")
        }
        if (Normalizer.normalize(rawName, Normalizer.Form.NFC) != rawName) {
            documentFailure("document_zip_path_not_normalized")
        }
        if (rawName.toByteArray(StandardCharsets.UTF_8).size > MAX_ZIP_NAME_UTF8_BYTES) {
            documentFailure("document_zip_path_invalid")
        }
        if (
            rawName.startsWith('/') ||
            rawName.startsWith('\\') ||
            '\\' in rawName ||
            ':' in rawName ||
            rawName.any { it == '\u0000' || it.isISOControl() }
        ) {
            documentFailure("document_zip_path_invalid")
        }
        val comparable = if (directory) rawName.removeSuffix("/") else rawName
        if (comparable.isEmpty() || (!directory && rawName.endsWith('/'))) {
            documentFailure("document_zip_path_invalid")
        }
        val segments = comparable.split('/')
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) {
            documentFailure("document_zip_path_invalid")
        }
        if (segments.any { it.toByteArray(StandardCharsets.UTF_8).size > MAX_ZIP_SEGMENT_UTF8_BYTES }) {
            documentFailure("document_zip_path_invalid")
        }
        return rawName
    }

    private fun decodeZipName(bytes: ByteArray, flags: Int): String {
        if (flags and UTF8_FLAG == 0 && bytes.any { (it.toInt() and 0xff) > 0x7f }) {
            documentFailure("document_zip_legacy_name_unsupported")
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { documentFailure("document_zip_path_invalid") }
    }

    private fun requireRange(bytes: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || offset > bytes.size - length) documentFailure("document_zip_invalid")
    }

    private fun u16(bytes: ByteArray, offset: Int): Int {
        requireRange(bytes, offset, 2)
        return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
    }

    private fun u32(bytes: ByteArray, offset: Int): Long {
        requireRange(bytes, offset, 4)
        return (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)
    }

    private const val EOCD_SIGNATURE = 0x06054b50L
    private const val CENTRAL_SIGNATURE = 0x02014b50L
    private const val EOCD_MIN_BYTES = 22
    private const val CENTRAL_FIXED_BYTES = 46
    private const val MAX_EOCD_SEARCH_BYTES = 65_557
    private const val UTF8_FLAG = 0x800
    private const val UNIX_HOST = 3
    private const val UNIX_FILE_TYPE_MASK = 0xf000
    private const val UNIX_REGULAR = 0x8000
    private const val UNIX_DIRECTORY = 0x4000
    private const val UNIX_SYMLINK = 0xa000
    private const val MAX_ZIP_NAME_CHARACTERS = 512
    private const val MAX_ZIP_NAME_UTF8_BYTES = 1_024
    private const val MAX_ZIP_SEGMENT_UTF8_BYTES = 255
}

internal data class SafeXmlElement(
    val qualifiedName: String,
    val localName: String,
    val attributes: Map<String, String>,
)

internal interface SafeXmlHandler {
    fun start(element: SafeXmlElement) = Unit
    fun text(value: String) = Unit
    fun end(element: SafeXmlElement) = Unit
}

/** Small XML tokenizer with no entity resolver, DTD support, or external-resource surface. */
internal object SafeDocumentXml {
    fun parse(
        bytes: ByteArray,
        limits: DocumentLimits,
        cancellation: DocumentCancellation,
        handler: SafeXmlHandler = object : SafeXmlHandler {},
    ) {
        if (bytes.size > limits.maxXmlBytes) documentFailure("document_xml_size_limit")
        val text = decodeUtf8(bytes)
        if (text.any { it == '\u0000' || (it.isISOControl() && it !in "\t\r\n") }) {
            documentFailure("document_xml_control_character")
        }
        val stack = ArrayDeque<SafeXmlElement>()
        var cursor = 0
        var rootSeen = false
        var rootClosed = false
        var declarationSeen = false
        while (cursor < text.length) {
            cancellation.checkDocumentCancellation()
            val markup = text.indexOf('<', cursor)
            if (markup < 0) {
                emitXmlText(text.substring(cursor), handler, stack.isNotEmpty())
                cursor = text.length
                break
            }
            if (markup > cursor) emitXmlText(text.substring(cursor, markup), handler, stack.isNotEmpty())
            when {
                text.startsWith("<!--", markup) -> {
                    val end = text.indexOf("-->", markup + 4)
                    if (end < 0 || text.substring(markup + 4, end).contains("--")) {
                        documentFailure("document_xml_invalid")
                    }
                    cursor = end + 3
                }
                text.startsWith("<![CDATA[", markup) -> {
                    if (stack.isEmpty()) documentFailure("document_xml_invalid")
                    val end = text.indexOf("]]>", markup + 9)
                    if (end < 0) documentFailure("document_xml_invalid")
                    handler.text(text.substring(markup + 9, end))
                    cursor = end + 3
                }
                text.startsWith("<?", markup) -> {
                    val end = text.indexOf("?>", markup + 2)
                    if (end < 0) documentFailure("document_xml_invalid")
                    val instruction = text.substring(markup + 2, end).trim()
                    if (
                        rootSeen ||
                        declarationSeen ||
                        text.substring(0, markup).isNotBlank() ||
                        !instruction.matches(Regex("(?is)xml(?:\\s+.*)?"))
                    ) {
                        documentFailure("document_xml_processing_instruction")
                    }
                    declarationSeen = true
                    val declaredEncoding = Regex("(?i)\\bencoding\\s*=\\s*(['\"])([^'\"]+)\\1")
                        .find(instruction)?.groupValues?.get(2)
                    if (declaredEncoding != null && !declaredEncoding.equals("UTF-8", ignoreCase = true)) {
                        documentFailure("document_xml_encoding_unsupported")
                    }
                    cursor = end + 2
                }
                text.startsWith("<!", markup) -> documentFailure("document_xml_declaration_forbidden")
                text.startsWith("</", markup) -> {
                    val end = findMarkupEnd(text, markup + 2)
                    val raw = text.substring(markup + 2, end).trim()
                    if (!XML_NAME.matches(raw)) documentFailure("document_xml_invalid")
                    val element = stack.removeLastOrNull() ?: documentFailure("document_xml_invalid")
                    if (element.qualifiedName != raw) documentFailure("document_xml_invalid")
                    handler.end(element)
                    if (stack.isEmpty()) rootClosed = true
                    cursor = end + 1
                }
                else -> {
                    if (rootClosed) documentFailure("document_xml_invalid")
                    val end = findMarkupEnd(text, markup + 1)
                    var raw = text.substring(markup + 1, end).trim()
                    val selfClosing = raw.endsWith('/')
                    if (selfClosing) raw = raw.dropLast(1).trimEnd()
                    val element = parseStartTag(raw)
                    rootSeen = true
                    if (stack.size >= limits.maxXmlDepth) documentFailure("document_xml_depth_limit")
                    handler.start(element)
                    if (selfClosing) {
                        handler.end(element)
                        if (stack.isEmpty()) rootClosed = true
                    } else {
                        stack.addLast(element)
                    }
                    cursor = end + 1
                }
            }
        }
        if (stack.isNotEmpty() || !rootSeen) documentFailure("document_xml_invalid")
    }

    private fun parseStartTag(raw: String): SafeXmlElement {
        var cursor = 0
        while (cursor < raw.length && !raw[cursor].isWhitespace()) cursor++
        val name = raw.substring(0, cursor)
        if (!XML_NAME.matches(name)) documentFailure("document_xml_invalid")
        val attributes = linkedMapOf<String, String>()
        while (cursor < raw.length) {
            while (cursor < raw.length && raw[cursor].isWhitespace()) cursor++
            if (cursor >= raw.length) break
            val start = cursor
            while (cursor < raw.length && !raw[cursor].isWhitespace() && raw[cursor] != '=') cursor++
            val attributeName = raw.substring(start, cursor)
            if (!XML_NAME.matches(attributeName)) documentFailure("document_xml_invalid")
            while (cursor < raw.length && raw[cursor].isWhitespace()) cursor++
            if (cursor >= raw.length || raw[cursor] != '=') documentFailure("document_xml_invalid")
            cursor++
            while (cursor < raw.length && raw[cursor].isWhitespace()) cursor++
            if (cursor >= raw.length || raw[cursor] !in setOf('\'', '"')) documentFailure("document_xml_invalid")
            val quote = raw[cursor++]
            val valueStart = cursor
            while (cursor < raw.length && raw[cursor] != quote) cursor++
            if (cursor >= raw.length) documentFailure("document_xml_invalid")
            val value = decodeEntities(raw.substring(valueStart, cursor))
            cursor++
            if (attributes.put(attributeName, value) != null) documentFailure("document_xml_duplicate_attribute")
            if (attributes.size > MAX_XML_ATTRIBUTES) documentFailure("document_xml_attribute_limit")
        }
        return SafeXmlElement(name, name.substringAfterLast(':'), attributes)
    }

    private fun findMarkupEnd(text: String, start: Int): Int {
        var quote: Char? = null
        for (index in start until text.length) {
            val character = text[index]
            if (quote == null && character in setOf('\'', '"')) quote = character
            else if (quote == character) quote = null
            else if (quote == null && character == '>') return index
        }
        documentFailure("document_xml_invalid")
    }

    private fun emitXmlText(raw: String, handler: SafeXmlHandler, insideRoot: Boolean) {
        if (raw.isEmpty()) return
        val decoded = decodeEntities(raw)
        if (!insideRoot && decoded.isNotBlank()) documentFailure("document_xml_invalid")
        if (insideRoot) handler.text(decoded)
    }

    private fun decodeEntities(raw: String): String {
        if ('&' !in raw) return raw
        val output = StringBuilder(raw.length)
        var cursor = 0
        while (cursor < raw.length) {
            if (raw[cursor] != '&') {
                output.append(raw[cursor++])
                continue
            }
            val end = raw.indexOf(';', cursor + 1)
            if (end < 0 || end - cursor > MAX_ENTITY_CHARACTERS) documentFailure("document_xml_entity_invalid")
            val entity = raw.substring(cursor + 1, end)
            when (entity) {
                "amp" -> output.append('&')
                "lt" -> output.append('<')
                "gt" -> output.append('>')
                "quot" -> output.append('"')
                "apos" -> output.append('\'')
                else -> {
                    val codePoint = when {
                        entity.startsWith("#x", ignoreCase = true) -> entity.drop(2).toIntOrNull(16)
                        entity.startsWith('#') -> entity.drop(1).toIntOrNull(10)
                        else -> null
                    } ?: documentFailure("document_xml_entity_forbidden")
                    if (!isAllowedXmlCodePoint(codePoint)) documentFailure("document_xml_entity_invalid")
                    output.appendCodePoint(codePoint)
                }
            }
            cursor = end + 1
        }
        return output.toString()
    }

    private fun decodeUtf8(bytes: ByteArray): String {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { documentFailure("document_xml_utf8_invalid") }
            .removePrefix("\ufeff")
    }

    private fun isAllowedXmlCodePoint(value: Int): Boolean =
        value == 0x9 || value == 0xa || value == 0xd ||
            value in 0x20..0xd7ff || value in 0xe000..0xfffd || value in 0x10000..0x10ffff

    private val XML_NAME = Regex("[A-Za-z_][A-Za-z0-9_.:-]{0,255}")
    private const val MAX_XML_ATTRIBUTES = 128
    private const val MAX_ENTITY_CHARACTERS = 16
}

internal fun xmlEscape(value: String): String = buildString(value.length) {
    value.forEach { character ->
        when (character) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> {
                if (character == '\u0000' || (character.isISOControl() && character !in "\t\r\n")) {
                    documentFailure("document_text_control_character")
                }
                append(character)
            }
        }
    }
}
