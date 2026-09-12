package ai.hans.standard.diagnostics

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

internal data class MigrationPackageSnapshot(
    val packageName: String,
    val longVersionCode: Long,
    val versionName: String,
    val uid: Int,
    val dataDirInode: Long,
    val debuggable: Boolean,
    val currentSignerSha256: String,
    val signingHistorySha256: List<String>,
    val homeRoleHeld: Boolean,
    val lastUpdateTimeMillis: Long,
) {
    init {
        require(packageName == PACKAGE_NAME)
        require(longVersionCode > 0L)
        require(versionName.length <= 128 && versionName.none(Char::isISOControl))
        require(uid >= 0)
        require(dataDirInode > 0L)
        require(currentSignerSha256.matches(SHA256))
        require(signingHistorySha256.size <= 2)
        require(signingHistorySha256.all(SHA256::matches))
        require(signingHistorySha256.distinct().size == signingHistorySha256.size)
        require(lastUpdateTimeMillis > 0L)
    }

    companion object {
        const val PACKAGE_NAME = "ai.hans.standard"
        private val SHA256 = Regex("[0-9a-f]{64}")
    }
}

internal data class MigrationDiagnosticsRoots(
    val dataDirectory: Path,
    val filesDirectory: Path,
    val noBackupDirectory: Path,
) {
    init {
        require(dataDirectory.isAbsolute && filesDirectory.isAbsolute && noBackupDirectory.isAbsolute)
        require(filesDirectory.normalize().startsWith(dataDirectory.normalize()))
        require(noBackupDirectory.normalize().startsWith(dataDirectory.normalize()))
    }
}

/**
 * Read-only migration projection. It never calls an Android store constructor because many of
 * those constructors legitimately create files or SQLite sidecars as part of normal startup.
 */
