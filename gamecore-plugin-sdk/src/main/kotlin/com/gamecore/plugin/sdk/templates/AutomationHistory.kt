package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.DataScopes
import com.gamecore.plugin.sdk.OverlayIcons
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.Sdk
import com.gamecore.plugin.sdk.plugin

/**
 * "Automation History" — a read-only window onto the user's own automation-history trail: the
 * record GameCore keeps of which automation rules have fired on the device, and when.
 *
 * Like [cyberpunkGameCore], [comicGameCore] and [liquidGlassGameCore], this is a worked authoring
 * example that lives *beside* [Templates] in its own file, so it can be added without editing the
 * shared template library. It carries **data only** — a `screens/screens.json` of static explainer
 * screens and a manifest that asks for two low-risk grants — and no code, callback, shell, native
 * entry or filesystem reach, because the package format admits none.
 *
 * ### What it actually does, and the honest limit
 * This plugin's own contribution is **static text**: three read-only screens that explain the
 * automation-history trail. It does *not* ship, hold or draw the trail itself. The trail is
 * **host-owned** — a built-in, Room-backed GameCore screen that the plugin's `automation_history`
 * read scope merely *unlocks* — and it is **empty until an automation rule actually fires**. A
 * plugin can never write to it, pre-fill it or fabricate an entry: there is no API by which plugin
 * data flows *into* GameCore's records, only a scoped, read-only aperture *out* of them. So an
 * honest explainer is exactly what this plugin can be, and it says plainly that the live trail is
 * GameCore's and that an empty trail is the expected state until the first rule runs.
 *
 * ### Why `api` and `minHostVersion` are set by hand
 * Unlike a theme or a screen — where touching a feature bumps the manifest `api` for you — a *data
 * scope* is not a section and bumps nothing automatically. Declaring [DataScopes.AUTOMATION_HISTORY]
 * is an **API-4** meaning an older host could not honour, so this plugin raises `api` to
 * [Sdk.API_V4] itself. The `screens` section's own per-use bump only reaches [Sdk.API_V3] (3), which
 * is why leaving `api` to that default would understate what the package needs. The scope also
 * requires host build **29**, and the SDK's [Sdk.HOST_VERSION_CODE] constant is still pinned at 28,
 * so `minHostVersion` is set to the literal `29` rather than that stale default. The upshot: an
 * older GameCore correctly turns this plugin away as too new (`Compatibility.TooNew` /
 * `HostTooOld`), and the current build (versionCode 29) loads it.
 *
 * ### Permissions
 *  - [Permissions.READ_APPROVED_DATA] scoped to [DataScopes.AUTOMATION_HISTORY] — the read-only
 *    aperture onto the host's automation-history trail. This is the whole point of the plugin.
 *  - [Permissions.CHANGE_UI] — required for the declarative `screens` section (screens are gated by
 *    CHANGE_UI, the same grant a theme uses).
 *
 * The screens use only `card`, `grid`, `text` and `image` (GameCore's own [OverlayIcons]); no
 * `stat`, `button`, `slider` or `switch`, because the plugin reports nothing live and dispatches
 * nothing — a static explainer should not draw a control that is inert or a figure that renders
 * absent.
 */
fun automationHistory(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.automationhistory",
    name = "Automation History",
) {
    version = 1
    versionName = "1.0.0"
    // Set by hand: a data scope bumps nothing automatically, and the `screens` bump only reaches
    // Sdk.API_V3 (3). The automation_history scope is an API-4 meaning, so api is API_V4 (4), and
    // the scope requires host build 29 while Sdk.HOST_VERSION_CODE is still 28, so minHostVersion
    // is the literal 29. maxOf keeps api at 4 even after the screens section's own bump to 3.
    api = Sdk.API_V4
    minHostVersion = 29
    developer = "Dream"
    description =
        "A read-only window onto your automation history — the record of which rules have run, " +
            "and when. The trail itself is kept by GameCore and stays empty until a rule fires."
    changelog = "Initial release."

    // The read-only aperture onto the host-owned trail, plus the grant the screens section needs.
    request(Permissions.READ_APPROVED_DATA, scopes = setOf(DataScopes.AUTOMATION_HISTORY))
    request(Permissions.CHANGE_UI)

    screens {
        // What the trail is, and whose it is.
        screen("overview", "Automation History", icon = OverlayIcons.TIMELINE) {
            card(padding = 16, gap = 12) {
                image(OverlayIcons.TIMELINE, size = 40)
                text("Automation History", size = 22, bold = true)
                text(
                    "A running record of which of your automation rules have fired on this " +
                        "device, and when.",
                    size = 15,
                )
                text(
                    "GameCore keeps this trail for you. This plugin only opens a read-only " +
                        "window onto it — it can never add, change or remove an entry.",
                    size = 14,
                )
            }
        }

        // That it fills as rules fire, and is empty until then.
        screen("how-it-fills", "How It Fills", icon = OverlayIcons.INSIGHTS) {
            card(padding = 16, gap = 12) {
                text("How the trail fills", size = 20, bold = true)
                text(
                    "The history starts empty. It fills only when an automation rule actually " +
                        "runs — nothing is recorded until a rule's trigger and conditions are met.",
                    size = 15,
                )
                grid(columns = 3, gap = 8) {
                    card(padding = 12, gap = 6) {
                        image(OverlayIcons.BOLT, size = 28)
                        text("The rule that ran", size = 13, bold = true)
                    }
                    card(padding = 12, gap = 6) {
                        image(OverlayIcons.GAME, size = 28)
                        text("What triggered it", size = 13, bold = true)
                    }
                    card(padding = 12, gap = 6) {
                        image(OverlayIcons.TIMER, size = 28)
                        text("When it happened", size = 13, bold = true)
                    }
                }
                text(
                    "Until that first rule fires the trail stays empty — that is expected, not a " +
                        "fault.",
                    size = 14,
                )
            }
        }

        // The honest limits, stated plainly.
        screen("about", "About This Plugin", icon = OverlayIcons.INFO) {
            card(padding = 16, gap = 12) {
                text("About this plugin", size = 20, bold = true)
                text(
                    "Automation History is a data-only plugin. These screens are static text — " +
                        "the live trail is owned and stored by GameCore, not by this plugin, and " +
                        "the plugin can only read it, never write to it.",
                    size = 15,
                )
                card(padding = 12, gap = 8) {
                    image(OverlayIcons.SHIELD, size = 24)
                    text("What it can see", size = 14, bold = true)
                    text(
                        "With your permission it reads your automation history, and nothing " +
                            "else. It holds no other data access and cannot change anything.",
                        size = 13,
                    )
                }
                text(
                    "It needs a current build of GameCore. An older build correctly turns it " +
                        "away as too new to run.",
                    size = 13,
                )
            }
        }
    }
}
