package ai.hans.standard.phone.notifications.facts

import ai.hans.standard.codex.DynamicToolCallParams
import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.codex.GatedDynamicToolExecutor
import ai.hans.standard.phone.tools.DynamicToolConfirmationProvider
import java.util.concurrent.Executor

/** Independent jobs and thread-bound heartbeats may recall claims, never invent owner edits. */
internal class NotificationFactToolExecutors(
    repository: NotificationFactRepository,
    executor: Executor,
    forget: (NotificationFactPrivacyRequest) -> NotificationFactPrivacyBeginResult,
    confirmation: DynamicToolConfirmationProvider,
    isInteractive: (DynamicToolCallParams) -> Boolean,
) {
    val background: DynamicToolExecutor = NotificationFactDynamicToolExecutor(
        repository = repository,
        backgroundExecutor = executor,
        allowMutations = false,
    )

    val interactive: DynamicToolExecutor = GatedDynamicToolExecutor(
        delegate = NotificationFactDynamicToolExecutor(
            repository = repository,
            backgroundExecutor = executor,
            forget = forget,
            allowMutations = true,
            confirmationProvider = confirmation,
        ),
        denialCode = "background_notification_memory_mutation_forbidden",
        isAllowed = { call ->
            call.tool in setOf("query", "status") || isInteractive(call)
        },
    )
}
