package ai.hans.standard.notifications

import ai.hans.standard.profile.AtomicFileUserProfileStorage
import ai.hans.standard.runtime.CodexRuntimeContract
import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * A small data-only view of the confirmed Hans profile and generated Codex memories for
 * restricted notification triage.
 *
 * Classification is memory-aware without making the isolated App Server memory-writable: it never
 * sees the personal CODEX_HOME and cannot query or update memory. The Android host performs one
 * deterministic, no-follow read and places only these sanitized facts in the model request as
 * untrusted data.
 */
internal class NotificationReadOnlyPersonalContext private constructor(
    confirmedProfileFacts: List<String>,
    unverifiedMemoryHints: List<String>,
    private val withheldUnverifiedDetailTerms: Set<String>,
    private val hasWithheldPrivatePlaceOrTimeDetail: Boolean,
) {
    val confirmedProfileFacts: List<String> = confirmedProfileFacts.toList()
    val unverifiedMemoryHints: List<String> = unverifiedMemoryHints.toList()
    val facts: List<String> = (this.confirmedProfileFacts + this.unverifiedMemoryHints).toList()

    fun toClassificationJson(): JSONObject = baseJson()
        .put(
            "unverified_memory_hints",
            JSONObject()
                .put("authority", "generated_unverified_relevance_hint_only")
                .put("use", "classification_relevance_only_not_spoken_evidence")
                .put("facts", JSONArray().also { array ->
                    unverifiedMemoryHints.forEach(array::put)
                }),
        )

    fun toSynthesisJson(): JSONObject = baseJson()
        .put(
            "unverified_memory_signal",
            JSONObject()
                .put("present", unverifiedMemoryHints.isNotEmpty())
                .put("count", unverifiedMemoryHints.size)
                .put("details_included", false)
                .put("use", "classification_relevance_signal_only"),
        )

    private fun baseJson(): JSONObject = JSONObject()
        .put("schema_version", 1)
        .put("write_allowed", false)
        .put("precedence", "confirmed_profile_over_unverified_memory")
        .put(
            "confirmed_profile",
            JSONObject()
                .put("authority", "explicitly_user_confirmed")
                .put("facts", JSONArray().also { array ->
                    confirmedProfileFacts.forEach(array::put)
                }),
        )

    fun confirmedProfileEvidence(): String? = confirmedProfileFacts
        .joinToString(". ")
        .takeIf(String::isNotBlank)

    fun couldRevealWithheldMemoryDetail(
        spokenSummary: String,
        notification: UntrustedNotificationEnvelope,
        evidence: NotificationEnrichmentEvidence,
    ): Boolean {
        if (withheldUnverifiedDetailTerms.isEmpty() && !hasWithheldPrivatePlaceOrTimeDetail) {
            return false
        }
        val trustedText = buildString {
            append(notification.title).append('\n')
            append(notification.text).append('\n')
            append(notification.subtext).append('\n')
            confirmedProfileFacts.forEach { append(it).append('\n') }
            evidence.confirmedProfileSummary?.let { append(it).append('\n') }
            evidence.linkMetadata.forEach {
                append(it.title).append('\n').append(it.description).append('\n')
            }
            evidence.nearbyCalendar.forEach {
                append(it.title).append('\n').append(it.location).append('\n')
                append(it.beginEpochMillis).append('\n').append(it.endEpochMillis).append('\n')
            }
            evidence.recentNotifications.forEach {
                append(it.title).append('\n').append(it.text).append('\n')
            }
        }
        val trustedTerms = privateDetailTerms(trustedText)
        val unsupportedTerms = withheldUnverifiedDetailTerms - trustedTerms
        if (privateDetailTerms(spokenSummary).any(unsupportedTerms::contains)) return true
        if (
            unverifiedMemoryHints.isNotEmpty() &&
            PERSONAL_RELATIONSHIP_CUE.containsMatchIn(spokenSummary) &&
            !PERSONAL_RELATIONSHIP_CUE.containsMatchIn(trustedText)
        ) return true
        return hasWithheldPrivatePlaceOrTimeDetail &&
            PRIVATE_PLACE_OR_TIME_CUE.containsMatchIn(spokenSummary) &&
            !PRIVATE_PLACE_OR_TIME_CUE.containsMatchIn(trustedText)
    }

    companion object {
        val EMPTY = NotificationReadOnlyPersonalContext(
            confirmedProfileFacts = emptyList(),
            unverifiedMemoryHints = emptyList(),
            withheldUnverifiedDetailTerms = emptySet(),
            hasWithheldPrivatePlaceOrTimeDetail = false,
        )

        fun fromCandidateFacts(candidates: Iterable<String>): NotificationReadOnlyPersonalContext {
            return fromSources(
                confirmedProfileCandidates = emptyList(),
                unverifiedMemoryCandidates = candidates,
            )
        }

        fun fromSources(
            confirmedProfileCandidates: Iterable<String>,
            unverifiedMemoryCandidates: Iterable<String>,
        ): NotificationReadOnlyPersonalContext {
            val confirmed = mutableListOf<String>()
            val memory = mutableListOf<String>()
            val seen = linkedSetOf<String>()
            fun canAccept(
                proposedConfirmed: List<String>,
                proposedMemory: List<String>,
                withheldTerms: Set<String>,
                withholdsPlaceOrTime: Boolean,
            ): Boolean = NotificationReadOnlyPersonalContext(
                confirmedProfileFacts = proposedConfirmed,
                unverifiedMemoryHints = proposedMemory,
                withheldUnverifiedDetailTerms = withheldTerms,
                hasWithheldPrivatePlaceOrTimeDetail = withholdsPlaceOrTime,
            ).toClassificationJson().toString().utf8Size() <= MAX_SERIALIZED_BYTES

            confirmedProfileCandidates.forEach { candidate ->
                if (confirmed.size + memory.size >= CodexNotificationMemoryProjection.MAX_FACTS) {
                    return@forEach
                }
                val safe = NotificationPersonalMemoryFactPolicy.sanitize(candidate) ?: return@forEach
                val identity = safe.lowercase(Locale.ROOT)
                if (!seen.add(identity)) return@forEach
                if (canAccept(confirmed + safe, memory, emptySet(), false)) {
                    confirmed += safe
                }
            }

            val confirmedTerms = confirmed.asSequence()
                .flatMap { highInformationTerms(listOf(it)).asSequence() }
                .toSet()
            val withheldTerms = linkedSetOf<String>()
            var withholdsPlaceOrTime = false
            unverifiedMemoryCandidates.forEach { candidate ->
                if (confirmed.size + memory.size >= CodexNotificationMemoryProjection.MAX_FACTS) {
                    return@forEach
                }
                val safe = NotificationPersonalMemoryFactPolicy.sanitize(candidate) ?: return@forEach
                val sourceTerms = highInformationTerms(listOf(safe))
                if (confirmedTerms.isNotEmpty() && sourceTerms.any(confirmedTerms::contains)) {
                    return@forEach
                }
                val projected = NotificationPersonalMemoryFactPolicy.asUnverifiedHint(safe)
                val identity = projected.text.lowercase(Locale.ROOT)
                if (!seen.add(identity)) return@forEach
                val proposedWithheldTerms = (withheldTerms + projected.withheldTerms).toSet()
                val proposedWithholdsPlaceOrTime = withholdsPlaceOrTime || projected.withholdsPlaceOrTime
                if (
                    canAccept(
                        confirmed,
                        memory + projected.text,
                        proposedWithheldTerms,
                        proposedWithholdsPlaceOrTime,
                    )
                ) {
                    memory += projected.text
                    withheldTerms += projected.withheldTerms
                    withholdsPlaceOrTime = proposedWithholdsPlaceOrTime
                }
            }

            return if (confirmed.isEmpty() && memory.isEmpty()) {
                EMPTY
            } else {
                NotificationReadOnlyPersonalContext(
                    confirmedProfileFacts = confirmed.toList(),
                    unverifiedMemoryHints = memory.toList(),
                    withheldUnverifiedDetailTerms = withheldTerms.toSet(),
                    hasWithheldPrivatePlaceOrTimeDetail = withholdsPlaceOrTime,
                )
            }
        }

        internal const val MAX_SERIALIZED_BYTES = 1 * 1_024
    }
}

