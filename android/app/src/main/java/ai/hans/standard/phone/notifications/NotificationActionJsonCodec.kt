package ai.hans.standard.phone.notifications

import org.json.JSONArray
import org.json.JSONObject

internal object NotificationActionJsonCodec {
    fun encode(actions: List<NotificationActionMetadata>): String {
        val array = JSONArray()
        actions.take(NotificationLimits.MAX_ACTIONS).forEach { action ->
            val remoteInputs = JSONArray()
            action.remoteInputs
                .take(NotificationLimits.MAX_REMOTE_INPUTS_PER_ACTION)
                .forEach { input ->
                    remoteInputs.put(
                        JSONObject()
                            .put("resultKey", input.resultKey)
                            .put("label", input.label)
                            .put("choices", JSONArray(input.choices))
                            .put("allowFreeFormInput", input.allowFreeFormInput)
                            .put("allowedDataTypes", JSONArray(input.allowedDataTypes))
                            .put("editChoicesBeforeSending", input.editChoicesBeforeSending),
                    )
                }
            array.put(
                JSONObject()
                    .put("index", action.index)
                    .put("title", action.title)
                    .put("semanticAction", action.semanticAction)
                    .put("isContextual", action.isContextual)
                    .put("allowGeneratedReplies", action.allowGeneratedReplies)
                    .put("authenticationRequired", action.authenticationRequired)
                    .put("hasActionIntent", action.hasActionIntent)
                    .put("remoteInputs", remoteInputs),
            )
        }
        return array.toString()
    }

    fun decode(encoded: String): List<NotificationActionMetadata> = runCatching {
        val array = JSONArray(encoded)
        buildList {
            for (index in 0 until minOf(array.length(), NotificationLimits.MAX_ACTIONS)) {
                val action = array.optJSONObject(index) ?: continue
                val remoteArray = action.optJSONArray("remoteInputs") ?: JSONArray()
                val remoteInputs = buildList {
                    for (
                        remoteIndex in 0 until minOf(
                            remoteArray.length(),
                            NotificationLimits.MAX_REMOTE_INPUTS_PER_ACTION,
                        )
                    ) {
                        val input = remoteArray.optJSONObject(remoteIndex) ?: continue
                        add(
                            NotificationRemoteInputMetadata(
                                resultKey = input.optString("resultKey"),
                                label = input.optString("label"),
                                choices = input.optJSONArray("choices").toStringList(
                                    NotificationLimits.MAX_CHOICES_PER_REMOTE_INPUT,
                                ),
                                allowFreeFormInput = input.optBoolean("allowFreeFormInput"),
                                allowedDataTypes = input.optJSONArray("allowedDataTypes").toStringList(
                                    NotificationLimits.MAX_DATA_TYPES_PER_REMOTE_INPUT,
                                ),
                                editChoicesBeforeSending = input.optInt("editChoicesBeforeSending"),
                            ),
                        )
                    }
                }
                add(
                    NotificationActionMetadata(
                        index = action.optInt("index", index),
                        title = action.optString("title"),
                        semanticAction = action.optInt("semanticAction"),
                        isContextual = action.optBoolean("isContextual"),
                        allowGeneratedReplies = action.optBoolean("allowGeneratedReplies"),
                        authenticationRequired = action.optBoolean("authenticationRequired"),
                        hasActionIntent = action.optBoolean("hasActionIntent"),
                        remoteInputs = remoteInputs,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun JSONArray?.toStringList(limit: Int): List<String> {
        if (this == null) return emptyList()
        return buildList {
            for (index in 0 until minOf(length(), limit)) {
                val value = optString(index)
                if (value.isNotBlank()) add(value)
            }
        }
    }
}
