package ai.hans.standard.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ProbeContractTest {
    @Test
    fun executablePathIsAlwaysInsideNativeLibraryDirectory() {
        val executable = ProbeContract.executableFile("/data/app/example/lib/arm64")

        assertEquals("/data/app/example/lib/arm64/libhans_probe.so", executable.path)
    }

    @Test
    fun relativeNativeLibraryDirectoryIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ProbeContract.executableFile("data/app/example")
        }
    }

    @Test
    fun validProbePayloadIsParsed() {
        assertEquals(
            NativeProbeReport(
                pid = 123,
                parentPid = 99,
                abi = "arm64-v8a",
                environment = "private",
            ),
            ProbeContract.parse(
                "HANS_NATIVE_PROBE_V1 pid=123 ppid=99 abi=arm64-v8a env=private\n",
            ),
        )
    }

    @Test
    fun malformedProbePayloadIsRejected() {
        assertNull(ProbeContract.parse("pid=123; rm -rf /"))
        assertNull(
            ProbeContract.parse(
                "HANS_NATIVE_PROBE_V1 pid=0 ppid=99 abi=arm64-v8a env=private",
            ),
        )
        assertNull(
            ProbeContract.parse(
                "HANS_NATIVE_PROBE_V1 pid=123 ppid=99 abi=arm64-v8a env=inherited",
            ),
        )
    }

    @Test
    fun runtimeEnvironmentIsAllowlistedAndAppPrivate() {
        val environment = RuntimeEnvironment.sanitized(
            runtimeDirectory = "/data/user/0/ai.hans.standard/no_backup/runtime",
            temporaryDirectory = "/data/user/0/ai.hans.standard/cache/runtime-tmp",
            androidRoot = "/system",
            androidData = "/data",
        )

        assertEquals(
            setOf(
                "ANDROID_DATA",
                "ANDROID_ROOT",
                "HANS_RUNTIME_DIR",
                "HANS_RUNTIME_TMP_DIR",
                "HOME",
                "LANG",
                "PATH",
                "TMPDIR",
            ),
            environment.keys,
        )
        assertEquals(environment.getValue("HANS_RUNTIME_DIR"), environment.getValue("HOME"))
        assertEquals(
            environment.getValue("HANS_RUNTIME_TMP_DIR"),
            environment.getValue("TMPDIR"),
        )
    }
}
