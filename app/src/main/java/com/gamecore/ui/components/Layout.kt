package com.gamecore.ui.components

import android.app.Activity
import android.view.WindowManager
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gamecore.ui.theme.LocalAnimationsEnabled
import com.gamecore.ui.theme.LocalGlassMaterial
import com.gamecore.ui.theme.StatusGood
import com.gamecore.ui.theme.ThermalCritical
import com.gamecore.ui.theme.ThermalWarning
import com.gamecore.ui.theme.glassPanel

/**
 * The pieces every screen is built out of.
 *
 * These exist so that "a card", "a section heading", "a warning" and "nothing here yet" are decided
 * once. §27 asks for rounded cards, large readable stats and a consistent look; a project where each
 * screen composes its own `Surface` with its own corner radius drifts within a week of being written.
 *
 * Nothing here knows about a ViewModel or a repository. Every component takes what it draws and, where
 * it is interactive, a lambda — so a screen preview can supply literals, and the same card can show a
 * live reading or a recorded one.
 */

/** Standard edge padding. One value, so screens line up with each other when the user switches. */
val ScreenPadding = 16.dp

/**
 * How much room a scrolling screen leaves at the bottom for the floating navigation bar.
 *
 * The bar floats over the content rather than sitting under it — §27 asks for that — so every scrollable
 * screen has to end above it. Here rather than per screen, because the day the bar's height changes is
 * the day seven screens would otherwise start clipping their last row.
 */
val ScreenBottomPadding = 96.dp

/**
 * What a value *means*, rather than what colour it is.
 *
 * Screens say `Tone.Warning` and never `ThermalWarning` directly, so the mapping from meaning to colour
 * is in one place and the semantic colours (which deliberately sit outside the accent's reach) cannot
 * be picked up decoratively by a screen that just wanted something orange.
 */
enum class Tone { Neutral, Muted, Accent, Good, Warning, Danger }

@Composable
fun Tone.colour(): Color = when (this) {
    Tone.Neutral -> MaterialTheme.colorScheme.onSurface
    Tone.Muted -> MaterialTheme.colorScheme.onSurfaceVariant
    Tone.Accent -> MaterialTheme.colorScheme.primary
    Tone.Good -> StatusGood
    Tone.Warning -> ThermalWarning
    Tone.Danger -> ThermalCritical
}

/**
 * A screen's title, and its way back.
 *
 * The title is the largest type on the screen and sits beside a short accent rail — the two together are
 * the header's identity, and the rail is the one place the accent appears before any content. A caller
 * that has a real icon for the screen (a plugin's own mark, say) swaps the rail for a tinted tile, the
 * same trade [SectionCard] makes, so the two headings cannot disagree about which mark is the app's.
 * The back control is a circular tonal surface rather than a bare arrow, which gives it a 40 dp target
 * and a visible resting state without borrowing a button's prominence.
 *
 * The back arrow is present only when [onBack] is — the five destinations on the bottom bar have no
 * "back" that means anything, and drawing a disabled arrow on them would suggest otherwise.
 */
@Composable
fun ScreenHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    icon: ImageVector? = null,
    action: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (onBack == null) ScreenPadding else 8.dp, end = 8.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Surface(
                onClick = onBack,
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = scheme.surfaceVariant.copy(alpha = 0.55f),
                contentColor = scheme.onSurface,
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(scheme.primary.copy(alpha = 0.14f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = scheme.primary,
                            modifier = Modifier.size(17.dp),
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .width(22.dp)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(scheme.primary),
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge,
                    color = scheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 30.dp, top = 2.dp),
                )
            }
        }
        if (action != null) action()
    }
}

/** Vertical space between sections. Named so the rhythm of a screen is not a pile of literal values. */
@Composable
fun SectionGap(height: Int = 12) = Spacer(modifier = Modifier.height(height.dp))

