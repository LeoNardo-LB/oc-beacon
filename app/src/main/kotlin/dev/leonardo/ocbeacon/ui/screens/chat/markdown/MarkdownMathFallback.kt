package dev.leonardo.ocbeacon.ui.screens.chat.markdown

/**
 * #312② 方案 C：数学定界符降级预变换（TDD 红→绿）。
 *
 * 背景：渲染栈 multiplatform-markdown-renderer 0.45.0 无 math 扩展（已取证），
 * 裁决 = 预变换——不碰渲染器、不增依赖，检测数学定界符并把公式段替换为
 * 等宽可读块：
 * - 块级 `$$...$$`（含多行）与 `\[...\]` → tex 围栏代码块（```tex），
 *   走渲染器既有等宽代码块渲染，公式原样可读且有视觉区分；
 * - 行内 `\(...\)` → 行内代码 span（等宽+底色区分）。取舍：CommonMark
 *   围栏必须在行首开启，行内公式若替换为围栏会把所在句子拆成三个块，
 *   行内代码保留段落流且同样等宽可读。
 *
 * 边界（单测钉死）：
 * - 单个 `$`（货币）不处理；定界符必须成对且内容非空白；
 * - 不成对/跨段落的孤定界符保留原文；
 * - 既有围栏代码块（``` / ~~~，含未闭合）与行内代码 span 内的定界符
 *   字面量不误伤——行级围栏跟踪（同 [normalizeTaskListMarkers] 语义）+
 *   反引号 run 匹配跳过；
 * - 幂等：已变换产物（tex 围栏/行内代码）再次变换不重复处理；
 * - CommonMark 围栏须行首：行中块级定界符前后补换行隔断（围栏可打断段落）。
 *
 * 流式取舍（StreamingMarkdownState 铁律）：前缀差分 append 管线只接受
 * 单调追加——本变换在定界符闭合时会改写已流出的前缀（原文 → 围栏），
 * 与 append 语义不相容。故只接在完结消息的归一化管点
 * （[normalizeMarkdown] → [normalizeForRender]，覆盖预解析/异步/同步全部
 * 渲染路径）；流式期间原文如实（pilot 分支本就放弃归一化，#265 冲突①裁决），
 * 完结时由既有完结跳变+高度补偿吸收（V6 人工验证项）。
 *
 * 性能：流式同步 fallback 路径每批全文归一化——无 `$$`/\(/\[ 痕迹时
 * native indexOf 快路径直返（同 [ensureBlankLineBeforeGfmTables] 守卫姿势）。
 */
internal fun transformMathFallback(content: String): String {
    // 快路径：无任何数学定界符痕迹（essay/纯文本常态零扫描成本）
    if (!content.contains("$$") && !content.contains("\\(") && !content.contains("\\[")) {
        return content
    }
    val lines = content.split('\n')
    val chunks = ArrayList<CharSequence>(lines.size)
    val run = StringBuilder() // 当前围栏外文本区（行粒度，待数学扫描）
    var fenceMarker: Char? = null
    var minFenceLen = 0
    for (line in lines) {
        val marker = FenceLineRegex.find(line)?.groupValues?.get(1)
        when {
            fenceMarker != null -> {
                // 围栏内（含闭合围栏行）原样；只有同字符且足够长的围栏行能闭合
                if (marker != null && marker.first() == fenceMarker && marker.length >= minFenceLen) {
                    fenceMarker = null
                    minFenceLen = 0
                }
                if (run.isNotEmpty()) {
                    chunks.add(transformMathSegments(run.toString()))
                    run.setLength(0)
                }
                chunks.add(line)
            }
            marker != null -> {
                // 开启围栏：先冲刷栏外文本（含数学降级），围栏行本身原样
                if (run.isNotEmpty()) {
                    chunks.add(transformMathSegments(run.toString()))
                    run.setLength(0)
                }
                chunks.add(line)
                fenceMarker = marker.first()
                minFenceLen = marker.length
            }
            else -> {
                if (run.isNotEmpty()) run.append('\n')
                run.append(line)
            }
        }
    }
    if (run.isNotEmpty()) chunks.add(transformMathSegments(run.toString()))
    return chunks.joinToString("\n")
}

/** 围栏行检测（CommonMark：≤3 空格缩进的 ```/~~~ ≥3 连字符）。 */
private val FenceLineRegex = Regex("^ {0,3}(`{3,}|~{3,})")

