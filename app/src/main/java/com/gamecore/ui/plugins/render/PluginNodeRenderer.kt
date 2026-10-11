package com.gamecore.ui.plugins.render

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gamecore.core.plugin.ui.ComponentNode
import com.gamecore.core.plugin.ui.ComponentType
import com.gamecore.core.plugin.ui.PropValue
import com.gamecore.ui.components.ABSENT
import com.gamecore.ui.theme.Spacing
import com.gamecore.ui.theme.StatLabelStyle
import com.gamecore.ui.theme.StatValueCompactStyle
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.truncate

/**
 * The first renderer for part F: a validated [ComponentNode] tree drawn as real Material 3 UI.
 *
 * A plugin is data (overlay contract §0). What arrives here is a tree of primitives that
 * `ScreenValidator` has already vetted for shape, depth, node count, grid width and string length,
 * and that the overlay codec has already stripped of anything interactive. So this file is not a
 * second validator — it is the one place that decides what those primitives *look like*, and the
 * whole of its job is faithful drawing.
 *
 * ### Why there are no callbacks in this file, at all
 *
 * The first consumer is a plugin overlay module: a small bounded window hosted by
 * `GamingOverlayService` with `FLAG_NOT_TOUCHABLE` set. That window cannot receive a touch event,
 * so a control drawn in it that *looked* tappable would be §32's button that does nothing — the
 * user presses it, the game underneath takes the press, and nothing happens for a reason nobody
 * can see. The second, deeper reason is the one the contract leads with: there is no plugin code
 * to call back into. A plugin never receives a binder, a lambda or a `Composable`, so a callback
 * here would have nowhere to go.
 *
 * **A plugin never gets a callback, so this file attaches no `onClick`, `onCheckedChange`,
 * `onValueChange`, `clickable`, `toggleable`, `pointerInput` or any other gesture modifier to any
 * node — and a future edit must not add one.** `BUTTON` draws an outlined, visibly inert label;
 * `SWITCH` and `SLIDER` draw static indicators of the value in their props; `SCROLL` draws a
 * plain clipped column, because scrolling is a gesture too. If a plugin ever does need to trigger
 * something, that arrives as a `PropValue.ActionRef` dispatched through the normal gated path by
 * the *host* — which means a change in the host, not an `onClick` smuggled in here.
 *
 * ### Why `IMAGE` is a closed set of built-in icons
 *
 * There is no image loader on this path and deliberately so: a plugin-supplied URL would be a
 * network call made on the plugin's behalf (which the Plugins screen promises cannot happen), a
 * plugin-supplied file path would be a read of whatever the plugin can name, and plugin-supplied
 * bytes would be a decoder fed untrusted input inside the overlay process. So `IMAGE` resolves its
 * `icon` prop against [BUILTIN_ICONS] — vectors already compiled into the app — and an unknown
 * name falls back to [PLACEHOLDER_ICON] rather than to nothing, so a mistyped name is visible as
 * a mistake instead of silently shrinking the layout.
 *
 * ### Why every prop read is defensive
 *
 * The props arrive validated, but validation guarantees *shape*, not that a `size` is a number or
 * that `columns` was supplied at all. A renderer that threw on a missing or mistyped prop would
 * take down an overlay — or a whole screen — over one bad key, so [str], [num], [bool] and
 * [color] each fall back to a sane default and anything unrecognised is ignored in silence. A node
 * that renders plainly beats a node that crashes the window it is drawn in.
 *
 * ### Why there is a depth ceiling here as well as in the validator
 *
 * `ScreenValidator` caps depth (24 by default, 6 for an overlay module), so for validated input
 * [MAX_RENDER_DEPTH] never fires. It exists because [PluginNodeTree] is public: the next caller
 * may hand it a tree it built itself, and "the input was supposed to be validated" is not a stack
 * frame. The ceiling is set well above the validator's cap on purpose, so it can never truncate a
 * tree that legitimately passed.
 */

/**
 * The defensive recursion ceiling — not the policy cap.
 *
 * Above `ScreenValidator`'s own default `maxDepth` of 24 so a validated screen is never clipped by
 * it; low enough that an unvalidated tree cannot recurse composition into a stack overflow.
 */
