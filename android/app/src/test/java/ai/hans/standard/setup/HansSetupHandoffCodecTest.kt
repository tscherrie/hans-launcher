package ai.hans.standard.setup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HansSetupHandoffCodecTest {
    @Test
    fun versionOneDocumentLoadsWithoutAliasesAndNextWriteUsesVersionTwo() {
        val original = HansSetupHandoffDocument(listOf(record(INSTALL)))
        val legacy = JSONObject(HansSetupHandoffCodec.encode(original)).put("version", 1)
        legacy.getJSONArray("records").getJSONObject(0).remove("aliases")
        val restored = HansSetupHandoffCodec.decode(legacy.toString())
        assertEquals(original, restored)
        assertTrue(restored.records.single().aliases.isEmpty())
        val encoded = HansSetupHandoffCodec.encode(restored)
        assertEquals(2, JSONObject(encoded).getInt("version"))
        assertEquals(restored, HansSetupHandoffCodec.decode(encoded))
    }

    @Test
    fun aliasesRetainTheirOwnIdAndReasonAcrossCodecRoundTrip() {
        val original = HansSetupHandoffDocument(listOf(record(INSTALL).copy(aliases = listOf(REPAIR))))
        val restored = HansSetupHandoffCodec.decode(HansSetupHandoffCodec.encode(original))
        assertEquals(original, restored)
        assertEquals(2, restored.requestCount)
        assertEquals(REPAIR, restored.records.single().registeredCommand(REPAIR.handoffId))
        assertEquals(HansSetupHandoffRecord.messageIdFor(INSTALL.handoffId), restored.records.single().clientUserMessageId)
        val originalAck = HansSetupHandoffAcknowledgement.create(INSTALL, HansSetupHandoffAckStatus.ACCEPTED)
        val aliasAck = HansSetupHandoffAcknowledgement.create(REPAIR, HansSetupHandoffAckStatus.ACCEPTED)
        assertTrue(aliasAck.resultData.contains(":${REPAIR.handoffId}:repair:accepted:"))
        assertFalse(aliasAck.resultData.contains(INSTALL.handoffId))
        assertFalse(originalAck.sha256 == aliasAck.sha256)
    }

    @Test
    fun aliasesCannotDuplicateAnotherCanonicalIdOrUseUnsupportedFields() {
        val original = HansSetupHandoffDocument(listOf(record(INSTALL).copy(aliases = listOf(REPAIR))))
        val malformed = JSONObject(HansSetupHandoffCodec.encode(original))
        malformed.getJSONArray("records").getJSONObject(0).getJSONArray("aliases")
            .getJSONObject(0).put("handoffId", INSTALL.handoffId)
        assertTrue(runCatching { HansSetupHandoffCodec.decode(malformed.toString()) }.isFailure)
        assertTrue(
            runCatching {
                HansSetupHandoffDocument(listOf(record(INSTALL).copy(aliases = listOf(REPAIR)), record(REPAIR)))
            }.isFailure,
        )
        val extraField = JSONObject(HansSetupHandoffCodec.encode(original))
        extraField.getJSONArray("records").getJSONObject(0).getJSONArray("aliases")
            .getJSONObject(0).put("clientUserMessageId", "invented-alias-ack")
        assertTrue(runCatching { HansSetupHandoffCodec.decode(extraField.toString()) }.isFailure)
    }

    @Test
    fun versionOneCannotSmuggleAliasesAndUnknownVersionFailsClosed() {
        val original = HansSetupHandoffDocument(listOf(record(INSTALL)))
        val legacyWithNewFields = JSONObject(HansSetupHandoffCodec.encode(original)).put("version", 1)
        assertTrue(runCatching { HansSetupHandoffCodec.decode(legacyWithNewFields.toString()) }.isFailure)
        val future = JSONObject(HansSetupHandoffCodec.encode(original)).put("version", 3)
        assertTrue(runCatching { HansSetupHandoffCodec.decode(future.toString()) }.isFailure)
    }

    private fun record(command: HansSetupHandoffCommand) = HansSetupHandoffRecord(
        command = command,
        clientUserMessageId = HansSetupHandoffRecord.messageIdFor(command.handoffId),
        phase = HansSetupHandoffPhase.QUEUED,
        reservationOwnerId = null,
        receivedAtMillis = 1,
        updatedAtMillis = 1,
    )

    private companion object {
        val INSTALL = HansSetupHandoffCommand(1, "cf9f3ad8-4e3b-431d-a6a1-b6f34f0d7bda", HansSetupHandoffReason.INSTALL)
        val REPAIR = HansSetupHandoffCommand(1, "2c1d0fd9-b534-4e0d-818f-93f9d0ee7f9d", HansSetupHandoffReason.REPAIR)
    }
}
