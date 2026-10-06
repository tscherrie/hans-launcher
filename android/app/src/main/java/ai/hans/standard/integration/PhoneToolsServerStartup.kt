package ai.hans.standard.integration

import ai.hans.standard.remotecontrol.PhoneToolsMcpServer

/** Optional incoming transport failure must not take away local chat/dynamic tools. */
internal fun startPhoneToolsServerOrNull(create: () -> PhoneToolsMcpServer): PhoneToolsMcpServer? {
    var server: PhoneToolsMcpServer? = null
    return try {
        create().also { server = it; it.start() }
    } catch (_: Exception) {
        runCatching { server?.close() }
        null
    }
}
