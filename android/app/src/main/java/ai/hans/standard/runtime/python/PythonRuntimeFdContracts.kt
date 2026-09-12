package ai.hans.standard.runtime.python

import ai.hans.standard.codex.JsonContract
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import java.io.Closeable
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray
import org.json.JSONObject

data class PythonRuntimeBootstrapManifest(
    val sessionNonce: String,
    val expectedPythonVersion: String,
    val expectedAbi: String,
    val expectedStdlibDigest: String,
    val expectedStdlibBytes: Long,
) {
    init {
        require(PythonRuntimeFdContract.isSessionNonce(sessionNonce)) { "Invalid Python session nonce" }
        require(expectedPythonVersion.startsWith("3.14.")) { "Unexpected Python runtime version" }
        require(expectedAbi == "arm64-v8a" || expectedAbi == "x86_64") { "Unexpected Python ABI" }
        require(PythonEnvironmentContract.isSha256(expectedStdlibDigest)) { "Invalid stdlib digest" }
        require(expectedStdlibBytes in 1..PythonRuntimeFdContract.MAX_STDLIB_BYTES) {
            "Invalid stdlib size"
        }
    }
}

data class PythonRuntimeExecutionLeaseManifest(
    val sessionNonce: String,
    val requestId: String,
    val environmentDigest: String,
    val environmentBytes: Long,
    val workspace: PythonRuntimeWorkspaceLeaseManifest? = null,
    val allowedNativeModules: List<PythonAllowedNativeModule> = emptyList(),
) {
    init {
        require(PythonRuntimeFdContract.isSessionNonce(sessionNonce)) { "Invalid Python session nonce" }
        require(PythonRuntimeFdContract.isRequestId(requestId)) { "Invalid Python lease request id" }
        require(PythonEnvironmentContract.isSha256(environmentDigest)) { "Invalid environment digest" }
        require(environmentBytes in PythonRuntimeFdContract.MIN_ENVIRONMENT_BYTES..
            PythonRuntimeFdContract.MAX_ENVIRONMENT_BYTES) { "Invalid environment archive size" }
        require(allowedNativeModules.size <= PythonRuntimeFdContract.MAX_ALLOWED_NATIVE_MODULES) {
            "Too many allowed native modules"
        }
        require(allowedNativeModules.distinct() == allowedNativeModules) {
            "Duplicate allowed native module"
        }
        require(allowedNativeModules == allowedNativeModules.sortedWith(
            compareBy(PythonAllowedNativeModule::module, PythonAllowedNativeModule::packagedName),
        )) { "Allowed native modules must be sorted" }
    }
}

data class PythonRuntimeWorkspaceLeaseManifest(
    val workspaceHandle: String,
    val archiveDigest: String,
    val archiveBytes: Long,
    val fileCount: Int,
    val contentBytes: Long,
) {
    init {
        require(PythonEnvironmentContract.isSha256(workspaceHandle)) { "Invalid workspace handle" }
        require(PythonEnvironmentContract.isSha256(archiveDigest)) { "Invalid workspace archive digest" }
        require(archiveBytes in PythonRuntimeFdContract.MIN_WORKSPACE_ARCHIVE_BYTES..
            PythonRuntimeFdContract.MAX_WORKSPACE_ARCHIVE_BYTES) { "Invalid workspace archive size" }
        require(fileCount in 0..PythonRuntimeFdContract.MAX_WORKSPACE_FILES) {
            "Invalid workspace file count"
        }
        require(contentBytes in 0..PythonRuntimeFdContract.MAX_WORKSPACE_CONTENT_BYTES) {
            "Invalid workspace content size"
        }
    }
}

/** Owned descriptor capability. It is always closed exactly once by engine/service lifecycle. */
internal class PythonOwnedFileDescriptor(
    val fd: Int,
    private val closer: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)
    override fun close() {
        if (closed.compareAndSet(false, true)) closer()
    }

    fun isClosed(): Boolean = closed.get()
}

internal data class PythonNativeBootstrapLease(
    val manifest: PythonRuntimeBootstrapManifest,
    val descriptor: PythonOwnedFileDescriptor,
    val nativeLibraryDirectory: String,
) : Closeable {
    fun nativeConfigJson(): String = PythonRuntimeFdContract.nativeBootstrapJson(
        manifest,
        descriptor.fd,
        nativeLibraryDirectory,
    )

    override fun close() = descriptor.close()
}

