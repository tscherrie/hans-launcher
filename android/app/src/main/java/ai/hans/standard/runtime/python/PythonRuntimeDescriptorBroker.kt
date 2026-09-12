package ai.hans.standard.runtime.python

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale

data class PythonEnvironmentArchiveReceipt(
    val archive: File,
    val sha256: String,
    val sizeBytes: Long,
    val allowedNativeModules: List<PythonAllowedNativeModule> = emptyList(),
)

fun interface PythonEnvironmentArchiveProvider {
    /** Returns an already built, immutable environment PYZ for this exact content digest. */
    fun open(environmentDigest: String): PythonEnvironmentArchiveReceipt
}

/** Main-UID broker. The isolated worker receives capabilities (FDs), never app-private paths. */
class AndroidPythonRuntimeDescriptorBroker(
    context: Context,
    environmentArchiveProvider: PythonEnvironmentArchiveProvider? = null,
    private val workspaceArchiveProvider: PythonWorkspaceArchiveProvider? = null,
) : PythonRuntimeDescriptorBroker {
    private val appContext = context.applicationContext
    private val noBackupRoot = appContext.noBackupFilesDir.canonicalFile
    private val runtimeMaterializer = PythonRuntimeAssetMaterializer(
        noBackupRoot = noBackupRoot,
        source = AndroidPythonRuntimeAssetSource(appContext.assets),
    )
    private val environmentProvider = environmentArchiveProvider ?: BaselineEnvironmentArchiveProvider(
        noBackupRoot,
    )

    override fun openBootstrap(sessionNonce: String): PythonClientBootstrapLease {
        require(PythonRuntimeFdContract.isSessionNonce(sessionNonce)) { "Invalid Python session nonce" }
        val receipt = runtimeMaterializer.materialize()
        requirePrivateRegularFile(receipt.stdlibZip, receipt.stdlibBytes, receipt.stdlibSha256)
        val manifest = PythonRuntimeBootstrapManifest(
            sessionNonce = sessionNonce,
            expectedPythonVersion = receipt.pythonVersion,
            expectedAbi = receipt.abi,
            expectedStdlibDigest = receipt.stdlibSha256,
            expectedStdlibBytes = receipt.stdlibBytes,
        )
        return PythonClientBootstrapLease(
            manifestJson = PythonRuntimeFdContract.encodeBootstrap(manifest),
            descriptor = ParcelFileDescriptor.open(receipt.stdlibZip, ParcelFileDescriptor.MODE_READ_ONLY),
        )
    }

    override fun openExecution(
        sessionNonce: String,
        request: PythonExecutionRequest,
    ): PythonClientExecutionLease {
        require(PythonRuntimeFdContract.isSessionNonce(sessionNonce)) { "Invalid Python session nonce" }
        val receipt = environmentProvider.open(request.environmentDigest)
        require(receipt.sha256 == request.environmentDigest) { "Environment provider returned another digest" }
        requirePrivateRegularFile(receipt.archive, receipt.sizeBytes, receipt.sha256)
        val workspaceReceipt = request.workspaceHandle?.let { requestedHandle ->
            val provider = workspaceArchiveProvider
                ?: error("Python workspace descriptor broker is unavailable")
            provider.open(requestedHandle).also { workspace ->
                require(workspace.workspaceHandle == requestedHandle) {
                    "Workspace provider returned another handle"
                }
                require(workspace.fileCount in 0..PythonRuntimeFdContract.MAX_WORKSPACE_FILES)
                require(workspace.contentBytes in 0..PythonRuntimeFdContract.MAX_WORKSPACE_CONTENT_BYTES)
                requirePrivateRegularFile(workspace.archive, workspace.sizeBytes, workspace.sha256)
            }
        }
        val manifest = PythonRuntimeExecutionLeaseManifest(
            sessionNonce = sessionNonce,
            requestId = request.requestId,
            environmentDigest = receipt.sha256,
            environmentBytes = receipt.sizeBytes,
            allowedNativeModules = receipt.allowedNativeModules,
            workspace = workspaceReceipt?.let { workspace ->
                PythonRuntimeWorkspaceLeaseManifest(
                    workspaceHandle = workspace.workspaceHandle,
                    archiveDigest = workspace.sha256,
                    archiveBytes = workspace.sizeBytes,
                    fileCount = workspace.fileCount,
                    contentBytes = workspace.contentBytes,
                )
            },
        )
        val environmentDescriptor = ParcelFileDescriptor.open(
            receipt.archive,
            ParcelFileDescriptor.MODE_READ_ONLY,
        )
        return try {
            PythonClientExecutionLease(
                manifestJson = PythonRuntimeFdContract.encodeExecutionLease(manifest),
                descriptor = environmentDescriptor,
                workspaceDescriptor = workspaceReceipt?.let { workspace ->
                    ParcelFileDescriptor.open(workspace.archive, ParcelFileDescriptor.MODE_READ_ONLY)
                },
            )
        } catch (error: Throwable) {
            runCatching(environmentDescriptor::close)
            throw error
        }
    }

    private fun requirePrivateRegularFile(
        file: File,
        expectedBytes: Long,
        expectedDigest: String,
    ) {
        val canonical = file.canonicalFile
        require(canonical.isFile && canonical.toPath().startsWith(noBackupRoot.toPath())) {
            "Python descriptor source must remain in app-private no-backup storage"
        }
        require(canonical.length() == expectedBytes) { "Python descriptor source size changed" }
        require(expectedBytes in 1..PythonRuntimeFdContract.MAX_ENVIRONMENT_BYTES) {
            "Python descriptor source is too large"
        }
        require(hash(canonical) == expectedDigest) { "Python descriptor source digest changed" }
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }
    }
}

/** Deterministic empty ZIP used until a non-baseline environment has been activated. */
internal class BaselineEnvironmentArchiveProvider(
    noBackupRoot: File,
) : PythonEnvironmentArchiveProvider {
    private val archive = File(noBackupRoot, "python/environments/baseline/environment.pyz")

    override fun open(environmentDigest: String): PythonEnvironmentArchiveReceipt {
        require(environmentDigest == PythonRuntimeContract.BASELINE_ENVIRONMENT_DIGEST) {
            "No activated Python environment matches the request"
        }
        synchronized(this) {
            if (!archive.isFile || archive.length() != EMPTY_ZIP.size.toLong() ||
                PythonEnvironmentContract.sha256(archive.readBytes()) != environmentDigest
            ) {
                require(archive.parentFile?.mkdirs() != false) { "Cannot create baseline environment" }
                val staging = File(archive.parentFile, ".environment.${System.nanoTime()}.tmp")
                try {
                    FileOutputStream(staging).use { output ->
                        output.write(EMPTY_ZIP)
                        output.fd.sync()
                    }
                    require(PythonEnvironmentContract.sha256(staging.readBytes()) == environmentDigest)
                    if (archive.exists()) require(archive.delete()) { "Cannot replace baseline environment" }
                    require(staging.renameTo(archive)) { "Cannot activate baseline environment" }
                    archive.setReadable(true, true)
                    archive.setWritable(false, false)
                    archive.setExecutable(false, false)
                } finally {
                    staging.delete()
                }
            }
        }
        return PythonEnvironmentArchiveReceipt(
            archive = archive.canonicalFile,
            sha256 = environmentDigest,
            sizeBytes = EMPTY_ZIP.size.toLong(),
        )
    }

    private companion object {
        val EMPTY_ZIP = byteArrayOf(
            0x50, 0x4b, 0x05, 0x06,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        )
    }
}