internal fun interface NotificationPersonalMemoryContextProvider {
    fun project(notification: UntrustedNotificationEnvelope): NotificationReadOnlyPersonalContext

    companion object {
        val NONE = NotificationPersonalMemoryContextProvider {
            NotificationReadOnlyPersonalContext.EMPTY
        }
    }
}

/** Builds the production source from Hans's private, persistent Codex home. */
internal object AndroidNotificationPersonalMemoryContextFactory {
    fun create(
        context: Context,
        additionalUnverifiedCandidateSource: (UntrustedNotificationEnvelope) -> List<String> = { emptyList() },
    ): NotificationPersonalMemoryContextProvider {
        val appContext = context.applicationContext
        val directories = CodexRuntimeContract.directories(
            noBackupFilesDirectory = appContext.noBackupFilesDir,
            cacheDirectory = appContext.cacheDir,
        )
        return CodexNotificationMemoryProjection(
            codexHomeDirectory = directories.codexHomeDirectory,
            confirmedProfileSource = {
                AtomicFileUserProfileStorage(appContext).read().confirmedSummary
            },
            additionalUnverifiedCandidateSource = additionalUnverifiedCandidateSource,
        )
    }
}

/**
 * Deterministic, read-only projection of `memory_summary.md` and `MEMORY.md`.
 *
 * Any present source that cannot be proven to be a stable regular file causes an empty projection.
 * A missing individual source is allowed because Codex creates the two files progressively.
 */
