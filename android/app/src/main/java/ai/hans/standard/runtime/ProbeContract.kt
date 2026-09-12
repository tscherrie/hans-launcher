package ai.hans.standard.runtime

import java.io.File

data class NativeProbeReport(
    val pid: Int,
    val parentPid: Int,
    val abi: String,
    val environment: String,
)

object ProbeContract {
    const val EXECUTABLE_NAME = "libhans_probe.so"
    const val ARGUMENT = "--contract=1"

    private val reportPattern = Regex(
        pattern = "^HANS_NATIVE_PROBE_V1 pid=([1-9][0-9]*) ppid=([1-9][0-9]*) abi=([A-Za-z0-9_-]+) env=(private)$",
    )

    fun executableFile(nativeLibraryDir: String): File {
        val directory = File(nativeLibraryDir)
        require(directory.isAbsolute) { "nativeLibraryDir must be absolute" }
        return File(directory, EXECUTABLE_NAME)
    }

    fun parse(stdout: String): NativeProbeReport? {
        val match = reportPattern.matchEntire(stdout.trim()) ?: return null
        return NativeProbeReport(
            pid = match.groupValues[1].toIntOrNull() ?: return null,
            parentPid = match.groupValues[2].toIntOrNull() ?: return null,
            abi = match.groupValues[3],
            environment = match.groupValues[4],
        )
    }
}

object RuntimeEnvironment {
    fun sanitized(
        runtimeDirectory: String,
        temporaryDirectory: String,
        androidRoot: String,
        androidData: String,
    ): Map<String, String> = mapOf(
        "ANDROID_DATA" to androidData,
        "ANDROID_ROOT" to androidRoot,
        "HANS_RUNTIME_DIR" to runtimeDirectory,
        "HANS_RUNTIME_TMP_DIR" to temporaryDirectory,
        "HOME" to runtimeDirectory,
        "LANG" to "C.UTF-8",
        "PATH" to "/system/bin",
        "TMPDIR" to temporaryDirectory,
    )
}
