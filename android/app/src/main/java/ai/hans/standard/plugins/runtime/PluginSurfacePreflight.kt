package ai.hans.standard.plugins.runtime

import ai.hans.standard.codex.DynamicToolExecutor
import ai.hans.standard.plugins.PluginDetailSnapshot
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

internal sealed interface PluginSurfacePreflightResult {
    /** Ordinary Codex plugin: preserve the existing App Server install path exactly. */
    data object NotDeclared : PluginSurfacePreflightResult

    data class Ready(
        val manifest: PluginSurfaceManifest,
        val inventory: PluginSurfaceInventorySnapshot,
        val dynamicToolExecutors: List<DynamicToolExecutor>,
        val nativeEvidence: CodexNativeSurfaceEvidence,
        val androidBindingEvidence: List<AndroidDynamicToolBindingSnapshot>,
        val hookBindingEvidence: List<HansDeclarativeHookBindingSnapshot>,
        /** Canonical identity of every declarative and signed-APK entrypoint proof. */
        val stateSha256: String,
    ) : PluginSurfacePreflightResult

    data class Rejected(val reason: String) : PluginSurfacePreflightResult
}

/**
 * Passive adapter for the installation coordinator. It consumes fresh plugin/read detail and
 * signed-APK capability evidence; Remote MCP is validated declaratively by its separate component.
 * This preflight performs no network request and starts no runtime.
 */
internal class PluginSurfacePreflight(
    private val manifests: PluginSurfaceManifestLoader,
    private val androidTools: AndroidDynamicToolEntrypointRegistry,
    private val hooks: HansDeclarativeHookRegistry,
    private val inventory: PluginSurfaceInventoryProjector,
) {
    internal fun declaration(
        pluginId: String,
        sourceRoot: File,
    ): PluginSurfaceManifestLoadResult = manifests.load(pluginId, sourceRoot)

    fun evaluate(
        pluginId: String,
        sourceRoot: File,
        freshPluginDetail: PluginDetailSnapshot,
    ): PluginSurfacePreflightResult = evaluate(
        pluginId = pluginId,
        sourceRoot = sourceRoot,
        nativeEvidence = freshPluginDetail.toCodexNativeSurfaceEvidence(),
    )

    internal fun evaluate(
        pluginId: String,
        sourceRoot: File,
        nativeEvidence: CodexNativeSurfaceEvidence,
    ): PluginSurfacePreflightResult = when (val loaded = manifests.load(pluginId, sourceRoot)) {
        PluginSurfaceManifestLoadResult.NotDeclared -> PluginSurfacePreflightResult.NotDeclared
        is PluginSurfaceManifestLoadResult.Rejected -> PluginSurfacePreflightResult.Rejected(loaded.reason)
        is PluginSurfaceManifestLoadResult.Declared -> evaluateDeclared(
            manifest = loaded.manifest,
            nativeEvidence = nativeEvidence,
        )
    }

    internal fun evaluateDeclared(
        manifest: PluginSurfaceManifest,
        nativeEvidence: CodexNativeSurfaceEvidence,
    ): PluginSurfacePreflightResult {
        return runCatching {
            val allAndroidEvidence = androidTools.snapshot()
            val allHookEvidence = hooks.snapshot()
            val androidResolution = androidTools.resolve(manifest.androidTools, allAndroidEvidence)
            val snapshot = inventory.projectForPreparation(
                manifest,
                nativeEvidence,
                androidEvidence = allAndroidEvidence,
                hookEvidence = allHookEvidence,
            )
            if (snapshot.readiness == PluginSurfaceReadiness.INCOMPATIBLE) {
                PluginSurfacePreflightResult.Rejected(
                    "plugin_surface_incompatible",
                )
            } else {
                val requiredAndroidBindings = manifest.androidTools
                    .mapTo(linkedSetOf(), AndroidToolRequirement::bindingId)
                val requiredHookBindings = manifest.hooks
                    .mapTo(linkedSetOf(), DeclarativeHookRequirement::actionId)
                val androidEvidence = allAndroidEvidence
                    .filter { it.bindingId in requiredAndroidBindings }
                val hookEvidence = allHookEvidence
                    .filter { it.actionId in requiredHookBindings }
                PluginSurfacePreflightResult.Ready(
                    manifest = manifest,
                    inventory = snapshot,
                    dynamicToolExecutors = androidTools.executorsForResolvedRequirements(
                        manifest.androidTools,
                        androidResolution.resolvedRequirementIds,
                    ),
                    nativeEvidence = nativeEvidence,
                    androidBindingEvidence = androidEvidence,
                    hookBindingEvidence = hookEvidence,
                    stateSha256 = PluginSurfaceEvidenceIdentity.digest(
                        manifest = manifest,
                        nativeEvidence = nativeEvidence,
                        inventory = snapshot,
                        androidBindings = androidEvidence,
                        hookBindings = hookEvidence,
                    ),
                )
            }
        }.getOrElse {
            PluginSurfacePreflightResult.Rejected(
                "plugin_surface_preflight_failed",
            )
        }
    }
}