internal class CodexNotificationMemoryProjection(
    codexHomeDirectory: File,
    private val confirmedProfileSource: () -> String? = { null },
    private val additionalUnverifiedCandidateSource: (UntrustedNotificationEnvelope) -> List<String> = { emptyList() },
    private val afterSourceReadBeforeSnapshotValidation: (Path) -> Unit = {},
) : NotificationPersonalMemoryContextProvider {
    private val expectedCodexHome = codexHomeDirectory.toPath()
        .toAbsolutePath()
        .normalize()
    private val expectedMemoryRoot = expectedCodexHome.resolve(MEMORIES_DIRECTORY_NAME)

    override fun project(
        notification: UntrustedNotificationEnvelope,
    ): NotificationReadOnlyPersonalContext = runCatching {
        projectOrThrow(notification)
    }.getOrDefault(NotificationReadOnlyPersonalContext.EMPTY)

    private fun projectOrThrow(
        notification: UntrustedNotificationEnvelope,
    ): NotificationReadOnlyPersonalContext {
        val confirmedProfileFacts = confirmedProfileSource()?.let { summary ->
            require(summary.toByteArray(StandardCharsets.UTF_8).size <= MAX_CONFIRMED_PROFILE_SOURCE_BYTES) {
                "confirmed_profile_projection_limit"
            }
            selectFacts(
                sources = listOf(
                    FactSource(
                        sourceOrder = -1,
                        segments = { FACT_SEGMENT.splitToSequence(summary) },
                    ),
                ),
                notification = notification,
                maxRelevantFacts = MAX_RELEVANT_FACTS,
                maxGlobalFacts = MAX_GLOBAL_FACTS,
            )
        }.orEmpty()
        val memoryDocuments = readStableMemoryDocuments()
        val memoryFacts = selectFacts(
            sources = memoryDocuments.map { document ->
                FactSource(
                    sourceOrder = document.sourceOrder,
                    segments = { FACT_SEGMENT.splitToSequence(document.content) },
                )
            },
            notification = notification,
            maxRelevantFacts = MAX_RELEVANT_FACTS,
            maxGlobalFacts = MAX_GLOBAL_FACTS,
        )
        val additional = runCatching { additionalUnverifiedCandidateSource(notification).take(2) }
            .getOrDefault(emptyList())
        // Combine raw candidates ONCE: reprojecting existing hints would discard their withheld
        // private-detail provenance and could incorrectly authorize later spoken details.
        val combined = memoryFacts.take(2) + additional + memoryFacts.drop(2)
        return NotificationReadOnlyPersonalContext.fromSources(
            confirmedProfileCandidates = confirmedProfileFacts,
            unverifiedMemoryCandidates = combined,
        )
    }

    private fun readStableMemoryDocuments(): List<MemoryDocument> {
        if (Files.isSymbolicLink(expectedCodexHome)) error("invalid_codex_home")
        if (!Files.exists(expectedMemoryRoot, LinkOption.NOFOLLOW_LINKS)) {
            return emptyList()
        }
        if (
            Files.isSymbolicLink(expectedMemoryRoot) ||
            !Files.isDirectory(expectedMemoryRoot, LinkOption.NOFOLLOW_LINKS)
        ) {
            error("invalid_memory_root")
        }
        val rootBefore = Files.readAttributes(
            expectedMemoryRoot,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        val canonicalRoot = expectedMemoryRoot.toRealPath(LinkOption.NOFOLLOW_LINKS)
        val snapshots = SOURCE_FILES.mapIndexed { sourceOrder, source ->
            val candidate = expectedMemoryRoot.resolve(source.fileName).normalize()
            if (candidate.parent != expectedMemoryRoot) error("invalid_memory_source_path")
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return@mapIndexed MemorySourceSnapshot.Missing(candidate)
            }
            val canonicalCandidate = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS)
            if (
                Files.isSymbolicLink(candidate) ||
                canonicalCandidate.parent != canonicalRoot ||
                !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
            ) {
                error("invalid_memory_source")
            }
            val attributes = Files.readAttributes(
                candidate,
                BasicFileAttributes::class.java,
                LinkOption.NOFOLLOW_LINKS,
            )
            if (!attributes.isRegularFile || attributes.size() !in 1..source.maximumBytes.toLong()) {
                error("invalid_memory_source_size")
            }
            MemorySourceSnapshot.Present(
                sourceOrder = sourceOrder,
                source = source,
                path = candidate,
                canonicalPath = canonicalCandidate,
                attributes = attributes,
            )
        }
        val documents = snapshots.mapNotNull { snapshot ->
            val present = snapshot as? MemorySourceSnapshot.Present ?: return@mapNotNull null
            val content = strictUtf8Read(present.path, present.attributes)
                ?: error("invalid_memory_source_content")
            afterSourceReadBeforeSnapshotValidation(present.path)
            MemoryDocument(present.sourceOrder, content)
        }
        val rootAfter = Files.readAttributes(
            expectedMemoryRoot,
            BasicFileAttributes::class.java,
            LinkOption.NOFOLLOW_LINKS,
        )
        if (
            Files.isSymbolicLink(expectedMemoryRoot) ||
            expectedMemoryRoot.toRealPath(LinkOption.NOFOLLOW_LINKS) != canonicalRoot ||
            !sameStableFile(rootBefore, rootAfter, requireRegularFile = false)
        ) {
            error("unstable_memory_root")
        }
        snapshots.forEach { snapshot ->
            when (snapshot) {
                is MemorySourceSnapshot.Missing -> if (
                    Files.exists(snapshot.path, LinkOption.NOFOLLOW_LINKS)
                ) {
                    error("memory_source_appeared_during_snapshot")
                }
                is MemorySourceSnapshot.Present -> {
                    if (
                        !Files.exists(snapshot.path, LinkOption.NOFOLLOW_LINKS) ||
                        Files.isSymbolicLink(snapshot.path) ||
                        !Files.isRegularFile(snapshot.path, LinkOption.NOFOLLOW_LINKS) ||
                        snapshot.path.toRealPath(LinkOption.NOFOLLOW_LINKS) != snapshot.canonicalPath
                    ) {
                        error("unstable_memory_source")
                    }
                    val after = Files.readAttributes(
                        snapshot.path,
                        BasicFileAttributes::class.java,
                        LinkOption.NOFOLLOW_LINKS,
                    )
                    if (!sameStableFile(snapshot.attributes, after, requireRegularFile = true)) {
                        error("unstable_memory_source")
                    }
                }
            }
        }
        return documents
    }

    private fun strictUtf8Read(path: Path, expected: BasicFileAttributes): String? {
        val bytes = ByteArray(expected.size().toInt())
        val readComplete = runCatching {
            FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) {
                    if (channel.read(buffer) < 0) return@use false
                }
                channel.read(ByteBuffer.allocate(1)) < 0
            }
        }.getOrDefault(false)
        if (!readComplete) return null
        return runCatching {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull()
    }

    private fun selectFacts(
        sources: List<FactSource>,
        notification: UntrustedNotificationEnvelope,
        maxRelevantFacts: Int,
        maxGlobalFacts: Int,
    ): List<String> {
        val notificationTerms = highInformationTerms(
            listOf(notification.title, notification.text, notification.subtext),
        )
        val relevant = mutableListOf<MemoryFactCandidate>()
        val relevantIdentities = linkedSetOf<String>()
        var global: MemoryFactCandidate? = null
        sources.sortedBy(FactSource::sourceOrder).forEach { source ->
            var scannedLines = 0
            var eligibleCandidates = 0
            val iterator = source.segments().iterator()
            while (
                iterator.hasNext() &&
                scannedLines < MAX_SCANNED_SEGMENTS_PER_SOURCE &&
                eligibleCandidates < MAX_ELIGIBLE_CANDIDATES_PER_SOURCE
            ) {
                val lineIndex = scannedLines++
                val fact = NotificationPersonalMemoryFactPolicy.sanitize(iterator.next()) ?: continue
                val matchingTerms = highInformationTerms(listOf(fact)).intersect(notificationTerms)
                val globalPreferenceClause =
                    NotificationPersonalMemoryFactPolicy.globalNotificationPreferenceClause(fact)
                val isGlobal = globalPreferenceClause != null
                if (matchingTerms.isEmpty() && !isGlobal) continue
                eligibleCandidates += 1
                val candidate = MemoryFactCandidate(
                    fact = fact,
                    sourceOrder = source.sourceOrder,
                    lineIndex = lineIndex,
                    matchingTerms = matchingTerms,
                    globalPreference = isGlobal,
                )
                if (matchingTerms.isNotEmpty()) {
                    val identity = fact.lowercase(Locale.ROOT)
                    if (relevantIdentities.add(identity)) {
                        relevant += candidate
                        relevant.sortWith(BEST_FACT_FIRST)
                        if (relevant.size > maxRelevantFacts) {
                            val removed = relevant.removeAt(relevant.lastIndex)
                            relevantIdentities.remove(removed.fact.lowercase(Locale.ROOT))
                        }
                    }
                }
                if (globalPreferenceClause != null) {
                    val globalCandidate = candidate.copy(
                        fact = globalPreferenceClause,
                        matchingTerms = highInformationTerms(listOf(globalPreferenceClause))
                            .intersect(notificationTerms),
                    )
                    if (global == null || BEST_FACT_FIRST.compare(globalCandidate, global) < 0) {
                        global = globalCandidate
                    }
                }
            }
        }
        val selected = relevant.sortedWith(BEST_FACT_FIRST).map(MemoryFactCandidate::fact)
        val globalFacts = global?.fact
            ?.takeIf { candidate -> selected.none { it.equals(candidate, ignoreCase = true) } }
            ?.let(::listOf)
            .orEmpty()
            .take(maxGlobalFacts)
        return (selected + globalFacts).take(maxRelevantFacts + maxGlobalFacts)
    }

    private fun sameStableFile(
        before: BasicFileAttributes,
        after: BasicFileAttributes,
        requireRegularFile: Boolean,
    ): Boolean =
        (!requireRegularFile || after.isRegularFile) &&
            (requireRegularFile || after.isDirectory) &&
            before.size() == after.size() &&
            before.lastModifiedTime() == after.lastModifiedTime() &&
            before.creationTime() == after.creationTime() &&
            (before.fileKey() == null || after.fileKey() == null || before.fileKey() == after.fileKey())

    private data class MemoryDocument(val sourceOrder: Int, val content: String)

    private data class FactSource(
        val sourceOrder: Int,
        val segments: () -> Sequence<String>,
    )

    private sealed interface MemorySourceSnapshot {
        data class Missing(val path: Path) : MemorySourceSnapshot
        data class Present(
            val sourceOrder: Int,
            val source: MemorySource,
            val path: Path,
            val canonicalPath: Path,
            val attributes: BasicFileAttributes,
        ) : MemorySourceSnapshot
    }

    private data class MemoryFactCandidate(
        val fact: String,
        val sourceOrder: Int,
        val lineIndex: Int,
        val matchingTerms: Set<String>,
        val globalPreference: Boolean,
    )

    internal companion object {
        const val MEMORIES_DIRECTORY_NAME = "memories"
        const val MAX_SUMMARY_SOURCE_BYTES = 64 * 1_024
        const val MAX_MEMORY_SOURCE_BYTES = 512 * 1_024
        const val MAX_CONFIRMED_PROFILE_SOURCE_BYTES = 64 * 1_024
        const val MAX_SCANNED_SEGMENTS_PER_SOURCE = 4_096
        const val MAX_ELIGIBLE_CANDIDATES_PER_SOURCE = 512
        const val MAX_FACTS = 4
        private const val MAX_RELEVANT_FACTS = 3
        private const val MAX_GLOBAL_FACTS = 1
        private val SOURCE_FILES = listOf(
            MemorySource("memory_summary.md", MAX_SUMMARY_SOURCE_BYTES),
            MemorySource("MEMORY.md", MAX_MEMORY_SOURCE_BYTES),
        )
        private data class MemorySource(val fileName: String, val maximumBytes: Int)
        private val FACT_SEGMENT = Regex("[\\r\\n]+|(?<=[.!?])\\s+|\\s*;\\s*")
        private val BEST_FACT_FIRST =
            compareByDescending<MemoryFactCandidate> { it.matchingTerms.size }
                .thenBy { it.sourceOrder }
                .thenBy { it.lineIndex }
    }
}

