package ai.hans.standard.setup

import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

sealed interface SetupUiCommand {
    val token: HansSetupOperationToken

    data class OpenSettings(
        override val token: HansSetupOperationToken,
        val optionalCapability: HansSetupOptionalCapability? = null,
    ) : SetupUiCommand

    data class BeginKeyCapture(
        override val token: HansSetupOperationToken,
        val inputChoice: HansSetupInputChoice,
    ) : SetupUiCommand

    data class ApplyCameraHoldChoice(
        override val token: HansSetupOperationToken,
        val enabled: Boolean,
    ) : SetupUiCommand

    data class ApplyModelSelection(
        override val token: HansSetupOperationToken,
        val model: String,
        val reasoningEffort: String,
    ) : SetupUiCommand

    data class ArmLiveTest(
        override val token: HansSetupOperationToken,
    ) : SetupUiCommand
}

sealed interface SetupUiCommandResult {
    data class Accepted(
        /**
         * True only after the requested public Android interaction was actually dispatched.
         * This may be a Settings screen, runtime-permission sheet or Quick Settings tile sheet;
         * [liveVerificationAccepted] remains the separate effective-state receipt.
         */
        val settingsOpened: Boolean = false,
        val liveVerificationAccepted: Boolean? = null,
    ) : SetupUiCommandResult

    data object UserInteractionRequired : SetupUiCommandResult

    data class Rejected(val code: String) : SetupUiCommandResult {
        init {
            requireSetupCode(code)
        }
    }
}

interface SetupUiCommandLease {
    /** False once the owning Activity, request deadline, or a replacement owner cancels the UI. */
    fun isActive(): Boolean

    /** Atomically wins this request and returns false when cancellation/deadline won first. */
    fun tryComplete(result: SetupUiCommandResult): Boolean
}

fun interface SetupUiCommandHandler {
    fun handle(
        command: SetupUiCommand,
        lease: SetupUiCommandLease,
        completion: (SetupUiCommandResult) -> Unit,
    )

    /** Best-effort teardown for Activity state owned by an expired command. */
    fun cancel(command: SetupUiCommand) = Unit
}

fun interface SetupUiCommandDeadline {
    fun cancel()
}

fun interface SetupUiCommandDeadlineScheduler {
    fun schedule(delayMillis: Long, task: () -> Unit): SetupUiCommandDeadline
}

/**
 * Activity-bound process seam. A headless turn fails explicitly instead of starting UI.
 *
 * Every dispatched command belongs to the Activity registration that received it. Losing or
 * replacing that owner completes all of its outstanding commands, and a one-shot deadline bounds
 * callbacks that Android never returns. Late platform callbacks are ignored exactly once.
 */
