package ai.hans.standard.notifications.agentchannel

import ai.hans.standard.phone.notifications.*
import android.app.Notification
import android.app.Person
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Process
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WhatsAppAgentChannelAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test fun androidMessagingStylePreservesIndividualTextAndOwnIdentityNotDisplayName() {
        val own = Person.Builder().setName("Du").setKey("actual-own-person-key").build()
        val style = Notification.MessagingStyle(own).setConversationTitle("Jeremias (Du)")
            .setGroupConversation(false)
            .addMessage(Notification.MessagingStyle.Message("[Codex:] First\nline two", 100, own))
            .addMessage(Notification.MessagingStyle.Message("[Hans:] Done", 200, null as Person?))
        val notification = notification(style)
        val extracted = AndroidNotificationExtractor.upsert(sbn(notification), 300).snapshot.agentChannelSource!!
        assertNotNull(extracted.identity())
        assertEquals(2, extracted.messages.size)
        assertEquals("[Codex:] First\nline two", extracted.messages.first().text)
        assertEquals(extracted.ownPersonIdentity, extracted.messages.first().senderIdentity)
        assertTrue(extracted.messages.last().senderIsOwnUser)
        val impostor = Person.Builder().setName("Du").setKey("different-person-key").build()
        val other = AndroidWhatsAppNotificationSource.extract(sbn(notification(Notification.MessagingStyle(own)
            .setGroupConversation(false).addMessage(Notification.MessagingStyle.Message("[Codex:] Wrong", 400, impostor)))))!!
        assertNotEquals(other.ownPersonIdentity, other.messages.single().senderIdentity)
        assertEquals(extracted.identity(), extracted.copy(displayTitle = "Any other title").identity())
    }

    @Test fun nameOnlyPersonUsesStableChatPinButGroupsOrSummaryCannotBeEnrolled() {
        val nameOnly = Person.Builder().setName("Jeremias (Du)").build()
        val style = Notification.MessagingStyle(nameOnly).addMessage("[Codex:] Not proof", 100, nameOnly)
        val nameOnlySource = AndroidWhatsAppNotificationSource.extract(sbn(notification(style)))!!
        assertNull(nameOnlySource.ownPersonIdentity)
        assertNotNull(nameOnlySource.identity())
        assertNull(nameOnlySource.copy(shortcutId = null).identity())
        val own = Person.Builder().setName("Du").setKey("own").build()
        val group = Notification.MessagingStyle(own).setGroupConversation(true).addMessage("[Codex:] Group", 100, own)
        assertNull(AndroidWhatsAppNotificationSource.extract(sbn(notification(group)))!!.identity())
        val summary = notification(Notification.MessagingStyle(own).addMessage("[Codex:] Summary", 100, own))
        summary.flags = summary.flags or Notification.FLAG_GROUP_SUMMARY
        assertNull(AndroidWhatsAppNotificationSource.extract(sbn(summary))!!.identity())
    }

    @Test fun boundedExtractionMarksIncompleteInsteadOfTrustingPrefix() {
        val own = Person.Builder().setName("Du").setKey("own").build()
        // Android can clip BEFORE the extractor sees text, without appending an ellipsis. Include
        // emoji so UTF-16 length (not bytes/code points) and a split surrogate boundary are tested.
        val longRequests = listOf("[Codex:] " + "x".repeat(5_000), "[Codex:] " + "\uD83D\uDE42".repeat(700),
            "[Codex:] " + "x".repeat(1_024 - "[Codex:] ".length))
        longRequests.forEach { original ->
            val style = Notification.MessagingStyle(own)
            repeat(AgentChannelLimits.MAX_MESSAGES + 1) { style.addMessage(original, 100L + it, own) }
            val source = AndroidWhatsAppNotificationSource.extract(sbn(notification(style)))!!
            val lengths = source.messages.map { "utf16=${it.text.length},bytes=${it.text.toByteArray().size},truncated=${it.truncated}" }
            assertTrue("Message-list truncation missing; lengths=$lengths", source.messagesTruncated)
            assertEquals(AgentChannelLimits.MAX_MESSAGES, source.messages.size)
            assertTrue("Platform-clipped text trusted; lengths=$lengths",
                source.messages.all { it.truncated && it.text.toByteArray().size <= AgentChannelLimits.MAX_MESSAGE_BYTES })

            // A single clipped message must not execute either: list-truncation is not the guard.
            val single = AndroidWhatsAppNotificationSource.extract(sbn(notification(Notification.MessagingStyle(own)
                .addMessage(original, 100L, own))))!!
            assertFalse(single.messagesTruncated)
            var now = 0L
            val memory = object : AgentChannelStorage {
                var state = AgentChannelState()
                override fun read() = state
                override fun write(state: AgentChannelState) { this.state = state }
            }
            val channel = WhatsAppAgentChannel(memory) { now }
            channel.observe(single.copy(messages = listOf(single.messages.single().copy(text = "hello", timestampEpochMillis = 0, truncated = false))))
            assertTrue(channel.confirmSelfChat(channel.listEnrollmentCandidates().single().id))
            now = 200
            val result = channel.observe(single)
            assertTrue("Clipped preview became an executable request", result.readyReceipts.isEmpty())
            assertEquals("message_incomplete", result.retrievalHints.single().reason)
            assertTrue(channel.pendingReceipts().isEmpty())
        }
        val belowCap = AndroidWhatsAppNotificationSource.extract(sbn(notification(Notification.MessagingStyle(own)
            .addMessage("[Codex:] " + "x".repeat(1_023 - "[Codex:] ".length), 100L, own))))!!
        assertEquals(1_023, belowCap.messages.single().text.length)
        assertFalse(belowCap.messages.single().truncated)
    }

    @Test fun atomicLedgerClaimSurvivesReopenAndMissingOrCorruptStateFailsClosed() {
        val name = "agent-channel-test-${System.nanoTime()}.json"
        try {
            var now = 1_000L
            val storage = AtomicFileAgentChannelStorage(context, name)
            val channel = WhatsAppAgentChannel(storage) { now }
            val source = WhatsAppNotificationSource("com.whatsapp", 0, 12345, "key", "self", "a".repeat(64),
                false, false, listOf(WhatsAppNotificationMessage("hello", now, "a".repeat(64), false)))
            channel.observe(source)
            assertTrue(channel.confirmSelfChat(channel.listEnrollmentCandidates().single().id))
            now++
            val receipt = channel.observe(source.copy(messages = listOf(WhatsAppNotificationMessage(
                "[Codex:] Task", now, "a".repeat(64), false)))).readyReceipts.single()
            assertNotNull(channel.claim(receipt.id))
            val reopened = WhatsAppAgentChannel(AtomicFileAgentChannelStorage(context, name)) { now }
            assertNull(reopened.claim(receipt.id))
            assertEquals(1, reopened.unsettledReceipts().size)
            File(context.noBackupFilesDir, name).writeText("corrupt")
            assertNull(AtomicFileAgentChannelStorage(context, name).read())
            File(context.noBackupFilesDir, name).delete()
            assertNull(AtomicFileAgentChannelStorage(context, name).read())
        } finally { deleteFiles(name) }
    }

    @Test fun privateInboxRoundTripPreservesSourceAndOldSchemaMigratesWithoutLosingRows() {
        for (oldVersion in 1..2) {
            val name = "agent-channel-migration-$oldVersion-${System.nanoTime()}.db"
            val now = System.currentTimeMillis()
            try {
                val path = context.getDatabasePath(name)
                path.parentFile!!.mkdirs()
                SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                    val common = "package_name TEXT NOT NULL, android_key TEXT NOT NULL, post_time INTEGER NOT NULL, " +
                        "notification_when INTEGER NOT NULL, title TEXT NOT NULL, text TEXT NOT NULL, subtext TEXT NOT NULL, " +
                        "category TEXT NOT NULL, channel_id TEXT NOT NULL, ongoing INTEGER NOT NULL, clearable INTEGER NOT NULL, actions_json TEXT NOT NULL"
                    db.execSQL("CREATE TABLE notification_events (sequence INTEGER PRIMARY KEY AUTOINCREMENT, event_kind TEXT NOT NULL, observed_at INTEGER NOT NULL, removal_reason INTEGER, $common)")
                    db.execSQL("CREATE TABLE notification_state ($common, active INTEGER NOT NULL, fingerprint TEXT NOT NULL, last_sequence INTEGER NOT NULL)")
                    if (oldVersion == 2) db.execSQL("CREATE TABLE notification_triage_outbox (event_sequence INTEGER PRIMARY KEY NOT NULL)")
                    db.execSQL("INSERT INTO notification_events VALUES (1,'POSTED',?,NULL,'com.whatsapp','old-key',?,?,'Old','Before update','','msg','channel',0,1,'[]')", arrayOf(now, now, now))
                    db.version = oldVersion
                }
                val privacy = NotificationPrivacyRepository(object : NotificationPrivacySettingsStorage {
                    override fun read() = NotificationPrivacyStorageRead.Available(NotificationPrivacySettings())
                    override fun write(settings: NotificationPrivacySettings) = Unit
                }, context.packageName)
                val fence = object : NotificationPrivacyPurgeFence {
                    override fun isRequired() = false
                    override fun markRequired() = true
                    override fun markClean() = true
                }
                NotificationInboxStore(context, databaseName = name, privacyRepository = privacy, privacyPurgeFence = fence).use { inbox ->
                    val old = inbox.queryPage(limit = 10).events.single()
                    assertEquals("Before update", old.snapshot.text)
                    assertNull(old.snapshot.agentChannelSource)
                    val own = Person.Builder().setName("Du").setKey("own-key").build()
                    val source = AndroidNotificationExtractor.upsert(sbn(notification(Notification.MessagingStyle(own)
                        .addMessage("[Codex:] Keep the original\nmessage", now, own))), now)
                    assertTrue(inbox.accept(source) is NotificationWriteResult.Stored)
                    assertEquals(source.snapshot.agentChannelSource, inbox.queryPage(limit = 10).events.last().snapshot.agentChannelSource)
                }
                SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { assertEquals(3, it.version) }
            } finally { context.deleteDatabase(name) }
        }
    }

    private fun notification(style: Notification.MessagingStyle): Notification = Notification.Builder(context, "agent-test")
        .setSmallIcon(android.R.drawable.stat_notify_chat).setShortcutId("stable-self-chat").setStyle(style).build()
    @Suppress("DEPRECATION")
    private fun sbn(notification: Notification) = StatusBarNotification("com.whatsapp", "com.whatsapp", 42,
        null, 12345, Process.myPid(), 0, notification, Process.myUserHandle(), System.currentTimeMillis())
    private fun deleteFiles(name: String) {
        listOf(name, "$name.bak", "$name.new", "$name.initialized", "$name.initialized.bak", "$name.initialized.new")
            .forEach { File(context.noBackupFilesDir, it).delete() }
    }
}
