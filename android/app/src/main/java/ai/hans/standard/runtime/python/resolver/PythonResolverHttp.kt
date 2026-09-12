package ai.hans.standard.runtime.python.resolver

import ai.hans.standard.runtime.python.PythonEnvironmentContract
import ai.hans.standard.runtime.python.PythonEnvironmentTarget
import ai.hans.standard.work.WorkHttpCallFactory
import ai.hans.standard.work.WorkHttpRequest
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.LinkedHashMap
import java.util.Locale
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import org.json.JSONObject

data class PythonCachedHttpResource(
    val url: String,
    val etag: String?,
    val body: ByteArray,
    val sha256: String,
) {
    init {
        require(url.startsWith("https://") && url.toByteArray(Charsets.UTF_8).size in 1..2_048)
        require(body.size <= PythonResolverLimits.MAX_SIMPLE_JSON_BYTES)
        require(PythonEnvironmentContract.isSha256(sha256))
        require(PythonEnvironmentContract.sha256(body) == sha256)
        require(
            etag == null || etag.toByteArray(Charsets.UTF_8).size <= 1_024 &&
                etag.none { it == '\r' || it == '\n' || it == '\u0000' },
        )
    }
}

interface PythonResolverHttpCache {
    fun get(url: String): PythonCachedHttpResource?
    fun put(resource: PythonCachedHttpResource)

    companion object {
        val NONE = object : PythonResolverHttpCache {
            override fun get(url: String) = null
            override fun put(resource: PythonCachedHttpResource) = Unit
        }
    }
}

class MemoryPythonResolverHttpCache : PythonResolverHttpCache {
    private val lock = Any()
    private val resources = LinkedHashMap<String, PythonCachedHttpResource>(16, 0.75f, true)
    private var bytes = 0L

    override fun get(url: String): PythonCachedHttpResource? = synchronized(lock) {
        resources[url]?.let { it.copy(body = it.body.copyOf()) }
    }

    override fun put(resource: PythonCachedHttpResource) = synchronized(lock) {
        resources.remove(resource.url)?.let { bytes -= it.body.size }
        resources[resource.url] = resource.copy(body = resource.body.copyOf())
        bytes += resource.body.size
        while (resources.size > PythonResolverLimits.MAX_CACHE_ENTRIES ||
            bytes > PythonResolverLimits.MAX_CACHE_BYTES
        ) {
            val oldest = resources.entries.iterator().next()
            resources.remove(oldest.key)
            bytes -= oldest.value.body.size
        }
    }
}

data class PythonHttpResource(
    val finalUrl: String,
    val body: ByteArray,
    val contentType: String?,
)

