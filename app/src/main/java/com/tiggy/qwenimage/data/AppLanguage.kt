package com.tiggy.qwenimage.data

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Supported application display languages (14 languages + Follow System).
 * Native names stay fixed regardless of active locale so users can always recognize their language.
 */
enum class AppLanguage(
    val tag: String,
    val nativeName: String
) {
    System("system", ""),
    English("en", "English"),
    ChineseSimplified("zh-CN", "简体中文"),
    ChineseTraditional("zh-TW", "繁體中文"),
    Japanese("ja", "日本語"),
    Korean("ko", "한국어"),
    German("de", "Deutsch"),
    French("fr", "Français"),
    Spanish("es", "Español"),
    Italian("it", "Italiano"),
    PortugueseBrazil("pt-BR", "Português (Brasil)"),
    Russian("ru", "Русский"),
    Dutch("nl", "Nederlands"),
    Polish("pl", "Polski"),
    Turkish("tr", "Türkçe");

    fun toLocale(): Locale? = when (this) {
        System -> null
        English -> Locale.ENGLISH
        ChineseSimplified -> Locale.SIMPLIFIED_CHINESE
        ChineseTraditional -> Locale.TRADITIONAL_CHINESE
        Japanese -> Locale.JAPANESE
        Korean -> Locale.KOREAN
        German -> Locale.GERMAN
        French -> Locale.FRENCH
        Spanish -> Locale("es")
        Italian -> Locale.ITALIAN
        PortugueseBrazil -> Locale("pt", "BR")
        Russian -> Locale("ru")
        Dutch -> Locale("nl")
        Polish -> Locale("pl")
        Turkish -> Locale("tr")
    }

    companion object {
        val EXPLICIT_LANGUAGES: List<AppLanguage> = entries.filter { it != System }

        fun fromTag(tag: String?): AppLanguage =
            entries.firstOrNull { it.tag.equals(tag?.trim(), ignoreCase = true) } ?: System

        /** Resolves a system [Locale] to one of the 14 supported explicit languages. */
        fun resolveSystemLocale(locale: Locale): AppLanguage {
            val lang = locale.language.lowercase(Locale.ROOT)
            val country = locale.country.uppercase(Locale.ROOT)
            val script = locale.script
            return when (lang) {
                "zh" -> if (script.equals("Hant", ignoreCase = true) ||
                    country == "TW" || country == "HK" || country == "MO"
                ) ChineseTraditional else ChineseSimplified
                "ja" -> Japanese
                "ko" -> Korean
                "de" -> German
                "fr" -> French
                "es" -> Spanish
                "it" -> Italian
                "pt" -> PortugueseBrazil
                "ru" -> Russian
                "nl" -> Dutch
                "pl" -> Polish
                "tr" -> Turkish
                else -> English
            }
        }

        fun resolveEffective(selected: AppLanguage, systemLocale: Locale): AppLanguage =
            if (selected == System) resolveSystemLocale(systemLocale) else selected
    }
}

object AppLanguageManager {
    private const val PREFS_NAME = "omni-app-prefs"
    private const val KEY_LANGUAGE = "app_language"

    @Volatile
    private var initialized = false
    private val _language = MutableStateFlow(AppLanguage.System)
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    fun current(context: Context): AppLanguage {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    val saved = context.applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getString(KEY_LANGUAGE, AppLanguage.System.tag)
                    _language.value = AppLanguage.fromTag(saved)
                    initialized = true
                }
            }
        }
        return _language.value
    }

    fun setLanguage(context: Context, language: AppLanguage) {
        synchronized(this) {
            initialized = true
            _language.value = language
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LANGUAGE, language.tag)
                .apply()
        }
    }

    fun systemDefaultLocale(): Locale {
        val list = Resources.getSystem().configuration.locales
        return if (!list.isEmpty) list[0] else Locale.getDefault()
    }

    fun effectiveLanguage(selected: AppLanguage = _language.value): AppLanguage =
        AppLanguage.resolveEffective(selected, systemDefaultLocale())

    /** Builds a configuration-wrapped context for [selected] without mutating process-wide Activity state. */
    fun localizedContext(base: Context, selected: AppLanguage = current(base)): Context {
        val effective = effectiveLanguage(selected)
        val locale = effective.toLocale() ?: Locale.ENGLISH
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
