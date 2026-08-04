package dev.leonardo.ocbeacon.ui.screens.viewer

import dev.leonardo.ocbeacon.domain.model.Annotation
import dev.leonardo.ocbeacon.domain.model.OffsetConverter
import java.util.UUID

/**
 * 管理单个文件源码视图中 [Annotation] 的内存列表。
 * 批注按创建时间排序（[Annotation.index]）。
 * 删除中间的批注时，剩余批注会重新编号为连续的 0..N-1。
 *
 * @param content 用于从字符偏移计算行:列的完整文件内容。
 *                行结尾会规范化为 \n，以匹配 WebView 的 DOM。
 */
class AnnotationManager(content: String) {

    private val content = content.replace("\r\n", "\n").replace("\r", "\n")

    private val annotations = mutableListOf<Annotation>()

    fun add(selectedText: String, startChar: Int, endChar: Int, note: String): Annotation {
        val start = OffsetConverter.charOffsetToLineCol(content, startChar)
        val end = OffsetConverter.charOffsetToLineCol(content, endChar)
        val annotation = Annotation(
            id = UUID.randomUUID().toString(),
            index = annotations.size,
            startChar = startChar, endChar = endChar,
            startLine = start.line, startCol = start.col,
            endLine = end.line, endCol = end.col,
            selectedText = selectedText, note = note,
            createdAt = System.currentTimeMillis()
        )
        annotations.add(annotation)
        return annotation
    }

    fun delete(id: String) {
        if (annotations.removeAll { it.id == id }) renumber()
    }

    fun update(id: String, note: String) {
        val idx = annotations.indexOfFirst { it.id == id }
        if (idx >= 0) annotations[idx] = annotations[idx].copy(note = note)
    }

    fun getAll(): List<Annotation> = annotations.sortedBy { it.index }

    /**
     * Phase 4：用 [list] 替换所有批注，不重新计算
     * id/index/line-col。用于旋转后从 SavedStateHandle 恢复。
     */
    fun restore(list: List<Annotation>) {
        annotations.clear()
        annotations.addAll(list)
    }

    /** 获取与 0-based [lineIndex] 相交的批注。 */
    fun getForLine(lineIndex: Int): List<Annotation> {
        val target = lineIndex + 1
        return annotations.filter { it.startLine <= target && it.endLine >= target }
    }

    fun clear() = annotations.clear()

    private fun renumber() {
        annotations.sortBy { it.index }
        annotations.forEachIndexed { i, ann ->
            if (ann.index != i) annotations[i] = ann.copy(index = i)
        }
    }
}
