package ai.hans.standard.phone.notifications

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.GatedDynamicToolExecutor
import ai.hans.standard.phone.tools.DynamicToolConfirmationProvider
import java.util.concurrent.Executor

/** Inbox management also mutates durable facts; background turns may only recall or inspect. */
internal class NotificationInboxToolExecutors(
    source: NotificationInboxQuerySource,
    executor: Executor,
    confirmation: DynamicToolConfirmationProvider,
    isInteractive: (DynamicToolCallParams) -> Boolean,
    reportPort: NotificationEventReportPort? = null,
) {
    private val delegate = NotificationInboxDynamicToolExecutor(
        source = source,
        backgroundExecutor = executor,
        confirmationProvider = confirmation,
        reportPort = reportPort,
    )
    private val backgroundGate = GatedDynamicToolExecutor(
        delegate = delegate,
        denialCode = "background_notification_management_forbidden",
        isAllowed = ::isReadOnlyCall,
    )

    // A narrower catalog is not the authority boundary: guessed management names still pass
    // through the gate before arguments, confirmations, or source methods are touched.
    val background: DynamicToolExecutor = object : DynamicToolExecutor by backgroundGate {
        override val specs = delegate.specs.map { namespace ->
            namespace.copy(tools = namespace.tools.filter { it.name in READ_ONLY_TOOLS })
        }
    }

    // Thread-bound heartbeats use the interactive catalog, including the reserved interval
    // before their turn id is acknowledged. Reuse the host's per-call origin predicate.
    val interactive: DynamicToolExecutor = GatedDynamicToolExecutor(
        delegate = delegate,
        denialCode = "background_notification_management_forbidden",
        isAllowed = { call -> isReadOnlyCall(call) || isInteractive(call) },
    )

    private fun isReadOnlyCall(call: DynamicToolCallParams): Boolean =
        call.namespace == NotificationInboxDynamicToolCatalog.NAMESPACE &&
            call.tool in READ_ONLY_TOOLS

    private companion object {
        val READ_ONLY_TOOLS = setOf("recent", "relevant", "privacy_status", "export_privacy_settings")
    }
}
