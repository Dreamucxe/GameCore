package com.gamecore.plugin.sdk.templates

import com.gamecore.plugin.sdk.IconSlots
import com.gamecore.plugin.sdk.OverlayIcons
import com.gamecore.plugin.sdk.Permissions
import com.gamecore.plugin.sdk.PluginDefinition
import com.gamecore.plugin.sdk.plugin

/**
 * "Stat Dictionary" — a plain-language guide to the figures GameCore's plugin feed exposes.
 *
 * This is a screens-only plugin, and deliberately so. The dictionary does not draw live numbers;
 * it explains what each number means, where it comes from, and what it cannot tell you. That is
 * useful precisely because the same words — "temperature", "load", "refresh rate" — mean
 * different things on different phones and in different parts of the app.
 *
 * Two definitions matter more than the rest:
 *  - the plugin feed's `temp` is the **battery** temperature, not the CPU junction temperature;
 *  - the plugin feed's refresh-rate story is the **Android-reported active mode**, not a physical
 *    panel measurement. A variable-rate panel legitimately sits below its peak on a still screen.
 *
 * The package is data only: seven read-only screens, one `change_ui` grant, one icon request. It
 * reads no data scope and can change nothing.
 */
fun statDictionary(): PluginDefinition = plugin(
    id = "com.dreamucxe.gamecore.statdictionary",
    name = "Stat Dictionary",
) {
    version = 1
    versionName = "1.0.0"
    developer = "Dream"
    description =
        "A plain-language dictionary of the figures GameCore's plugin feed exposes — what each " +
            "one measures, what it does not, and why an unavailable reading is a dash."
    changelog = "Initial release."

    request(Permissions.CHANGE_UI)
    request(Permissions.CHANGE_ICON)

    screens {
        screen("overview", "Stat Dictionary", icon = OverlayIcons.DATA) {
            column(gap = 12, padding = 16) {
                text("Stat Dictionary", size = 22, bold = true)
                text(
                    "Every live figure in GameCore is a reading, not a guess. When a device does " +
                        "not expose one, the app draws a dash and keeps the reason; it does not " +
                        "invent a zero.",
                    size = 15,
                )
                card(padding = 14, gap = 8) {
                    text("How to read an entry", size = 16, bold = true)
                    text("What it measures.", size = 14)
                    text("Where it comes from.", size = 14)
                    text("What it cannot tell you.", size = 14)
                }
                grid(columns = 2, gap = 8) {
                    card(padding = 12, gap = 6) {
                        image(OverlayIcons.SPEED, size = 24)
                        text("Frame rate", size = 13, bold = true)
                    }
                    card(padding = 12, gap = 6) {
                        image(OverlayIcons.SENSORS, size = 24)
                        text("Load", size = 13, bold = true)
                    }
                    card(padding = 12, gap = 6) {
                        image(OverlayIcons.BATTERY, size = 24)
                        text("Power", size = 13, bold = true)
                    }
                    card(padding = 12, gap = 6) {
                        image(OverlayIcons.TIMELINE, size = 24)
                        text("Display", size = 13, bold = true)
                    }
                }
            }
        }

        screen("frames", "Frame Rate", icon = OverlayIcons.SPEED) {
            column(gap = 12, padding = 16) {
                text("Frame rate", size = 20, bold = true)
                card(padding = 14, gap = 8) {
                    text("What it measures", size = 15, bold = true)
                    text(
                        "The average number of frames drawn per second over GameCore's sample " +
                            "window. It is a real frame source when the device exposes one, not " +
                            "a synthetic benchmark score.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("What it cannot tell you", size = 15, bold = true)
                    text(
                        "An average hides stutter. A steady 60 and a 60 that drops to 20 every " +
                            "few seconds can share the same average. Use the session graph or " +
                            "frame-time history when the shape matters.",
                        size = 14,
                    )
                    text(
                        "It also cannot measure a game that hides its frames from the system. " +
                            "In that case the reading is unavailable, not zero.",
                        size = 14,
                    )
                }
            }
        }

        screen("load", "CPU, GPU & Memory", icon = OverlayIcons.SENSORS) {
            column(gap = 12, padding = 16) {
                text("Load and memory", size = 20, bold = true)
                card(padding = 14, gap = 8) {
                    text("CPU", size = 15, bold = true)
                    text(
                        "Overall CPU usage as a percentage of the cores the system reports. " +
                            "It is not a clock speed, a per-core temperature or a measure of " +
                            "how well a game uses the big cores.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("GPU", size = 15, bold = true)
                    text(
                        "GPU load as a percentage, when the device exposes a readable counter. " +
                            "Many phones do not. It is not GPU frequency, memory bandwidth or " +
                            "a frame-time breakdown.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("Memory", size = 15, bold = true)
                    text(
                        "System RAM currently in use, as a percentage. It is not one app's " +
                            "private memory and not a leak diagnosis.",
                        size = 14,
                    )
                }
            }
        }

        screen("power", "Battery & Temperature", icon = OverlayIcons.BATTERY) {
            column(gap = 12, padding = 16) {
                text("Battery and temperature", size = 20, bold = true)
                card(padding = 14, gap = 8) {
                    text("Battery level", size = 15, bold = true)
                    text(
                        "The charge percentage Android reports. It is not battery health, " +
                            "charge current, cycle count or a prediction of remaining time.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("Temperature", size = 15, bold = true)
                    text(
                        "The plugin feed's `temp` figure is the battery sensor in degrees " +
                            "Celsius, not the CPU junction temperature. GameCore's built-in " +
                            "Performance screen may show a CPU sensor; a plugin's `temp` is " +
                            "the battery one.",
                        size = 14,
                    )
                    text(
                        "A battery sensor is useful for heat trends and charge safety. It is " +
                            "not a die temperature and it will not tell you when the SoC is " +
                            "throttling.",
                        size = 14,
                    )
                }
            }
        }

        screen("display", "Refresh Rate", icon = OverlayIcons.TIMELINE) {
            column(gap = 12, padding = 16) {
                text("Refresh rate", size = 20, bold = true)
                card(padding = 14, gap = 8) {
                    text("What the number is", size = 15, bold = true)
                    text(
                        "The mode Android reports as active when GameCore reads the display. " +
                            "It is not a physical measurement of the panel with an external " +
                            "instrument, and it is not a promise that every frame is drawn at " +
                            "that rate.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("Why it moves", size = 15, bold = true)
                    text(
                        "A variable-rate panel can sit at 60 Hz on a still screen and rise to " +
                            "120 Hz when content moves. A current reading below the peak is " +
                            "not automatically a cap.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("Requested vs reported", size = 15, bold = true)
                    text(
                        "GameCore's refresh verification compares the rate it requested with " +
                            "the rate Android reports afterwards. That is a real check of the " +
                            "request; it is not a hardware panel measurement.",
                        size = 14,
                    )
                }
            }
        }

        screen("motion", "Motion", icon = OverlayIcons.EXTENSION) {
            column(gap = 12, padding = 16) {
                text("Rotation rate and movement", size = 20, bold = true)
                card(padding = 14, gap = 8) {
                    text("Rotation rate", size = 15, bold = true)
                    text(
                        "How fast the device is turning, read from the gyroscope. It is a " +
                            "motion signal, not a game's aim input and not a measure of " +
                            "sensor accuracy.",
                        size = 14,
                    )
                }
                card(padding = 14, gap = 8) {
                    text("Movement", size = 15, bold = true)
                    text(
                        "A movement reading from the accelerometer. It can tell you the phone " +
                            "moved; it cannot tell you how far, and it is not a step counter.",
                        size = 14,
                    )
                }
                text(
                    "Motion is its own data scope. A plugin that does not request it never " +
                        "sees these figures, even if the device has the sensors.",
                    size = 14,
                )
            }
        }

        screen("not-measured", "What GameCore Doesn't Measure", icon = OverlayIcons.BLOCK) {
            column(gap = 12, padding = 16) {
                text("Not in the plugin feed", size = 20, bold = true)
                text(
                    "A missing figure is often a platform boundary, not a bug. These are " +
                        "outside the approved plugin feed:",
                    size = 14,
                )
                grid(columns = 2, gap = 8) {
                    text("Battery health", bold = true)
                    text("Not exposed to the plugin feed.")
                    text("Charge current", bold = true)
                    text("Not exposed to the plugin feed.")
                    text("Fan speed", bold = true)
                    text("No standard Android sensor.")
                    text("CPU/GPU frequency", bold = true)
                    text("Not in the plugin feed.")
                    text("Per-game FPS", bold = true)
                    text("GameCore's feed is system-level.")
                    text("Panel measurement", bold = true)
                    text("No external instrument is used.")
                }
                text(
                    "The rule is simple: if GameCore cannot read it, the app says so. A dash " +
                        "is information, not an empty zero.",
                    size = 14,
                )
            }
        }
    }

    icon(slot = IconSlots.DEFAULT)
}
