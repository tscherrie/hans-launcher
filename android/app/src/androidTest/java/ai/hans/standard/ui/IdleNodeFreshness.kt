package ai.hans.standard.ui

/** Single public refresh, never a cache fallback or an expected-value mutation. */
internal fun <T> readFreshIdleNode(refresh: () -> Boolean, read: () -> T): T {
    check(refresh()) { "Accessibility node became obsolete during renderer observation" }
    return read()
}