internal class MigrationDiagnosticsCollector(
    private val roots: MigrationDiagnosticsRoots,
    private val packageSnapshot: MigrationPackageSnapshot,
    private val readinessReader: () -> MigrationSessionReadinessSnapshot?,
    private val interactiveProcessId: () -> Int?,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val stableReadHook: MigrationStableReadHook = MigrationStableReadHook.NONE,
) {
    fun collect(request: MigrationDiagnosticsRequest): String {
        val paths = MigrationStorePaths(roots, stableReadHook)
        val persistedThreadId = paths.readPersistedThreadId()
        val persistedThreadHash = persistedThreadId?.let(MigrationDiagnosticsHashes::thread)
        val persistence = paths.persistencePayload(persistedThreadId, persistedThreadHash)
        requireMigrationCondition(
            persistence["authFilePresent"] == true && persistence["authFileNonEmpty"] == true,
            MigrationDiagnosticsBlocker.AUTH_MISSING,
        )
        requireMigrationCondition(
            persistence["threadIdPresent"] == true && persistedThreadHash != null,
            MigrationDiagnosticsBlocker.THREAD_MISSING,
        )
        requireMigrationCondition(
            persistence["backupImportJournalPresent"] == false,
            MigrationDiagnosticsBlocker.BACKUP_IMPORT_PENDING,
        )
        requireMigrationCondition(
            persistence["cameraCapturePending"] == false,
            MigrationDiagnosticsBlocker.CAMERA_PENDING,
        )
        val processId = interactiveProcessId()
            ?: throw MigrationDiagnosticsBlocked(MigrationDiagnosticsBlocker.PROCESS_MISSING)
        val readiness = (readinessReader()
            ?: throw MigrationDiagnosticsBlocked(MigrationDiagnosticsBlocker.READINESS_MISSING)).also { snapshot ->
            snapshot.requireCurrentPackage(packageSnapshot, processId, clockMillis())
            requireMigrationCondition(
                snapshot.accountReadComplete && snapshot.accountPhase == MigrationAccountPhase.SIGNED_IN,
                MigrationDiagnosticsBlocker.ACCOUNT_NOT_READY,
            )
            requireMigrationCondition(snapshot.runtimeReady, MigrationDiagnosticsBlocker.RUNTIME_NOT_READY)
            requireMigrationCondition(
                snapshot.effectiveModel != null && snapshot.effectiveReasoningEffort != null,
                MigrationDiagnosticsBlocker.SELECTION_NOT_READY,
            )
            requireMigrationCondition(
                snapshot.threadCorrelationSha256 == persistedThreadHash,
                MigrationDiagnosticsBlocker.THREAD_MISMATCH,
            )
            requireMigrationCondition(snapshot.threadResumeConfirmed, MigrationDiagnosticsBlocker.THREAD_NOT_RESUMED)
            requireMigrationCondition(snapshot.memoryModeEnabledAck, MigrationDiagnosticsBlocker.MEMORY_NOT_READY)
            requireMigrationCondition(snapshot.speechCredentialAvailable, MigrationDiagnosticsBlocker.SPEECH_NOT_READY)
            requireMigrationCondition(snapshot.setupComplete, MigrationDiagnosticsBlocker.SETUP_INCOMPLETE)
            requireMigrationCondition(!snapshot.activeTurn, MigrationDiagnosticsBlocker.TURN_ACTIVE)
            requireMigrationCondition(!snapshot.dictationActive, MigrationDiagnosticsBlocker.DICTATION_ACTIVE)
            requireMigrationCondition(!snapshot.liveVoiceActive, MigrationDiagnosticsBlocker.LIVE_VOICE_ACTIVE)
            requireMigrationCondition(snapshot.activeAutomationCount == 0, MigrationDiagnosticsBlocker.AUTOMATION_ACTIVE)
        }
        val sessionPayload = readiness.toPayload(persistedThreadHash)
        val payload = linkedMapOf<String, Any?>(
            "app" to linkedMapOf(
                "currentSignerSha256" to packageSnapshot.currentSignerSha256,
                "dataDirInode" to packageSnapshot.dataDirInode,
                "debuggable" to packageSnapshot.debuggable,
                "homeRoleHeld" to packageSnapshot.homeRoleHeld,
                "longVersionCode" to packageSnapshot.longVersionCode,
                "packageName" to packageSnapshot.packageName,
                "signingHistorySha256" to packageSnapshot.signingHistorySha256,
                "uid" to packageSnapshot.uid,
                "versionName" to packageSnapshot.versionName,
            ),
            "persistence" to persistence,
            "phase" to request.phase.wireValue,
            "requestId" to request.requestId,
            "sessionReadiness" to sessionPayload,
        )
        val payloadJson = MigrationCanonicalJson.encode(payload)
        val envelope = linkedMapOf<String, Any?>(
            "payload" to payload,
            "payloadSha256" to MigrationDiagnosticsHashes.payload(payloadJson),
            "phase" to request.phase.wireValue,
            "requestId" to request.requestId,
            "schema" to MigrationDiagnosticsContract.ENVELOPE_SCHEMA,
            "status" to "ok",
            "version" to MigrationDiagnosticsContract.PROTOCOL_VERSION,
        )
        return MigrationCanonicalJson.encode(envelope).also { result ->
            require(result.toByteArray(StandardCharsets.UTF_8).size <= MigrationDiagnosticsContract.MAX_RESULT_BYTES) {
                "migration_result_size"
            }
        }
    }

    private fun MigrationSessionReadinessSnapshot.toPayload(
        persistedThreadHash: String?,
    ): Map<String, Any?> {
        val liveHash = threadCorrelationSha256
        return linkedMapOf(
            "accountPhase" to accountPhase.wireValue,
            "accountReadComplete" to accountReadComplete,
            "activeAutomationCount" to activeAutomationCount,
            "activeTurn" to activeTurn,
            "dictationActive" to dictationActive,
            "effectiveModel" to effectiveModel,
            "effectiveReasoningEffort" to effectiveReasoningEffort,
            "liveThreadMatchesPersisted" to (liveHash == persistedThreadHash),
            "liveVoiceActive" to liveVoiceActive,
            "memoryModeEnabledAck" to memoryModeEnabledAck,
            "observationTimestampMillis" to observationTimestampMillis,
            "present" to true,
            "runtimeReady" to runtimeReady,
            "setupComplete" to setupComplete,
            "speechCredentialAvailable" to speechCredentialAvailable,
            "threadCorrelationSha256" to liveHash,
            "threadResumeConfirmed" to threadResumeConfirmed,
        )
    }
}

internal fun interface MigrationStableReadHook {
    fun afterFirstStat(path: Path)

    companion object {
        val NONE = MigrationStableReadHook {}
    }
}

