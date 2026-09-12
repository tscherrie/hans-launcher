package ai.hans.standard.plugins.install

import ai.hans.standard.plugins.BoundedPluginSourceIdentityHasher
import ai.hans.standard.plugins.PluginAuthPolicy
import ai.hans.standard.plugins.PluginAvailability
import ai.hans.standard.plugins.PluginCard
import ai.hans.standard.plugins.PluginDisabledReason
import ai.hans.standard.plugins.PluginHandle
import ai.hans.standard.plugins.PluginInstallPolicy
import ai.hans.standard.plugins.PluginListWireResult
import ai.hans.standard.plugins.PluginLocator
import ai.hans.standard.plugins.PluginSourceHashCancellation
import ai.hans.standard.plugins.PluginSourceIdentityHasher
import ai.hans.standard.plugins.PluginSourceKind
import ai.hans.standard.plugins.PluginWireRecord
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class PluginInstallRemoteProverTest {
    @Test
    fun exactRequiresOneFullyMatchingInstalledRecordAndFreshSourceDigest() {
        withFixture { fixture ->
            assertEquals(
                PluginInstallRemoteProof.EXACT,
                ExactPluginInstallRemoteProver().prove(
                    fixture.identity,
                    listing(fixture.installedRecord),
                ),
            )
        }
    }

    @Test
    fun cleanAbsenceIgnoresUninstalledAndUnrelatedRecords() {
        withFixture { fixture ->
            val uninstalled = fixture.installedRecord.copy(
                card = fixture.installedRecord.card.copy(installed = false),
            )
            val unrelated = record(
                pluginId = "unrelated",
                sourceRoot = fixture.sourceRoot,
                installed = true,
            )

            assertEquals(
                PluginInstallRemoteProof.ABSENT,
                ExactPluginInstallRemoteProver().prove(
                    fixture.identity,
                    listing(uninstalled, unrelated),
                ),
            )
        }
    }

    @Test
    fun loadIssuesMakePresentAndAbsentEvidenceUnavailable() {
        withFixture { fixture ->
            val prover = ExactPluginInstallRemoteProver()
            assertEquals(
                PluginInstallRemoteProof.UNAVAILABLE,
                prover.prove(fixture.identity, listing(fixture.installedRecord, issueCount = 1)),
            )
            assertEquals(
                PluginInstallRemoteProof.UNAVAILABLE,
                prover.prove(fixture.identity, listing(issueCount = 1)),
            )
            assertEquals(
                PluginInstallRemoteProof.UNAVAILABLE,
                prover.prove(fixture.identity, listing(issueCount = -1)),
            )
        }
    }

    @Test
    fun duplicateInstalledIdentityIsChangedEvenWhenOneRecordIsExact() {
        withFixture { fixture ->
            assertEquals(
                PluginInstallRemoteProof.CHANGED,
                ExactPluginInstallRemoteProver().prove(
                    fixture.identity,
                    listing(
                        fixture.installedRecord,
                        fixture.installedRecord.copy(
                            localVersion = "2.0.0",
                        ),
                    ),
                ),
            )
        }
    }

    @Test
    fun everyKnownIdentityDeviationIsChanged() {
        withFixture { fixture ->
            val exact = fixture.installedRecord
            val deviations = listOf(
                exact.copy(card = exact.card.copy(pluginId = "other")),
                exact.copy(locator = exact.locator.copy(pluginId = "other")),
                exact.copy(card = exact.card.copy(handle = PluginHandle("b".repeat(64)))),
                exact.copy(locator = exact.locator.copy(pluginName = "renamed")),
                exact.copy(locator = exact.locator.copy(marketplaceName = "other-market")),
                exact.copy(locator = exact.locator.copy(marketplacePath = "/other/market.json")),
                exact.copy(localVersion = "2.0.0"),
                exact.copy(localSourcePath = fixture.sourceRoot.resolve("other").absolutePath),
            )

            deviations.forEach { changed ->
                assertEquals(
                    PluginInstallRemoteProof.CHANGED,
                    ExactPluginInstallRemoteProver().prove(
                        fixture.identity,
                        listing(changed),
                    ),
                )
            }
        }
    }

    @Test
    fun incompleteInstalledEvidenceIsUnavailable() {
        withFixture { fixture ->
            val exact = fixture.installedRecord
            val incomplete = listOf(
                exact.copy(localVersion = null),
                exact.copy(localVersion = ""),
                exact.copy(localSourcePath = null),
                exact.copy(localSourcePath = "relative/plugin"),
            )

            incomplete.forEach { record ->
                assertEquals(
                    PluginInstallRemoteProof.UNAVAILABLE,
                    ExactPluginInstallRemoteProver().prove(
                        fixture.identity,
                        listing(record),
                    ),
                )
            }
        }
    }

    @Test
    fun changedSourceBytesAreChanged() {
        withFixture { fixture ->
            fixture.sourceRoot.resolve("content.txt").writeText("changed after install")

            assertEquals(
                PluginInstallRemoteProof.CHANGED,
                ExactPluginInstallRemoteProver().prove(
                    fixture.identity,
                    listing(fixture.installedRecord),
                ),
            )
        }
    }

    @Test
    fun filesystemAndHasherFailuresAreUnavailable() {
        withFixture { fixture ->
            val throwing = ExactPluginInstallRemoteProver(
                PluginSourceIdentityHasher { _, _ -> error("filesystem unavailable") },
            )
            val malformed = ExactPluginInstallRemoteProver(
                PluginSourceIdentityHasher { _, _ -> "not-a-sha" },
            )

            assertEquals(
                PluginInstallRemoteProof.UNAVAILABLE,
                throwing.prove(fixture.identity, listing(fixture.installedRecord)),
            )
            assertEquals(
                PluginInstallRemoteProof.UNAVAILABLE,
                malformed.prove(fixture.identity, listing(fixture.installedRecord)),
            )

            fixture.sourceRoot.deleteRecursively()
            assertEquals(
                PluginInstallRemoteProof.UNAVAILABLE,
                ExactPluginInstallRemoteProver().prove(
                    fixture.identity,
                    listing(fixture.installedRecord),
                ),
            )
        }
    }

    @Test
    fun canonicalAliasesResolveToTheSameProvenRoot() {
        withFixture { fixture ->
            val aliased = fixture.installedRecord.copy(
                localSourcePath = fixture.sourceRoot.resolve(".").path,
            )
            assertEquals(
                PluginInstallRemoteProof.EXACT,
                ExactPluginInstallRemoteProver().prove(
                    fixture.identity,
                    listing(aliased),
                ),
            )
        }
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val sourceRoot = Files.createTempDirectory("plugin-remote-proof").toFile()
        try {
            sourceRoot.resolve("content.txt").writeText("installed source")
            val digest = BoundedPluginSourceIdentityHasher().digest(
                sourceRoot,
                PluginSourceHashCancellation.NONE,
            )
            val installedRecord = record(
                pluginId = PLUGIN_ID,
                sourceRoot = sourceRoot,
                installed = true,
            )
            val identity = PluginInstallIdentity(
                pluginId = PLUGIN_ID,
                pluginHandleSha256 = HANDLE_SHA,
                pluginName = PLUGIN_NAME,
                marketplaceName = MARKETPLACE_NAME,
                marketplacePath = MARKETPLACE_PATH,
                expectedInstalledVersion = VERSION,
                canonicalSourceRoot = sourceRoot.canonicalPath,
                sourceSha256 = digest,
            )
            block(Fixture(sourceRoot, installedRecord, identity))
        } finally {
            sourceRoot.deleteRecursively()
        }
    }

    private fun record(
        pluginId: String,
        sourceRoot: File,
        installed: Boolean,
    ): PluginWireRecord = PluginWireRecord(
        locator = PluginLocator(
            pluginId = pluginId,
            pluginName = if (pluginId == PLUGIN_ID) PLUGIN_NAME else pluginId,
            marketplaceName = MARKETPLACE_NAME,
            marketplacePath = MARKETPLACE_PATH,
        ),
        card = PluginCard(
            handle = if (pluginId == PLUGIN_ID) {
                PluginHandle(HANDLE_SHA)
            } else {
                PluginHandle("c".repeat(64))
            },
            pluginId = pluginId,
            name = if (pluginId == PLUGIN_ID) PLUGIN_NAME else pluginId,
            displayName = null,
            shortDescription = null,
            marketplaceDisplayName = "Test marketplace",
            installed = installed,
            enabled = true,
            availability = PluginAvailability.AVAILABLE,
            disabledReason = null as PluginDisabledReason?,
            installPolicy = PluginInstallPolicy.AVAILABLE,
            authPolicy = PluginAuthPolicy.ON_USE,
            sourceKind = PluginSourceKind.LOCAL,
            logoUrl = null,
            logoDarkUrl = null,
            capabilities = emptyList(),
            featured = false,
        ),
        availableVersion = VERSION,
        localVersion = if (installed) VERSION else null,
        localSourcePath = sourceRoot.absolutePath,
    )

    private fun listing(
        vararg records: PluginWireRecord,
        issueCount: Int = 0,
    ) = PluginListWireResult(
        records = records.toList(),
        featuredPluginIds = emptySet(),
        marketplaceLoadIssueCount = issueCount,
    )

    private data class Fixture(
        val sourceRoot: File,
        val installedRecord: PluginWireRecord,
        val identity: PluginInstallIdentity,
    )

    private companion object {
        const val PLUGIN_ID = "sample-plugin"
        const val PLUGIN_NAME = "sample"
        const val MARKETPLACE_NAME = "local"
        const val MARKETPLACE_PATH = "/private/marketplace.json"
        const val VERSION = "1.0.0"
        val HANDLE_SHA = "a".repeat(64)
    }
}