private object NotificationPersonalMemoryFactPolicy {
    data class ProjectedUnverifiedHint(
        val text: String,
        val withheldTerms: Set<String> = emptySet(),
        val withholdsPlaceOrTime: Boolean = false,
    )

    fun sanitize(raw: String): String? {
        if (raw.toByteArray(StandardCharsets.UTF_8).size > MAX_RAW_LINE_BYTES) return null
        val withoutMarkdown = raw.trim()
            .replace(MARKDOWN_PREFIX, "")
            .trim()
        if (
            withoutMarkdown.isBlank() ||
            withoutMarkdown.equals("v1", ignoreCase = true) ||
            withoutMarkdown.startsWith("```") ||
            FORBIDDEN_LOCATOR.containsMatchIn(withoutMarkdown) ||
            FORBIDDEN_TRACKING_ID.containsMatchIn(withoutMarkdown) ||
            FORBIDDEN_SECRET_SHAPE.containsMatchIn(withoutMarkdown) ||
            PROMPT_OR_TOOL_INJECTION.containsMatchIn(withoutMarkdown)
        ) return null
        val redacted = sanitizeRestrictedNotificationText(
            title = "",
            text = withoutMarkdown,
            subtext = "",
        ) ?: return null
        if (redacted.redactionApplied) return null
        return NotificationTriageBounds.boundedText(redacted.text, MAX_FACT_BYTES)
            .takeIf { it.isNotBlank() }
    }

