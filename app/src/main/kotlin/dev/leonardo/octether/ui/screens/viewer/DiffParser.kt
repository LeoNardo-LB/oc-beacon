package dev.leonardo.octether.ui.screens.viewer

class DiffParser {

    companion object {
        private val HUNK_HEADER = Regex("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@")
    }

    fun parseUnifiedDiff(patch: String): List<DiffHunk> {
        val lines = patch.lines()
        val hunks = mutableListOf<DiffHunk>()
        var i = 0
        while (i < lines.size) {
            val m = HUNK_HEADER.find(lines[i])
            if (m != null) {
                val startLine = m.groupValues[2].toIntOrNull() ?: 1
                val patchStartIdx = i
                var hasAdded = false
                var hasRemoved = false
                i++
                while (i < lines.size && !lines[i].startsWith("@@")) {
                    when {
                        lines[i].startsWith("+") -> hasAdded = true
                        lines[i].startsWith("-") -> hasRemoved = true
                    }
                    i++
                }
                val type = when {
                    hasAdded && hasRemoved -> DiffHunkType.MODIFIED
                    hasAdded -> DiffHunkType.ADDED
                    hasRemoved -> DiffHunkType.REMOVED
                    else -> DiffHunkType.MODIFIED
                }
                hunks.add(
                    DiffHunk(
                        startLine = startLine,
                        patchStartLineIndex = patchStartIdx,
                        type = type,
                        rawPatch = lines.subList(patchStartIdx, i).joinToString("\n")
                    )
                )
            } else {
                i++
            }
        }
        return hunks
    }
}
