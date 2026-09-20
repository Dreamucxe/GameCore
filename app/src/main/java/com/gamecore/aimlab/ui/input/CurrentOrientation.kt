package com.gamecore.aimlab.ui.input

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import com.gamecore.aimlab.engine.ControlOrientation

/**
 * The [ControlOrientation] the screen is currently showing, read from the live [Configuration] (§4).
 *
 * The overlay draws the portrait or landscape control set depending on how the device is actually held,
 * not on the requested setting — so a layout being edited or trained on shows the arrangement for the
 * orientation on screen right now, and re-reads on rotation because `LocalConfiguration` recomposes.
 */
@Composable
fun currentControlOrientation(): ControlOrientation =
    if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        ControlOrientation.LANDSCAPE
    } else {
        ControlOrientation.PORTRAIT
    }
