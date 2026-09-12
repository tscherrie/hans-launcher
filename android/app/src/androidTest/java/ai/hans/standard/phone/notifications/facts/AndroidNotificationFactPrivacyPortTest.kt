package ai.hans.standard.phone.notifications.facts

import ai.hans.standard.notifications.*
import ai.hans.standard.phone.notifications.NotificationPrivacyPurgeFence
import ai.hans.standard.phone.notifications.NotificationPrivacyRepository
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Synthetic SQLite + AtomicFile composition; no canonical stores, accounts or Android access. */
@RunWith(AndroidJUnit4::class)
class AndroidNotificationFactPrivacyPortTest {
    private lateinit var directory: File
    private lateinit var context: Context
    private lateinit var storage: AtomicFileNotificationTriageStorage
    private lateinit var queueFile: File
    private val fence = object : NotificationPrivacyPurgeFence {
        override fun isRequired() = false
        override fun markRequired() = true
        override fun markClean() = true
    }

    @Before fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        directory = Files.createTempDirectory(app.cacheDir.toPath(), "fact-privacy-port-").toFile()
        context = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getNoBackupFilesDir(): File = directory
            override fun getDatabasePath(name: String): File {
                require(name.matches(Regex("[A-Za-z0-9][A-Za-z0-9_-]*\\.db")))
                return File(directory, name)
            }
        }
        queueFile = File(directory, "synthetic-queue.json")
        storage = AtomicFileNotificationTriageStorage(context, queueFile.name)
    }

    @After fun tearDown() {
        if (!::directory.isInitialized) return
        val app = ApplicationProvider.getApplicationContext<Context>()
        check(directory.parentFile == app.cacheDir && directory.name.startsWith("fact-privacy-port-"))
        assertTrue(directory.deleteRecursively())
    }

    @Test fun sourceForgetPurgesOnlyMatchingOutboxAndCommitsOtherCapturedSourceLater() {
        val first = batch("source_one", 1)
        val other = batch("source_two", 2)
        archive().use { assertTrue(it.commit(first) is NotificationFactCommitResult.Stored) }
        storage.write(NotificationTriageQueueState(emptyList(),
            factOutbox = listOf(StoredNotificationFactOutbox(first), StoredNotificationFactOutbox(other))))
        val request = NotificationFactPrivacyRequest("forget_source",
            NotificationFactPrivacyScope.Source(PACKAGE, first.sourceRef))
        val port = port()
        val begun = port.begin(request)
        assertTrue(begun is NotificationFactPrivacyBeginResult.Pending)
        val intent = (begun as NotificationFactPrivacyBeginResult.Pending).intent
        assertEquals(listOf(intent), port.pending())
        val completed = port.forgetScoped(request)
        assertEquals(NotificationFactPrivacyBeginResult.Completed(intent), completed)
        assertTrue(port.pending().isEmpty())
        assertEquals(listOf(other), storage.read().factOutbox.map { it.batch })
        archive().use { repository ->
            assertTrue(repository.query(NotificationFactQuery(sourceRef = first.sourceRef, packageName = PACKAGE)).facts.isEmpty())
            assertTrue(repository.canStage(other))
        }
        archive().use { repository ->
            val queue = queue(repository)
            assertEquals(NotificationFactOutboxDrainResult.PROGRESSED, queue.drainNextFactOutbox())
            assertTrue(storage.read().factOutbox.isEmpty())
            assertEquals(other.validatedCandidates.single().quote,
                repository.query(NotificationFactQuery(packageName = PACKAGE)).facts.single().text)
        }
    }

    @Test fun inventedOrMismatchedAllIntentNeverRepairsUnreadableQueue() {
        val original = batch("source_one", 1)
        archive().use { assertTrue(it.commit(original) is NotificationFactCommitResult.Stored) }
        storage.write(NotificationTriageQueueState(emptyList(), factOutbox = listOf(StoredNotificationFactOutbox(original))))
        val port = port()
        val actual = (port.begin(NotificationFactPrivacyRequest("real_all", NotificationFactPrivacyScope.All))
            as NotificationFactPrivacyBeginResult.Pending).intent
        queueFile.writeText("{synthetic unreadable")
        val bytes = queueFile.readBytes()
        for (invented in listOf(actual.copy(mutationId = "invented_all"),
            actual.copy(allGeneration = actual.allGeneration + 1))) {
            assertFalse(port.purgeCandidateOutbox(invented))
            assertTrue(bytes.contentEquals(queueFile.readBytes()))
            assertEquals(listOf(actual), port.pending())
        }
        assertEquals(NotificationTriageStorageRead.Unavailable, storage.readStatus())
    }

    @Test fun scopedForgetWithUnreadableQueueStaysDurablePendingAcrossPortRecreation() {
        val forgotten = batch("source_one", 1)
        val other = batch("source_two", 2)
        archive().use {
            assertTrue(it.commit(forgotten) is NotificationFactCommitResult.Stored)
            assertTrue(it.commit(other) is NotificationFactCommitResult.Stored)
        }
        storage.write(NotificationTriageQueueState(emptyList(),
            factOutbox = listOf(StoredNotificationFactOutbox(forgotten), StoredNotificationFactOutbox(other))))
        val validQueue = queueFile.readBytes()
        queueFile.writeText("{synthetic unreadable")
        val brokenQueue = queueFile.readBytes()
        val request = NotificationFactPrivacyRequest("scoped_forget",
            NotificationFactPrivacyScope.Source(PACKAGE, forgotten.sourceRef))
        val first = port().forgetScoped(request)
        assertTrue(first is NotificationFactPrivacyBeginResult.Pending)
        val intent = (first as NotificationFactPrivacyBeginResult.Pending).intent
        assertTrue(brokenQueue.contentEquals(queueFile.readBytes()))
        val recreated = port()
        assertEquals(listOf(intent), recreated.pending())
        assertTrue(recreated.forgetScoped(request) is NotificationFactPrivacyBeginResult.Pending)
        assertTrue(brokenQueue.contentEquals(queueFile.readBytes()))
        // Repair only the synthetic fixture bytes to prove pending recovery did not erase source two.
        queueFile.writeBytes(validQueue)
        assertEquals(NotificationFactPrivacyBeginResult.Completed(intent), recreated.forgetScoped(request))
        assertEquals(listOf(other), storage.read().factOutbox.map { it.batch })
        archive().use {
            assertEquals(listOf(other.validatedCandidates.single().quote),
                it.query(NotificationFactQuery(packageName = PACKAGE)).facts.map { fact -> fact.text })
        }
    }

    private fun archive() = AndroidNotificationFactRepository(context, databaseName = "synthetic.db",
        privacySnapshot = { NotificationFactExternalPrivacy(true, false) })
    private fun queue(repository: NotificationFactRepository? = null) = NotificationTriageQueue(
        storage, HansNotificationExclusionPolicy(setOf("ai.hans.standard")), factArchive = repository)
    private fun port() = AndroidNotificationFactPrivacyPort(context, NotificationPrivacyRepository(context),
        fence, openArchive = ::archive, createQueue = { queue() })
    private fun batch(source: String, sequence: Long): NotificationFactBatch {
        val quote = "Festival $source"
        return NotificationFactBatch(UUID.randomUUID().toString(),
            archive().use { requireNotNull(it.captureToken(PACKAGE)) }, PACKAGE, source, sequence,
            sequence, 100, "v1", listOf(NotificationMemoryCandidate(NotificationMemoryKind.EVENT_DETAIL,
                NotificationMemorySourceField.TEXT, quote, 0, quote.length, "a".repeat(64))))
    }
    companion object { private const val PACKAGE = "synthetic.chat" }
}