private class MigrationStorePaths(
    private val roots: MigrationDiagnosticsRoots,
    private val stableReadHook: MigrationStableReadHook,
) {
    private val data = roots.dataDirectory.normalize()
    private val files = roots.filesDirectory.normalize()
    private val noBackup = roots.noBackupDirectory.normalize()
    private val sharedPreferences = data.resolve("shared_prefs")
    private val databases = data.resolve("databases")
    private val codexHome = noBackup.resolve("codex/home")

    init {
        requireFixedRoot(data, allowMissing = false)
        requireFixedRoot(files, allowMissing = true)
        requireFixedRoot(noBackup, allowMissing = true)
    }

    fun readPersistedThreadId(): String? {
        val preferences = sharedPreferences.resolve("hans_codex_session_v1.xml")
        requireSafeAncestors(preferences)
        val bytes = stableRead(
            preferences,
            MAX_PREFERENCES_BYTES,
            missingAllowed = true,
            stableReadHook = stableReadHook,
        )
            ?: return null
        return try {
            val value = readFixedSharedPreferenceString(bytes, "thread_id") ?: return null
            value.takeIf {
                it.isNotBlank() &&
                    it.length <= MAX_THREAD_ID_CHARACTERS &&
                    it.none(Char::isISOControl)
            } ?: error("migration_thread_invalid")
        } finally {
            bytes.fill(0)
        }
    }

    fun persistencePayload(
        persistedThreadId: String?,
        persistedThreadHash: String?,
    ): Map<String, Any?> {
        val auth = probeRegularFile(
            requireSafeAncestors(codexHome.resolve("auth.json")),
            MAX_AUTH_BYTES,
            missingAllowed = true,
            stableReadHook = stableReadHook,
        )
        val memoryRoot = codexHome.resolve("memories")
        val cameraPreferences = sharedPreferences.resolve("hans_camera_capture_v1.xml")
        val cameraBytes = stableRead(
            requireSafeAncestors(cameraPreferences),
            MAX_PREFERENCES_BYTES,
            missingAllowed = true,
            stableReadHook = stableReadHook,
        )
        val cameraPending = try {
            cameraBytes?.let { readFixedSharedPreferenceString(it, "pending_file") } != null
        } finally {
            cameraBytes?.fill(0)
        }
        return linkedMapOf(
            "actionKeyPreferencesPresent" to present(sharedPreferences.resolve("hans_action_key_v1.xml")),
            "authFileNonEmpty" to (auth?.size?.let { it > 0L } ?: false),
            "authFilePresent" to (auth != null),
            "automationLegacyStorePresent" to present(noBackup.resolve("hans_automations_v1.json")),
            "automationStorePresent" to present(noBackup.resolve("hans_automations_v1.db")),
            "automationWorkLedgerPreferencesPresent" to
                present(sharedPreferences.resolve("hans_automation_work_generations_v1.xml")),
            "backupImportJournalPresent" to
                present(noBackup.resolve("hans-backup-import-journal-v1.json")),
            "cameraCapturePending" to cameraPending,
            "cameraCapturePreferencesPresent" to present(cameraPreferences),
            "codexArchivedSessionRegularFileCount" to countRegularFiles(
                codexHome.resolve("archived_sessions"),
                MAX_CODEX_SESSION_FILES,
                MAX_DIRECTORY_DEPTH,
            ),
            "codexSessionRegularFileCount" to countRegularFiles(
                codexHome.resolve("sessions"),
                MAX_CODEX_SESSION_FILES,
                MAX_DIRECTORY_DEPTH,
            ),
            "consentsStorePresent" to present(noBackup.resolve("persistent-android-consents-v2.json")),
            "databaseRegularFileCount" to countRegularFiles(
                databases,
                MAX_DATABASE_FILES,
                1,
            ),
            "mediaLeasesStorePresent" to present(noBackup.resolve("media-turn-leases-v1.json")),
            "mediaRegularFileCount" to countRegularFiles(
                files.resolve("hans-media"),
                MAX_MEDIA_FILES,
                MAX_MEDIA_DIRECTORY_DEPTH,
            ),
            "memoryDatabasePresent" to present(codexHome.resolve("memories_1.sqlite")),
            "memoryRegistryPresent" to present(memoryRoot.resolve("MEMORY.md"), memoryRoot),
            "memoryRegularFileCount" to countRegularFiles(
                memoryRoot,
                MAX_MEMORY_FILES,
                MAX_DIRECTORY_DEPTH,
            ),
            "memorySummaryPresent" to present(memoryRoot.resolve("memory_summary.md"), memoryRoot),
            "notificationInboxStorePresent" to present(databases.resolve("notification_inbox.db")),
            "notificationListenerBaselinePresent" to
                present(noBackup.resolve("notification-listener-baseline-v1")),
            "notificationPrivacyInitializedPresent" to
                present(noBackup.resolve("notification-privacy-v1.json.initialized")),
            "notificationPrivacyPurgeFencePresent" to
                present(noBackup.resolve("notification-privacy-purge-fence-v1")),
            "notificationPrivacyStorePresent" to present(noBackup.resolve("notification-privacy-v1.json")),
            "notificationTriageInitializedPresent" to
                present(noBackup.resolve("notification-triage-v1.json.initialized")),
            "notificationTriageStorePresent" to present(noBackup.resolve("notification-triage-v1.json")),
            "mp01VendorActionAckPresent" to present(noBackup.resolve("hans_mp01_vendor_action_v3.json")),
            "pendingDictationStorePresent" to present(noBackup.resolve("pending-dictations-v1.json")),
            "privateSpacePreferencesPresent" to
                present(sharedPreferences.resolve("hans_private_space_visibility_v1.xml")),
            "profileStorePresent" to present(files.resolve("hans-user-profile-v1.json")),
            "sessionPreferencesPresent" to
                present(sharedPreferences.resolve("hans_codex_session_v1.xml")),
            "setupHandoffStorePresent" to present(noBackup.resolve("hans-setup-handoff-v1.json")),
            "setupStorePresent" to present(noBackup.resolve("hans-setup-state-v1.json")),
            "sharedPreferencesRegularFileCount" to countRegularFiles(
                sharedPreferences,
                MAX_SHARED_PREFERENCE_FILES,
                1,
            ),
            "settingsPreferencesPresent" to present(sharedPreferences.resolve("hans_settings_v1.xml")),
            "speechCredentialPreferencesPresent" to
                present(sharedPreferences.resolve("hans_speech_credential_v1.xml")),
            "threadCorrelationSha256" to persistedThreadHash,
            "threadIdPresent" to (persistedThreadId != null),
            "dynamicToolContractPreferencesPresent" to
                present(sharedPreferences.resolve("hans_dynamic_tool_contract_v1.xml")),
            "importedPluginChoicesStorePresent" to
                present(noBackup.resolve("hans-imported-plugin-choices-v1.json")),
            "validatedAnnouncementsPrivacyFencePresent" to present(
                noBackup.resolve("validated-notification-announcements.json.privacy-fence"),
            ),
            "validatedAnnouncementsPrivacyGenerationPresent" to present(
                noBackup.resolve("validated-notification-announcements.json.privacy-generation"),
            ),
            "validatedAnnouncementsStorePresent" to
                present(noBackup.resolve("validated-notification-announcements.json")),
            "workspaceDirectoryPresent" to directoryPresent(files.resolve("codex-workspace")),
        )
    }

    private fun present(path: Path, requiredParent: Path = path.parent): Boolean =
        probeRegularFile(
            requireSafeAncestors(requireContained(path, requiredParent)),
            MAX_STORE_BYTES,
            missingAllowed = true,
            stableReadHook = stableReadHook,
        ) != null

    private fun directoryPresent(path: Path): Boolean {
        val normalized = requireSafeAncestors(path)
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return false
        require(!Files.isSymbolicLink(normalized)) { "migration_symlink" }
        val before = attributes(normalized)
        require(before.isDirectory) { "migration_directory_type" }
        val after = attributes(normalized)
        require(sameIdentity(before, after)) { "migration_unstable" }
        return true
    }

    private fun countRegularFiles(root: Path, maximum: Int, maximumDepth: Int): Int {
        val normalized = root.normalize()
        requireContained(normalized, data)
        requireSafeAncestors(normalized)
        if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return 0
        require(!Files.isSymbolicLink(normalized)) { "migration_symlink" }
        require(Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
            "migration_directory_type"
        }
        var count = 0
        var scanned = 0
        val pending = ArrayDeque<Pair<Path, Int>>()
        pending.add(normalized to 0)
        while (pending.isNotEmpty()) {
            val (directory, depth) = pending.removeFirst()
            val before = attributes(directory)
            require(before.isDirectory && !Files.isSymbolicLink(directory)) {
                "migration_directory_type"
            }
            val entries = Files.newDirectoryStream(directory).use { stream ->
                stream.toList().sortedBy { it.fileName.toString() }
            }
            entries.forEach { entry ->
                scanned += 1
                require(scanned <= MAX_SCANNED_ENTRIES) { "migration_entry_bound" }
                requireContained(entry.normalize(), normalized)
                require(!Files.isSymbolicLink(entry)) { "migration_symlink" }
                val entryAttributes = attributes(entry)
                when {
                    entryAttributes.isRegularFile -> {
                        probeRegularFile(
                            entry,
                            MAX_COUNTED_FILE_BYTES,
                            missingAllowed = false,
                            stableReadHook = stableReadHook,
                        )
                        count += 1
                        require(count <= maximum) { "migration_count_bound" }
                    }
                    entryAttributes.isDirectory -> {
                        require(depth < maximumDepth) { "migration_depth_bound" }
                        pending.add(entry to depth + 1)
                    }
                    else -> error("migration_file_type")
                }
            }
            val after = attributes(directory)
            require(sameIdentity(before, after)) { "migration_unstable" }
        }
        return count
    }

    private fun requireFixedRoot(root: Path, allowMissing: Boolean) {
        require(root.isAbsolute && root == root.normalize()) { "migration_root" }
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            require(allowMissing) { "migration_root_missing" }
            return
        }
        require(!Files.isSymbolicLink(root)) { "migration_symlink" }
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) { "migration_root_type" }
    }

    private fun requireSafeAncestors(path: Path): Path {
        val normalized = requireContained(path, data)
        val parent = normalized.parent ?: return normalized
        var current = data
        data.relativize(parent).forEach { segment ->
            current = current.resolve(segment)
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(current)) { "migration_symlink" }
                require(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    "migration_path_parent"
                }
            }
        }
        return normalized
    }

    companion object {
        private const val MAX_AUTH_BYTES = 1L * 1_024L * 1_024L
        private const val MAX_PREFERENCES_BYTES = 64L * 1_024L
        private const val MAX_STORE_BYTES = 256L * 1_024L * 1_024L
        private const val MAX_COUNTED_FILE_BYTES = 512L * 1_024L * 1_024L
        private const val MAX_THREAD_ID_CHARACTERS = 256
        private const val MAX_SHARED_PREFERENCE_FILES = 256
        private const val MAX_DATABASE_FILES = 128
        private const val MAX_MEMORY_FILES = 4_096
        private const val MAX_CODEX_SESSION_FILES = 8_192
        private const val MAX_MEDIA_FILES = 1_024
        private const val MAX_MEDIA_DIRECTORY_DEPTH = 2
        private const val MAX_DIRECTORY_DEPTH = 16
        private const val MAX_SCANNED_ENTRIES = 16_384
    }
}

