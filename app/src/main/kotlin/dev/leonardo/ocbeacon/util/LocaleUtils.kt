package dev.leonardo.ocbeacon.util

import android.content.Context
import dev.leonardo.ocbeacon.data.repository.SettingsDataStore
import java.util.Locale

/** 将 BCP 47 标签（例如 "pt-BR"、"zh-CN"、"en"）解析为 [Locale]。 */
fun parseLocale(tag: String): Locale = Locale.forLanguageTag(tag)

/**
 * 在 attachBaseContext 中应用已存储的语言（D2-L20：MainActivity 与 OpenCodeConnectionService 双处重复提取）。
 * 返回应用语言后的 context；未设置语言时原样返回。
 */
fun Context.applyAppLanguage(): Context {
    val languageCode = SettingsDataStore.getStoredLanguage(this)
    if (languageCode.isEmpty()) return this
    val locale = parseLocale(languageCode)
    Locale.setDefault(locale)
    val config = resources.configuration
    config.setLocale(locale)
    return createConfigurationContext(config)
}
