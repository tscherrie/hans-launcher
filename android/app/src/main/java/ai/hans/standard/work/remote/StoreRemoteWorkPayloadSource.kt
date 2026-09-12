package ai.hans.standard.work.remote

import ai.hans.standard.artifacts.AtomicArtifactStore
import ai.hans.standard.workspace.PrivateWorkspaceStore
import java.io.InputStream

/**
 * Read-only bridge from Hans' immutable stores to the remote transport. Raw app-private paths
 * never cross this boundary; every lease is reopened through an opaque handle and reverified by
 * the owning store.
 */
internal class StoreRemoteWorkPayloadSource(
    request: RemoteWorkRequest,
    private val workspaceStore: PrivateWorkspaceStore,
    private val artifactStore: AtomicArtifactStore,
) : RemoteWorkPayloadSource {
    private val frozenRequest = request.copy(
        inputArtifacts = request.inputArtifacts.toList(),
        limits = request.limits.copy(),
    )
    private val frozenDescriptors: List<RemoteWorkPayloadDescriptor>
    private val allowed: Set<RemoteWorkPayloadDescriptor>

    init {
        val collected = buildList {
            request.workspace?.let { workspace ->
                val snapshot = workspaceStore.snapshot(workspace)
                snapshot.manifest.files.forEach { entry ->
                    add(
                        RemoteWorkPayloadDescriptor(
                            kind = RemoteWorkPayloadKind.WORKSPACE_FILE,
                            sha256 = entry.sha256,
                            byteCount = entry.byteCount,
                            workspace = workspace,
                            relativePath = entry.relativePath,
                        ),
                    )
                }
            }
            request.inputArtifacts.forEach { handle ->
                val metadata = artifactStore.metadata(handle)
                add(
                    RemoteWorkPayloadDescriptor(
                        kind = RemoteWorkPayloadKind.ARTIFACT,
                        sha256 = metadata.sha256,
                        byteCount = metadata.byteCount,
                        artifact = handle,
                    ),
                )
            }
        }
        require(collected.size <= MAX_PAYLOADS) { "Too many remote work payloads" }
        val totalBytes = collected.fold(0L) { total, descriptor ->
            Math.addExact(total, descriptor.byteCount)
        }
        require(totalBytes <= MAX_PAYLOAD_BYTES) { "Remote work payload byte limit exceeded" }
        require(collected.distinct().size == collected.size) { "Duplicate remote work payload" }
        frozenDescriptors = collected.toList()
        allowed = frozenDescriptors.toSet()
    }

    override fun descriptors(request: RemoteWorkRequest): List<RemoteWorkPayloadDescriptor> {
        require(request == frozenRequest) { "Remote work payload request mismatch" }
        return frozenDescriptors.toList()
    }

    override fun open(descriptor: RemoteWorkPayloadDescriptor): RemoteWorkPayloadLease {
        require(descriptor in allowed) { "Unknown remote work payload descriptor" }
        val input = when (descriptor.kind) {
            RemoteWorkPayloadKind.WORKSPACE_FILE -> workspaceStore.openFile(
                requireNotNull(descriptor.workspace),
                requireNotNull(descriptor.relativePath),
            )
            RemoteWorkPayloadKind.ARTIFACT -> artifactStore.open(requireNotNull(descriptor.artifact))
        }
        return InputLease(descriptor, input)
    }

    private class InputLease(
        override val descriptor: RemoteWorkPayloadDescriptor,
        override val input: InputStream,
    ) : RemoteWorkPayloadLease {
        override fun close() = input.close()
    }

    private companion object {
        const val MAX_PAYLOADS = 10_000
        const val MAX_PAYLOAD_BYTES = RemoteWorkLimits.MAX_OUTPUT_BYTES
    }
}