private data class StableFileProbe(val size: Long)

private fun probeRegularFile(
    path: Path,
    maximumBytes: Long,
    missingAllowed: Boolean,
    stableReadHook: MigrationStableReadHook = MigrationStableReadHook.NONE,
): StableFileProbe? {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        if (missingAllowed) return null
        error("migration_file_missing")
    }
    require(!Files.isSymbolicLink(path)) { "migration_symlink" }
    val before = attributes(path)
    require(before.isRegularFile) { "migration_file_type" }
    require(before.size() in 0..maximumBytes) { "migration_file_bound" }
    stableReadHook.afterFirstStat(path)
    Files.newByteChannel(path, setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
        val buffer = java.nio.ByteBuffer.allocate(PROBE_BYTES)
        channel.read(buffer)
        buffer.clear()
        if (before.size() > PROBE_BYTES) {
            channel.position((before.size() - PROBE_BYTES).coerceAtLeast(0))
            channel.read(buffer)
        }
        buffer.array().fill(0)
    }
    val after = attributes(path)
    require(sameIdentity(before, after)) { "migration_unstable" }
    return StableFileProbe(after.size())
}

internal fun stableRead(
    path: Path,
    maximumBytes: Long,
    missingAllowed: Boolean,
    stableReadHook: MigrationStableReadHook = MigrationStableReadHook.NONE,
): ByteArray? {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
        if (missingAllowed) return null
        error("migration_file_missing")
    }
    require(!Files.isSymbolicLink(path)) { "migration_symlink" }
    val before = attributes(path)
    require(before.isRegularFile) { "migration_file_type" }
    require(before.size() in 0..maximumBytes) { "migration_file_bound" }
    stableReadHook.afterFirstStat(path)
    val output = ByteArrayOutputStream(before.size().toInt().coerceAtMost(8_192))
    Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { input ->
        val buffer = ByteArray(4_096)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maximumBytes) { "migration_file_bound" }
            output.write(buffer, 0, count)
        }
        buffer.fill(0)
    }
    val after = attributes(path)
    require(sameIdentity(before, after)) { "migration_unstable" }
    return output.toByteArray()
}

