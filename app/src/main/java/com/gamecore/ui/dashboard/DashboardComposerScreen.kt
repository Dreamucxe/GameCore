package com.gamecore.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gamecore.core.model.MetricHistory
import com.gamecore.core.model.PerformanceSnapshot
import com.gamecore.core.plugin.dashboard.WidgetSize
import com.gamecore.ui.components.NoteBanner
import com.gamecore.ui.components.RowDivider
import com.gamecore.ui.components.ScreenBottomPadding
import com.gamecore.ui.components.ScreenHeader
import com.gamecore.ui.components.ScreenPadding
import com.gamecore.ui.components.SectionCard
import com.gamecore.ui.components.SwitchRow
import com.gamecore.ui.components.Tone
import com.gamecore.ui.plugins.render.PluginNodeTree

/**
 * The stateless Dashboard Composer screen: a pure function of [DashboardComposerUiState] and the callbacks
 * that change it. It holds no ViewModel and no rules — [DashboardComposerRoute] binds those — so a Compose
 * preview or a screenshot test can drive every panel below with literals.
 *
 * ## One card per item, always
 *
 * The one structural rule of this screen is that the *host* draws exactly one [SectionCard] around every
 * placed item and renders the item's content bare inside it. A built-in panel, a plugin widget and an item
 * that cannot be drawn right now all sit in the same chrome, titled by the host from [ResolvedItem.title] and
 * [ResolvedItem.subtitle] — never from the widget itself. That is what keeps the screen from being made to
 * lie by a plugin: the card names whose widget it is even when the widget is gone, and never borrows a title
 * from one the host is not actually drawing. The content inside is the only part a plugin authored, and it is
 * drawn by the read-only [PluginNodeTree], which attaches no gesture; this screen hangs no click, toggle or
 * drag on it either, because a dashboard widget is a readout, not a control.
 *
 * ## Viewing versus editing
 *
 * Not editing, the screen draws [DashboardComposerUiState.visibleItems]: the host hides an item the user
 * switched off, it does not delete it. Editing, it draws every item in [DashboardComposerUiState.resolvedItems]
 * — hidden ones included — and hangs the arrange controls on each: two reorder arrows, a remove, and a
 * visibility switch. The arrows are disabled at the ends of the list rather than hidden, so the controls keep
 * their place as rows move. Remove is a plain action, not a confirmed one, because it drops a reference the
 * user can re-add from the list below rather than destroying anything. Edits persist as they are made — there
 * is no Save button, a dashboard being a thing you arrange and leave — so the top-bar action reads "Done".
 *
 * ## Honest states
 *
 * An item the host cannot prove it can draw shows [ItemUnavailableReason.label] in a worded banner inside the
 * still-titled card, so the user sees which item went and is told plainly why. An empty dashboard says so and
 * points at the add controls, which the ViewModel has already opened edit mode to show. Nothing here invents
 * a figure or a reason it cannot stand behind.
 */
