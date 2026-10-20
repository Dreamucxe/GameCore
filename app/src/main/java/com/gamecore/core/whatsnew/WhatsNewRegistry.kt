package com.gamecore.core.whatsnew

/** One shipped feature line inside a release: a short title and a plain sentence of what it does. */
data class FeatureNote(val title: String, val description: String)

/**
 * One release and everything worth telling the user it introduced.
 *
 * [versionCode] is the monotonic key every decision compares — it is never shown. [versionName] and
 * [date] are display-only: a marketing string that can move sideways, and a plain `YYYY-MM-DD` the
 * screen prints beside it.
 */
data class ReleaseNote(
    val versionCode: Int,
    val versionName: String,
    val date: String,
    val features: List<FeatureNote>,
)

/**
 * The single source of truth for the in-app changelog, newest release first.
 *
 * Both surfaces read this object and nothing else: the one-time card after an update summarises the
 * releases the user has not seen, and the Settings screen lists every release here. It is plain
 * compiled-in Kotlin — no Room, no `strings.xml`, no `android.*` — so [WhatsNewLogic] can decide
 * against it in a JVM test.
 *
 * Standing rule: every future release that adds a user-facing feature appends one [ReleaseNote] here,
 * newest first, at the same time its `versionCode` / `versionName` are bumped in
 * `app/build.gradle.kts`. That single append is what makes the card fire for anyone upgrading into the
 * release — no other wiring changes. Keep the copy factual and short; describe only features that
 * actually shipped.
 */
object WhatsNewRegistry {