private fun attributes(path: Path): BasicFileAttributes = Files.readAttributes(
    path,
    BasicFileAttributes::class.java,
    LinkOption.NOFOLLOW_LINKS,
)

private fun sameIdentity(first: BasicFileAttributes, second: BasicFileAttributes): Boolean =
    first.isRegularFile == second.isRegularFile &&
        first.isDirectory == second.isDirectory &&
        first.size() == second.size() &&
        first.lastModifiedTime() == second.lastModifiedTime() &&
        (first.fileKey() == null || second.fileKey() == null || first.fileKey() == second.fileKey())

private fun requireContained(path: Path, root: Path): Path {
    val normalizedPath = path.normalize()
    val normalizedRoot = root.normalize()
    require(normalizedPath.startsWith(normalizedRoot)) { "migration_path_containment" }
    return normalizedPath
}

/** Strict, Android-portable parser for one fixed SharedPreferences string. */
internal fun readFixedSharedPreferenceString(bytes: ByteArray, key: String): String? {
    require(bytes.isNotEmpty() && bytes.size <= 64 * 1_024) { "migration_preferences_size" }
    require(key.matches(Regex("[a-z0-9_]{1,64}"))) { "migration_preferences_key" }
    val source = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString()
    require(!source.contains("<!")) { "migration_preferences_declaration" }
    val cursor = AndroidPreferencesXmlCursor(source)
    cursor.consumeXmlDeclarationIfPresent()
    cursor.skipDocumentWhitespace()
    val root = cursor.readStartTag()
    require(root.name == "map" && root.attributes.isEmpty()) { "migration_preferences_root" }
    if (root.selfClosing) {
        cursor.skipDocumentWhitespace()
        cursor.requireEndOfDocument()
        return null
    }
    var result: String? = null
    var found = false
    while (true) {
        cursor.skipDocumentWhitespace()
        if (cursor.nextIsEndTag()) {
            require(cursor.readEndTag() == "map") { "migration_preferences_root_end" }
            break
        }
        val child = cursor.readStartTag()
        if (child.attributes["name"] == key) {
            require(!found) { "migration_preferences_duplicate" }
            require(child.name == "string") { "migration_preferences_type" }
            require(child.attributes.keys == setOf("name")) { "migration_preferences_attributes" }
            result = if (child.selfClosing) {
                ""
            } else {
                val value = cursor.readText()
                require(cursor.readEndTag() == "string") { "migration_preferences_value" }
                value
            }
            found = true
        } else {
            cursor.skipElement(child)
        }
    }
    cursor.skipDocumentWhitespace()
    cursor.requireEndOfDocument()
    return result
}

