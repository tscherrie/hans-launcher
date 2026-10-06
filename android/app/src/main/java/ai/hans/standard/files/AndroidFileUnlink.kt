package ai.hans.standard.files

import android.system.ErrnoException
import java.nio.file.Path

/** Public NDK unlink only: no private Android API, rmdir, or recursive fallback. */
internal object AndroidFileUnlink {
    private val available = try {
        System.loadLibrary("hans_file_unlink_jni")
        nativeContract() == 1
    } catch (_: LinkageError) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun unlink(path: Path) {
        if (!available) throw FileAccessFailure("native_file_unlink_unavailable")
        // JNI strings use Modified UTF-8, which is not the filesystem encoding for emoji.
        // Pass ordinary UTF-8 bytes instead; the native side rejects embedded NULs.
        val error = nativeUnlink(path.toString().toByteArray(Charsets.UTF_8))
        if (error != 0) throw ErrnoException("unlink", error)
    }

    @JvmStatic private external fun nativeContract(): Int
    @JvmStatic private external fun nativeUnlink(path: ByteArray): Int
}
