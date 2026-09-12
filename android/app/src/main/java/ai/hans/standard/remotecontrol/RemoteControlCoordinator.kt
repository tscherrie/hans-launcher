package ai.hans.standard.remotecontrol

import java.util.UUID
import org.json.JSONObject

/**
 * Pure, single-owner coordinator for the existing App Server connection. The owning integration
 * serializes all calls; no thread, timer, listener socket, runtime process, or persistent setting
 * is created here. UI must call the explicitly named local-user methods, never model tools.
 */
class RemoteControlCoordinator(
    private val sendRequest: (RemoteControlRequest) -> Boolean,
    private val onChanged: (RemoteControlSnapshot) -> Unit = {},
    private val newRequestId: () -> String = { "hans_remote_${UUID.randomUUID()}" },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    @Volatile var snapshot: RemoteControlSnapshot = RemoteControlSnapshot()
        private set
    private var pending: Pending? = null
    private val ownedRequestGenerations = linkedMapOf<String, Long>()

    fun onRuntimeReady(generation: Long) {
        require(generation >= 0)
        if (snapshot.runtimeReady && snapshot.generation == generation) return
        pending = null
        update(RemoteControlSnapshot(generation = generation, runtimeReady = true))
        refreshStatus() // Capability probe only. Never restore or implicitly enable access.
    }

    fun onRuntimeUnavailable() {
        pending = null
        update(snapshot.copy(runtimeReady = false, statusConfirmedForCurrentRuntime = false,
            localConsentGranted = false, pendingOperation = null, pairing = null,
            pairingClaimed = false, issue = RemoteControlIssue.TRANSPORT_UNAVAILABLE))
    }

    fun refreshStatus(): Boolean = submit(RemoteControlOperation.STATUS) { RemoteControlProtocol.status(it) }

    fun enableFromLocalUserConsent(): Boolean {
        if (!supportedAndIdle()) return false
        update(snapshot.copy(localConsentGranted = true, issue = null))
        return submit(RemoteControlOperation.ENABLE) { RemoteControlProtocol.enable(it) }
    }

    fun disableFromLocalUserAction(): Boolean {
        // Stop local tool authority immediately, but do NOT falsely report the server as off.
        // Disabling preempts an in-flight enable/pair; their eventual replies cannot re-enable UI.
        pending = null
        update(snapshot.copy(localConsentGranted = false, pairing = null, pairingClaimed = false,
            pendingOperation = null, issue = null))
        return submit(RemoteControlOperation.DISABLE) { RemoteControlProtocol.disable(it) }
    }

    fun startPairingFromLocalUserAction(): Boolean {
        onDeadline()
        if (!supportedAndIdle()) return false
        if (!snapshot.localConsentGranted || !snapshot.isEnabledConfirmed) {
            update(snapshot.copy(issue = RemoteControlIssue.CONSENT_REQUIRED))
            return false
        }
        update(snapshot.copy(pairing = null, pairingClaimed = false))
        return submit(RemoteControlOperation.PAIR, environmentId = snapshot.connection?.environmentId) {
            RemoteControlProtocol.pair(it)
        }
    }

    fun refreshPairingStatus(): Boolean {
        onDeadline()
        if (!supportedAndIdle()) return false
        val pairing = snapshot.pairing ?: return false
        return submit(RemoteControlOperation.PAIR_STATUS, environmentId = pairing.environmentId) {
            RemoteControlProtocol.pairingStatus(it, pairing)
        }
    }

    fun refreshClients(): Boolean {
        if (!supportedAndIdle()) return false
        val environment = snapshot.connection?.environmentId ?: return false
        return submit(RemoteControlOperation.CLIENTS, environmentId = environment) {
            RemoteControlProtocol.clients(it, environment)
        }
    }

    fun loadMoreClients(): Boolean {
        if (!supportedAndIdle()) return false
        val environment = snapshot.connection?.environmentId ?: return false
        val cursor = snapshot.nextClientCursor ?: return false
        if (snapshot.clients.size >= MAX_CLIENTS) return false
        return submit(RemoteControlOperation.CLIENTS, environmentId = environment, cursor = cursor) {
            RemoteControlProtocol.clients(it, environment, cursor)
        }
    }

    fun revokeClientFromLocalUserAction(clientId: String): Boolean {
        if (!supportedAndIdle() || snapshot.clients.none { it.clientId == clientId }) return false
        val environment = snapshot.connection?.environmentId ?: return false
        val priorConsent = snapshot.localConsentGranted
        update(snapshot.copy(localConsentGranted = false, pairing = null))
        return submit(RemoteControlOperation.REVOKE, environmentId = environment, clientId = clientId,
            restoreConsentAfterRevoke = priorConsent) { RemoteControlProtocol.revoke(it, environment, clientId) }
    }

    /** Returns false for another subsystem's response; raw provider errors never enter UI state. */
    fun onRpcResponse(generation: Long, rawJson: String): Boolean {
        val envelope = runCatching { RemoteControlProtocol.envelope(rawJson) }.getOrNull() ?: return false
        val id = envelope.opt("id") as? String ?: return false
        if (ownedRequestGenerations[id] != generation) return false
        // Also consume late/duplicate/preempted replies; the core correlator never owned these IDs.
        val request = pending ?: return true
        if (!snapshot.runtimeReady || generation != request.generation || generation != snapshot.generation ||
            id != request.request.id) return true
        pending = null
        if (nowMillis() >= request.deadlineAtMillis) {
            fail(request, RemoteControlIssue.TIMED_OUT)
            return true
        }
        try {
            require(envelope.has("result") != envelope.has("error"))
            if (envelope.has("error")) {
                fail(request, RemoteControlProtocol.issue(envelope.getJSONObject("error")))
                return true
            }
            val result = envelope.getJSONObject("result")
            when (request.operation) {
                RemoteControlOperation.STATUS, RemoteControlOperation.ENABLE, RemoteControlOperation.DISABLE -> {
                    val connection = RemoteControlProtocol.connection(result)
                    if (request.operation == RemoteControlOperation.ENABLE) {
                        // This still-correlated UI request carries consent even if an older
                        // disabled notification raced ahead of the enable response.
                        snapshot = snapshot.copy(localConsentGranted = true)
                    }
                    acceptConnection(connection)
                    val unexpected = when (request.operation) {
                        RemoteControlOperation.ENABLE -> connection.status !in setOf(RemoteControlStatus.CONNECTING, RemoteControlStatus.CONNECTED)
                        RemoteControlOperation.DISABLE -> connection.status != RemoteControlStatus.DISABLED
                        else -> false
                    }
                    update(snapshot.copy(pendingOperation = null,
                        issue = RemoteControlIssue.UNEXPECTED_STATUS.takeIf { unexpected }))
                }
                RemoteControlOperation.PAIR -> {
                    val pairing = RemoteControlProtocol.pairing(result, nowMillis())
                    require(request.environmentId == null || pairing.environmentId == request.environmentId)
                    require(snapshot.localConsentGranted && snapshot.isEnabledConfirmed)
                    update(snapshot.copy(pendingOperation = null, pairing = pairing, pairingClaimed = false,
                        connection = snapshot.connection?.copy(environmentId = pairing.environmentId), issue = null))
                }
                RemoteControlOperation.PAIR_STATUS -> {
                    require(result.get("claimed") is Boolean)
                    val claimed = result.getBoolean("claimed")
                    val current = snapshot.pairing
                    require(current != null && current.environmentId == request.environmentId)
                    update(snapshot.copy(pendingOperation = null, pairingClaimed = claimed,
                        pairing = current.takeUnless { claimed }, issue = null))
                }
                RemoteControlOperation.CLIENTS -> {
                    require(request.environmentId == snapshot.connection?.environmentId)
                    val (page, cursor) = RemoteControlProtocol.clients(result)
                    require(cursor == null || cursor != request.cursor)
                    val clients = if (request.cursor == null) page else (snapshot.clients + page).distinctBy { it.clientId }
                    require(clients.size <= MAX_CLIENTS)
                    update(snapshot.copy(pendingOperation = null, clients = clients.toList(), nextClientCursor = cursor,
                        clientsComplete = cursor == null, issue = null))
                }
                RemoteControlOperation.REVOKE -> {
                    require(request.environmentId == snapshot.connection?.environmentId)
                    update(snapshot.copy(pendingOperation = null,
                        clients = snapshot.clients.filterNot { it.clientId == request.clientId },
                        localConsentGranted = request.restoreConsentAfterRevoke && snapshot.isEnabledConfirmed,
                        issue = null))
                }
            }
        } catch (_: Exception) {
            fail(request, RemoteControlIssue.MALFORMED_RESPONSE)
        }
        return true
    }

    fun onStatusNotification(generation: Long, rawJson: String): Boolean {
        val envelope = runCatching { RemoteControlProtocol.envelope(rawJson) }.getOrNull() ?: return false
        if (envelope.optString("method") != RemoteControlProtocol.STATUS_NOTIFICATION) return false
        // Native optional status can precede the first local Settings probe. It is not a
        // bootstrap failure and cannot grant consent or establish a current runtime by itself.
        if (!snapshot.runtimeReady || generation != snapshot.generation) return true
        try {
            require(!envelope.has("id"))
            acceptConnection(RemoteControlProtocol.connection(envelope.getJSONObject("params")))
        } catch (_: Exception) {
            update(snapshot.copy(statusConfirmedForCurrentRuntime = false, localConsentGranted = false,
                pairing = null, issue = RemoteControlIssue.MALFORMED_RESPONSE))
        }
        return true
    }

    /** Owner schedules one shot at snapshot.nextDeadlineAtMillis; no polling is needed. */
    fun onDeadline() {
        val now = nowMillis()
        val active = pending
        if (active != null && now >= active.deadlineAtMillis) {
            pending = null
            fail(active, RemoteControlIssue.TIMED_OUT)
        }
        if (snapshot.pairing?.let { now >= it.expiresAtMillis } == true) {
            if (pending?.operation == RemoteControlOperation.PAIR_STATUS) pending = null
            update(snapshot.copy(pairing = null, pairingClaimed = false,
                pendingOperation = pending?.operation, issue = RemoteControlIssue.PAIRING_EXPIRED))
        }
    }

    private fun supportedAndIdle(): Boolean {
        if (!snapshot.runtimeReady || snapshot.capability != RemoteControlCapability.SUPPORTED || pending != null) return false
        return true
    }

    private fun submit(operation: RemoteControlOperation, environmentId: String? = null,
        clientId: String? = null, cursor: String? = null, restoreConsentAfterRevoke: Boolean = false,
        build: (String) -> RemoteControlRequest): Boolean {
        if (!snapshot.runtimeReady || pending != null) return false
        val generation = snapshot.generation ?: return false
        val wire = runCatching { build(newRequestId()) }.getOrNull() ?: run {
            update(snapshot.copy(issue = RemoteControlIssue.TRANSPORT_UNAVAILABLE, localConsentGranted = false))
            return false
        }
        val request = Pending(wire, generation, operation, nowMillis() + wire.timeoutMillis,
            environmentId, clientId, cursor, restoreConsentAfterRevoke)
        ownedRequestGenerations[wire.id] = generation
        while (ownedRequestGenerations.size > 256) ownedRequestGenerations.remove(ownedRequestGenerations.keys.first())
        pending = request
        update(snapshot.copy(pendingOperation = operation, issue = null))
        if (runCatching { sendRequest(wire) }.getOrDefault(false)) return true
        if (pending === request) { pending = null; fail(request, RemoteControlIssue.TRANSPORT_UNAVAILABLE) }
        return false
    }

    private fun acceptConnection(connection: RemoteControlConnection) {
        val changedEnvironment = snapshot.connection?.environmentId != connection.environmentId
        val disabled = connection.status == RemoteControlStatus.DISABLED
        update(snapshot.copy(connection = connection, capability = RemoteControlCapability.SUPPORTED,
            statusConfirmedForCurrentRuntime = true,
            localConsentGranted = snapshot.localConsentGranted && !disabled,
            pairing = snapshot.pairing.takeUnless { disabled || changedEnvironment },
            clients = snapshot.clients.takeUnless { changedEnvironment } ?: emptyList(),
            nextClientCursor = snapshot.nextClientCursor.takeUnless { changedEnvironment },
            clientsComplete = snapshot.clientsComplete && !changedEnvironment,
            issue = null))
    }

    private fun fail(request: Pending, issue: RemoteControlIssue) {
        val changesAccess = request.operation in setOf(RemoteControlOperation.ENABLE, RemoteControlOperation.DISABLE)
        val capability = when {
            issue == RemoteControlIssue.UNSUPPORTED -> RemoteControlCapability.UNSUPPORTED
            issue in setOf(RemoteControlIssue.AUTH_REQUIRED, RemoteControlIssue.POLICY_BLOCKED, RemoteControlIssue.UNAVAILABLE) -> RemoteControlCapability.UNAVAILABLE
            request.operation == RemoteControlOperation.STATUS -> RemoteControlCapability.UNAVAILABLE
            else -> snapshot.capability
        }
        update(snapshot.copy(pendingOperation = null, capability = capability, issue = issue,
            statusConfirmedForCurrentRuntime = snapshot.statusConfirmedForCurrentRuntime && !changesAccess && request.operation != RemoteControlOperation.STATUS,
            localConsentGranted = snapshot.localConsentGranted && !changesAccess && request.operation != RemoteControlOperation.REVOKE && capability == RemoteControlCapability.SUPPORTED,
            pairing = snapshot.pairing.takeUnless { changesAccess || request.operation == RemoteControlOperation.PAIR }))
    }

    private fun update(value: RemoteControlSnapshot) {
        snapshot = value.copy(nextDeadlineAtMillis = listOfNotNull(pending?.deadlineAtMillis,
            value.pairing?.expiresAtMillis).minOrNull())
        runCatching { onChanged(snapshot) }
    }

    private data class Pending(val request: RemoteControlRequest, val generation: Long,
        val operation: RemoteControlOperation, val deadlineAtMillis: Long,
        val environmentId: String?, val clientId: String?, val cursor: String?,
        val restoreConsentAfterRevoke: Boolean)

    companion object { const val MAX_CLIENTS = 500 }
}
