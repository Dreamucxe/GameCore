package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.DataScopes
import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.OverlayIcons
import com.gamecore.plugin.sdk.OverlaySizes
import com.gamecore.plugin.sdk.OverlayStats
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.plugin

/**
 * "Battery Badge" — a compact in-game battery and temperature readout.
 *
 * The module is deliberately two figures and no more: battery level and the host's temperature
 * reading. It asks for `thermals` only, because both figures live behind that scope, and for
 * `create_overlay` because it draws over a game. It cannot read charge current, battery health,
 * cycle count or a per-cell temperature; none of those is in GameCore's approved feed.
 *
 * The module is read-only by construction: the overlay window takes no touches, and the SDK's
 * overlay scope has no button, slider, switch or scroll node to author.
 */
fun batteryBadge(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.batterybadge",
    name = "Battery Badge",
) {
    version = 1
    versionName = "1.0.0"
    developer = "Dream"
    description =
        "A compact in-game battery and temperature badge. It reads GameCore's approved thermals " +
            "feed; it cannot read charge current or battery health."
    changelog = "Initial release."

    request(Permissions.CREATE_OVERLAY)
    request(
        Permissions.READ_APPROVED_DATA,
        scopes = setOf(DataScopes.THERMALS),
    )
    request(Permissions.CHANGE_ICON)

    pill {
        id = "battery-badge"
        title = "Battery badge"
        anchor(x = 0.84, y = 0.08)
        size = OverlaySizes.MEDIUM
        opacity = 82
        row(gap = 10, align = "center") {
            image(OverlayIcons.BATTERY, size = 20)
            column(gap = 1, align = "center") {
                text("BATTERY", color = "#B3FFFFFF", size = 9)
                stat(OverlayStats.BATTERY, size = 18)
            }
            column(gap = 1, align = "center") {
                text("TEMP", color = "#B3FFFFFF", size = 9)
                stat(OverlayStats.TEMP, size = 18)
            }
        }
    }

    icon(slot = IconSlots.NEON)
}
