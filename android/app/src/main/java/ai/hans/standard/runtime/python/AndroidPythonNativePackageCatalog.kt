package ai.hans.standard.runtime.python

import ai.hans.standard.codex.JsonContract
import android.content.Context
import android.content.res.AssetManager
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal fun interface PythonNativeCatalogAssetSource {
    fun open(path: String): InputStream
}

internal class AndroidPythonNativeCatalogAssetSource(
    private val assets: AssetManager,
) : PythonNativeCatalogAssetSource {
    override fun open(path: String): InputStream = assets.open(path, AssetManager.ACCESS_STREAMING)
}

/**
 * Closed catalog backed exclusively by the installed APK's signed assets and nativeLibraryDir.
 * Construction verifies every native ELF byte before any package can participate in resolution.
 */
internal class AndroidPythonNativePackageCatalog private constructor(
    private val entriesByName: Map<String, List<PythonNativeCatalogEntry>>,
) : PythonNativePackageCatalog {
    override fun find(
        pin: PythonNativeCatalogPin,
        target: PythonEnvironmentTarget,
    ): PythonNativeCatalogEntry? = entriesByName[pin.normalizedName]
        .orEmpty()
        .singleOrNull { entry ->
            entry.catalogId == pin.catalogId &&
                entry.version == pin.version &&
                entry.payloadSha256 == pin.payloadSha256 &&
                target.directorySegment in entry.supportedTargets
        }

    override fun candidates(
        normalizedName: String,
        target: PythonEnvironmentTarget,
    ): List<PythonNativeCatalogEntry> = entriesByName[normalizedName]
        .orEmpty()
        .filter { target.directorySegment in it.supportedTargets }

    companion object {
        const val CATALOG_ASSET = "hans/python/native-packages/catalog.json"
        private const val SCHEMA_VERSION = 1
        private const val MAX_CATALOG_BYTES = 256 * 1024

        fun load(context: Context): AndroidPythonNativePackageCatalog = load(
            source = AndroidPythonNativeCatalogAssetSource(context.assets),
            nativeLibraryDirectory = File(context.applicationInfo.nativeLibraryDir),
        )

        internal fun load(
            source: PythonNativeCatalogAssetSource,
            nativeLibraryDirectory: File,
        ): AndroidPythonNativePackageCatalog {
            val raw = source.open(CATALOG_ASSET).use { input ->
                readUtf8Bounded(input, MAX_CATALOG_BYTES)
            }
            val root = JsonContract.parseObject(raw, MAX_CATALOG_BYTES)
            JsonContract.requireOnlyKeys(
                root,
                setOf("schemaVersion", "pythonVersion", "packages"),
                "Python native package catalog",
            )
            require(JsonContract.requiredLong(root, "schemaVersion") == SCHEMA_VERSION.toLong()) {
                "Unsupported Python native package catalog"
            }
            val pythonVersion = JsonContract.requiredString(root, "pythonVersion", 32)
            require(pythonVersion.startsWith("3.14.")) { "Native catalog targets another Python" }
            val nativeRoot = nativeLibraryDirectory.canonicalFile
            require(nativeRoot.isDirectory && !Files.isSymbolicLink(nativeRoot.toPath())) {
                "APK native library directory is unavailable"
            }
            val packages = JsonContract.requiredArray(root, "packages")
            require(packages.length() in 1..PythonEnvironmentLimits.MAX_NATIVE_PACKAGES) {
                "Invalid Python native package count"
            }
            val entries = buildList {
                repeat(packages.length()) { index ->
                    add(parseEntry(packages.requiredObject(index), source, nativeRoot))
                }
            }
            require(entries.map { it.catalogId }.distinct().size == entries.size) {
                "Duplicate Python native catalog id"
            }
            require(entries.map { Triple(it.packageName, it.version, it.supportedTargets) }
                .distinct().size == entries.size) {
                "Duplicate Python native package candidate"
            }
            return AndroidPythonNativePackageCatalog(
                entries.groupBy { PythonEnvironmentContract.normalizePackageName(it.packageName) }
                    .mapValues { (_, values) -> values.sortedBy { it.version } },
            )
        }

        private fun parseEntry(
            json: JSONObject,
            source: PythonNativeCatalogAssetSource,
            nativeRoot: File,
        ): PythonNativeCatalogEntry {
            JsonContract.requireOnlyKeys(
                json,
                setOf(
                    "catalogId", "packageName", "version", "payloadSha256",
                    "sourceSdistSha256", "supportedTargets", "importNames",
                    "requiresPython", "requiresDist", "companionWheel", "nativeLibraries",
                ),
                "Python native package",
            )
            val companionJson = JsonContract.requiredObject(json, "companionWheel")
            JsonContract.requireOnlyKeys(
                companionJson,
                setOf("assetPath", "fileName", "sha256", "sizeBytes"),
                "Python native companion wheel",
            )
            val assetPath = JsonContract.requiredString(companionJson, "assetPath", 256)
            val companion = PythonNativeCompanionWheel(
                assetPath = assetPath,
                fileName = JsonContract.requiredString(companionJson, "fileName", 256),
                sha256 = JsonContract.requiredString(companionJson, "sha256", 64),
                sizeBytes = JsonContract.requiredLong(companionJson, "sizeBytes"),
                source = PythonSignedAssetSource { source.open(assetPath) },
            )
            val libraries = JsonContract.requiredArray(json, "nativeLibraries").mapObjects { library ->
                JsonContract.requireOnlyKeys(
                    library,
                    setOf("moduleName", "packagedName", "sha256", "sizeBytes"),
                    "Python native library",
                )
                PythonNativeLibraryPin(
                    moduleName = JsonContract.requiredString(library, "moduleName", 128),
                    packagedName = JsonContract.requiredString(library, "packagedName", 256),
                    sha256 = JsonContract.requiredString(library, "sha256", 64),
                    sizeBytes = JsonContract.requiredLong(library, "sizeBytes"),
                ).also { pin -> verifyNativeLibrary(nativeRoot, pin) }
            }
            val entry = PythonNativeCatalogEntry(
                catalogId = JsonContract.requiredString(json, "catalogId", 128),
                packageName = JsonContract.requiredString(json, "packageName", 128),
                version = JsonContract.requiredString(json, "version", 128),
                payloadSha256 = JsonContract.requiredString(json, "payloadSha256", 64),
                sourceSdistSha256 = JsonContract.requiredString(json, "sourceSdistSha256", 64),
                supportedTargets = JsonContract.requiredArray(json, "supportedTargets").stringSet(16, 96),
                importNames = JsonContract.requiredArray(json, "importNames").stringSet(32, 128),
                requiresPython = JsonContract.optionalString(
                    json,
                    "requiresPython",
                    PythonEnvironmentLimits.MAX_REQUIRES_PYTHON_BYTES,
                ),
                requiresDist = JsonContract.requiredArray(json, "requiresDist").stringList(256, 2_048),
                companionWheel = companion,
                nativeLibraries = libraries,
            )
            require(entry.payloadSha256 == payloadDigest(entry)) {
                "Python native package payload identity does not match its catalog"
            }
            return entry
        }

        internal fun payloadDigest(entry: PythonNativeCatalogEntry): String {
            val values = buildList {
                add("hans.python-native-payload.v1")
                add(entry.catalogId)
                add(entry.packageName)
                add(entry.version)
                add(entry.sourceSdistSha256)
                add(entry.supportedTargets.size.toString())
                addAll(entry.supportedTargets.sorted())
                add(entry.requiresPython.orEmpty())
                add(entry.requiresDist.size.toString())
                addAll(entry.requiresDist)
                add(entry.importNames.size.toString())
                addAll(entry.importNames.sorted())
                add(entry.companionWheel.assetPath)
                add(entry.companionWheel.fileName)
                add(entry.companionWheel.sizeBytes.toString())
                add(entry.companionWheel.sha256)
                add(entry.nativeLibraries.size.toString())
                entry.nativeLibraries.forEach { library ->
                    add(library.moduleName)
                    add(library.packagedName)
                    add(library.sizeBytes.toString())
                    add(library.sha256)
                }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            values.forEach { value ->
                val bytes = value.toByteArray(Charsets.UTF_8)
                digest.update(bytes.size.toString().toByteArray(Charsets.US_ASCII))
                digest.update(':'.code.toByte())
                digest.update(bytes)
            }
            return digest.digest().joinToString("") {
                "%02x".format(Locale.US, it.toInt() and 0xff)
            }
        }

        private fun verifyNativeLibrary(root: File, pin: PythonNativeLibraryPin) {
            val library = File(root, pin.packagedName)
            require(library.parentFile?.canonicalFile == root && library.isFile &&
                !Files.isSymbolicLink(library.toPath())) {
                "Signed Python native library is missing"
            }
            require(library.length() == pin.sizeBytes && sha256(library) == pin.sha256) {
                "Signed Python native library does not match its catalog"
            }
        }

        private fun readUtf8Bounded(input: InputStream, maximum: Int): String {
            val output = java.io.ByteArrayOutputStream(minOf(maximum, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                require(output.size() + read <= maximum) { "Python native catalog is too large" }
                output.write(buffer, 0, read)
            }
            return output.toString(Charsets.UTF_8.name())
        }

        private fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            BufferedInputStream(file.inputStream()).use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") {
                "%02x".format(Locale.US, it.toInt() and 0xff)
            }
        }

        private fun JSONArray.requiredObject(index: Int): JSONObject =
            optJSONObject(index) ?: error("Python native catalog entry must be an object")

        private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> = buildList {
            require(length() in 1..32) { "Invalid Python native library count" }
            repeat(length()) { add(transform(requiredObject(it))) }
        }

        private fun JSONArray.stringSet(maximumCount: Int, maximumBytes: Int): Set<String> =
            stringList(maximumCount, maximumBytes).toSet().also {
                require(it.size == length()) { "Duplicate Python native catalog string" }
            }

        private fun JSONArray.stringList(maximumCount: Int, maximumBytes: Int): List<String> =
            buildList {
                require(length() <= maximumCount) { "Python native catalog array is too large" }
                repeat(length()) { index ->
                    val value = opt(index) as? String
                        ?: error("Python native catalog array must contain strings")
                    JsonContract.requireUtf8Bound(value, maximumBytes, "Python native catalog string")
                    require(value.isNotBlank()) { "Python native catalog string is blank" }
                    add(value)
                }
            }
    }
}
