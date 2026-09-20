package com.gamecore.service

import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.gamecore.R
import com.gamecore.core.model.QuickTriggerAction
import com.gamecore.domain.trigger.QuickTriggerCoordinator
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.Executor
import java.util.function.Consumer
import javax.inject.Inject

/**
 * The Quick Settings tile: the one trigger that works from anywhere and costs nothing to keep armed.
 *
 * Android dispatches the click itself, so there is no service running, no sensor being read and no window
 * that has to have focus. That makes this the honest recommendation for a user who wants the panel from
 * inside a game, and the settings screen says so.
 *
 * **Adding the tile is the opt-in.** This tile does not check
 * [com.gamecore.core.model.QuickTriggerSettings.enabled] and does not require the method to be
 * [com.gamecore.core.model.QuickTriggerMethod.QUICK_TILE]. A tile only exists in the shade because the
 * user dragged it there or accepted the prompt, and a tile that answered a tap by doing nothing — because
 * a switch on a settings screen they have not visited is off — would be a broken control on the system's
 * own surface. What the configured method *does* change is which trigger the settings screen describes as
 * the active one, and whether the shake service runs.
 *
 * The action does come from the settings, so a user who set the trigger to open the app rather than the
 * floating panel gets that from here too.
 */
@AndroidEntryPoint
class QuickTriggerTileService : TileService() {

    @Inject lateinit var coordinator: QuickTriggerCoordinator

    /**
     * Called when the shade opens with the tile visible. The only place the subtitle can be set.
     *
     * `STATE_INACTIVE` throughout: this tile is a button, not a switch. A tile that showed itself as
     * "active" would be claiming a state GameCore is in, and the panel's visibility is not something a
     * tile can read back reliably — the overlay may have been dismissed by the system, or the permission
     * revoked since.
     */
    override fun onStartListening() {
        super.onStartListening()
        describe()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        describe()
    }

    /**
     * The tap.
     *
     * [unlockAndRun] when the device is locked, because the destination is either a window over whatever
     * is on screen or the app itself, and both are things that should be behind the user's lock. Android
     * prompts for the credential and runs the block afterwards; if the user cancels, nothing happens,
     * which is the correct outcome.
     */
    override fun onClick() {
        super.onClick()
        val fire = Runnable { coordinator.fire() }
        if (isLocked) unlockAndRun(fire) else fire.run()
    }

    /**
     * Puts the configured action in the tile's subtitle, where Android has room for it.
     *
     * Subtitles arrived in API 29. Below that the tile shows its label alone, which is a smaller tile
     * rather than a wrong one, so there is nothing to work around.
     */
    private fun describe() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_trigger_label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when (coordinator.tileAction()) {
                    QuickTriggerAction.OPEN_APP -> R.string.tile_trigger_subtitle_app
                    else -> R.string.tile_trigger_subtitle_panel
                },
            )
        }
        tile.updateTile()
    }

    companion object {

        /**
         * Asks Android to offer the tile, on the one version that has an API for it.
         *
         * `requestAddTileService` is API 33. There is no equivalent below that and nothing to fake: an app
         * cannot place a tile itself on any version, so the settings screen tells the user where the Quick
         * Settings edit screen is instead. [onResult] receives one of `StatusBarManager`'s
         * `TILE_ADD_REQUEST_RESULT_*` values, including the two that mean the user said no.
         *
         * Returns false when the version is too low or the system service is missing, so the caller knows
         * the prompt is not coming rather than waiting for a callback that will never arrive.
         */
        fun requestAdd(context: Context, onResult: (Int) -> Unit): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
            return requestAddOnTiramisu(context, onResult)
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun requestAddOnTiramisu(context: Context, onResult: (Int) -> Unit): Boolean {
            val manager = context.getSystemService(StatusBarManager::class.java) ?: return false
            return try {
                manager.requestAddTileService(
                    ComponentName(context, QuickTriggerTileService::class.java),
                    context.getString(R.string.tile_trigger_label),
                    Icon.createWithResource(context, R.drawable.ic_stat_gamecore),
                    // The prompt is a system dialog and the result is a UI state change, so the callback
                    // is run on the thread the caller is already on rather than on a pool.
                    Executor { runnable -> runnable.run() },
                    Consumer<Int> { result -> onResult(result) },
                )
                true
            } catch (refused: Exception) {
                // Documented to throw IllegalArgumentException for a component that is not this app's, and
                // observed on some OEM builds to throw where AOSP does not. Either way there is no prompt.
                false
            }
        }
    }
}
