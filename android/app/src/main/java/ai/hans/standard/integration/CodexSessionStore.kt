package ai.hans.standard.integration

/** Persistence seam containing no credentials or conversation content. */
interface CodexSessionStore {
    /** Absolute app-private directory exposed as Codex's working directory. */
    val workspacePath: String

    fun readThreadId(): String?
    fun saveThreadId(threadId: String)
    fun clearThreadId(expectedThreadId: String? = null)
}
