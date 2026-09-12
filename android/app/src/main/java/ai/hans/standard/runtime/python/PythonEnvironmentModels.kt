package ai.hans.standard.runtime.python

import java.io.File
import java.io.InputStream
import java.util.concurrent.CancellationException
import org.json.JSONArray
import org.json.JSONObject

/** Exact interpreter and Android target for one immutable package environment. */
data class PythonEnvironmentTarget(
    val pythonVersion: String,
    val interpreterTag: String,
    val androidAbi: String,
    val minimumAndroidApi: Int,
) {
    init {
        require(PYTHON_VERSION.matches(pythonVersion)) { "Invalid Python version" }
        require(INTERPRETER_TAG.matches(interpreterTag)) { "Invalid interpreter tag" }
        require(ANDROID_ABI.matches(androidAbi)) { "Invalid Android ABI" }
        require(minimumAndroidApi in 31..99) { "Unsupported minimum Android API" }
    }

    /** Safe single directory component used below the plugin directory. */
    val directorySegment: String
        get() = "$interpreterTag-android_${androidAbi.replace('-', '_')}"

    companion object {
        private val PYTHON_VERSION = Regex("3\\.[0-9]{1,2}(?:\\.[0-9]{1,3})?")
        private val INTERPRETER_TAG = Regex("(?:py|cp)[0-9]{2,4}")
        private val ANDROID_ABI = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,31}")
    }
}

/** A content-addressed, pre-built, pure-Python wheel. */
data class PythonWheelPin(
    val packageName: String,
    val version: String,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val sourceUri: String,
    val requiresPython: String? = null,
) {
    init {
        require(PythonEnvironmentContract.isPackageName(packageName)) { "Invalid package name" }
        require(PythonEnvironmentContract.isVersion(version)) { "Invalid package version" }
        require(PythonEnvironmentContract.isWheelFileName(fileName)) { "Invalid wheel filename" }
        require(PythonEnvironmentContract.isSha256(sha256)) { "Invalid wheel digest" }
        require(sizeBytes in 1..PythonEnvironmentLimits.MAX_WHEEL_BYTES) { "Invalid wheel size" }
        require(sourceUri.length in 1..PythonEnvironmentLimits.MAX_SOURCE_URI_BYTES) {
            "Invalid wheel source URI"
        }
        require(sourceUri.startsWith("https://") || sourceUri.startsWith("file://")) {
            "Wheel source must be HTTPS or an explicit offline file URI"
        }
        requiresPython?.let {
            require(it.length <= PythonEnvironmentLimits.MAX_REQUIRES_PYTHON_BYTES) {
                "Requires-Python is too long"
            }
        }
    }

    val normalizedName: String get() = PythonEnvironmentContract.normalizePackageName(packageName)
}

/** Native modules can only refer to entries already shipped in the signed APK catalog. */
data class PythonNativeCatalogPin(
    val packageName: String,
    val version: String,
    val catalogId: String,
    val payloadSha256: String,
) {
    init {
        require(PythonEnvironmentContract.isPackageName(packageName)) { "Invalid native package name" }
        require(PythonEnvironmentContract.isVersion(version)) { "Invalid native package version" }
        require(PythonEnvironmentContract.isSafeIdentifier(catalogId)) { "Invalid native catalog id" }
        require(PythonEnvironmentContract.isSha256(payloadSha256)) { "Invalid native catalog digest" }
    }

    val normalizedName: String get() = PythonEnvironmentContract.normalizePackageName(packageName)
}

/**
 * Exact artifact lock. There are no ranges, sdists, build requirements, or implicit indexes here.
 * A resolver is responsible for proving transitive Requires-Dist closure; the offline installer
 * verifies every listed artifact but deliberately does not pretend to be a PEP 508 resolver.
 * Lists are copied at construction so later caller mutation cannot change the effective lock.
 */
