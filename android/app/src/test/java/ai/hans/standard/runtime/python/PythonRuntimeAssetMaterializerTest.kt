package ai.hans.standard.runtime.python

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PythonRuntimeAssetMaterializerTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun signedStdlibIsVerifiedMaterializedAndNeverMadeExecutable() {
        val bytes = "signed pure Python zip".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        val manifest = JSONObject()
            .put("schemaVersion", 1)
            .put("pythonVersion", "3.14.2")
            .put("abi", "arm64-v8a")
            .put("stdlibAsset", "hans/python/python314.zip")
            .put("stdlibSha256", digest)
            .put("stdlibBytes", bytes.size)
            .toString()
            .toByteArray()
        val assets = mapOf(
            "hans/python/runtime-manifest.json" to manifest,
            "hans/python/python314.zip" to bytes,
        )
        val materializer = PythonRuntimeAssetMaterializer(
            temporaryFolder.root,
            PythonRuntimeAssetSource { path -> ByteArrayInputStream(assets.getValue(path)) },
        )

        val receipt = materializer.materialize()

        assertEquals(digest, receipt.stdlibSha256)
        assertArrayEquals(bytes, receipt.stdlibZip.readBytes())
        assertFalse(receipt.stdlibZip.canExecute())
        assertEquals(receipt.stdlibZip, materializer.materialize().stdlibZip)
    }
}