/** Fixed-origin, bounded client built on Hans's redirect/DNS/SSRF-safe transport. */
class PythonResolverHttpClient(
    private val calls: WorkHttpCallFactory,
    private val cache: PythonResolverHttpCache = PythonResolverHttpCache.NONE,
) {
    fun get(
        url: String,
        maximumBytes: Int,
        allowedFinalHosts: Set<String>,
        accept: String,
        cancellation: PythonResolutionCancellation,
        cacheResponse: Boolean = true,
    ): PythonHttpResource {
        cancellation.throwIfCancelled()
        require(maximumBytes > 0) { "Python HTTP byte limit must be positive" }
        requireOrigin(url, allowedFinalHosts)
        val cached = if (cacheResponse) {
            runCatching { cache.get(url) }.getOrNull()?.takeIf {
                it.body.size <= maximumBytes && PythonEnvironmentContract.sha256(it.body) == it.sha256
            }
        } else {
            null
        }
        val headers = buildMap {
            put("Accept", accept)
            cached?.etag?.let { put("If-None-Match", it) }
        }
        val call = calls.create(WorkHttpRequest(url, "GET", headers, null, false))
        cancellation.onCancel(call::cancel).use {
            val response = try {
                call.execute()
            } catch (error: Exception) {
                cancellation.throwIfCancelled()
                throw PythonResolutionException(
                    PythonResolutionErrorCode.INDEX_UNAVAILABLE,
                    "Python package metadata could not be downloaded",
                    error,
                )
            }
            try {
                response.use {
                    cancellation.throwIfCancelled()
                    requireOrigin(response.metadata.finalUrl, allowedFinalHosts)
                    if (response.metadata.statusCode == 304) {
                        return cached?.let {
                            PythonHttpResource(
                                response.metadata.finalUrl,
                                it.body.copyOf(),
                                response.metadata.contentType,
                            )
                        } ?: protocol("PyPI returned 304 without a cached representation")
                    }
                    if (response.metadata.statusCode != 200) {
                        throw PythonResolutionException(
                            if (response.metadata.statusCode == 404) {
                                PythonResolutionErrorCode.PACKAGE_INCOMPATIBLE
                            } else {
                                PythonResolutionErrorCode.INDEX_UNAVAILABLE
                            },
                            "PyPI returned HTTP ${response.metadata.statusCode}",
                        )
                    }
                    response.metadata.declaredContentLength?.let {
                        if (it > maximumBytes) limit("PyPI response exceeds its byte limit")
                    }
                    val body = readBounded(response.body, maximumBytes, cancellation)
                    val etag = response.metadata.headers.header("etag")?.takeIf { value ->
                        value.toByteArray(Charsets.UTF_8).size <= 1_024 &&
                            value.none { it == '\r' || it == '\n' || it == '\u0000' }
                    }
                    if (cacheResponse && body.size <= PythonResolverLimits.MAX_SIMPLE_JSON_BYTES) {
                        runCatching {
                            cache.put(
                                PythonCachedHttpResource(
                                    url = url,
                                    etag = etag,
                                    body = body.copyOf(),
                                    sha256 = PythonEnvironmentContract.sha256(body),
                                ),
                            )
                        }
                    }
                    return PythonHttpResource(response.metadata.finalUrl, body, response.metadata.contentType)
                }
            } catch (error: PythonResolutionException) {
                throw error
            } catch (error: Exception) {
                cancellation.throwIfCancelled()
                throw PythonResolutionException(
                    PythonResolutionErrorCode.INDEX_UNAVAILABLE,
                    "Python package metadata could not be read",
                    error,
                )
            }
        }
    }

    private fun readBounded(
        input: java.io.InputStream,
        maximumBytes: Int,
        cancellation: PythonResolutionCancellation,
    ): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        while (true) {
            cancellation.throwIfCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            if (output.size() + read > maximumBytes) limit("PyPI response exceeds its byte limit")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun requireOrigin(url: String, allowedHosts: Set<String>) {
        val uri = runCatching { URI(url) }.getOrNull() ?: protocol("PyPI returned an invalid URL")
        val host = uri.host?.trimEnd('.')?.lowercase(Locale.ROOT)
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.fragment != null ||
            uri.port !in setOf(-1, 443) || host !in allowedHosts
        ) {
            protocol("PyPI URL escaped its fixed HTTPS origin")
        }
    }

    private fun Map<String, List<String>>.header(name: String): String? = entries
        .firstOrNull { it.key.equals(name, ignoreCase = true) }
        ?.value?.singleOrNull()

    private fun protocol(message: String): Nothing = throw PythonResolutionException(
        PythonResolutionErrorCode.INDEX_PROTOCOL_ERROR,
        message,
    )

    private fun limit(message: String): Nothing = throw PythonResolutionException(
        PythonResolutionErrorCode.RESOURCE_LIMIT_EXCEEDED,
        message,
    )
}

