package ai.hans.standard.runtime.python.resolver

import ai.hans.standard.runtime.python.PythonEnvironmentContract
import ai.hans.standard.runtime.python.PythonEnvironmentResolutionRequest
import ai.hans.standard.runtime.python.PythonEnvironmentTarget
import ai.hans.standard.runtime.python.PythonNativeCatalogEntry
import ai.hans.standard.runtime.python.PythonNativeCompanionWheel
import ai.hans.standard.runtime.python.PythonNativeLibraryPin
import ai.hans.standard.runtime.python.PythonNativePackageCatalog
import ai.hans.standard.runtime.python.PythonSignedAssetSource
import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignedNativeFirstPythonPackageCatalogTest {
    @Test
    fun signedNativeCandidateParticipatesInResolutionButProducesNoDownloadableWheel() {
        var fallbackCalled = false
        val catalog = SignedNativeFirstPythonPackageCatalog(
            nativeCatalog = nativeCatalog(),
            purePythonFallback = PythonPackageCatalog { name, _, _ ->
                fallbackCalled = true
                PythonResolverProject(name, emptyList())
            },
        )
        val worker = PythonResolverWorker { _, projects, _ ->
            if ("msgpack" !in projects) {
                PythonResolverWorkerOutcome.NeedsProjects(setOf("msgpack"))
            } else {
                PythonResolverWorkerOutcome.Resolved(
                    listOf(
                        PythonResolverSelection(
                            normalizedName = "msgpack",
                            version = "1.2.1",
                            fileName = "msgpack-1.2.1-py3-none-any.whl",
                        ),
                    ),
                )
            }
        }

        val lock = PythonResolverEngine(catalog, worker).resolve(
            PythonEnvironmentResolutionRequest("plugin", listOf("msgpack==1.2.1"), TARGET),
        )

        assertFalse(fallbackCalled)
        assertTrue(lock.wheels.isEmpty())
        assertEquals(1, lock.nativePackages.size)
        assertEquals(PAYLOAD, lock.nativePackages.single().payloadSha256)
        assertEquals("msgpack", lock.nativePackages.single().normalizedName)
    }

    @Test
    fun packageAbsentFromClosedNativeCatalogUsesPurePythonFallback() {
        val fallback = PythonPackageCatalog { name, _, _ ->
            PythonResolverProject(
                name,
                listOf(
                    PythonResolverCandidate(
                        normalizedName = name,
                        version = "2.0",
                        fileName = "$name-2.0-py3-none-any.whl",
                        sourceUri = "https://files.pythonhosted.org/packages/$name.whl",
                        sha256 = PythonEnvironmentContract.sha256(name),
                        sizeBytes = 99,
                        requiresPython = ">=3.14",
                        yanked = false,
                        requiresDist = emptyList(),
                    ),
                ),
            )
        }

        val project = SignedNativeFirstPythonPackageCatalog(nativeCatalog(), fallback).project(
            "packaging",
            TARGET,
            PythonResolutionCancellation.NONE,
        )

        assertEquals("https://files.pythonhosted.org/packages/packaging.whl", project.candidates.single().sourceUri)
        assertTrue(project.candidates.single().nativePin == null)
    }

    private fun nativeCatalog(): PythonNativePackageCatalog = object : PythonNativePackageCatalog {
        override fun find(
            pin: ai.hans.standard.runtime.python.PythonNativeCatalogPin,
            target: PythonEnvironmentTarget,
        ): PythonNativeCatalogEntry? = ENTRY.takeIf {
            pin.catalogId == it.catalogId && pin.payloadSha256 == it.payloadSha256 && target == TARGET
        }

        override fun candidates(
            normalizedName: String,
            target: PythonEnvironmentTarget,
        ): List<PythonNativeCatalogEntry> = if (normalizedName == "msgpack" && target == TARGET) {
            listOf(ENTRY)
        } else {
            emptyList()
        }
    }

    private companion object {
        val TARGET = PythonEnvironmentTarget("3.14.7", "cp314", "arm64-v8a", 31)
        const val PAYLOAD = "05f9c31ae33dd23421ee8a2a96a13e1bf219b52fc2df1af29975cb82a2dd5e8e"
        val ENTRY = PythonNativeCatalogEntry(
            catalogId = "msgpack-1.2.1-cp314-android31-arm64-v8a",
            packageName = "msgpack",
            version = "1.2.1",
            payloadSha256 = PAYLOAD,
            sourceSdistSha256 =
                "04c721c2c7448767e9e3f2520a475663d8ee0f09c31890f6d2bd70fd636a9647",
            supportedTargets = setOf(TARGET.directorySegment),
            importNames = setOf("msgpack", "msgpack._cmsgpack"),
            requiresPython = ">=3.10",
            companionWheel = PythonNativeCompanionWheel(
                assetPath = "hans/python/native-packages/msgpack-1.2.1-py3-none-any.whl",
                fileName = "msgpack-1.2.1-py3-none-any.whl",
                sha256 = "d4d73313577410bc14c09a59af5821dd973f2f011f9a90b633147d52d1e8b26e",
                sizeBytes = 43_486,
                source = PythonSignedAssetSource { ByteArrayInputStream(byteArrayOf(1)) },
            ),
            nativeLibraries = listOf(
                PythonNativeLibraryPin(
                    moduleName = "msgpack._cmsgpack",
                    packagedName = "libhans_py_msgpack___cmsgpack.so",
                    sha256 = "c2be049193ed2345b44dc195b0372054539b8b4ce5cef16c8ec808dbd7de9490",
                    sizeBytes = 161_760,
                ),
            ),
        )
    }
}
