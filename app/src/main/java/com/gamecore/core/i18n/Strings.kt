package com.gamecore.core.i18n

import androidx.compose.runtime.Immutable

/**
 * One language's words, read over the English catalogue.
 *
 * A missing key is never an exception and never a blank label: [get] falls through to English and
 * finally to the key itself, so a catalogue that predates a new screen still renders that screen in
 * English instead of rendering it empty. That is the whole reason this is a lookup object rather than
 * generated constants — translations are data that can arrive after the code, and the fallback is what
 * makes that safe.
 *
 * Keys are namespaced by surface (`nav.`, `setup.`, `settings.`) rather than by screen class, because
 * the same word is deliberately reused across screens and one translation for "Settings" is the point.
 */
@Immutable
class Strings internal constructor(
    private val values: Map<String, String>,
    private val fallback: Map<String, String>,
) {
    operator fun get(key: String): String = values[key] ?: fallback[key] ?: key

    /**
     * A catalogue line with `{0}`, `{1}`… replaced.
     *
     * Positional braces rather than `%s`: translators reorder words, and a format string whose
     * arguments are named by position survives that while `String.format` positions do not. Braces
     * also cannot be eaten by a translation tool the way a lone `%` can.
     */
    fun format(key: String, vararg args: Any): String {
        var text = get(key)
        args.forEachIndexed { index, value ->
            text = text.replace("{$index}", value.toString())
        }
        return text
    }

    // ------------------------------------------------------------------------------- common words

    val ok get() = get("common.ok")
    val cancel get() = get("common.cancel")
    val close get() = get("common.close")
    val done get() = get("common.done")
    val save get() = get("common.save")
    val back get() = get("common.back")
    val next get() = get("common.next")
    val finish get() = get("common.finish")
    val skip get() = get("common.skip")
    val retry get() = get("common.retry")
    val search get() = get("common.search")
    val remove get() = get("common.remove")
    val dismiss get() = get("common.dismiss")
    val continueLabel get() = get("common.continue")
    val notNow get() = get("common.notNow")
    val enabled get() = get("common.enabled")
    val disabled get() = get("common.disabled")
    val on get() = get("common.on")
    val off get() = get("common.off")
    val loading get() = get("common.loading")
    val error get() = get("common.error")

    // ---------------------------------------------------------------------------------- navigation

    val navHome get() = get("nav.home")
    val navGames get() = get("nav.games")
    val navAimLab get() = get("nav.aimLab")
    val navSessions get() = get("nav.sessions")
    val navSettings get() = get("nav.settings")

    // ------------------------------------------------------------------------------------ language

    val languageTitle get() = get("language.title")
    val languageDescription get() = get("language.description")
    val languageSystem get() = get("language.system")
    val languageSystemDescription get() = get("language.systemDescription")
    val languageChoose get() = get("language.choose")
    val languageSearch get() = get("language.search")
    val languageNoResults get() = get("language.noResults")

    // --------------------------------------------------------------------------------------- setup

    val setupSkipSetup get() = get("setup.skipSetup")
    val setupSkipStep get() = get("setup.skipStep")
    val setupLeaveTitle get() = get("setup.leaveTitle")
    val setupLeaveMessage get() = get("setup.leaveMessage")
    val setupLeaveConfirm get() = get("setup.leaveConfirm")
    val setupCheckAgain get() = get("setup.checkAgain")
    val setupLanguageTitle get() = get("setup.languageTitle")
    val setupLanguageBody get() = get("setup.languageBody")
    val setupStaysTitle get() = get("setup.staysTitle")
    val setupStaysBody get() = get("setup.staysBody")
    val setupConnectsTitle get() = get("setup.connectsTitle")
    val setupConnectsBody get() = get("setup.connectsBody")

    fun setupStep(index: Int, total: Int) = format("setup.step", index, total)

    fun setupTitle(step: String): String = get("setup.title.$step")

    fun setupLead(step: String): String = get("setup.lead.$step")

    // ------------------------------------------------------------------------------------ settings

    val settingsTitle get() = get("settings.title")
    val settingsSubtitle get() = get("settings.subtitle")
    val settingsAppearance get() = get("settings.appearance")
    val settingsDisplayText get() = get("settings.displayText")
    val settingsAccessibility get() = get("settings.accessibility")
    val settingsBehavior get() = get("settings.behavior")
    val settingsOverlays get() = get("settings.overlays")
    val settingsGames get() = get("settings.games")
    val settingsDetection get() = get("settings.detection")
    val settingsMonitoring get() = get("settings.monitoring")
    val settingsNetwork get() = get("settings.network")
    val settingsRecording get() = get("settings.recording")
    val settingsAccess get() = get("settings.access")
    val settingsData get() = get("settings.data")
    val settingsAbout get() = get("settings.about")
    val settingsLanguage get() = get("settings.language")
    val settingsLanguageDescription get() = get("settings.languageDescription")
    val settingsTheme get() = get("settings.theme")
    val settingsAccent get() = get("settings.accent")
    val settingsCustomColour get() = get("settings.customColour")
    val settingsWallpaperColours get() = get("settings.wallpaperColours")
    val settingsBackgroundImage get() = get("settings.backgroundImage")

    // ------------------------------------------------------------------------------------- plugins

    val pluginsTitle get() = get("plugins.title")
    val pluginsSubtitle get() = get("plugins.subtitle")
    val pluginsSearch get() = get("plugins.search")
    val pluginsSearchHint get() = get("plugins.searchHint")
    val pluginsChooseFiles get() = get("plugins.chooseFiles")
    val pluginsRemove get() = get("plugins.remove")
    val pluginsRefresh get() = get("plugins.refresh")

    // ----------------------------------------------------------------------------------- what's new

    val whatsNewTitle get() = get("whatsnew.title")
    val whatsNewSubtitle get() = get("whatsnew.subtitle")

    fun whatsNewVersion(version: String) = format("whatsnew.version", version)
}
