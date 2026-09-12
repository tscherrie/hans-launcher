package ai.hans.standard.runtime

import android.content.res.AssetManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import org.json.JSONObject

internal object BundledSetupPluginContract {
    const val MARKETPLACE_NAME = "hans-bundled"
    const val MARKETPLACE_DISPLAY_NAME = "Hans"
    const val PLUGIN_NAME = "hans-setup"
    const val SKILL_NAME = "setup-hans-device"
    // App Server qualifies plugin skills with the owning plugin name. Keep the
    // on-disk/frontmatter name separate because both values are part of their
    // respective public contracts.
    const val QUALIFIED_SKILL_NAME = "hans-setup:setup-hans-device"
    const val MARKETPLACE_DIRECTORY_NAME = "hans-bundled-marketplace"

    const val MANIFEST_ASSET = "hans/bundled-plugins/hans-setup/manifest.json"
    const val SKILL_ASSET =
        "hans/bundled-plugins/hans-setup/skills/setup-hans-device/SKILL.md"

    const val MARKETPLACE_MANIFEST_RELATIVE_PATH = ".agents/plugins/marketplace.json"
    const val PLUGIN_MANIFEST_RELATIVE_PATH =
        "plugins/hans-setup/.codex-plugin/plugin.json"
    const val SKILL_RELATIVE_PATH =
        "plugins/hans-setup/skills/setup-hans-device/SKILL.md"
    const val RECEIPT_RELATIVE_PATH = ".hans-bootstrap-receipt.json"

    fun marketplaceRootForWorkspace(workspacePath: String): File {
        require(workspacePath.startsWith('/')) { "Workspace path must be absolute" }
        val workspace = File(workspacePath)
        val parent = requireNotNull(workspace.parentFile) { "Workspace has no parent" }
        return File(parent, MARKETPLACE_DIRECTORY_NAME).absoluteFile
    }

    fun marketplaceRootForFilesDirectory(filesDirectory: File): File {
        require(filesDirectory.isAbsolute) { "filesDir must be absolute" }
        return File(filesDirectory, MARKETPLACE_DIRECTORY_NAME).absoluteFile
    }
}

internal fun interface BundledPluginAssetSource {
    fun read(assetPath: String): ByteArray
}

internal class AndroidBundledPluginAssetSource(
    private val assets: AssetManager,
) : BundledPluginAssetSource {
    override fun read(assetPath: String): ByteArray = assets.open(assetPath).use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            check(output.size() <= MAX_ASSET_BYTES - count) {
                "Bundled plugin asset is too large"
            }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    private companion object {
        const val MAX_ASSET_BYTES = 512 * 1024
    }
}

internal data class CodexRuntimeBootstrapReceipt(
    val schemaVersion: Int,
    val pluginVersion: String,
    val contentSha256: String,
    val marketplaceRoot: String,
    val reusedExisting: Boolean,
)

/**
 * Materializes only Hans-owned, non-executable plugin data below one dedicated
 * app-private root. The Codex home and all account/session files are outside
 * that root and are never read, rewritten, or removed here.
 */
