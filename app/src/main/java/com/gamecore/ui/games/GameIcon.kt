package com.gamecore.ui.games

import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A game's own launcher icon, for the §5 profile cards.
 *
 * Three constraints shape this, and all three come from the spec rather than from taste.
 *
 * **Off the main thread.** `PackageManager.getApplicationIcon` opens the other app's APK and decodes a
 * drawable out of it. Done inline in a composable it is a disk read on every frame of a scrolling list,
 * which is exactly the jank §10 asks to avoid, so the load runs in a `LaunchedEffect` on the IO dispatcher
 * and the card draws its fallback until the answer arrives.
 *
 * **A small cache.** The same six or seven packages are drawn every time the user scrolls back up, and
 * re-decoding an APK for an icon that has not changed is pure waste. [GameIconCache] is a fixed, tiny LRU
 * of already-rasterised [ImageBitmap]s, shared across the screen's cards and bounded so a user with forty
 * profiles cannot turn it into a leak.
 *
 * **A generic fallback, never a blank.** A package with no icon, an APK that will not decode, or a game
 * uninstalled since the profile was saved all land in the same place: a lettered tile. Something to look at
 * and something to aim a thumb at, in the same footprint as the real icon so nothing reflows when it loads.
 *
 * No new permission is involved. The manifest's existing `<queries>` element already declares the
 * launcher-activity intent the profile list is built from, which is the same visibility this needs — the
 * spec is explicit that no package-visibility permission may be added just to load icons.
 *
 * [contentDescription] is deliberately null: the game's name is drawn immediately beside the icon in every
 * card, and a screen reader announcing "Rocket Racer icon, Rocket Racer" reads the same word twice.
 */
@Composable
fun GameIcon(
    packageName: String,
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    val context = LocalContext.current
    // Seeded from the cache so a card scrolled back into view draws its icon on the first frame rather
    // than flashing the fallback while an already-answered lookup is repeated.
    var result by remember(packageName) { mutableStateOf(GameIconCache.cached(packageName)) }

    LaunchedEffect(packageName) {
        if (result == null) {
            val pm = context.packageManager
            result = withContext(Dispatchers.IO) { GameIconCache.load(pm, packageName) }
        }
    }

    val image = result?.image
    val shape = MaterialTheme.shapes.medium
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier
                .size(size)
                .clip(shape),
        )
    } else {
        Surface(
            modifier = modifier.size(size),
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Box(contentAlignment = Alignment.Center) {
                val initial = label.trim().firstOrNull()
                if (initial == null) {
                    // No label to take a letter from — a sanitised label can end up empty. A game glyph
                    // still says "this is a game" where a blank square says nothing at all.
                    Icon(
                        imageVector = Icons.Filled.SportsEsports,
                        contentDescription = null,
                        modifier = Modifier.size(size / 2),
                    )
                } else {
                    Text(
                        text = initial.uppercaseChar().toString(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

/**
 * What one lookup produced: an icon, or the settled fact that there is not one.
 *
 * A wrapper rather than a bare nullable [ImageBitmap] because "not looked up yet" and "looked up, there is
 * no icon" are different states and only the first should be retried. Without the distinction an
 * uninstalled game's card would re-open the package manager on every recomposition, forever, to be told
 * the same thing.
 */
internal class GameIconResult(val image: ImageBitmap?)

/**
 * The process-wide icon cache: small, bounded, and holding rasterised bitmaps rather than drawables.
 *
 * An [ImageBitmap] so Compose draws it directly; a `Drawable` would be re-rendered on every recomposition
 * of a list that scrolls. [MAX_ENTRIES] is deliberately smaller than the number of profiles a heavy user
 * might have — this is a scroll cache, not a registry, and at [ICON_PX] square each entry is about 64 kB,
 * so the ceiling is roughly a megabyte no matter how many games are configured.
 *
 * [LruCache] is used for its synchronisation as much as its eviction: cards load in parallel coroutines and
 * two of them can ask for the same package at once.
 */
internal object GameIconCache {

    private val entries = LruCache<String, GameIconResult>(MAX_ENTRIES)

    /** The already-known answer, or null when this package has not been looked up yet. */
    fun cached(packageName: String): GameIconResult? = entries.get(packageName)

    /**
     * Rasterise one package's icon, caching both an icon and the absence of one.
     *
     * The size is passed to `toBitmap` rather than read from the drawable because an adaptive icon reports
     * no intrinsic size worth trusting, and `toBitmap()` on a drawable reporting −1 throws — the same
     * reasoning as [com.gamecore.domain.overlay.QuickAppLauncher].
     *
     * Every failure is one outcome: there is no icon to draw. A missing package, a
     * [PackageManager.NameNotFoundException], an APK whose resources will not decode — none of them is a
     * reason to fail the card, which falls back to its lettered tile.
     *
     * Must be called off the main thread.
     */
    fun load(pm: PackageManager, packageName: String): GameIconResult {
        entries.get(packageName)?.let { return it }
        val result = try {
            GameIconResult(
                pm.getApplicationIcon(packageName)
                    .toBitmap(width = ICON_PX, height = ICON_PX)
                    .asImageBitmap(),
            )
        } catch (error: Throwable) {
            GameIconResult(null)
        }
        entries.put(packageName, result)
        return result
    }

    /** A little over a 44 dp icon at xxhdpi: sharp on every density this app runs on. */
    private const val ICON_PX = 128

    private const val MAX_ENTRIES = 16
}
