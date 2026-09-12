package ai.hans.standard.automations

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * One-shot persisted network constraint. It is armed only while validated Internet is absent, so
 * it performs no idle polling. JobScheduler wakes the process when Android observes a validated
 * default-capable network; the runtime then performs its normal durable/idempotent reconciliation.
 */
internal object AndroidAutomationNetworkRecoveryBackstop {
    const val JOB_ID = 0x48414E54 // "HANT", separate from timer/execution/recovery IDs.

    fun schedule(context: Context): Boolean = runCatching {
        context.getSystemService(JobScheduler::class.java).schedule(platformJob(context)) ==
            JobScheduler.RESULT_SUCCESS
    }.getOrDefault(false)

    fun cancel(context: Context) {
        runCatching { context.getSystemService(JobScheduler::class.java).cancel(JOB_ID) }
    }

    fun isValidated(context: Context, network: Network?): Boolean = runCatching {
        val matched = network ?: return@runCatching false
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity.getNetworkCapabilities(matched) ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    fun isDefaultValidated(context: Context): Boolean = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val defaultNetwork = connectivity.activeNetwork ?: return@runCatching false
        val capabilities = connectivity.getNetworkCapabilities(defaultNetwork)
            ?: return@runCatching false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    internal fun platformJob(context: Context): JobInfo = JobInfo.Builder(
        JOB_ID,
        ComponentName(context, HansAutomationWakeupJobService::class.java),
    )
        // The builder starts with TRUSTED, NOT_VPN and NOT_RESTRICTED. Remove only the latter two:
        // an automation may legitimately recover over a validated VPN or restricted network, but
        // untrusted networks must not wake an unattended personal assistant. This explicit
        // request also avoids Android 12/12L's legacy NETWORK_TYPE_ANY -> NOT_RESTRICTED mapping.
        .setRequiredNetwork(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                .build(),
        )
        .setPersisted(true)
        .build()
}

/**
 * A JobScheduler constraint can be satisfied by a non-default network on multi-network devices.
 * Hans' HTTP/App Server traffic follows Android's default routing, so both the assigned network
 * and the actual default must be usable before consuming this one-shot recovery opportunity.
 */
internal object AutomationNetworkRecoveryDeliveryPolicy {
    fun ready(assignedNetworkValidated: Boolean, defaultNetworkValidated: Boolean): Boolean =
        assignedNetworkValidated && defaultNetworkValidated
}
