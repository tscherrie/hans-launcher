package ai.hans.standard.runtime.python.resolver

import ai.hans.standard.runtime.python.PythonEnvironmentContract
import ai.hans.standard.runtime.python.PythonEnvironmentLock
import ai.hans.standard.runtime.python.PythonEnvironmentResolutionRequest
import ai.hans.standard.runtime.python.PythonEnvironmentResolver
import ai.hans.standard.runtime.python.PythonNativeCatalogPin
import ai.hans.standard.runtime.python.PythonWheelPin
import java.util.LinkedHashMap

/** Iteratively supplies only projects requested by the isolated semantic resolver. */
class PythonResolverEngine(
    private val catalog: PythonPackageCatalog,
    private val worker: PythonResolverWorker,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) : CancellablePythonEnvironmentResolver, PythonEnvironmentResolver {
    override fun resolve(request: PythonEnvironmentResolutionRequest): PythonEnvironmentLock =
        resolve(request, PythonResolutionCancellation.NONE)

    override fun resolve(
        request: PythonEnvironmentResolutionRequest,
        cancellation: PythonResolutionCancellation,
    ): PythonEnvironmentLock {
        val started = nowMillis()
        val projects = LinkedHashMap<String, PythonResolverProject>()
        repeat(PythonResolverLimits.MAX_RESOLUTION_ROUNDS) {
            cancellation.throwIfCancelled()
            requireWithinDeadline(started)
            when (val outcome = worker.resolve(request, projects.toMap(), cancellation)) {
                is PythonResolverWorkerOutcome.NeedsProjects -> {
                    if (outcome.normalizedNames.isEmpty()) {
                        throw PythonResolutionException(
                            PythonResolutionErrorCode.WORKER_PROTOCOL_ERROR,
                            "Resolver requested an empty project set",
                        )
                    }
                    var fetchedProject = false
                    outcome.normalizedNames.sorted().forEach { rawName ->
                        val name = PythonEnvironmentContract.normalizePackageName(rawName)
                        if (name != rawName || !PythonEnvironmentContract.isPackageName(name)) {
                            throw PythonResolutionException(
                                PythonResolutionErrorCode.WORKER_PROTOCOL_ERROR,
                                "Resolver requested an invalid project",
                            )
                        }
                        if (name !in projects) {
                            if (projects.size >= PythonResolverLimits.MAX_PROJECTS) {
                                limit("Dependency graph contains too many projects")
                            }
                            val project = catalog.project(name, request.target, cancellation)
                            cancellation.throwIfCancelled()
                            requireWithinDeadline(started)
                            if (project.normalizedName != name) {
                                workerProtocol("Package catalog returned another project")
                            }
                            projects[name] = project
                            fetchedProject = true
                            if (projects.values.sumOf { it.candidates.size } >
                                PythonResolverLimits.MAX_CANDIDATES
                            ) {
                                limit("Dependency graph contains too many candidates")
                            }
                            if (projects.values.sumOf { it.metadataBytes } >
                                PythonResolverLimits.MAX_TOTAL_METADATA_BYTES
                            ) {
                                limit("Dependency graph metadata exceeds its aggregate byte limit")
                            }
                        }
                    }
                    if (!fetchedProject) {
                        workerProtocol("Resolver repeatedly requested already supplied projects")
                    }
                }
                is PythonResolverWorkerOutcome.Resolved -> {
                    cancellation.throwIfCancelled()
                    requireWithinDeadline(started)
                    return lock(request, projects, outcome.selections)
                }
                is PythonResolverWorkerOutcome.Failed -> throw PythonResolutionException(
                    outcome.code,
                    outcome.detail ?: "Python dependency resolution failed",
                )
            }
        }
        limit("Dependency resolution exceeded its round limit")
    }

    private fun lock(
        request: PythonEnvironmentResolutionRequest,
        projects: Map<String, PythonResolverProject>,
        selections: List<PythonResolverSelection>,
    ): PythonEnvironmentLock {
        if (selections.size > 128 || selections.map { it.normalizedName }.distinct().size != selections.size) {
            throw PythonResolutionException(
                PythonResolutionErrorCode.WORKER_PROTOCOL_ERROR,
                "Resolver returned a malformed package selection",
            )
        }
        val selectedCandidates = selections.map { selection ->
            val name = PythonEnvironmentContract.normalizePackageName(selection.normalizedName)
            if (name != selection.normalizedName) workerProtocol("Selection name is not canonical")
            projects[name]?.candidates?.singleOrNull {
                it.version == selection.version && it.fileName == selection.fileName
            } ?: workerProtocol("Selection does not match fetched PyPI metadata")
        }
        val wheels = selectedCandidates.filter { it.nativePin == null }.map { candidate ->
            PythonWheelPin(
                packageName = candidate.normalizedName,
                version = candidate.version,
                fileName = candidate.fileName,
                sha256 = candidate.sha256,
                sizeBytes = candidate.sizeBytes,
                sourceUri = candidate.sourceUri,
                requiresPython = candidate.requiresPython,
            )
        }.sortedWith(compareBy<PythonWheelPin> { it.normalizedName }.thenBy { it.version })
        val nativePackages = selectedCandidates.mapNotNull(PythonResolverCandidate::nativePin)
            .sortedWith(compareBy<PythonNativeCatalogPin> { it.normalizedName }.thenBy { it.version })
        return PythonEnvironmentLock(
            schemaVersion = PythonEnvironmentContract.LOCK_SCHEMA_VERSION,
            pluginId = request.pluginId,
            target = request.target,
            wheels = wheels,
            nativePackages = nativePackages,
        )
    }

    private fun requireWithinDeadline(started: Long) {
        if (nowMillis() - started > PythonResolverLimits.RESOLUTION_TIMEOUT_MILLIS) {
            limit("Dependency resolution exceeded its time limit")
        }
    }

    private fun workerProtocol(message: String): Nothing = throw PythonResolutionException(
        PythonResolutionErrorCode.WORKER_PROTOCOL_ERROR,
        message,
    )

    private fun limit(message: String): Nothing = throw PythonResolutionException(
        PythonResolutionErrorCode.RESOURCE_LIMIT_EXCEEDED,
        message,
    )
}
