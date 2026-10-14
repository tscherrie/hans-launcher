package ai.hans.standard.notifications

import ai.hans.standard.codex.ReasoningEffort
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Local API31–36 contracts only: no login changes, real account reads, tools or network calls. */
@RunWith(AndroidJUnit4::class)
class RestrictedTextAnalysisAndroidContractTest {
    @Test fun noFollowMetadataProjectionWorksOnAndroidWithoutExposingAuthValues() = withTemporaryDirectory { root ->
        val auth = File(root, "synthetic-auth.json").apply {
            writeText("""{"auth_mode":"chatgpt","OPENAI_API_KEY":null,"tokens":{"account_id":"synthetic-account","access_token":"synthetic-never-real"}}""")
        }
        val destination = File(root, "projected/auth.json")
        val projector = RestrictedTriageAuthProjector(auth, destination)
        val owner = projector.currentAccountFingerprint()
        assertNotNull(owner)
        assertTrue(owner!!.matches(Regex("[0-9a-f]{64}")))
        assertFalse(owner.contains("synthetic-account"))
        projector.projectLatest(owner)
        assertEquals(auth.readText(), destination.readText())
        val link = File(root, "linked-auth.json")
        Files.createSymbolicLink(link.toPath(), auth.toPath())
        assertNull(RestrictedTriageAuthProjector(link, destination).currentAccountFingerprint())
    }

    @Test fun heldKernelLeasePreventsConcurrentSweeperUntilReleaseOnAndroid() = withTemporaryDirectory { root ->
        val directories = RestrictedTriageAppServerPolicy.newSessionDirectories(root, UUID.randomUUID().toString())
        val session = directories.homeDirectory.parentFile!!
        val lease = RestrictedTriageSessionWorkspace.acquireSession(directories)
        val future = System.currentTimeMillis() + 20 * 60_000L
        try {
            RestrictedTriageSessionWorkspace.sweepAbandonedSessions(root, future)
            assertTrue(session.isDirectory)
        } finally { lease.close() }
        RestrictedTriageSessionWorkspace.sweepAbandonedSessions(root, future)
        assertFalse(session.exists())
    }

    @Test fun sharedProtocolForDailyAnalysisIsEphemeralLowEffortAndToolFree() {
        val request = PhoneRoutineRestrictedAnalysisPrompt.request("""{"evidence":[]}""")
        val params = JSONObject(RestrictedTriageAppServerPolicy.threadStartRequest("/empty", "gpt-6-luna",
            ReasoningEffort.LOW, request.baseInstructions, request.developerInstructions).json).getJSONObject("params")
        assertTrue(params.getBoolean("ephemeral"))
        assertEquals("read-only", params.getString("sandbox"))
        assertEquals("never", params.getString("approvalPolicy"))
        assertFalse(params.has("dynamicTools"))
        val command = RestrictedTriageAppServerPolicy.command(File("/synthetic/codex"))
        assertTrue("--strict-config" in command)
        for (disabled in listOf("features.shell_tool=false", "features.multi_agent=false", "features.apps=false",
            "features.plugins=false", "features.memories=false", "tools.web_search=false")) assertTrue(disabled in command)
    }

    private fun withTemporaryDirectory(block: (File) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val root = Files.createTempDirectory(context.cacheDir.toPath(), "restricted-analysis-contract-").toFile()
        try { block(root) } finally { root.deleteRecursively() }
    }
}
