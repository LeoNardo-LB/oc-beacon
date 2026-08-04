package dev.leonardo.ocbeacon.util

import java.util.Locale

/** 将 BCP 47 标签（例如 "pt-BR"、"zh-CN"、"en"）解析为 [Locale]。 */
fun parseLocale(tag: String): Locale = Locale.forLanguageTag(tag)
