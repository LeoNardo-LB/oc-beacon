package dev.leonardo.ocbeacon.ui.screens.chat.input

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Visual transformation that highlights confirmed @file mentions in the input field.
 * Confirmed paths get a colored background + bold style.
 * Unconfirmed @query mentions (still searching) and plain text
 * remain unstyled so the user can see they haven't been selected yet.
 */
internal class FileMentionVisualTransformation(
    private val confirmedFilePaths: Set<String>,
    private val highlightColor: Color,
    private val bgColor: Color
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (confirmedFilePaths.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }
        val raw = text.text
        val annotated = buildAnnotatedString {
            append(raw)
            // For each confirmed path, find all occurrences of @path in the text
            for (path in confirmedFilePaths) {
                val needle = "@$path"
                var searchFrom = 0
                while (true) {
                    val idx = raw.indexOf(needle, searchFrom)
                    if (idx == -1) break
                    // Ensure the match is not part of a longer token:
                    // next char after needle should be whitespace, end-of-string, or another @
                    val endIdx = idx + needle.length
                    if (endIdx < raw.length) {
                        val next = raw[endIdx]
                        if (!next.isWhitespace() && next != '@') {
                            searchFrom = endIdx
                            continue
                        }
                    }
                    addStyle(
                        SpanStyle(
                            color = highlightColor,
                            background = bgColor,
                            fontWeight = FontWeight.SemiBold
                        ),
                        start = idx,
                        end = endIdx
                    )
                    searchFrom = endIdx
                }
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}
