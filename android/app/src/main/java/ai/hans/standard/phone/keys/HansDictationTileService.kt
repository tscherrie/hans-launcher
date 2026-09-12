package ai.hans.standard.phone.keys

import ai.hans.standard.voice.android.DictationRuntimeObserver
import ai.hans.standard.voice.android.DictationRuntimeSnapshot
import ai.hans.standard.voice.android.DictationUiPhase
import ai.hans.standard.voice.android.HansDictationRuntime
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * User-added Quick Settings fallback for hardware keys Android/OEM firmware
 * never delivers to Accessibility. Listening is event-driven and exists only
 * while System UI is displaying the tile.
 */
class HansDictationTileService : TileService() {
    private var listening = false
    private var observer: DictationRuntimeObserver? = null

    override fun onStartListening() {
        super.onStartListening()
        if (listening) return
        listening = true
        val nextObserver = DictationRuntimeObserver { snapshot ->
            mainExecutor.execute {
                if (listening) updateTile(snapshot)
            }
        }
        observer = nextObserver
        HansDictationRuntime.addObserver(nextObserver)
    }

    override fun onStopListening() {
        listening = false
        observer?.let(HansDictationRuntime::removeObserver)
        observer = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        val active = HansDictationRuntime.snapshotUi().isActive() ||
            RootFreeDictationLaunchCoordinator.hasPendingStart()
        if (active) {
            RootFreeDictationLaunchCoordinator.requestStop(this)
            updateTile(HansDictationRuntime.snapshotUi())
            return
        }
        val launch = Runnable {
            val prepared = RootFreeDictationLaunchCoordinator.prepareStart(this)
                ?: return@Runnable
            if (!launchAndCollapse(prepared)) {
                RootFreeDictationLaunchCoordinator.cancelPreparedStart(prepared.token)
            }
            updateTile(HansDictationRuntime.snapshotUi())
        }
        if (isLocked) {
            unlockAndRun(launch)
        } else {
            launch.run()
        }
    }

    override fun onDestroy() {
        if (listening) onStopListening()
        super.onDestroy()
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchAndCollapse(prepared: PreparedDictationLaunch): Boolean = runCatching {
        val intent = prepared.intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_NO_ANIMATION or
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val creatorOptions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                ActivityOptions.makeBasic().apply {
                    pendingIntentCreatorBackgroundActivityStartMode =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                        } else {
                            @Suppress("DEPRECATION")
                            ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                        }
                }.toBundle()
            } else {
                null
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                (prepared.token and Int.MAX_VALUE.toLong()).toInt(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                creatorOptions,
            )
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }.isSuccess

    private fun updateTile(snapshot: DictationRuntimeSnapshot) {
        val tile = qsTile ?: return
        val active = snapshot.isActive() || RootFreeDictationLaunchCoordinator.hasPendingStart()
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(ai.hans.standard.R.string.dictation_tile_label)
        tile.subtitle = getString(
            if (active) {
                ai.hans.standard.R.string.dictation_tile_stop
            } else {
                ai.hans.standard.R.string.dictation_tile_start
            },
        )
        tile.updateTile()
    }
}

private fun DictationRuntimeSnapshot.isActive(): Boolean = phase in setOf(
    DictationUiPhase.PREPARING,
    DictationUiPhase.LISTENING,
    DictationUiPhase.FINALIZING,
)
