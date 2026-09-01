package com.gamecore.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.gamecore.core.model.AppSettings
import com.gamecore.core.model.ThemeChoice

/**
 * Rounded, and consistently so. §27 asks for rounded cards; this decides "rounded" once rather than as
 * a different literal on each screen.
 */
val GameCoreShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// THEME_BODY

/**
 * The app's theme: three settings turned into a `MaterialTheme`.
 *
 * [settings] is passed in rather than read from the preference store here, so this stays a pure
 * function of its input — a screen preview supplies an [AppSettings] literal and gets the real theme,
 * and there is no composable in the tree that needs Hilt to draw itself.
 *
 * Three decisions worth stating:
 *
 *  - **Dynamic colour is opt-in and off by default.** Material You is the platform's preference and it
 *    is offered, but GameCore's own accents are chosen to stay legible against the near-black surfaces
 *    the graphs are drawn on, and a wallpaper-derived pastel primary can leave a thermal line and a CPU
 *    line the same value of grey.
 *  - **UI scale scales the whole interface, not just the text.** The multiplier goes on `density`
 *    rather than on `fontScale`, so at 130% a stat card, its padding and its touch target all grow
 *    together. Scaling only the text would push type into layouts sized for smaller type, which is how
 *    a scale setting ends up clipping the number it was turned on to make readable. The user's own
 *    system font scale is left where it is and still applies on top.
 *  - **The system bar icons follow the resolved scheme.** With edge-to-edge on, the bars are
 *    transparent and their icons are drawn over this app's background; light icons on a light scheme
 *    would be invisible. `SideEffect` rather than `LaunchedEffect` because it is a window property that
 *    must match the composition that has just been produced, not a job.
 */
@Composable
fun GameCoreTheme(
    settings: AppSettings = AppSettings(),
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.theme) {
        ThemeChoice.SYSTEM -> systemDark
        ThemeChoice.DARK -> true
        ThemeChoice.LIGHT -> false
    }
    val context = LocalContext.current
    val scheme = remember(dark, settings.accent, settings.useDynamicColour) {
        val dynamicAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        when {
            settings.useDynamicColour && dynamicAvailable && dark -> dynamicDarkColorScheme(context)
            settings.useDynamicColour && dynamicAvailable -> dynamicLightColorScheme(context)
            dark -> darkSchemeFor(settings.accent)
            else -> lightSchemeFor(settings.accent)
        }
    }

    val base = LocalDensity.current
    val scaled = remember(base, settings.uiScalePercent) {
        if (settings.uiScalePercent == 100) {
            base
        } else {
            Density(
                density = base.density * settings.uiScalePercent / 100f,
                fontScale = base.fontScale,
            )
        }
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(
        colorScheme = scheme,
        typography = GameCoreTypography,
        shapes = GameCoreShapes,
    ) {
        CompositionLocalProvider(LocalDensity provides scaled, content = content)
    }
}
