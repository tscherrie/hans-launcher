package ai.hans.standard.runtime.python

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

data class PythonValidatedWheel(
    val pin: PythonWheelPin,
    val packageName: String,
    val version: String,
    val requiresPython: String?,
    val entries: List<PythonWheelEntry>,
    val importNames: Set<String>,
) {
    val extractedBytes: Long get() = entries.filterNot { it.directory }.sumOf { it.uncompressedSize }
    val fileCount: Int get() = entries.count { !it.directory }
}

data class PythonWheelEntry(
    val path: String,
    val compressedSize: Long,
    val uncompressedSize: Long,
    val compressionMethod: Int,
    val directory: Boolean,
    val unixMode: Int,
)

/** Strictly validates and extracts already hash-pinned pure wheels without invoking pip. */
class PythonWheelArchiveValidator(
    private val deviceAndroidApi: Int,
    supportedAndroidAbis: Set<String>,
) {
    private val supportedAndroidAbis = supportedAndroidAbis.toSet()

    init {
        require(deviceAndroidApi >= 31) { "Hans Python requires Android 12 or newer" }
        require(this.supportedAndroidAbis.isNotEmpty()) { "No Android ABI is available" }
    }

    fun validate(pin: PythonWheelPin, archive: File, target: PythonEnvironmentTarget): PythonValidatedWheel {
        validateTarget(target)
        verifyFileIdentity(pin, archive)
        validateFileNameTags(pin.fileName, target)
        val entries = readCentralDirectory(archive)
        require(entries.isNotEmpty()) { "Wheel is empty" }
        require(entries.size <= PythonEnvironmentLimits.MAX_FILES_PER_WHEEL) { "Wheel has too many entries" }
        val files = entries.filterNot { it.directory }
        val totalBytes = files.sumOf { it.uncompressedSize }
        require(totalBytes <= PythonEnvironmentLimits.MAX_EXTRACTED_BYTES_PER_WHEEL) {
            "Wheel expands beyond its limit"
        }
        validateEntries(entries)

        ZipFile(archive).use { zip ->
            val metadataEntries = files.filter { it.path.endsWith(".dist-info/METADATA") }
            val wheelEntries = files.filter { it.path.endsWith(".dist-info/WHEEL") }
            require(metadataEntries.size == 1) { "Wheel must contain exactly one METADATA file" }
            require(wheelEntries.size == 1) { "Wheel must contain exactly one WHEEL file" }
            val metadata = readSmallEntry(zip, metadataEntries.single().path)
            val wheel = readSmallEntry(zip, wheelEntries.single().path)
            val metadataName = headerValues(metadata, "Name").singleOrNull()
                ?: error("Wheel METADATA has no unique Name")
            val metadataVersion = headerValues(metadata, "Version").singleOrNull()
                ?: error("Wheel METADATA has no unique Version")
            require(
                PythonEnvironmentContract.normalizePackageName(metadataName) == pin.normalizedName,
            ) { "Wheel package name does not match its lock" }
            require(metadataVersion == pin.version) { "Wheel version does not match its lock" }
            val requiresPython = headerValues(metadata, "Requires-Python").singleOrNull()
            require(pin.requiresPython == null || pin.requiresPython == requiresPython) {
                "Wheel Requires-Python does not match its lock"
            }
            requiresPython?.let {
                require(PythonRequiresPython.isSatisfied(target.pythonVersion, it)) {
                    "Wheel does not support Python ${target.pythonVersion}"
                }
            }
            require(headerValues(wheel, "Root-Is-Purelib").singleOrNull()?.lowercase() == "true") {
                "Wheel is not declared pure-Python"
            }
            val declaredTags = headerValues(wheel, "Tag")
            require(declaredTags.isNotEmpty() && declaredTags.all { isCompatiblePureTag(it, target) }) {
                "Wheel contains an incompatible or native platform tag"
            }
            val topLevel = files.singleOrNull { it.path.endsWith(".dist-info/top_level.txt") }
                ?.let { readSmallEntry(zip, it.path) }
                ?.lineSequence()
                ?.map(String::trim)
                ?.filter(::isImportName)
                ?.toSet()
                .orEmpty()
            return PythonValidatedWheel(
                pin = pin,
                packageName = metadataName,
                version = metadataVersion,
                requiresPython = requiresPython,
                entries = entries,
                importNames = topLevel.ifEmpty { inferImportNames(files) },
            )
        }
    }

    /** Extracts only entries from a prior validation and rejects cross-wheel collisions. */
    fun extract(
        validated: PythonValidatedWheel,
        archive: File,
        destination: File,
        claimedPaths: MutableSet<String>,
    ) {
        verifyFileIdentity(validated.pin, archive)
        require(destination.isDirectory || destination.mkdirs()) { "Cannot create site-packages" }
        val destinationRoot = destination.canonicalFile
        val expected = validated.entries.associateBy { it.path }
        ZipFile(archive).use { zip ->
            val seen = mutableSetOf<String>()
            val iterator = zip.entries()
            while (iterator.hasMoreElements()) {
                val entry = iterator.nextElement()
                val descriptor = expected[entry.name] ?: error("Wheel changed after validation")
                require(seen.add(descriptor.path)) { "Duplicate wheel entry" }
                val output = File(destinationRoot, descriptor.path).canonicalFile
                require(output.path.startsWith(destinationRoot.path + File.separator)) {
                    "Wheel entry escapes site-packages"
                }
                if (descriptor.directory) {
                    require(output.mkdirs() || output.isDirectory) { "Cannot create wheel directory" }
                    continue
                }
                val relativeKey = descriptor.path.lowercase(Locale.US)
                require(claimedPaths.add(relativeKey)) { "Package files collide across wheels" }
                require(output.parentFile?.let { it.mkdirs() || it.isDirectory } == true) {
                    "Cannot create wheel parent directory"
                }
                require(!output.exists()) { "Wheel attempted to overwrite a package file" }
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(output).use { sink ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var written = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            written += read
                            require(written <= descriptor.uncompressedSize &&
                                written <= PythonEnvironmentLimits.MAX_ENTRY_BYTES) {
                                "Wheel entry expanded beyond its declared size"
                            }
                            sink.write(buffer, 0, read)
                        }
                        require(written == descriptor.uncompressedSize) {
                            "Wheel entry size changed during extraction"
                        }
                        sink.fd.sync()
                    }
                }
                require(output.setReadable(true, true)) { "Cannot make package file readable" }
                require(output.setWritable(false, false)) { "Cannot make package file immutable" }
                output.setExecutable(false, false)
            }
            require(seen == expected.keys) { "Wheel entries changed during extraction" }
        }
    }

    private fun validateTarget(target: PythonEnvironmentTarget) {
        require(target.pythonVersion.startsWith("3.14.")) { "Only CPython 3.14 environments are supported" }
        require(target.interpreterTag == "cp314") { "Unexpected CPython interpreter tag" }
        require(target.minimumAndroidApi <= deviceAndroidApi) { "Device Android API is too old" }
        require(target.androidAbi in supportedAndroidAbis) { "Device ABI is incompatible" }
    }

    private fun verifyFileIdentity(pin: PythonWheelPin, archive: File) {
        require(archive.isFile && !Files.isSymbolicLink(archive.toPath())) {
            "Wheel artifact is not a regular file"
        }
        require(archive.length() == pin.sizeBytes) { "Wheel size does not match its lock" }
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(archive)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
        require(actual == pin.sha256) { "Wheel digest does not match its lock" }
    }

    private fun validateFileNameTags(fileName: String, target: PythonEnvironmentTarget) {
        val components = fileName.removeSuffix(".whl").split('-')
        require(components.size >= 5) { "Malformed wheel filename" }
        val pythonTags = components[components.lastIndex - 2]
        val abiTags = components[components.lastIndex - 1]
        val platformTags = components.last()
        require(abiTags.split('.').all { it == "none" }) { "Native wheel ABI is not allowed" }
        require(platformTags.split('.').all { it == "any" }) { "Android/platform wheel is not allowed" }
        require(isCompatiblePythonTag(pythonTags, target)) { "Wheel Python tag is incompatible" }
    }

    private fun isCompatiblePureTag(tag: String, target: PythonEnvironmentTarget): Boolean {
        val parts = tag.split('-')
        return parts.size == 3 &&
            isCompatiblePythonTag(parts[0], target) &&
            parts[1].split('.').all { it == "none" } &&
            parts[2].split('.').all { it == "any" }
    }

    private fun isCompatiblePythonTag(value: String, target: PythonEnvironmentTarget): Boolean {
        val tags = value.split('.')
        val compatible = setOf("py3", "py314")
        return tags.any { it in compatible } && tags.all { it == "py2" || it in compatible }
    }

    private fun validateEntries(entries: List<PythonWheelEntry>) {
        val exact = mutableSetOf<String>()
        val folded = mutableSetOf<String>()
        entries.forEach { entry ->
            require(exact.add(entry.path)) { "Wheel contains a duplicate path" }
            require(folded.add(entry.path.lowercase(Locale.US))) { "Wheel has a case-colliding path" }
            require(entry.uncompressedSize in 0..PythonEnvironmentLimits.MAX_ENTRY_BYTES) {
                "Wheel entry is too large"
            }
            require(entry.compressedSize >= 0) { "Invalid compressed size" }
            if (entry.uncompressedSize > 0) {
                require(entry.compressedSize > 0 || entry.compressionMethod == 0) {
                    "Invalid zero-byte compressed payload"
                }
                if (entry.compressedSize > 0) {
                    require(entry.uncompressedSize / entry.compressedSize.coerceAtLeast(1) <=
                        PythonEnvironmentLimits.MAX_COMPRESSION_RATIO) {
                        "Wheel compression ratio is unsafe"
                    }
                }
            }
            if (!entry.directory) {
                val lower = entry.path.lowercase(Locale.US)
                val leaf = lower.substringAfterLast('/')
                require(!lower.startsWith("__hans_plugin_source__/")) {
                    "Wheel uses Hans's reserved plugin-source namespace"
                }
                require(DANGEROUS_EXTENSIONS.none(lower::endsWith)) { "Executable package payload is forbidden" }
                require(!lower.endsWith(".pth")) { "Python path hook files are forbidden" }
                require(!lower.contains(".data/scripts/")) { "Wheel script installation is forbidden" }
                require(leaf !in BUILD_BACKEND_FILES) { "Build backend files are forbidden" }
                require(entry.unixMode and EXECUTABLE_BITS == 0) { "Executable wheel mode is forbidden" }
            }
        }
    }

    private fun readSmallEntry(zip: ZipFile, path: String): String {
        val entry = zip.getEntry(path) ?: error("Wheel metadata disappeared")
        require(entry.size in 0..PythonEnvironmentLimits.MAX_METADATA_BYTES.toLong()) {
            "Wheel metadata is too large"
        }
        return zip.getInputStream(entry).use { input ->
            val output = ByteArrayOutputStream(minOf(entry.size.toInt(), 16 * 1024))
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (output.size() <= PythonEnvironmentLimits.MAX_METADATA_BYTES) {
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
            val bytes = output.toByteArray()
            require(bytes.size <= PythonEnvironmentLimits.MAX_METADATA_BYTES) { "Wheel metadata is too large" }
            bytes.toString(StandardCharsets.UTF_8)
        }
    }

    private fun headerValues(text: String, name: String): List<String> {
        val values = mutableListOf<Pair<String, StringBuilder>>()
        text.lineSequence().forEach { line ->
            if ((line.startsWith(' ') || line.startsWith('\t')) && values.isNotEmpty()) {
                values.last().second.append(' ').append(line.trim())
            } else {
                val separator = line.indexOf(':')
                if (separator > 0) {
                    values += line.substring(0, separator).trim() to
                        StringBuilder(line.substring(separator + 1).trim())
                }
            }
        }
        return values.filter { it.first.equals(name, ignoreCase = true) }.map { it.second.toString() }
    }

    private fun inferImportNames(files: List<PythonWheelEntry>): Set<String> = buildSet {
        files.forEach { entry ->
            val first = entry.path.substringBefore('/')
            if (first.endsWith(".dist-info") || first.endsWith(".data")) return@forEach
            val candidate = if ('/' in entry.path) first else first.removeSuffix(".py")
            if (isImportName(candidate)) add(candidate)
        }
    }

    private fun readCentralDirectory(file: File): List<PythonWheelEntry> {
        RandomAccessFile(file, "r").use { input ->
            val length = input.length()
            require(length >= EOCD_MIN_BYTES) { "Truncated wheel archive" }
            val tailLength = minOf(length, EOCD_MAX_SEARCH_BYTES.toLong()).toInt()
            val tail = ByteArray(tailLength)
            input.seek(length - tailLength)
            input.readFully(tail)
            val eocd = (tail.size - EOCD_MIN_BYTES downTo 0).firstOrNull {
                uint32(tail, it) == EOCD_SIGNATURE
            } ?: error("Wheel has no end-of-central-directory record")
            val disk = uint16(tail, eocd + 4)
            val centralDisk = uint16(tail, eocd + 6)
            val entriesOnDisk = uint16(tail, eocd + 8)
            val entryCount = uint16(tail, eocd + 10)
            val centralSize = uint32(tail, eocd + 12)
            val centralOffset = uint32(tail, eocd + 16)
            val commentLength = uint16(tail, eocd + 20)
            require(disk == 0 && centralDisk == 0 && entriesOnDisk == entryCount) {
                "Multi-disk wheels are forbidden"
            }
            require(entryCount != 0xffff && centralSize != UINT32_MAX && centralOffset != UINT32_MAX) {
                "ZIP64 wheels are forbidden"
            }
            require(eocd + EOCD_MIN_BYTES + commentLength == tail.size) { "Malformed wheel comment" }
            require(centralOffset + centralSize <= length) { "Wheel central directory is out of bounds" }
            require(entryCount <= PythonEnvironmentLimits.MAX_FILES_PER_WHEEL) { "Wheel has too many entries" }
            input.seek(centralOffset)
            val entries = ArrayList<PythonWheelEntry>(entryCount)
            repeat(entryCount) {
                val header = ByteArray(CENTRAL_FIXED_BYTES)
                input.readFully(header)
                require(uint32(header, 0) == CENTRAL_SIGNATURE) { "Malformed central directory" }
                val madeByHost = uint16(header, 4) ushr 8
                val flags = uint16(header, 8)
                val method = uint16(header, 10)
                val compressed = uint32(header, 20)
                val uncompressed = uint32(header, 24)
                val nameLength = uint16(header, 28)
                val extraLength = uint16(header, 30)
                val entryCommentLength = uint16(header, 32)
                val external = uint32(header, 38)
                require(flags and 1 == 0) { "Encrypted wheels are forbidden" }
                require(method == 0 || method == 8) { "Unsupported wheel compression" }
                require(compressed != UINT32_MAX && uncompressed != UINT32_MAX) { "ZIP64 entry is forbidden" }
                require(nameLength in 1..PythonEnvironmentLimits.MAX_ARCHIVE_PATH_BYTES) {
                    "Invalid wheel path length"
                }
                val nameBytes = ByteArray(nameLength)
                input.readFully(nameBytes)
                val charset = if (flags and UTF8_FLAG != 0) StandardCharsets.UTF_8 else CP437
                val name = nameBytes.toString(charset)
                require(input.skipBytes(extraLength + entryCommentLength) ==
                    extraLength + entryCommentLength) { "Truncated central directory entry" }
                val path = validateArchivePath(name)
                val unixMode = if (madeByHost == UNIX_HOST) (external ushr 16).toInt() and 0xffff else 0
                val fileType = unixMode and FILE_TYPE_MASK
                require(fileType != SYMLINK_TYPE) { "Wheel symlinks are forbidden" }
                require(fileType == 0 || fileType == REGULAR_TYPE || fileType == DIRECTORY_TYPE) {
                    "Wheel contains a non-regular filesystem object"
                }
                entries += PythonWheelEntry(
                    path = path,
                    compressedSize = compressed,
                    uncompressedSize = uncompressed,
                    compressionMethod = method,
                    directory = name.endsWith('/'),
                    unixMode = unixMode,
                )
            }
            require(input.filePointer == centralOffset + centralSize) { "Central directory size mismatch" }
            return entries
        }
    }

    private fun validateArchivePath(raw: String): String {
        require(raw.toByteArray(StandardCharsets.UTF_8).size <=
            PythonEnvironmentLimits.MAX_ARCHIVE_PATH_BYTES) { "Wheel path is too long" }
        require(raw.isNotBlank() && !raw.startsWith('/') && !raw.startsWith('\\')) {
            "Wheel path is absolute"
        }
        require('\\' !in raw && '\u0000' !in raw && ':' !in raw) { "Wheel path is unsafe" }
        val path = raw.removeSuffix("/")
        require(path.isNotBlank()) { "Wheel root directory entry is forbidden" }
        require(path.split('/').all { it.isNotBlank() && it != "." && it != ".." }) {
            "Wheel path traversal is forbidden"
        }
        return path + if (raw.endsWith('/')) "/" else ""
    }

    private fun isImportName(value: String): Boolean =
        value.isNotBlank() && value.split('.').all { PYTHON_IDENTIFIER.matches(it) }

    private companion object {
        const val EOCD_SIGNATURE = 0x06054b50L
        const val CENTRAL_SIGNATURE = 0x02014b50L
        const val EOCD_MIN_BYTES = 22
        const val EOCD_MAX_SEARCH_BYTES = 65_557
        const val CENTRAL_FIXED_BYTES = 46
        const val UTF8_FLAG = 0x0800
        const val UNIX_HOST = 3
        const val FILE_TYPE_MASK = 0xf000
        const val REGULAR_TYPE = 0x8000
        const val DIRECTORY_TYPE = 0x4000
        const val SYMLINK_TYPE = 0xa000
        const val EXECUTABLE_BITS = 0x49
        const val UINT32_MAX = 0xffff_ffffL
        val CP437: Charset = Charset.forName("IBM437")
        val PYTHON_IDENTIFIER = Regex("[A-Za-z_][A-Za-z0-9_]*")
        val DANGEROUS_EXTENSIONS = setOf(
            ".so", ".dex", ".jar", ".apk", ".class", ".dylib", ".dll", ".exe",
        )
        val BUILD_BACKEND_FILES = setOf("setup.py", "setup.cfg", "pyproject.toml")

        fun uint16(bytes: ByteArray, offset: Int): Int =
            (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

        fun uint32(bytes: ByteArray, offset: Int): Long =
            uint16(bytes, offset).toLong() or (uint16(bytes, offset + 2).toLong() shl 16)
    }
}

internal object PythonRequiresPython {
    fun isSatisfied(runtimeVersion: String, specification: String): Boolean {
        val runtime = Version.parse(runtimeVersion)
        val clauses = specification.split(',').map(String::trim).filter(String::isNotEmpty)
        require(clauses.isNotEmpty()) { "Requires-Python is empty" }
        return clauses.all { clause ->
            val match = CLAUSE.matchEntire(clause) ?: error("Unsupported Requires-Python clause")
            val operator = match.groupValues[1]
            val raw = match.groupValues[2]
            val wildcard = raw.endsWith(".*")
            val expected = Version.parse(raw.removeSuffix(".*"))
            when (operator) {
                ">=" -> runtime >= expected
                ">" -> runtime > expected
                "<=" -> runtime <= expected
                "<" -> runtime < expected
                "==" -> if (wildcard) runtime.startsWith(expected) else runtime == expected
                "!=" -> if (wildcard) !runtime.startsWith(expected) else runtime != expected
                "~=" -> runtime >= expected && runtime < expected.compatibleUpperBound()
                else -> error("Unsupported Requires-Python operator")
            }
        }
    }

    private data class Version(val components: List<Int>) : Comparable<Version> {
        override fun compareTo(other: Version): Int {
            val size = maxOf(components.size, other.components.size, 3)
            repeat(size) { index ->
                val compared = components.getOrElse(index) { 0 }
                    .compareTo(other.components.getOrElse(index) { 0 })
                if (compared != 0) return compared
            }
            return 0
        }

        fun startsWith(prefix: Version): Boolean =
            prefix.components.indices.all { components.getOrElse(it) { 0 } == prefix.components[it] }

        fun compatibleUpperBound(): Version {
            val base = components.toMutableList()
            return if (base.size <= 2) {
                Version(listOf(base.first() + 1, 0))
            } else {
                Version(listOf(base[0], base[1] + 1, 0))
            }
        }

        companion object {
            fun parse(value: String): Version {
                require(VERSION.matches(value)) { "Unsupported Python version" }
                return Version(value.split('.').map(String::toInt))
            }
        }
    }

    private val CLAUSE = Regex("(>=|<=|==|!=|~=|>|<)\\s*([0-9]+(?:\\.[0-9]+){0,2}(?:\\.\\*)?)")
    private val VERSION = Regex("[0-9]+(?:\\.[0-9]+){0,2}")
}
