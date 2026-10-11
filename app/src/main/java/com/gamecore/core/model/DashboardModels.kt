package com.gamecore.core.model

/**
 * The host-side model behind the Dashboard Composer (plugin #8 / Personal OS #5).
 *
 * A dashboard is an ordered list of [DashboardItem]s the user has arranged into a [DashboardLayout]. An
 * item is one of two kinds: a plugin's dashboard widget ([DashboardItemKind.PLUGIN_WIDGET], referenced by
 * its owning plugin id and the widget's own id) or a host-provided built-in component
 * ([DashboardItemKind.BUILTIN], referenced by a [BuiltinDashboardComponent] id). Nothing here holds data
 * a plugin authored and nothing here runs plugin code: an item is a *reference*, resolved against the live
 * plugin registry and the host's own stored data at render time. A reference whose target is gone resolves
 * to an honest "not currently provided" rather than a blank or a fabricated reading.
 */
enum class DashboardItemKind { PLUGIN_WIDGET, BUILTIN }

/**
 * One placed item on a dashboard. [refId] is a widget id for a plugin item and a [BuiltinDashboardComponent]
 * id for a built-in; [pluginId] is the owning plugin for a plugin item and empty for a built-in. [itemId] is
 * derived deterministically from (kind, pluginId, refId) so adding the same thing twice overwrites rather
 * than duplicates, and so a layout needs no clock or random source to persist.
 */
data class DashboardItem(
    val itemId: String,
    val kind: DashboardItemKind,
    val pluginId: String,
    val refId: String,
    val position: Int,
    val visible: Boolean = true,
) {
    /** The dedupe key: two items are "the same thing" when their kind, plugin and reference all match. */
    val identity: String get() = "${kind.name}|$pluginId|$refId"

    companion object {
        fun pluginWidget(pluginId: String, widgetId: String, position: Int): DashboardItem =
            DashboardItem(
                itemId = "plugin:$pluginId#$widgetId",
                kind = DashboardItemKind.PLUGIN_WIDGET,
                pluginId = pluginId,
                refId = widgetId,
                position = position,
            )

        fun builtin(componentId: String, position: Int): DashboardItem =
            DashboardItem(
                itemId = "builtin:$componentId",
                kind = DashboardItemKind.BUILTIN,
                pluginId = "",
                refId = componentId,
                position = position,
            )
    }
}

/**
 * A named, orderable dashboard the user composes. [isSelected] marks the one dashboard currently shown; the
 * schema permits several but the v1 composer manages the single selected one. [normalised] is the invariant
 * every persisted layout is put through: it drops duplicate references, caps the plugin-widget items at
 * [MAX_PLUGIN_WIDGET_ITEMS] (the same ceiling the plugin registry enforces across all widgets, so a layout
 * can never ask for more widgets than the host will ever draw), and renumbers positions to a dense 0..n so
 * the stored order is always canonical.
 */
data class DashboardLayout(
    val id: Long,
    val name: String,
    val schemaVersion: Int = DASHBOARD_SCHEMA_VERSION,
    val isSelected: Boolean,
    val items: List<DashboardItem>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
) {
    fun normalised(): DashboardLayout {
        val seen = HashSet<String>()
        var pluginCount = 0
        val kept = ArrayList<DashboardItem>(items.size)
        for (item in items.sortedBy { it.position }) {
            if (!seen.add(item.identity)) continue
            if (item.kind == DashboardItemKind.PLUGIN_WIDGET) {
                if (pluginCount >= MAX_PLUGIN_WIDGET_ITEMS) continue
                pluginCount++
            }
            kept.add(item)
        }
        return copy(items = kept.mapIndexed { index, item -> item.copy(position = index) })
    }

    val pluginWidgetCount: Int get() = items.count { it.kind == DashboardItemKind.PLUGIN_WIDGET }

    fun hasPluginWidgetCapacity(): Boolean = pluginWidgetCount < MAX_PLUGIN_WIDGET_ITEMS

    companion object {
        const val MAX_NAME_LENGTH = 40

        /**
         * The most plugin-widget items one layout may hold. Deliberately equal to the plugin registry's own
         * `PluginDashboardCodec.MAX_WIDGETS` (32): the registry will never expose more than that many active
         * widgets at once, so a layout that could hold more would only ever hold references the host cannot
         * resolve. Keeping the two equal is what stops the composer from implying a capacity the host lacks.
         */
        const val MAX_PLUGIN_WIDGET_ITEMS = 32

        fun empty(id: Long = 0L, name: String = "My dashboard", nowMillis: Long = 0L): DashboardLayout =
            DashboardLayout(
                id = id,
                name = name,
                isSelected = true,
                items = emptyList(),
                createdAtMillis = nowMillis,
                updatedAtMillis = nowMillis,
            )
    }
}

/** The dashboard persistence format version, stored per layout so a future format can migrate the data. */
const val DASHBOARD_SCHEMA_VERSION = 1
