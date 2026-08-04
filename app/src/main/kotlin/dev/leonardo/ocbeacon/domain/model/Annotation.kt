package dev.leonardo.ocbeacon.domain.model

/**
 * FileViewer 源码视图中用户对代码选区的标注。
 * 纯内存对象，不持久化。生命周期：用户操作时创建，
 * ViewModel 销毁或提交后清除。
 */
data class Annotation(
    val id: String,           // UUID 字符串
    val index: Int,           // 创建顺序（0 基）。显示时以 index + 1 呈现。
                              // 删除中间项后重新编号为连续的 0..N-1。
    val startChar: Int,       // 完整内容中的起始偏移（包含）
    val endChar: Int,         // 完整内容中的结束偏移（不包含）
    val startLine: Int,       // 基于 1
    val startCol: Int,        // 基于 1
    val endLine: Int,         // 基于 1
    val endCol: Int,          // 基于 1
    val selectedText: String, // 原始选中文本
    val note: String,         // 用户的修改备注
    val createdAt: Long       // 毫秒级时间戳
) {
    /** 统一的位置标签："[startLine:startCol-endLine:endCol]" */
    val positionLabel: String
        get() = "[$startLine:$startCol-$endLine:$endCol]"
}

/** 基于 1 的行、列位置。 */
data class LineCol(val line: Int, val col: Int)

/**
 * 在字符偏移与基于 1 的 line:col 位置之间相互转换。
 * 处理 \n、\r\n、\r 三种换行符（跨平台文件）。
 *
 * 语义：换行符本身属于当前行（占据一列）。
 * 仅当换行序列被完全消费后才会递增行号，
 * 因此指向换行符*所在位置*的偏移仍报告旧行号。
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
                    // '\r' 占据当前列。
                    col++
                    if (!(i + 1 < content.length && content[i + 1] == '\n')) {
                        // 纯 CR：立即换行。
                        line++; col = 1
                    }
                    // CRLF：'\r' 不换行；由紧随其后的 '\n' 负责。
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
