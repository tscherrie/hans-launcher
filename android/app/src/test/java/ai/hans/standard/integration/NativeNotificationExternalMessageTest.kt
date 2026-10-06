package ai.hans.standard.integration

import ai.hans.standard.codex.TurnStatus
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class NativeNotificationExternalMessageTest {
    @Test fun messagePinsExactUtf8PayloadAndRejectsEventOrFingerprintReplacement() {
        val json = payload("event-1").put("text", "€ 🧪").toString()
        val message = NativeNotificationExternalMessage.create("event-1", json)
        assertEquals(64, message.payloadSha256.length)
        assertThrows(IllegalArgumentException::class.java) {
            NativeNotificationExternalMessage("event-2", json, message.payloadSha256)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NativeNotificationExternalMessage("event-1", json, "0".repeat(64))
        }
    }

    @Test fun onlyNativeFunctionCallOutputWithExactToolAndNamespaceProvesIngestion() {
        val message = NativeNotificationExternalMessage.create("event-1", payload("event-1").toString())
        val native = item(message)
        val receipt = NativeNotificationExternalHistoryDecoder.item("thread-1", "turn-1", native)
        assertEquals(NativeNotificationExternalReceipt(message.eventId, message.payloadSha256, "thread-1", "turn-1"), receipt)
        for ((key, value) in listOf("type" to "agentMessage", "name" to "other_tool", "namespace" to "other_namespace")) {
            assertNull(NativeNotificationExternalHistoryDecoder.item("thread-1", "turn-1", JSONObject(native.toString()).put(key, value)))
        }
        assertNull(NativeNotificationExternalHistoryDecoder.item("thread-1", "turn-1", native.remove("namespace").let { native }))
        assertNull(NativeNotificationExternalHistoryDecoder.item("thread-1", "turn-1", JSONObject()
            .put("type", "agentMessage").put("id", "agent").put("text", item(message).toString())))
    }

    @Test fun historyRequiresFullPersistedItemsAndNeverInfersAcceptanceFromAbsence() {
        val message = NativeNotificationExternalMessage.create("event-1", payload("event-1").toString())
        val result = page(turn("turn-1", JSONArray().put(item(message))))
        val history = NativeNotificationExternalHistoryDecoder.page("thread-1", result)
        assertEquals(1, history?.receipts?.size)
        assertEquals(TurnStatus.COMPLETED, history?.turnStatuses?.get("turn-1"))
        assertNull(NativeNotificationExternalHistoryDecoder.page("thread-1", page(
            turn("turn-1", JSONArray().put(item(message))).put("itemsView", "summary"))))
        val empty = NativeNotificationExternalHistoryDecoder.page("thread-1", page(turn("turn-1", JSONArray())))
        assertTrue(empty?.receipts?.isEmpty() == true)
    }

    @Test fun contradictoryEventCorrelationOrOversizedHistoryIsNotPositiveProof() {
        val message = NativeNotificationExternalMessage.create("event-1", payload("event-1").toString())
        assertNull(NativeNotificationExternalHistoryDecoder.page("thread-1", page(
            turn("turn-1", JSONArray().put(item(message))), turn("turn-2", JSONArray().put(item(message))))))
        val oversized = JSONArray().apply { repeat(513) { put(JSONObject().put("id", "other-$it").put("type", "other")) } }
        assertNull(NativeNotificationExternalHistoryDecoder.page("thread-1", page(turn("turn-1", oversized))))
        assertNull(NativeNotificationExternalHistoryDecoder.page("thread-1", page(turn("turn-1", JSONArray()).put("status", "unknown"))))
    }

    private fun payload(eventId: String) = JSONObject().put("schema", NativeNotificationExternalMessage.SCHEMA).put("eventId", eventId)
    private fun item(message: NativeNotificationExternalMessage) = JSONObject().put("id", "native-item")
        .put("type", "functionCallOutput").put("name", "push_event").put("namespace", "hans_notifications")
        .put("output", message.payloadJson)
    private fun turn(id: String, items: JSONArray) = JSONObject().put("id", id).put("status", "completed")
        .put("itemsView", "full").put("items", items)
    private fun page(vararg turns: JSONObject) = JSONObject().put("data", JSONArray(turns.toList()))
}
