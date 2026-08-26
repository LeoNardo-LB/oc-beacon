package dev.leonardo.ocbeacon.ui.screens.chat.markdown

/**
 * 将 Unicode 任务列表标记（☐/☑/✅）规范化回 GFM 复选框语法
 *（`[ ]`/`[x]`），以便 Markdown 渲染器显示正确的交互式复选框。
 *
 * 围栏代码块（``` 或 ~~~）内的行保持不变，符合
 * CommonMark 围栏跟踪语义。
 */
internal fun normalizeTaskListMarkers(markdown: String): String {
    // 2026-08-26 流式卡顿根因修复：任务标记替换仅在行含 ☐/☑/✅ 时产生输出
    // 变化，fence 跟踪也只为保护标记替换而存在——全文无三字符则输出恒等于
    // 输入，两正则/行全免（native indexOf 扫描，essay 常态零正则）。
    if (markdown.indexOf(BALLOT_BOX) < 0 &&
        markdown.indexOf('\u2611') < 0 &&
        markdown.indexOf('\u2705') < 0
    ) return markdown
    var fenceMarker: Char? = null
    var minimumFenceLength = 0
    return markdown.split('\n').joinToString("\n") { line ->
        val marker = MarkdownFenceStartRegex.find(line)?.groupValues?.get(1)
        if (fenceMarker != null) {
            // 在围栏内——只有匹配的关闭围栏才能结束它。
            if (marker != null && marker.first() == fenceMarker && marker.length >= minimumFenceLength) {
                fenceMarker = null
                minimumFenceLength = 0
            }
            line
        } else if (marker != null) {
            // 开启新的围栏。
            fenceMarker = marker.first()
            minimumFenceLength = marker.length
            line
        } else {
            // 在任何围栏外——将 Unicode 任务标记规范化为 GFM 语法。
            TaskListMarkerRegex.replace(line) { match ->
                val checkbox = if (match.groupValues[2][0] == BALLOT_BOX) "[ ]" else "[x]"
                match.groupValues[1] + checkbox + match.groupValues[3]
            }
        }
    }
}

private const val BALLOT_BOX = '\u2610' // ☐ — 未选中；☑ (\u2611) 和 ✅ (\u2705) 映射为已选中。

private val MarkdownFenceStartRegex = Regex("^ {0,3}(`{3,}|~{3,})")
private val TaskListMarkerRegex = Regex("^(\\s*[-+*]\\s+)([\u2610\u2611\u2705])([ \\t]+)")
