package ai.hans.standard.documents

import ai.hans.standard.artifacts.ArtifactHandle
import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolCancellation
import ai.hans.standard.codex.DynamicToolExecutionGate
import ai.hans.standard.codex.DynamicToolExecutionHandle
import ai.hans.standard.codex.DynamicToolExecutionResult
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.DynamicToolFunctionSpec
import ai.hans.standard.codex.DynamicToolNamespaceSpec
import ai.hans.standard.codex.JsonContract
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.Executor
import org.json.JSONArray
import org.json.JSONObject

object DocumentDynamicToolCatalog {
    const val NAMESPACE = "hans_documents"

    val namespace = DynamicToolNamespaceSpec(
        name = NAMESPACE,
        description =
            "Bounded local PDF and OOXML document tools. Inputs and outputs use opaque artifact handles; " +
                "no Android path, SAF URI, binary payload, macro, embedded object, or external relationship is exposed.",
        tools = listOf(
            function(
                "inspect_document",
                "Validate a PDF, DOCX, XLSX, or PPTX artifact and return deterministic safe metadata.",
                JSONObject().put("artifactHandle", string(68)),
                listOf("artifactHandle"),
            ),
            function(
                "extract_document_text",
                "Extract bounded deterministic plain text from a validated PDF, DOCX, XLSX, or PPTX artifact.",
                JSONObject()
                    .put("artifactHandle", string(68))
                    .put("maxCharacters", integer(1, MAX_DYNAMIC_TEXT_CHARACTERS.toLong())),
                listOf("artifactHandle"),
            ),
            function(
                "create_pdf",
                "Create a basic deterministic PDF artifact from a title and paragraphs. Complex layout and unsupported characters are rejected explicitly.",
                textDocumentProperties("pdf"),
                listOf("displayName", "title", "paragraphs"),
            ),
            function(
                "create_docx",
                "Create a basic deterministic DOCX artifact from a title and paragraphs.",
                textDocumentProperties("docx"),
                listOf("displayName", "title", "paragraphs"),
            ),
            function(
                "create_xlsx",
                "Create a basic deterministic single-sheet XLSX artifact from bounded text cells.",
                JSONObject()
                    .put("displayName", fileName("xlsx"))
                    .put("sheetName", string(MAX_TITLE_CHARACTERS))
                    .put(
                        "rows",
                        JSONObject()
                            .put("type", "array")
                            .put("maxItems", MAX_ROWS)
                            .put(
                                "items",
                                JSONObject()
                                    .put("type", "array")
                                    .put("maxItems", MAX_COLUMNS)
                                    .put("items", JSONObject().put("type", "string").put("maxLength", MAX_CELL_CHARACTERS)),
                            ),
                    ),
                listOf("displayName", "sheetName", "rows"),
            ),
            function(
                "create_pptx",
                "Create a basic deterministic PPTX artifact from bounded title-and-body slides.",
                JSONObject()
                    .put("displayName", fileName("pptx"))
                    .put(
                        "slides",
                        JSONObject()
                            .put("type", "array")
                            .put("minItems", 1)
                            .put("maxItems", MAX_SLIDES)
                            .put(
                                "items",
                                JSONObject()
                                    .put("type", "object")
                                    .put(
                                        "properties",
                                        JSONObject()
                                            .put("title", JSONObject().put("type", "string").put("maxLength", MAX_TITLE_CHARACTERS))
                                            .put(
                                                "body",
                                                JSONObject()
                                                    .put("type", "array")
                                                    .put("maxItems", MAX_PARAGRAPHS)
                                                    .put("items", JSONObject().put("type", "string").put("maxLength", MAX_PARAGRAPH_CHARACTERS)),
                                            ),
                                    )
                                    .put("required", JSONArray(listOf("title", "body")))
                                    .put("additionalProperties", false),
                            ),
                    ),
                listOf("displayName", "slides"),
            ),
        ),
    )

    val toolNames: Set<String> = namespace.tools.mapTo(linkedSetOf(), DynamicToolFunctionSpec::name)