private const val MAX_RENDER_DEPTH = 32

/** The §5 "ceiling on anything iterated" rule, applied to children. Above the validator's 100. */
private const val MAX_CHILDREN_RENDERED = 128

/** A plugin string is capped before it reaches a `Text`, in case it never met a validator. */
private const val MAX_TEXT_CHARS = 240

/** A resolved stat is a figure, not a paragraph; a long one would burst a bounded module. */
private const val MAX_STAT_CHARS = 24

/** Text wraps, but only so far: an unbounded block of lines is how an overlay box stops fitting. */
private const val DEFAULT_TEXT_MAX_LINES = 3
private const val MAX_TEXT_LINES = 8

/** Grid width. The validator rejects outside 1..8 (1..4 for a module); this clamps for safety. */
private const val DEFAULT_GRID_COLUMNS = 2
private const val MAX_GRID_COLUMNS = 8

/**
 * Dimension clamps, in dp.
 *
 * Clamped rather than rejected, for the reason §3 clamps an anchor: a gap of 9999 is an author's
 * mistake to contain, not an attack to refuse, and refusing the whole module over it would hide
 * everything else the plugin got right.
 */
private const val DEFAULT_GAP_DP = 4f
private const val MAX_GAP_DP = 48f
private const val MAX_PADDING_DP = 32f
private const val CARD_PADDING_DP = 10f
private const val DEFAULT_SPACER_DP = 8f
private const val MAX_SPACER_DP = 64f
private const val DEFAULT_ICON_DP = 20f
private const val MIN_ICON_DP = 8f
private const val MAX_ICON_DP = 48f

/** Type size, in sp. Floored so a plugin cannot render text no one can read. */
private const val MIN_TEXT_SP = 8f
private const val MAX_TEXT_SP = 48f

/** Keeps a resized line from clipping against the inherited line height. */
private const val LINE_HEIGHT_RATIO = 1.3f

private const val MIN_FONT_WEIGHT = 100
private const val MAX_FONT_WEIGHT = 900

/**
 * The alpha floor for text and icons.
 *
 * §3's reasoning about module opacity applied one level down: a plugin that paints its label
 * `#00FFFFFF` has drawn a module the user cannot find, which is worse than a faint one. A card's
 * *background* is left alone, because a transparent plate over a game is a legitimate choice.
 */
private const val MIN_CONTENT_ALPHA = 0.25f

private val SWITCH_TRACK_WIDTH = 30.dp
private val SWITCH_TRACK_HEIGHT = 18.dp
private val SWITCH_KNOB_INSET = 2.dp
private val SWITCH_KNOB_SIZE = 14.dp
private val SLIDER_TRACK_HEIGHT = 4.dp

/**
 * Every image a plugin may draw, by `icon` name.
 *
 * A closed map rather than a reflective lookup over `Icons.Filled`, so the set is auditable by
 * reading this declaration: a reviewer sees twenty vectors, not "whatever the icon library ships".
 * The names are GameCore's own vocabulary rather than Material's, so an icon can be re-pointed at a
 * better vector later without breaking a plugin that already shipped.
 */
private val BUILTIN_ICONS: Map<String, ImageVector> = mapOf(
    "speed" to Icons.Filled.Speed,
    "memory" to Icons.Filled.Memory,
    "thermostat" to Icons.Filled.Thermostat,
    "battery" to Icons.Filled.BatteryFull,
    "bolt" to Icons.Filled.Bolt,
    "timer" to Icons.Filled.Timer,
    "timeline" to Icons.Filled.Timeline,
    "data" to Icons.Filled.DataUsage,
    "insights" to Icons.Filled.Insights,
    "sensors" to Icons.Filled.Sensors,
    "storage" to Icons.Filled.Storage,
    "shield" to Icons.Filled.Shield,
    "extension" to Icons.Filled.Extension,
    "info" to Icons.Filled.Info,
    "warning" to Icons.Filled.Warning,
    "block" to Icons.Filled.Block,
    "check" to Icons.Filled.CheckCircle,
    "dot" to Icons.Filled.FiberManualRecord,
    "game" to Icons.Filled.SportsEsports,
    "settings" to Icons.Filled.Settings,
)

