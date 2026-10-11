package com.gamecore.ui.plugins

import com.gamecore.core.plugin.InstalledPlugin
import com.gamecore.core.plugin.PluginPermission
import com.gamecore.core.plugin.PluginSection
import com.gamecore.core.plugin.ui.PluginScreen

/**
 * The one rule that decides whether a plugin screen route may draw the plugin's content, or must
 * show an honest "unavailable" state instead — and, when it may not, exactly why.
 *
 * This lives here as a pure function over plain inputs, not inside the route composable or its
 * ViewModel, for the same reason the plugin detail screen's grant arithmetic does
 * ([com.gamecore.ui.plugins.canEnable]): a screen that drew a plugin's UI one condition past the
 * point it should have — after the kill switch, after the user revoked "Change the look", after
 * the plugin was disabled — would be the plugin reaching the user through a surface the user
 * believed they had closed. Getting that wrong is a trust failure, not a cosmetic one, so it is
 * written where a JVM test can call it with a hand-built plugin and no Android at all.
 *
 * The order the reasons are checked in is the order of how terminal each is, worst first, so a
 * route reachable from a stale back stack after several things changed at once reports the most
 * fundamental one:
 *
 *  1. **Removed** — the plugin is gone (uninstalled in the manager, or an id that never existed).
 *     Nothing the user does on this route brings it back.
 *  2. **Incompatible** — this build cannot run the package at all. A newer GameCore might; this
 *     route cannot. Effectively unreachable, because the entry point only appears while the plugin
 *     is active, but checked so a deep link or a stale link cannot slip past it.
 *  3. **KillSwitch** — the global "revoke all plugin access" is on, so no plugin draws anything.
 *  4. **Disabled** — the plugin is turned off, or suspended for this run after repeated failure.
 *  5. **NotGranted** — "Change the look" ([PluginPermission.CHANGE_UI]) is not in the *grant*.
 *     Declarative screens are part of what that permission covers, so a plugin that may not restyle
 *     the app may not draw a screen in it either. Read off the stored grant, never a draft.
 *  6. **NotFound** — the plugin is active and permitted, but it declares no `screens` section, or
 *     none of the screens it carries has this id. A link to a screen the plugin dropped in an
 *     update lands here.
 *
 * Only when all six pass is the screen [Available]. The kill switch and the grant are the two the
 * route re-reads live from the registry on every emission, so revoking either closes an open
 * screen on the next frame rather than only on the next navigation.
 */
sealed interface PluginScreenResolution {

    /** The screen may be drawn: here it is, already validated by the codec. */
    data class Available(val screen: PluginScreen) : PluginScreenResolution

    /** The screen may not be drawn, and [reason] carries a line to show the user why. */
    data class Unavailable(val reason: Reason) : PluginScreenResolution {
        /** The user-facing sentence for this state. */
        val message: String get() = reason.message
    }

    /** Why a plugin screen could not be shown. Each carries the sentence the route renders. */
    enum class Reason(val message: String) {
        REMOVED("This plugin is no longer installed, so its screens are gone."),
        INCOMPATIBLE("This plugin needs a different version of GameCore and cannot run here."),
        KILL_SWITCH("Plugin access is turned off. Turn it back on in Plugins to use this screen."),
        DISABLED("This plugin is turned off. Enable it in Plugins to use this screen."),
        NOT_GRANTED("This plugin's \"Change the look\" permission is not granted, so it cannot draw this screen."),
        NOT_FOUND("This screen is no longer part of the plugin."),
    }
}

/**
 * Resolves whether [screenId] of [plugin] may be drawn right now.
 *
 * Pure and total: a null [plugin] (not installed, or not loaded into the passed list) is
 * [PluginScreenResolution.Reason.REMOVED], never an exception. [screens] is the plugin's already
 * parsed, already validated screen list, and [screenId] is matched trimmed and lower-cased because
 * the codec stores every id in that form — a link carrying stray case still finds its screen.
 */
fun resolvePluginScreen(
    plugin: InstalledPlugin?,
    killSwitchOn: Boolean,
    screens: List<PluginScreen>,
    screenId: String,
): PluginScreenResolution {
    if (plugin == null) return unavailable(PluginScreenResolution.Reason.REMOVED)
    if (!plugin.manifest.compatibility.isRunnable) {
        return unavailable(PluginScreenResolution.Reason.INCOMPATIBLE)
    }
    if (killSwitchOn) return unavailable(PluginScreenResolution.Reason.KILL_SWITCH)
    if (!plugin.enabled || plugin.suspended) return unavailable(PluginScreenResolution.Reason.DISABLED)
    if (!plugin.grant.has(PluginPermission.CHANGE_UI)) {
        return unavailable(PluginScreenResolution.Reason.NOT_GRANTED)
    }
    if (PluginSection.SCREENS !in plugin.manifest.declares) {
        return unavailable(PluginScreenResolution.Reason.NOT_FOUND)
    }
    val wanted = screenId.trim().lowercase()
    val screen = screens.firstOrNull { it.id == wanted }
        ?: return unavailable(PluginScreenResolution.Reason.NOT_FOUND)
    return PluginScreenResolution.Available(screen)
}

private fun unavailable(reason: PluginScreenResolution.Reason) = PluginScreenResolution.Unavailable(reason)
