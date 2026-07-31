package dev.leonardo.octether.ui.screens.chat.markdown

/**
 * Normalizes Unicode task-list markers (☐/☑/✅) back to GFM checkbox syntax
 * (`[ ]`/`[x]`) so the Markdown renderer displays proper interactive checkboxes.
 *
 * Lines inside fenced code blocks (``` or ~~~) are left untouched, matching
 * CommonMark fence-tracking semantics.
 */
internal fun normalizeTaskListMarkers(markdown: String): String {
    var fenceMarker: Char? = null
    var minimumFenceLength = 0
    return markdown.split('\n').joinToString("\n") { line ->
        val marker = MarkdownFenceStartRegex.find(line)?.groupValues?.get(1)
        if (fenceMarker != null) {
            // Inside a fence — only a matching closing fence can end it.
            if (marker != null && marker.first() == fenceMarker && marker.length >= minimumFenceLength) {
                fenceMarker = null
                minimumFenceLength = 0
            }
            line
        } else if (marker != null) {
            // Opening a new fence.
            fenceMarker = marker.first()
            minimumFenceLength = marker.length
            line
        } else {
            // Outside any fence — normalize Unicode task markers to GFM syntax.
            TaskListMarkerRegex.replace(line) { match ->
                val checkbox = if (match.groupValues[2][0] == BALLOT_BOX) "[ ]" else "[x]"
                match.groupValues[1] + checkbox + match.groupValues[3]
            }
        }
    }
}

private const val BALLOT_BOX = '\u2610' // ☐ — unchecked; ☑ (\u2611) and ✅ (\u2705) map to checked.

private val MarkdownFenceStartRegex = Regex("^ {0,3}(`{3,}|~{3,})")
private val TaskListMarkerRegex = Regex("^(\\s*[-+*]\\s+)([\u2610\u2611\u2705])([ \\t]+)")
