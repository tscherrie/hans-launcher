package ai.hans.standard.automations

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardOpenOption.APPEND
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ai.hans.standard.automations.AutomationProcessRecoveryProtocol as Protocol
import ai.hans.standard.automations.AutomationProcessRecoveryJournal as Journal

class AutomationProcessRecoveryProtocolTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val descriptor = AutomationProcessRecoveryDescriptor(
        fixtureId = "93a69520-1a14-4de8-9f85-0c36e14c5aaa",
        scenario = "process-pre-fence",
        appApkSha256 = "a".repeat(64),
        initialScheduleNonce = "fa38fc66-0ff4-4877-a4af-58b741ce88cd",
    )

    @Test
    fun descriptorRoundTripsEveryExplicitScenarioAndDerivesAllOwnedNames() {
        for (scenario in listOf(
            "process-pre-fence", "process-post-fence", "reboot-pre-fence", "reboot-post-fence",
        )) {
            val expected = descriptor.copy(scenario = scenario)
            assertEquals(expected, Protocol.decodeDescriptor(Protocol.encodeDescriptor(expected)))
        }
        val stem = "automation-process-" + descriptor.fixtureId
        assertEquals(stem + ".db", descriptor.databaseName)
        assertEquals(stem + "-effects.db", descriptor.effectsDatabaseName)
        assertEquals(stem + "-work", descriptor.workPreferencesName)
        assertEquals(stem, descriptor.checkpointDirectoryName)
        assertEquals(0x4854F101, descriptor.executionJobId)
        assertEquals(0x4854F102, descriptor.wakeupJobId)
        assertEquals(0x4854F111, descriptor.bootExecutionJobId)
    }

    @Test
    fun absentDescriptorAloneIsUnarmed() {
        val root = temporary.newFolder()
        assertNull(Protocol.readDescriptor(root))
        val file = File(root, Protocol.DESCRIPTOR_FILE_NAME)
        file.writeText("{}")
        invalid { Protocol.readDescriptor(root) }
        assertEquals("{}", file.readText())
    }

    @Test
    fun descriptorRejectsUnknownFieldsPathsMissingFieldsAndWrongSchema() {
        val json = descriptorJson()
        for (candidate in listOf(
            json.dropLast(1) + ",\"databasePath\":\"/not/allowed\"}",
            json.replace("\"schema\":\"" + Protocol.SCHEMA + "\",", ""),
            json.replace(Protocol.SCHEMA, "hans.automation-process-fixture.v2"),
            json.replace("\"executionJobId\":" + descriptor.executionJobId, "\"executionJobId\":\"" + descriptor.executionJobId + "\""),
            json.replace("\"wakeupJobId\":" + descriptor.wakeupJobId, "\"wakeupJobId\":" + descriptor.executionJobId),
        )) invalid { Protocol.decodeDescriptor(candidate.toByteArray()) }
    }

    @Test
    fun descriptorRejectsNonCanonicalIdentifiersAndHashesAtConstruction() {
        for (value in listOf("../outside", "1-1-1-1-1", descriptor.fixtureId.uppercase(), "")) {
            invalid { descriptor.copy(fixtureId = value) }
            invalid { descriptor.copy(initialScheduleNonce = value) }
        }
        for (value in listOf("A".repeat(64), "a".repeat(63), "g".repeat(64), "a".repeat(65))) {
            invalid { descriptor.copy(appApkSha256 = value) }
        }
        invalid { descriptor.copy(scenario = "process") }
        invalid { descriptor.copy(executionJobId = 1) }
        invalid { descriptor.copy(wakeupJobId = 2) }
    }

    @Test
    fun strictJsonRejectsDuplicatesIncludingEscapedEquivalentKeysAndTrailingSyntax() {
        val json = descriptorJson()
        for (candidate in listOf(
            json.dropLast(1) + ",\"fixtureId\":\"" + descriptor.fixtureId + "\"}",
            json.dropLast(1) + ",\"\\u0066ixtureId\":\"" + descriptor.fixtureId + "\"}",
            json + "{}",
            json + " false",
            json.dropLast(1) + ",}",
            json.replace("{", "{/* comment */"),
            json.replace("\"executionJobId\":" + descriptor.executionJobId, "\"executionJobId\":0" + descriptor.executionJobId),
            json.replace("\"executionJobId\":" + descriptor.executionJobId, "\"executionJobId\":1.0"),
            json.replace("\"executionJobId\":" + descriptor.executionJobId, "\"executionJobId\":1e2"),
            json.replace("\"executionJobId\":" + descriptor.executionJobId, "\"executionJobId\":9223372036854775808"),
            json.replace("\"executionJobId\":" + descriptor.executionJobId, "\"executionJobId\":null"),
        )) invalid { Protocol.decodeDescriptor(candidate.toByteArray()) }
    }

    @Test
    fun strictJsonRejectsInvalidUtf8UnpairedSurrogatesAndOversizeBytes() {
        invalid { Protocol.decodeDescriptor(byteArrayOf(0xc3.toByte(), 0x28)) }
        invalid { Protocol.decodeDescriptor(byteArrayOf()) }
        invalid { Protocol.decodeDescriptor(ByteArray(Protocol.MAX_DESCRIPTOR_BYTES + 1) { ' '.code.toByte() }) }
        invalid { Protocol.decodeDescriptor(descriptorJson().replace(descriptor.fixtureId, "\\uD800").toByteArray()) }
        invalid { Protocol.decodeDescriptor(descriptorJson().replace(descriptor.fixtureId, "\n").toByteArray()) }
    }

    @Test
    fun existingDescriptorDirectoryOrSymlinkIsNotUnarmed() {
        val root = temporary.newFolder()
        val path = File(root, Protocol.DESCRIPTOR_FILE_NAME).toPath()
        Files.createDirectory(path)
        invalid { Protocol.readDescriptor(root) }
        Files.delete(path)
        val outside = temporary.newFile()
        outside.writeBytes(Protocol.encodeDescriptor(descriptor))
        Files.createSymbolicLink(path, outside.toPath())
        invalid { Protocol.readDescriptor(root) }
        assertEquals(descriptor, Protocol.decodeDescriptor(outside.readBytes()))
        Files.delete(path)
        Files.createSymbolicLink(path, File(root, "missing").toPath())
        invalid { Protocol.readDescriptor(root) }
    }

    @Test
    fun oversizedDescriptorOnDiskIsRejectedWithoutWritingAnything() {
        val root = temporary.newFolder()
        val path = File(root, Protocol.DESCRIPTOR_FILE_NAME)
        val bytes = ByteArray(Protocol.MAX_DESCRIPTOR_BYTES + 1) { ' '.code.toByte() }
        path.writeBytes(bytes)
        invalid { Protocol.readDescriptor(root) }
        assertArrayEquals(bytes, path.readBytes())
        assertEquals(listOf(Protocol.DESCRIPTOR_FILE_NAME), root.list()?.toList())
    }

    @Test
    fun journalInitializesOnlyFreshOwnedDirectoryAndPersistsAcrossReopen() {
        val root = armedRoot()
        val journal = Journal.open(root, descriptor)
        assertTrue(journal.events().isEmpty())
        assertNull(journal.lastCheckpoint())
        append(journal, "armed")
        append(journal, "gate_ready", mapOf("effectCount" to 0, "fenced" to false))

        val reopened = Journal.open(root, descriptor)
        assertEquals(listOf("armed", "gate_ready"), reopened.events().map { it.event })
        assertEquals(listOf(1L, 2L), reopened.events().map { it.sequence })
        assertEquals(0L, reopened.lastCheckpoint()?.fields?.get("effectCount"))
        assertArrayEquals(
            Protocol.encodeEvent(checkNotNull(reopened.lastCheckpoint())),
            File(reopened.directory, Protocol.CHECKPOINT_FILE_NAME).readBytes(),
        )
        assertEquals(
            setOf(Protocol.OWNER_FILE_NAME, Protocol.JOURNAL_FILE_NAME, Protocol.CHECKPOINT_FILE_NAME),
            reopened.directory.list()?.toSet(),
        )
    }

    @Test
    fun journalDoesNotTreatAnExistingEmptyOrUnexpectedDirectoryAsFresh() {
        val root = armedRoot()
        val owned = File(root, descriptor.checkpointDirectoryName)
        assertTrue(owned.mkdir())
        val unexpected = File(owned, "unowned.txt").apply { writeText("retain") }
        invalid { Journal.open(root, descriptor) }
        assertEquals("retain", unexpected.readText())
        assertEquals(listOf("unowned.txt"), owned.list()?.toList())
    }

    @Test
    fun addingUnexpectedFileAfterOpenBlocksFurtherReadsAndAppendsWithoutLoss() {
        val root = armedRoot()
        val journal = Journal.open(root, descriptor)
        append(journal, "armed")
        val original = journalFile(journal).readBytes()
        File(journal.directory, "unknown.json").writeText("{}")
        invalid { journal.events() }
        invalid { append(journal, "gate_ready") }
        assertArrayEquals(original, journalFile(journal).readBytes())
    }

    @Test
    fun changedDescriptorOrOwnerDoesNotAdoptAnotherFixture() {
        val root = armedRoot()
        val journal = Journal.open(root, descriptor)
        append(journal, "armed")
        val original = journalFile(journal).readBytes()
        val other = descriptor.copy(initialScheduleNonce = "c49454e9-17f5-4307-895f-b1480962d703")
        File(journal.directory, Protocol.OWNER_FILE_NAME).writeBytes(Protocol.encodeDescriptor(other))
        invalid { Journal.open(root, descriptor) }
        File(journal.directory, Protocol.OWNER_FILE_NAME).writeBytes(Protocol.encodeDescriptor(descriptor))
        File(root, Protocol.DESCRIPTOR_FILE_NAME).writeBytes(Protocol.encodeDescriptor(other))
        invalid { journal.events() }
        invalid { Journal.open(root, other) }
        assertArrayEquals(original, journalFile(journal).readBytes())
    }

    @Test
    fun symlinkRootOrOwnedDirectoryCannotRedirectJournalWrites() {
        val outside = armedRoot()
        val parent = temporary.newFolder()
        val alias = File(parent, "alias").toPath()
        Files.createSymbolicLink(alias, outside.toPath())
        invalid { Journal.open(alias.toFile(), descriptor) }
        val root = armedRoot()
        Files.createSymbolicLink(File(root, descriptor.checkpointDirectoryName).toPath(), outside.toPath())
        invalid { Journal.open(root, descriptor) }
        assertFalse(File(outside, Protocol.OWNER_FILE_NAME).exists())
    }

    @Test
    fun symlinkJournalOwnerAndCheckpointAreRejectedWithoutTouchingTheirTargets() {
        for (fileName in listOf(Protocol.JOURNAL_FILE_NAME, Protocol.OWNER_FILE_NAME, Protocol.CHECKPOINT_FILE_NAME)) {
            val root = armedRoot()
            val journal = Journal.open(root, descriptor)
            append(journal, "armed")
            val target = temporary.newFile().apply { writeText("outside") }
            val path = File(journal.directory, fileName).toPath()
            Files.delete(path)
            Files.createSymbolicLink(path, target.toPath())
            invalid { Journal.open(root, descriptor) }
            assertEquals("outside", target.readText())
        }
    }

    @Test
    fun fieldsAreDefensivelyCopiedNormalizedAndStrictlyScalar() {
        val journal = Journal.open(armedRoot(), descriptor)
        val input = linkedMapOf<String, Any>("count" to 2, "ready" to true, "text" to "hello\n\"world\"\\")
        val event = append(journal, "gate_ready", input)
        input["count"] = 99
        assertEquals(2L, event.fields["count"])
        assertEquals(event.fields, Protocol.decodeEvent(Protocol.encodeEvent(event)).fields)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (event.fields as MutableMap<String, Any>)["new"] = "not allowed"
        }
        invalid { append(journal, "gate_ready", mapOf("nested" to mapOf("count" to 1))) }
        invalid { append(journal, "gate_ready", mapOf("floating" to 1.5)) }
        invalid { append(journal, "gate_ready", mapOf("invalid-key" to 1)) }
        assertEquals(1, journal.events().size)
    }

    @Test
    fun eventCodecRejectsUnknownDuplicateNonScalarAndOutOfRangeFields() {
        val json = String(Protocol.encodeEvent(event(1)), Charsets.UTF_8)
        for (candidate in listOf(
            json.dropLast(1) + ",\"extra\":true}",
            json.replace("\"fields\":{}", "\"fields\":{\"a\":1,\"a\":2}"),
            json.replace("\"fields\":{}", "\"fields\":{\"a\":null}"),
            json.replace("\"fields\":{}", "\"fields\":{\"a\":[]}"),
            json.replace("\"fields\":{}", "\"fields\":{\"a\":{\"b\":1}}"),
            json.replace("\"fields\":{}", "\"fields\":{\"a\":1.5}"),
            json.replace("\"sequence\":1", "\"sequence\":0"),
            json.replace("\"sequence\":1", "\"sequence\":513"),
            json.replace("\"pid\":123", "\"pid\":0"),
            json.replace("\"uid\":10001", "\"uid\":-1"),
            json.replace("\"timestamp\":1000", "\"timestamp\":-1"),
            json.replace("\"event\":\"armed\"", "\"event\":\"../armed\""),
            json.replace("\"bootId\":\"android-boot-1\"", "\"bootId\":\"\""),
        )) invalid { Protocol.decodeEvent(candidate.toByteArray()) }
    }

    @Test
    fun unicodeEscapesSignedIntegersAndBooleansRoundTrip() {
        val journal = Journal.open(armedRoot(), descriptor)
        val record = append(
            journal,
            "armed",
            mapOf("minimum" to Long.MIN_VALUE, "maximum" to Long.MAX_VALUE, "value" to "a\u0000b \uD83D\uDE42", "allowed" to false),
        )
        assertEquals(record.fields, Protocol.decodeEvent(Protocol.encodeEvent(record)).fields)
        invalid { append(journal, "armed", mapOf("broken" to "\uD800")) }
    }

    @Test
    fun fieldCountStringAndEncodedByteLimitsLeaveJournalUnchanged() {
        val journal = Journal.open(armedRoot(), descriptor)
        append(journal, "armed")
        val original = journalFile(journal).readBytes()
        invalid { append(journal, "gate_ready", (1..49).associate { "field" + it to it }) }
        invalid { append(journal, "gate_ready", mapOf("text" to "a".repeat(2_049))) }
        invalid { append(journal, "gate_ready", mapOf("first" to "a".repeat(2_048), "second" to "b".repeat(2_048))) }
        assertArrayEquals(original, journalFile(journal).readBytes())
        assertEquals(1L, journal.lastCheckpoint()?.sequence)
    }

    @Test
    fun completeJournalAheadOfCheckpointRecoversExactProjectionWithoutRewritingJournal() {
        val root = armedRoot()
        val journal = Journal.open(root, descriptor)
        append(journal, "armed")
        val second = event(2, "gate_ready")
        Files.write(journalFile(journal).toPath(), Protocol.encodeEvent(second) + byteArrayOf(10), APPEND)
        val original = journalFile(journal).readBytes()

        val reopened = Journal.open(root, descriptor)
        assertEquals(2L, reopened.lastCheckpoint()?.sequence)
        assertEquals("gate_ready", reopened.lastCheckpoint()?.event)
        assertArrayEquals(original, journalFile(reopened).readBytes())
        assertArrayEquals(Protocol.encodeEvent(second), File(reopened.directory, Protocol.CHECKPOINT_FILE_NAME).readBytes())
    }

    @Test
    fun firstFullyWrittenJournalEventWithoutCheckpointCanBeProjectedAfterRestart() {
        val root = armedRoot()
        val journal = Journal.open(root, descriptor)
        journalFile(journal).writeBytes(Protocol.encodeEvent(event(1)) + byteArrayOf(10))
        val reopened = Journal.open(root, descriptor)
        assertEquals(1L, reopened.lastCheckpoint()?.sequence)
        assertNotNull(File(reopened.directory, Protocol.CHECKPOINT_FILE_NAME).takeIf { it.isFile })
    }

    @Test
    fun completePendingProjectionIsAdoptedOnlyIfItExactlyMatchesLastJournalEvent() {
        val root = armedRoot()
        val journal = Journal.open(root, descriptor)
        append(journal, "armed")
        val second = Protocol.encodeEvent(event(2, "gate_ready"))
        Files.write(journalFile(journal).toPath(), second + byteArrayOf(10), APPEND)
        val pending = File(journal.directory, Protocol.CHECKPOINT_PENDING_FILE_NAME)
        pending.writeBytes(second)
        val reopened = Journal.open(root, descriptor)
        assertEquals(2L, reopened.lastCheckpoint()?.sequence)
        assertFalse(pending.exists())
    }

    @Test
    fun checkpointObjectKeyOrderingDoesNotChangeEventIdentity() {
        val root = armedRoot()
        val journal = Journal.open(root, descriptor)
        append(journal, "armed", linkedMapOf("first" to 1, "second" to false))
        val checkpoint = File(journal.directory, Protocol.CHECKPOINT_FILE_NAME)
        checkpoint.writeBytes(
            Protocol.encodeEvent(event(1, fields = linkedMapOf("second" to false, "first" to 1))),
        )
        val reopened = Journal.open(root, descriptor)
        assertEquals(mapOf("first" to 1L, "second" to false), reopened.lastCheckpoint()?.fields)
    }

    @Test
    fun nonRegularOrSymlinkPendingCheckpointCannotBePromoted() {
        for (symlink in listOf(false, true)) {
            val root = armedRoot()
            val journal = Journal.open(root, descriptor)
            append(journal, "armed")
            val outside = temporary.newFile().apply { writeBytes(Protocol.encodeEvent(event(1))) }
            val pending = File(journal.directory, Protocol.CHECKPOINT_PENDING_FILE_NAME).toPath()
            if (symlink) Files.createSymbolicLink(pending, outside.toPath()) else Files.createDirectory(pending)
            invalid { Journal.open(root, descriptor) }
            assertArrayEquals(Protocol.encodeEvent(event(1)), outside.readBytes())
        }
    }

    @Test
    fun tornJournalTailRemainsUntouchedAndNeverBecomesAReadyCheckpoint() {
        val root = armedRoot()
        val journal = Journal.open(root, descriptor)
        append(journal, "armed")
        val checkpoint = File(journal.directory, Protocol.CHECKPOINT_FILE_NAME).readBytes()
        Files.write(journalFile(journal).toPath(), Protocol.encodeEvent(event(2, "gate_ready")), APPEND)
        val original = journalFile(journal).readBytes()
        invalid { Journal.open(root, descriptor) }
        assertArrayEquals(original, journalFile(journal).readBytes())
        assertArrayEquals(checkpoint, File(journal.directory, Protocol.CHECKPOINT_FILE_NAME).readBytes())
    }

    @Test
    fun malformedCompleteJournalLineIsNotTruncatedOrSilentlySkipped() {
        val root = armedRoot()
        val journal = Journal.open(root, descriptor)
        append(journal, "armed")
        Files.write(journalFile(journal).toPath(), "{broken}\n".toByteArray(), APPEND)
        val original = journalFile(journal).readBytes()
        invalid { Journal.open(root, descriptor) }
        assertArrayEquals(original, journalFile(journal).readBytes())
    }

    @Test
    fun foreignOrGappedJournalEntriesAreRejectedBeforeAnyCheckpointRepair() {
        for (entry in listOf(
            event(3, "gate_ready"),
            event(2, "gate_ready", fixtureId = "c49454e9-17f5-4307-895f-b1480962d703"),
            event(1, "gate_ready"),
        )) {
            val root = armedRoot()
            val journal = Journal.open(root, descriptor)
            append(journal, "armed")
            Files.write(journalFile(journal).toPath(), Protocol.encodeEvent(entry) + byteArrayOf(10), APPEND)
            val original = journalFile(journal).readBytes()
            invalid { Journal.open(root, descriptor) }
            assertArrayEquals(original, journalFile(journal).readBytes())
        }
    }

    @Test
    fun checkpointAheadOfJournalOrWithAlteredFieldsIsNeverAcceptedAsEvidence() {
        for (checkpoint in listOf(
            event(2, "gate_ready"),
            event(1, "gate_ready"),
            event(1, "armed", fields = mapOf("effectCount" to 1)),
            event(1, "armed", fixtureId = "c49454e9-17f5-4307-895f-b1480962d703"),
        )) {
            val root = armedRoot()
            val journal = Journal.open(root, descriptor)
            append(journal, "armed")
            val bytes = Protocol.encodeEvent(checkpoint)
            File(journal.directory, Protocol.CHECKPOINT_FILE_NAME).writeBytes(bytes)
            invalid { Journal.open(root, descriptor) }
            assertArrayEquals(bytes, File(journal.directory, Protocol.CHECKPOINT_FILE_NAME).readBytes())
        }
    }

    @Test
    fun corruptOrMismatchedPendingCheckpointIsPreservedForDiagnosis() {
        for (bytes in listOf("{partial".toByteArray(), Protocol.encodeEvent(event(2, "gate_ready")))) {
            val root = armedRoot()
            val journal = Journal.open(root, descriptor)
            append(journal, "armed")
            val pending = File(journal.directory, Protocol.CHECKPOINT_PENDING_FILE_NAME)
            pending.writeBytes(bytes)
            invalid { Journal.open(root, descriptor) }
            assertArrayEquals(bytes, pending.readBytes())
        }
    }

    @Test
    fun journalEventLimitPreventsAppendWithoutDiscardingHistory() {
        val root = armedRoot()
        val journal = Journal.open(root, descriptor)
        val bytes = (1..Protocol.MAX_EVENTS).flatMap {
            (Protocol.encodeEvent(event(it.toLong())) + byteArrayOf(10)).toList()
        }.toByteArray()
        assertTrue(bytes.size < Protocol.MAX_JOURNAL_BYTES)
        journalFile(journal).writeBytes(bytes)
        File(journal.directory, Protocol.CHECKPOINT_FILE_NAME).writeBytes(Protocol.encodeEvent(event(512)))
        val reopened = Journal.open(root, descriptor)
        invalid { append(reopened, "gate_ready") }
        assertEquals(512, reopened.events().size)
        assertArrayEquals(bytes, journalFile(journal).readBytes())
    }

    @Test
    fun fullJournalByteBudgetPreventsAppendAndOversizeReadFailsClosed() {
        val root = armedRoot()
        val journal = Journal.open(root, descriptor)
        val lines = (1..64).map { index ->
            val line = Protocol.encodeEvent(event(index.toLong()))
            val desired = if (index < 64) 4_096 else 4_032
            line + ByteArray(desired - line.size) { ' '.code.toByte() } + byteArrayOf(10)
        }
        val bytes = lines.flatMap { it.toList() }.toByteArray()
        assertEquals(Protocol.MAX_JOURNAL_BYTES, bytes.size)
        journalFile(journal).writeBytes(bytes)
        File(journal.directory, Protocol.CHECKPOINT_FILE_NAME).writeBytes(Protocol.encodeEvent(event(64)))
        val reopened = Journal.open(root, descriptor)
        invalid { append(reopened, "gate_ready") }
        assertArrayEquals(bytes, journalFile(journal).readBytes())
        Files.write(journalFile(journal).toPath(), byteArrayOf(10), APPEND)
        invalid { Journal.open(root, descriptor) }
        assertEquals(Protocol.MAX_JOURNAL_BYTES.toLong() + 1, journalFile(journal).length())
    }

    @Test
    fun concurrentAppendsAcrossReopenedHandlesHaveUniqueContiguousSequences() {
        val root = armedRoot()
        val first = Journal.open(root, descriptor)
        val second = Journal.open(root, descriptor)
        val executor = Executors.newFixedThreadPool(4)
        try {
            val work = (1..24).map { index ->
                Callable { append(if (index % 2 == 0) first else second, "observed", mapOf("index" to index)) }
            }
            val completed = executor.invokeAll(work, 10, TimeUnit.SECONDS).map { it.get(5, TimeUnit.SECONDS) }
            assertEquals((1L..24L).toSet(), completed.map { it.sequence }.toSet())
            assertEquals((1L..24L).toList(), first.events().map { it.sequence })
            assertEquals((1L..24L).toSet(), first.events().map { it.fields.getValue("index") }.toSet())
            assertEquals(24L, second.lastCheckpoint()?.sequence)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun wallClockRollbackDoesNotRewriteOrReorderDurableEvents() {
        val journal = Journal.open(armedRoot(), descriptor)
        journal.append("armed", 123, 10001, "android-boot-1", 1_000)
        journal.append("observed", 123, 10001, "android-boot-1", 900)
        assertEquals(listOf(1L, 2L), journal.events().map { it.sequence })
        assertEquals(listOf(1_000L, 900L), journal.events().map { it.timestamp })
    }

    private fun armedRoot(): File = temporary.newFolder().also {
        File(it, Protocol.DESCRIPTOR_FILE_NAME).writeBytes(Protocol.encodeDescriptor(descriptor))
    }

    private fun descriptorJson(): String = String(Protocol.encodeDescriptor(descriptor), Charsets.UTF_8)

    private fun append(
        journal: Journal,
        event: String,
        fields: Map<String, Any> = emptyMap(),
    ): AutomationProcessRecoveryEvent = journal.append(event, 123, 10001, "android-boot-1", 1_000, fields)

    private fun event(
        sequence: Long,
        name: String = "armed",
        fixtureId: String = descriptor.fixtureId,
        fields: Map<String, Any> = emptyMap(),
    ): AutomationProcessRecoveryEvent =
        AutomationProcessRecoveryEvent(fixtureId, name, sequence, 123, 10001, "android-boot-1", 1_000, fields)

    private fun journalFile(journal: Journal): File =
        File(journal.directory, Protocol.JOURNAL_FILE_NAME)

    private fun invalid(block: () -> Unit) {
        assertThrows(AutomationProcessRecoveryProtocolException::class.java, block)
    }
}
