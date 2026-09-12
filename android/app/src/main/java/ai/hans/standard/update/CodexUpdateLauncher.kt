package ai.hans.standard.update

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import java.net.URI

/**
 * Opens the fixed, build-time Hans update surface for the Codex runtime bundled in the APK.
 *
 * This deliberately does not download or install executable content. Codex is updated only as
 * part of a normal, same-publisher Hans APK update, so Android remains the signer and user-consent
 * boundary. A blank URL is the honest staging configuration rather than an editable fallback.
 */
internal class CodexUpdateLauncher(
    private val configuredUpdateUrl: String,
    private val platform: CodexUpdatePlatform,
) {
    constructor(context: Context, configuredUpdateUrl: String) : this(
        configuredUpdateUrl = configuredUpdateUrl,
        platform = AndroidCodexUpdatePlatform(context.applicationContext),
    )

    fun launch(): CodexUpdateLaunchResult {
        if (configuredUpdateUrl.isEmpty()) {
            return CodexUpdateLaunchResult.BUNDLED_WITH_HANS
        }
        val safeUrl = validatedFixedHttpsUrlOrNull(configuredUpdateUrl)
            ?: return CodexUpdateLaunchResult.INVALID_CONFIGURATION
        if (!platform.hasValidatedInternet()) {
            return CodexUpdateLaunchResult.OFFLINE
        }
        if (!platform.canOpen(safeUrl)) {
            return CodexUpdateLaunchResult.NO_BROWSER
        }
        return runCatching { platform.open(safeUrl) }
            .fold(
                onSuccess = { CodexUpdateLaunchResult.OPENED },
                onFailure = { CodexUpdateLaunchResult.OPEN_FAILED },
            )
    }
}

internal enum class CodexUpdateLaunchResult {
    /** No public update endpoint is sealed into this build; Codex still updates with Hans. */
    BUNDLED_WITH_HANS,
    INVALID_CONFIGURATION,
    OFFLINE,
    NO_BROWSER,
    OPENED,
    OPEN_FAILED,
}

internal interface CodexUpdatePlatform {
    fun hasValidatedInternet(): Boolean
    fun canOpen(url: String): Boolean
    fun open(url: String)
}

internal class AndroidCodexUpdatePlatform(
    private val context: Context,
) : CodexUpdatePlatform {
    override fun hasValidatedInternet(): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun canOpen(url: String): Boolean =
        updateIntent(url).resolveActivity(context.packageManager) != null

    override fun open(url: String) {
        context.startActivity(updateIntent(url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun updateIntent(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
}

/** Strict because the value eventually comes from sealed release metadata, never user input. */
internal fun validatedFixedHttpsUrlOrNull(value: String): String? {
    if (
        value.isBlank() || value != value.trim() || value.length > MAX_UPDATE_URL_LENGTH ||
        value.any {
            it.code !in 0x21..0x7e || it.isISOControl() || it.isWhitespace() || it == '\\'
        }
    ) {
        return null
    }
    val uri = runCatching { URI(value) }.getOrNull() ?: return null
    if (
        !value.startsWith("https://") || uri.scheme != "https" || uri.host.isNullOrBlank() ||
        uri.userInfo != null || uri.fragment != null || uri.port !in setOf(-1, 443)
    ) {
        return null
    }
    return value
}

private const val MAX_UPDATE_URL_LENGTH = 2_048