@Composable
fun DashboardComposerScreen(
    state: DashboardComposerUiState,
    snapshot: PerformanceSnapshot?,
    history: MetricHistory,
    onBack: () -> Unit,
    onSetEditing: (Boolean) -> Unit,
    onAddBuiltin: (componentId: String) -> Unit,
    onAddPluginWidget: (pluginId: String, widgetId: String) -> Unit,
    onRemove: (itemId: String) -> Unit,
    onSetVisible: (itemId: String, visible: Boolean) -> Unit,
    onMoveUp: (itemId: String) -> Unit,
    onMoveDown: (itemId: String) -> Unit,
    stats: (id: String) -> String?,
    series: (id: String) -> List<Float>?,
    modifier: Modifier = Modifier,
) {
    // Re-made whenever the live frame changes, which is the point: reading `snapshot`/`history` here keys the
    // resolvers to the latest sample, and handing PluginNodeTree a fresh resolver instance is what makes it
    // re-read the current value through [stats]/[series]. A bare function reference would be a stable one
    // reading a value Compose does not track, so every figure a widget draws would freeze at the first frame.
    // Mirrors the Home widgets section verbatim; the chart decode is keyed on its node and not the frame, so
    // this re-runs the resolver without re-parsing the tree.
    val statsResolver = remember(snapshot) { { id: String -> stats(id) } }
    val seriesResolver = remember(history) { { id: String -> series(id) } }

    val padded = Modifier.padding(horizontal = ScreenPadding)
    val items = if (state.isEditing) state.resolvedItems else state.visibleItems

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 4.dp, bottom = ScreenBottomPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ScreenHeader(
                title = "Dashboard",
                subtitle = if (state.isEditing) {
                    "Add, hide and reorder your panels. Changes save as you make them."
                } else {
                    "The panels on your dashboard. Tap Edit to change them."
                },
                onBack = onBack,
                // The edit toggle lives in the header's trailing action slot, the same place every other
                // screen puts its top-bar action. It reads "Done" while editing because the edits are already
                // saved — there is nothing to commit, only a mode to leave.
                action = {
                    TextButton(onClick = { onSetEditing(!state.isEditing) }) {
                        Text(if (state.isEditing) "Done" else "Edit")
                    }
                },
            )
        }

        when {
            state.isLoading -> item { LoadingBody() }
            else -> {
                if (state.isEmpty) {
                    item {
                        NoteBanner(
                            text = "Your dashboard is empty. Add built-in panels or plugin widgets below " +
                                "to fill it.",
                            tone = Tone.Muted,
                            modifier = padded,
                        )
                    }
                }

                itemsIndexed(items, key = { _, resolved -> resolved.item.itemId }) { index, resolved ->
                    ComposerItemCard(
                        resolved = resolved,
                        editing = state.isEditing,
                        isFirst = index == 0,
                        isLast = index == items.lastIndex,
                        chartData = state.chartData,
                        statsResolver = statsResolver,
                        seriesResolver = seriesResolver,
                        onRemove = onRemove,
                        onSetVisible = onSetVisible,
                        onMoveUp = onMoveUp,
                        onMoveDown = onMoveDown,
                        modifier = padded,
                    )
                }

                if (state.isEditing) {
                    addControls(
                        state = state,
                        onAddBuiltin = onAddBuiltin,
                        onAddPluginWidget = onAddPluginWidget,
                        padded = padded,
                    )
                }
            }
        }
    }
}

/**
 * One placed item, in the host's single card.
 *
 * The chrome — [ResolvedItem.title], [ResolvedItem.subtitle] and an icon marking the *kind* of item — stays
 * truthful even when the content below is a "this is gone" banner. The content is dispatched on
 * [ResolvedContent] and drawn bare: a built-in through [BuiltinComponentContent], a plugin widget through the
 * read-only [PluginNodeTree] given a per-span minimum height, and an unavailable item through a worded
 * [NoteBanner] carrying [ItemUnavailableReason.label].
 *
 * The arrange controls show only when [editing]. The arrows are disabled at the ends ([isFirst]/[isLast])
 * rather than removed; remove is unconfirmed because the item is a re-addable reference, not something
 * destroyed; and the switch reads the item's own `visible` flag, because the draft the ViewModel holds is the
 * only copy of that truth.
 */
@Composable
private fun ComposerItemCard(
    resolved: ResolvedItem,
    editing: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    chartData: DashboardChartData,
    statsResolver: (String) -> String?,
    seriesResolver: (String) -> List<Float>?,
    onRemove: (String) -> Unit,
    onSetVisible: (String, Boolean) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    SectionCard(
        title = resolved.title,
        subtitle = resolved.subtitle,
        icon = when (resolved.content) {
            is ResolvedContent.Builtin -> Icons.Filled.Insights
            is ResolvedContent.PluginWidget -> Icons.Filled.Extension
            is ResolvedContent.Unavailable -> Icons.Filled.Warning
        },
        modifier = modifier,
    ) {
        when (val content = resolved.content) {
            is ResolvedContent.Builtin ->
                BuiltinComponentContent(component = content.component, data = chartData)
            is ResolvedContent.PluginWidget -> PluginNodeTree(
                node = content.active.widget.content,
                stats = statsResolver,
                series = seriesResolver,
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = minHeightForSpan(content.active.widget.size)),
            )
            is ResolvedContent.Unavailable ->
                NoteBanner(text = content.reason.label, tone = Tone.Warning)
        }

        if (editing) {
            RowDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onMoveUp(resolved.item.itemId) }, enabled = !isFirst) {
                    Icon(
                        imageVector = Icons.Filled.ArrowUpward,
                        contentDescription = "Move ${resolved.title} up",
                        modifier = Modifier.size(18.dp),
                    )
                }
                IconButton(onClick = { onMoveDown(resolved.item.itemId) }, enabled = !isLast) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDownward,
                        contentDescription = "Move ${resolved.title} down",
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = { onRemove(resolved.item.itemId) }) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Remove")
                }
            }
            SwitchRow(
                title = "Show on dashboard",
                checked = resolved.item.visible,
                onCheckedChange = { visible -> onSetVisible(resolved.item.itemId, visible) },
                description = "Hidden panels are kept here but aren't drawn on your dashboard until you " +
                    "show them again.",
            )
        }
    }
}

