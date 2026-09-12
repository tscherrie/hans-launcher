package ai.hans.standard.diagnostics

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationDiagnosticsCollectorTest {
    private val request = MigrationDiagnosticsRequest(
        1,
        "70468768-7ee7-4bc2-b9c9-bb8ddff3345f",
        MigrationDiagnosticsPhase.AFTER_TRANSITION,
    )

    @Test
    fun deterministicCollectionReportsOnlyCountsPresenceAndDomainSeparatedThreadHash() =
        withFixture { fixture ->
            fixture.write("no_backup/codex/home/auth.json", "sk-secret-auth")
            fixture.write("no_backup/codex/home/memories/MEMORY.md", "private memory")
            fixture.write("no_backup/codex/home/memories/memory_summary.md", "private summary")
            fixture.write("no_backup/codex/home/sessions/2026/receipt.jsonl", "private chat")
            fixture.write("no_backup/hans-setup-state-v1.json", "private setup")
            fixture.write("no_backup/hans_automations_v1.db", "private automation")
            fixture.write("databases/notification_inbox.db", "private notification")
            fixture.write("files/hans-user-profile-v1.json", "Jeremias Example")
            fixture.write(
                "shared_prefs/hans_codex_session_v1.xml",
                """<?xml version="1.0"?><map><string name="thread_id">thread-secret-123</string></map>""",
            )
            val before = fixture.snapshotTree()
            val collector = fixture.collector(
                readiness = {
                    readiness(version = 2, accountRead = true, threadId = "thread-secret-123")
                },
            )

            val first = collector.collect(request)
            val second = collector.collect(request)
            val after = fixture.snapshotTree()

            assertEquals(first, second)
            assertEquals(before, after)
            listOf(
                "sk-secret-auth",
                "private memory",
                "private summary",
                "private chat",
                "private notification",
                "Jeremias Example",
                "thread-secret-123",
                "receipt.jsonl",
            ).forEach { secret -> assertFalse(secret, first.contains(secret)) }
            val root = JSONObject(first)
            assertEquals("hans.migration-diagnostics", root.getString("schema"))
            val payload = root.getJSONObject("payload")
            val persistence = payload.getJSONObject("persistence")
            assertTrue(persistence.getBoolean("authFilePresent"))
            assertTrue(persistence.getBoolean("authFileNonEmpty"))
            assertEquals(2, persistence.getInt("memoryRegularFileCount"))
            assertEquals(1, persistence.getInt("codexSessionRegularFileCount"))
            assertTrue(persistence.getBoolean("threadIdPresent"))
            assertEquals(
                MigrationDiagnosticsHashes.thread("thread-secret-123"),
                persistence.getString("threadCorrelationSha256"),
            )
            assertTrue(payload.getJSONObject("sessionReadiness").getBoolean("present"))
        }

    @Test
    fun symlinkInRelevantTreeFailsClosedWithoutFollowingIt() = withFixture { fixture ->
        val external = Files.createTempFile("hans-external-secret", ".txt")
        Files.write(external, "outside".toByteArray(StandardCharsets.UTF_8))
        val sessions = fixture.directory("no_backup/codex/home/sessions")
        try {
            Files.createSymbolicLink(sessions.resolve("escape"), external)
            assertFails { fixture.collector().collect(request) }
        } finally {
            Files.deleteIfExists(external)
        }
    }

    @Test
    fun symlinkedFixedParentFailsClosed() = withFixture { fixture ->
        val external = Files.createTempDirectory("hans-external-auth")
        Files.write(
            external.resolve("auth.json"),
            "secret".toByteArray(StandardCharsets.UTF_8),
        )
        val codex = fixture.directory("no_backup/codex")
        try {
            Files.createSymbolicLink(codex.resolve("home"), external)
            assertFails { fixture.collector().collect(request) }
        } finally {
            external.toFile().deleteRecursively()
        }
    }

    @Test
    fun fileCountBoundsFailClosed() = withFixture { fixture ->
        repeat(257) { index ->
            fixture.write("shared_prefs/pref-$index.xml", "<map/>")
        }

        assertFails { fixture.collector().collect(request) }
    }

    @Test
    fun replacementBetweenStatAndReadIsRejectedAsUnstable() = withFixture { fixture ->
        val auth = fixture.write("no_backup/codex/home/auth.json", "secret")
        var changed = false
        val collector = fixture.collector(
            MigrationStableReadHook { path ->
                if (!changed && path == auth) {
                    changed = true
                    Files.write(
                        path,
                        "changed".toByteArray(StandardCharsets.UTF_8),
                        StandardOpenOption.APPEND,
                    )
                }
            },
        )

        assertFails { collector.collect(request) }
        assertTrue(changed)
    }

    @Test
    fun presentReadinessMustMatchInstalledVersionAndAuthoritativeAccountRead() =
        withFixture { fixture ->
            fixture.write("no_backup/codex/home/auth.json", "secret")
            fixture.write(
                "shared_prefs/hans_codex_session_v1.xml",
                """<map><string name="thread_id">thread-1</string></map>""",
            )
            val current = readiness(version = 2, accountRead = true)
            val output = fixture.collector(readiness = { current }).collect(request)
            val session = JSONObject(output).getJSONObject("payload")
                .getJSONObject("sessionReadiness")
            assertTrue(session.getBoolean("present"))
            assertTrue(session.getBoolean("liveThreadMatchesPersisted"))

            assertFails {
                fixture.collector(readiness = { readiness(version = 1, accountRead = true) })
                    .collect(request)
            }
            assertFails {
                fixture.collector(readiness = { readiness(version = 2, accountRead = false) })
                    .collect(request)
            }
            assertFails {
                fixture.collector(
                    readiness = { readiness(version = 2, accountRead = true) },
                    interactiveProcessId = { 4321 },
                ).collect(request)
            }
            assertFails {
                fixture.collector(
                    readiness = {
                        readiness(version = 2, accountRead = true)
                            .copy(observationTimestampMillis = 200)
                    },
                    clockMillis = { 200 + MigrationSessionReadinessSnapshot.MAX_AGE_MILLIS + 1 },
                ).collect(request)
            }
        }

    @Test
    fun fixedPreferencesParserAcceptsOneEscapedStringAndRejectsAmbiguityOrXmlExpansion() {
        val valid = """<?xml version="1.0"?><map><string name="thread_id">a&amp;b</string></map>"""
            .toByteArray(StandardCharsets.UTF_8)
        assertEquals("a&b", readFixedSharedPreferenceString(valid, "thread_id"))

        listOf(
            """<map><string name="thread_id">a</string><string name="thread_id">b</string></map>""",
            """<!DOCTYPE map [<!ENTITY xxe SYSTEM "file:///etc/passwd">]><map><string name="thread_id">&xxe;</string></map>""",
            """<map><string name="thread_id">a</string></map><map/>""",
            """<map><int name="thread_id" value="7"/></map>""",
        ).forEach { document ->
            assertFails {
                readFixedSharedPreferenceString(
                    document.toByteArray(StandardCharsets.UTF_8),
                    "thread_id",
                )
            }
        }
    }

    @Test
    fun collectorFailsClosedForMissingReadinessActiveWorkAndPendingCameraCapture() =
        withFixture { fixture ->
            fixture.write("no_backup/codex/home/auth.json", "secret")
            fixture.write(
                "shared_prefs/hans_codex_session_v1.xml",
                """<map><string name="thread_id">thread-1</string></map>""",
            )
            assertFails { fixture.collector().collect(request) }
            assertFails {
                fixture.collector(
                    readiness = {
                        readiness(2, true).copy(activeTurn = true)
                    },
                ).collect(request)
            }
            fixture.write(
                "shared_prefs/hans_camera_capture_v1.xml",
                """<map><string name="pending_file">private-photo.jpg</string></map>""",
            )
            assertFails {
                fixture.collector(readiness = { readiness(2, true) }).collect(request)
            }
        }

    @Test
    fun blockedReadinessIsActionableButNeverAnAcceptedReceiptAndDoesNotWriteStores() =
        withFixture { fixture ->
            fixture.write("no_backup/codex/home/auth.json", "secret")
            fixture.write(
                "shared_prefs/hans_codex_session_v1.xml",
                """<map><string name="thread_id">thread-1</string></map>""",
            )
            val ready = readiness(2, true)
            val cases = listOf(
                ready.copy(setupComplete = false) to MigrationDiagnosticsBlocker.SETUP_INCOMPLETE,
                ready.copy(runtimeReady = false) to MigrationDiagnosticsBlocker.RUNTIME_NOT_READY,
                ready.copy(memoryModeEnabledAck = false) to MigrationDiagnosticsBlocker.MEMORY_NOT_READY,
                ready.copy(speechCredentialAvailable = false) to MigrationDiagnosticsBlocker.SPEECH_NOT_READY,
                ready.copy(threadResumeConfirmed = false) to MigrationDiagnosticsBlocker.THREAD_NOT_RESUMED,
                ready.copy(threadCorrelationSha256 = "c".repeat(64)) to MigrationDiagnosticsBlocker.THREAD_MISMATCH,
                ready.copy(effectiveModel = null, effectiveReasoningEffort = null) to
                    MigrationDiagnosticsBlocker.SELECTION_NOT_READY,
                readiness(2, false) to MigrationDiagnosticsBlocker.ACCOUNT_NOT_READY,
                ready.copy(longVersionCode = 1) to MigrationDiagnosticsBlocker.READINESS_OBSOLETE,
                ready.copy(interactiveProcessId = 4321) to MigrationDiagnosticsBlocker.READINESS_OBSOLETE,
                ready.copy(activeTurn = true) to MigrationDiagnosticsBlocker.TURN_ACTIVE,
                ready.copy(dictationActive = true) to MigrationDiagnosticsBlocker.DICTATION_ACTIVE,
                ready.copy(liveVoiceActive = true) to MigrationDiagnosticsBlocker.LIVE_VOICE_ACTIVE,
                ready.copy(activeAutomationCount = 1) to MigrationDiagnosticsBlocker.AUTOMATION_ACTIVE,
            )
            val before = fixture.snapshotTree()
            cases.forEach { (snapshot, blocker) ->
                assertEquals(
                    MigrationDiagnosticsOutcome.Blocked(blocker),
                    MigrationDiagnosticsOutcome.collect { fixture.collector(readiness = { snapshot }).collect(request) },
                )
            }
            assertEquals(
                MigrationDiagnosticsOutcome.Blocked(MigrationDiagnosticsBlocker.READINESS_STALE),
                MigrationDiagnosticsOutcome.collect {
                    fixture.collector(
                        readiness = { ready },
                        clockMillis = { 201 + MigrationSessionReadinessSnapshot.MAX_AGE_MILLIS },
                    ).collect(request)
                },
            )
            assertEquals(before, fixture.snapshotTree())
        }

    @Test
    fun missingSnapshotProcessOrAuthHasAnExactDataFreeReason() = withFixture { fixture ->
        assertEquals(
            MigrationDiagnosticsOutcome.Blocked(MigrationDiagnosticsBlocker.AUTH_MISSING),
            MigrationDiagnosticsOutcome.collect { fixture.collector().collect(request) },
        )
        fixture.write("no_backup/codex/home/auth.json", "secret")
        assertEquals(
            MigrationDiagnosticsOutcome.Blocked(MigrationDiagnosticsBlocker.THREAD_MISSING),
            MigrationDiagnosticsOutcome.collect { fixture.collector().collect(request) },
        )
        fixture.write(
            "shared_prefs/hans_codex_session_v1.xml",
            """<map><string name="thread_id">thread-1</string></map>""",
        )
        assertEquals(
            MigrationDiagnosticsOutcome.Blocked(MigrationDiagnosticsBlocker.READINESS_MISSING),
            MigrationDiagnosticsOutcome.collect { fixture.collector().collect(request) },
        )
        assertEquals(
            MigrationDiagnosticsOutcome.Blocked(MigrationDiagnosticsBlocker.PROCESS_MISSING),
            MigrationDiagnosticsOutcome.collect {
                fixture.collector(interactiveProcessId = { null }).collect(request)
            },
        )
    }

    private fun readiness(
        version: Long,
        accountRead: Boolean,
        threadId: String = "thread-1",
    ) =
        MigrationSessionReadinessSnapshot(
            packageName = "ai.hans.standard",
            longVersionCode = version,
            packageLastUpdateTimeMillis = 100,
            observationTimestampMillis = 200,
            interactiveProcessId = 1234,
            accountPhase = if (accountRead) {
                MigrationAccountPhase.SIGNED_IN
            } else {
                MigrationAccountPhase.UNKNOWN
            },
            accountReadComplete = accountRead,
            runtimeReady = accountRead,
            threadCorrelationSha256 = MigrationDiagnosticsHashes.thread(threadId),
            threadResumeConfirmed = true,
            memoryModeEnabledAck = true,
            effectiveModel = "gpt-5.6-luna",
            effectiveReasoningEffort = "max",
            speechCredentialAvailable = true,
            setupComplete = true,
            activeTurn = false,
            dictationActive = false,
            liveVoiceActive = false,
            activeAutomationCount = 0,
        )

    private inline fun withFixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("hans-migration-collector")
        try {
            val fixture = Fixture(root)
            fixture.directory("files")
            fixture.directory("no_backup")
            block(fixture)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private class Fixture(private val root: Path) {
        fun directory(relative: String): Path = root.resolve(relative).also(Files::createDirectories)

        fun write(relative: String, content: String): Path {
            val target = root.resolve(relative)
            Files.createDirectories(target.parent)
            Files.write(target, content.toByteArray(StandardCharsets.UTF_8))
            return target
        }

        fun collector(
            hook: MigrationStableReadHook = MigrationStableReadHook.NONE,
            readiness: () -> MigrationSessionReadinessSnapshot? = { null },
            interactiveProcessId: () -> Int? = { 1234 },
            clockMillis: () -> Long = { 200 },
        ) = MigrationDiagnosticsCollector(
            roots = MigrationDiagnosticsRoots(
                dataDirectory = root,
                filesDirectory = root.resolve("files"),
                noBackupDirectory = root.resolve("no_backup"),
            ),
            packageSnapshot = packageSnapshot(),
            readinessReader = readiness,
            interactiveProcessId = interactiveProcessId,
            clockMillis = clockMillis,
            stableReadHook = hook,
        )

        fun snapshotTree(): List<TreeEntry> = Files.walk(root).use { paths ->
            paths.sorted().map { path ->
                val attributes = Files.readAttributes(
                    path,
                    BasicFileAttributes::class.java,
                    LinkOption.NOFOLLOW_LINKS,
                )
                TreeEntry(
                    relative = root.relativize(path).toString(),
                    directory = attributes.isDirectory,
                    modifiedMillis = attributes.lastModifiedTime().toMillis(),
                    bytes = if (attributes.isRegularFile) Files.readAllBytes(path).toList() else emptyList(),
                )
            }.toList()
        }

        private fun packageSnapshot() = MigrationPackageSnapshot(
            packageName = "ai.hans.standard",
            longVersionCode = 2,
            versionName = "0.1.0",
            uid = 10_123,
            dataDirInode = 42,
            debuggable = false,
            currentSignerSha256 = "a".repeat(64),
            signingHistorySha256 = listOf("a".repeat(64)),
            homeRoleHeld = true,
            lastUpdateTimeMillis = 100,
        )
    }

    private data class TreeEntry(
        val relative: String,
        val directory: Boolean,
        val modifiedMillis: Long,
        val bytes: List<Byte>,
    )

    private fun assertFails(block: () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: Exception) {
            failed = true
        }
        assertTrue(failed)
    }
}