/**
 * 围栏外文本区的数学定界符扫描替换。行内代码 span（反引号 run）原样跳过
 * ——其内的定界符是字面量，兼作幂等保护（本函数产出的 `公式` 不会再被扫）。
 */
private fun transformMathSegments(text: String): String {
    val out = StringBuilder(text.length + 16)
    var i = 0
    val n = text.length
    while (i < n) {
        when {
            text[i] == '`' -> {
                val runLen = backtickRunLen(text, i)
                val close = matchingBacktickRun(text, i + runLen, runLen)
                val end = if (close < 0) i + runLen else close + runLen
                out.append(text, i, end)
                i = end
            }
            text.startsWith("$$", i) -> i = appendBlockMath(out, text, i, findMathCloser(text, i + 2, "$$"), "$$", "$$")
            text.startsWith("\\[", i) -> i = appendBlockMath(out, text, i, findMathCloser(text, i + 2, "\\]"), "\\[", "\\]")
            text.startsWith("\\(", i) -> {
                // 行内数学：闭合定界符须与开启定界符同行（行内公式不跨行）
                val lineEnd = text.indexOf('\n', i + 2).let { if (it < 0) n else it }
                val close = text.indexOf("\\)", i + 2)
                val inner = if (close > i + 2 && close < lineEnd) text.substring(i + 2, close).trim() else null
                if (inner.isNullOrEmpty()) {
                    out.append("\\(")
                    i += 2
                } else {
                    out.append('`').append(inner).append('`')
                    i = close + 2
                }
            }
            else -> {
                out.append(text[i])
                i++
            }
        }
    }
    return out.toString()
}

/**
 * 块级数学段替换为 tex 围栏。[closeStart] < 0 或内容空白 = 不成对 → 定界符
 * 原样吐出（后续文本继续扫描，不影响下一段配对）。返回推进后的索引。
 */
private fun appendBlockMath(
    out: StringBuilder,
    text: String,
    openStart: Int,
    closeStart: Int,
    openDelim: String,
    closeDelim: String,
): Int {
    val openEnd = openStart + openDelim.length
    val inner = if (closeStart > openEnd) text.substring(openEnd, closeStart).trim() else null
    if (inner.isNullOrEmpty()) {
        out.append(openDelim)
        return openEnd
    }
    // CommonMark：围栏须行首开启——行中定界符前补换行隔断（围栏可打断段落），
    // 围栏后非行尾同样补换行让后续文本独立成段
    if (openStart > 0 && text[openStart - 1] != '\n') out.append('\n')
    out.append("```tex\n").append(inner).append("\n```")
    val closeEnd = closeStart + closeDelim.length
    if (closeEnd < text.length && text[closeEnd] != '\n') out.append('\n')
    return closeEnd
}

/**
 * 在 [from] 后找成对闭合定界符；跨空行（段落边界）不算——两个孤 $$ 分处
 * 不同段落时不得配成一个巨型公式块。返回闭合起始索引，无则 -1。
 */
private fun findMathCloser(text: String, from: Int, delim: String): Int {
    val limit = blankLineStart(text, from)
    val idx = text.indexOf(delim, from)
    return if (idx >= 0 && (limit < 0 || idx + delim.length <= limit)) idx else -1
}

/** [from] 后首个空行前的换行索引（闭合定界符必须结束于此之前），无空行则 -1。 */
private fun blankLineStart(text: String, from: Int): Int {
    var i = text.indexOf('\n', from)
    while (i >= 0) {
        var j = i + 1
        while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
        if (j >= text.length) return -1
        if (text[j] == '\n') return i
        i = text.indexOf('\n', j)
    }
    return -1
}

/** [from] 起的连续反引号长度。 */
private fun backtickRunLen(text: String, from: Int): Int {
    var i = from
    while (i < text.length && text[i] == '`') i++
    return i - from
}

/** [from] 后查找与长度 [len] 严格相等的反引号 run（CommonMark 行内代码配对）；无则 -1。 */
private fun matchingBacktickRun(text: String, from: Int, len: Int): Int {
    var i = from
    while (i < text.length) {
        if (text[i] == '`') {
            val run = backtickRunLen(text, i)
            if (run == len) return i
            i += run
        } else {
            i++
        }
    }
    return -1
}
