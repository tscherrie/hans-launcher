package ai.hans.standard.runtime

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CodexRuntimeBootstrapMaterializerTest {
    @Test
    fun materializesExactMarketplaceAtomicallyAndReusesVerifiedReceipt() {
        val parent = Files.createTempDirectory("hans-bootstrap").toFile()
        val root = File(parent, BundledSetupPluginContract.MARKETPLACE_DIRECTORY_NAME)
        val unrelated = File(parent, "codex-account.json").apply { writeText("keep-me") }
        val source = FakeAssets()
        val materializer = CodexRuntimeBootstrapMaterializer(root, source)

        val first = materializer.materialize()
        assertFalse(first.reusedExisting)
        assertEquals("0.1.0+codex.test", first.pluginVersion)
        assertEquals(root.canonicalPath, first.marketplaceRoot)
        assertEquals("keep-me", unrelated.readText())
        assertEquals(2, source.readCount)

        val marketplace = JSONObject(
            File(root, BundledSetupPluginContract.MARKETPLACE_MANIFEST_RELATIVE_PATH)
                .readText(),
        )
        assertEquals(BundledSetupPluginContract.MARKETPLACE_NAME, marketplace.getString("name"))
        val entry = marketplace.getJSONArray("plugins").getJSONObject(0)
        assertEquals(BundledSetupPluginContract.PLUGIN_NAME, entry.getString("name"))
        assertEquals(
            "./plugins/${BundledSetupPluginContract.PLUGIN_NAME}",
            entry.getJSONObject("source").getString("path"),
        )
        managedFiles(root).forEach { file ->
            assertTrue(file.isFile)
            assertFalse(Files.isExecutable(file.toPath()))
        }

        val second = materializer.materialize()
        assertTrue(second.reusedExisting)
        assertEquals(first.contentSha256, second.contentSha256)
        assertEquals(4, source.readCount)
        assertEquals("keep-me", unrelated.readText())
    }

    @Test
    fun repairsTamperedManagedDataWithoutTouchingCodexHomeSiblings() {
        val parent = Files.createTempDirectory("hans-bootstrap-repair").toFile()
        val root = File(parent, BundledSetupPluginContract.MARKETPLACE_DIRECTORY_NAME)
        val codexHome = File(parent, "codex-home").apply { mkdirs() }
        val login = File(codexHome, "auth.json").apply { writeText("private-login") }
        val materializer = CodexRuntimeBootstrapMaterializer(root, FakeAssets())
        materializer.materialize()

        val skill = File(root, BundledSetupPluginContract.SKILL_RELATIVE_PATH)
        skill.writeText("tampered")
        val repaired = materializer.materialize()

        assertFalse(repaired.reusedExisting)
        assertEquals(FakeAssets.SKILL, skill.readText(StandardCharsets.UTF_8))
        assertEquals("private-login", login.readText())
        assertFalse(File(parent, ".${root.name}.staging").exists())
        assertFalse(File(parent, ".${root.name}.backup").exists())
    }

    @Test
    fun upgradesBundledSkillAndVersionWithoutChangingAccountData() {
        val parent = Files.createTempDirectory("hans-bootstrap-upgrade").toFile()
        try {
            val root = File(parent, BundledSetupPluginContract.MARKETPLACE_DIRECTORY_NAME)
            val codexHome = File(parent, "codex-home").apply { mkdirs() }
            val login = File(codexHome, "auth.json").apply { writeText("synthetic-login-preserved") }
            val previous = CodexRuntimeBootstrapMaterializer(
                root,
                FakeAssets(version = "0.2.6+codex.20260826"),
            ).materialize()
            val nextVersion = "0.2.7+codex.20260828"
            val nextSkill = FakeAssets.SKILL + "\nUpdated setup policy instructions.\n"
            val updatedMaterializer = CodexRuntimeBootstrapMaterializer(
                root,
                FakeAssets(version = nextVersion, skillText = nextSkill),
            )

            val updated = updatedMaterializer.materialize()

            assertFalse(updated.reusedExisting)
            assertEquals(nextVersion, updated.pluginVersion)
            assertTrue(previous.contentSha256 != updated.contentSha256)
            assertEquals(nextSkill, File(root, BundledSetupPluginContract.SKILL_RELATIVE_PATH).readText())
            assertEquals(
                nextVersion,
                JSONObject(File(root, BundledSetupPluginContract.PLUGIN_MANIFEST_RELATIVE_PATH).readText())
                    .getString("version"),
            )
            assertEquals("synthetic-login-preserved", login.readText())
            assertFalse(File(parent, ".${root.name}.staging").exists())
            assertFalse(File(parent, ".${root.name}.backup").exists())
            assertTrue(updatedMaterializer.materialize().reusedExisting)
        } finally {
            parent.deleteRecursively()
        }
    }

    @Test
    fun refusesSymlinkedManagedRootAndPreservesItsTarget() {
        val parent = Files.createTempDirectory("hans-bootstrap-link").toFile()
        val outside = Files.createTempDirectory("hans-bootstrap-outside").toFile()
        val marker = File(outside, "marker").apply { writeText("outside") }
        val root = File(parent, BundledSetupPluginContract.MARKETPLACE_DIRECTORY_NAME)
        Files.createSymbolicLink(root.toPath(), outside.toPath())

        assertThrows(IllegalStateException::class.java) {
            CodexRuntimeBootstrapMaterializer(root, FakeAssets()).materialize()
        }
        assertEquals("outside", marker.readText())
        assertTrue(Files.isSymbolicLink(root.toPath()))
    }

    @Test
    fun rejectsUnexpectedBinaryAssetContentBeforeWritingAnything() {
        val parent = Files.createTempDirectory("hans-bootstrap-nul").toFile()
        val root = File(parent, BundledSetupPluginContract.MARKETPLACE_DIRECTORY_NAME)
        val source = BundledPluginAssetSource { path ->
            if (path == BundledSetupPluginContract.MANIFEST_ASSET) {
                FakeAssets.MANIFEST.toByteArray()
            } else {
                byteArrayOf('x'.code.toByte(), 0, 'y'.code.toByte())
            }
        }

        assertThrows(IllegalStateException::class.java) {
            CodexRuntimeBootstrapMaterializer(root, source).materialize()
        }
        assertFalse(root.exists())
    }

    private fun managedFiles(root: File): List<File> = listOf(
        File(root, BundledSetupPluginContract.MARKETPLACE_MANIFEST_RELATIVE_PATH),
        File(root, BundledSetupPluginContract.PLUGIN_MANIFEST_RELATIVE_PATH),
        File(root, BundledSetupPluginContract.SKILL_RELATIVE_PATH),
        File(root, BundledSetupPluginContract.RECEIPT_RELATIVE_PATH),
    )

    private class FakeAssets(
        private val version: String = "0.1.0+codex.test",
        private val skillText: String = SKILL,
    ) : BundledPluginAssetSource {
        var readCount: Int = 0

        override fun read(assetPath: String): ByteArray {
            readCount += 1
            return when (assetPath) {
                BundledSetupPluginContract.MANIFEST_ASSET -> JSONObject(MANIFEST).put("version", version)
                    .toString().toByteArray()
                BundledSetupPluginContract.SKILL_ASSET -> skillText.toByteArray()
                else -> error("unexpected asset")
            }
        }

        companion object {
            const val MANIFEST =
                "{\"name\":\"hans-setup\",\"version\":\"0.1.0+codex.test\"}"
            const val SKILL = "---\nname: setup-hans-device\ndescription: Test\n---\n"
        }
    }
}