internal data class PythonNativeExecutionLease(
    val manifest: PythonRuntimeExecutionLeaseManifest,
    val descriptor: PythonOwnedFileDescriptor,
    val workspaceDescriptor: PythonOwnedFileDescriptor? = null,
) : Closeable {
    fun augmentRequest(requestJson: String): String = PythonRuntimeFdContract.attachExecutionLease(
        requestJson,
        manifest,
        descriptor.fd,
        workspaceDescriptor?.fd,
    )

    override fun close() {
        workspaceDescriptor?.close()
        descriptor.close()
    }
}

data class PythonClientBootstrapLease(
    val manifestJson: String,
    val descriptor: ParcelFileDescriptor,
) : Closeable {
    override fun close() = descriptor.close()
}

data class PythonClientExecutionLease(
    val manifestJson: String,
    val descriptor: ParcelFileDescriptor,
    val workspaceDescriptor: ParcelFileDescriptor? = null,
) : Closeable {
    override fun close() {
        runCatching { workspaceDescriptor?.close() }
        descriptor.close()
    }
}

interface PythonRuntimeDescriptorBroker {
    fun openBootstrap(sessionNonce: String): PythonClientBootstrapLease
    fun openExecution(
        sessionNonce: String,
        request: PythonExecutionRequest,
    ): PythonClientExecutionLease
}

internal object PythonRuntimeFdContract {
    const val PROTOCOL_VERSION = 1
    const val MAX_MANIFEST_BYTES = 16 * 1024
    const val MAX_STDLIB_BYTES = 128L * 1024L * 1024L
    const val MIN_ENVIRONMENT_BYTES = 22L
    const val MAX_ENVIRONMENT_BYTES = 512L * 1024L * 1024L
    const val MIN_WORKSPACE_ARCHIVE_BYTES = 22L
    const val MAX_WORKSPACE_ARCHIVE_BYTES = 192L * 1024L * 1024L
    const val MAX_WORKSPACE_CONTENT_BYTES = 128L * 1024L * 1024L
    const val MAX_WORKSPACE_FILES = 2_048
    const val MAX_WORKSPACE_PATH_BYTES = 1_024
    const val MAX_WORKSPACE_MANIFEST_BYTES = 8 * 1024 * 1024
    const val MAX_ALLOWED_NATIVE_MODULES = 32
    const val WORKSPACE_MANIFEST_MEMBER = "__hans_workspace_manifest__.json"
    const val WORKSPACE_FILE_PREFIX = "__hans_workspace_files__/"

    private val SESSION_NONCE = Regex("[a-f0-9]{64}")
    private val REQUEST_ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")

    fun isSessionNonce(value: String): Boolean = SESSION_NONCE.matches(value)
    fun isRequestId(value: String): Boolean = REQUEST_ID.matches(value)

    fun newSessionNonce(random: SecureRandom = SecureRandom()): String = ByteArray(32)
        .also(random::nextBytes)
        .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

    fun encodeBootstrap(manifest: PythonRuntimeBootstrapManifest): String =
        JsonContract.encodeBounded(
            JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("sessionNonce", manifest.sessionNonce)
                .put("expectedPythonVersion", manifest.expectedPythonVersion)
                .put("expectedAbi", manifest.expectedAbi)
                .put("expectedStdlibDigest", manifest.expectedStdlibDigest)
                .put("expectedStdlibBytes", manifest.expectedStdlibBytes),
            MAX_MANIFEST_BYTES,
        )

    fun decodeBootstrap(raw: String): PythonRuntimeBootstrapManifest {
        val json = JsonContract.parseObject(raw, MAX_MANIFEST_BYTES)
        JsonContract.requireOnlyKeys(
            json,
            setOf(
                "protocolVersion", "sessionNonce", "expectedPythonVersion", "expectedAbi",
                "expectedStdlibDigest", "expectedStdlibBytes",
            ),
            "Python bootstrap manifest",
        )
        requireProtocol(json)
        return PythonRuntimeBootstrapManifest(
            sessionNonce = JsonContract.requiredString(json, "sessionNonce", 64),
            expectedPythonVersion = JsonContract.requiredString(json, "expectedPythonVersion", 32),
            expectedAbi = JsonContract.requiredString(json, "expectedAbi", 32),
            expectedStdlibDigest = JsonContract.requiredString(json, "expectedStdlibDigest", 64),
            expectedStdlibBytes = JsonContract.requiredLong(json, "expectedStdlibBytes"),
        )
    }

