package ai.hans.standard.ui

/** Register atomically and release every acquired observer, including after partial failure. */
internal fun registerDisplayMotionObservers(
    vararg registrations: () -> AutoCloseable,
): AutoCloseable {
    val acquired = mutableListOf<AutoCloseable>()
    fun release() {
        val closing = acquired.asReversed().toList()
        acquired.clear()
        closing.forEach { runCatching { it.close() } }
    }
    try {
        registrations.forEach { acquired += it() }
    } catch (failure: Exception) {
        release()
        throw failure
    }
    return AutoCloseable { release() }
}
