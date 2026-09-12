package ai.hans.standard.ui

import ai.hans.standard.notifications.isStrictNotificationJsonObject
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import org.json.JSONObject

internal data class ThirdPartyNoticeText(val label: String, val asset: String, val sha256: String)
internal data class ThirdPartyNoticeComponent(val id: String, val label: String, val texts: List<ThirdPartyNoticeText>)

/** Host-owned, immutable assets only; callers perform every read on a worker dispatcher. */
internal class ThirdPartyNoticesLoader(private val open: (String) -> InputStream) {
    fun loadIndex(): List<ThirdPartyNoticeComponent> {
        val input = utf8(readBounded(INDEX, MAX_INDEX_BYTES))
        require(isStrictNotificationJsonObject(input))
        val root = JSONObject(input)
        require(root.keys().asSequence().toSet() == setOf("schema", "components"))
        require(root.get("schema") == "hans.third-party-notices.v1")
        val entries = root.getJSONArray("components")
        require(entries.length() in 1..2048)
        val ids = mutableSetOf<String>()
        return List(entries.length()) { index ->
            val entry = entries.getJSONObject(index)
            require(entry.keys().asSequence().toSet() == setOf("id", "label", "texts"))
            val id = string(entry, "id")
            require(Regex("[a-zA-Z0-9._:-]{1,160}").matches(id) && ids.add(id))
            val texts = entry.getJSONArray("texts")
            require(texts.length() in 1..16)
            val assets = mutableSetOf<String>()
            ThirdPartyNoticeComponent(id, label(entry), List(texts.length()) { textIndex ->
                val text = texts.getJSONObject(textIndex)
                require(text.keys().asSequence().toSet() == setOf("label", "asset", "sha256"))
                val asset = string(text, "asset")
                require(validAsset(asset) && assets.add(asset))
                val hash = string(text, "sha256")
                require(Regex("[0-9a-f]{64}").matches(hash))
                ThirdPartyNoticeText(label(text), asset, hash)
            })
        }
    }

    fun loadText(text: ThirdPartyNoticeText): String {
        require(validAsset(text.asset))
        val raw = readBounded(text.asset, MAX_TEXT_BYTES)
        val hash = MessageDigest.getInstance("SHA-256").digest(raw)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        require(hash == text.sha256)
        return utf8(raw) // Preserve the exact source text, including line endings and whitespace.
    }

    private fun readBounded(asset: String, maximum: Int): ByteArray = open(asset).use { stream ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) break
            require(count > 0 && output.size() <= maximum - count)
            output.write(buffer, 0, count)
        }
        require(output.size() > 0)
        output.toByteArray()
    }

    private fun string(json: JSONObject, key: String): String = json.get(key).let {
        require(it is String)
        it
    }

    private fun label(json: JSONObject): String = string(json, "label").also {
        require(it.isNotBlank() && it.length <= 240 && it.none { c -> c.isISOControl() })
        Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .encode(java.nio.CharBuffer.wrap(it))
    }

    companion object {
        const val INDEX = "hans/licenses/index.json"
        const val MAX_INDEX_BYTES = 2 * 1024 * 1024
        const val MAX_TEXT_BYTES = 1024 * 1024
        fun validAsset(path: String): Boolean = path.startsWith("hans/licenses/") &&
            path.length <= 240 && path.endsWith(".txt") && path.split('/').let { parts ->
                parts.size in 3..8 && parts.all { Regex("[a-zA-Z0-9_-][a-zA-Z0-9_.-]*").matches(it) && it != "." && it != ".." }
            }
        private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    }
}
