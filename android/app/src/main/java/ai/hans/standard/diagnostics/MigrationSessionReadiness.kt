package ai.hans.standard.diagnostics

import android.util.AtomicFile
import android.os.Process
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import org.json.JSONObject

internal enum class MigrationAccountPhase(val wireValue: String) {
    SIGNED_IN("signed_in"),
    SIGNED_OUT("signed_out"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWireValue(value: String): MigrationAccountPhase? =
            entries.firstOrNull { it.wireValue == value }
    }
}

internal data class MigrationSessionReadinessProjection(
    val accountPhase: MigrationAccountPhase,
    val accountReadComplete: Boolean,
    val runtimeReady: Boolean,
    val threadCorrelationSha256: String?,
    val threadResumeConfirmed: Boolean,
    val memoryModeEnabledAck: Boolean,
    val effectiveModel: String?,
    val effectiveReasoningEffort: String?,
    val speechCredentialAvailable: Boolean,
    val setupComplete: Boolean,
    val activeTurn: Boolean,
    val dictationActive: Boolean,
    val liveVoiceActive: Boolean,
    val activeAutomationCount: Int,
) {
    init {
        require(threadCorrelationSha256 == null || threadCorrelationSha256.matches(SHA256))
        require((effectiveModel == null) == (effectiveReasoningEffort == null))
        effectiveModel?.let { require(it.matches(WIRE_TOKEN)) }
        effectiveReasoningEffort?.let { require(it.matches(WIRE_TOKEN)) }
        require(activeAutomationCount in 0..MAX_ACTIVE_AUTOMATIONS)
        if (accountPhase == MigrationAccountPhase.UNKNOWN) require(!accountReadComplete)
        if (threadCorrelationSha256 == null) {
            require(!threadResumeConfirmed)
            require(!memoryModeEnabledAck)
        }
    }

    companion object {
        const val MAX_ACTIVE_AUTOMATIONS = 200_000
        private val SHA256 = Regex("[0-9a-f]{64}")
        private val WIRE_TOKEN = Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")
    }
}

