package com.tellybox.tv.ui.settings

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The interface languages the viewer can pick, each named in its own language.
 * Keep in step with res/xml/locales_config.xml and the values-* folders.
 */
object AppLanguages {
    class Language(val tag: String, val name: String)

    val all = listOf(
        Language("en", "English"),
        Language("ar", "العربية"),
        Language("bg", "Български"),
        Language("cs", "Čeština"),
        Language("da", "Dansk"),
        Language("de", "Deutsch"),
        Language("el", "Ελληνικά"),
        Language("es", "Español"),
        Language("fi", "Suomi"),
        Language("fr", "Français"),
        Language("hr", "Hrvatski"),
        Language("hu", "Magyar"),
        Language("it", "Italiano"),
        Language("iw", "עברית"),
        Language("ka", "ქართული"),
        Language("lt", "Lietuvių"),
        Language("lv", "Latviešu"),
        Language("nl", "Nederlands"),
        Language("no", "Norsk"),
        Language("pl", "Polski"),
        Language("pt-PT", "Português (Portugal)"),
        Language("pt-BR", "Português (Brasil)"),
        Language("ro", "Română"),
        Language("ru", "Русский"),
        Language("sk", "Slovenčina"),
        Language("sl", "Slovenščina"),
        Language("sr", "Српски"),
        Language("sr-Latn", "Srpski"),
        Language("sv", "Svenska"),
        Language("tr", "Türkçe"),
        Language("uk", "Українська"),
        Language("vi", "Tiếng Việt"),
        Language("zh-CN", "简体中文"),
    )

    /** The chosen language, or null when the app follows the system. */
    fun current(): Language? {
        val chosen = AppCompatDelegate.getApplicationLocales().takeUnless { it.isEmpty }?.get(0) ?: return null
        fun Locale.same(o: Locale) = language == o.language && script == o.script && country == o.country
        return all.firstOrNull { Locale.forLanguageTag(it.tag).same(chosen) }
            ?: all.firstOrNull { Locale.forLanguageTag(it.tag).language == chosen.language }
    }

    /** Applies and remembers [tag]; an empty tag goes back to the system language. Main thread only. */
    fun apply(tag: String) {
        AppCompatDelegate.setApplicationLocales(
            if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag),
        )
    }
}
