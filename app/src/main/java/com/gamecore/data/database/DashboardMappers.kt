package com.gamecore.data.database

import com.gamecore.core.common.TextSanitizer
import com.gamecore.core.model.DashboardItem
import com.gamecore.core.model.DashboardItemKind
import com.gamecore.core.model.DashboardLayout

/**
 * Mapping between the dashboard domain models and their Room rows.
 *
 * Deliberately a self-contained object rather than new methods on the shared [Mappers]: the dashboard
 * tables are a new, isolated pair read only through [DashboardDao], so their mapping has no reason to
 * sit next to the HUD/crosshair/profile mappers and every reason not to widen that already-large file.
 * It mirrors [Mappers]' two load-bearing habits exactly — a name is sanitised and never written blank,
 * and a row whose enum name this build does not know is dropped on the way back rather than defaulted
 * or thrown on — so a dashboard written by a newer build degrades to the items this build understands
 * instead of refusing to open the database.
 */
internal object DashboardMappers {

    fun toEntity(layout: DashboardLayout, nowMillis: Long): DashboardLayoutEntity = DashboardLayoutEntity(
        id = layout.id,
        name = TextSanitizer.sanitizeName(layout.name, DashboardLayout.MAX_NAME_LENGTH)
            .ifBlank { "My dashboard" },
        schemaVersion = layout.schemaVersion,
        isSelected = layout.isSelected,
        createdAtMillis = if (layout.createdAtMillis > 0L) layout.createdAtMillis else nowMillis,
        updatedAtMillis = nowMillis,
    )

    fun toItemEntity(item: DashboardItem, layoutId: Long): DashboardItemEntity = DashboardItemEntity(
        itemId = item.itemId,
        layoutId = layoutId,
        kind = item.kind.name,
        pluginId = item.pluginId,
        refId = item.refId,
        position = item.position,
        visible = item.visible,
    )

    fun toModel(entity: DashboardLayoutEntity, items: List<DashboardItemEntity>): DashboardLayout =
        DashboardLayout(
            id = entity.id,
            name = entity.name,
            schemaVersion = entity.schemaVersion,
            isSelected = entity.isSelected,
            // An item whose kind this build does not recognise is dropped, never defaulted: a dashboard
            // that silently turns an unknown item into the wrong one is worse than one short an item.
            items = items.mapNotNull { toItemOrNull(it) },
            createdAtMillis = entity.createdAtMillis,
            updatedAtMillis = entity.updatedAtMillis,
        ).normalised()

    private fun toItemOrNull(entity: DashboardItemEntity): DashboardItem? {
        val kind = DashboardItemKind.entries.firstOrNull { it.name == entity.kind } ?: return null
        return DashboardItem(
            itemId = entity.itemId,
            kind = kind,
            pluginId = entity.pluginId,
            refId = entity.refId,
            position = entity.position,
            visible = entity.visible,
        )
    }
}
