package dev.leonardo.ocbeacon.ui.screens.chat.markdown

/**
 * #437 两级安全放行闸（2026-09-25 块级重写，纯函数，零库依赖）。
 *
 * 使命：把交给 StreamingMarkdownState.append 的文本流变成「定案内容」。
 * 用户裁决（2026-09-25）：未闭合构造零输出，闭合符号落地后整体上屏；表格按
 * 完整行渐显。
 *
 * 放行规则（对快照 S、已放行长度 floor）：
 *  1. 空行毕业：开放区中最后一个空行块之前的全部内容已定案 → 无条件放行；
 *  2. 块级行扫描（最后开放段内逐行状态机）：
 *     a. 围栏代码块：开栏行起整体扣留（零输出），闭栏行落地后整块放行
 *        （含开/闭栏行）；扣留跨批时经全文围栏状态扫描恢复状态（phase-0）；
 *     b. 表格：表头+分隔行两行齐备后整体放行，其后每条完整表行逐行放行
 *        （GFM 按行增长无重释义——单调安全）；
 *     c. 普通行：完整行且行内无活动标记 → 放行至行尾；含标记（未闭合
 *        内联构造）或未完行 → 从该行起扣留，等空行毕业或完结 flush。
 *
 * 不变量：
 *  - 结果单调不回退（>= alreadyReleased）——放行流只增不减，与 #435 高度
 *    引擎「锚即意图」配对天然兼容（单调量子增长）；
 *  - 扣留内容去路恒两条：毕业（空行/闭栏/换行边界推进）或完结 EOF 全量
 *    flush——一字不丢；
 *  - 放行边界必为行边界或块边界：行中间前缀不放。
 */
internal object SafePrefixGate {

    /**
     * 活动标记字符集：行内出现即差终止符（未闭合内联构造，未来行可闭合回溯
     * 重释义已上屏内容）。☐/☑/✅（#437 阶段 C）：normalizeTaskListMarkers 完结
     * 变换字符——流中字面放行会在完结时替换为 GFM 任务列表，扣留等空行闭合。
     */
    private const val ACTIVE_MARKERS = "*_~#>|[]!\u2610\u2611\u2705"

    /** CommonMark 有序列表起始标记的最多位数。 */
    private const val ORDERED_LIST_MAX_DIGITS = 9

