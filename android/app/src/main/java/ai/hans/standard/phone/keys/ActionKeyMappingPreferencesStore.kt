package ai.hans.standard.phone.keys

import android.content.Context
import android.content.SharedPreferences
import java.io.Closeable
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores only keys the user actually demonstrated. Version two supports the
 * primary voice key plus optional semantic shortcuts such as the Luna/Astra
 * toggle; the old single-key layout is read for upgrade compatibility.
 */
class ActionKeyMappingPreferencesStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) {
    private val preferences = context.applicationContext.getSharedPreferences(
        preferencesName,
        Context.MODE_PRIVATE,
    )

    @Synchronized
    fun read(): ActionKeyMappingSet = runCatching {
        preferences.getString(KEY_MAPPINGS_JSON, null)?.let(::decodeMappings)
            ?: readLegacyMapping()
    }.getOrDefault(ActionKeyMappingSet.empty())

    @Synchronized
    fun save(mapping: ActionKeyMapping) {
        saveSet(read().upsert(mapping))
    }

    @Synchronized
    fun remove(mappingId: String) {
        saveSet(read().remove(mappingId))
    }

    @Synchronized
    fun savePrimaryDictation(mapping: ActionKeyMapping) {
        require(mapping.action == KeySemanticAction.DICTATION)
        save(mapping)
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().clear().commit()) { "Could not clear action-key mappings" }
    }

    /**
     * Event-driven observation for the user-enabled Accessibility shortcut.
     * The callback receives only the already-bounded mapping model; raw key
     * events and printable characters are never persisted or published here.
     */
    fun observe(observer: (ActionKeyMappingSet) -> Unit): Closeable {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == null || changedKey in OBSERVED_KEYS) {
                runCatching { observer(read()) }
            }
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        runCatching { observer(read()) }
        return Closeable {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    private fun readLegacyMapping(): ActionKeyMappingSet {
        val mappingId = preferences.getString(KEY_MAPPING_ID, null) ?: return ActionKeyMappingSet.empty()
        val descriptor = preferences.getString(KEY_DESCRIPTOR_SHA256, null)
            ?: return ActionKeyMappingSet.empty()
        return ActionKeyMappingSet.of(
            listOf(
                ActionKeyMapping(
                    mappingId = mappingId,
                    device = PhysicalKeyDeviceSelector(
                        vendorId = preferences.getInt(KEY_VENDOR_ID, -1),
                        productId = preferences.getInt(KEY_PRODUCT_ID, -1),
                        descriptorSha256 = descriptor,
                    ),
                    source = preferences.getInt(KEY_SOURCE, -1),
                    scanCode = preferences.getInt(KEY_SCAN_CODE, -1),
                    keyCode = preferences.getInt(KEY_KEY_CODE, -1),
                    metaState = preferences.getInt(KEY_META_STATE, 0),
                    trigger = ActionKeyTrigger.valueOf(
                        checkNotNull(preferences.getString(KEY_TRIGGER, null)),
                    ),
                    action = KeySemanticAction.valueOf(
                        checkNotNull(preferences.getString(KEY_ACTION, null)),
                    ),
                ),
            ),
        )
    }

    private fun saveSet(set: ActionKeyMappingSet) {
        val encoded = JSONArray().also { array ->
            set.mappings.forEach { mapping -> array.put(mapping.toJson()) }
        }.toString()
        require(encoded.length <= MAX_MAPPINGS_JSON_CHARACTERS)
        check(
            preferences.edit()
                .putString(KEY_MAPPINGS_JSON, encoded)
                .remove(KEY_MAPPING_ID)
                .remove(KEY_VENDOR_ID)
                .remove(KEY_PRODUCT_ID)
                .remove(KEY_DESCRIPTOR_SHA256)
                .remove(KEY_SOURCE)
                .remove(KEY_SCAN_CODE)
                .remove(KEY_KEY_CODE)
                .remove(KEY_META_STATE)
                .remove(KEY_TRIGGER)
                .remove(KEY_ACTION)
                .commit(),
        ) { "Could not persist action-key mappings" }
    }

    private fun decodeMappings(raw: String): ActionKeyMappingSet {
        require(raw.length <= MAX_MAPPINGS_JSON_CHARACTERS)
        val array = JSONArray(raw)
        require(array.length() <= MAX_MAPPINGS)
        return ActionKeyMappingSet.of(
            buildList {
                repeat(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    require(item.keys().asSequence().toSet() == MAPPING_KEYS)
                    add(
                        ActionKeyMapping(
                            mappingId = item.getString("mappingId"),
                            device = PhysicalKeyDeviceSelector(
                                vendorId = item.getInt("vendorId"),
                                productId = item.getInt("productId"),
                                descriptorSha256 = item.getString("descriptorSha256"),
                            ),
                            source = item.getInt("source"),
                            scanCode = item.getInt("scanCode"),
                            keyCode = item.getInt("keyCode"),
                            metaState = item.getInt("metaState"),
                            trigger = ActionKeyTrigger.valueOf(item.getString("trigger")),
                            action = KeySemanticAction.valueOf(item.getString("action")),
                        ),
                    )
                }
            },
        )
    }

    private fun ActionKeyMapping.toJson(): JSONObject = JSONObject()
        .put("mappingId", mappingId)
        .put("vendorId", device.vendorId)
        .put("productId", device.productId)
        .put("descriptorSha256", device.descriptorSha256)
        .put("source", source)
        .put("scanCode", scanCode)
        .put("keyCode", keyCode)
        .put("metaState", metaState)
        .put("trigger", trigger.name)
        .put("action", action.name)

    private companion object {
        const val PREFERENCES_NAME = "hans_action_key_v1"
        const val KEY_MAPPINGS_JSON = "mappings_json_v2"
        const val KEY_MAPPING_ID = "mapping_id"
        const val KEY_VENDOR_ID = "vendor_id"
        const val KEY_PRODUCT_ID = "product_id"
        const val KEY_DESCRIPTOR_SHA256 = "descriptor_sha256"
        const val KEY_SOURCE = "source"
        const val KEY_SCAN_CODE = "scan_code"
        const val KEY_KEY_CODE = "key_code"
        const val KEY_META_STATE = "meta_state"
        const val KEY_TRIGGER = "trigger"
        const val KEY_ACTION = "action"
        const val MAX_MAPPINGS = 8
        const val MAX_MAPPINGS_JSON_CHARACTERS = 32 * 1_024
        val MAPPING_KEYS = setOf(
            "mappingId",
            "vendorId",
            "productId",
            "descriptorSha256",
            "source",
            "scanCode",
            "keyCode",
            "metaState",
            "trigger",
            "action",
        )
        val OBSERVED_KEYS = MAPPING_KEYS + setOf(
            KEY_MAPPINGS_JSON,
            KEY_MAPPING_ID,
            KEY_VENDOR_ID,
            KEY_PRODUCT_ID,
            KEY_DESCRIPTOR_SHA256,
            KEY_SOURCE,
            KEY_SCAN_CODE,
            KEY_KEY_CODE,
            KEY_META_STATE,
            KEY_TRIGGER,
            KEY_ACTION,
        )
    }
}
