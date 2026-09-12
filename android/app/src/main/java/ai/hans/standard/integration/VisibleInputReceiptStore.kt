package ai.hans.standard.integration

import ai.hans.standard.codex.CodexInput
import ai.hans.standard.codex.ProtocolLimits
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Content-free proof that a persisted App Server user item contained text Hans previously
 * projected as a visible user bubble. App Server currently serializes trusted user text and
 * Hans-internal context as the same wire input type, so raw history is never shown without this
 * proof.
 */
data class VisibleInputReceipt(
    val threadId: String,
    val clientId: String,
    val visibleTextDigests: List<String>,
    val attachmentPlaceholder: Boolean,
) {
    init {
        require(validOpaqueId(threadId)) { "Invalid receipt thread id" }
        require(validOpaqueId(clientId)) { "Invalid receipt client id" }
        require(visibleTextDigests.size <= ProtocolLimits.MAX_RECOVERED_HISTORY_INPUT_PARTS)
        require(visibleTextDigests.all(::validDigest)) { "Invalid visible-text digest" }
        require(visibleTextDigests.isNotEmpty() || attachmentPlaceholder) {
            "Visible input receipt must prove text or a placeholder"
        }
    }

    /**
     * Returns the exact visible text only when the digest sequence has one unambiguous ordered
     * match in the persisted wire text. Internal context parts are therefore never projected.
     */
    fun recoverDisplayText(wireTextParts: List<String>): String? {
        if (visibleTextDigests.isEmpty()) {
            return if (attachmentPlaceholder) ATTACHMENT_LABEL else null
        }
        if (wireTextParts.size > ProtocolLimits.MAX_RECOVERED_HISTORY_INPUT_PARTS) return null
        val wireDigests = wireTextParts.map(::sha256)
        val earliest = IntArray(visibleTextDigests.size)
        var searchFrom = 0
        visibleTextDigests.forEachIndexed { targetIndex, target ->
            val found = (searchFrom until wireDigests.size).firstOrNull {
                wireDigests[it] == target
            } ?: return null
            earliest[targetIndex] = found
            searchFrom = found + 1
        }
        val latest = IntArray(visibleTextDigests.size)
        searchFrom = wireDigests.lastIndex
        for (targetIndex in visibleTextDigests.indices.reversed()) {
            val target = visibleTextDigests[targetIndex]
            val found = (searchFrom downTo 0).firstOrNull { wireDigests[it] == target }
                ?: return null
            latest[targetIndex] = found
            searchFrom = found - 1
        }
        if (!earliest.contentEquals(latest)) return null
        return earliest.joinToString("\n") { wireTextParts[it] }
            .takeIf(String::isNotBlank)
    }

    companion object {
        const val ATTACHMENT_LABEL = "Attachment"

        fun fromInputs(
            threadId: String,
            clientId: String,
            inputs: List<CodexInput>,
        ): VisibleInputReceipt? {
            val visibleTexts = inputs.filterIsInstance<CodexInput.Text>().map(CodexInput.Text::text)
            if (visibleTexts.size > ProtocolLimits.MAX_RECOVERED_HISTORY_INPUT_PARTS) return null
            return VisibleInputReceipt(
                threadId = threadId,
                clientId = clientId,
                visibleTextDigests = visibleTexts.map(::sha256),
                attachmentPlaceholder = visibleTexts.isEmpty(),
            )
        }

        internal fun validOpaqueId(value: String): Boolean =
            value.isNotBlank() && value.length <= ProtocolLimits.MAX_OPAQUE_ID_CHARS &&
                value.none(Char::isISOControl)

        internal fun validDigest(value: String): Boolean =
            value.length == SHA256_HEX_CHARS && value.all { it in '0'..'9' || it in 'a'..'f' }

        internal fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private const val SHA256_HEX_CHARS = 64
    }
}

interface VisibleInputReceiptStore {
    fun read(threadId: String, clientId: String): VisibleInputReceipt?
    fun record(receipt: VisibleInputReceipt)
    fun remove(threadId: String, clientId: String)
    fun clear(threadId: String? = null)

    companion object {
        val NONE: VisibleInputReceiptStore = object : VisibleInputReceiptStore {
            override fun read(threadId: String, clientId: String): VisibleInputReceipt? = null
            override fun record(receipt: VisibleInputReceipt) = Unit
            override fun remove(threadId: String, clientId: String) = Unit
            override fun clear(threadId: String?) = Unit
        }
    }
}