    /**
     * 计算快照 [snapshot] 相对已放行长度 [alreadyReleased] 的安全放行长度。
     * 调用方保证 [alreadyReleased] 是此前某次调用结果（或 0/重建清零）。
     */
    fun releaseLength(snapshot: String, alreadyReleased: Int): Int {
        val floor = alreadyReleased.coerceIn(0, snapshot.length)
        if (floor >= snapshot.length) return floor

        // —— 第一级：空行毕业——开放区内最后一个空行块之后的起点。
        var cand = floor
        var i = floor
        var atLineStart = true // floor（上轮放行边界）恒为行首
        while (i < snapshot.length) {
            if (atLineStart && isBlankLineAt(snapshot, i)) {
                i = skipBlankLines(snapshot, i)
                cand = i
            } else {
                atLineStart = snapshot[i] == '\n'
                i++
            }
        }

        // —— 第二级：块级行扫描（2026-09-25 用户裁决重写）。
        var allowed = cand
        var j = cand
        var inFence = fenceStateAt(snapshot, cand)
        while (j < snapshot.length) {
            val nl = snapshot.indexOf('\n', j)
            val complete = nl >= 0
            val lineEnd = if (complete) nl else snapshot.length
            val line = snapshot.substring(j, lineEnd)
            when {
                inFence -> {
                    // 扣留块内部：完整行逐行放行（含闭栏行），闭栏后退出块态
                    if (!complete) break
                    allowed = nl + 1
                    j = nl + 1
                    if (isFenceClose(line)) inFence = false
                }
                isFenceOpen(line) != null -> {
                    // 未闭合围栏：从开栏行起整体扣留（零输出）
                    val close = findFenceClose(snapshot, lineEnd + 1, isFenceOpen(line)!!)
                    if (close < 0) break
                    allowed = close
                    j = close
                }
                complete && isTableHeaderRow(line) -> {
                    val sepNl = snapshot.indexOf('\n', nl + 1)
                    val sep = if (sepNl < 0) "" else snapshot.substring(nl + 1, sepNl)
                    if (sepNl >= 0 && isTableSeparatorRow(sep)) {
                        // 表头+分隔行整体放行，其后完整表行逐行渐显
                        allowed = sepNl + 1
                        var k = sepNl + 1
                        while (k < snapshot.length) {
                            val rNl = snapshot.indexOf('\n', k)
                            if (rNl < 0) break
                            if (!isTableRowLine(snapshot.substring(k, rNl))) break
                            allowed = rNl + 1
                            k = rNl + 1
                        }
                        j = k
                    } else break // 表未成形（分隔行未到/不完整）：扣留
                }
                complete && !lineHasActiveMarker(line) -> { allowed = nl + 1; j = nl + 1 }
                else -> break // 含活动标记的行 / 未完行：扣留等毕业
            }
        }
        // setext 守卫（保留旧语义）：快照以换行结束且末条已放行行是普通文字行
        // → 回退一行（未来 "---"/"===" 会把它升格为 setext 标题=重释义）。
        if (allowed > cand && snapshot.endsWith("\n") && !inFence) {
            val prevNl = snapshot.lastIndexOf('\n', allowed - 2)
            val lineStart = prevNl + 1
            if (lineStart >= cand) {
                val prevLine = snapshot.substring(lineStart, allowed - 1)
                if (prevLine.isNotBlank() && isFenceOpen(prevLine) == null && !isTableRowLine(prevLine)) {
                    allowed = lineStart
                }
            }
        }
        val boundary = allowed
        // #437 验收二轮：单批放行量子上限。空行毕业一次可放整段——LazyList 对
        // 暴涨的锚定校正产生「吸底→弹回」两态翻转（用户看到的震荡）。限制单批后
        // 超出部分留扣留区，下一批（48ms）继续。截断点回退行边界（不放半行）。
        var release = maxOf(floor, boundary)
        if (release - floor > MAX_RELEASE_PER_BATCH) {
            val cap = floor + MAX_RELEASE_PER_BATCH
            val lastNlBefore = snapshot.lastIndexOf('\n', (cap - 1).coerceAtLeast(floor))
            release = if (lastNlBefore >= floor) lastNlBefore + 1 else floor
        }
        return release
    }

    // ===== 块级判定辅助（2026-09-25 行扫描重写） =====

    /** 开栏行：≤3 空白缩进 + ≥3 个反引号或 ~ + info string（反引号栏 info 不得含反引号）。返回 栏字符 to 栏长。 */
    private fun isFenceOpen(line: String): Pair<Char, Int>? {
        var i = 0
        var indent = 0
        while (i < line.length && indent < 3 && (line[i] == ' ' || line[i] == '\t')) { i++; indent++ }
        if (i >= line.length) return null
        val c = line[i]
        if (c != '`' && c != '~') return null
        var n = 0
        while (i + n < line.length && line[i + n] == c) n++
        if (n < 3) return null
        val rest = line.substring(i + n)
        if (c == '`' && rest.indexOf('`') >= 0) return null
        return c to n
    }

    /** 闭栏行：仅 ≥3 个同字符 + 可选空白（无 info）。 */
    private fun isFenceClose(line: String): Boolean {
        val t = line.trim(' ', '\t')
        if (t.isEmpty()) return false
        val c = t[0]
        if (c != '`' && c != '~') return false
        var n = 0
        while (n < t.length && t[n] == c) n++
        return n >= 3 && n == t.length
    }

