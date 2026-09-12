package ai.hans.standard.codex

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.nio.charset.StandardCharsets

internal object JsonContract {
    fun parseObject(raw: String, byteLimit: Int): JSONObject {
        requireUtf8Bound(raw, byteLimit, "JSON frame")
        val parsed = try {
            JSONObject(raw)
        } catch (error: JSONException) {
            throw MalformedEnvelopeException("Malformed JSON object", error)
        }
        validateTree(parsed, depth = 0)
        return parsed
    }

    fun encodeBounded(value: JSONObject, byteLimit: Int): String {
        validateTree(value, depth = 0)
        return value.toString().also {
            requireUtf8Bound(it, byteLimit, "Encoded JSON frame")
        }
    }

    fun requireUtf8Bound(value: String, byteLimit: Int, label: String) {
        if (value.length > byteLimit) {
            throw FrameLimitException("$label exceeds $byteLimit bytes")
        }
        if (value.toByteArray(StandardCharsets.UTF_8).size > byteLimit) {
            throw FrameLimitException("$label exceeds $byteLimit bytes")
        }
    }

    fun requiredObject(parent: JSONObject, key: String): JSONObject =
        parent.opt(key) as? JSONObject
            ?: throw MalformedEnvelopeException("Expected object at '$key'")

    fun requiredArray(parent: JSONObject, key: String): JSONArray =
        parent.opt(key) as? JSONArray
            ?: throw MalformedEnvelopeException("Expected array at '$key'")

    fun requiredString(
        parent: JSONObject,
        key: String,
        maxBytes: Int = ProtocolLimits.MAX_STRING_BYTES,
        allowBlank: Boolean = false,
    ): String {
        val value = parent.opt(key) as? String
            ?: throw MalformedEnvelopeException("Expected string at '$key'")
        if (!allowBlank && value.isBlank()) {
            throw MalformedEnvelopeException("String at '$key' must not be blank")
        }
        requireUtf8Bound(value, maxBytes, "String at '$key'")
        return value
    }

    fun optionalString(
        parent: JSONObject,
        key: String,
        maxBytes: Int = ProtocolLimits.MAX_STRING_BYTES,
    ): String? {
        if (!parent.has(key) || parent.isNull(key)) return null
        return requiredString(parent, key, maxBytes)
    }

    fun requiredBoolean(parent: JSONObject, key: String): Boolean =
        parent.opt(key) as? Boolean
            ?: throw MalformedEnvelopeException("Expected boolean at '$key'")

    fun optionalBoolean(parent: JSONObject, key: String, default: Boolean): Boolean {
        if (!parent.has(key) || parent.isNull(key)) return default
        return requiredBoolean(parent, key)
    }

    fun requiredLong(parent: JSONObject, key: String): Long = when (val value = parent.opt(key)) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> throw MalformedEnvelopeException("Expected integer at '$key'")
    }

    fun optionalLong(parent: JSONObject, key: String): Long? {
        if (!parent.has(key) || parent.isNull(key)) return null
        return requiredLong(parent, key)
    }

    fun requireOnlyKeys(parent: JSONObject, allowed: Set<String>, label: String) {
        val keys = parent.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            if (key !in allowed) {
                throw MalformedEnvelopeException("Unexpected key '$key' in $label")
            }
        }
    }

    private fun validateTree(value: Any?, depth: Int) {
        if (depth > ProtocolLimits.MAX_JSON_DEPTH) {
            throw FrameLimitException("JSON nesting exceeds ${ProtocolLimits.MAX_JSON_DEPTH}")
        }
        when (value) {
            null, JSONObject.NULL, is Boolean -> Unit
            is String -> requireUtf8Bound(value, ProtocolLimits.MAX_STRING_BYTES, "JSON string")
            is Number -> {
                val number = value.toDouble()
                if (!number.isFinite()) {
                    throw MalformedEnvelopeException("JSON contains a non-finite number")
                }
            }
            is JSONObject -> {
                if (value.length() > ProtocolLimits.MAX_JSON_CONTAINER_ENTRIES) {
                    throw FrameLimitException("JSON object has too many entries")
                }
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    requireUtf8Bound(key, 1_024, "JSON key")
                    validateTree(value.opt(key), depth + 1)
                }
            }
            is JSONArray -> {
                if (value.length() > ProtocolLimits.MAX_JSON_CONTAINER_ENTRIES) {
                    throw FrameLimitException("JSON array has too many entries")
                }
                repeat(value.length()) { index ->
                    validateTree(value.opt(index), depth + 1)
                }
            }
            else -> throw MalformedEnvelopeException(
                "Unsupported JSON value type ${value::class.java.name}",
            )
        }
    }
}

internal fun JSONObject.putIfNotNull(key: String, value: Any?): JSONObject {
    if (value != null) put(key, value)
    return this
}