class PyPiSimpleJsonCatalog(
    private val http: PythonResolverHttpClient,
) : PythonPackageCatalog {
    override fun project(
        normalizedName: String,
        target: PythonEnvironmentTarget,
        cancellation: PythonResolutionCancellation,
    ): PythonResolverProject {
        val name = PythonEnvironmentContract.normalizePackageName(normalizedName)
        if (name != normalizedName || !PythonEnvironmentContract.isPackageName(name)) {
            protocol("Invalid normalized project name")
        }
        val response = http.get(
            url = "https://pypi.org/simple/$name/",
            maximumBytes = PythonResolverLimits.MAX_SIMPLE_JSON_BYTES,
            allowedFinalHosts = setOf("pypi.org"),
            accept = "application/vnd.pypi.simple.v1+json",
            cancellation = cancellation,
        )
        val json = parseObject(response.body, "PyPI project JSON")
        val meta = json.optJSONObject("meta") ?: protocol("PyPI project has no API metadata")
        val apiVersion = meta.opt("api-version") as? String
            ?: protocol("PyPI project has no API version")
        if (!apiVersion.startsWith("1.")) protocol("Unsupported PyPI Simple API version")
        val declaredName = json.opt("name") as? String ?: protocol("PyPI project has no name")
        if (PythonEnvironmentContract.normalizePackageName(declaredName) != name) {
            protocol("PyPI returned another project")
        }
        val files = json.optJSONArray("files") ?: protocol("PyPI project has no file list")
        if (files.length() > PythonResolverLimits.MAX_SIMPLE_FILES) {
            limit("PyPI project has too many release files")
        }
        val candidates = mutableListOf<PythonResolverCandidate>()
        var metadataBytes = response.body.size.toLong()
        repeat(files.length()) { index ->
            cancellation.throwIfCancelled()
            val file = files.optJSONObject(index) ?: protocol("PyPI file entry is not an object")
            val descriptor = candidateDescriptor(file, target) ?: return@repeat
            if (candidates.size >= PythonResolverLimits.MAX_CANDIDATES_PER_PROJECT) {
                limit("PyPI project has too many compatible wheel candidates")
            }
            val metadata = try {
                http.get(
                    url = descriptor.metadataUrl,
                    maximumBytes = PythonResolverLimits.MAX_CORE_METADATA_BYTES,
                    allowedFinalHosts = setOf("files.pythonhosted.org"),
                    accept = "text/plain, application/octet-stream;q=0.9",
                    cancellation = cancellation,
                )
            } catch (error: PythonResolutionException) {
                if (error.code == PythonResolutionErrorCode.PACKAGE_INCOMPATIBLE) return@repeat
                throw error
            }
            metadataBytes += metadata.body.size
            if (metadataBytes > PythonResolverLimits.MAX_TOTAL_METADATA_BYTES) {
                limit("PyPI core metadata exceeds the aggregate byte limit")
            }
            if (descriptor.metadataSha256 != null &&
                PythonEnvironmentContract.sha256(metadata.body) != descriptor.metadataSha256
            ) {
                throw PythonResolutionException(
                    PythonResolutionErrorCode.HASH_MISMATCH,
                    "PyPI core metadata digest mismatch",
                )
            }
            parseCoreMetadata(name, descriptor, metadata.body)?.let(candidates::add)
        }
        return PythonResolverProject(
            normalizedName = name,
            candidates = candidates.sortedWith(
                compareBy<PythonResolverCandidate> { it.version }
                    .thenBy { it.fileName },
            ),
            metadataBytes = metadataBytes,
        )
    }

    private fun candidateDescriptor(
        json: JSONObject,
        target: PythonEnvironmentTarget,
    ): CandidateDescriptor? {
        val fileName = json.opt("filename") as? String ?: return null
        if (!PythonEnvironmentContract.isWheelFileName(fileName) || !isCompatiblePureWheel(fileName, target)) {
            return null
        }
        val url = json.opt("url") as? String ?: return null
        requireFilesOrigin(url)
        val hashes = json.optJSONObject("hashes") ?: return null
        val sha256 = hashes.opt("sha256") as? String ?: return null
        if (!PythonEnvironmentContract.isSha256(sha256)) return null
        val size = json.optLongStrict("size") ?: return null
        if (size !in 1..(64L * 1024L * 1024L)) return null
        val requiresPythonKey = when {
            json.has("requires-python") -> "requires-python"
            json.has("requires_python") -> "requires_python"
            else -> null
        }
        val requiresPython = requiresPythonKey?.let { key ->
            if (json.isNull(key)) null else {
                val value = json.opt(key) as? String ?: return null
                value.takeIf {
                    it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 512
                } ?: return null
            }
        }
        val yanked = when (val value = json.opt("yanked")) {
            null, JSONObject.NULL, false -> false
            true, is String -> true
            else -> return null
        }
        val metadataField = when {
            json.has("core-metadata") -> json.opt("core-metadata")
            json.has("data-dist-info-metadata") -> json.opt("data-dist-info-metadata")
            else -> null
        }
        val metadataDigest = when (metadataField) {
            true -> null
            is JSONObject -> (metadataField.opt("sha256") as? String)?.takeIf {
                PythonEnvironmentContract.isSha256(it)
            } ?: return null
            else -> return null
        }
        return CandidateDescriptor(
            fileName = fileName,
            url = url,
            sha256 = sha256,
            sizeBytes = size,
            simpleRequiresPython = requiresPython,
            yanked = yanked,
            metadataUrl = "$url.metadata",
            metadataSha256 = metadataDigest,
        )
    }

    private fun parseCoreMetadata(
        normalizedName: String,
        descriptor: CandidateDescriptor,
        bytes: ByteArray,
    ): PythonResolverCandidate? {
        val text = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        }.getOrNull() ?: return null
        if ('\u0000' in text) return null
        val headers = parseHeaders(text) ?: return null
        val names = headers.values("Name")
        val versions = headers.values("Version")
        if (names.size != 1 || versions.size != 1 ||
            PythonEnvironmentContract.normalizePackageName(names.single()) != normalizedName
        ) return null
        val version = versions.single()
        if (!PythonEnvironmentContract.isVersion(version)) return null
        val requiresPythonValues = headers.values("Requires-Python")
        if (requiresPythonValues.size > 1) return null
        val metadataRequiresPython = requiresPythonValues.singleOrNull()
        if (descriptor.simpleRequiresPython != metadataRequiresPython) return null
        val dependencies = headers.values("Requires-Dist")
        if (dependencies.size > PythonResolverLimits.MAX_DEPENDENCIES_PER_CANDIDATE ||
            dependencies.any {
                it.isBlank() || it.toByteArray(Charsets.UTF_8).size >
                    PythonResolverLimits.MAX_DEPENDENCY_BYTES
            }
        ) return null
        return runCatching {
            PythonResolverCandidate(
                normalizedName = normalizedName,
                version = version,
                fileName = descriptor.fileName,
                sourceUri = descriptor.url,
                sha256 = descriptor.sha256,
                sizeBytes = descriptor.sizeBytes,
                requiresPython = metadataRequiresPython,
                yanked = descriptor.yanked,
                requiresDist = dependencies,
            )
        }.getOrNull()
    }

    private fun isCompatiblePureWheel(fileName: String, target: PythonEnvironmentTarget): Boolean {
        if (target.interpreterTag != "cp314" || !target.pythonVersion.startsWith("3.14.")) return false
        val parts = fileName.removeSuffix(".whl").split('-')
        if (parts.size < 5) return false
        val pythonTags = parts[parts.lastIndex - 2].split('.')
        val abiTags = parts[parts.lastIndex - 1].split('.')
        val platformTags = parts.last().split('.')
        val compatible = setOf("py3", "py314", "cp314")
        return pythonTags.isNotEmpty() && pythonTags.any { it in compatible } &&
            abiTags.all { it == "none" } && platformTags.all { it == "any" }
    }

    private fun parseHeaders(text: String): List<Pair<String, String>>? {
        val values = mutableListOf<Pair<String, StringBuilder>>()
        for (line in text.lineSequence()) {
            if (line.isEmpty()) break
            if ((line.startsWith(' ') || line.startsWith('\t')) && values.isNotEmpty()) {
                values.last().second.append(' ').append(line.trim())
            } else {
                val separator = line.indexOf(':')
                if (separator <= 0) return null
                val name = line.substring(0, separator)
                if (!HEADER_NAME.matches(name)) return null
                values += name to StringBuilder(line.substring(separator + 1).trim())
            }
        }
        return values.map { it.first to it.second.toString() }
    }

    private fun List<Pair<String, String>>.values(name: String): List<String> =
        filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    private fun parseObject(bytes: ByteArray, label: String): JSONObject = try {
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        require('\u0000' !in text)
        JSONObject(text)
    } catch (error: Exception) {
        throw PythonResolutionException(
            PythonResolutionErrorCode.INDEX_PROTOCOL_ERROR,
            "$label is malformed",
            error,
        )
    }

    private fun JSONObject.optLongStrict(key: String): Long? = when (val value = opt(key)) {
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private fun requireFilesOrigin(url: String) {
        val uri = runCatching { URI(url) }.getOrNull() ?: protocol("Invalid PyPI file URL")
        if (uri.scheme != "https" || uri.host?.trimEnd('.')?.lowercase(Locale.ROOT) !=
            "files.pythonhosted.org" || uri.rawUserInfo != null || uri.fragment != null ||
            uri.rawQuery != null || uri.port !in setOf(-1, 443) || url.length > 2_048
        ) protocol("PyPI file URL escaped its fixed origin")
    }

    private fun protocol(message: String): Nothing = throw PythonResolutionException(
        PythonResolutionErrorCode.INDEX_PROTOCOL_ERROR,
        message,
    )

    private fun limit(message: String): Nothing = throw PythonResolutionException(
        PythonResolutionErrorCode.RESOURCE_LIMIT_EXCEEDED,
        message,
    )

    private data class CandidateDescriptor(
        val fileName: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long,
        val simpleRequiresPython: String?,
        val yanked: Boolean,
        val metadataUrl: String,
        val metadataSha256: String?,
    )

    private companion object {
        val HEADER_NAME = Regex("[A-Za-z0-9-]{1,128}")
    }
}