    val releases: List<ReleaseNote> = listOf(
        ReleaseNote(
            versionCode = 33,
            versionName = "3.11.0",
            date = "2026-10-20",
            features = listOf(
                FeatureNote(
                    "Theme Engine",
                    "Settings → Theme Engine paints a live backdrop from this device's CPU, memory " +
                        "and temperature. Low load is a soft ice-blue drifting at half speed; a busy " +
                        "or warm device turns Aurora Violet; a hot or saturated one goes molten " +
                        "crimson and travels fastest of all.",
                ),
                FeatureNote(
                    "Calibrate it yourself",
                    "The calibration screen shows the vector in force — its mode, its exact " +
                        "rgb(r, g, b) value and its fluid velocity — beside the three readings it is " +
                        "drawn from. Flat sliders let you stand in for those readings and watch the " +
                        "backdrop answer, and the thresholds that decide each mode are adjustable.",
                ),
                FeatureNote(
                    "Save points",
                    "A calibration you like can be saved under a name and recalled later; save points " +
                        "are stored on this device and survive restarts.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 32,
            versionName = "3.10.0",
            date = "2026-10-20",
            features = listOf(
                FeatureNote(
                    "Sort your plugins",
                    "The plugin manager now has an Order control: by name, by date installed, or by " +
                        "version. By date puts the most recently installed or updated package first; by " +
                        "version puts the highest version number first. The order is only how the list " +
                        "is drawn — it changes nothing about which plugins run.",
                ),
                FeatureNote(
                    "Where the date comes from",
                    "A sideloaded plugin's date is when its file was written on this device — its install " +
                        "or update. A plugin bundled with GameCore has no file of its own, so it dates " +
                        "from when the app itself was installed.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 31,
            versionName = "3.9.1",
            date = "2026-10-20",
            features = listOf(
                FeatureNote(
                    "Graphics screen",
                    "Settings → Graphics now holds two standing device controls: a render resolution preset " +
                        "that runs the display below its own pixel count, and a frame-rate cap that sets the " +
                        "highest refresh the display may use. Both read the device back after every change " +
                        "and say so when the elevated shell is missing.",
                ),
                FeatureNote(
                    "What it cannot do",
                    "A game's textures, filtering and shaders live inside its own renderer, and GameCore does " +
                        "not claim to reach them. The screen changes what the display draws and says plainly " +
                        "where that stops.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 30,
            versionName = "3.9.0",
            date = "2026-10-15",
            features = listOf(
                FeatureNote(
                    "Background image",
                    "Pick a photo of your own to draw behind the app, under Settings → Appearance. GameCore " +
                        "keeps a resized copy in its own storage and dims it so text stays readable — you choose " +
                        "how much of the image shows through.",
                ),
                FeatureNote(
                    "Graphics Tuning plugin",
                    "A guide to the in-game graphics settings that actually move frame rate, with an in-game " +
                        "readout of frame rate, GPU load and temperature so you can check a change on your own " +
                        "device. It explains and measures — it cannot change a game's graphics for you.",
                ),
                FeatureNote(
                    "Redline skin",
                    "A red-on-black RedMagic-style look under Settings → Appearance: cut-corner cards, the " +
                        "pinwheel mark and a restyled home banner. Always dark, and off until you turn it on. " +
                        "A matching RedMagic theme plugin carries the colours for a plain install.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 29,
            versionName = "3.8.1",
            date = "2026-10-11",
            features = listOf(
                FeatureNote(
                    "Eight official plugins",
                    "A first set of GameCore-made plugins you can install from a file: a Nothing-OS-style " +
                        "theme, an anime-inspired pack, a sensitivity workspace, an automation-history view, " +
                        "a personal dashboard, a local advisor, a benchmark readout and a dashboard composer. " +
                        "Each is data GameCore reads — none runs code.",
                ),
                FeatureNote(
                    "Plugin settings you can change",
                    "A plugin can offer its own options — switches, choices and numbers — and GameCore stores " +
                        "and applies them for you. The controls live in GameCore; the plugin still runs nothing.",
                ),
                FeatureNote(
                    "Your automation history, shown by a plugin",
                    "A plugin you allow can display the record of automations GameCore has already run for you. " +
                        "It reads that history only to show it — it is handed none of your other data and runs " +
                        "no code of its own.",
                ),
                FeatureNote(
                    "Live readouts on plugin screens",
                    "Plugin screens and dashboard widgets can show figures GameCore already measures — frame " +
                        "rate, temperature and the like — each drawn by GameCore from data you have allowed.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 28,
            versionName = "3.8",
            date = "2026-10-08",
            features = listOf(
                FeatureNote(
                    "Plugins",
                    "Install a plugin from a file to restyle GameCore, see exactly what it asks for, and " +
                        "turn it on. A plugin is data GameCore reads, not code — it never runs inside " +
                        "GameCore and never gets a shell.",
                ),
                FeatureNote(
                    "Permission by permission",
                    "Every plugin lists exactly what it wants and you grant each item separately. A new " +
                        "plugin arrives switched off with nothing granted, and device settings need a " +
                        "second opt-in.",
                ),
                FeatureNote(
                    "Plugin themes",
                    "A plugin can supply colours, corner rounding and scale. Unreadable colour pairs are " +
                        "replaced automatically, and the Plugins screen always draws in GameCore's own " +
                        "theme — so the way back is never styled by the plugin you are turning off.",
                ),
                FeatureNote(
                    "On-screen plugin modules",
                    "A plugin can add a small readout that sits over your game beside the Game Pill — " +
                        "frame rate, temperature and the like. It cannot cover the screen and cannot take " +
                        "touches away from the game, and it only appears once you grant that plugin the " +
                        "overlay permission. Up to three at a time.",
                ),
                FeatureNote(
                    "One switch to stop them all",
                    "A single toggle on the Plugins screen stops every plugin at once, whatever each " +
                        "plugin's own switch says.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 27,
            versionName = "3.7.2",
            date = "2026-10-01",
            features = listOf(
                FeatureNote(
                    "Dock customisation",
                    "Rearrange the floating dock's buttons and choose which appear, saved per game.",
                ),
                FeatureNote(
                    "Quick actions on the dock",
                    "Put shortcuts to GameCore's existing controls on the dock for one-tap access in game.",
                ),
                FeatureNote(
                    "Revert settings mid-session",
                    "A card on Home lists what the active profile changed and puts any of it back without " +
                        "ending play.",
                ),
                FeatureNote(
                    "In-game point-trigger placement",
                    "Place a volume-key tap target by touching the spot on screen, without opening the app.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 25,
            versionName = "3.7.0",
            date = "2026-09-30",
            features = listOf(
                FeatureNote(
                    "Floating Dock",
                    "A small draggable dock over your game with a quick control panel; it snaps to the " +
                        "screen edge and can be turned on from Settings.",
                ),
                FeatureNote(
                    "Screen Extraction",
                    "Capture the current screen, crop a region, then save it or share it.",
                ),
                FeatureNote(
                    "Touch Sampling Monitor",
                    "See your device's real touch input rate live; it shows Unavailable when the " +
                        "hardware can't report it.",
                ),
                FeatureNote(
                    "Volume Button Point Trigger",
                    "Map a volume key to tap a saved screen point per game — single, double or hold; " +
                        "needs accessibility and Shizuku.",
                ),
                FeatureNote(
                    "What's New",
                    "This screen: see new features once on update, and browse all past updates any time.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 24,
            versionName = "3.6.1",
            date = "2026-09-29",
            features = listOf(
                FeatureNote(
                    "Network Stability Mode",
                    "A live read of the connection you play on — transport, signal, link speed and a " +
                        "stability window of ping and jitter, with anything unmeasurable shown as Unavailable.",
                ),
                FeatureNote(
                    "Advanced Performance HUD",
                    "A customisable heads-up display over FPS, CPU, GPU, RAM, temperature, battery and " +
                        "ping, as a compact line or an expanded card.",
                ),
                FeatureNote(
                    "Custom Overlay Modules",
                    "Turn individual overlay modules on and off, drag them to position and resize them, " +
                        "saved as an overlay layout per game.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 23,
            versionName = "3.6",
            date = "2026-09-28",
            features = listOf(
                FeatureNote(
                    "Instant Replay",
                    "A rolling video buffer that always holds the last 15–120 seconds of play, so you can " +
                        "save a moment after it has already happened; video only, on-device, no new permission.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 22,
            versionName = "3.5.4",
            date = "2026-09-26",
            features = listOf(
                FeatureNote(
                    "Config editor",
                    "Browse a game's own configuration files and edit one in place, shown as a diff and " +
                        "written atomically after you confirm, with automatic backups; needs Shizuku.",
                ),
                FeatureNote(
                    "Resolution override",
                    "A per-game render resolution set as a scale of the display's native size and cleared " +
                        "when the game leaves; needs Shizuku.",
                ),
                FeatureNote(
                    "Config backup, transfer and suggestions",
                    "Export and restore all configuration, share game profiles as a file, and draft a " +
                        "starting profile from what a game was actually measured doing.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 18,
            versionName = "3.5",
            date = "2026-09-23",
            features = listOf(
                FeatureNote(
                    "First-run setup wizard and Setup health",
                    "A guided setup that turns on only the permissions your chosen features need, plus a " +
                        "Settings screen listing each item's real state with a fix shortcut.",
                ),
                FeatureNote(
                    "Thermal auto-downshift",
                    "Per game, step the refresh rate down when the device runs hot and back up once it has " +
                        "stayed cool long enough.",
                ),
                FeatureNote(
                    "Network check",
                    "Per game, warn about a poor connection before and during play, reading transport, band " +
                        "and link speed without any location permission.",
                ),
                FeatureNote(
                    "Keep full performance under battery saver",
                    "Per game, hold full performance even under battery saver and put the saver's own " +
                        "behaviour back when the game exits.",
                ),
                FeatureNote(
                    "Redesigned overlay and themes",
                    "A floating button with real states and a quick sheet, a tabbed full panel, and Dark, " +
                        "Light or AMOLED themes with accent colours.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 6,
            versionName = "2.0",
            date = "2026-09-08",
            features = listOf(
                FeatureNote(
                    "Split control panel",
                    "The overlay panel can split into two plates pinned to opposite screen edges, keeping " +
                        "the game visible between them.",
                ),
                FeatureNote(
                    "Refresh rate from the panel",
                    "Switch between the rates the display actually advertises without leaving the game; a " +
                        "rate is reported only after it is read back and confirmed.",
                ),
                FeatureNote(
                    "Crosshair quick-select",
                    "Hold the crosshair tile to change its design and colour without opening a screen.",
                ),
                FeatureNote(
                    "CPU core affinity (experimental)",
                    "Choose which cores a game's process may run on; it may reduce stutter but never makes " +
                        "the device faster. Needs Shizuku.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 1,
            versionName = "1.0",
            date = "2026-09-01",
            features = listOf(
                FeatureNote(
                    "First release",
                    "GameCore's gaming overlay, per-game profiles applied on launch and restored on exit, " +
                        "honest performance monitoring, and encrypted session history.",
                ),
            ),
        ),
    )
}