class PythonEnvironmentLock(
    val schemaVersion: Int,
    val pluginId: String,
    val target: PythonEnvironmentTarget,
    wheels: List<PythonWheelPin>,
    nativePackages: List<PythonNativeCatalogPin> = emptyList(),
    val sourceSha256: String? = null,
) {
    val wheels: List<PythonWheelPin> = wheels.toList()
    val nativePackages: List<PythonNativeCatalogPin> = nativePackages.toList()

    init {
        require(schemaVersion == PythonEnvironmentContract.LOCK_SCHEMA_VERSION) {
            "Unsupported Python environment lock version"
        }
        require(PythonEnvironmentContract.isPluginId(pluginId)) { "Invalid plugin id" }
        require(this.wheels.size <= PythonEnvironmentLimits.MAX_WHEELS) { "Too many wheels" }
        require(this.nativePackages.size <= PythonEnvironmentLimits.MAX_NATIVE_PACKAGES) {
            "Too many native catalog packages"
        }
        sourceSha256?.let {
            require(PythonEnvironmentContract.isSha256(it)) { "Invalid plugin source digest" }
        }
        val allNames = (this.wheels.map { it.normalizedName } +
            this.nativePackages.map { it.normalizedName })
        require(allNames.distinct().size == allNames.size) { "Duplicate locked package" }
        require(this.wheels.map { it.sha256 }.distinct().size == this.wheels.size) {
            "Duplicate wheel digest"
        }
        require(this.wheels.sumOf { it.sizeBytes } <= PythonEnvironmentLimits.MAX_TOTAL_WHEEL_BYTES) {
            "Locked wheel set is too large"
        }
    }

    val lockDigest: String get() = PythonEnvironmentLockCodec.digest(this)
}

class PythonOfflineWheelSet private constructor(
    private val filesByDigest: Map<String, File>,
) {
    fun fileFor(pin: PythonWheelPin): File =
        filesByDigest[pin.sha256] ?: error("Offline artifact is missing for ${pin.packageName}")

    fun digests(): Set<String> = filesByDigest.keys

    companion object {
        fun of(filesByDigest: Map<String, File>): PythonOfflineWheelSet {
            require(filesByDigest.size <= PythonEnvironmentLimits.MAX_WHEELS) { "Too many artifacts" }
            val copy = filesByDigest.mapKeys { (digest, _) ->
                require(PythonEnvironmentContract.isSha256(digest)) { "Invalid artifact digest" }
                digest
            }.mapValues { (_, file) ->
                require(file.isFile) { "Offline artifact is not a regular file" }
                file
            }
            return PythonOfflineWheelSet(copy.toMap())
        }

        val EMPTY = PythonOfflineWheelSet(emptyMap())
    }
}

data class PythonEnvironmentInstallRequest(
    val lock: PythonEnvironmentLock,
    val offlineWheels: PythonOfflineWheelSet,
    val pluginSourceDirectory: File? = null,
)

/** Opaque handle for a verified-but-not-yet-committed environment activation. */
class PythonEnvironmentInstallReceipt internal constructor(
    val transactionId: String,
    val pluginId: String,
    val target: PythonEnvironmentTarget,
    val lockDigest: String,
    val environmentDigest: String,
) {
    init {
        require(PythonEnvironmentContract.isSafeIdentifier(transactionId)) {
            "Invalid environment transaction id"
        }
        require(PythonEnvironmentContract.isPluginId(pluginId)) { "Invalid plugin id" }
        require(PythonEnvironmentContract.isSha256(lockDigest)) { "Invalid lock digest" }
        require(PythonEnvironmentContract.isSha256(environmentDigest)) { "Invalid environment digest" }
    }
}

enum class PythonEnvironmentRecoveryState {
    PREPARED,
    COMMITTED,
}

/** Exact content identity only; no mutable filesystem path is exposed to recovery callers. */
data class PythonEnvironmentActivationIdentity(
    val pluginId: String,
    val target: PythonEnvironmentTarget,
    val lockDigest: String,
    val environmentDigest: String,
) {
    init {
        require(PythonEnvironmentContract.isPluginId(pluginId)) { "Invalid recovery plugin id" }
        require(PythonEnvironmentContract.isSha256(lockDigest)) { "Invalid recovery lock identity" }
        require(PythonEnvironmentContract.isSha256(environmentDigest)) {
            "Invalid recovery environment identity"
        }
    }
}

/**
 * Durable transaction reconstructed after process death. Pass [receipt] back to the normal
 * commit/finalize/rollback APIs; the store re-verifies every identity and operation state.
 */
data class PythonEnvironmentRecoveryDescriptor(
    val receipt: PythonEnvironmentInstallReceipt,
    val state: PythonEnvironmentRecoveryState,
    val installed: PythonEnvironmentActivationIdentity,
    val previous: PythonEnvironmentActivationIdentity?,
    val newlyInstalled: Boolean,
)

enum class PythonEnvironmentState {
    MISSING,
    INSTALLED,
    ACTIVE,
    CORRUPT,
}

