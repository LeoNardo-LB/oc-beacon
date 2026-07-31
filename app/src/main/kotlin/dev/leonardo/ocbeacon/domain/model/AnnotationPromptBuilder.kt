package dev.leonardo.ocbeacon.domain.model

/**
 * Builds structured prompt text for submitting annotations.
 *
 * Format (Markdown):
 *   # 文件备注
 *   对于 <filePath> 文件，用户提出了下述备注。请按照用户备注与指示来做出回答、修改内容或执行任务！
 *
 *   ## 总体备注    (omitted if blank)
 *   <overallNote>
 *
 *   ## 具体备注
 *   注格式为<行号1:字符索引>-<行号2:字符索引> <具体备注内容>
 *   用户的具体备注如下：
 *   1. x1:y1-x2:y2 <note>
 *   2. ...
 */
object AnnotationPromptBuilder {

    fun build(
        annotations: List<Annotation>,
        overallNote: String,
        filePath: String,
        directory: String
    ): String {
        require(annotations.isNotEmpty()) { "Cannot submit empty annotation list" }

        val resolvedPath = resolvePath(filePath, directory)

        val sb = StringBuilder()
        sb.append("# 文件备注\n")
        sb.append("对于 $resolvedPath 文件，用户提出了下述备注。请按照用户备注与指示来做出回答、修改内容或执行任务！\n\n")

        // 总体备注 — only include if non-blank
        if (overallNote.isNotBlank()) {
            sb.append("## 总体备注\n")
            sb.append(overallNote).append("\n\n")
        }

        sb.append("## 具体备注\n")
        sb.append("注格式为<行号1:字符索引>-<行号2:字符索引> <具体备注内容>\n\n")
        sb.append("用户的具体备注如下：\n")
        annotations.sortedBy { it.index }.forEach { ann ->
            sb.append("${ann.index + 1}. ")
              .append("${ann.positionLabel} ")
              .append(ann.note).append("\n")
        }
        return sb.toString().trimEnd()
    }

    private fun resolvePath(filePath: String, directory: String): String {
        if (filePath.isBlank()) return directory
        if (filePath.startsWith("/")) return filePath
        if (filePath.length >= 2 && filePath[1] == ':') return filePath
        if (directory.isBlank()) return filePath
        val sep = if (directory.endsWith("/")) "" else "/"
        return "$directory$sep$filePath"
    }
}