/**
 * A heading that sits *outside* the card it introduces.
 *
 * The redesign uses these to turn a screen into a set of shelves rather than one long stack of cards: the
 * label names the shelf, the content under it can be a grid, a rail or a bento, and the page gains a
 * rhythm that a column of identically-titled cards cannot. [SectionCard] is still the right component when
 * the heading belongs to one card; this one is for the headings that belong to a *region*.
 *
 * The accent rail and the uppercase label match [ScreenHeader]'s mark and [SectionCard]'s rail, so the
 * three read as one system. [action] is for the one thing a region offers as a whole — "See all", a
 * filter, a state word — never for a per-item control.
 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    action: @Composable (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(16.dp)
                .clip(CircleShape)
                .background(scheme.primary),
        )
        Spacer(modifier = Modifier.width(9.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (action != null) action()
    }
}

/**
 * The card everything sits in.
 *
 * `surface` on a `background` that is a shade darker, with a hairline outline rather than an elevation
 * shadow: on the near-black dark scheme a shadow is invisible and a tonal elevation overlay muddies the
 * accent, whereas a one-pixel outline separates a card from the page at any brightness.
 *
 * When a schema-4 theme has put the app in glass, the card is drawn as material instead — see
 * [glassPanel] — and this becomes the same `Surface` with its own fill and outline switched off, so
 * everything a card is *for* is unchanged: the shape, the content colour, the ripple on the clickable
 * variant and the role a screen reader announces. Only the pixels come from somewhere else.
 */
@Composable
fun PlainCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 14.dp,
    fillHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val glass = LocalGlassMaterial.current
    Surface(
        modifier = if (glass == null) modifier else modifier.glassPanel(glass, shape),
        shape = shape,
        color = if (glass == null) MaterialTheme.colorScheme.surface else Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (glass == null) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        // `fillHeight` exists for grids: when two cards share a row, the shorter one should stretch to
        // the taller one and keep its action row on the same baseline. A plain card keeps wrapping its
        // content, which is what every list row wants.
        Column(
            modifier = (if (fillHeight) Modifier.fillMaxHeight() else Modifier).padding(contentPadding),
            content = content,
        )
    }
}

/**
 * A card the user can tap.
 *
 * Separate from [PlainCard] rather than a nullable `onClick`, because the two are different components
 * to the accessibility layer: a clickable `Surface` gets a role, a ripple and a focus target, and a card
 * that merely *might* have a click handler would advertise all three to a screen reader either way.
 *
 * Glass-aware in exactly [PlainCard]'s way, which is possible precisely because the material is a
 * modifier on the `Surface` rather than a replacement for it: a tappable glass panel still ripples,
 * and the ripple is drawn over the material rather than under it.
 */
@Composable
fun ClickableCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 14.dp,
    fillHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    val glass = LocalGlassMaterial.current
    val motion = LocalAnimationsEnabled.current
    // Press feedback is a 2.5% scale, not a colour flash: the card is usually a game's own artwork or a
    // live reading, and dimming either would read as a disabled state. The interaction source is passed
    // to Surface so the ripple and this scale share one press lifecycle.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && motion) 0.975f else 1f,
        animationSpec = if (motion) {
            spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium)
        } else {
            snap()
        },
        label = "cardPress",
    )
    Surface(
        onClick = onClick,
        interactionSource = interaction,
        modifier = (if (glass == null) modifier else modifier.glassPanel(glass, shape))
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        shape = shape,
        color = if (glass == null) MaterialTheme.colorScheme.surface else Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = if (glass == null) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant) else null,
    ) {
        Column(
            modifier = (if (fillHeight) Modifier.fillMaxHeight() else Modifier).padding(contentPadding),
            content = content,
        )
    }
}

/**
 * A card with a heading, which is what most of the app is.
 *
 * [action] is a trailing composable rather than a button plus a label, because the actions differ —
 * Sessions wants a sort control, Performance wants a refresh, Games wants "Add" — and a signature that
 * tried to cover all of them would end up as five nullable parameters.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    action: @Composable (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    PlainCard(modifier = modifier.fillMaxWidth(), contentPadding = 16.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // An icon gets a tinted tile; a heading without one gets a short accent rail. Both are the
            // same job — a deliberate mark at the start of the row — and the rail keeps icon-less cards
            // from looking like plain lists.
            if (icon != null) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(scheme.primary.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
            } else {
                Box(
                    modifier = Modifier
                        .width(3.dp)
                        .height(18.dp)
                        .clip(CircleShape)
                        .background(scheme.primary),
                )
                Spacer(modifier = Modifier.width(10.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = scheme.onSurface,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (action != null) action()
        }
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

/** A label on the left, a value on the right. The app's most-used row by a wide margin. */
@Composable
fun KeyValueRow(
    label: String,
    value: String,
    tone: Tone = Tone.Neutral,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            color = tone.colour(),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * A row that goes somewhere.
 *
 * The chevron is the affordance; the description is why the user would tap it. Both are needed — a list
 * of eight bare titles on a settings screen is a menu the user has to open to understand.
 */
@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    description: String? = null,
    icon: ImageVector? = null,
    trailing: String? = null,
    trailingTone: Tone = Tone.Muted,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge)
                if (description != null) {
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelLarge,
                    color = trailingTone.colour(),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * What a list looks like before the user has made anything.
 *
 * Every empty list in this app says what would be in it and offers the action that fills it. An empty
 * screen with a spinner-shaped hole in it is indistinguishable from a broken one.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(26.dp),
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(14.dp))
            Button(onClick = onAction, shape = MaterialTheme.shapes.medium) {
                Text(actionLabel)
            }
        }
    }
}

