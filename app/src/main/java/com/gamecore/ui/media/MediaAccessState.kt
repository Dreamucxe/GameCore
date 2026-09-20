package com.gamecore.ui.media

import com.gamecore.domain.media.NowPlaying

/**
 * The media access screen: one switch, and a live account of what it lets GameCore see.
 *
 * [nowPlaying] is null until the first read comes back, and that is a third state rather than a tidier
 * spelling of [NowPlaying.Silent]. "Nothing is playing" is a claim about the device, and a screen whose
 * whole argument is that it only reads what it says it reads cannot afford to make that claim before it
 * has asked. Until then the card says it is checking.
 *
 * There is no stored copy of the title or artist anywhere in this state's lifetime beyond the current
 * value — the screen is showing the reader's latest emission and nothing accumulates. Which is the point
 * it is making.
 */
data class MediaAccessUiState(
    val isLoaded: Boolean = false,
    val hasAccess: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    val message: String? = null,
) {
    /**
     * The header's subtitle: what the switch currently means for the panel, not what it is called.
     *
     * Phrased in terms of the media strip both ways round, because the user arrived here from that strip
     * and "granted"/"not granted" would answer a question they did not ask.
     */
    val summary: String
        get() = when {
            !isLoaded -> "Checking what this device has granted"
            hasAccess -> "The panel can read what is playing"
            else -> "The panel's media strip needs one switch"
        }
}
