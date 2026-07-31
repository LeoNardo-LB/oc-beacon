package dev.leonardo.octether.ui.screens.viewer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import dev.leonardo.octether.ui.theme.AlphaTokens
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.BoldHighlight
import dev.snipme.highlights.model.ColorHighlight
import dev.snipme.highlights.model.SyntaxLanguage
import dev.snipme.highlights.model.SyntaxThemes

private const val ALPHA_MASK = 0xFF000000.toInt()

/** Syntax-highlight + annotation builders extracted from CodeSourceView.kt. */
internal object HighlightBuilder {

    fun buildHighlights(
        content: String,
        language: SyntaxLanguage,
        isDark: Boolean
    ): Highlights =
        Highlights.Builder()
            .code(content)
            .language(language)
            .theme(SyntaxThemes.default(isDark))
            .build()

    fun buildAnnotatedStringFromHighlights(
        content: String,
        highlightsResult: Highlights
    ): AnnotatedString = buildAnnotatedString {
        append(content)
        val maxIndex = content.length
        highlightsResult.getHighlights().forEach { h ->
            val start = h.location.start
            val end = (h.location.end + 1).coerceAtMost(maxIndex)
            if (start >= maxIndex || end <= start) return@forEach
            when (h) {
                is ColorHighlight -> addStyle(
                    SpanStyle(color = Color(h.rgb or ALPHA_MASK)),
                    start, end
                )
                is BoldHighlight -> addStyle(
                    SpanStyle(fontWeight = FontWeight.Bold),
                    start, end
                )
            }
        }
    }

    fun rememberLanguage(filePath: String): SyntaxLanguage {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts" -> SyntaxLanguage.KOTLIN
            "java" -> SyntaxLanguage.JAVA
            "js", "mjs", "jsx" -> SyntaxLanguage.JAVASCRIPT
            "ts", "tsx" -> SyntaxLanguage.TYPESCRIPT
            "py" -> SyntaxLanguage.PYTHON
            "go" -> SyntaxLanguage.GO
            "rs" -> SyntaxLanguage.RUST
            "c", "h" -> SyntaxLanguage.C
            "cpp", "cc", "cxx", "hpp", "hxx" -> SyntaxLanguage.CPP
            "cs" -> SyntaxLanguage.CSHARP
            "rb" -> SyntaxLanguage.RUBY
            "dart" -> SyntaxLanguage.DART
            "php" -> SyntaxLanguage.PHP
            "pl", "pm" -> SyntaxLanguage.PERL
            "swift" -> SyntaxLanguage.SWIFT
            "coffee" -> SyntaxLanguage.COFFEESCRIPT
            "sh", "bash", "zsh", "fish" -> SyntaxLanguage.SHELL
            else -> SyntaxLanguage.DEFAULT
        }
    }

    /**
     * Build a per-line AnnotatedString with annotation highlights overlaid on the base syntax-highlighted line.
     */
    fun buildAnnotatedLineWithAnnotations(
        baseLine: AnnotatedString,
        annotations: List<Triple<Int, Int, Int>>,
        baseColor: Color
    ): AnnotatedString = buildAnnotatedString {
        append(baseLine)
        annotations.forEach { (relStart, relEnd, _) ->
            addStyle(
                SpanStyle(background = baseColor.copy(alpha = AlphaTokens.SELECTED)),
                relStart.coerceIn(0, length),
                relEnd.coerceIn(0, length)
            )
        }
    }
}
