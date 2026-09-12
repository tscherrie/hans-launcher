package ai.hans.standard.ui

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ThirdPartyNoticesTest {
    private val original = "Copyright © Example\r\n\r\n  Original text.\n"
    private val path = "hans/licenses/example-LICENSE.txt"
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun index(): JSONObject = JSONObject().put("schema", "hans.third-party-notices.v1")
        .put("components", JSONArray().put(JSONObject().put("id", "example:1").put("label", "Example 1")
            .put("texts", JSONArray().put(JSONObject().put("label", "LICENSE").put("asset", path)
                .put("sha256", hash(original.toByteArray()))))))
    private fun loader(json: String = index().toString(), bytes: ByteArray = original.toByteArray()) =
        ThirdPartyNoticesLoader { ByteArrayInputStream(if (it == ThirdPartyNoticesLoader.INDEX) json.toByteArray() else bytes) }
    private fun reject(block: () -> Unit) {
        try { block(); fail("Expected invalid index/text to fail") } catch (_: Exception) { }
    }

    @Test fun exactTextAndLineEndingsArePreserved() {
        val loader = loader()
        assertEquals(original, loader.loadText(loader.loadIndex().single().texts.single()))
    }
    @Test fun traversalAbsolutePathsAndDeepPathsRejected() {
        for (asset in listOf("../secret.txt", "/hans/licenses/a.txt", "hans/licenses/../a.txt",
            "hans/licenses/a\\b.txt", "hans/licenses/a/b/c/d/e/f/g.txt", "https://example.org/a.txt")) {
            val json = index()
            json.getJSONArray("components").getJSONObject(0).getJSONArray("texts").getJSONObject(0).put("asset", asset)
            reject { loader(json.toString()).loadIndex() }
        }
    }
    @Test fun duplicateIdsAndUnknownFieldsRejected() {
        val json = index()
        json.getJSONArray("components").put(json.getJSONArray("components").getJSONObject(0))
        reject { loader(json.toString()).loadIndex() }
        reject { loader(index().put("publicReady", true).toString()).loadIndex() }
    }
    @Test fun strictDuplicateKeysTrailingJsonAndVersionRejected() {
        reject { loader(index().toString().replace("\"schema\":", "\"schema\":\"wrong\",\"schema\":")).loadIndex() }
        reject { loader(index().toString() + " trailing").loadIndex() }
        reject { loader(index().put("schema", "v2").toString()).loadIndex() }
    }
    @Test fun textDigestAndUtf8AreChecked() {
        val reference = loader().loadIndex().single().texts.single()
        reject { loader(bytes = "tampered".toByteArray()).loadText(reference) }
        val malformed = byteArrayOf(0xC3.toByte(), 0x28)
        reject { loader(bytes = malformed).loadText(reference.copy(sha256 = hash(malformed))) }
    }
    @Test fun byteBoundsFailClosed() {
        reject { loader(" ".repeat(ThirdPartyNoticesLoader.MAX_INDEX_BYTES + 1)).loadIndex() }
        val bytes = ByteArray(ThirdPartyNoticesLoader.MAX_TEXT_BYTES + 1) { 65 }
        val reference = loader().loadIndex().single().texts.single().copy(sha256 = hash(bytes))
        reject { loader(bytes = bytes).loadText(reference) }
    }
    @Test fun labelsMustBeStringsWithoutControlsOrInvalidSurrogates() {
        for (label in listOf<Any>(true, JSONObject.NULL, "x\n")) {
            val json = index()
            json.getJSONArray("components").getJSONObject(0).put("label", label)
            reject { loader(json.toString()).loadIndex() }
        }
        // A literal unpaired surrogate becomes '?' during UTF-8 encoding. Keep the
        // malformed label as an ASCII JSON escape so the loader really receives it.
        for ((escaped, expected) in listOf("\\uD800" to '\uD800', "\\uDC00" to '\uDC00')) {
            val json = index().toString().replace("\"Example 1\"", "\"$escaped\"")
            val wireLabel = JSONObject(json.toByteArray().toString(Charsets.UTF_8))
                .getJSONArray("components").getJSONObject(0).getString("label")
            assertEquals(expected, wireLabel.single())
            reject { loader(json).loadIndex() }
        }
    }
    @Test fun everyOpenedStreamIsClosedEvenOnFailure() {
        var closed = false
        val loader = ThirdPartyNoticesLoader { object : ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { closed = true; super.close() }
        } }
        reject { loader.loadIndex() }
        assertTrue(closed)
    }
}