/** The house loading indicator: a small, centred spinner, matching every other screen's first frame. */
@Composable
private fun LoadingBody() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 36.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}

/**
 * The add affordances, shown only in edit mode: one card for the built-in panels not yet placed, and one for
 * the plugin widgets not yet placed.
 *
 * A [LazyListScope] extension so each card is its own lazy item, the same shape the rest of the package
 * factors a list body into. Both lists come straight off the reducer — [DashboardComposerUiState.addableBuiltins]
 * and [DashboardComposerUiState.addablePluginWidgets], each already filtered to what is not on the dashboard
 * yet — so there is nothing for the UI to re-derive. When the layout is at its plugin-widget cap the plugin
 * card says so and offers nothing, because the reducer has set [DashboardComposerUiState.atPluginCapacity] and
 * emptied that list rather than let the screen imply a capacity the host lacks.
 */
private fun LazyListScope.addControls(
    state: DashboardComposerUiState,
    onAddBuiltin: (String) -> Unit,
    onAddPluginWidget: (String, String) -> Unit,
    padded: Modifier,
) {
    item {
        SectionCard(title = "Add a built-in panel", icon = Icons.Filled.Insights, modifier = padded) {
            if (state.addableBuiltins.isEmpty()) {
                NoteBanner(text = "Every built-in panel is already on your dashboard.", tone = Tone.Muted)
            } else {
                state.addableBuiltins.forEachIndexed { index, component ->
                    if (index > 0) RowDivider()
                    AddRow(
                        title = component.title,
                        detail = component.summary,
                        onAdd = { onAddBuiltin(component.id) },
                    )
                }
            }
        }
    }

    item {
        SectionCard(title = "Add a plugin widget", icon = Icons.Filled.Extension, modifier = padded) {
            when {
                state.atPluginCapacity -> NoteBanner(
                    text = "You've reached the limit of plugin widgets on one dashboard. Remove one to add " +
                        "another.",
                    tone = Tone.Warning,
                    icon = Icons.Filled.Info,
                )
                state.addablePluginWidgets.isEmpty() -> NoteBanner(
                    text = "No plugin widgets are available to add right now.",
                    tone = Tone.Muted,
                )
                else -> state.addablePluginWidgets.forEachIndexed { index, active ->
                    if (index > 0) RowDivider()
                    AddRow(
                        title = active.widget.title,
                        detail = active.pluginName,
                        onAdd = { onAddPluginWidget(active.pluginId, active.widget.id) },
                    )
                }
            }
        }
    }
}

/**
 * One offer in an add card: what would be placed, and the button that places it.
 *
 * Two lines so the summary gets the full width instead of being squeezed against the button and wrapping —
 * the same reason the arrange rows are two lines. The button carries a visible "Add" label beside its icon so
 * a screen reader announces an action and not a bare glyph.
 */
@Composable
private fun AddRow(
    title: String,
    detail: String,
    onAdd: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        TextButton(onClick = onAdd) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Add")
        }
    }
}

/**
 * The minimum height a plugin widget's content is given for its declared [WidgetSize].
 *
 * Copied as a private helper rather than shared because the identical mapping is `private` to the Home
 * widgets section ([com.gamecore.ui.home.PluginWidgetsSection]): the span-to-size decision belongs to each
 * surface that draws a widget, since [WidgetSize] carries no dp of its own. Here, as on Home, the panels
 * stack full-width rather than tiling a grid, so a span is a height floor the content lays out within and a
 * card grows past it whenever its content needs more.
 */
private fun minHeightForSpan(size: WidgetSize): Dp = when (size) {
    WidgetSize.SMALL -> 56.dp
    WidgetSize.MEDIUM -> 88.dp
    WidgetSize.LARGE -> 132.dp
    WidgetSize.FULL -> 180.dp
}