internal data class MigrationSessionReadinessSnapshot(
    val packageName: String,
    val longVersionCode: Long,
    val packageLastUpdateTimeMillis: Long,
    val observationTimestampMillis: Long,
    /** Internal process-incarnation binding; never included in the public receipt. */
    val interactiveProcessId: Int,
    val accountPhase: MigrationAccountPhase,
    val accountReadComplete: Boolean,
    val runtimeReady: Boolean,
    val threadCorrelationSha256: String?,
    val threadResumeConfirmed: Boolean,
    val memoryModeEnabledAck: Boolean,
    val effectiveModel: String?,
    val effectiveReasoningEffort: String?,
    val speechCredentialAvailable: Boolean,
    val setupComplete: Boolean,
    val activeTurn: Boolean,
    val dictationActive: Boolean,
    val liveVoiceActive: Boolean,
    val activeAutomationCount: Int,
) {
    init {
        require(packageName == MigrationPackageSnapshot.PACKAGE_NAME)
        require(longVersionCode > 0L)
        require(packageLastUpdateTimeMillis > 0L)
        require(observationTimestampMillis >= packageLastUpdateTimeMillis)
        require(interactiveProcessId > 0)
        MigrationSessionReadinessProjection(
            accountPhase = accountPhase,
            accountReadComplete = accountReadComplete,
            runtimeReady = runtimeReady,
            threadCorrelationSha256 = threadCorrelationSha256,
            threadResumeConfirmed = threadResumeConfirmed,
            memoryModeEnabledAck = memoryModeEnabledAck,
            effectiveModel = effectiveModel,
            effectiveReasoningEffort = effectiveReasoningEffort,
            speechCredentialAvailable = speechCredentialAvailable,
            setupComplete = setupComplete,
            activeTurn = activeTurn,
            dictationActive = dictationActive,
            liveVoiceActive = liveVoiceActive,
            activeAutomationCount = activeAutomationCount,
        )
    }

    fun requireCurrentPackage(
        current: MigrationPackageSnapshot,
        currentInteractiveProcessId: Int,
        observedAtMillis: Long,
    ) {
        requireMigrationCondition(
            packageName == current.packageName &&
                longVersionCode == current.longVersionCode &&
                packageLastUpdateTimeMillis == current.lastUpdateTimeMillis &&
                observationTimestampMillis >= current.lastUpdateTimeMillis &&
                interactiveProcessId == currentInteractiveProcessId &&
                observationTimestampMillis <= observedAtMillis + MAX_FUTURE_SKEW_MILLIS,
            MigrationDiagnosticsBlocker.READINESS_OBSOLETE,
        )
        requireMigrationCondition(
            observedAtMillis - observationTimestampMillis <= MAX_AGE_MILLIS,
            MigrationDiagnosticsBlocker.READINESS_STALE,
        )
    }

    fun projection(): MigrationSessionReadinessProjection = MigrationSessionReadinessProjection(
        accountPhase = accountPhase,
        accountReadComplete = accountReadComplete,
        runtimeReady = runtimeReady,
        threadCorrelationSha256 = threadCorrelationSha256,
        threadResumeConfirmed = threadResumeConfirmed,
        memoryModeEnabledAck = memoryModeEnabledAck,
        effectiveModel = effectiveModel,
        effectiveReasoningEffort = effectiveReasoningEffort,
        speechCredentialAvailable = speechCredentialAvailable,
        setupComplete = setupComplete,
        activeTurn = activeTurn,
        dictationActive = dictationActive,
        liveVoiceActive = liveVoiceActive,
        activeAutomationCount = activeAutomationCount,
    )

    companion object {
        const val MAX_AGE_MILLIS = 10L * 60L * 1_000L
        const val MAX_FUTURE_SKEW_MILLIS = 60L * 1_000L
    }
}

internal object MigrationSessionReadinessCodec {
    const val SCHEMA = "hans.migration-session-readiness"
    const val VERSION = 1
    const val MAX_BYTES = 8 * 1_024

    private val FIELDS = setOf(
        "accountPhase",
        "accountReadComplete",
        "activeAutomationCount",
        "activeTurn",
        "dictationActive",
        "effectiveModel",
        "effectiveReasoningEffort",
        "liveVoiceActive",
        "interactiveProcessId",
        "longVersionCode",
        "memoryModeEnabledAck",
        "observationTimestampMillis",
        "packageLastUpdateTimeMillis",
        "packageName",
        "runtimeReady",
        "schema",
        "threadCorrelationSha256",
        "threadResumeConfirmed",
        "setupComplete",
        "speechCredentialAvailable",
        "version",
    )

