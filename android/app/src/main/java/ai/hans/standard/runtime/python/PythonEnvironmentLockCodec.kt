package ai.hans.standard.runtime.python

import ai.hans.standard.codex.JsonContract
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

object PythonEnvironmentContract {
    const val LOCK_SCHEMA_VERSION = 1
    const val MAX_LOCK_BYTES = 512 * 1024

    private val PACKAGE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val PLUGIN_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
    private val IDENTIFIER = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
    private val VERSION = Regex("[A-Za-z0-9][A-Za-z0-9.!+_-]{0,127}")
    private val SHA_256 = Regex("[a-f0-9]{64}")
    private val WHEEL_FILE = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,240}\\.whl")

    fun isPackageName(value: String): Boolean = PACKAGE_NAME.matches(value)
    fun isPluginId(value: String): Boolean = PLUGIN_ID.matches(value)
    fun isSafeIdentifier(value: String): Boolean = IDENTIFIER.matches(value)
    fun isVersion(value: String): Boolean = VERSION.matches(value)
    fun isSha256(value: String): Boolean = SHA_256.matches(value)
    fun isWheelFileName(value: String): Boolean = WHEEL_FILE.matches(value) &&
        !value.contains('/') && !value.contains('\\')

    fun normalizePackageName(value: String): String =
        value.lowercase(Locale.US).replace(Regex("[-_.]+"), "-")

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(Locale.US, it.toInt() and 0xff) }

    fun sha256(text: String): String = sha256(text.toByteArray(StandardCharsets.UTF_8))
}

object PythonEnvironmentLockCodec {
    fun encode(lock: PythonEnvironmentLock): String = canonicalJson(lock).toString()

    fun digest(lock: PythonEnvironmentLock): String = PythonEnvironmentContract.sha256(encode(lock))

    fun decode(raw: String): PythonEnvironmentLock {
        val json = JsonContract.parseObject(raw, PythonEnvironmentContract.MAX_LOCK_BYTES)
        JsonContract.requireOnlyKeys(
            json,
            setOf("schemaVersion", "pluginId", "target", "wheels", "nativePackages", "sourceSha256"),
            "Python environment lock",
        )
        val targetJson = JsonContract.requiredObject(json, "target")
        JsonContract.requireOnlyKeys(
            targetJson,
            setOf("pythonVersion", "interpreterTag", "androidAbi", "minimumAndroidApi"),
            "Python environment target",
        )
        val wheels = JsonContract.requiredArray(json, "wheels")
        val wheelPins = buildList {
            repeat(wheels.length()) { index ->
                val item = wheels.optJSONObject(index) ?: error("Wheel lock entry must be an object")
                JsonContract.requireOnlyKeys(
                    item,
                    setOf(
                        "packageName", "version", "fileName", "sha256", "sizeBytes",
                        "sourceUri", "requiresPython",
                    ),
                    "Python wheel pin",
                )
                add(
                    PythonWheelPin(
                        packageName = JsonContract.requiredString(item, "packageName", 128),
                        version = JsonContract.requiredString(item, "version", 128),
                        fileName = JsonContract.requiredString(item, "fileName", 256),
                        sha256 = JsonContract.requiredString(item, "sha256", 64),
                        sizeBytes = JsonContract.requiredLong(item, "sizeBytes"),
                        sourceUri = JsonContract.requiredString(
                            item,
                            "sourceUri",
                            PythonEnvironmentLimits.MAX_SOURCE_URI_BYTES,
                        ),
                        requiresPython = JsonContract.optionalString(
                            item,
                            "requiresPython",
                            PythonEnvironmentLimits.MAX_REQUIRES_PYTHON_BYTES,
                        ),
                    ),
                )
            }
        }
        val native = JsonContract.requiredArray(json, "nativePackages")
        val nativePins = buildList {
            repeat(native.length()) { index ->
                val item = native.optJSONObject(index) ?: error("Native lock entry must be an object")
                JsonContract.requireOnlyKeys(
                    item,
                    setOf("packageName", "version", "catalogId", "payloadSha256"),
                    "Python native catalog pin",
                )
                add(
                    PythonNativeCatalogPin(
                        packageName = JsonContract.requiredString(item, "packageName", 128),
                        version = JsonContract.requiredString(item, "version", 128),
                        catalogId = JsonContract.requiredString(item, "catalogId", 128),
                        payloadSha256 = JsonContract.requiredString(item, "payloadSha256", 64),
                    ),
                )
            }
        }
        return PythonEnvironmentLock(
            schemaVersion = requiredInt(json, "schemaVersion"),
            pluginId = JsonContract.requiredString(json, "pluginId", 128),
            target = PythonEnvironmentTarget(
                pythonVersion = JsonContract.requiredString(targetJson, "pythonVersion", 32),
                interpreterTag = JsonContract.requiredString(targetJson, "interpreterTag", 16),
                androidAbi = JsonContract.requiredString(targetJson, "androidAbi", 32),
                minimumAndroidApi = requiredInt(targetJson, "minimumAndroidApi"),
            ),
            wheels = wheelPins,
            nativePackages = nativePins,
            sourceSha256 = JsonContract.optionalString(json, "sourceSha256", 64),
        )
    }

    fun targetJson(target: PythonEnvironmentTarget): JSONObject = JSONObject()
        .put("pythonVersion", target.pythonVersion)
        .put("interpreterTag", target.interpreterTag)
        .put("androidAbi", target.androidAbi)
        .put("minimumAndroidApi", target.minimumAndroidApi)

    private fun canonicalJson(lock: PythonEnvironmentLock): JSONObject = JSONObject()
        .put("schemaVersion", lock.schemaVersion)
        .put("pluginId", lock.pluginId)
        .put("target", targetJson(lock.target))
        .put(
            "wheels",
            JSONArray(
                lock.wheels.sortedWith(compareBy<PythonWheelPin> { it.normalizedName }.thenBy { it.version })
                    .map(::wheelJson),
            ),
        )
        .put(
            "nativePackages",
            JSONArray(
                lock.nativePackages
                    .sortedWith(compareBy<PythonNativeCatalogPin> { it.normalizedName }.thenBy { it.version })
                    .map(::nativeJson),
            ),
        )
        .put("sourceSha256", lock.sourceSha256 ?: JSONObject.NULL)

    private fun wheelJson(pin: PythonWheelPin): JSONObject = JSONObject()
        .put("packageName", pin.packageName)
        .put("version", pin.version)
        .put("fileName", pin.fileName)
        .put("sha256", pin.sha256)
        .put("sizeBytes", pin.sizeBytes)
        .put("sourceUri", pin.sourceUri)
        .put("requiresPython", pin.requiresPython ?: JSONObject.NULL)

    private fun nativeJson(pin: PythonNativeCatalogPin): JSONObject = JSONObject()
        .put("packageName", pin.packageName)
        .put("version", pin.version)
        .put("catalogId", pin.catalogId)
        .put("payloadSha256", pin.payloadSha256)

    private fun requiredInt(json: JSONObject, key: String): Int {
        val value = JsonContract.requiredLong(json, key)
        require(value in Int.MIN_VALUE..Int.MAX_VALUE) { "$key is outside integer range" }
        return value.toInt()
    }
}
