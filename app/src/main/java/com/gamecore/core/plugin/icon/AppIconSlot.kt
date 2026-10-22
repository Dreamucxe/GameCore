package com.gamecore.core.plugin.icon

/**
 * The app icons GameCore ships, one per launcher entry.
 *
 * **Why a fixed set.** An Android launcher icon is a manifest attribute resolved by the system
 * against the installed package, so an app cannot draw one at runtime: there is no API that takes a
 * bitmap and puts it in the launcher. The only way to change it is to declare several entries —
 * `activity-alias` components, each with its own icon — and enable exactly one. That set is fixed
 * when the APK is built, and this enum is its mirror in code: every case here has a matching alias
 * in `AndroidManifest.xml`, a matching adaptive icon in `res/mipmap-anydpi-v26/`, and a
 * `AppIconSlotManifestTest` that fails if the three ever disagree.
 *
 * **What a plugin can therefore do.** A plugin may *choose* a slot: it names one in its `icon.json`,
 * and the host switches to it. It may also ship artwork, but that artwork cannot become the launcher
 * icon — the launcher never reads a file from another app. [PluginIconSpec.artworkPath] exists for
 * the other surface that does take runtime art: a pinned home-screen shortcut, which the system
 * builds from the bitmap the host hands it. Saying that plainly is the whole design: [AppIconSlot]
 * is the honest half of "change my icon", and the shortcut is the part that takes arbitrary art.
 *
 * [componentSuffix] is the alias name after the application id, so the fully-qualified component is
 * `BuildConfig.APPLICATION_ID + componentSuffix`. It is kept as a suffix rather than a full name so
 * that nothing in the pure-Kotlin core has to hard-code a package name, and a debug build with an
 * application-id suffix still resolves.
 *
 * Order is display order: [DEFAULT] first, because it is the one a fresh install uses and the one
 * "reset" returns to.
 */
enum class AppIconSlot(
    /** The stable id that travels in `icon.json` and in storage. Never rename one. */
    val id: String,
    /** What the picker calls it. */
    val label: String,
    /** One line under the name in the picker. */
    val description: String,
    /**
     * The `activity-alias` name after the application id.
     *
     * Every value here is a component declared in the manifest; [AppIconSlotManifestTest] reads the
     * manifest and proves it.
     */
    val componentSuffix: String,
) {
    DEFAULT(
        id = "default",
        label = "Core",
        description = "The GameCore mark — a hexagon inside a reticle.",
        componentSuffix = ".icon.IconDefault",
    ),

    MONO(
        id = "mono",
        label = "Mono",
        description = "The same mark, one colour, for themed-icon launchers.",
        componentSuffix = ".icon.IconMono",
    ),

    GLITCH(
        id = "glitch",
        label = "Glitch",
        description = "Cyan and red copies of the mark, torn a few pixels apart.",
        componentSuffix = ".icon.IconGlitch",
    ),

    NEON(
        id = "neon",
        label = "Neon",
        description = "Magenta over cyan — the arcade cabinet look.",
        componentSuffix = ".icon.IconNeon",
    ),

    REDLINE(
        id = "redline",
        label = "Redline",
        description = "The house red, cut to a hard corner.",
        componentSuffix = ".icon.IconRedline",
    );

    companion object {
        private val byId = entries.associateBy { it.id }

        /** The slot with this id, or null. An unknown id is a rejection, never a fallback. */
        fun fromId(id: String?): AppIconSlot? = id?.trim()?.lowercase()?.let(byId::get)

        /**
         * The slot used when nothing has been chosen.
         *
         * Not "whatever is enabled in the manifest": the enabled alias and the stored choice can
         * disagree for a moment while a switch is in flight, and a default that reads the system
         * would make [DEFAULT] unnameable.
         */
        val FALLBACK: AppIconSlot = DEFAULT
    }
}