    fun encode(snapshot: MigrationSessionReadinessSnapshot): String =
        MigrationCanonicalJson.encode(
            linkedMapOf(
                "accountPhase" to snapshot.accountPhase.wireValue,
                "accountReadComplete" to snapshot.accountReadComplete,
                "activeAutomationCount" to snapshot.activeAutomationCount,
                "activeTurn" to snapshot.activeTurn,
                "dictationActive" to snapshot.dictationActive,
                "effectiveModel" to snapshot.effectiveModel,
                "effectiveReasoningEffort" to snapshot.effectiveReasoningEffort,
                "liveVoiceActive" to snapshot.liveVoiceActive,
                "interactiveProcessId" to snapshot.interactiveProcessId,
                "longVersionCode" to snapshot.longVersionCode,
                "memoryModeEnabledAck" to snapshot.memoryModeEnabledAck,
                "observationTimestampMillis" to snapshot.observationTimestampMillis,
                "packageLastUpdateTimeMillis" to snapshot.packageLastUpdateTimeMillis,
                "packageName" to snapshot.packageName,
                "runtimeReady" to snapshot.runtimeReady,
                "schema" to SCHEMA,
                "threadCorrelationSha256" to snapshot.threadCorrelationSha256,
                "threadResumeConfirmed" to snapshot.threadResumeConfirmed,
                "setupComplete" to snapshot.setupComplete,
                "speechCredentialAvailable" to snapshot.speechCredentialAvailable,
                "version" to VERSION,
            ),
        ).also { encoded ->
            require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES)
        }

    fun decode(raw: String): MigrationSessionReadinessSnapshot {
        val bytes = raw.toByteArray(StandardCharsets.UTF_8)
        require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "migration_readiness_size" }
        val root = JSONObject(raw)
        require(root.keys().asSequence().toSet() == FIELDS) { "migration_readiness_fields" }
        require(root.requireString("schema") == SCHEMA) { "migration_readiness_schema" }
        require(root.requireInt("version") == VERSION) { "migration_readiness_version" }
        val threadHash = if (root.isNull("threadCorrelationSha256")) {
            null
        } else {
            root.requireString("threadCorrelationSha256")
        }
        val effectiveModel = if (root.isNull("effectiveModel")) null else {
            root.requireString("effectiveModel")
        }
        val effectiveReasoningEffort = if (root.isNull("effectiveReasoningEffort")) null else {
            root.requireString("effectiveReasoningEffort")
        }
        return MigrationSessionReadinessSnapshot(
            packageName = root.requireString("packageName"),
            longVersionCode = root.requireLong("longVersionCode"),
            packageLastUpdateTimeMillis = root.requireLong("packageLastUpdateTimeMillis"),
            observationTimestampMillis = root.requireLong("observationTimestampMillis"),
            interactiveProcessId = root.requireInt("interactiveProcessId"),
            accountPhase = MigrationAccountPhase.fromWireValue(root.requireString("accountPhase"))
                ?: error("migration_readiness_account"),
            accountReadComplete = root.requireBoolean("accountReadComplete"),
            runtimeReady = root.requireBoolean("runtimeReady"),
            threadCorrelationSha256 = threadHash,
            threadResumeConfirmed = root.requireBoolean("threadResumeConfirmed"),
            memoryModeEnabledAck = root.requireBoolean("memoryModeEnabledAck"),
            effectiveModel = effectiveModel,
            effectiveReasoningEffort = effectiveReasoningEffort,
            speechCredentialAvailable = root.requireBoolean("speechCredentialAvailable"),
            setupComplete = root.requireBoolean("setupComplete"),
            activeTurn = root.requireBoolean("activeTurn"),
            dictationActive = root.requireBoolean("dictationActive"),
            liveVoiceActive = root.requireBoolean("liveVoiceActive"),
            activeAutomationCount = root.requireInt("activeAutomationCount"),
        )
    }

    private fun JSONObject.requireBoolean(name: String): Boolean =
        get(name).also { require(it is Boolean) } as Boolean

    private fun JSONObject.requireString(name: String): String =
        get(name).also { require(it is String) } as String

    private fun JSONObject.requireLong(name: String): Long {
        val value = get(name)
        require(value is Byte || value is Short || value is Int || value is Long)
        return (value as Number).toLong()
    }

    private fun JSONObject.requireInt(name: String): Int {
        val value = requireLong(name)
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
        return value.toInt()
    }
}

