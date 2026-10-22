package com.gamecore.ui.settings

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gamecore.R
import com.gamecore.core.plugin.icon.AppIconSlot
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.Tone

/**
 * The app-icon picker.
 *
 * Its own card and its own ViewModel so that none of Settings' form machinery has to know about it:
 * the icon is not an `AppSettings` field, it is not exported with a backup, and a plugin — not the
 * user — can be the thing that changed it.
 *
 * The picker shows the difference between the two things a user can get, because they are genuinely
 * different: a **slot** changes the launcher icon itself, and a **pinned shortcut** adds a second icon
 * drawn from a plugin's image. Labelling the second as a shortcut rather than as "the plugin's icon"
 * is the whole honesty of this screen — Android will not put a package's bitmap in its launcher slot,
 * and a menu that pretends otherwise would ship a feature that does not work.
 */
@Composable
fun AppIconCard(
    modifier: Modifier = Modifier,
    viewModel: AppIconViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val icon = state.icon
    val busy = state.busy || !icon.loaded

    SectionCard(title = "App icon", icon = Icons.Filled.Star, modifier = modifier) {
        Text(
            text = "ICON",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            for (slot in AppIconSlot.entries) {
                IconTile(
                    slot = slot,
                    selected = icon.current == slot && icon.inForce == null,
                    enabled = !busy,
                    onClick = { viewModel.chooseSlot(slot) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (icon.inForce != null) {
            RowDivider()
            NoteBanner(
                text = "${icon.inForce.pluginName} is setting your app icon.",
                tone = Tone.Accent,
                action = {
                    TextButton(onClick = viewModel::reset, enabled = !busy) { Text("Undo") }
                },
            )
        }

        if (icon.offers.isNotEmpty()) {
            RowDivider()
            Text(
                text = "FROM PLUGINS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (offer in icon.offers) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = offer.pluginName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${offer.spec.slot.label} icon" +
                                if (offer.spec.hasArtwork) " · offers artwork" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (offer.spec.hasArtwork) {
                        TextButton(
                            onClick = { viewModel.pinArtwork(offer.pluginId) },
                            enabled = !busy,
                        ) { Text("Pin") }
                    }
                    TextButton(
                        onClick = { viewModel.usePluginIcon(offer.pluginId) },
                        enabled = !busy && icon.inForce?.pluginId != offer.pluginId,
                    ) { Text("Use") }
                }
            }
        }

        state.message?.let { note ->
            NoteBanner(
                text = note,
                tone = Tone.Muted,
                action = { TextButton(onClick = viewModel::dismissMessage) { Text("OK") } },
            )
        }
        Spacer(Modifier.size(2.dp))
        Text(
            text = "A plugin can choose any of these icons. Only GameCore's own set can be the " +
                "launcher icon; a plugin's artwork can be pinned as a home-screen shortcut instead.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One icon in the row: its own art, its name, and a ring when it is the one in force. */
@Composable
private fun IconTile(
    slot: AppIconSlot,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    val art = rememberIconArt(slot.previewRes())
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 4.dp),
    ) {
        if (art != null) {
            Image(
                bitmap = art,
                contentDescription = slot.label,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = ring,
                        shape = RoundedCornerShape(12.dp),
                    ),
            )
        }
        Text(
            text = slot.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * The artwork each slot ships.
 *
 * An exhaustive `when`, so adding an [AppIconSlot] case is a compile error here until its preview is
 * wired — which is what keeps the picker from showing an empty tile for an icon that exists.
 */
private fun AppIconSlot.previewRes(): Int = when (this) {
    AppIconSlot.DEFAULT -> R.mipmap.ic_launcher
    AppIconSlot.MONO -> R.mipmap.ic_launcher_mono
    AppIconSlot.GLITCH -> R.mipmap.ic_launcher_glitch
    AppIconSlot.NEON -> R.mipmap.ic_launcher_neon
    AppIconSlot.REDLINE -> R.mipmap.ic_launcher_redline
}

/**
 * Draws a slot's own icon into a bitmap, rather than loading it with `painterResource`.
 *
 * Every launcher icon here is an *adaptive* icon: the mipmap is an `<adaptive-icon>` document, and
 * this project ships no density-specific PNG fallback, so on any API 26+ device that document is
 * what the id resolves to. `painterResource` cannot load it — it parses an XML resource as a vector,
 * and the adaptive document has no viewport to parse, so the parse throws inside composition. The
 * throw happens while the Settings list is being built, which takes the whole process down the
 * instant this card appears, with no error the user can act on.
 *
 * Drawing into a bitmap of a size we pick asks the drawable nothing it cannot answer, and it is the
 * same art the launcher shows. The result is remembered per resource and density, so a row scrolling
 * out of view and back does not redraw it.
 */
@Composable
private fun rememberIconArt(@DrawableRes resId: Int): ImageBitmap? {
    val context = LocalContext.current
    val density = LocalDensity.current
    return remember(resId, density) {
        val drawable = context.getDrawable(resId) ?: return@remember null
        val side = with(density) { 48.dp.roundToPx() }.coerceAtLeast(1)
        Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).also { bitmap ->
            drawable.setBounds(0, 0, side, side)
            drawable.draw(Canvas(bitmap))
        }.asImageBitmap()
    }
}
