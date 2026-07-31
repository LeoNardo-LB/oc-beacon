package dev.leonardo.ocbeacon.domain.model

/**
 * A user annotation on a code selection in the FileViewer source view.
 * Pure in-memory; not persisted. Lifecycle: created on user action,
 * cleared on ViewModel disposal or after submission.
 */
data class Annotation(
    val id: String,           // UUID string
    val index: Int,           // Creation order (0-based). Display as index + 1.
                              // Re-numbered to consecutive 0..N-1 on middle deletion.
    val startChar: Int,       // Start offset in full content (inclusive)
    val endChar: Int,         // End offset in full content (exclusive)
    val startLine: Int,       // 1-based
    val startCol: Int,        // 1-based
    val endLine: Int,         // 1-based
    val endCol: Int,          // 1-based
    val selectedText: String, // Originally selected snippet
    val note: String,         // User's modification note
    val createdAt: Long       // Epoch millis
) {
    /** Unified position label: "[startLine:startCol-endLine:endCol]" */
    val positionLabel: String
        get() = "[$startLine:$startCol-$endLine:$endCol]"
}

/** 1-based line and column position. */
data class LineCol(val line: Int, val col: Int)

/**
 * Converts between character offsets and 1-based line:col positions.
 * Handles \n, \r\n, and \r line endings (cross-platform files).
 *
 * Semantics: a line terminator character itself belongs to the current line
 * (it occupies a column). The line increment happens only after the terminator
 * sequence is fully consumed, so an offset pointing *at* a terminator still
 * reports the old line.
 */
object OffsetConverter {

    fun charOffsetToLineCol(content: String, offset: Int): LineCol {
        var line = 1
        var col = 1
        val effectiveOffset = offset.coerceIn(0, content.length)
        var i = 0
        while (i < effectiveOffset && i < content.length) {
            when (val c = content[i]) {
                '\r' -> {
                    // '\r' occupies the current column.
                    col++
                    if (!(i + 1 < content.length && content[i + 1] == '\n')) {
                        // Pure CR: line break now.
                        line++; col = 1
                    }
                    // CRLF: '\r' does NOT break; the following '\n' will.
                }
                '\n' -> { line++; col = 1 }
                else -> col++
            }
            i++
        }
        return LineCol(line, col)
    }

    fun lineColToCharOffset(content: String, line: Int, col: Int): Int {
        if (line <= 1) return (col - 1).coerceIn(0, content.length)
        var currentLine = 1
        var i = 0
        while (i < content.length && currentLine < line) {
            when (content[i]) {
                '\r' -> {
                    currentLine++
                    if (i + 1 < content.length && content[i + 1] == '\n') i++
                }
                '\n' -> currentLine++
            }
            i++
        }
        if (currentLine < line) return content.length
        return (i + (col - 1)).coerceAtMost(content.length)
    }
}
