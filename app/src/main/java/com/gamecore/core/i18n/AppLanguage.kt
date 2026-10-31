package com.gamecore.core.i18n

import java.util.Locale

/**
 * Every language GameCore can speak, as a BCP-47 tag and the name its own speakers use.
 *
 * [SYSTEM] is not a language: it means "ask the device", and it is stored as an empty tag so a settings
 * file written by an older build — where the key does not exist — reads back as the same behaviour.
 * Every other entry has a catalogue under `assets/i18n/<tag>.json`; a missing or unreadable catalogue
 * falls back to English rather than to blank text, so a translation can never make a screen unusable.
 *
 * The tags are the ones Android, Chrome and every translation tool agree on, which is what lets the
 * system-language match below work without a table of aliases: `pt-BR` is Brazilian Portuguese, `nb`
 * is Norwegian Bokmål, and `zh-CN`/`zh-TW` are Simplified and Traditional Chinese. A device set to
 * `pt-PT` or `zh-HK` still resolves to the nearest catalogue we ship rather than to English.
 */
enum class AppLanguage(
    val tag: String,
    /** The language's name in itself — what the picker shows first. */
    val nativeName: String,
    /** The English name, shown underneath and searched, so a speaker of another language can find it. */
    val englishName: String,
    val rtl: Boolean = false,
) {
    SYSTEM("", "System default", "System default"),

    ENGLISH("en", "English", "English"),
    SPANISH("es", "Español", "Spanish"),
    FRENCH("fr", "Français", "French"),
    GERMAN("de", "Deutsch", "German"),
    ITALIAN("it", "Italiano", "Italian"),
    PORTUGUESE_BRAZIL("pt-BR", "Português (Brasil)", "Portuguese (Brazil)"),
    RUSSIAN("ru", "Русский", "Russian"),
    UKRAINIAN("uk", "Українська", "Ukrainian"),
    POLISH("pl", "Polski", "Polish"),
    CZECH("cs", "Čeština", "Czech"),
    SLOVAK("sk", "Slovenčina", "Slovak"),
    ROMANIAN("ro", "Română", "Romanian"),
    HUNGARIAN("hu", "Magyar", "Hungarian"),
    GREEK("el", "Ελληνικά", "Greek"),
    TURKISH("tr", "Türkçe", "Turkish"),
    ARABIC("ar", "العربية", "Arabic", rtl = true),
    HEBREW("he", "עברית", "Hebrew", rtl = true),
    PERSIAN("fa", "فارسی", "Persian", rtl = true),
    URDU("ur", "اردو", "Urdu", rtl = true),
    HINDI("hi", "हिन्दी", "Hindi"),
    BENGALI("bn", "বাংলা", "Bengali"),
    INDONESIAN("id", "Bahasa Indonesia", "Indonesian"),
    MALAY("ms", "Bahasa Melayu", "Malay"),
    VIETNAMESE("vi", "Tiếng Việt", "Vietnamese"),
    THAI("th", "ไทย", "Thai"),
    JAPANESE("ja", "日本語", "Japanese"),
    KOREAN("ko", "한국어", "Korean"),
    CHINESE_SIMPLIFIED("zh-CN", "简体中文", "Chinese (Simplified)"),
    CHINESE_TRADITIONAL("zh-TW", "繁體中文", "Chinese (Traditional)"),
    DUTCH("nl", "Nederlands", "Dutch"),
    SWEDISH("sv", "Svenska", "Swedish"),
    DANISH("da", "Dansk", "Danish"),
    FINNISH("fi", "Suomi", "Finnish"),
    NORWEGIAN("nb", "Norsk bokmål", "Norwegian"),
    BULGARIAN("bg", "Български", "Bulgarian"),
    CROATIAN("hr", "Hrvatski", "Croatian"),
    SERBIAN("sr", "Српски", "Serbian"),
    FILIPINO("fil", "Filipino", "Filipino"),
    SWAHILI("sw", "Kiswahili", "Swahili"),
    AFRIKAANS("af", "Afrikaans", "Afrikaans"),
    CATALAN("ca", "Català", "Catalan"),
    GEORGIAN("ka", "ქართული", "Georgian"),
    ARMENIAN("hy", "Հայերեն", "Armenian"),
    AZERBAIJANI("az", "Azərbaycanca", "Azerbaijani"),
    KAZAKH("kk", "Қазақша", "Kazakh"),
    UZBEK("uz", "Oʻzbekcha", "Uzbek"),
    LITHUANIAN("lt", "Lietuvių", "Lithuanian"),
    LATVIAN("lv", "Latviešu", "Latvian"),
    ESTONIAN("et", "Eesti", "Estonian"),
    SLOVENIAN("sl", "Slovenščina", "Slovenian"),
    ICELANDIC("is", "Íslenska", "Icelandic"),
    ;

    /** True for the entries the picker offers below "Follow system". */
    val isExplicit: Boolean get() = this != SYSTEM

    companion object {
        /** The languages a user can pick, in the order the picker lists them. */
        val selectable: List<AppLanguage> get() = entries.filter { it.isExplicit }

        /** The catalogue English itself uses, and the fallback every other catalogue is read over. */
        val fallback: AppLanguage get() = ENGLISH

        fun forTag(tag: String?): AppLanguage? {
            val wanted = tag?.trim().orEmpty()
            if (wanted.isEmpty()) return null
            return entries.firstOrNull { it.tag.equals(wanted, ignoreCase = true) }
        }

        /**
         * The language a stored tag and a device tag resolve to.
         *
         * An explicit choice wins. An empty or unknown explicit tag means "follow the system": an
         * unknown tag can only come from a newer build or a hand-edited backup, and the honest answer
         * there is the device's language rather than a blank screen. The device match tries the exact
         * tag, then the language with regional aliases for the two families where the region changes
         * the script or the vocabulary — Chinese and Portuguese — then any catalogue with the same
         * primary language.
         */
        fun resolve(configuredTag: String?, systemTag: String?): AppLanguage {
            val configured = forTag(configuredTag)
            if (configured != null && configured.isExplicit) return configured
            return matchSystem(systemTag)
        }

        private fun matchSystem(systemTag: String?): AppLanguage {
            val raw = systemTag?.trim().orEmpty()
            val locale = if (raw.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(raw)
            val language = locale.language.lowercase(Locale.ROOT)
            if (language.isEmpty()) return ENGLISH

            forTag(raw)?.takeIf { it.isExplicit }?.let { return it }

            when (language) {
                "zh" -> {
                    val country = locale.country.uppercase(Locale.ROOT)
                    val script = locale.script.uppercase(Locale.ROOT)
                    val traditional = country in TRADITIONAL_CHINESE_REGIONS || script == "HANT"
                    return if (traditional) CHINESE_TRADITIONAL else CHINESE_SIMPLIFIED
                }
                "pt" -> return PORTUGUESE_BRAZIL
                "no", "nb", "nn" -> return NORWEGIAN
                "iw" -> return HEBREW
                "in" -> return INDONESIAN
            }

            return entries.firstOrNull {
                it.isExplicit && it.tag.substringBefore('-').equals(language, ignoreCase = true)
            } ?: ENGLISH
        }

        private val TRADITIONAL_CHINESE_REGIONS = setOf("TW", "HK", "MO")
    }
}