    private fun textDocumentProperties(extension: String) = JSONObject()
        .put("displayName", fileName(extension))
        .put("title", JSONObject().put("type", "string").put("maxLength", MAX_TITLE_CHARACTERS))
        .put(
            "paragraphs",
            JSONObject()
                .put("type", "array")
                .put("maxItems", MAX_PARAGRAPHS)
                .put("items", JSONObject().put("type", "string").put("maxLength", MAX_PARAGRAPH_CHARACTERS)),
        )

    private fun fileName(extension: String) = JSONObject()
        .put("type", "string")
        .put("minLength", extension.length + 2)
        .put("maxLength", 255)
        .put("pattern", "^.{1,250}\\.${extension}$")

    private fun string(maxLength: Int) = JSONObject()
        .put("type", "string")
        .put("minLength", 1)
        .put("maxLength", maxLength)

    private fun integer(minimum: Long, maximum: Long) = JSONObject()
        .put("type", "integer")
        .put("minimum", minimum)
        .put("maximum", maximum)

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        required: List<String>,
    ) = DynamicToolFunctionSpec(
        name,
        description,
        JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(required))
            .put("additionalProperties", false)
            .toString(),
    )

    internal const val MAX_DYNAMIC_TEXT_CHARACTERS = 96 * 1024
    internal const val MAX_TITLE_CHARACTERS = 2_000
    internal const val MAX_PARAGRAPHS = 2_000
    internal const val MAX_PARAGRAPH_CHARACTERS = 64 * 1024
    internal const val MAX_ROWS = 2_000
    internal const val MAX_COLUMNS = 512
    internal const val MAX_CELL_CHARACTERS = 32 * 1024
    internal const val MAX_SLIDES = 2_000
}

