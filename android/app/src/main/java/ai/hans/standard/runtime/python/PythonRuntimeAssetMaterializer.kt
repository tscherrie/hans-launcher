package ai.hans.standard.runtime.python

import ai.hans.standard.codex.JsonContract
import android.content.res.AssetManager
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

internal data class PythonRuntimeAssetReceipt(
    val pythonVersion: String,
    val abi: String,
    val stdlibZip: File,
    val stdlibSha256: String,
    val stdlibBytes: Long,
)

internal fun interface PythonRuntimeAssetSource {
    fun open(path: String): InputStream
}

internal class AndroidPythonRuntimeAssetSource(
    private val assets: AssetManager,
) : PythonRuntimeAssetSource {
    override fun open(path: String): InputStream = assets.open(path, AssetManager.ACCESS_STREAMING)
}

/** Atomically materializes only the signed pure-Python stdlib; native code stays in the APK. */
internal class PythonRuntimeAssetMaterializer(
    private val noBackupRoot: File,
    private val source: PythonRuntimeAssetSource,
) {
    fun materialize(): PythonRuntimeAssetReceipt {
        val manifestRaw = source.open(MANIFEST_ASSET).bufferedReader(Charsets.UTF_8).use {
            it.readTextLimited(PythonRuntimeContract.MAX_STATE_BYTES)
        }
        val manifest = JsonContract.parseObject(manifestRaw, PythonRuntimeContract.MAX_STATE_BYTES)
        require(JsonContract.requiredLong(manifest, "schemaVersion") == MANIFEST_SCHEMA_VERSION.toLong())
        val pythonVersion = JsonContract.requiredString(manifest, "pythonVersion", 64)
        require(pythonVersion.startsWith("${PythonRuntimeContract.REQUIRED_PYTHON_SERIES}."))
        val abi = JsonContract.requiredString(manifest, "abi", 64)
        require(abi == "arm64-v8a" || abi == "x86_64")
        val assetPath = JsonContract.requiredString(manifest, "stdlibAsset", 256)
        require(assetPath == STDLIB_ASSET) { "Unexpected Python stdlib asset path" }
        val digest = JsonContract.requiredString(manifest, "stdlibSha256", 64)
        require(SHA_256.matches(digest)) { "Invalid Python stdlib digest" }
        val expectedBytes = JsonContract.requiredLong(manifest, "stdlibBytes")
        require(expectedBytes in 1..MAX_STDLIB_BYTES) { "Invalid Python stdlib size" }

        val directory = File(noBackupRoot, "python/runtime/$digest").canonicalFile
        require(directory.toPath().startsWith(noBackupRoot.canonicalFile.toPath()))
        check(directory.mkdirs() || directory.isDirectory) { "Cannot create Python runtime directory" }
        val destination = File(directory, "python314.zip")
        if (!matches(destination, expectedBytes, digest)) {
            val staging = File(directory, ".python314.zip.${System.nanoTime()}.tmp")
            try {
                source.open(assetPath).use { input ->
                    FileOutputStream(staging).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var copied = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            copied += count
                            check(copied <= expectedBytes) { "Python stdlib exceeds its pinned size" }
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                        check(copied == expectedBytes) { "Python stdlib size does not match" }
                    }
                }
                check(matches(staging, expectedBytes, digest)) { "Python stdlib digest does not match" }
                if (destination.exists()) check(destination.delete()) { "Cannot replace Python stdlib" }
                check(staging.renameTo(destination)) { "Cannot activate Python stdlib" }
                destination.setReadable(true, true)
                destination.setWritable(true, true)
                destination.setExecutable(false, false)
            } finally {
                staging.delete()
            }
        }
        return PythonRuntimeAssetReceipt(
            pythonVersion = pythonVersion,
            abi = abi,
            stdlibZip = destination.canonicalFile,
            stdlibSha256 = digest,
            stdlibBytes = expectedBytes,
        )
    }

    private fun matches(file: File, expectedBytes: Long, expectedDigest: String): Boolean {
        if (!file.isFile || file.length() != expectedBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == expectedDigest
    }

    private fun java.io.Reader.readTextLimited(maximumCharacters: Int): String {
        val output = StringBuilder(minOf(maximumCharacters, 8 * 1024))
        val buffer = CharArray(4 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            check(output.length + count <= maximumCharacters) { "Python runtime manifest is oversized" }
            output.append(buffer, 0, count)
        }
        return output.toString()
    }

    private companion object {
        const val MANIFEST_SCHEMA_VERSION = 1
        const val MANIFEST_ASSET = "hans/python/runtime-manifest.json"
        const val STDLIB_ASSET = "hans/python/python314.zip"
        const val MAX_STDLIB_BYTES = 128L * 1024L * 1024L
        val SHA_256 = Regex("[a-f0-9]{64}")
    }
}