private data class AndroidPreferencesStartTag(
    val name: String,
    val attributes: Map<String, String>,
    val selfClosing: Boolean,
)

/** Minimal XML grammar matching Android's deterministic SharedPreferences serializer. */
private class AndroidPreferencesXmlCursor(private val source: String) {
    private var index = 0

    fun consumeXmlDeclarationIfPresent() {
        if (!source.startsWith("<?xml", index)) return
        val end = source.indexOf("?>", startIndex = index + 5)
        require(end >= 0) { "migration_preferences_xml_declaration" }
        val declaration = source.substring(index + 5, end)
        require(declaration.isNotBlank() && '<' !in declaration && '>' !in declaration) {
            "migration_preferences_xml_declaration"
        }
        index = end + 2
    }

    fun skipDocumentWhitespace() {
        while (index < source.length && source[index].isXmlWhitespace()) index += 1
    }

    fun nextIsEndTag(): Boolean = source.startsWith("</", index)

    fun readStartTag(): AndroidPreferencesStartTag {
        require(index < source.length && source[index] == '<') { "migration_preferences_start" }
        require(!source.startsWith("</", index) && !source.startsWith("<?", index)) {
            "migration_preferences_start"
        }
        index += 1
        val name = readName()
        val attributes = linkedMapOf<String, String>()
        while (true) {
            skipTagWhitespace()
            when {
                source.startsWith("/>", index) -> {
                    index += 2
                    return AndroidPreferencesStartTag(name, attributes, true)
                }
                index < source.length && source[index] == '>' -> {
                    index += 1
                    return AndroidPreferencesStartTag(name, attributes, false)
                }
                else -> {
                    val attributeName = readName()
                    require(attributeName !in attributes) { "migration_preferences_attribute_duplicate" }
                    skipTagWhitespace()
                    require(index < source.length && source[index] == '=') {
                        "migration_preferences_attribute"
                    }
                    index += 1
                    skipTagWhitespace()
                    require(index < source.length && source[index] in charArrayOf('\'', '"')) {
                        "migration_preferences_attribute"
                    }
                    val quote = source[index++]
                    val start = index
                    while (index < source.length && source[index] != quote) {
                        require(source[index] != '<') { "migration_preferences_attribute" }
                        index += 1
                    }
                    require(index < source.length) { "migration_preferences_attribute" }
                    attributes[attributeName] = decodeXmlText(source.substring(start, index))
                    index += 1
                }
            }
        }
    }