/** Stable private-store identity. The outer journal receives only this digest and an opaque id. */
internal object PluginSurfaceEvidenceIdentity {
    fun digest(
        manifest: PluginSurfaceManifest,
        nativeEvidence: CodexNativeSurfaceEvidence,
        inventory: PluginSurfaceInventorySnapshot,
        androidBindings: List<AndroidDynamicToolBindingSnapshot>,
        hookBindings: List<HansDeclarativeHookBindingSnapshot>,
    ): String {
        val document = JSONObject()
            .put(
                "manifest",
                PluginSurfaceManifestCodec.encode(manifest).toString(StandardCharsets.UTF_8),
            )
            .put("nativeComplete", nativeEvidence.complete)
            .put("nativeSkills", JSONArray(nativeEvidence.enabledSkillNames.sorted()))
            .put(
                "nativeHooks",
                JSONArray(
                    nativeEvidence.enabledHooks
                        .sortedWith(compareBy<Pair<String, String>>({ it.first }, { it.second }))
                        .map { JSONArray(listOf(it.first, it.second)) },
                ),
            )
            .put("inventory", inventory.canonicalJson())
            .put(
                "androidBindings",
                JSONArray(
                    androidBindings.sortedBy(AndroidDynamicToolBindingSnapshot::bindingId).map {
                        JSONObject()
                            .put("bindingId", it.bindingId)
                            .put("capabilityId", it.capabilityId)
                            .put("namespace", it.namespace)
                            .put("tool", it.tool)
                            .put("readiness", it.readiness.name)
                            .put("contractSha256", it.contractSha256)
                    },
                ),
            )
            .put(
                "hookBindings",
                JSONArray(
                    hookBindings.sortedBy(HansDeclarativeHookBindingSnapshot::actionId).map {
                        JSONObject()
                            .put("actionId", it.actionId)
                            .put("capabilityId", it.capabilityId)
                            .put("readiness", it.readiness.name)
                    },
                ),
            )
        return MessageDigest.getInstance("SHA-256")
            .digest(document.toString().toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun PluginSurfaceInventorySnapshot.canonicalJson(): JSONObject = JSONObject()
        .put("pluginId", pluginId)
        .put("readiness", readiness.wireName)
        .put(
            "items",
            JSONArray(
                items.sortedWith(compareBy({ it.kind }, { it.id })).map {
                    JSONObject()
                        .put("id", it.id)
                        .put("kind", it.kind)
                        .put("readiness", it.readiness.wireName)
                        .put("detailCode", it.detailCode)
                },
            ),
        )
        .put("entrypoints", JSONArray(resolvedEntrypointIds.sorted()))
        .put("capabilities", JSONArray(availableCapabilityIds.sorted()))
}
