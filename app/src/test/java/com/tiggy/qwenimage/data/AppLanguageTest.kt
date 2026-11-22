package com.tiggy.qwenimage.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AppLanguageTest {

    @Test
    fun hasExactlyFourteenExplicitLanguagesPlusSystem() {
        assertEquals(14, AppLanguage.EXPLICIT_LANGUAGES.size)
        assertEquals(15, AppLanguage.entries.size)
    }

    @Test
    fun roundTripsAllTags() {
        for (lang in AppLanguage.entries) {
            assertEquals(lang, AppLanguage.fromTag(lang.tag))
        }
        assertEquals(AppLanguage.System, AppLanguage.fromTag(null))
        assertEquals(AppLanguage.System, AppLanguage.fromTag("unknown"))
    }

    @Test
    fun resolvesSystemLocalesToMatchingSupportedLanguage() {
        assertEquals(AppLanguage.ChineseSimplified, AppLanguage.resolveSystemLocale(Locale.SIMPLIFIED_CHINESE))
        assertEquals(AppLanguage.ChineseSimplified, AppLanguage.resolveSystemLocale(Locale("zh", "SG")))
        assertEquals(AppLanguage.ChineseTraditional, AppLanguage.resolveSystemLocale(Locale.TRADITIONAL_CHINESE))
        assertEquals(AppLanguage.ChineseTraditional, AppLanguage.resolveSystemLocale(Locale("zh", "HK")))
        assertEquals(AppLanguage.ChineseTraditional, AppLanguage.resolveSystemLocale(Locale.forLanguageTag("zh-Hant")))
        assertEquals(AppLanguage.Japanese, AppLanguage.resolveSystemLocale(Locale.JAPANESE))
        assertEquals(AppLanguage.Korean, AppLanguage.resolveSystemLocale(Locale.KOREAN))
        assertEquals(AppLanguage.German, AppLanguage.resolveSystemLocale(Locale.GERMANY))
        assertEquals(AppLanguage.French, AppLanguage.resolveSystemLocale(Locale.CANADA_FRENCH))
        assertEquals(AppLanguage.Spanish, AppLanguage.resolveSystemLocale(Locale("es", "MX")))
        assertEquals(AppLanguage.Italian, AppLanguage.resolveSystemLocale(Locale.ITALY))
        assertEquals(AppLanguage.PortugueseBrazil, AppLanguage.resolveSystemLocale(Locale("pt", "BR")))
        assertEquals(AppLanguage.PortugueseBrazil, AppLanguage.resolveSystemLocale(Locale("pt", "PT")))
        assertEquals(AppLanguage.Russian, AppLanguage.resolveSystemLocale(Locale("ru", "RU")))
        assertEquals(AppLanguage.Dutch, AppLanguage.resolveSystemLocale(Locale("nl", "NL")))
        assertEquals(AppLanguage.Polish, AppLanguage.resolveSystemLocale(Locale("pl", "PL")))
        assertEquals(AppLanguage.Turkish, AppLanguage.resolveSystemLocale(Locale("tr", "TR")))
        assertEquals(AppLanguage.English, AppLanguage.resolveSystemLocale(Locale("sv", "SE")))
    }

    @Test
    fun explicitSelectionOverridesSystemLocale() {
        assertEquals(
            AppLanguage.Japanese,
            AppLanguage.resolveEffective(AppLanguage.Japanese, Locale.SIMPLIFIED_CHINESE)
        )
        assertEquals(
            AppLanguage.ChineseSimplified,
            AppLanguage.resolveEffective(AppLanguage.System, Locale.SIMPLIFIED_CHINESE)
        )
    }
}
