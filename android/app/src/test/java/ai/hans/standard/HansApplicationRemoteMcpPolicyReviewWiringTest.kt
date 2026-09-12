package ai.hans.standard

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Product-path guard for the exact, fail-closed Remote MCP policy review boundary. */
class HansApplicationRemoteMcpPolicyReviewWiringTest {
    @Test
    fun reviewUsesSharedPolicyAuthorityAndRefreshesOnlyTheExactRuntimeContract() {
        val source = source()
        val wiring = source.substringAfter("private val remoteMcpPolicyReviewCoordinator")
            .substringBefore("private val remoteMcpHttpCalls")

        assertTrue(wiring.contains("store = remoteMcpToolPolicyStore"))
        assertTrue(wiring.contains("remoteMcpToolPolicies.reloadFromStore()"))
        assertTrue(wiring.contains("remoteMcpSessionRegistry.closeSessionIfPresent(identity)"))
        assertTrue(wiring.contains("refreshFinalizedPluginToolContract()"))
    }

    @Test
    fun approvalReresolvesSourceManifestRequirementAndFreshCatalogOffMain() {
        val source = source()
        val approval = approvalBody(source)

        assertTrue(approval.contains("Remote MCP policy review must run off-main"))
        assertTrue(approval.contains("resolveRemoteMcpPolicyReviewTarget("))
        assertTrue(approval.contains("PluginRuntimeManifestLoader(privatePluginSourceRoots)"))
        assertTrue(approval.contains("remoteMcpRequirements.load("))
        assertTrue(approval.contains("RemoteMcpConfigurationIdentity.digest(requirement)"))
        assertTrue(approval.contains("SessionRemoteMcpPreflightDiscoverer(remoteMcpSessionFactory)"))
        assertTrue(approval.contains("RemoteMcpPolicyReviewCatalog.fromDiscovery("))
        assertTrue(approval.contains("catalog.request(target.request.policyStoreRevision) != target.request"))
        assertTrue(approval.contains("currentSourceDigest() != target.sourceSha256"))
        assertTrue(approval.windowed("resolveRemoteMcpPolicyReviewTarget(".length)
            .count { it == "resolveRemoteMcpPolicyReviewTarget(" } >= 2)
    }

    @Test
    fun mutatingPolicyIsOnlyServerConfirmedAndApprovalNeverRetriesInstallation() {
        val source = source()
        val approval = approvalBody(source)

        assertTrue(approval.contains("RemoteMcpBuiltInVerifiers.SERVER_ACKNOWLEDGED_V1"))
        assertTrue(approval.contains("PluginRemoteMcpPolicyEffect.READ_ONLY"))
        assertTrue(approval.contains("else {\n                        null"))
        assertTrue(approval.contains("remoteMcpPolicyReviewCoordinator.approve("))
        assertTrue(approval.contains("reconcileCommittedApproval("))
        assertTrue(approval.contains("mcp_policy_review_runtime_refresh_failed"))
        assertTrue(approval.contains("sessionHost.markRemoteMcpPolicyReviewed(target)"))
        assertTrue(
            approval.indexOf("remoteMcpPolicyReviewCoordinator.approve(") <
                approval.indexOf("sessionHost.markRemoteMcpPolicyReviewed(target)"),
        )
        assertFalse(approval.contains("installPlugin("))
        assertFalse(approval.contains("retryRemoteMcpPluginInstall("))
        assertFalse(approval.contains("pluginInstallTransactions.prepare("))
    }

    private fun approvalBody(source: String): String =
        source.substringAfter("internal fun approveRemoteMcpPolicyReview(")
            .substringBefore("private val compositePluginDependencyPreparer")

    private fun source(): String = listOf(
        File("src/main/java/ai/hans/standard/HansApplication.kt"),
        File("android/app/src/main/java/ai/hans/standard/HansApplication.kt"),
    ).first(File::isFile).readText()
}