    /** 从 [from] 起找与 [open] 配对的闭栏行，返回放行边界（含闭栏行换行）；未闭合 = -1。 */
    private fun findFenceClose(snapshot: String, from: Int, open: Pair<Char, Int>): Int {
        var k = from
        while (k <= snapshot.length) {
            val nl = snapshot.indexOf('\n', k)
            val line = snapshot.substring(k, if (nl < 0) snapshot.length else nl)
            val t = line.trim(' ', '\t')
            var n = 0
            while (n < t.length && t[n] == open.first) n++
            if (n >= open.second && n == t.length) {
                return if (nl < 0) snapshot.length else nl + 1
            }
            if (nl < 0) break
            k = nl + 1
        }
        return -1
    }

    /**
     * [pos] 处是否处于未闭合围栏内部——从快照行首扫描到 pos 恢复围栏状态。
     * 行扫描批间无状态（cand 会落在上批未放行块内部），每批 O(n) 重建
     * （n=快照长，数十 KB 量级，48ms 批节奏下成本可忽略）。
     */
    private fun fenceStateAt(snapshot: String, pos: Int): Boolean {
        var inFence = false
        var openLen = 3
        var openChar = '`'
        var k = 0
        while (k < pos) {
            val nl = snapshot.indexOf('\n', k)
            val lineEnd = if (nl < 0) pos else nl
            val line = snapshot.substring(k, lineEnd)
            if (inFence) {
                val t = line.trim(' ', '\t')
                var n = 0
                while (n < t.length && t[n] == openChar) n++
                if (n >= openLen && n == t.length) inFence = false
            } else {
                val open = isFenceOpen(line)
                if (open != null) { inFence = true; openChar = open.first; openLen = open.second }
            }
            if (nl < 0 || lineEnd >= pos) break
            k = lineEnd + 1
        }
        return inFence
    }

    /** 行内含活动标记（未闭合内联构造风险）、双美元（完结数学变换）或行首有序列表起始。单美元是普通文字。 */
    private fun lineHasActiveMarker(line: String): Boolean {
        for (k in line.indices) {
            val c = line[k]
            if (ACTIVE_MARKERS.indexOf(c) >= 0) return true
            if (c == '$' && k + 1 < line.length && line[k + 1] == '$') return true
        }
        return isOrderedListStart(line, 0)
    }

    /** 表行：非空白起始为 |（与 GFM 宽松判定一致——逐行渐显足够）。 */
    private fun isTableRowLine(line: String): Boolean = line.trimStart(' ', '\t').startsWith("|")

    /** 放行决策：新放行长度（快照坐标）+ 实际交给 append 的 delta 文本。 */
    internal class ReleaseDecision(val newReleased: Int, val delta: String)

    /**
     * #437 阶段 C：放行决策（含表格粘边空行注入）。
     *
     * 完结归一化 ensureBlankLineBeforeGfmTables 会在「文字行紧贴表头行」处
     * 补空行——此处在放行 delta 内做与归一化同判定的增量前移（注入后归一化
     * 幂等，流中与完结渲染一致）。
     *
     * [newReleased] 是快照坐标；[delta] 可能比快照区间多注入的换行——
     * state.content 与快照长度自此解耦（调用方从不比较二者）。
     */
    fun releaseDelta(snapshot: String, alreadyReleased: Int): ReleaseDecision {
        val r = releaseLength(snapshot, alreadyReleased)
        val from = alreadyReleased.coerceIn(0, snapshot.length)
        val delta = snapshot.substring(from, r)
        return ReleaseDecision(r, injectTableBlankLines(snapshot, from, delta))
    }