/** A small state marker: "Connected", "Not supported", "Running". */
@Composable
fun StatusChip(text: String, tone: Tone = Tone.Neutral, icon: ImageVector? = null) {
    val colour = tone.colour()
    Surface(
        shape = MaterialTheme.shapes.small,
        color = colour.copy(alpha = 0.14f),
        contentColor = colour,
        border = BorderStroke(1.dp, colour.copy(alpha = 0.30f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(13.dp))
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(text = text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

/**
 * A sentence the app needs the user to read: an explanation, a limitation, a warning.
 *
 * This is the component §22 and §24's honesty rules are spent through — "this device does not report a
 * frame rate", "Shizuku is not running, so this is unavailable". Tinted by tone rather than shouting,
 * because most of these are neutral facts about a device rather than failures.
 */
@Composable
fun NoteBanner(
    text: String,
    tone: Tone = Tone.Muted,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    action: @Composable (() -> Unit)? = null,
) {
    val colour = tone.colour()
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = colour.copy(alpha = 0.10f),
        contentColor = colour,
        border = BorderStroke(1.dp, colour.copy(alpha = 0.22f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(17.dp))
                Spacer(modifier = Modifier.width(9.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (action != null) {
                Spacer(modifier = Modifier.width(6.dp))
                action()
            }
        }
    }
}

/** A row of buttons under a card, spaced consistently. */
@Composable
fun ActionRow(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Runs [block] every time the screen becomes visible again, and once when it first appears.
 *
 * GameCore sends the user out to system settings constantly — usage access, "display over other apps",
 * notification permission, battery optimisation — and Android gives an app no callback for the user
 * coming back. A screen that reads a permission state once on composition will keep showing the state
 * from before the user granted it, which is the single most common way a permissions UI ends up lying.
 *
 * `ON_RESUME` rather than a composition effect, because returning from another activity does not leave
 * this composable: `LaunchedEffect(Unit)` would run once and never again. [block] is captured through
 * `rememberUpdatedState` so a caller can pass a lambda that closes over changing state without the
 * observer being torn down and re-registered on every recomposition.
 */
@Composable
fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(block)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) latest()
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/**
 * Keeps this screen out of screenshots and the recent-apps thumbnail while it is on screen.
 *
 * §24A.12 asks for `FLAG_SECURE` where anything sensitive is shown, which in GameCore means the Shizuku
 * screen: it displays the connection state and the exact shell operations the app is permitted to run,
 * and that is a map of this device's elevated surface that does not belong in a screen recording the
 * user posts.
 *
 * A window flag, so it is set on entry and cleared on exit rather than left on for the session —
 * `DisposableEffect` is exactly that shape. The flag is only cleared if this composable set it, so a
 * future second secure screen appearing over this one cannot lower it on the way out.
 */
@Composable
fun SecureWindow() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val alreadySet = window != null &&
            (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (window != null && !alreadySet) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (window != null && !alreadySet) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
    }
}

/**
 * This device's screen shape, for the boxes that stand in for the screen.
 *
 * The HUD builder and the crosshair editor both preview an overlay inside a card, and a preview shaped
 * 9:16 on a tablet or a foldable would put a widget the user dragged to the corner somewhere else once it
 * was drawn for real. Taken from the current configuration, so it also follows a rotation.
 *
 * Clamped because the value ends up in `Modifier.aspectRatio`, and a window in a freeform or split-screen
 * mode can report something extreme enough to leave the preview a few pixels tall — a preview too small to
 * drag in is worse than one that is not exactly the window's shape.
 */
@Composable
fun screenAspectRatio(): Float {
    val configuration = LocalConfiguration.current
    val width = configuration.screenWidthDp.coerceAtLeast(1).toFloat()
    val height = configuration.screenHeightDp.coerceAtLeast(1).toFloat()
    return (width / height).coerceIn(MIN_PREVIEW_ASPECT, MAX_PREVIEW_ASPECT)
}

private const val MIN_PREVIEW_ASPECT = 0.35f
private const val MAX_PREVIEW_ASPECT = 2.4f
