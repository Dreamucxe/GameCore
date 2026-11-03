package com.gamecore.pluginmaker

/**
 * The Plugin Maker's own words.
 *
 * The maker is the one app in this project whose whole job is to speak to a
 * person, so its copy is kept in one object instead of being spread across the
 * activity, the screens and the strings that build the sentences about
 * GameCore. A later localisation pass then has one file to translate and one
 * file to check, rather than a dozen call sites to hunt down.
 *
 * The strings here are the maker's own; the studio library has its own copy
 * for the editor itself, and the plugin package format has its own.
 */
object MakerStrings {

    /** The app's name, as the launcher shows it. */
    const val TITLE = "Plugin Maker"

    /** The one line under the title. */
    const val SUBTITLE = "Make a GameCore plugin, then save it to your phone."

    /** The button that jumps to GameCore. */
    const val OPEN_GAMECORE = "Open GameCore"

    /** The name of the app the maker writes plugins for. */
    const val GAME_CORE = "GameCore"

    /**
     * Said plainly when GameCore is not on this phone.
     *
     * It says what is true rather than what would be convenient: the plugin
     * the user is making cannot run here yet, and the maker will not pretend
     * otherwise.
     */
    const val GAME_CORE_NOT_INSTALLED =
        "GameCore is not installed on this phone. Install it to run the plugin you make."

    /**
     * Said after a package has been written.
     *
     * The folder is the one the user picked in the system's file picker, so
     * the maker can only point at it rather than name it — and the second
     * half of the sentence is the part that matters, because a saved file is
     * not an installed plugin.
     */
    const val SAVE_CONFIRMATION =
        "Saved %s to the folder you chose. Install it from GameCore → Plugins → Choose files."

    /** [SAVE_CONFIRMATION] for the file that was just written. */
    fun saveConfirmation(fileName: String): String = SAVE_CONFIRMATION.format(fileName)
}
