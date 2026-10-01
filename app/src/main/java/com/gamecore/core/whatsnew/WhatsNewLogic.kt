package com.gamecore.core.whatsnew

/**
 * The "What's New" decisions as pure logic over the values the caller hands in — no `android.*`, no
 * `BuildConfig`, no preference store — in the same shape as [com.gamecore.domain.setup.decideEntry], so
 * the whole thing is decided in a JVM test without an Activity.
 *
 * Everything compares [ReleaseNote.versionCode] and only that. `versionName` is a marketing string that
 * can go sideways or backwards between builds; the `versionCode` is the monotonic integer the platform
 * itself orders updates by, which is why the setup wizard compares codes too. The stored
 * `lastSeenWhatsNewVersionCode` and `BuildConfig.VERSION_CODE` are both that same kind of integer.
 */

/**
 * The releases strictly newer than what the user has already seen, newest first.
 *
 * `> lastSeenVersionCode`, not `>=`: a user who last saw code N has seen release N, so the boundary
 * release itself is excluded and only genuinely-newer releases remain. Sorted by `versionCode`
 * descending so the result is newest-first regardless of how [all] happens to be ordered — the card and
 * the screen can then trust the order without depending on the registry being kept sorted by hand.
 */
fun entriesSince(all: List<ReleaseNote>, lastSeenVersionCode: Int): List<ReleaseNote> =
    all.filter { it.versionCode > lastSeenVersionCode }
        .sortedByDescending { it.versionCode }

/**
 * Whether the one-time card should appear this launch.
 *
 * Three gates, in order:
 *  - [isFirstInstall] → false. A brand-new install has nothing to catch up on, and its stored
 *    last-seen version is still 0, so a naive "unseen releases" check would wrongly fire on the very
 *    first launch. The caller instead seeds last-seen to the current version so no card ever appears
 *    for the version the user installed on. This mirrors the wizard's fresh-install branch: an existing
 *    user upgrading from a build that never wrote the key also has last-seen 0, but is *not* a first
 *    install, and correctly sees the whole changelog once.
 *  - `lastSeen >= current` → false. The user is already on (or past) this version; there is nothing
 *    newer than what they have seen, so the card would be noise on a normal launch.
 *  - otherwise → true only if there is at least one release newer than last-seen.
 *
 * Compares by `versionCode` throughout, against [WhatsNewRegistry] as the single source of truth.
 */
fun shouldShowCard(
    lastSeenVersionCode: Int,
    currentVersionCode: Int,
    isFirstInstall: Boolean,
): Boolean {
    if (isFirstInstall) return false
    if (lastSeenVersionCode >= currentVersionCode) return false
    return entriesSince(WhatsNewRegistry.releases, lastSeenVersionCode).isNotEmpty()
}
