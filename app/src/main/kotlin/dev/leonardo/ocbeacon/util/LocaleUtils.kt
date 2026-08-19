package dev.leonardo.ocbeacon.util

import android.content.Context
import android.os.StrictMode
import dev.leonardo.ocbeacon.data.repository.SettingsDataStore
import java.util.Locale

/** 将 BCP 47 标签（例如 "pt-BR"、"zh-CN"、"en"）解析为 [Locale]。 */
fun parseLocale(tag: String): Locale = Locale.forLanguageTag(tag)

/**
 * 读取语言镜像（#136 设计的 attachBaseContext 同步读）。
 *
 * StrictMode ②（2026-08-19）：DataStore 异步而 locale 必须在 base context
 * 包装前确定，故该同步读是设计行为——allowThreadDiskReads 显式声明意图，
 * 避免设计读永久刷 StrictMode 噪音掩盖真实回归（Android 官方推荐模式：
 * 仅包裹已知的有意读）。attachBaseContext 路径的所有读取统一经此入口。
 */
fun readStoredLanguagePermitted(context: Context): String {
    val previousPolicy = StrictMode.allowThreadDiskReads()
    return try {
        SettingsDataStore.getStoredLanguage(context)
    } finally {
        StrictMode.setThreadPolicy(previousPolicy)
    }
}

/**
 * 在 attachBaseContext 中应用已存储的语言（D2-L20：MainActivity 与 OpenCodeConnectionService 双处重复提取）。
 * 返回应用语言后的 context；未设置语言时原样返回。
 */
fun Context.applyAppLanguage(): Context {
    val languageCode = readStoredLanguagePermitted(this)
    if (languageCode.isEmpty()) return this
    val locale = parseLocale(languageCode)
    Locale.setDefault(locale)
    val config = resources.configuration
    config.setLocale(locale)
    return createConfigurationContext(config)
}