class SetupUiCommandRouter(
    private val deadlineScheduler: SetupUiCommandDeadlineScheduler =
        ProcessSetupUiCommandDeadlineScheduler,
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
) {
    init {
        require(requestTimeoutMillis in MIN_REQUEST_TIMEOUT_MILLIS..MAX_REQUEST_TIMEOUT_MILLIS)
    }

    private val lock = Any()
    private var current: Owner? = null
    private var nextRequestId = 1L

    fun request(
        command: SetupUiCommand,
        completion: (SetupUiCommandResult) -> Unit,
    ) {
        val request = synchronized(lock) {
            val owner = current ?: return@synchronized null
            check(nextRequestId < Long.MAX_VALUE) { "Setup UI request id exhausted" }
            PendingRequest(
                id = nextRequestId++,
                owner = owner,
                command = command,
                completion = completion,
            ).also { owner.pending[it.id] = it }
        }
        if (request == null) {
            completion(SetupUiCommandResult.UserInteractionRequired)
            return
        }

        val deadline = runCatching {
            deadlineScheduler.schedule(requestTimeoutMillis) {
                complete(
                    request,
                    SetupUiCommandResult.Rejected("setup_ui_request_timeout"),
                    cancelHandler = true,
                )
            }
        }.getOrElse {
            complete(request, SetupUiCommandResult.Rejected("setup_ui_dispatch_failed"))
            return
        }
        val dispatch = synchronized(lock) {
            if (request.owner.pending[request.id] === request) {
                request.deadline = deadline
                current === request.owner
            } else {
                false
            }
        }
        if (!dispatch) {
            runCatching(deadline::cancel)
            return
        }

        val lease = object : SetupUiCommandLease {
            override fun isActive(): Boolean = isActive(request)

            override fun tryComplete(result: SetupUiCommandResult): Boolean = complete(
                request,
                truthfulResult(request.command, result),
            )
        }
        runCatching {
            // Never invoke Activity or controller code while holding the router lock. A platform
            // callback may block, but the independent deadline must still be able to complete the
            // App Server request. The lease makes a concurrently queued UI dispatch fail closed.
            if (lease.isActive()) {
                request.owner.handler.handle(request.command, lease) { result ->
                    lease.tryComplete(result)
                }
            }
        }.onFailure {
            complete(
                request,
                SetupUiCommandResult.Rejected("setup_ui_dispatch_failed"),
                cancelHandler = true,
            )
        }
    }

    fun attach(handler: SetupUiCommandHandler): Closeable {
        val owner = Owner(handler)
        val replaced = synchronized(lock) {
            current.also { current = owner }
        }
        cancelOwner(replaced)
        return Closeable { detach(owner) }
    }

    fun clear() {
        val removed = synchronized(lock) {
            current.also { current = null }
        }
        cancelOwner(removed)
    }

    private fun detach(owner: Owner) {
        val removed = synchronized(lock) {
            if (current !== owner) return
            current = null
            owner
        }
        cancelOwner(removed)
    }

    private fun cancelOwner(owner: Owner?) {
        val pending = synchronized(lock) { owner?.pending?.values?.toList().orEmpty() }
        pending.forEach {
            complete(
                it,
                SetupUiCommandResult.UserInteractionRequired,
                cancelHandler = true,
            )
        }
    }

    private fun complete(
        request: PendingRequest,
        result: SetupUiCommandResult,
        cancelHandler: Boolean = false,
    ): Boolean {
        val deadline = synchronized(lock) {
            if (request.owner.pending.remove(request.id) !== request) return false
            request.deadline.also { request.deadline = null }
        }
        runCatching { deadline?.cancel() }
        // Completion releases the App Server turn. Best-effort Activity teardown must not delay
        // that bounded outcome if a platform implementation itself blocks.
        runCatching { request.completion(result) }
        if (cancelHandler) runCatching { request.owner.handler.cancel(request.command) }
        return true
    }

    private fun isActive(request: PendingRequest): Boolean = synchronized(lock) {
        current === request.owner && request.owner.pending[request.id] === request
    }

    private fun truthfulResult(
        command: SetupUiCommand,
        result: SetupUiCommandResult,
    ): SetupUiCommandResult = if (
        command is SetupUiCommand.OpenSettings &&
        result is SetupUiCommandResult.Accepted &&
        !result.settingsOpened &&
        result.liveVerificationAccepted != true
    ) {
        SetupUiCommandResult.Rejected("settings_ui_not_opened")
    } else {
        result
    }

    private class Owner(
        val handler: SetupUiCommandHandler,
        val pending: MutableMap<Long, PendingRequest> = linkedMapOf(),
    )

    private class PendingRequest(
        val id: Long,
        val owner: Owner,
        val command: SetupUiCommand,
        val completion: (SetupUiCommandResult) -> Unit,
        var deadline: SetupUiCommandDeadline? = null,
    )

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 120_000L
        private const val MIN_REQUEST_TIMEOUT_MILLIS = 1L
        private const val MAX_REQUEST_TIMEOUT_MILLIS = 5 * 60_000L
    }
}

internal object ProcessSetupUiCommandDeadlineScheduler : SetupUiCommandDeadlineScheduler {
    private val executor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "hans-setup-ui-deadline").apply { isDaemon = true }
    }.apply {
        removeOnCancelPolicy = true
    }
    private val deliveryExecutor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "hans-setup-ui-deadline-delivery").apply { isDaemon = true }
    }

    override fun schedule(delayMillis: Long, task: () -> Unit): SetupUiCommandDeadline {
        require(delayMillis >= 0)
        // The timer thread only releases work. A blocked Activity/controller completion cannot
        // serialize or postpone deadlines belonging to other setup requests.
        val future = executor.schedule(
            { deliveryExecutor.execute(task) },
            delayMillis,
            TimeUnit.MILLISECONDS,
        )
        return SetupUiCommandDeadline { future.cancel(false) }
    }
}