/** Writes only after interactive App Server evidence changes; diagnostics never instantiate it. */
internal class MigrationSessionReadinessPublisher(
    file: File,
    private val packageSnapshot: () -> MigrationPackageSnapshot,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val processId: () -> Int = Process::myPid,
    private val writer: MigrationReadinessWriter = androidAtomicReadinessWriter(file),
) {
    private val baseFile = file
    private var lastPublished: MigrationSessionReadinessSnapshot? = readExisting(file.toPath())
    private var publishedInThisProcess = false

    @Synchronized
    fun publish(projection: MigrationSessionReadinessProjection): Boolean {
        val currentPackage = packageSnapshot()
        val previous = lastPublished
        val now = clockMillis()
        val previousAgeMillis = previous?.let { (now - it.observationTimestampMillis).coerceAtLeast(0L) }
        if (
            publishedInThisProcess &&
            previous != null &&
            previous.packageName == currentPackage.packageName &&
            previous.longVersionCode == currentPackage.longVersionCode &&
            previous.packageLastUpdateTimeMillis == currentPackage.lastUpdateTimeMillis &&
            previous.projection() == projection &&
            previousAgeMillis != null &&
            previousAgeMillis < IDENTICAL_PROJECTION_REFRESH_MILLIS
        ) {
            return false
        }
        val observedAt = now.coerceAtLeast(currentPackage.lastUpdateTimeMillis)
        val snapshot = MigrationSessionReadinessSnapshot(
            packageName = currentPackage.packageName,
            longVersionCode = currentPackage.longVersionCode,
            packageLastUpdateTimeMillis = currentPackage.lastUpdateTimeMillis,
            observationTimestampMillis = observedAt,
            interactiveProcessId = processId(),
            accountPhase = projection.accountPhase,
            accountReadComplete = projection.accountReadComplete,
            runtimeReady = projection.runtimeReady,
            threadCorrelationSha256 = projection.threadCorrelationSha256,
            threadResumeConfirmed = projection.threadResumeConfirmed,
            memoryModeEnabledAck = projection.memoryModeEnabledAck,
            effectiveModel = projection.effectiveModel,
            effectiveReasoningEffort = projection.effectiveReasoningEffort,
            speechCredentialAvailable = projection.speechCredentialAvailable,
            setupComplete = projection.setupComplete,
            activeTurn = projection.activeTurn,
            dictationActive = projection.dictationActive,
            liveVoiceActive = projection.liveVoiceActive,
            activeAutomationCount = projection.activeAutomationCount,
        )
        val bytes = MigrationSessionReadinessCodec.encode(snapshot)
            .toByteArray(StandardCharsets.UTF_8)
        try {
            writer.write(bytes)
        } finally {
            bytes.fill(0)
        }
        val verified = readExisting(baseFile.toPath())
        check(verified == snapshot) { "migration_readiness_verification" }
        lastPublished = snapshot
        publishedInThisProcess = true
        return true
    }

    private fun readExisting(path: Path): MigrationSessionReadinessSnapshot? {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        val bytes = stableRead(path, MigrationSessionReadinessCodec.MAX_BYTES.toLong(), false)
            ?: return null
        return try {
            MigrationSessionReadinessCodec.decode(bytes.toString(StandardCharsets.UTF_8))
        } catch (_: Exception) {
            null
        } finally {
            bytes.fill(0)
        }
    }

    companion object {
        const val FILE_NAME = "hans-migration-session-readiness-v1.json"
        const val IDENTICAL_PROJECTION_REFRESH_MILLIS =
            MigrationSessionReadinessSnapshot.MAX_AGE_MILLIS / 2L
    }
}

internal fun interface MigrationReadinessWriter {
    fun write(bytes: ByteArray)
}

private fun androidAtomicReadinessWriter(file: File): MigrationReadinessWriter {
    val atomicFile = AtomicFile(file)
    return MigrationReadinessWriter { bytes ->
        val output = atomicFile.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            atomicFile.finishWrite(output)
        } catch (failure: Exception) {
            atomicFile.failWrite(output)
            throw failure
        }
    }
}

internal fun readMigrationSessionReadiness(path: Path): MigrationSessionReadinessSnapshot? {
    if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
    val bytes = stableRead(
        path,
        MigrationSessionReadinessCodec.MAX_BYTES.toLong(),
        missingAllowed = false,
    ) ?: return null
    return try {
        MigrationSessionReadinessCodec.decode(bytes.toString(StandardCharsets.UTF_8))
    } finally {
        bytes.fill(0)
    }
}
