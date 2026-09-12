package ai.hans.standard.artifacts

import java.nio.charset.StandardCharsets

@JvmInline
value class ArtifactHandle(val value: String) {
    init {
        require(HANDLE.matches(value)) { "Invalid artifact handle" }
    }

    override fun toString(): String = value

    private companion object {
        val HANDLE = Regex("art_[0-9a-f]{64}")
    }
}

enum class ArtifactOrigin {
    CODEX,
    PYTHON,
    JAVASCRIPT,
    ANDROID,
    USER_IMPORT,
    REMOTE_WORKER,
}

data class ArtifactMetadata(
    val handle: ArtifactHandle,
    val displayName: String,
    val mimeType: String,
    val byteCount: Long,
    val sha256: String,
    val createdAtEpochMillis: Long,
    val origin: ArtifactOrigin,
    val workspaceHandle: String? = null,
) {
    init {
        require(displayName.isNotBlank() && displayName.length <= 255) { "Invalid artifact display name" }
        require(displayName.none { it == '/' || it == '\\' || it == '\u0000' || it.isISOControl() }) {
            "Unsafe artifact display name"
        }
        require(MIME_TYPE.matches(mimeType)) { "Invalid artifact MIME type" }
        require(byteCount >= 0L) { "Negative artifact size" }
        require(SHA_256.matches(sha256)) { "Invalid artifact digest" }
        require(createdAtEpochMillis >= 0L) { "Invalid artifact creation time" }
        require(workspaceHandle == null || SHA_256.matches(workspaceHandle)) { "Invalid workspace reference" }
        require(displayName.toByteArray(StandardCharsets.UTF_8).size <= 1_024) { "Artifact name is too long" }
    }

    private companion object {
        val SHA_256 = Regex("[0-9a-f]{64}")
        val MIME_TYPE = Regex("[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]{0,126}/[a-zA-Z0-9][a-zA-Z0-9!#$&^_.+-]{0,126}")
    }
}

data class ArtifactQuotas(
    val maxArtifactBytes: Long = DEFAULT_MAX_ARTIFACT_BYTES,
    val maxStoreBytes: Long = DEFAULT_MAX_STORE_BYTES,
    val maxArtifacts: Int = DEFAULT_MAX_ARTIFACTS,
) {
    init {
        require(maxArtifactBytes in 1..ABSOLUTE_MAX_ARTIFACT_BYTES)
        require(maxStoreBytes in maxArtifactBytes..ABSOLUTE_MAX_STORE_BYTES)
        require(maxArtifacts in 1..ABSOLUTE_MAX_ARTIFACTS)
    }

    companion object {
        const val DEFAULT_MAX_ARTIFACT_BYTES = 512L * 1024L * 1024L
        const val ABSOLUTE_MAX_ARTIFACT_BYTES = 4L * 1024L * 1024L * 1024L
        const val DEFAULT_MAX_STORE_BYTES = 2L * 1024L * 1024L * 1024L
        const val ABSOLUTE_MAX_STORE_BYTES = 32L * 1024L * 1024L * 1024L
        const val DEFAULT_MAX_ARTIFACTS = 1_000
        const val ABSOLUTE_MAX_ARTIFACTS = 100_000
    }
}
