package ai.hans.standard.files

import ai.hans.standard.artifacts.*
import ai.hans.standard.codex.*
import ai.hans.standard.text.AssistantMarkdown
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.concurrent.Executor
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileDynamicToolsTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var root: File
    private lateinit var recoveryRoot: File
    private lateinit var artifacts: AtomicArtifactStore
    private lateinit var executor: FileDynamicToolExecutor
    private var granted = true
    @Before fun prepare() {
        root = temp.newFolder("Download").canonicalFile
        recoveryRoot = temp.newFolder("recovery").canonicalFile
        val boundary = temp.newFolder("private").canonicalFile
        artifacts = AtomicArtifactStore(File(boundary, "hans-artifacts"), boundary)
        executor = FileDynamicToolExecutor(SharedFileStore({ listOf(root) }, { granted },
            recoveryRoots = { listOf(SharedFileRecoveryRoot(root, recoveryRoot)) }), artifacts,
            Executor(Runnable::run), { root.path })
    }
    private fun call(tool: String, args: JSONObject = JSONObject()): DynamicToolExecutionResult {
        var result: DynamicToolExecutionResult? = null
        executor.execute(DynamicToolCallParams("thread", "turn", "call", "hans_files", tool, args.toString())) { result = it }
        return checkNotNull(result)
    }
    private fun body(tool: String, args: JSONObject = JSONObject()): JSONObject {
        val result = call(tool, args)
        assertTrue(result.contentText, result.success)
        return JSONObject(result.contentText)
    }
    @Test fun downloadedAudioArtifactSavesToPublicDownloadsThenLoadsAgain() {
        val binary = byteArrayOf(82, 73, 70, 70, 1, 2, 3, 4)
        val artifact = artifacts.put("voice.wav", "audio/wav", ArtifactOrigin.CODEX, binary.inputStream())
        val saved = body("save", JSONObject().put("artifactHandle", artifact.handle.value))
        assertEquals("public_phone_file", saved.getString("storage"))
        assertTrue(saved.getBoolean("verified"))
        assertEquals(File(root, "voice.wav").path, saved.getString("path"))
        assertArrayEquals(binary, File(root, "voice.wav").readBytes())
        val loaded = body("load", JSONObject().put("path", saved.getString("path")))
        assertEquals(artifact.sha256, loaded.getString("sha256"))
        assertFalse(loaded.has("text"))
    }
    @Test fun missingAndroidGrantCannotBecomePrivateSaveSuccess() {
        granted = false
        assertFalse(body("locations").getBoolean("allFilesAccess"))
        val result = call("save", JSONObject().put("fileName", "missing.txt").put("text", "hello"))
        assertFalse(result.success)
        assertEquals("all_files_access_required", JSONObject(result.contentText).getString("errorCode"))
        assertEquals(0, root.listFiles()!!.size)
        assertTrue(artifacts.listMetadata().isEmpty())
    }
    @Test fun savesTextAndRejectsConflictsAndAmbiguousInputs() {
        body("save", JSONObject().put("fileName", "note.txt").put("text", "äöü"))
        assertFalse(call("save", JSONObject().put("fileName", "note.txt").put("text", "new")).success)
        assertEquals("äöü", File(root, "note.txt").readText())
        assertFalse(call("save", JSONObject().put("fileName", "../escape").put("text", "bad")).success)
        assertFalse(call("save", JSONObject().put("text", "bad").put("artifactHandle", "x")).success)
        assertFalse(call("save", JSONObject().put("fileName", "x").put("text", "bad").put("ignored", true)).success)
    }
    @Test fun cleanupDeletesOnlyExactPrivateArtifactNeverPublicCopy() {
        val audio = artifacts.put("voice.wav", "audio/wav", ArtifactOrigin.CODEX, "tone".byteInputStream())
        val other = artifacts.put("note.txt", "text/plain", ArtifactOrigin.CODEX, "note".byteInputStream())
        body("save", JSONObject().put("artifactHandle", audio.handle.value))
        val listed = body("list_temporary").getJSONArray("artifacts")
        assertEquals(2, listed.length())
        assertFalse(call("delete_temporary", JSONObject().put("artifactHandle", audio.handle.value).put("expectedSha256", "0".repeat(64))).success)
        body("delete_temporary", JSONObject().put("artifactHandle", audio.handle.value).put("expectedSha256", audio.sha256))
        assertEquals(listOf(other.handle), artifacts.listMetadata().map { it.handle })
        assertTrue(File(root, "voice.wav").exists())
    }
    @Test fun cancelledQueuedWriteHasNoEffect() {
        val queue = mutableListOf<Runnable>()
        val queued = FileDynamicToolExecutor(SharedFileStore({ listOf(root) }, { granted }), artifacts,
            Executor { queue.add(it) }, { root.path })
        var callbacks = 0
        val handle = queued.executeCancellable(DynamicToolCallParams("thread", "turn", "call", "hans_files", "save",
            JSONObject().put("fileName", "cancelled").put("text", "x").toString()), DynamicToolCancellation.NONE) { callbacks++ }
        assertEquals(DynamicToolCancellationDisposition.CANCELLED_BEFORE_EXTERNAL_EFFECT, handle.cancel())
        queue.single().run()
        assertEquals(0, callbacks)
        assertEquals(0, root.listFiles()!!.size)
    }
    @Test fun fileLinksAcceptOnlyOpaqueReceiptsAndNeverRawDevicePaths() {
        val valid = "hansfile://open/" + "a".repeat(32)
        assertEquals(valid, AssistantMarkdown.safeLinkUrl(valid))
        assertNull(AssistantMarkdown.safeWebUrl(valid))
        listOf("file:///sdcard/Download/a", "content://ai.hans.standard.files/anything", "hansfile://open/../../secret",
            "$valid?path=/data", "hansfile://other/" + "a".repeat(32)).forEach {
            assertNull(it, AssistantMarkdown.safeLinkUrl(it))
        }
        val parsed = AssistantMarkdown.parse("[Öffnen]($valid)", true)
        assertEquals("Öffnen", parsed.plainText)
    }

    @Test fun ordinaryDeleteReceiptDisclosesNonRecoverabilityAndConcurrentPathRisk() {
        val original = body("save", JSONObject().put("fileName", "recover.txt").put("text", "approved"))
        val deleted = body("delete", JSONObject().put("path", original.getString("path"))
            .put("expectedSha256", original.getString("sha256")))
        assertFalse(deleted.getBoolean("recoverable"))
        assertTrue(deleted.getBoolean("filesystemNamespaceAbsent"))
        assertEquals("path_based", deleted.getString("deletionMode"))
        assertTrue(deleted.getBoolean("concurrentReplacementRisk"))
        assertFalse(deleted.has("bytesFreed"))
        assertFalse(deleted.has("recovery"))
        assertEquals(0, body("list_recovery").getJSONArray("recovery").length())
        assertFalse(File(original.getString("path")).exists())
    }

    @Test fun legacyRecoveryCanStillBeListedAndRestoredWithoutOverwrite() {
        val original = body("save", JSONObject().put("fileName", "legacy.txt").put("text", "approved"))
        val reservation = SharedFileRecovery { listOf(SharedFileRecoveryRoot(root, recoveryRoot)) }
            .reserve(File(original.getString("path")))
        Files.move(File(original.getString("path")).toPath(), reservation.entry, ATOMIC_MOVE)
        val handle = reservation.handle
        assertEquals(handle, body("list_recovery").getJSONArray("recovery").getJSONObject(0).getString("recoveryHandle"))
        val restored = body("restore_recovery", JSONObject().put("recoveryHandle", handle)
            .put("destination", File(root, "restored.txt").path))
        assertTrue(restored.getBoolean("recoveryRetained"))
        assertEquals("approved", File(root, "restored.txt").readText())
    }

    @Test fun moveReceiptExplicitlyReportsOrdinaryNonRecoverableSourceRemoval() {
        val original = body("save", JSONObject().put("fileName", "move.txt").put("text", "approved"))
        val moved = body("move", JSONObject().put("source", original.getString("path"))
            .put("destination", File(root, "moved.txt").path).put("expectedSha256", original.getString("sha256")))
        assertTrue(moved.getBoolean("sourceFilesystemNamespaceAbsent"))
        assertFalse(moved.getBoolean("sourceRecoverable"))
        assertTrue(moved.getBoolean("concurrentReplacementRisk"))
        assertEquals(original.getString("path"), moved.getString("sourceRemovedPath"))
        assertFalse(moved.has("sourceRecovery"))
        assertFalse(File(root, "move.txt").exists())
    }

    @Test fun locationsDisclosesPathDeletionRiskAndNoLongerClaimsSafeAtomicCapture() {
        val capability = body("locations")
        assertEquals("path_based", capability.getString("deletionMode"))
        assertTrue(capability.getBoolean("concurrentReplacementRisk"))
        assertFalse(capability.getBoolean("newDeletesRecoverable"))
        assertFalse(capability.has("safeRemoval"))
        val original = body("save", JSONObject().put("fileName", "unsupported.txt").put("text", "approved"))
        val destination = File(root, "never-created.txt")
        val moved = call("move", JSONObject().put("source", original.getString("path"))
            .put("destination", destination.path).put("expectedSha256", original.getString("sha256")))
        assertTrue(moved.success)
        assertEquals("approved", destination.readText())
        assertFalse(File(original.getString("path")).exists())
        granted = false
        assertFalse(body("locations").getBoolean("allFilesAccess"))
        assertFalse(call("list_recovery").success)
    }

    @Test fun failedSourceRemovalKeepsAndReportsVerifiedDestinationForEveryExceptionType() {
        val source = File(root, "vanishing.txt")
        val destination = File(root, "retained-copy.txt")
        executor = FileDynamicToolExecutor(SharedFileStore({ listOf(root) }, { granted }, changed = {
            if (it == destination) Files.delete(source.toPath())
        }), artifacts, Executor(Runnable::run), { root.path })
        val original = body("save", JSONObject().put("path", source.path).put("text", "approved"))
        val result = call("move", JSONObject().put("source", source.path).put("destination", destination.path)
            .put("expectedSha256", original.getString("sha256")))
        assertFalse(result.success)
        val receipt = JSONObject(result.contentText)
        assertEquals("source_removal_failed_destination_retained", receipt.getString("errorCode"))
        assertEquals(destination.path, receipt.getString("affectedPath"))
        assertTrue(receipt.getBoolean("destinationVerified"))
        assertEquals(original.getString("sha256"), receipt.getJSONObject("retainedDestination").getString("sha256"))
        assertEquals("approved", destination.readText())
    }
}
