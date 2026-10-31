package com.gamecore.core.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import java.util.Locale

/**
 * The words of the current screen, and the direction they are read in.
 *
 * Provided at the root and read by every screen that shows chrome. A screen that has not been
 * translated yet simply keeps its English literals; nothing breaks and nothing shows a key, because a
 * missing catalogue line falls back to English rather than to the key.
 */
val LocalStrings = compositionLocalOf { Strings(emptyMap(), emptyMap()) }

/**
 * Resolves the language and provides it to everything below.
 *
 * The configured tag is what the user picked; an empty tag follows the device. [LocalConfiguration] is
 * read so a device-language change while GameCore is running recomposes the provider rather than
 * leaving the old words until the next launch. Layout direction is provided from the resolved
 * language, not from the configuration: picking Arabic inside an English device has to turn the app
 * around immediately, which is the whole point of an in-app language choice.
 */
@Composable
fun ProvideStrings(configuredTag: String, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val systemTag = remember(configuration) {
        configuration.locales.takeIf { it.size() > 0 }?.get(0)?.toLanguageTag()
            ?: Locale.getDefault().toLanguageTag()
    }
    val language = remember(configuredTag, systemTag) {
        AppLanguage.resolve(configuredTag, systemTag)
    }
    val strings = remember(context, language) { I18nCatalog.strings(context, language) }
    CompositionLocalProvider(
        LocalStrings provides strings,
        LocalLayoutDirection provides if (language.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
        content = content,
    )
}
