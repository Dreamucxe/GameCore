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
            versionCode = 50,
            versionName = "4.1",
            date = "2026-10-31",
            features = listOf(
                FeatureNote(
                    "Fifty-one languages",
                    "Every screen, button and setup step can now speak one of fifty-one languages. " +
                        "Choose one on the first setup step or later in Settings › Appearance; " +
                        "until you do, GameCore follows the device. Right-to-left languages flip " +
                        "the layout, and a line that has no translation yet falls back to English " +
                        "rather than disappearing.",
                ),
                FeatureNote(
                    "Build a plugin without reading our source",
                    "plugin-template/ is a complete, installable theme in two JSON files, and " +
                        "tools/build-plugin.py checks it against the same rules GameCore enforces " +
                        "and writes a reproducible .gcplugin with a SHA-256 beside it. The template " +
                        "README says which fields are required and what each one changes.",
                ),
                FeatureNote(
                    "Correctness fixes",
                    "The energy estimate now averages the two middle sessions when a game has an " +
                        "even number of them, stutter reports choose the five worst frame times " +
                        "rather than the five latest, and a five-pixel display-size difference is " +
                        "no longer reported as a different panel.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 49,
            versionName = "4.0",
            date = "2026-10-30",
            features = listOf(
                FeatureNote(
                    "Whole-app font themes",
                    "Nine Liquid Glass font packages now change the typeface everywhere, not only " +
                        "inside the plugin section. Each package is a complete theme that keeps the " +
                        "glass material and Control Center slider while applying one bundled face " +
                        "across the app; switching packages switches the whole-app font.",
                ),
                FeatureNote(
                    "Eight more bundled faces",
                    "Orbitron, Press Start 2P, VT323, Comic Neue, Cinzel, Inter, JetBrains Mono and " +
                        "Rubik join the Minecraft-inspired pixel face. All eight are open-source " +
                        "families bundled with their SIL Open Font License texts, so the font " +
                        "packages stay data-only and ship no font files of their own.",
                ),
                FeatureNote(
                    "Pick several plugin files at once",
                    "The plugin manager's file picker now takes more than one .gcplugin at a time. " +
                        "Every file you pick is read and shown to you first — which ones can be " +
                        "installed, and why the others cannot — and one confirmation installs them " +
                        "all. Installing still grants nothing: every permission starts off.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 47,
            versionName = "3.9.0",
            date = "2026-10-30",
            features = listOf(
                FeatureNote(
                    "Whole-app font themes",
                    "Nine Liquid Glass font packages now change the typeface everywhere, not only " +
                        "inside the plugin section. Each package is a complete theme that keeps the " +
                        "glass material and Control Center slider while applying one bundled face " +
                        "across the app; switching packages switches the whole-app font.",
                ),
                FeatureNote(
                    "Eight more bundled faces",
                    "Orbitron, Press Start 2P, VT323, Comic Neue, Cinzel, Inter, JetBrains Mono and " +
                        "Rubik join the Minecraft-inspired pixel face. All eight are open-source " +
                        "families bundled with their SIL Open Font License texts, so the font " +
                        "packages stay data-only and ship no font files of their own.",
                ),
                FeatureNote(
                    "Pick several plugin files at once",
                    "The plugin manager's file picker now takes more than one .gcplugin at a time. " +
                        "Every file you pick is read and shown to you first — which ones can be " +
                        "installed, and why the others cannot — and one confirmation installs them " +
                        "all. Installing still grants nothing: every permission starts off.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 44,
            versionName = "3.6.0",
            date = "2026-10-27",
            features = listOf(
                FeatureNote(
                    "A theme and dashboard schedule",
                    "Daily time windows can switch the plugin theme and the Home dashboard, and a " +
                        "per-game override wins while that game is running. A schedule is an override " +
                        "layer: when no window or game applies, the manual theme and Home-stats " +
                        "choice return unchanged.",
                ),
                FeatureNote(
                    "Active plugins at the top of Home",
                    "A compact strip names the plugins that are actually running, using the same " +
                        "enabled, suspended, quarantine and compatibility rules as the Plugins " +
                        "screen. It is absent when nothing is running and says so when the global " +
                        "plugin switch is on.",
                ),
                FeatureNote(
                    "Ten more data-only plugin packs",
                    "Night Shift, Colorblind Safe, Papercut, Minimal Vitals, Thermal Desk, Battery " +
                        "Badge, Cooldown Ladder, Exit Restore, Charger Boost and Stat Dictionary join " +
                        "the official set. They are JSON and image data read through the same codecs " +
                        "as every other package; none of them adds code, a shell, a network call or a " +
                        "permission the host does not already gate.",
                ),
                FeatureNote(
                    "Plugin search and appearance",
                    "Search installed plugins by name, id, developer, description or version. The " +
                        "plugin section also has its own font and text-size controls, including a " +
                        "bundled Minecraft-inspired pixel face, without changing the rest of the app.",
                ),
                FeatureNote(
                    "The plugin section follows your theme",
                    "The plugin manager, detail screen and automation history now use the theme the " +
                        "rest of GameCore is wearing. The safety switch, consent switches and removal " +
                        "dialog keep GameCore's own colours, so a plugin cannot restyle the controls " +
                        "that turn it off.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 43,
            versionName = "3.5.0",
            date = "2026-10-25",
            features = listOf(
                FeatureNote(
                    "Emulators, marked experimental",
                    "GameCore can recognise a set of emulators already installed on the device by " +
                        "package name, open one, and try to hand it a ROM. It does not include, " +
                        "download or install an emulator, and opening an emulator is not counted as " +
                        "a game launch.",
                ),
                FeatureNote(
                    "A ROM library that leaves the file in place",
                    "Pick a disc image or ROM with Android's document picker and confirm which " +
                        "system it belongs to. GameCore keeps a persisted document link and the " +
                        "metadata the provider exposes; it does not copy the file, and forgetting a " +
                        "row does not delete anything.",
                ),
                FeatureNote(
                    "Windows programs are open-only",
                    "A .exe can be filed in the library, but Winlator runs Windows programs inside " +
                        "its own container and exposes no supported way to receive a specific file. " +
                        "GameCore opens the container and says so instead of claiming the program ran.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 41,
            versionName = "3.19.0",
            date = "2026-10-24",
            features = listOf(
                FeatureNote(
                    "Display changes ask before they stay",
                    "Every display-size change GameCore makes now shows a fifteen-second countdown " +
                        "with Keep and Revert. Silence puts the previous size back, and a size left " +
                        "unconfirmed across a restart is recovered at launch instead of waiting for " +
                        "you to find the screen that set it.",
                ),
                FeatureNote(
                    "Recovery console",
                    "A display-recovery screen in Settings puts the panel back to its own size, " +
                        "confirms whether the elevated shell is reachable, and can be opened from the " +
                        "recovery notification when the app itself is hard to read.",
                ),
                FeatureNote(
                    "Frame-time attribution",
                    "Session reports now say what was happening around each frame-time spike — " +
                        "thermal throttling, a capped refresh rate, battery saver, load, temperature " +
                        "or a profile that did not fully apply — and name a spike nothing explains " +
                        "as unexplained.",
                ),
                FeatureNote(
                    "Session timeline and receipt",
                    "Every session now keeps a timeline of profile applies, thermal changes, refresh " +
                        "changes, power-saver transitions and frame-time spikes, and can render the " +
                        "report as a shareable PNG receipt.",
                ),
                FeatureNote(
                    "New-device migration",
                    "Backups carry an optional device snapshot. Restoring one shows where the file " +
                        "came from and names the refresh rates, display sizes and Android versions " +
                        "that may not mean the same thing on this device. Files written before this " +
                        "release still restore and read as unknown origin.",
                ),
                FeatureNote(
                    "Energy budget per game",
                    "The profile editor estimates a game's battery cost from its finished sessions — " +
                        "median percent per hour, how many sessions it rests on, and the playtime " +
                        "that leaves at the current level when the device is not charging.",
                ),
                FeatureNote(
                    "Network versus device attribution",
                    "Session reports compare frame-time spikes against two measured lanes — this " +
                        "device to the router, and the path beyond it — and say which one answered " +
                        "slowly at that moment, or that the network record does not support an " +
                        "explanation. No game server is contacted or claimed.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 40,
            versionName = "3.18.0",
            date = "2026-10-23",
            features = listOf(
                FeatureNote(
                    "Search the whole app",
                    "A search row under the Home header finds screens, features and settings by " +
                        "name or by what they do, and opens the result directly.",
                ),
                FeatureNote(
                    "Device Fitness Check",
                    "One checkup of the sensors, special permissions, display rate, thermal and " +
                        "power state, and the GPU. Each gap says what it costs, and every gap the " +
                        "device can close opens the Android settings page that closes it.",
                ),
                FeatureNote(
                    "Sensor calibration",
                    "A ten-second still capture of the gyroscope and accelerometer, the live trace " +
                        "while it runs, and then the bias, noise and drift those readings actually " +
                        "show.",
                ),
                FeatureNote(
                    "Frame-time percentiles",
                    "Session reports now show p95 and p99 frame times beside the average, recorded " +
                        "with the thermal status, power-saver state and refresh-capped state they " +
                        "were measured under.",
                ),
                FeatureNote(
                    "Thermal guard",
                    "GameCore warns once when the system starts throttling, using the platform's own " +
                        "explanation, and clears the warning when the device recovers. Hysteresis " +
                        "keeps a reading that bounces at the threshold from repeating the alert.",
                ),
                FeatureNote(
                    "Profile-apply diagnostics",
                    "The Games screen reports how the last profile application ended, so a profile " +
                        "that did not take effect is a named outcome instead of a silent miss.",
                ),
                FeatureNote(
                    "Plugin preview, rollback and quarantine",
                    "See what a plugin changes before installing it, roll back to the previous " +
                        "version in one tap, and quarantine a plugin that misbehaves — it is switched " +
                        "off with the reason kept, not uninstalled.",
                ),
                FeatureNote(
                    "A training plan from your own history",
                    "Aim Lab Home recommends today's run from the sessions and records you have " +
                        "actually made, and says in a sentence which figure earned the recommendation.",
                ),
                FeatureNote(
                    "Backups carry history, and history has a retention window",
                    "A backup now includes finished game sessions, Aim Lab runs and personal " +
                        "records. Restore merges them without deleting anything, and the retention " +
                        "setting removes sessions older than the window you choose.",
                ),
                FeatureNote(
                    "Accessibility options",
                    "Choose a target palette for Aim Lab, mirror its on-screen controls for a " +
                        "left-handed grip, and raise the minimum height of navigation rows, settings " +
                        "switches and overlay panel chips.",
                ),
                FeatureNote(
                    "Game detection exclusions",
                    "Choose which apps are never treated as a game, and pin the profile detection " +
                        "should use. Every enabled profile is still watched for the apps you leave " +
                        "alone.",
                ),
                FeatureNote(
                    "Custom aspect ratios",
                    "Save your own display shapes beside the four presets. A saved ratio appears as " +
                        "its own chip in the GamePill's Aspect tile and as a row in the profile editor.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 39,
            versionName = "3.17.0",
            date = "2026-10-22",
            features = listOf(
                FeatureNote(
                    "Tilt training for phones without a gyroscope",
                    "A phone with no gyroscope can now run the gyro challenge as Tilt training, " +
                        "read from the accelerometer. Bank the phone left or right in the plane of " +
                        "the screen to steer, and tip the top edge away from you to aim up. A phone " +
                        "that has a gyroscope is unchanged — it is still the input.",
                ),
                FeatureNote(
                    "Tilt runs stay apart from gyro records",
                    "An accelerometer cannot sense turning on the spot — gravity does not change " +
                        "when the phone rotates about itself — so a tilt run is filed under its own " +
                        "mode and never ranked against a gyro record. A device with neither sensor " +
                        "gets a plain explanation instead of a Start button.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 38,
            versionName = "3.16.0",
            date = "2026-10-22",
            features = listOf(
                FeatureNote(
                    "Themes can be made of glass",
                    "A plugin theme can now ask for a material instead of a flat colour: a " +
                        "translucent, glossy surface with a specular rim, a highlight that falls " +
                        "across the face and drifts slowly down it, and an iOS-style spring that " +
                        "settles a panel when it changes. On Android 12 and newer the backdrop " +
                        "behind a panel is blurred too.",
                ),
                FeatureNote(
                    "Glass Control Center",
                    "One slider for how much material the app is wearing. It scales the " +
                        "transparency, the gloss and the blur together, over a live panel drawn from " +
                        "the exact material you are about to save — so you can see it before you let " +
                        "go. Settings shows the row only while a theme with a material is active, and " +
                        "your Animations setting still wins: with animation off the material is drawn " +
                        "at rest, without the drift or the settle.",
                ),
                FeatureNote(
                    "Liquid Glass GameCore, rewritten",
                    "The iOS-inspired theme now asks for the material it was named after, with an " +
                        "Intensity control in the Control Center. It needs this version of GameCore, " +
                        "and Android 11 and below get the translucency, rim and gloss against a sharp " +
                        "backdrop — backdrop blur is an Android 12 feature, and the Control Center " +
                        "says so rather than calling it frosted.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 37,
            versionName = "3.15.0",
            date = "2026-10-21",
            features = listOf(
                FeatureNote(
                    "GameCore has five icons now",
                    "Settings → App icon shows the set: the Core mark, a one-colour Mono for " +
                        "themed-icon launchers, Glitch torn cyan-and-red, Neon's magenta reticle and " +
                        "Redline cut to a hard corner. Tapping one changes the icon your launcher " +
                        "shows — the real one, not a shortcut to it.",
                ),
                FeatureNote(
                    "Plugins can pick the icon",
                    "A plugin can ask for one of the five, and Settings shows which plugin is " +
                        "currently doing it with a one-tap Undo back to your own choice.",
                ),
                FeatureNote(
                    "Plugin artwork as a pinned icon",
                    "A plugin that brings an image of its own can offer it as a home-screen " +
                        "shortcut. Android will not let any app install a bitmap as its launcher icon, " +
                        "so this is the honest version of that: your launcher asks you to confirm, " +
                        "and GameCore's own icon is untouched.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 36,
            versionName = "3.14.0",
            date = "2026-10-21",
            features = listOf(
                FeatureNote(
                    "Themes can move now",
                    "A plugin theme can carry a glitch block: the screen tears into horizontal bands " +
                        "that slide sideways, fractures into one-pixel lines, throws block noise and " +
                        "drops a frame when the phone gets hot. The whole app is affected, text and " +
                        "panels alike — it is a live re-draw, not a colour filter.",
                ),
                FeatureNote(
                    "It reads the phone",
                    "With \"hardware reactive\" on, a busy device tears about twice as often and a " +
                        "throttling one adds a vertical frame drop. Turn it off and the effect runs on " +
                        "a fixed rhythm and costs no sensor reads at all.",
                ),
                FeatureNote(
                    "Animation settings still win",
                    "Settings → Animations → Off, or the system's remove-animations switch, suppresses " +
                        "every theme effect. A plugin asks for motion; it can never take it.",
                ),
                FeatureNote(
                    "Off by default",
                    "Nothing changes unless you install and select a theme that asks for it. " +
                        "Existing themes render exactly as they did.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 35,
            versionName = "3.13.0",
            date = "2026-10-20",
            features = listOf(
                FeatureNote(
                    "One tap, the full panel",
                    "Tapping the floating button now opens the control panel straight away — every tab, " +
                        "every control — instead of the small quick sheet. The sheet's toggles, sliders " +
                        "and session clock have not gone anywhere: a double tap on the button opens it.",
                ),
                FeatureNote(
                    "Or keep the sheet first",
                    "Settings → Overlay → Quick sheet has a switch for it. Off, a tap opens the small " +
                        "sheet first and the panel stays on its \"More\" button, exactly as before.",
                ),
            ),
        ),
        ReleaseNote(
            versionCode = 34,
            versionName = "3.12.0",
            date = "2026-10-20",
            features = listOf(
                FeatureNote(
                    "App-wide colour",
                    "A switch under the vector hands the app's accent to the engine. On, every screen " +
                        "is tinted by what the device is doing right now, and a theme plugin keeps " +
                        "everything else it does but stops choosing the palette. Off, a plugin's " +
                        "colours win exactly as they always have.",
                ),
                FeatureNote(
                    "The engine runs the whole time",
                    "The Theme Engine now starts with the app instead of with its settings screen, so " +
                        "its colour is alive on every screen and the first recolour is a transition " +
                        "rather than a jump. Calibrating still drives it live; leaving the screen no " +
                        "longer stops it.",
                ),
            ),
        ),
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