/** What an unknown `icon` name draws: a neutral outline, so the gap is visible but not alarming. */
private val PLACEHOLDER_ICON: ImageVector = Icons.Filled.RadioButtonUnchecked

/**
 * Draws a plugin's node tree.
 *
 * The one entry point. [node] is expected to have come through `ScreenValidator` — and for an
 * overlay module, through the codec's §3 bounds as well — but nothing here depends on that beyond
 * the ceilings above, so a caller that has not validated gets a bounded, plain rendering rather
 * than an exception.
 *
 * [stats] resolves a `STAT` node's `stat` prop to a display string. Returning `null` means "not
 * available", and an unavailable figure renders as [ABSENT] — the app's one way of writing "there
 * is no figure here" — never as an error and never as a blank, because a blank would silently
 * collapse the row it was meant to occupy and leave a module looking broken rather than honest.
 * The resolver is called inside composition, so it must be cheap and must not block; it is also
 * wrapped, so a resolver that throws on an unknown id costs that one figure and not the window.
 *
 * **This renderer is read-only by construction: a plugin never gets a callback, so nothing drawn
 * here is interactive and nothing in this file attaches a click, a toggle, a drag or any other
 * gesture.** See the file KDoc for why, before adding one.
 */
@Composable
fun PluginNodeTree(
    node: ComponentNode,
    stats: (String) -> String?,
    series: (String) -> List<Float>? = { null },
    modifier: Modifier = Modifier,
) {
    RenderNode(node = node, stats = stats, series = series, depth = 0, modifier = modifier)
}

/**
 * The recursive core: one node, dispatched on its type.
 *
 * Exhaustive over [ComponentType] with no `else`, so adding a component to the enum fails the
 * build here rather than quietly rendering nothing on every plugin screen.
 */
@Composable
private fun RenderNode(
    node: ComponentNode,
    stats: (String) -> String?,
    series: (String) -> List<Float>?,
    depth: Int,
    modifier: Modifier = Modifier,
) {
    if (depth > MAX_RENDER_DEPTH) return
    when (node.type) {
        ComponentType.COLUMN -> NodeColumn(node, stats, series, depth, modifier)
        ComponentType.ROW -> NodeRow(node, stats, series, depth, modifier)
        ComponentType.GRID -> NodeGrid(node, stats, series, depth, modifier)
        ComponentType.SCROLL -> NodeScroll(node, stats, series, depth, modifier)
        ComponentType.CARD -> NodeCard(node, stats, series, depth, modifier)
        ComponentType.TEXT -> NodeText(node.props, modifier)
        ComponentType.BUTTON -> NodeButtonLabel(node.props, modifier)
        ComponentType.SLIDER -> NodeSlider(node.props, modifier)
        ComponentType.SWITCH -> NodeSwitch(node.props, modifier)
        ComponentType.STAT -> NodeStat(node.props, stats, modifier)
        ComponentType.SPACER -> NodeSpacer(node.props, modifier)
        ComponentType.IMAGE -> NodeImage(node.props, modifier)
        ComponentType.CHART -> NodeChart(node, series, modifier)
    }
}

/** Children, capped and one level deeper. Containers all go through this rather than iterating. */
@Composable
private fun NodeChildren(
    node: ComponentNode,
    stats: (String) -> String?,
    series: (String) -> List<Float>?,
    depth: Int,
) {
    node.children.take(MAX_CHILDREN_RENDERED).forEach { child ->
        RenderNode(node = child, stats = stats, series = series, depth = depth + 1)
    }
}

@Composable
private fun NodeColumn(
    node: ComponentNode,
    stats: (String) -> String?,
    series: (String) -> List<Float>?,
    depth: Int,
    modifier: Modifier,
) {
    val props = node.props
    Column(
        modifier = modifier.then(props.paddingModifier()),
        verticalArrangement = Arrangement.spacedBy(props.gap()),
        horizontalAlignment = props.horizontalAlignment(),
    ) {
        NodeChildren(node, stats, series, depth)
    }
}