class DocumentDynamicToolExecutor(
    private val adapter: DocumentArtifactAdapter,
    private val backgroundExecutor: Executor,
) : DynamicToolExecutor {
    override val specs = listOf(DocumentDynamicToolCatalog.namespace)

    override fun execute(
        call: DynamicToolCallParams,
        completion: (DynamicToolExecutionResult) -> Unit,
    ) {
        executeCancellable(call, DynamicToolCancellation.NONE, completion)
    }

    override fun executeCancellable(
        call: DynamicToolCallParams,
        cancellation: DynamicToolCancellation,
        completion: (DynamicToolExecutionResult) -> Unit,
    ): DynamicToolExecutionHandle {
        val gate = DynamicToolExecutionGate(cancellation, completion)
        val scheduled = gate.schedule(backgroundExecutor) {
            val result = runCatching { executeSafely(call, gate) }
                .getOrElse { failureResult(call, it.documentFailureCode()) }
            gate.complete(result)
        }
        if (!scheduled) gate.complete(failureResult(call, "document_executor_rejected"))
        return gate
    }

    override fun failureResult(call: DynamicToolCallParams, code: String): DynamicToolExecutionResult = result(
        JSONObject()
            .put("status", "failed")
            .put("errorCode", code.takeIf(SAFE_ERROR_CODE::matches) ?: "document_tool_failed"),
        false,
    )

    private fun executeSafely(
        call: DynamicToolCallParams,
        gate: DynamicToolExecutionGate,
    ): DynamicToolExecutionResult {
        if (call.namespace != DocumentDynamicToolCatalog.NAMESPACE) {
            return failureResult(call, "unknown_document_namespace")
        }
        if (call.tool !in DocumentDynamicToolCatalog.toolNames) {
            return failureResult(call, "unknown_document_tool")
        }
        val arguments = JsonContract.parseObject(call.argumentsJson, MAX_ARGUMENT_BYTES)
        val cancellation = DocumentCancellation(gate::isCancellationRequested)
        return when (call.tool) {
            "inspect_document" -> {
                arguments.requireOnly(setOf("artifactHandle"))
                val handle = ArtifactHandle(arguments.requiredString("artifactHandle", 68))
                if (!gate.markExternalEffectStarted()) return cancelled(call)
                result(adapter.inspect(handle, cancellation).toJson(), true)
            }
            "extract_document_text" -> {
                arguments.requireOnly(setOf("artifactHandle", "maxCharacters"))
                val handle = ArtifactHandle(arguments.requiredString("artifactHandle", 68))
                val maxCharacters = arguments.optionalInt(
                    "maxCharacters",
                    DocumentDynamicToolCatalog.MAX_DYNAMIC_TEXT_CHARACTERS,
                    1..DocumentDynamicToolCatalog.MAX_DYNAMIC_TEXT_CHARACTERS,
                )
                if (!gate.markExternalEffectStarted()) return cancelled(call)
                result(adapter.extractText(handle, maxCharacters, cancellation).toJson(), true)
            }
            "create_pdf", "create_docx" -> {
                arguments.requireOnly(setOf("displayName", "title", "paragraphs"))
                val displayName = arguments.requiredString("displayName", 255)
                val title = arguments.requiredText("title", DocumentDynamicToolCatalog.MAX_TITLE_CHARACTERS)
                val paragraphs = arguments.requiredStringArray(
                    "paragraphs",
                    DocumentDynamicToolCatalog.MAX_PARAGRAPHS,
                    DocumentDynamicToolCatalog.MAX_PARAGRAPH_CHARACTERS,
                )
                if (!gate.markExternalEffectStarted()) return cancelled(call)
                val created = if (call.tool == "create_pdf") {
                    adapter.createPdf(displayName, title, paragraphs, cancellation)
                } else {
                    adapter.createDocx(displayName, title, paragraphs, cancellation)
                }
                result(created.toJson(), true)
            }
            "create_xlsx" -> {
                arguments.requireOnly(setOf("displayName", "sheetName", "rows"))
                val displayName = arguments.requiredString("displayName", 255)
                val sheetName = arguments.requiredString("sheetName", DocumentDynamicToolCatalog.MAX_TITLE_CHARACTERS)
                val rows = arguments.requiredRows()
                if (!gate.markExternalEffectStarted()) return cancelled(call)
                result(adapter.createXlsx(displayName, sheetName, rows, cancellation).toJson(), true)
            }
            "create_pptx" -> {
                arguments.requireOnly(setOf("displayName", "slides"))
                val displayName = arguments.requiredString("displayName", 255)
                val slides = arguments.requiredSlides()
                if (!gate.markExternalEffectStarted()) return cancelled(call)
                result(adapter.createPptx(displayName, slides, cancellation).toJson(), true)
            }
            else -> failureResult(call, "unknown_document_tool")
        }
    }

    private fun DocumentInspection.toJson() = JSONObject()
        .put("status", "ok")
        .put("artifactHandle", artifactHandle.value)
        .put("format", format.name.lowercase(Locale.ROOT))
        .put("mimeType", format.mimeType)
        .put("byteCount", byteCount)
        .put("sha256", sha256)
        .put("unitKind", unitKind)
        .put("unitCount", unitCount)
        .put("textCharacterCount", textCharacterCount)
        .put("limitations", JSONArray(limitations))

    private fun DocumentTextExtraction.toJson() = JSONObject()
        .put("status", "ok")
        .put("artifactHandle", artifactHandle.value)
        .put("format", format.name.lowercase(Locale.ROOT))
        .put("text", text)
        .put("truncated", truncated)
        .put("limitations", JSONArray(limitations))

    private fun CreatedDocument.toJson() = JSONObject()
        .put("status", "ok")
        .put("artifactHandle", artifactHandle.value)
        .put("format", format.name.lowercase(Locale.ROOT))
        .put("mimeType", format.mimeType)
        .put("displayName", displayName)
        .put("byteCount", byteCount)
        .put("sha256", sha256)
        .put("unitCount", unitCount)

    private fun JSONObject.requireOnly(allowed: Set<String>) {
        if (!keys().asSequence().all { it in allowed }) documentFailure("document_argument_unknown")
    }

    private fun JSONObject.requiredString(key: String, maxCharacters: Int): String {
        val value = opt(key) as? String ?: documentFailure("document_argument_missing")
        if (value.isBlank() || value.length > maxCharacters || value.any(Char::isISOControl)) {
            documentFailure("document_argument_invalid")
        }
        return value
    }

    private fun JSONObject.requiredText(key: String, maxCharacters: Int): String {
        val value = opt(key) as? String ?: documentFailure("document_argument_missing")
        if (value.length > maxCharacters || '\u0000' in value) documentFailure("document_argument_invalid")
        return value
    }

    private fun JSONObject.optionalInt(key: String, default: Int, range: IntRange): Int {
        if (!has(key)) return default
        val value = when (val raw = opt(key)) {
            is Int -> raw
            is Long -> raw.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
            else -> null
        } ?: documentFailure("document_argument_invalid")
        if (value !in range) documentFailure("document_argument_invalid")
        return value
    }

    private fun JSONObject.requiredStringArray(
        key: String,
        maxItems: Int,
        maxCharacters: Int,
    ): List<String> {
        val array = opt(key) as? JSONArray ?: documentFailure("document_argument_missing")
        if (array.length() > maxItems) documentFailure("document_argument_invalid")
        return List(array.length()) { index ->
            (array.opt(index) as? String)?.takeIf { it.length <= maxCharacters && '\u0000' !in it }
                ?: documentFailure("document_argument_invalid")
        }
    }

    private fun JSONObject.requiredRows(): List<List<String>> {
        val rows = opt("rows") as? JSONArray ?: documentFailure("document_argument_missing")
        if (rows.length() > DocumentDynamicToolCatalog.MAX_ROWS) documentFailure("document_argument_invalid")
        return List(rows.length()) { rowIndex ->
            val row = rows.opt(rowIndex) as? JSONArray ?: documentFailure("document_argument_invalid")
            if (row.length() > DocumentDynamicToolCatalog.MAX_COLUMNS) documentFailure("document_argument_invalid")
            List(row.length()) { columnIndex ->
                (row.opt(columnIndex) as? String)?.takeIf {
                    it.length <= DocumentDynamicToolCatalog.MAX_CELL_CHARACTERS && '\u0000' !in it
                } ?: documentFailure("document_argument_invalid")
            }
        }
    }

    private fun JSONObject.requiredSlides(): List<PresentationSlide> {
        val array = opt("slides") as? JSONArray ?: documentFailure("document_argument_missing")
        if (array.length() !in 1..DocumentDynamicToolCatalog.MAX_SLIDES) documentFailure("document_argument_invalid")
        return List(array.length()) { index ->
            val slide = array.opt(index) as? JSONObject ?: documentFailure("document_argument_invalid")
            slide.requireOnly(setOf("title", "body"))
            PresentationSlide(
                slide.requiredText("title", DocumentDynamicToolCatalog.MAX_TITLE_CHARACTERS),
                slide.requiredStringArray(
                    "body",
                    DocumentDynamicToolCatalog.MAX_PARAGRAPHS,
                    DocumentDynamicToolCatalog.MAX_PARAGRAPH_CHARACTERS,
                ),
            )
        }
    }

    private fun Throwable.documentFailureCode(): String = when (this) {
        is DocumentContractException -> code
        is IllegalArgumentException -> "invalid_document_arguments"
        is IllegalStateException -> "document_artifact_integrity_failure"
        else -> "document_tool_failed"
    }

    private fun cancelled(call: DynamicToolCallParams) = failureResult(call, "document_operation_cancelled")

    private fun result(value: JSONObject, success: Boolean): DynamicToolExecutionResult {
        val text = value.toString()
        if (text.toByteArray(StandardCharsets.UTF_8).size > MAX_RESULT_BYTES) {
            documentFailure("document_result_size_limit")
        }
        return DynamicToolExecutionResult(text, success)
    }

    private companion object {
        const val MAX_ARGUMENT_BYTES = 512 * 1024
        const val MAX_RESULT_BYTES = 512 * 1024
        val SAFE_ERROR_CODE = Regex("[a-z0-9_.:-]{1,96}")
    }
}