internal class CodexRuntimeBootstrapMaterializer(
    marketplaceRoot: File,
    private val assets: BundledPluginAssetSource,
) {
    private val root = marketplaceRoot.absoluteFile

    init {
        require(root.isAbsolute) { "Bundled marketplace root must be absolute" }
        require(root.name == BundledSetupPluginContract.MARKETPLACE_DIRECTORY_NAME) {
            "Bundled marketplace root has an unexpected name"
        }
    }

    @Synchronized
    fun materialize(): CodexRuntimeBootstrapReceipt {
        val manifest = readAsset(BundledSetupPluginContract.MANIFEST_ASSET)
        val skill = readAsset(BundledSetupPluginContract.SKILL_ASSET)
        val manifestJson = JSONObject(manifest.toString(StandardCharsets.UTF_8))
        check(manifestJson.getString("name") == BundledSetupPluginContract.PLUGIN_NAME) {
            "Bundled plugin manifest name does not match"
        }
        val pluginVersion = manifestJson.getString("version").also(::requireSafeVersion)
        val marketplace = marketplaceJson().toByteArray(StandardCharsets.UTF_8)
        val managed = linkedMapOf(
            BundledSetupPluginContract.MARKETPLACE_MANIFEST_RELATIVE_PATH to marketplace,
            BundledSetupPluginContract.PLUGIN_MANIFEST_RELATIVE_PATH to manifest,
            BundledSetupPluginContract.SKILL_RELATIVE_PATH to skill,
        )
        val digest = contentDigest(managed)
        val canonicalRoot = canonicalRootPath()
        val expected = CodexRuntimeBootstrapReceipt(
            schemaVersion = RECEIPT_SCHEMA_VERSION,
            pluginVersion = pluginVersion,
            contentSha256 = digest,
            marketplaceRoot = canonicalRoot,
            reusedExisting = false,
        )
        if (matchesExisting(expected, managed)) {
            return expected.copy(reusedExisting = true)
        }

        val parent = requireNotNull(root.parentFile).also(::ensurePrivateDirectory)
        check(!Files.isSymbolicLink(parent.toPath())) {
            "Bundled marketplace parent must not be a symbolic link"
        }
        val staging = File(parent, ".${root.name}.staging")
        val backup = File(parent, ".${root.name}.backup")
        deleteManagedTree(staging)
        deleteManagedTree(backup)
        check(staging.mkdir() && staging.isDirectory) {
            "Could not create bundled marketplace staging directory"
        }
        try {
            managed.forEach { (relativePath, bytes) ->
                writePrivateDataFile(staging, relativePath, bytes)
            }
            writePrivateDataFile(
                staging,
                BundledSetupPluginContract.RECEIPT_RELATIVE_PATH,
                receiptJson(expected).toByteArray(StandardCharsets.UTF_8),
            )
            verifyTree(staging, expected, managed)

            if (root.exists()) {
                check(!Files.isSymbolicLink(root.toPath())) {
                    "Bundled marketplace root must not be a symbolic link"
                }
                check(root.renameTo(backup)) {
                    "Could not preserve previous bundled marketplace"
                }
            }
            if (!staging.renameTo(root)) {
                if (backup.exists()) check(backup.renameTo(root)) {
                    "Could not restore previous bundled marketplace"
                }
                error("Could not activate bundled marketplace")
            }
            deleteManagedTree(backup)
            verifyTree(root, expected, managed)
            return expected
        } finally {
            deleteManagedTree(staging)
        }
    }

    private fun readAsset(path: String): ByteArray {
        check(path in ALLOWED_ASSETS) { "Unexpected bundled plugin asset" }
        return assets.read(path).also { bytes ->
            check(bytes.isNotEmpty()) { "Bundled plugin asset is empty" }
            check(bytes.size <= MAX_ASSET_BYTES) { "Bundled plugin asset is too large" }
            check(bytes.none { it == 0.toByte() }) { "Bundled plugin asset contains NUL" }
        }
    }

    private fun matchesExisting(
        expected: CodexRuntimeBootstrapReceipt,
        managed: Map<String, ByteArray>,
    ): Boolean = runCatching {
        verifyTree(root, expected, managed)
        true
    }.getOrDefault(false)

    private fun verifyTree(
        candidateRoot: File,
        expected: CodexRuntimeBootstrapReceipt,
        managed: Map<String, ByteArray>,
    ) {
        check(candidateRoot.isDirectory && !Files.isSymbolicLink(candidateRoot.toPath())) {
            "Bundled marketplace root is not a regular directory"
        }
        managed.forEach { (relativePath, expectedBytes) ->
            val file = containedFile(candidateRoot, relativePath)
            check(file.isFile && !Files.isSymbolicLink(file.toPath())) {
                "Bundled marketplace file is missing"
            }
            check(!Files.isExecutable(file.toPath())) {
                "Bundled plugin data must not be executable"
            }
            check(file.length() == expectedBytes.size.toLong()) {
                "Bundled marketplace file length does not match"
            }
            check(MessageDigest.isEqual(expectedBytes, file.readBytes())) {
                "Bundled marketplace file digest does not match"
            }
        }
        val receiptFile = containedFile(
            candidateRoot,
            BundledSetupPluginContract.RECEIPT_RELATIVE_PATH,
        )
        check(receiptFile.isFile && !Files.isSymbolicLink(receiptFile.toPath())) {
            "Bundled marketplace receipt is missing"
        }
        check(receiptFile.length() in 1..MAX_RECEIPT_BYTES.toLong()) {
            "Bundled marketplace receipt length is invalid"
        }
        val parsed = parseReceipt(receiptFile.readText(StandardCharsets.UTF_8))
        check(parsed == expected.copy(reusedExisting = false)) {
            "Bundled marketplace receipt does not match"
        }
    }

    private fun canonicalRootPath(): String {
        val parent = requireNotNull(root.parentFile)
        val canonicalParent = parent.canonicalFile
        return File(canonicalParent, root.name).absolutePath
    }

    private fun writePrivateDataFile(rootDirectory: File, relativePath: String, bytes: ByteArray) {
        val destination = containedFile(rootDirectory, relativePath)
        ensurePrivateDirectory(requireNotNull(destination.parentFile))
        FileOutputStream(destination).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
        check(destination.setReadable(true, true)) { "Could not make plugin data readable" }
        check(destination.setWritable(true, true)) { "Could not make plugin data writable" }
        check(destination.setExecutable(false, false)) { "Could not clear executable mode" }
        check(!Files.isExecutable(destination.toPath())) { "Plugin data remained executable" }
    }

    private fun containedFile(rootDirectory: File, relativePath: String): File {
        require(relativePath.isNotBlank() && !relativePath.startsWith('/')) {
            "Bundled plugin path must be relative"
        }
        val segments = relativePath.split('/')
        require(segments.all { it.isNotBlank() && it != "." && it != ".." }) {
            "Bundled plugin path contains traversal"
        }
        val canonicalRoot = rootDirectory.canonicalFile.toPath()
        val candidate = File(rootDirectory, relativePath).canonicalFile
        check(candidate.toPath().startsWith(canonicalRoot)) {
            "Bundled plugin path escapes its root"
        }
        return candidate
    }

    private fun ensurePrivateDirectory(directory: File) {
        check((directory.isDirectory || directory.mkdirs()) && directory.isDirectory) {
            "Could not create private bundled marketplace directory"
        }
    }

    private fun deleteManagedTree(directory: File) {
        if (!directory.exists() && !Files.isSymbolicLink(directory.toPath())) return
        Files.walkFileTree(
            directory.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    Files.deleteIfExists(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(
                    dir: Path,
                    exc: java.io.IOException?,
                ): FileVisitResult {
                    if (exc != null) throw exc
                    Files.deleteIfExists(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }

    private fun marketplaceJson(): String = JSONObject()
        .put("name", BundledSetupPluginContract.MARKETPLACE_NAME)
        .put(
            "interface",
            JSONObject().put(
                "displayName",
                BundledSetupPluginContract.MARKETPLACE_DISPLAY_NAME,
            ),
        )
        .put(
            "plugins",
            org.json.JSONArray().put(
                JSONObject()
                    .put("name", BundledSetupPluginContract.PLUGIN_NAME)
                    .put(
                        "source",
                        JSONObject()
                            .put("source", "local")
                            .put("path", "./plugins/${BundledSetupPluginContract.PLUGIN_NAME}"),
                    )
                    .put(
                        "policy",
                        JSONObject()
                            .put("installation", "AVAILABLE")
                            .put("authentication", "ON_INSTALL"),
                    )
                    .put("category", "Productivity"),
            ),
        )
        .toString(2) + "\n"

    private fun receiptJson(receipt: CodexRuntimeBootstrapReceipt): String = JSONObject()
        .put("schemaVersion", receipt.schemaVersion)
        .put("pluginVersion", receipt.pluginVersion)
        .put("contentSha256", receipt.contentSha256)
        .put("marketplaceRoot", receipt.marketplaceRoot)
        .toString(2) + "\n"

    private fun parseReceipt(raw: String): CodexRuntimeBootstrapReceipt {
        val json = JSONObject(raw)
        check(
            json.keys().asSequence().toSet() == setOf(
                "schemaVersion",
                "pluginVersion",
                "contentSha256",
                "marketplaceRoot",
            ),
        ) { "Bundled marketplace receipt shape is invalid" }
        return CodexRuntimeBootstrapReceipt(
            schemaVersion = json.getInt("schemaVersion"),
            pluginVersion = json.getString("pluginVersion").also(::requireSafeVersion),
            contentSha256 = json.getString("contentSha256").also(::requireSha256),
            marketplaceRoot = json.getString("marketplaceRoot").also {
                require(it.startsWith('/')) { "Receipt marketplace root must be absolute" }
            },
            reusedExisting = false,
        )
    }

    private fun contentDigest(files: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        files.forEach { (path, bytes) ->
            digest.update(path.toByteArray(StandardCharsets.UTF_8))
            digest.update(0.toByte())
            digest.update(MessageDigest.getInstance("SHA-256").digest(bytes))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun requireSafeVersion(value: String) {
        require(value.matches(Regex("^[0-9A-Za-z][0-9A-Za-z.+_-]{0,127}$"))) {
            "Bundled plugin version is invalid"
        }
    }

    private fun requireSha256(value: String) {
        require(value.length == 64 && value.all { it in "0123456789abcdef" }) {
            "Bundled plugin digest is invalid"
        }
    }

    private companion object {
        const val RECEIPT_SCHEMA_VERSION = 1
        const val MAX_ASSET_BYTES = 512 * 1024
        const val MAX_RECEIPT_BYTES = 16 * 1024
        val ALLOWED_ASSETS = setOf(
            BundledSetupPluginContract.MANIFEST_ASSET,
            BundledSetupPluginContract.SKILL_ASSET,
        )
    }
}