    fun globalNotificationPreferenceClause(fact: String): String? =
        GLOBAL_PREFERENCE_CLAUSE.splitToSequence(fact)
            .map(String::trim)
            .firstOrNull { clause ->
                EXPLICIT_NOTIFICATION_MEDIUM.containsMatchIn(clause) &&
                    USER_PREFERENCE_SUBJECT.containsMatchIn(clause) &&
                    USER_PREFERENCE_CONSTRUCTION.containsMatchIn(clause)
            }

    fun asUnverifiedHint(fact: String): ProjectedUnverifiedHint {
        if (!PRIVATE_PLACE_OR_TIME_CUE.containsMatchIn(fact)) {
            return ProjectedUnverifiedHint(
                text = fact,
                withheldTerms = privateDetailTerms(fact),
            )
        }
        return ProjectedUnverifiedHint(
            text = "A generated memory indicates additional private relevance; concrete details are withheld.",
            withheldTerms = privateDetailTerms(fact),
            withholdsPlaceOrTime = true,
        )
    }

    private const val MAX_RAW_LINE_BYTES = 2 * 1_024
    private const val MAX_FACT_BYTES = 256
    private val MARKDOWN_PREFIX = Regex("^\\s{0,3}(?:#{1,6}|[-*+]|\\d+[.)])\\s+")
    private val FORBIDDEN_LOCATOR = Regex(
        "(?iu)(?:\\b[a-z][a-z0-9+.-]{1,20}:(?:/{0,2})\\S+|www\\.|" +
            "\\b[\\p{L}0-9](?:[\\p{L}0-9-]{0,62}\\.)+[\\p{L}]{2,}(?:/\\S*)?|" +
            "\\b[\\p{L}0-9._%+-]+@[\\p{L}0-9.-]+\\.[\\p{L}]{2,}\\b|" +
            "(?:^|\\s)(?:/data/|/storage/|/sdcard/|/Users/|~?/\\.))",
    )
    private val FORBIDDEN_TRACKING_ID = Regex(
        "(?iu)(?:\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b|" +
            "\\b[0-9a-f]{24,}\\b|" +
            "\\b(?=[A-Za-z0-9_-]{28,}={0,2}\\b)(?=[A-Za-z0-9_-]*[0-9_-])" +
            "[A-Za-z0-9_-]{28,}={0,2}\\b)",
    )
    private val FORBIDDEN_SECRET_SHAPE = Regex(
        "(?iu)(?:\\bsk-(?:proj-)?[A-Za-z0-9_-]+|\\b(?:api[_ -]?key|access[_ -]?token|" +
            "refresh[_ -]?token|password|passwort|secret)\\b|\\[REDACTED_[A-Z_]+])",
    )
    private val PROMPT_OR_TOOL_INJECTION = Regex(
        "(?iu)(?:ignore|disregard|vergiss|ignoriere).{0,48}(?:instruction|prompt|regel|system)|" +
            "(?:system|developer|assistant)[ _-]?(?:message|prompt|instruction)|" +
            "(?:call|invoke|run|execute|starte|fuehre|führe).{0,32}(?:tool|shell|command|befehl)|" +
            "(?:write|append|store|save|schreib|speicher).{0,32}(?:memory|MEMORY\\.md|memory_summary)",
    )
    private val EXPLICIT_NOTIFICATION_MEDIUM = Regex(
        "(?iu)\\b(?:notifications?|benachrichtigungen?|" +
            "push(?:[ -]+(?:notifications?|benachrichtigungen?)))\\b",
    )
    private val USER_PREFERENCE_SUBJECT = Regex(
        "(?iu)\\b(?:i|ich|me|mir|mich|my|mein(?:e|er|em|en|es)?|" +
            "user|user's|nutzer|nutzerin|nutzers|jeremias)\\b",
    )
    private val USER_PREFERENCE_CONSTRUCTION = Regex(
        "(?iu)\\b(?:prefer(?:s|red|ring)?|preference|bevorzugt|wants?|möchte|moechte|" +
            "wünscht|wuenscht|soll(?:en)?|should)\\b|" +
            "\\b(?:always|never|immer|niemals)\\b.{0,80}\\b(?:announce|inform|notify|" +
            "read aloud|surface|interrupt|vorlesen|informieren|melden|unterbrechen)\\b|" +
            "\\b(?:announce|inform|notify|read aloud|surface|interrupt|vorlesen|informieren|" +
            "melden|unterbrechen)\\b.{0,80}\\b(?:always|never|immer|niemals)\\b",
    )
    private val GLOBAL_PREFERENCE_CLAUSE = Regex("(?<=[.!?])\\s+|\\s*;\\s*")
}

