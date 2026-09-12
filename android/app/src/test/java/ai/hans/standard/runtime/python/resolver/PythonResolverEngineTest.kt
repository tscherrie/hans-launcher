package ai.hans.standard.runtime.python.resolver

import ai.hans.standard.runtime.python.PythonEnvironmentResolutionRequest
import ai.hans.standard.runtime.python.PythonEnvironmentTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PythonResolverEngineTest {
    @Test
    fun iteratesMissingProjectsAndBuildsDeterministicExactLock() {
        val fetched = mutableListOf<String>()
        val catalog = PythonPackageCatalog { name, _, _ ->
            fetched += name
            PythonResolverProject(name, listOf(candidate(name, "1.0")))
        }
        val worker = PythonResolverWorker { _, projects, _ ->
            when {
                "root" !in projects -> PythonResolverWorkerOutcome.NeedsProjects(setOf("root"))
                "child" !in projects -> PythonResolverWorkerOutcome.NeedsProjects(setOf("child"))
                else -> PythonResolverWorkerOutcome.Resolved(
                    listOf(
                        PythonResolverSelection("child", "1.0", "child-1.0-py3-none-any.whl"),
                        PythonResolverSelection("root", "1.0", "root-1.0-py3-none-any.whl"),
                    ),
                )
            }
        }
        val request = request()
        val first = PythonResolverEngine(catalog, worker).resolve(request)
        val second = PythonResolverEngine(catalog, worker).resolve(request)
        assertEquals(listOf("root", "child", "root", "child"), fetched)
        assertEquals(listOf("child", "root"), first.wheels.map { it.normalizedName })
        assertEquals(first.lockDigest, second.lockDigest)
        assertTrue(first.wheels.all { it.sha256.length == 64 && it.sizeBytes == 123L })
    }

    @Test
    fun rejectsSelectionNotProvenByFetchedMetadata() {
        val engine = PythonResolverEngine(
            PythonPackageCatalog { name, _, _ -> PythonResolverProject(name, listOf(candidate(name, "1.0"))) },
            PythonResolverWorker { _, projects, _ ->
                if (projects.isEmpty()) PythonResolverWorkerOutcome.NeedsProjects(setOf("root"))
                else PythonResolverWorkerOutcome.Resolved(
                    listOf(PythonResolverSelection("root", "9.0", "root-9.0-py3-none-any.whl")),
                )
            },
        )
        val error = runCatching { engine.resolve(request()) }.exceptionOrNull() as PythonResolutionException
        assertEquals(PythonResolutionErrorCode.WORKER_PROTOCOL_ERROR, error.code)
    }

    @Test
    fun cancellationStopsBeforeCatalogNetwork() {
        var fetched = false
        val cancellation = MutablePythonResolutionCancellation().also { it.cancel() }
        val engine = PythonResolverEngine(
            PythonPackageCatalog { name, _, _ ->
                fetched = true
                PythonResolverProject(name, emptyList())
            },
            PythonResolverWorker { _, _, _ -> PythonResolverWorkerOutcome.NeedsProjects(setOf("root")) },
        )
        val error = runCatching { engine.resolve(request(), cancellation) }.exceptionOrNull()
        assertTrue(error is PythonResolutionCancelledException)
        assertTrue(!fetched)
    }

    @Test
    fun deadlineAndCandidateLimitsFailClosed() {
        var now = 0L
        val timedOut = PythonResolverEngine(
            PythonPackageCatalog { name, _, _ -> PythonResolverProject(name, emptyList()) },
            PythonResolverWorker { _, _, _ ->
                now = PythonResolverLimits.RESOLUTION_TIMEOUT_MILLIS + 1
                PythonResolverWorkerOutcome.NeedsProjects(setOf("root"))
            },
            nowMillis = { now },
        )
        val timeoutError = runCatching { timedOut.resolve(request()) }.exceptionOrNull() as
            PythonResolutionException
        assertEquals(PythonResolutionErrorCode.RESOURCE_LIMIT_EXCEEDED, timeoutError.code)

        var round = 0
        val tooMany = PythonResolverEngine(
            PythonPackageCatalog { name, _, _ -> PythonResolverProject(name, emptyList()) },
            PythonResolverWorker { _, _, _ ->
                round += 1
                PythonResolverWorkerOutcome.NeedsProjects(setOf("p$round"))
            },
        )
        val limitError = runCatching { tooMany.resolve(request()) }.exceptionOrNull() as
            PythonResolutionException
        assertEquals(PythonResolutionErrorCode.RESOURCE_LIMIT_EXCEEDED, limitError.code)
    }

    @Test
    fun repeatedProjectRequestAndAggregateMetadataFailClosed() {
        val repeated = PythonResolverEngine(
            PythonPackageCatalog { name, _, _ -> PythonResolverProject(name, emptyList()) },
            PythonResolverWorker { _, _, _ -> PythonResolverWorkerOutcome.NeedsProjects(setOf("root")) },
        )
        val repeatedError = runCatching { repeated.resolve(request()) }.exceptionOrNull() as
            PythonResolutionException
        assertEquals(PythonResolutionErrorCode.WORKER_PROTOCOL_ERROR, repeatedError.code)

        val oversized = PythonResolverEngine(
            PythonPackageCatalog { name, _, _ ->
                PythonResolverProject(
                    name,
                    emptyList(),
                    metadataBytes = PythonResolverLimits.MAX_TOTAL_METADATA_BYTES / 2 + 1,
                )
            },
            PythonResolverWorker { _, projects, _ ->
                when {
                    "first" !in projects -> PythonResolverWorkerOutcome.NeedsProjects(setOf("first"))
                    "second" !in projects -> PythonResolverWorkerOutcome.NeedsProjects(setOf("second"))
                    else -> PythonResolverWorkerOutcome.Resolved(emptyList())
                }
            },
        )
        val metadataError = runCatching { oversized.resolve(request()) }.exceptionOrNull() as
            PythonResolutionException
        assertEquals(PythonResolutionErrorCode.RESOURCE_LIMIT_EXCEEDED, metadataError.code)
    }

    private fun request() = PythonEnvironmentResolutionRequest(
        pluginId = "test.plugin",
        requirements = listOf("root>=1"),
        target = PythonEnvironmentTarget("3.14.7", "cp314", "arm64-v8a", 31),
    )

    private fun candidate(name: String, version: String) = PythonResolverCandidate(
        normalizedName = name,
        version = version,
        fileName = "$name-$version-py3-none-any.whl",
        sourceUri = "https://files.pythonhosted.org/packages/$name-$version.whl",
        sha256 = ai.hans.standard.runtime.python.PythonEnvironmentContract.sha256("$name-$version"),
        sizeBytes = 123,
        requiresPython = ">=3.14",
        yanked = false,
        requiresDist = emptyList(),
    )
}
