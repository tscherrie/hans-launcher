package ai.hans.standard.backup

import ai.hans.standard.LauncherActivity
import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.core.os.BundleCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

/** Public AndroidX registry/lifecycle exercise, pinned to the seven actual Launcher registrations. */
internal object BackupRecoverySavedResults {
    // These are the documented saved-state keys of the repository's pinned AndroidX Activity
    // 1.11.0, not private Android APIs. A dependency/registration change must fail this proof.
    private const val REGISTERED_KEYS = "KEY_COMPONENT_ACTIVITY_REGISTERED_KEYS"
    private const val REGISTERED_RCS = "KEY_COMPONENT_ACTIVITY_REGISTERED_RCS"
    private const val LAUNCHED_KEYS = "KEY_COMPONENT_ACTIVITY_LAUNCHED_KEYS"
    private const val PENDING = "KEY_COMPONENT_ACTIVITY_PENDING_RESULT"
    private const val RESULT_MARKER = "hans_backup_fixture_result"
    private val fields = listOf("cameraCaptureLauncher", "videoCaptureLauncher", "microphonePermission",
        "notificationPermission", "publicPhonePermissions", "backupCreateDocument", "backupOpenDocument")
    private val keys = fields.indices.map { "activity_rq#$it" }.toSet()

    fun enqueue(activity: LauncherActivity, fixtureId: String, success: Boolean): BackupRecoveryPendingResults {
        val state = saveRegistry(activity)
        val codes = requestCodes(state)
        assertTrue(state.getStringArrayList(LAUNCHED_KEYS).orEmpty().isEmpty())
        assertTrue(pending(state).isEmpty)
        val expected = linkedMapOf<String, ActivityResult>()
        fields.forEachIndexed { index, field ->
            val key = "activity_rq#$index"
            val marker = "$fixtureId:$field:$success"
            val permissions = when (index) {
                2 -> arrayOf(Manifest.permission.RECORD_AUDIO)
                3 -> arrayOf("android.permission.POST_NOTIFICATIONS")
                4 -> arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
                else -> emptyArray()
            }
            val uri = Uri.parse("content://ai.hans.standard.test.backup/$fixtureId/$field")
            val data = Intent().putExtra(RESULT_MARKER, marker).apply {
                if (permissions.isNotEmpty()) {
                    putExtra(RequestMultiplePermissions.EXTRA_PERMISSIONS, permissions)
                    putExtra(RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS,
                        IntArray(permissions.size) { if (success) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED })
                }
                if (success && index in 5..6) this.data = uri
            }
            val result = ActivityResult(if (success) Activity.RESULT_OK else Activity.RESULT_CANCELED, data)
            val launcher = LauncherActivity::class.java.getDeclaredField(field)
                .apply { isAccessible = true }.get(activity) as ActivityResultLauncher<*>
            val parsed = launcher.contract.parseResult(result.resultCode, result.data)
            val expectedParsed: Any? = when (index) {
                0, 1, 2, 3 -> success
                4 -> if (success) permissions.associateWith { true } else emptyMap<String, Boolean>()
                else -> if (success) uri else null
            }
            assertEquals("The actual $field contract must parse the intended result", expectedParsed, parsed)
            assertTrue("Actual registration must accept $field", activity.activityResultRegistry.dispatchResult(
                codes.getValue(key), result.resultCode, result.data,
            ))
            expected[key] = result
        }
        assertFalse(codes.values.contains(0))
        assertFalse("An unmapped request must not masquerade as delivered", activity.activityResultRegistry.dispatchResult(
            0, Activity.RESULT_CANCELED, null,
        ))
        return BackupRecoveryPendingResults(codes, expected).also { assertPending(activity, it) }
    }

    fun assertPending(activity: LauncherActivity, expected: BackupRecoveryPendingResults) {
        assertPendingBundle(saveRegistry(activity), expected)
    }

    fun assertFrameworkSaved(outState: Bundle, expected: BackupRecoveryPendingResults) {
        val savedRegistry = checkNotNull(outState.getBundle("androidx.lifecycle.BundlableSavedStateRegistry.key"))
        val results = checkNotNull(savedRegistry.getBundle("android:support:activity-result"))
        assertPendingBundle(results, expected)
    }

    fun assertConsumed(activity: LauncherActivity, expected: BackupRecoveryPendingResults) {
        val state = saveRegistry(activity)
        assertEquals(expected.requestCodes, requestCodes(state))
        assertTrue("Every buffered raw result must be consumed by ON_START", pending(state).isEmpty)
    }

    private fun assertPendingBundle(state: Bundle, expected: BackupRecoveryPendingResults) {
        assertEquals(expected.requestCodes, requestCodes(state))
        val pending = pending(state)
        assertEquals(keys, pending.keySet())
        expected.results.forEach { (key, wanted) ->
            val actual = checkNotNull(BundleCompat.getParcelable(pending, key, ActivityResult::class.java))
            assertEquals(wanted.resultCode, actual.resultCode)
            assertEquals(wanted.data?.getStringExtra(RESULT_MARKER), actual.data?.getStringExtra(RESULT_MARKER))
            assertEquals(wanted.data?.data, actual.data?.data)
            assertEquals(wanted.data?.getStringArrayExtra(RequestMultiplePermissions.EXTRA_PERMISSIONS)?.toList(),
                actual.data?.getStringArrayExtra(RequestMultiplePermissions.EXTRA_PERMISSIONS)?.toList())
            assertEquals(wanted.data?.getIntArrayExtra(RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS)?.toList(),
                actual.data?.getIntArrayExtra(RequestMultiplePermissions.EXTRA_PERMISSION_GRANT_RESULTS)?.toList())
        }
    }

    private fun requestCodes(state: Bundle): Map<String, Int> {
        val registered = checkNotNull(state.getStringArrayList(REGISTERED_KEYS))
        val codes = checkNotNull(state.getIntegerArrayList(REGISTERED_RCS))
        assertEquals(7, registered.size)
        assertEquals(keys, registered.toSet())
        assertEquals(7, codes.size)
        assertEquals(7, codes.toSet().size)
        return registered.zip(codes).toMap()
    }

    private fun pending(state: Bundle): Bundle = checkNotNull(state.getBundle(PENDING))
    private fun saveRegistry(activity: LauncherActivity): Bundle = Bundle().also {
        activity.activityResultRegistry.onSaveInstanceState(it)
    }
}

internal data class BackupRecoveryPendingResults(
    val requestCodes: Map<String, Int>,
    val results: Map<String, ActivityResult>,
)

internal class BackupRecoveryResultDeliveryObserver(
    private val activity: LauncherActivity,
    private val pending: BackupRecoveryPendingResults,
    private val label: String,
    private val deliveries: MutableSet<String>,
) : LifecycleEventObserver {
    private var delivered = false

    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event != Lifecycle.Event.ON_START) return
        assertFalse("A receipt must represent one delivery, not a repeated lifecycle event", delivered)
        // Registered after the seven production observers: AndroidX has parsed each queued result
        // and called its actual callback before this observer can see its key removed.
        BackupRecoverySavedResults.assertConsumed(activity, pending)
        pending.results.keys.forEach { assertTrue(deliveries.add("$label:$it")) }
        delivered = true
        source.lifecycle.removeObserver(this)
    }

    fun assertDelivered() {
        assertTrue("ON_START must actually run; dispatchResult(true) alone proves no callback", delivered)
        BackupRecoverySavedResults.assertConsumed(activity, pending)
    }
}