@Composable
private fun NodeRow(
    node: ComponentNode,
    stats: (String) -> String?,
    series: (String) -> List<Float>?,
    depth: Int,
    modifier: Modifier,
) {
    val props = node.props
    // `align` means the cross axis in both containers, so one prop reads the same way in a row as
    // in a column: "center" centres, whichever direction the children run.
    Row(
        modifier = modifier.then(props.paddingModifier()),
        horizontalArrangement = Arrangement.spacedBy(props.gap()),
        verticalAlignment = props.verticalAlignment(),
    ) {
        NodeChildren(node, stats, series, depth)
    }
}

/**
 * A grid, laid out by chunking rather than by `LazyVerticalGrid`.
 *
 * The house pattern (see `ChoiceRow`, `TileRow`, the Home tool grid): rows of weighted cells, so
 * the wrap point is the `columns` prop and not whatever the longest child happens to measure. A
 * lazy grid would also be wrong twice over here — it brings its own scrolling to a surface that
 * cannot be scrolled, and a plugin tree is tens of nodes, not thousands.
 *
 * The last row is padded with weighted spacers so four items in a three-wide grid leave a gap
 * where the fourth cell would be, instead of stretching one cell across the row.
 */
@Composable
private fun NodeGrid(
    node: ComponentNode,
    stats: (String) -> String?,
    series: (String) -> List<Float>?,
    depth: Int,
    modifier: Modifier,
) {
    val props = node.props
    val columns = (props.numOrNull("columns")?.toInt() ?: DEFAULT_GRID_COLUMNS)
        .coerceIn(1, MAX_GRID_COLUMNS)
    val gap = props.gap()
    val cells = node.children.take(MAX_CHILDREN_RENDERED)
    Column(
        modifier = modifier.then(props.paddingModifier()),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        cells.chunked(columns).forEach { rowCells ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(gap),
                verticalAlignment = props.verticalAlignment(),
            ) {
                rowCells.forEach { cell ->
                    RenderNode(
                        node = cell,
                        stats = stats,
                        series = series,
                        depth = depth + 1,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - rowCells.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * `SCROLL`, rendered as a plain clipped column.
 *
 * Not `Modifier.verticalScroll`: scrolling is a gesture, and the overlay window this renderer was
 * written for cannot deliver one — a scroll container there is a scrollbar the user can see and
 * never move. The clip is the honest half of the behaviour: content that does not fit is cut off
 * at the box rather than drawn outside it and over the game. On a full plugin screen the host's own
 * `LazyColumn` provides the scrolling, which is where it belongs anyway.
 */
@Composable
private fun NodeScroll(
    node: ComponentNode,
    stats: (String) -> String?,
    series: (String) -> List<Float>?,
    depth: Int,
    modifier: Modifier,
) {
    val props = node.props
    Column(
        modifier = modifier.then(props.paddingModifier()).clipToBounds(),
        verticalArrangement = Arrangement.spacedBy(props.gap()),
        horizontalAlignment = props.horizontalAlignment(),
    ) {
        NodeChildren(node, stats, series, depth)
    }
}

/**
 * A card: `PlainCard`'s shape and outline, but with a plugin-settable fill.
 *
 * Built on `Surface` directly rather than reusing `PlainCard`, which fixes its colour and padding —
 * a module needs the `color` prop to land somewhere and a tighter inset than a 14.dp screen card.
 *
 * When the plugin supplies no colour the whole card inherits the active `MaterialTheme`, which is
 * the default we want: a module follows the user's theme unless it asks not to. When it does supply
 * one, the content colour is derived from that fill's own brightness instead of the theme's
 * `onSurfaceVariant` — otherwise a plugin picking a pale plate in a dark theme gets white text on
 * near-white and the module is unreadable. Alpha is ignored in that decision, because what shows
 * through a translucent plate is a game frame nobody here can measure.
 */
@Composable
private fun NodeCard(
    node: ComponentNode,
    stats: (String) -> String?,
    series: (String) -> List<Float>?,
    depth: Int,
    modifier: Modifier,
) {
    val props = node.props
    val scheme = MaterialTheme.colorScheme
    val fill = props.color("color")
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = fill ?: scheme.surfaceVariant,
        contentColor = fill?.readableContentColour() ?: scheme.onSurfaceVariant,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Column(
            modifier = Modifier.padding(props.dpOf("padding", CARD_PADDING_DP, 0f, MAX_PADDING_DP)),
            verticalArrangement = Arrangement.spacedBy(props.gap()),
            horizontalAlignment = props.horizontalAlignment(),
        ) {
            NodeChildren(node, stats, series, depth)
        }
    }
}

/**
 * Text: the component every other one is really made of.
 *
 * Always `maxLines` plus [TextOverflow.Ellipsis], never an unbounded block. A module's box is a
 * closed enum of sizes (§3) and a screen's column has a width, so a thousand-character label has to
 * end somewhere; ending it in an ellipsis says "there was more" where letting it wrap forever just
 * pushes everything else off the surface. The string is cut at [MAX_TEXT_CHARS] before Compose ever
 * measures it, so the cost of a long one is bounded too.
 */
@Composable
private fun NodeText(props: Map<String, PropValue>, modifier: Modifier) {
    Text(
        text = props.capped("text"),
        modifier = modifier.then(props.paddingModifier()),
        color = props.contentColour(),
        style = MaterialTheme.typography.bodyMedium.withProps(props),
        textAlign = props.textAlign(),
        maxLines = props.maxLines(DEFAULT_TEXT_MAX_LINES),
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * `BUTTON`, drawn as a label that is visibly not a button.
 *
 * Outlined and transparent rather than a filled `Button`, and muted rather than accented, because
 * the one thing this must not look like is something to press. It carries `disabled()` semantics for
 * the same reason: a screen reader that announced it as a button would be making the promise the
 * window cannot keep.
 *
 * It is drawn at all — rather than skipped — because a plugin screen that declares a control and
 * shows nothing is harder to debug than one that shows an inert control, and because the codec
 * already rejects `BUTTON` outright inside an overlay module (§3), so the only trees that reach
 * this branch are full screens, where a host-dispatched action may legitimately follow later.
 */
@Composable
private fun NodeButtonLabel(props: Map<String, PropValue>, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.then(props.paddingModifier()).semantics { disabled() },
        shape = MaterialTheme.shapes.small,
        color = Color.Transparent,
        contentColor = scheme.onSurfaceVariant,
        border = BorderStroke(1.dp, scheme.outline),
    ) {
        Text(
            text = props.label(),
            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            color = props.contentColour(),
            style = MaterialTheme.typography.labelLarge.withProps(props),
            textAlign = props.textAlign(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * `SLIDER`, drawn as a static bar at the value in its props.
 *
 * Hand-drawn from a track and a fill instead of Material's `Slider`, which has no read-only form —
 * its `onValueChange` is not optional, and an `enabled = false` slider says "this is broken for
 * now", which is not what a plugin readout means. A bar plus the figure says what the value is
 * without suggesting it can be moved.
 *
 * A `max` at or below `min` yields an empty bar rather than a `NaN` width: bad bounds are an
 * author's mistake, and an empty bar is the reading that makes the mistake visible.
 */
@Composable
private fun NodeSlider(props: Map<String, PropValue>, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val min = props.num("min", 0.0)
    val max = props.num("max", 100.0)
    val value = props.num("value", min)
    val span = max - min
    val fraction = if (span > 0.0) ((value - min) / span).toFloat().coerceIn(0f, 1f) else 0f
    val label = props.label()
    Column(
        modifier = modifier.then(props.paddingModifier()),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        if (label.isNotEmpty() || props.containsKey("value")) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value.asDisplay(),
                    color = props.contentColour(),
                    style = StatValueCompactStyle.withProps(props),
                    maxLines = 1,
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(SLIDER_TRACK_HEIGHT)
                .clip(CircleShape)
                .background(scheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(CircleShape)
                    .background(props.color("color")?.legible() ?: scheme.primary),
            )
        }
    }
}

/**
 * `SWITCH`, drawn as a static pill at the state in its props.
 *
 * Material's `Switch` does accept a null `onCheckedChange`, but it is drawn here by hand for the
 * same reason the slider is: this is a state readout, not a control that happens to be inert, and
 * the two should not look alike. An on/off pill reads as "this is how it is set"; a real switch
 * invites a thumb that the window will never feel.
 */
@Composable
private fun NodeSwitch(props: Map<String, PropValue>, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val on = props.bool("value")
    val track = props.color("color")?.legible() ?: if (on) scheme.primary else scheme.surfaceVariant
    val knob = if (on) scheme.onPrimary else scheme.outline
    val label = props.label()
    Row(
        modifier = modifier.then(props.paddingModifier()),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                modifier = Modifier.weight(1f, fill = false),
                color = props.contentColour(),
                style = MaterialTheme.typography.labelMedium.withProps(props),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .size(width = SWITCH_TRACK_WIDTH, height = SWITCH_TRACK_HEIGHT)
                .clip(CircleShape)
                .background(track)
                .padding(SWITCH_KNOB_INSET),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .size(SWITCH_KNOB_SIZE)
                    .clip(CircleShape)
                    .background(knob),
            )
        }
    }
}

/**
 * `STAT`: the only node whose content comes from GameCore rather than from the plugin.
 *
 * The plugin names a reading; the host decides whether that name resolves and what it reads. A null
 * from [stats] is the honest answer "not available", and it draws as [ABSENT] — the same em dash the
 * dashboard uses — because a plugin must not be able to tell the difference between a stat it was
 * not granted and one the device cannot measure, and because a zero would be a reading that never
 * happened.
 *
 * The figure uses the monospaced stat style for the reason `Type.kt` gives: a proportional face
 * changes width when 9 becomes 10, and a module that twitches twice a second is unreadable even
 * when every number in it is right. [stats] is wrapped, so a resolver that throws on an unknown id
 * costs this one figure rather than the window it is drawn in.
 */
@Composable
private fun NodeStat(
    props: Map<String, PropValue>,
    stats: (String) -> String?,
    modifier: Modifier,
) {
    val id = props.str("stat").trim()
    val resolved = if (id.isEmpty()) null else runCatching { stats(id) }.getOrNull()
    val figure = resolved?.takeIf { it.isNotBlank() }?.take(MAX_STAT_CHARS) ?: ABSENT
    val label = props.capped("label")
    val style = StatValueCompactStyle.withProps(props)
    if (label.isEmpty()) {
        Text(
            text = figure,
            modifier = modifier.then(props.paddingModifier()),
            color = props.contentColour(),
            style = style,
            textAlign = props.textAlign(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    } else {
        Column(
            modifier = modifier.then(props.paddingModifier()),
            horizontalAlignment = props.horizontalAlignment(),
        ) {
            Text(
                text = label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = StatLabelStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = figure,
                color = props.contentColour(),
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A gap. Square, so the same node works in a row and in a column without a second prop. */
@Composable
private fun NodeSpacer(props: Map<String, PropValue>, modifier: Modifier) {
    Spacer(modifier = modifier.size(props.dpOf("size", DEFAULT_SPACER_DP, 0f, MAX_SPACER_DP)))
}

/**
 * `IMAGE`: one of [BUILTIN_ICONS], or [PLACEHOLDER_ICON] when the name is not one of them.
 *
 * No URL, no file, no plugin bytes — see the file KDoc. `contentDescription` is null because the
 * icon is decoration beside a `TEXT` or `STAT` that carries the meaning; announcing a plugin's
 * icon name would read an unvetted string to a screen-reader user as though GameCore had written it.
 */
@Composable
private fun NodeImage(props: Map<String, PropValue>, modifier: Modifier) {
    val icon = BUILTIN_ICONS[props.str("icon").trim().lowercase()] ?: PLACEHOLDER_ICON
    Icon(
        imageVector = icon,
        contentDescription = null,
        modifier = modifier
            .then(props.paddingModifier())
            .size(props.dpOf("size", DEFAULT_ICON_DP, MIN_ICON_DP, MAX_ICON_DP)),
        tint = props.color("color")?.legible() ?: LocalContentColor.current,
    )
}

// ---------------------------------------------------------------------------------------------
// Prop readers
//
// Four typed accessors and a handful of mappers built on them. Every one of these answers rather
// than throws: a missing key, a `Str` where a number was meant, a `NaN` that survived JSON — all
// of them resolve to the default. `internal` rather than private so the next renderer in this
// package can reuse them and so they are reachable from a JVM unit test, which a `@Composable` is
// not (contract §6).
// ---------------------------------------------------------------------------------------------

/**
 * A prop as text.
 *
 * A number or a boolean is coerced, because `"text": 42` is a reasonable thing for an author to
 * write and dropping it would be a blank where a figure was meant. A [PropValue.ActionRef] is
 * never coerced: an action id is plumbing, and printing it on screen would leak a host identifier
 * into a label.
 */
internal fun Map<String, PropValue>.str(key: String, default: String = ""): String =
    when (val raw = this[key]) {
        is PropValue.Str -> raw.value
        is PropValue.Num -> raw.value.asDisplay()
        is PropValue.Bool -> raw.value.toString()
        else -> default
    }

/** A prop as a number, with a numeric string parsed and a non-finite value refused. */
internal fun Map<String, PropValue>.num(key: String, default: Double = 0.0): Double =
    numOrNull(key) ?: default

/** A prop as a boolean, accepting the spellings an author is likely to reach for. */
internal fun Map<String, PropValue>.bool(key: String, default: Boolean = false): Boolean =
    when (val raw = this[key]) {
        is PropValue.Bool -> raw.value
        is PropValue.Num -> raw.value != 0.0
        is PropValue.Str -> when (raw.value.trim().lowercase()) {
            "true", "yes", "on", "1" -> true
            "false", "no", "off", "0" -> false
            else -> default
        }
        else -> default
    }

/**
 * A prop as a colour, or null when the plugin did not set one.
 *
 * Null rather than a default on purpose: the call sites turn it into "inherit from the ambient
 * [MaterialTheme]", which is what makes a module follow the user's theme — including a plugin
 * theme, including AMOLED — unless it explicitly asks not to. Only a [PropValue.Color] counts; the
 * codec is where a `"#RRGGBB"` string became one, so a `Str` reaching here is a label, not a colour.
 */
internal fun Map<String, PropValue>.color(key: String): Color? =
    (this[key] as? PropValue.Color)?.let { Color(it.argb) }

/** Presence-aware numeric read: null means "absent or not a number", which `num` cannot express. */
private fun Map<String, PropValue>.numOrNull(key: String): Double? =
    when (val raw = this[key]) {
        is PropValue.Num -> raw.value.takeIf { it.isFinite() }
        is PropValue.Bool -> if (raw.value) 1.0 else 0.0
        is PropValue.Str -> raw.value.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

/** A capped string prop, ready for a `Text`. */
private fun Map<String, PropValue>.capped(key: String): String = str(key).take(MAX_TEXT_CHARS)

/** `label` or `text`, whichever the author used — the two are interchangeable on a control. */
private fun Map<String, PropValue>.label(): String = capped("label").ifBlank { capped("text") }

/** A dp dimension, clamped. */
private fun Map<String, PropValue>.dpOf(key: String, default: Float, min: Float, max: Float): Dp =
    (numOrNull(key)?.toFloat() ?: default).coerceIn(min, max).dp

/** The gap between a container's children. */
private fun Map<String, PropValue>.gap(): Dp = dpOf("gap", DEFAULT_GAP_DP, 0f, MAX_GAP_DP)

/** A node's own inset. Returns an empty [Modifier] when unset, so the chain stays short. */
private fun Map<String, PropValue>.paddingModifier(): Modifier {
    val pad = dpOf("padding", 0f, 0f, MAX_PADDING_DP)
    return if (pad == 0.dp) Modifier else Modifier.padding(pad)
}

/** Text and icon colour: the plugin's, lifted off invisible, or "inherit" when it set none. */
private fun Map<String, PropValue>.contentColour(): Color =
    color("color")?.legible() ?: Color.Unspecified

/** How many lines a text node may wrap to, clamped so a box cannot be burst by one prop. */
private fun Map<String, PropValue>.maxLines(default: Int): Int =
    (numOrNull("maxLines")?.toInt() ?: default).coerceIn(1, MAX_TEXT_LINES)

/** `align` as a column's cross-axis alignment. */
private fun Map<String, PropValue>.horizontalAlignment(): Alignment.Horizontal =
    when (str("align").trim().lowercase()) {
        "center", "centre", "middle" -> Alignment.CenterHorizontally
        "end", "right" -> Alignment.End
        else -> Alignment.Start
    }

/** `align` as a row's cross-axis alignment. */
private fun Map<String, PropValue>.verticalAlignment(): Alignment.Vertical =
    when (str("align").trim().lowercase()) {
        "center", "centre", "middle" -> Alignment.CenterVertically
        "bottom", "end" -> Alignment.Bottom
        else -> Alignment.Top
    }

/** `align` as text alignment. Null means "whatever the style says", which is the right default. */
private fun Map<String, PropValue>.textAlign(): TextAlign? =
    when (str("align").trim().lowercase()) {
        "center", "centre", "middle" -> TextAlign.Center
        "end", "right" -> TextAlign.End
        "start", "left" -> TextAlign.Start
        else -> null
    }

/**
 * `bold` and `weight`, resolved to a font weight.
 *
 * `bold: true` wins outright, because it is the shorthand and an author who wrote both meant bold.
 * `weight` accepts a name or a number; an unrecognised name falls through to the numeric reading
 * rather than to `Normal`, so `"weight": "700"` works as well as `"weight": 700`.
 */
private fun Map<String, PropValue>.fontWeight(): FontWeight? {
    if (bool("bold")) return FontWeight.Bold
    val byName = when ((this["weight"] as? PropValue.Str)?.value?.trim()?.lowercase()) {
        "thin" -> FontWeight.Thin
        "light" -> FontWeight.Light
        "normal", "regular" -> FontWeight.Normal
        "medium" -> FontWeight.Medium
        "semibold", "semi-bold" -> FontWeight.SemiBold
        "bold" -> FontWeight.Bold
        "extrabold", "extra-bold" -> FontWeight.ExtraBold
        "black" -> FontWeight.Black
        else -> null
    }
    if (byName != null) return byName
    val numeric = numOrNull("weight") ?: return null
    return FontWeight(numeric.toInt().coerceIn(MIN_FONT_WEIGHT, MAX_FONT_WEIGHT))
}

/**
 * The ambient style with the node's `size` and weight applied, and nothing else.
 *
 * Starting from the theme's style rather than building one means a plugin that sets no type props
 * gets GameCore's type scale — and a plugin theme's own family when one is active. The line height
 * is recomputed with the size, because resizing text inside an inherited line height is how a 32sp
 * figure ends up clipped against a 20sp box.
 */
private fun TextStyle.withProps(props: Map<String, PropValue>): TextStyle {
    val points = props.numOrNull("size")?.toFloat()?.coerceIn(MIN_TEXT_SP, MAX_TEXT_SP)
    val sized = if (points == null) {
        this
    } else {
        copy(fontSize = points.sp, lineHeight = (points * LINE_HEIGHT_RATIO).sp)
    }
    return props.fontWeight()?.let { sized.copy(fontWeight = it) } ?: sized
}

/** A number as a short display string: whole where it can be, one decimal where it cannot. */
private fun Double.asDisplay(): String = when {
    !isFinite() -> ABSENT
    this == truncate(this) && abs(this) < 1e9 -> toLong().toString()
    else -> (round(this * 10.0) / 10.0).toString()
}

/** Lifts a colour off fully transparent — see [MIN_CONTENT_ALPHA]. */
private fun Color.legible(): Color =
    if (alpha < MIN_CONTENT_ALPHA) copy(alpha = MIN_CONTENT_ALPHA) else this

/**
 * Black or white, whichever reads on this fill.
 *
 * The usual perceived-brightness weighting, computed here rather than taken from a Compose helper
 * so the threshold is visible and tunable in the file that depends on it. Only reached for a
 * plugin-chosen card colour; everything else takes its content colour from the theme.
 */
private fun Color.readableContentColour(): Color =
    if (0.299f * red + 0.587f * green + 0.114f * blue > 0.6f) Color.Black else Color.White
