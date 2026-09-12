package ai.hans.standard.plugins.install

import ai.hans.standard.plugins.BoundedPluginSourceIdentityHasher
import ai.hans.standard.plugins.PluginListWireResult
import ai.hans.standard.plugins.PluginSourceHashCancellation
import ai.hans.standard.plugins.PluginSourceIdentityHasher
import java.io.File

/** Proves one durable install identity against one fresh App Server plugin listing. */
internal fun interface PluginInstallRemoteProver {
    fun prove(
        identity: PluginInstallIdentity,
        listed: PluginListWireResult,
    ): PluginInstallRemoteProof
}

/**
 * Read-only, fail-closed proof of the exact plugin tree accepted by App Server.
 *
 * Display metadata is deliberately insufficient: an exact proof includes the opaque handle,
 * locator, installed version, canonical source root, and a freshly bounded digest of that root.
 */
internal class ExactPluginInstallRemoteProver(
    private val sourceIdentityHasher: PluginSourceIdentityHasher =
        BoundedPluginSourceIdentityHasher(),
) : PluginInstallRemoteProver {
    override fun prove(
        identity: PluginInstallIdentity,
        listed: PluginListWireResult,
    ): PluginInstallRemoteProof {
        if (listed.marketplaceLoadIssueCount != 0) {
            return PluginInstallRemoteProof.UNAVAILABLE
        }

        val installedCandidates = listed.records.filter { record ->
            record.card.installed &&
                (record.card.pluginId == identity.pluginId ||
                    record.locator.pluginId == identity.pluginId)
        }
        if (installedCandidates.isEmpty()) return PluginInstallRemoteProof.ABSENT
        if (installedCandidates.size != 1) return PluginInstallRemoteProof.CHANGED

        val record = installedCandidates.single()
        if (
            record.card.pluginId != identity.pluginId ||
            record.locator.pluginId != identity.pluginId ||
            record.card.handle.value != identity.pluginHandleSha256 ||
            record.locator.pluginName != identity.pluginName ||
            record.locator.marketplaceName != identity.marketplaceName ||
            record.locator.marketplacePath != identity.marketplacePath
        ) {
            return PluginInstallRemoteProof.CHANGED
        }

        val localVersion = record.localVersion?.takeIf(String::isNotBlank)
            ?: return PluginInstallRemoteProof.UNAVAILABLE
        if (localVersion != identity.expectedInstalledVersion) {
            return PluginInstallRemoteProof.CHANGED
        }

        val expectedRoot = canonicalAbsoluteRoot(identity.canonicalSourceRoot)
            ?: return PluginInstallRemoteProof.UNAVAILABLE
        if (expectedRoot.path != identity.canonicalSourceRoot) {
            return PluginInstallRemoteProof.UNAVAILABLE
        }
        val listedRoot = record.localSourcePath?.let(::canonicalAbsoluteRoot)
            ?: return PluginInstallRemoteProof.UNAVAILABLE
        if (listedRoot.path != expectedRoot.path) {
            return PluginInstallRemoteProof.CHANGED
        }

        val freshDigest = runCatching {
            sourceIdentityHasher.digest(listedRoot, PluginSourceHashCancellation.NONE)
        }.getOrNull()?.takeIf(SHA_256::matches)
            ?: return PluginInstallRemoteProof.UNAVAILABLE
        return if (freshDigest == identity.sourceSha256) {
            PluginInstallRemoteProof.EXACT
        } else {
            PluginInstallRemoteProof.CHANGED
        }
    }

    private fun canonicalAbsoluteRoot(rawPath: String): File? {
        if (rawPath.isBlank() || '\u0000' in rawPath) return null
        val raw = File(rawPath)
        if (!raw.isAbsolute) return null
        return runCatching { raw.canonicalFile }.getOrNull()
    }

    private companion object {
        val SHA_256 = Regex("[0-9a-f]{64}")
    }
}
