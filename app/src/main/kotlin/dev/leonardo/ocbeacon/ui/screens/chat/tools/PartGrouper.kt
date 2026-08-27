package dev.leonardo.ocbeacon.ui.screens.chat.tools

import dev.leonardo.ocbeacon.domain.model.Part

// #247：改为 internal——RenderableTurn 的回合内 tool 卡折叠需要同一排除集合
internal val CONTEXT_TOOLS = setOf("read", "glob", "grep")

sealed class PartGroup {
    data class Context(val parts: List<Part.Tool>) : PartGroup()
    data class Single(val part: Part) : PartGroup()
}

data class ContextSummary(val read: Int, val search: Int)

fun groupContextParts(parts: List<Part>): List<PartGroup> {
    val result = mutableListOf<PartGroup>()
    val buffer = mutableListOf<Part.Tool>()

    fun flush() {
        if (buffer.size >= 2) {
            result.add(PartGroup.Context(buffer.toList()))
        } else {
            buffer.forEach { result.add(PartGroup.Single(it)) }
        }
        buffer.clear()
    }

    for (part in parts) {
        if (part is Part.Tool && part.tool.lowercase() in CONTEXT_TOOLS) {
            buffer.add(part)
        } else {
            flush()
            result.add(PartGroup.Single(part))
        }
    }
    flush()
    return result
}

fun contextToolSummary(parts: List<Part.Tool>): ContextSummary {
    val read = parts.count { it.tool.lowercase() == "read" }
    val search = parts.count { it.tool.lowercase() in setOf("glob", "grep") }
    return ContextSummary(read, search)
}