    fun readEndTag(): String {
        require(source.startsWith("</", index)) { "migration_preferences_end" }
        index += 2
        val name = readName()
        skipTagWhitespace()
        require(index < source.length && source[index] == '>') { "migration_preferences_end" }
        index += 1
        return name
    }

    fun readText(): String {
        val start = index
        while (index < source.length && source[index] != '<') index += 1
        return decodeXmlText(source.substring(start, index))
    }

    fun skipElement(start: AndroidPreferencesStartTag) {
        if (start.selfClosing) return
        val stack = ArrayDeque<String>()
        stack.addLast(start.name)
        while (stack.isNotEmpty()) {
            readText()
            if (nextIsEndTag()) {
                val ended = readEndTag()
                require(ended == stack.removeLast()) { "migration_preferences_nesting" }
            } else {
                val nested = readStartTag()
                if (!nested.selfClosing) stack.addLast(nested.name)
            }
        }
    }

    fun requireEndOfDocument() {
        require(index == source.length) { "migration_preferences_trailing" }
    }

    private fun readName(): String {
        val start = index
        while (index < source.length && source[index].isXmlNameCharacter()) index += 1
        require(index > start) { "migration_preferences_name" }
        return source.substring(start, index)
    }

    private fun skipTagWhitespace() {
        while (index < source.length && source[index].isXmlWhitespace()) index += 1
    }
}

private fun decodeXmlText(raw: String): String {
    val output = StringBuilder(raw.length)
    var index = 0
    while (index < raw.length) {
        val character = raw[index]
        if (character != '&') {
            require(character.isXmlTextCharacter()) { "migration_preferences_character" }
            output.append(character)
            index += 1
            continue
        }
        val end = raw.indexOf(';', startIndex = index + 1)
        require(end in (index + 2)..(index + 16)) { "migration_preferences_entity" }
        val entity = raw.substring(index + 1, end)
        when (entity) {
            "amp" -> output.append('&')
            "lt" -> output.append('<')
            "gt" -> output.append('>')
            "quot" -> output.append('"')
            "apos" -> output.append('\'')
            else -> {
                val codePoint = when {
                    entity.startsWith("#x") -> entity.drop(2).toIntOrNull(16)
                    entity.startsWith('#') -> entity.drop(1).toIntOrNull(10)
                    else -> null
                } ?: error("migration_preferences_entity")
                require(codePoint.isXmlCodePoint()) { "migration_preferences_entity" }
                output.appendCodePoint(codePoint)
            }
        }
        index = end + 1
    }
    return output.toString()
}

private fun Char.isXmlWhitespace(): Boolean = this == ' ' || this == '\t' || this == '\r' || this == '\n'

private fun Char.isXmlNameCharacter(): Boolean =
    this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9' || this == '_' || this == ':' ||
        this == '-' || this == '.'

private fun Char.isXmlTextCharacter(): Boolean = code.isXmlCodePoint()

private fun Int.isXmlCodePoint(): Boolean =
    this == 0x9 || this == 0xA || this == 0xD || this in 0x20..0xD7FF ||
        this in 0xE000..0xFFFD || this in 0x10000..0x10FFFF

private const val PROBE_BYTES = 64
