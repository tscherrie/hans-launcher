package ai.hans.standard.voice.stt.android

import ai.hans.standard.profile.AtomicFileUserProfileStorage
import ai.hans.standard.voice.stt.SttTranscriptionContext
import ai.hans.standard.voice.stt.SttTranscriptionContextSource
import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads only user-confirmed profile data and Hans' app-private spelling glossary.
 *
 * Both reads fail closed. A damaged optional hint must never prevent a dictation from starting.
 */
class AndroidSttTranscriptionContextSource(
    context: Context,
    private val profileSummary: () -> String? = {
        AtomicFileUserProfileStorage(context.applicationContext).read().confirmedSummary
    },
    private val glossary: ConfirmedSttGlossary =
        AtomicFileConfirmedSttGlossary(context.applicationContext),
) : SttTranscriptionContextSource {
    override fun snapshot(): SttTranscriptionContext = SttTranscriptionContext(
        confirmedProfileSummary = runCatching(profileSummary).getOrNull(),
        confirmedGlossaryTerms = runCatching(glossary::readConfirmedTerms).getOrDefault(emptyList()),
    )
}

/** Only explicitly confirmed spellings may cross this storage boundary. */
interface ConfirmedSttGlossary {
    fun readConfirmedTerms(): List<String>

    fun replaceConfirmedTerms(terms: List<String>, explicitUserConfirmation: Boolean)
}

/**
 * Single policy for both the editor and durable storage.
 *
 * The limits deliberately match [ai.hans.standard.voice.stt.SttTranscriptionPromptBuilder], so
 * every spelling the user sees as saved can be used by the next transcription session. Terms are
 * never inferred from chats or notifications; they cross this boundary only after the user presses
 * the explicit save action.
 */
object ConfirmedSttGlossaryPolicy {
    const val MAX_TERMS =
        ai.hans.standard.voice.stt.SttTranscriptionPromptBuilder.MAX_GLOSSARY_TERMS
    const val MAX_TERM_CHARACTERS =
        ai.hans.standard.voice.stt.SttTranscriptionPromptBuilder.MAX_GLOSSARY_TERM_CHARACTERS
    const val MAX_EDITOR_CHARACTERS = MAX_TERMS * (MAX_TERM_CHARACTERS + 1)

    fun parseEditorText(raw: String): List<String> = normalize(raw.lineSequence().toList())

    fun normalize(terms: List<String>): List<String> = terms
        .asSequence()
        .map { SttTranscriptionPromptBuilderBridge.sanitize(it) }
        .filter(String::isNotBlank)
        .map { it.take(MAX_TERM_CHARACTERS).trim() }
        .filter(String::isNotBlank)
        .distinctBy { it.lowercase(Locale.ROOT) }
        .take(MAX_TERMS)
        .toList()
}

/**
 * Small, app-private and non-cloud-backed glossary. It survives same-publisher APK updates.
 */
class AtomicFileConfirmedSttGlossary(
    context: Context,
    file: File = File(context.applicationContext.noBackupFilesDir, FILE_NAME),
) : ConfirmedSttGlossary {
    private val atomicFile = AtomicFile(file)

    @Synchronized
    override fun readConfirmedTerms(): List<String> {
        if (!atomicFile.baseFile.exists()) return emptyList()
        val bytes = atomicFile.readFully()
        return try {
            require(bytes.size <= MAX_FILE_BYTES) { "stt_glossary_storage_limit" }
            decode(bytes.toString(Charsets.UTF_8))
        } finally {
            bytes.fill(0)
        }
    }

    @Synchronized
    override fun replaceConfirmedTerms(
        terms: List<String>,
        explicitUserConfirmation: Boolean,
    ) {
        require(explicitUserConfirmation) { "stt_glossary_confirmation_required" }
        val bounded = ConfirmedSttGlossaryPolicy.normalize(terms)
        val bytes = JSONObject()
            .put("version", 1)
            .put("terms", JSONArray(bounded))
            .toString()
            .toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_FILE_BYTES) { "stt_glossary_storage_limit" }
        val stream = atomicFile.startWrite()
        try {
            stream.write(bytes)
            stream.fd.sync()
            atomicFile.finishWrite(stream)
        } catch (failure: Exception) {
            atomicFile.failWrite(stream)
            throw failure
        } finally {
            bytes.fill(0)
        }
    }

    private fun decode(raw: String): List<String> {
        val root = JSONObject(raw)
        require(root.optInt("version") == 1) { "stt_glossary_storage_version" }
        val terms = root.getJSONArray("terms")
        require(terms.length() <= LEGACY_MAX_STORED_TERMS) { "stt_glossary_term_limit" }
        val decoded = buildList {
            repeat(terms.length()) { index ->
                val term = terms.getString(index)
                require(term.length <= LEGACY_MAX_STORED_TERM_CHARACTERS) {
                    "stt_glossary_term_limit"
                }
                add(term)
            }
        }
        // Version 1 briefly allowed more entries than one prompt can consume. Read those files
        // compatibly, but expose only the same bounded, cleaned set the next recording can use.
        return ConfirmedSttGlossaryPolicy.normalize(decoded)
    }

    private companion object {
        const val FILE_NAME = "hans-stt-confirmed-glossary-v1.json"
        const val MAX_FILE_BYTES = 32 * 1_024
        const val LEGACY_MAX_STORED_TERMS = 256
        const val LEGACY_MAX_STORED_TERM_CHARACTERS = 128
    }
}

/** Keeps the sanitizer implementation single-sourced without widening it outside the STT module. */
private object SttTranscriptionPromptBuilderBridge {
    fun sanitize(raw: String): String =
        ai.hans.standard.voice.stt.SttTranscriptionPromptBuilder.sanitizeSingleLine(raw)
}
