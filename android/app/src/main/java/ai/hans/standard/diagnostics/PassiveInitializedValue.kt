package ai.hans.standard.diagnostics

/** A read boundary which can never initialize the wrapped lazy value. */
internal class PassiveInitializedValue<T>(private val delegate: Lazy<T>) {
    fun isInitialized(): Boolean = delegate.isInitialized()

    fun <R> snapshot(project: (T) -> R?): R? =
        if (delegate.isInitialized()) project(delegate.value) else null
}