    /** 放行 delta 内的表格粘边空行注入（判定与 TABLE_AFTER_TEXT_REGEX 同语义）。 */
    private fun injectTableBlankLines(snapshot: String, from: Int, delta: String): String {
        if (delta.indexOf('|') < 0 || delta.length < 4) return delta
        val out = StringBuilder(delta.length + 4)
        var lineStart = 0
        var prevNonEmptyTextLine = false // 前行=非空且非 | 结尾（注入条件）
        var first = true
        while (lineStart <= delta.length) {
            val nl = delta.indexOf('\n', lineStart)
            val lineEnd = if (nl < 0) delta.length else nl
            val line = delta.substring(lineStart, lineEnd)
            if (isTableHeaderRow(line)) {
                val nextStart = if (nl < 0) -1 else nl + 1
                val nextNl = if (nextStart < 0) -1 else delta.indexOf('\n', nextStart)
                val nextEnd = if (nextNl < 0) delta.length else nextNl
                val sep = if (nextStart < 0) "" else delta.substring(nextStart, nextEnd)
                val prevIsText = if (first) prevLineBeforeIsTextRow(snapshot, from) else prevNonEmptyTextLine
                if (isTableSeparatorRow(sep) && prevIsText) out.append('\n')
            }
            if (lineStart >= delta.length) break
            out.append(line)
            if (nl >= 0) out.append('\n')
            prevNonEmptyTextLine = line.isNotEmpty() && !line.endsWith("|")
            first = false
            lineStart = lineEnd + 1
        }
        return out.toString()
    }

    /** 单批放行上限（字符）——约 6-8 行正文。超出跨批渐进（48ms/批）。 */
    private const val MAX_RELEASE_PER_BATCH = 400

    /** 表头行：可选缩进 + | 开头 + | 结尾（含至少一个内部字符）。 */
    private fun isTableHeaderRow(line: String): Boolean {
        val t = line.trimStart(' ', '\t')
        return t.length >= 2 && t.startsWith('|') && t.endsWith('|') && t.length > 2
    }

    /** 分隔行：可选缩进 + | 包裹，内部仅 - : 空格 | 且含 -。 */
    private fun isTableSeparatorRow(line: String): Boolean {
        val t = line.trimStart(' ', '\t')
        if (t.length < 3 || !t.startsWith('|') || !t.endsWith('|')) return false
        var hasDash = false
        for (c in t.substring(1, t.length - 1)) {
            when {
                c == '-' -> hasDash = true
                c == ':' || c == ' ' || c == '\t' || c == '|' -> Unit
                else -> return false
            }
        }
        return hasDash
    }

    /** delta 首行前的快照行（上批末行）是否为文字行（非空、非 | 结尾、存在）。 */
    private fun prevLineBeforeIsTextRow(snapshot: String, from: Int): Boolean {
        if (from == 0) return false
        val prevNl = snapshot.lastIndexOf('\n', from - 2)
        val lineStart = prevNl + 1
        val prevEnd = from - 1 // from 前必是换行（放行边界恒在行后）或行中
        if (lineStart >= prevEnd) return false
        val line = snapshot.substring(lineStart, prevEnd.coerceAtMost(snapshot.length))
        return line.isNotEmpty() && !line.endsWith("|")
    }

    /** [i] 是行首且该行是空行（仅空白字符直到换行/结尾）。 */
    private fun isBlankLineAt(s: String, i: Int): Boolean {
        var k = i
        while (k < s.length) {
            val c = s[k]
            if (c == '\n') return true
            if (c != ' ' && c != '\t' && c != '\r') return false
            k++
        }
        return true // 结尾的空白行
    }

    /** 从空行行首 [i] 起跨过连续空行，返回下一非空行的行首。 */
    private fun skipBlankLines(s: String, i: Int): Int {
        var k = i
        while (k < s.length && isBlankLineAt(s, k)) {
            while (k < s.length && s[k] != '\n') k++
            if (k < s.length) k++ // 跨过换行
            else return s.length
        }
        return k
    }

    /** [i] 是行首且匹配有序列表起始：1-9 位数字 + [.)] + 空格或行尾。 */
    private fun isOrderedListStart(s: String, i: Int): Boolean {
        var k = i
        var digits = 0
        while (k < s.length && s[k] in '0'..'9' && digits < ORDERED_LIST_MAX_DIGITS) { k++; digits++ }
        if (digits == 0 || digits > ORDERED_LIST_MAX_DIGITS) return false
        if (k >= s.length) return false
        val marker = s[k]
        if (marker != '.' && marker != ')') return false
        k++
        return k >= s.length || s[k] == ' ' || s[k] == '\t'
    }
}
