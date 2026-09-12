package ai.hans.standard.runtime.python.resolver

import ai.hans.standard.runtime.python.PythonEnvironmentContract
import ai.hans.standard.runtime.python.PythonEnvironmentTarget
import ai.hans.standard.runtime.python.PythonNativeCatalogPin
import ai.hans.standard.runtime.python.PythonNativePackageCatalog

/**
 * Gives the closed, APK-signed native catalog precedence for names it owns. A name absent from
 * that catalog retains the existing pure-Python PyPI route. There is deliberately no fallback for
 * an owned name with an incompatible version: silently substituting a differently built artifact
 * would defeat the native lock.
 */
class SignedNativeFirstPythonPackageCatalog(
    private val nativeCatalog: PythonNativePackageCatalog,
    private val purePythonFallback: PythonPackageCatalog,
) : PythonPackageCatalog {
    override fun project(
        normalizedName: String,
        target: PythonEnvironmentTarget,
        cancellation: PythonResolutionCancellation,
    ): PythonResolverProject {
        cancellation.throwIfCancelled()
        val name = PythonEnvironmentContract.normalizePackageName(normalizedName)
        require(name == normalizedName && PythonEnvironmentContract.isPackageName(name)) {
            "Invalid native package lookup"
        }
        val native = nativeCatalog.candidates(name, target)
        cancellation.throwIfCancelled()
        if (native.isEmpty()) {
            return purePythonFallback.project(name, target, cancellation)
        }
        require(native.size <= PythonResolverLimits.MAX_CANDIDATES_PER_PROJECT) {
            "Signed native catalog has too many candidates"
        }
        val candidates = native.map { entry ->
            require(PythonEnvironmentContract.normalizePackageName(entry.packageName) == name) {
                "Signed native catalog returned another package"
            }
            require(target.directorySegment in entry.supportedTargets) {
                "Signed native catalog returned an incompatible target"
            }
            val pin = PythonNativeCatalogPin(
                packageName = entry.packageName,
                version = entry.version,
                catalogId = entry.catalogId,
                payloadSha256 = entry.payloadSha256,
            )
            PythonResolverCandidate(
                normalizedName = name,
                version = entry.version,
                fileName = entry.companionWheel.fileName,
                sourceUri = "apk-native://${entry.catalogId}/${entry.companionWheel.fileName}",
                sha256 = entry.payloadSha256,
                sizeBytes = entry.companionWheel.sizeBytes,
                requiresPython = entry.requiresPython,
                yanked = false,
                requiresDist = entry.requiresDist,
                nativePin = pin,
            )
        }.sortedWith(compareBy<PythonResolverCandidate> { it.version }.thenBy { it.fileName })
        require(candidates.map { it.version to it.fileName }.distinct().size == candidates.size) {
            "Signed native catalog contains duplicate candidates"
        }
        return PythonResolverProject(name, candidates, metadataBytes = 0L)
    }
}
