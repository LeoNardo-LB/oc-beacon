package dev.leonardo.ocbeacon.ui.screens.chat.util

import dev.leonardo.ocbeacon.domain.model.PromptPart
import dev.leonardo.ocbeacon.util.PathUtils

/**
 * 将原始输入文本拆分为 [PromptPart] 对象列表。
 * 已确认 @file 提及周围的文本成为 type="text" parts，
 * 每个 @file 提及成为带 file:// URL 的 type="file" part。
 *
 * 从 ChatInputBar.kt 抽取 —— 纯逻辑，无 Compose 依赖。
 */
internal object PromptBuilder {

    fun buildPromptParts(
        text: String,
        confirmedPaths: Set<String>,
        sessionDirectory: String?
    ): List<PromptPart> {
        if (confirmedPaths.isEmpty()) {
            val trimmed = text.trim()
            return if (trimmed.isEmpty()) emptyList()
            else listOf(PromptPart(type = "text", text = trimmed))
        }

        // 查找文本中所有已确认的 @path 提及及其位置
        data class Mention(val start: Int, val end: Int, val path: String)
        val mentions = mutableListOf<Mention>()

        for (path in confirmedPaths) {
            val needle = "@$path"
            var searchFrom = 0
            while (true) {
                val idx = text.indexOf(needle, searchFrom)
                if (idx == -1) break
                val endIdx = idx + needle.length
                // 边界检查：下一个字符必须是空白、字符串末尾或 @
                if (endIdx < text.length) {
                    val next = text[endIdx]
                    if (!next.isWhitespace() && next != '@') {
                        searchFrom = endIdx
                        continue
                    }
                }
                mentions.add(Mention(idx, endIdx, path))
                searchFrom = endIdx
            }
        }

        if (mentions.isEmpty()) {
            val trimmed = text.trim()
            return if (trimmed.isEmpty()) emptyList()
            else listOf(PromptPart(type = "text", text = trimmed))
        }

        // 按位置排序
        mentions.sortBy { it.start }

        val parts = mutableListOf<PromptPart>()
        var cursor = 0

        for (mention in mentions) {
            // 添加此提及之前的文本
            if (mention.start > cursor) {
                val segment = text.substring(cursor, mention.start).trim()
                if (segment.isNotEmpty()) {
                    parts.add(PromptPart(type = "text", text = segment))
                }
            }
            // 添加文件 part
            val isDir = mention.path.endsWith("/")
            val absPath = if (sessionDirectory != null) "$sessionDirectory/${mention.path}" else mention.path
            val displayName = PathUtils.fileName(mention.path.trimEnd('/', '\\'))
            parts.add(
                PromptPart(
                    type = "file",
                    path = mention.path,
                    mime = if (isDir) "application/x-directory" else "text/plain",
                    url = "file:///$absPath",
                    filename = displayName
                )
            )
            cursor = mention.end
        }

        // 尾部文本
        if (cursor < text.length) {
            val segment = text.substring(cursor).trim()
            if (segment.isNotEmpty()) {
                parts.add(PromptPart(type = "text", text = segment))
            }
        }

        return parts
    }
}