    fun encodeExecutionLease(manifest: PythonRuntimeExecutionLeaseManifest): String =
        JsonContract.encodeBounded(
            JSONObject()
                .put("protocolVersion", PROTOCOL_VERSION)
                .put("sessionNonce", manifest.sessionNonce)
                .put("requestId", manifest.requestId)
                .put("environmentDigest", manifest.environmentDigest)
                .put("environmentBytes", manifest.environmentBytes)
                .put("allowedNativeModules", allowedNativeModulesJson(manifest.allowedNativeModules))
                .put(
                    "workspace",
                    manifest.workspace?.let { workspace ->
                        JSONObject()
                            .put("workspaceHandle", workspace.workspaceHandle)
                            .put("archiveDigest", workspace.archiveDigest)
                            .put("archiveBytes", workspace.archiveBytes)
                            .put("fileCount", workspace.fileCount)
                            .put("contentBytes", workspace.contentBytes)
                    } ?: JSONObject.NULL,
                ),
            MAX_MANIFEST_BYTES,
        )

    fun decodeExecutionLease(
        raw: String,
        expectedSessionNonce: String,
        request: PythonExecutionRequest,
    ): PythonRuntimeExecutionLeaseManifest {
        val json = JsonContract.parseObject(raw, MAX_MANIFEST_BYTES)
        JsonContract.requireOnlyKeys(
            json,
            setOf(
                "protocolVersion", "sessionNonce", "requestId", "environmentDigest",
                "environmentBytes", "allowedNativeModules", "workspace",
            ),
            "Python execution lease manifest",
        )
        requireProtocol(json)
        val workspaceJson = json.opt("workspace")
            ?.takeUnless { it == JSONObject.NULL }
            ?.let { it as? JSONObject ?: error("Python workspace lease must be an object") }
        val workspace = workspaceJson?.let {
            JsonContract.requireOnlyKeys(
                it,
                setOf(
                    "workspaceHandle", "archiveDigest", "archiveBytes", "fileCount", "contentBytes",
                ),
                "Python workspace lease manifest",
            )
            PythonRuntimeWorkspaceLeaseManifest(
                workspaceHandle = JsonContract.requiredString(it, "workspaceHandle", 64),
                archiveDigest = JsonContract.requiredString(it, "archiveDigest", 64),
                archiveBytes = JsonContract.requiredLong(it, "archiveBytes"),
                fileCount = JsonContract.requiredLong(it, "fileCount").toIntExact("workspace file count"),
                contentBytes = JsonContract.requiredLong(it, "contentBytes"),
            )
        }
        return PythonRuntimeExecutionLeaseManifest(
            sessionNonce = JsonContract.requiredString(json, "sessionNonce", 64),
            requestId = JsonContract.requiredString(json, "requestId", 128),
            environmentDigest = JsonContract.requiredString(json, "environmentDigest", 64),
            environmentBytes = JsonContract.requiredLong(json, "environmentBytes"),
            allowedNativeModules = decodeAllowedNativeModules(json),
            workspace = workspace,
        ).also {
            require(constantTimeEquals(it.sessionNonce, expectedSessionNonce)) {
                "Python execution session does not match"
            }
            require(it.requestId == request.requestId) { "Python lease request does not match" }
            require(it.environmentDigest == request.environmentDigest) {
                "Python lease environment does not match"
            }
            require(it.workspace?.workspaceHandle == request.workspaceHandle) {
                "Python lease workspace does not match"
            }
        }
    }

    fun nativeBootstrapJson(
        manifest: PythonRuntimeBootstrapManifest,
        stdlibFd: Int,
        nativeLibraryDirectory: String,
    ): String {
        require(stdlibFd >= 0) { "Invalid stdlib descriptor" }
        require(nativeLibraryDirectory.startsWith('/')) { "Native library path must be absolute" }
        return JsonContract.encodeBounded(
            JSONObject()
                .put("protocolVersion", PythonRuntimeContract.PROTOCOL_VERSION)
                .put("expectedPythonSeries", PythonRuntimeContract.REQUIRED_PYTHON_SERIES)
                .put("expectedPythonVersion", manifest.expectedPythonVersion)
                .put("expectedAbi", manifest.expectedAbi)
                .put("expectedStdlibDigest", manifest.expectedStdlibDigest)
                .put("expectedStdlibBytes", manifest.expectedStdlibBytes)
                .put("nativeLibraryDir", nativeLibraryDirectory)
                .put("stdlibFd", stdlibFd)
                .put("sitePackages", JSONObject.NULL)
                .put("workspaceRoot", JSONObject.NULL)
                .put("maximumEventBytes", PythonRuntimeContract.MAX_EVENT_BYTES)
                .put("maximumCapabilityBytes", PythonRuntimeContract.MAX_CAPABILITY_BYTES),
            PythonRuntimeContract.MAX_STATE_BYTES,
        )
    }

