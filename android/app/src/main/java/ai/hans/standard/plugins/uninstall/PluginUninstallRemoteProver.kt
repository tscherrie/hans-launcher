package ai.hans.standard.plugins.uninstall

import ai.hans.standard.plugins.PluginListWireResult
import ai.hans.standard.plugins.PluginSourceHashCancellation
import ai.hans.standard.plugins.PluginSourceIdentityHasher
import ai.hans.standard.plugins.PluginWireRecord
import java.io.File

internal enum class PluginUninstallRemoteProof {
    /** A fresh complete catalog contains no installed row with the exact plugin id. */
    ABSENT,
    /** The exact pre-uninstall target is still installed; retry is safe and local state stays live. */
    PRESENT_EXACT,
    /** The id now resolves to another source/version/handle or to conflicting duplicate rows. */
    CONFLICT,
    /** Marketplace load failures or unreadable source evidence make absence unprovable. */
    UNKNOWN,
}

internal fun interface PluginUninstallRemoteProver {
    fun prove(
        target: PluginUninstallTargetIdentity,
        listed: PluginListWireResult,
    ): PluginUninstallRemoteProof
}

internal class ExactPluginUninstallRemoteProver(
    private val sourceHasher: PluginSourceIdentityHasher,
) : PluginUninstallRemoteProver {
    override fun prove(
        target: PluginUninstallTargetIdentity,
        listed: PluginListWireResult,
    ): PluginUninstallRemoteProof {
        if (listed.marketplaceLoadIssueCount != 0) return PluginUninstallRemoteProof.UNKNOWN
        val installed = listed.records.filter {
            it.card.installed && it.card.pluginId == target.pluginId
        }
        if (installed.isEmpty()) return PluginUninstallRemoteProof.ABSENT
        if (installed.size != 1) return PluginUninstallRemoteProof.CONFLICT
        return when (matchesTarget(installed.single(), target)) {
            Match.EXACT -> PluginUninstallRemoteProof.PRESENT_EXACT
            Match.CHANGED -> PluginUninstallRemoteProof.CONFLICT
            Match.UNAVAILABLE -> PluginUninstallRemoteProof.UNKNOWN
        }
    }

    private fun matchesTarget(
        record: PluginWireRecord,
        target: PluginUninstallTargetIdentity,
    ): Match {
        if (
            record.locator.pluginId != target.pluginId ||
            record.locator.pluginName != target.pluginName ||
            record.locator.marketplaceName != target.marketplaceName ||
            record.locator.marketplacePath != target.marketplacePath ||
            record.card.handle.value != target.pluginHandleSha256 ||
            record.localVersion != target.installedVersion
        ) {
            return Match.CHANGED
        }
        val expectedRoot = target.canonicalSourceRoot
        val actualRaw = record.localSourcePath
        if (expectedRoot == null || actualRaw == null) {
            return if (expectedRoot == null && actualRaw == null) Match.EXACT else Match.CHANGED
        }
        val actualRoot = runCatching { File(actualRaw).canonicalFile }.getOrNull()
            ?: return Match.UNAVAILABLE
        if (actualRoot.path != expectedRoot) return Match.CHANGED
        val digest = runCatching {
            sourceHasher.digest(actualRoot, PluginSourceHashCancellation.NONE)
        }.getOrNull() ?: return Match.UNAVAILABLE
        return if (digest == target.sourceSha256) Match.EXACT else Match.CHANGED
    }

    private enum class Match { EXACT, CHANGED, UNAVAILABLE }
}
