package dev.leonardo.ocbeacon.ui.screens.viewer

import dev.leonardo.ocbeacon.domain.model.Annotation
import dev.leonardo.ocbeacon.domain.model.OffsetConverter
import java.util.UUID

/**
 * Manages an in-memory list of [Annotation]s for a single file's source view.
 * Annotations are ordered by creation time ([Annotation.index]).
 * Deleting a middle annotation re-numbers remaining to consecutive 0..N-1.
 *
 * @param content The full file content for computing line:col from char offsets.
 *                Line endings are normalized to \n to match the WebView's DOM.
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
     * Phase 4: Replace all annotations with [list] without re-calculating
     * id/index/line-col. Used to restore from SavedStateHandle after rotation.
     */
    fun restore(list: List<Annotation>) {
        annotations.clear()
        annotations.addAll(list)
    }

    /** Get annotations intersecting 0-based [lineIndex]. */
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