/** Downloads only an already selected, exact PyPI wheel and verifies it before store import. */
class SecurePyPiWheelDownloader(
    private val http: PythonResolverHttpClient,
) : ai.hans.standard.runtime.python.PythonWheelDownloader {
    override fun download(
        pin: ai.hans.standard.runtime.python.PythonWheelPin,
        destination: File,
    ): ai.hans.standard.runtime.python.PythonWheelDownloadReceipt =
        download(pin, destination, PythonResolutionCancellation.NONE)

    fun download(
        pin: ai.hans.standard.runtime.python.PythonWheelPin,
        destination: File,
        cancellation: PythonResolutionCancellation,
    ): ai.hans.standard.runtime.python.PythonWheelDownloadReceipt {
        val requestedParent = destination.parentFile ?: error("Wheel destination has no parent")
        require(!java.nio.file.Files.isSymbolicLink(requestedParent.toPath()))
        require(requestedParent.isDirectory || requestedParent.mkdirs())
        val parent = requestedParent.canonicalFile
        require(!java.nio.file.Files.isSymbolicLink(parent.toPath()))
        require(destination.canonicalFile.parentFile == parent) { "Wheel destination escaped its directory" }
        require(!destination.exists()) { "Wheel destination already exists" }
        val response = http.get(
            pin.sourceUri,
            pin.sizeBytes.toInt(),
            setOf("files.pythonhosted.org"),
            "application/octet-stream",
            cancellation,
            cacheResponse = false,
        )
        if (response.body.size.toLong() != pin.sizeBytes) hashFailure("Wheel size mismatch")
        val digest = PythonEnvironmentContract.sha256(response.body)
        if (digest != pin.sha256) hashFailure("Wheel digest mismatch")
        val staging = File(parent, ".${destination.name}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(staging).use { output ->
                output.write(response.body)
                output.fd.sync()
            }
            if (!staging.renameTo(destination)) error("Cannot publish downloaded wheel")
            destination.setReadable(true, true)
            destination.setWritable(false, false)
            destination.setExecutable(false, false)
        } finally {
            if (staging.exists()) staging.delete()
        }
        return ai.hans.standard.runtime.python.PythonWheelDownloadReceipt(digest, pin.sizeBytes)
    }

    private fun hashFailure(message: String): Nothing = throw PythonResolutionException(
        PythonResolutionErrorCode.HASH_MISMATCH,
        message,
    )
}