    fun attachExecutionLease(
        requestJson: String,
        manifest: PythonRuntimeExecutionLeaseManifest,
        environmentFd: Int,
        workspaceFd: Int? = null,
    ): String {
        require(environmentFd >= 0) { "Invalid environment descriptor" }
        require((manifest.workspace == null) == (workspaceFd == null)) {
            "Workspace manifest and descriptor must be supplied together"
        }
        workspaceFd?.let { require(it >= 0) { "Invalid workspace descriptor" } }
        val request = JsonContract.parseObject(requestJson, PythonRuntimeContract.MAX_REQUEST_BYTES)
        require(!request.has("runtimeLease")) { "Runtime lease is reserved for the isolated service" }
        require(!request.has("allowedNativeModules")) {
            "Native module authority is reserved for the isolated service"
        }
        request.put("allowedNativeModules", allowedNativeModulesJson(manifest.allowedNativeModules))
        request.put(
            "runtimeLease",
            JSONObject()
                .put("environmentFd", environmentFd)
                .put("environmentDigest", manifest.environmentDigest)
                .put("environmentBytes", manifest.environmentBytes)
                .put("allowedNativeModules", allowedNativeModulesJson(manifest.allowedNativeModules))
                .apply {
                    manifest.workspace?.let { workspace ->
                        put(
                            "workspace",
                            JSONObject()
                                .put("workspaceFd", workspaceFd)
                                .put("workspaceHandle", workspace.workspaceHandle)
                                .put("archiveDigest", workspace.archiveDigest)
                                .put("archiveBytes", workspace.archiveBytes)
                                .put("fileCount", workspace.fileCount)
                                .put("contentBytes", workspace.contentBytes),
                        )
                    }
                },
        )
        return JsonContract.encodeBounded(request, PythonRuntimeContract.MAX_REQUEST_BYTES)
    }

    fun duplicateReadOnly(
        incoming: ParcelFileDescriptor,
        expectedBytes: Long,
    ): PythonOwnedFileDescriptor {
        val duplicate = ParcelFileDescriptor.dup(incoming.fileDescriptor)
        try {
            val flags = Os.fcntlInt(duplicate.fileDescriptor, OsConstants.F_GETFL, 0)
            require(flags and OsConstants.O_ACCMODE == OsConstants.O_RDONLY) {
                "Writable descriptors are forbidden"
            }
            val stat = Os.fstat(duplicate.fileDescriptor)
            require(stat.st_mode and OsConstants.S_IFMT == OsConstants.S_IFREG) {
                "Descriptor is not a regular file"
            }
            require(stat.st_size == expectedBytes) { "Descriptor size does not match its manifest" }
            Os.lseek(duplicate.fileDescriptor, 0, OsConstants.SEEK_CUR)
            return PythonOwnedFileDescriptor(duplicate.fd, duplicate::close)
        } catch (error: Throwable) {
            runCatching(duplicate::close)
            throw error
        }
    }

    fun constantTimeEquals(left: String, right: String): Boolean {
        if (left.length != right.length) return false
        var difference = 0
        left.indices.forEach { difference = difference or (left[it].code xor right[it].code) }
        return difference == 0
    }

    private fun requireProtocol(json: JSONObject) {
        require(JsonContract.requiredLong(json, "protocolVersion") == PROTOCOL_VERSION.toLong()) {
            "Unsupported Python descriptor protocol"
        }
    }

    private fun allowedNativeModulesJson(modules: List<PythonAllowedNativeModule>): JSONArray =
        JSONArray(modules.map { module ->
            JSONObject()
                .put("module", module.module)
                .put("packagedName", module.packagedName)
        })

    private fun decodeAllowedNativeModules(json: JSONObject): List<PythonAllowedNativeModule> {
        val array = JsonContract.requiredArray(json, "allowedNativeModules")
        require(array.length() <= MAX_ALLOWED_NATIVE_MODULES) { "Too many allowed native modules" }
        return List(array.length()) { index ->
            val value = array.optJSONObject(index)
                ?: error("Allowed native module must be an object")
            JsonContract.requireOnlyKeys(
                value,
                setOf("module", "packagedName"),
                "Allowed native module",
            )
            PythonAllowedNativeModule(
                module = JsonContract.requiredString(value, "module", 128),
                packagedName = JsonContract.requiredString(value, "packagedName", 256),
            )
        }
    }

    private fun Long.toIntExact(label: String): Int {
        require(this in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Invalid $label" }
        return toInt()
    }
}
