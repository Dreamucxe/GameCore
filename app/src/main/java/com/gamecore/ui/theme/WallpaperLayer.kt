package com.gamecore.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material3.MaterialTheme
import android.graphics.BitmapFactory
import com.gamecore.core.model.AppSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The user's wallpaper, drawn behind the app under a mandatory scrim.
 *
 * In its own file rather than inside [com.gamecore.ui.GameCoreRoot] on purpose: the root is a shared
 * seam, and a wallpaper is a self-contained layer, so the root's change is one call.
 *
 * ## The scrim is not a preference
 *
 * GameCore's theme contract guarantees that `onSurface` text is legible against `background`. A
 * photograph behind that text voids the guarantee silently — nothing fails, the app simply becomes
 * unreadable in patches, and a screenshot of a settings screen over a bright sky is a bug report, not
 * a taste difference. So the scrim is always drawn, in the scheme's own `background` colour, and its
 * floor ([AppSettings.MIN_WALLPAPER_SCRIM]) is enforced where the setting is normalised rather than
 * here. The user controls how much of their image shows through; they cannot turn the guarantee off.
 *
 * ## Absent is absent
 *
 * A null [path], a file that has been deleted behind the app's back, and bytes that no longer decode
 * all render as *nothing* — the layer draws the scrim-less background it was already sitting on, and
 * the app looks exactly as it does with no wallpaper set. That is the honest outcome: the alternative
 * is a black rectangle where an image should be, which reads as a rendering bug rather than as an
 * absent file. The import path is where a failure is *reported*; by the time a wallpaper is being
 * drawn there is no user action left to prompt for.
 *
 * The decode runs off the main thread and is keyed by [path], so a new import re-reads and an
 * unchanged one does not. The bitmap is deliberately never recycled: it is handed to Compose, which
 * may still be drawing it, and recycling a bitmap that is on screen crashes.
 */
@Composable
fun WallpaperLayer(
    path: String?,
    scrimPercent: Int,
    modifier: Modifier = Modifier,
) {
    if (path.isNullOrBlank()) return

    val image: ImageBitmap? by produceState<ImageBitmap?>(initialValue = null, key1 = path) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull()
        }
    }

    val bitmap = image ?: return

    Box(modifier = modifier.fillMaxSize()) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    MaterialTheme.colorScheme.background.copy(
                        alpha = (scrimPercent.coerceIn(
                            AppSettings.MIN_WALLPAPER_SCRIM,
                            AppSettings.MAX_WALLPAPER_SCRIM,
                        )) / 100f,
                    ),
                ),
        )
    }
}