/** Shared deterministic selector; never performs IO or treats text as an instruction. */
internal fun notificationFactRelevanceTerms(values: Iterable<String>): Set<String> =
    highInformationTerms(values)

private fun highInformationTerms(values: Iterable<String>): Set<String> = values.asSequence()
    .flatMap { value -> MEMORY_TERM.findAll(value).map { match -> match.value } }
    .map { it.lowercase(Locale.ROOT) }
    .filter { it.length >= MIN_TERM_LENGTH }
    .filterNot(LOW_INFORMATION_TERMS::contains)
    .take(MAX_TERMS)
    .toCollection(linkedSetOf())

private const val MIN_TERM_LENGTH = 3
private const val MAX_TERMS = 24
private val MEMORY_TERM = Regex("[\\p{L}\\p{N}]{3,}")
private val LOW_INFORMATION_TERMS = setOf(
    "aber", "alle", "also", "and", "are", "auf", "aus", "bei", "das", "dass", "der",
    "die", "ein", "eine", "einer", "eines", "for", "from", "hat", "ist", "mit", "new",
    "not", "oder", "the", "this", "und", "von", "was", "with", "your", "you", "zur",
    "message", "nachricht", "notification", "notifications", "benachrichtigung", "update",
    "event", "termin", "project", "projekt", "important", "wichtig", "urgent", "dringend",
    "user", "users", "nutzer", "nutzers", "nutzerin", "nutzerschaft",
)