data class PythonEnvironmentStatus(
    val pluginId: String,
    val target: PythonEnvironmentTarget,
    val state: PythonEnvironmentState,
    val lockDigest: String? = null,
    val environmentDirectory: File? = null,
    val wheelPackages: List<String> = emptyList(),
    val nativeCatalogPackages: List<String> = emptyList(),
    val detail: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("pluginId", pluginId)
        .put("target", PythonEnvironmentLockCodec.targetJson(target))
        .put("state", state.name.lowercase())
        .put("lockDigest", lockDigest ?: JSONObject.NULL)
        .put("wheelPackages", JSONArray(wheelPackages.sorted()))
        .put("nativeCatalogPackages", JSONArray(nativeCatalogPackages.sorted()))
        .put("detail", detail ?: JSONObject.NULL)
}

data class PythonEnvironmentStatusProjection(
    val effectiveEnvironmentDigest: String,
    val environments: List<PythonEnvironmentStatus>,
) {
    init {
        require(PythonEnvironmentContract.isSha256(effectiveEnvironmentDigest)) {
            "Invalid effective environment digest"
        }
        require(environments.size <= PythonEnvironmentLimits.MAX_STATUS_ENVIRONMENTS) {
            "Too many environment statuses"
        }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("effectiveEnvironmentDigest", effectiveEnvironmentDigest)
        .put("environments", JSONArray(environments.map { it.toJson() }))
}

fun interface PythonEnvironmentStatusProvider {
    fun snapshot(): PythonEnvironmentStatusProjection
}

data class PythonEnvironmentResolutionRequest(
    val pluginId: String,
    val requirements: List<String>,
    val target: PythonEnvironmentTarget,
) {
    init {
        require(PythonEnvironmentContract.isPluginId(pluginId)) { "Invalid plugin id" }
        require(requirements.size <= PythonEnvironmentLimits.MAX_REQUIREMENTS) {
            "Too many package requirements"
        }
        requirements.forEach {
            require(it.isNotBlank() && it.length <= PythonEnvironmentLimits.MAX_REQUIREMENT_BYTES) {
                "Invalid package requirement"
            }
        }
    }
}

/** Resolver implementations must return a complete, immutable and hash-pinned lock. */
fun interface PythonEnvironmentResolver {
    fun resolve(request: PythonEnvironmentResolutionRequest): PythonEnvironmentLock
}

data class PythonWheelDownloadReceipt(
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * Network is deliberately outside the installer. A downloader receives an already resolved pin,
 * must write only to [destination], and is still re-verified byte-for-byte by offline install.
 */
fun interface PythonWheelDownloader {
    fun download(pin: PythonWheelPin, destination: File): PythonWheelDownloadReceipt
}

data class PythonNativeCatalogEntry(
    val catalogId: String,
    val packageName: String,
    val version: String,
    val payloadSha256: String,
    val sourceSdistSha256: String,
    val supportedTargets: Set<String>,
    val importNames: Set<String> = emptySet(),
    val requiresPython: String? = null,
    val requiresDist: List<String> = emptyList(),
    val companionWheel: PythonNativeCompanionWheel,
    val nativeLibraries: List<PythonNativeLibraryPin>,
) {
    init {
        require(PythonEnvironmentContract.isSafeIdentifier(catalogId)) { "Invalid native catalog id" }
        require(PythonEnvironmentContract.isPackageName(packageName)) { "Invalid native package name" }
        require(PythonEnvironmentContract.isVersion(version)) { "Invalid native package version" }
        require(PythonEnvironmentContract.isSha256(payloadSha256)) { "Invalid native payload digest" }
        require(PythonEnvironmentContract.isSha256(sourceSdistSha256)) {
            "Invalid native source digest"
        }
        require(supportedTargets.isNotEmpty() && supportedTargets.size <= 16) {
            "Invalid native target set"
        }
        supportedTargets.forEach {
            require(it.length in 1..96 && PythonEnvironmentContract.isSafeIdentifier(it)) {
                "Invalid native target"
            }
        }
        require(importNames.isNotEmpty() && importNames.size <= 32) {
            "Native catalog entry must declare bounded import roots"
        }
        importNames.forEach {
            require(PYTHON_IMPORT.matches(it)) { "Invalid native import name" }
        }
        requiresPython?.let {
            require(it.isNotBlank() && it.length <= PythonEnvironmentLimits.MAX_REQUIRES_PYTHON_BYTES) {
                "Invalid native Requires-Python"
            }
        }
        require(requiresDist.size <= 256) { "Too many native dependencies" }
        requiresDist.forEach {
            require(it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 2_048) {
                "Invalid native dependency"
            }
        }
        require(nativeLibraries.isNotEmpty() && nativeLibraries.size <= 32) {
            "Invalid native library set"
        }
        require(nativeLibraries.map { it.moduleName }.distinct().size == nativeLibraries.size) {
            "Duplicate native module"
        }
        require(nativeLibraries.all { it.moduleName in importNames }) {
            "Native library modules must be declared imports"
        }
    }

    private companion object {
        val PYTHON_IMPORT = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")
    }
}

/** Opens one immutable companion wheel directly from the APK's signed asset table. */
fun interface PythonSignedAssetSource {
    fun open(): InputStream
}

data class PythonNativeCompanionWheel(
    val assetPath: String,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val source: PythonSignedAssetSource,
) {
    init {
        require(ASSET_PATH.matches(assetPath) && assetPath.startsWith("hans/python/native-packages/")) {
            "Invalid native companion asset path"
        }
        require(PythonEnvironmentContract.isWheelFileName(fileName)) {
            "Invalid native companion wheel filename"
        }
        require(PythonEnvironmentContract.isSha256(sha256)) {
            "Invalid native companion wheel digest"
        }
        require(sizeBytes in 1..PythonEnvironmentLimits.MAX_WHEEL_BYTES) {
            "Invalid native companion wheel size"
        }
    }

    private companion object {
        val ASSET_PATH = Regex("[A-Za-z0-9._-]+(?:/[A-Za-z0-9._-]+)*")
    }
}

data class PythonNativeLibraryPin(
    val moduleName: String,
    val packagedName: String,
    val sha256: String,
    val sizeBytes: Long,
) {
    init {
        require(PYTHON_IMPORT.matches(moduleName)) { "Invalid native library module" }
        require(packagedName == "libhans_py_${moduleName.replace(".", "__")}.so") {
            "Native library name does not match its module"
        }
        require(PythonEnvironmentContract.isSha256(sha256)) { "Invalid native library digest" }
        require(sizeBytes in 1..PythonEnvironmentLimits.MAX_WHEEL_BYTES) {
            "Invalid native library size"
        }
    }

    private companion object {
        val PYTHON_IMPORT = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")
    }
}

/** Minimal per-environment authority transported to the isolated interpreter. */
data class PythonAllowedNativeModule(
    val module: String,
    val packagedName: String,
) {
    init {
        require(PYTHON_IMPORT.matches(module)) { "Invalid allowed native module" }
        require(packagedName == "libhans_py_${module.replace(".", "__")}.so") {
            "Allowed native library name does not match its module"
        }
    }

    private companion object {
        val PYTHON_IMPORT = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*")
    }
}

internal fun PythonNativeCatalogEntry.allowedNativeModules(): List<PythonAllowedNativeModule> =
    nativeLibraries.map { library ->
        PythonAllowedNativeModule(
            module = library.moduleName,
            packagedName = library.packagedName,
        )
    }.sortedWith(compareBy(PythonAllowedNativeModule::module, PythonAllowedNativeModule::packagedName))

fun interface PythonNativePackageCatalog {
    fun find(pin: PythonNativeCatalogPin, target: PythonEnvironmentTarget): PythonNativeCatalogEntry?

    /** Closed resolver view. The default preserves the empty/test-catalog contract. */
    fun candidates(normalizedName: String, target: PythonEnvironmentTarget): List<PythonNativeCatalogEntry> =
        emptyList()

    companion object {
        val EMPTY = PythonNativePackageCatalog { _, _ -> null }
    }
}

data class PythonPreparedEnvironment(
    val pluginId: String,
    val lockDigest: String,
    val target: PythonEnvironmentTarget,
    val stagingDirectory: File,
    val sitePackagesDirectory: File,
    val sourceDirectory: File,
    val environmentArchive: File,
    val importNames: Set<String>,
)

data class PythonImportSelfTestResult(
    val succeeded: Boolean,
    val importedNames: Set<String> = emptySet(),
    val errorCode: String? = null,
    val detail: String? = null,
)

fun interface PythonEnvironmentImportSelfTester {
    /** Called before activation. It must actually import the requested names in this environment. */
    fun test(environment: PythonPreparedEnvironment): PythonImportSelfTestResult
}

/** Cooperative cancellation used during an offline environment transaction. */
fun interface PythonEnvironmentInstallCancellation {
    fun isCancellationRequested(): Boolean

    fun throwIfCancellationRequested() {
        if (isCancellationRequested()) throw PythonEnvironmentInstallCancelledException()
    }

    companion object {
        val NONE = PythonEnvironmentInstallCancellation { false }
    }
}

class PythonEnvironmentInstallCancelledException : CancellationException(
    "Python environment installation was cancelled",
)

/**
 * Selects only a proven-active environment. A missing/unknown plugin always falls back to the
 * empty baseline rather than accidentally receiving another plugin's packages or source.
 */
interface PythonEnvironmentSelectionProvider : PythonEnvironmentStatusProvider {
    fun digestFor(pluginId: String?): String

    companion object {
        val BASELINE = object : PythonEnvironmentSelectionProvider {
            override fun digestFor(pluginId: String?) = PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST

            override fun snapshot() = PythonEnvironmentStatusProjection(
                effectiveEnvironmentDigest = PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST,
                environments = emptyList(),
            )
        }
    }
}

data class PythonPinnedPipBootstrapContract(
    val pipVersion: String,
    val wheelAsset: String,
    val wheelSha256: String,
    val wheelBytes: Long,
    val requireHashes: Boolean = true,
    val noIndex: Boolean = true,
    val onlyBinary: Boolean = true,
    val allowBuildBackends: Boolean = false,
    val allowShellExecution: Boolean = false,
) {
    init {
        require(PythonEnvironmentContract.isVersion(pipVersion)) { "Invalid pinned pip version" }
        require(wheelAsset.startsWith("hans/python/") && wheelAsset.endsWith(".whl")) {
            "Pinned pip must be a signed APK wheel asset"
        }
        require(PythonEnvironmentContract.isSha256(wheelSha256)) { "Invalid pinned pip digest" }
        require(wheelBytes in 1..PythonEnvironmentLimits.MAX_WHEEL_BYTES) { "Invalid pip wheel size" }
        require(requireHashes && noIndex && onlyBinary && !allowBuildBackends && !allowShellExecution) {
            "Unsafe pip bootstrap policy"
        }
    }

    /** Documentation/interop contract only; Hans Standard does not spawn pip or a shell. */
    fun policyJson(): JSONObject = JSONObject()
        .put("pipVersion", pipVersion)
        .put("wheelAsset", wheelAsset)
        .put("wheelSha256", wheelSha256)
        .put("wheelBytes", wheelBytes)
        .put("requireHashes", requireHashes)
        .put("noIndex", noIndex)
        .put("onlyBinary", onlyBinary)
        .put("allowBuildBackends", allowBuildBackends)
        .put("allowShellExecution", allowShellExecution)
}

object PythonEnvironmentLimits {
    const val MAX_WHEELS = 128
    const val MAX_NATIVE_PACKAGES = 32
    const val MAX_REQUIREMENTS = 128
    const val MAX_REQUIREMENT_BYTES = 512
    const val MAX_SOURCE_URI_BYTES = 2_048
    const val MAX_REQUIRES_PYTHON_BYTES = 512
    const val MAX_WHEEL_BYTES = 64L * 1024L * 1024L
    const val MAX_TOTAL_WHEEL_BYTES = 256L * 1024L * 1024L
    const val MAX_EXTRACTED_BYTES_PER_WHEEL = 128L * 1024L * 1024L
    const val MAX_EXTRACTED_BYTES_PER_ENVIRONMENT = 512L * 1024L * 1024L
    const val MAX_ENTRY_BYTES = 32L * 1024L * 1024L
    const val MAX_FILES_PER_WHEEL = 8_192
    const val MAX_FILES_PER_ENVIRONMENT = 32_768
    const val MAX_COMPRESSION_RATIO = 200L
    const val MAX_ARCHIVE_PATH_BYTES = 1_024
    const val MAX_METADATA_BYTES = 1024 * 1024
    const val MAX_SOURCE_FILES = 8_192
    const val MAX_SOURCE_BYTES = 64L * 1024L * 1024L
    const val MAX_SOURCE_FILE_BYTES = 16L * 1024L * 1024L
    const val MAX_STATUS_ENVIRONMENTS = 256
    const val MAX_INSTALLED_ENVIRONMENTS = 256
    const val MAX_INSTALLED_ENVIRONMENT_BYTES = 1024L * 1024L * 1024L
    const val MAX_MANIFEST_BYTES = 256 * 1024
}
