package ai.hans.standard.runtime.python

import java.io.ByteArrayInputStream
import java.nio.file.Files
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidPythonNativePackageCatalogTest {
    @Test
    fun exactCatalogVerifiesSignedNativeBytesAndRecomputesWholePayloadIdentity() {
        val fixture = fixture()
        val catalog = AndroidPythonNativePackageCatalog.load(fixture.source(), fixture.nativeRoot)
        val target = PythonEnvironmentTarget("3.14.7", "cp314", "arm64-v8a", 31)
        val pin = PythonNativeCatalogPin("msgpack", "1.2.1", CATALOG_ID, fixture.payload)

        val entry = requireNotNull(catalog.find(pin, target))
        assertEquals(SOURCE_SHA, entry.sourceSdistSha256)
        assertEquals(COMPANION_BYTES.toList(), entry.companionWheel.source.open().readBytes().toList())
        assertEquals(
            listOf(PythonAllowedNativeModule(MODULE, PACKAGED_NAME)),
            entry.allowedNativeModules(),
        )
        assertTrue(catalog.find(pin.copy(payloadSha256 = "f".repeat(64)), target) == null)
    }

    @Test
    fun catalogFailsClosedWhenNativeElfOrPayloadDescriptorChanges() {
        val elfDrift = fixture()
        elfDrift.library.writeBytes("changed".toByteArray())
        assertTrue(
            runCatching {
                AndroidPythonNativePackageCatalog.load(elfDrift.source(), elfDrift.nativeRoot)
            }.isFailure,
        )

        val payloadDrift = fixture(payloadOverride = "e".repeat(64))
        assertTrue(
            runCatching {
                AndroidPythonNativePackageCatalog.load(payloadDrift.source(), payloadDrift.nativeRoot)
            }.isFailure,
        )
    }

    private fun fixture(payloadOverride: String? = null): Fixture {
        val nativeRoot = Files.createTempDirectory("hans-native-catalog").toFile()
        val library = nativeRoot.resolve(PACKAGED_NAME).apply { writeBytes(ELF_BYTES) }
        val libraryPin = PythonNativeLibraryPin(
            moduleName = MODULE,
            packagedName = PACKAGED_NAME,
            sha256 = PythonEnvironmentContract.sha256(ELF_BYTES),
            sizeBytes = ELF_BYTES.size.toLong(),
        )
        val companion = PythonNativeCompanionWheel(
            assetPath = COMPANION_ASSET,
            fileName = "msgpack-1.2.1-py3-none-any.whl",
            sha256 = PythonEnvironmentContract.sha256(COMPANION_BYTES),
            sizeBytes = COMPANION_BYTES.size.toLong(),
            source = PythonSignedAssetSource { ByteArrayInputStream(COMPANION_BYTES) },
        )
        val identity = PythonNativeCatalogEntry(
            catalogId = CATALOG_ID,
            packageName = "msgpack",
            version = "1.2.1",
            payloadSha256 = "0".repeat(64),
            sourceSdistSha256 = SOURCE_SHA,
            supportedTargets = setOf("cp314-android_arm64_v8a"),
            importNames = setOf("msgpack", MODULE),
            requiresPython = ">=3.10",
            companionWheel = companion,
            nativeLibraries = listOf(libraryPin),
        )
        val payload = AndroidPythonNativePackageCatalog.payloadDigest(identity)
        val catalog = JSONObject()
            .put("schemaVersion", 1)
            .put("pythonVersion", "3.14.7")
            .put(
                "packages",
                JSONArray().put(
                    JSONObject()
                        .put("catalogId", CATALOG_ID)
                        .put("packageName", "msgpack")
                        .put("version", "1.2.1")
                        .put("payloadSha256", payloadOverride ?: payload)
                        .put("sourceSdistSha256", SOURCE_SHA)
                        .put("supportedTargets", JSONArray().put("cp314-android_arm64_v8a"))
                        .put("importNames", JSONArray().put("msgpack").put(MODULE))
                        .put("requiresPython", ">=3.10")
                        .put("requiresDist", JSONArray())
                        .put(
                            "companionWheel",
                            JSONObject()
                                .put("assetPath", COMPANION_ASSET)
                                .put("fileName", companion.fileName)
                                .put("sha256", companion.sha256)
                                .put("sizeBytes", companion.sizeBytes),
                        )
                        .put(
                            "nativeLibraries",
                            JSONArray().put(
                                JSONObject()
                                    .put("moduleName", MODULE)
                                    .put("packagedName", PACKAGED_NAME)
                                    .put("sha256", libraryPin.sha256)
                                    .put("sizeBytes", libraryPin.sizeBytes),
                            ),
                        ),
                ),
            )
            .toString()
            .toByteArray()
        return Fixture(nativeRoot, library, catalog, payload)
    }

    private data class Fixture(
        val nativeRoot: java.io.File,
        val library: java.io.File,
        val catalog: ByteArray,
        val payload: String,
    ) {
        fun source() = PythonNativeCatalogAssetSource { path ->
            ByteArrayInputStream(
                when (path) {
                    AndroidPythonNativePackageCatalog.CATALOG_ASSET -> catalog
                    COMPANION_ASSET -> COMPANION_BYTES
                    else -> error("Unexpected asset: $path")
                },
            )
        }
    }

    private companion object {
        const val CATALOG_ID = "msgpack-1.2.1-cp314-android31-arm64-v8a"
        const val SOURCE_SHA = "04c721c2c7448767e9e3f2520a475663d8ee0f09c31890f6d2bd70fd636a9647"
        const val MODULE = "msgpack._cmsgpack"
        const val PACKAGED_NAME = "libhans_py_msgpack___cmsgpack.so"
        const val COMPANION_ASSET =
            "hans/python/native-packages/msgpack-1.2.1-py3-none-any.whl"
        val ELF_BYTES = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        val COMPANION_BYTES = "signed companion".toByteArray()
    }
}