private val PRIVATE_DETAIL_TERM = Regex(
    "[\\p{L}\\p{N}]{3,}|(?<![\\p{L}\\p{N}])\\d{1,2}(?::\\d{2})?(?![\\p{L}\\p{N}])",
)
private val PRIVATE_PLACE_OR_TIME_CUE = Regex(
    "(?iu)\\b(?:address|adresse|birthday|geburtstag|home|location|standort|timezone|zeitzone|" +
        "lives|living|lebt|wohnt|near|nahe|today|heute|tomorrow|morgen|yesterday|gestern|" +
        "uhr|a\\.?m\\.?|p\\.?m\\.?)\\b|\\b\\d{1,2}:\\d{2}\\b",
)
private val PERSONAL_RELATIONSHIP_CUE = Regex(
    "(?iu)\\b(?:brother|sister|mother|father|parent|child|son|daughter|family|relative|" +
        "friend|partner|spouse|colleague|coworker|bruder|schwester|mutter|vater|elternteil|" +
        "kind|sohn|tochter|familie|familienmitglied|angehörige|angehoerige|verwandte|freund|" +
        "freundin|partnerin|ehepartner|kollege|kollegin|nahestehend|close contact|close person)\\b",
)

private fun privateDetailTerms(value: String): Set<String> = PRIVATE_DETAIL_TERM.findAll(value)
    .map { it.value.lowercase(Locale.ROOT) }
    .filterNot(LOW_INFORMATION_TERMS::contains)
    .take(MAX_PRIVATE_DETAIL_TERMS)
    .toSet()

private const val MAX_PRIVATE_DETAIL_TERMS = 128

private fun String.utf8Size(): Int = toByteArray(StandardCharsets.UTF_8).size
