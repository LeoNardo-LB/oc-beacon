package dev.leonardo.ocbeacon.ui.screens.chat.markdown

/**
 * #437 两级安全放行闸（2026-09-26 二次重写，纯函数，零库依赖）。
 *
 * 用户裁决修订（2026-09-26）：**纯文字增量直出**——纯文字（无活动标记）的
 * 字面临时排版=最终排版（库 stable/unstable 分裂第二道防线），未完行逐批
 * 增量放行=终端式流式节奏，不再等整段收完；含标记行/围栏/表格仍按闭合语义
 * （未闭合零输出，闭合整体上屏；表格按完整行渐显）。
 *
 * 放行规则（对快照 S、已放行长度 floor）：
 *  1. **空行毕业**：开放区中最后一个空行块之前的全部内容已定案 → 无条件放行；
 *  2. **块级行扫描**（最后开放段内逐行状态机，单批预算 MAX_RELEASE_PER_BATCH
 *     循环内逐次扣减）：
 *     a. 围栏代码块：开栏行起整体扣留（零输出），闭栏行落地后整块放行（预算
 *        内）；超预算块按行铺开，下批 fenceStateAt 恢复块内续放；
 *     b. 表格：表头+分隔行齐备后整体放行，其后每条完整表行逐行放行（半行不
 *        放——表格文本重排闪烁；预算不足整行等下批）；
 *     c. 纯文字行（无活动标记）：完整行整行放行；未完行增量放行至快照尾或
 *        预算耗尽——中点截断只落在纯文字上（字面=最终，安全）；
 *     d. 含活动标记的行：整行扣留等闭合（空行毕业/完结 flush）。
 *
 * 不变量：
 *  - 结果单调不回退（>= alreadyReleased）——放行流只增不减；
 *  - 放行边界三类：行边界、纯文字字面边界（无重释义）、块边界；
 *  - 扣留内容去路恒两条：毕业或完结 EOF 全量 flush——一字不丢；
 *  - 行续段（floor 落在行中）无表头/围栏语义——表头与开/闭栏判定一律要求
 *    真实行首，防增量截断后的续段被误判成块构造。
 *  - setext 升格（"---"/"===" 紧随文字行）为已知理论缺口（旧闸同样存在），
 *    模型输出以 ATX 标题为主，接受。
 */
internal object SafePrefixGate {

    /**
     * 活动标记字符集：行内出现即差终止符（未闭合内联构造，未来行可闭合回溯
     * 重释义已上屏内容）。☐/☑/✅（#437 阶段 C）：normalizeTaskListMarkers 完结
     * 变换字符——流中字面放行会在完结时替换为 GFM 任务列表，扣留等空行闭合。
     */
        private const val ACTIVE_MARKERS = "*_~#>|[]!`☐☑✅"

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
        var atLineStart = true
        while (i < snapshot.length) {
            if (atLineStart && isBlankLineAt(snapshot, i)) {
                i = skipBlankLines(snapshot, i)
                cand = i
            } else {
                atLineStart = snapshot[i] == '\n'
                i++
            }
        }

        // —— 第二级：块级行扫描 + 纯文字增量直出（2026-09-26 二次重写）。
        var allowed = cand
        var j = cand
        var inFence = fenceStateAt(snapshot, cand)
        while (j < snapshot.length) {
            val nl = snapshot.indexOf('\n', j)
            val complete = nl >= 0
            val lineEnd = if (complete) nl else snapshot.length
            val line = snapshot.substring(j, lineEnd)
            val lineStartReal = j == 0 || snapshot[j - 1] == '\n'
            val budgetLeft = MAX_RELEASE_PER_BATCH - (allowed - floor)
            if (budgetLeft <= 0) break
            when {
                inFence -> {
                    // 块内部（上批预算截断的续放）：代码文本字面稳定，完整行整行
                    // 放、未完行增量放；闭栏行只整行放（半行闭栏会误判块态）。
                    if (complete && lineStartReal && isFenceClose(line)) {
                        if (nl + 1 - allowed > budgetLeft) break
                        allowed = nl + 1
                        j = nl + 1
                        inFence = false
                    } else {
                        val want = if (complete) nl + 1 else lineEnd
                        allowed = if (want - allowed > budgetLeft) allowed + budgetLeft else want
                        j = allowed
                        if (j >= snapshot.length) break
                    }
                }
                lineStartReal && isFenceOpen(line) != null -> {
                    // 未闭合围栏：零输出；闭合后整块放行（预算内）或按行铺开
                    val close = findFenceClose(snapshot, lineEnd + 1, isFenceOpen(line)!!)
                    if (close < 0) break
                    if (close - allowed <= budgetLeft) {
                        allowed = close
                        j = close
                    } else {
                        val cut = allowed + budgetLeft
                        val snap = snapshot.lastIndexOf('\n', cut - 1)
                        allowed = if (snap + 1 > allowed) snap + 1 else cut
                        j = allowed
                        inFence = true // 预算截断：下批 fenceStateAt 续放
                    }
                }
                // #441 稳态粒度：表格正文行跨批续放——上批已放内容以表格族行收尾时，
                // 本批正文行直接整行放行（原逻辑正文行被 isTableHeaderRow 误判为新表头
                // → 等不存在的分隔行 → 扣留到 EOF = 「表头先出、正文整块最后出」根因）。
                lineStartReal && complete && isTableRowLine(line) &&
                    prevReleasedLineIsTableFamily(snapshot, allowed) -> {
                    if (nl + 1 - allowed > budgetLeft) break
                    allowed = nl + 1
                    j = nl + 1
                }
                lineStartReal && complete && isTableHeaderRow(line) -> {
                    val sepNl = snapshot.indexOf('\n', nl + 1)
                    val sep = if (sepNl < 0) "" else snapshot.substring(nl + 1, sepNl)
                    if (!(sepNl >= 0 && isTableSeparatorRow(sep))) break // 表未成形：扣留
                    if (sepNl + 1 - allowed > budgetLeft) break
                    allowed = sepNl + 1
                    var k = sepNl + 1
                    while (k < snapshot.length) {
                        val rNl = snapshot.indexOf('\n', k)
                        if (rNl < 0) break
                        if (!isTableRowLine(snapshot.substring(k, rNl))) break
                        if (rNl + 1 - allowed > budgetLeft) break
                        allowed = rNl + 1
                        k = rNl + 1
                    }
                    j = k
                }
                !lineHasActiveMarker(line) -> {
                    // 纯文字（完整或未完）：字面=最终——整行/增量直出
                    val want = if (complete) nl + 1 else lineEnd
                    allowed = if (want - allowed > budgetLeft) allowed + budgetLeft else want
                    j = allowed
                    if (j >= snapshot.length) break
                }
                // #441 稳态粒度：'* ' 无序列表项——列表语义行级定案（后续行不会重释义
                // 本行块类型），完整行整行放行（半行扣留：续接内容未定）。'*' 后非空格
                // （强调构造开头）不进本分支，维持扣留。
                lineStartReal && complete && isStarBulletItemLine(line) -> {
                    if (nl + 1 - allowed > budgetLeft) break
                    allowed = nl + 1
                    j = nl + 1
                }
                else -> break // 含活动标记的行：整行扣留等闭合（毕业/EOF flush）
            }
        }
        return maxOf(floor, allowed)
    }

    // ===== 块级判定辅助（2026-09-26 行扫描二次重写） =====

    /** #441：已放行内容以表格族行（表头/分隔/正文）收尾——表格续放判据。 */
    private fun prevReleasedLineIsTableFamily(snapshot: String, allowed: Int): Boolean {
        if (allowed <= 0) return false
        var lineStart = snapshot.lastIndexOf('\n', allowed - 1) + 1
        var lineEnd = allowed
        // 跳过 allowed 位置的假行尾：上一真实行 = [lineStart, 上一个\n]
        val prevNl = snapshot.lastIndexOf('\n', allowed - 1)
        if (prevNl < 0) { lineStart = 0; lineEnd = allowed } 
        else { lineStart = snapshot.lastIndexOf('\n', prevNl - 1) + 1; lineEnd = prevNl }
        if (lineEnd <= lineStart) return false
        val prevLine = snapshot.substring(lineStart, lineEnd)
        return isTableHeaderRow(prevLine) || isTableSeparatorRow(prevLine) || isTableRowLine(prevLine)
    }

    /** #441：'* ' 无序列表项行（≤3 缩进 + '*' + 空格或行尾）。 */
    private fun isStarBulletItemLine(line: String): Boolean {
        var i = 0
        var indent = 0
        while (i < line.length && indent < 4 && (line[i] == ' ' || line[i] == '\t')) { i++; indent++ }
        if (i >= line.length || line[i] != '*') return false
        val next = i + 1
        return next >= line.length || line[next] == ' ' || line[next] == '\t'
    }

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
     * 行含 pos 时读**整行**（开栏判定需完整行）；闭栏只在该行完整越过 pos 时
     * 生效（pos 在闭栏行中=仍在栏内——闭栏行经整行放行，floor 不会落在其中，
     * 此防御针对任意调用方）。每批 O(n)（n=快照长，48ms 批节奏下可忽略）。
     */
    private fun fenceStateAt(snapshot: String, pos: Int): Boolean {
        var inFence = false
        var openLen = 3
        var openChar = '`'
        var k = 0
        while (k < pos) {
            val nl = snapshot.indexOf('\n', k)
            val lineEnd = if (nl < 0) snapshot.length else nl
            val fullLine = snapshot.substring(k, lineEnd)
            val linePassed = nl >= 0 && nl + 1 <= pos
            if (inFence) {
                if (linePassed) {
                    val t = fullLine.trim(' ', '\t')
                    var n = 0
                    while (n < t.length && t[n] == openChar) n++
                    if (n >= openLen && n == t.length) inFence = false
                }
            } else {
                val open = isFenceOpen(fullLine)
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
     * 完结归一化 ensureBlankLineBeforeGfmTables 会在「文字行紧贴表头行」处补
     * 空行——此处在放行 delta 内做与归一化同判定的增量前移（注入后归一化幂等，
     * 流中与完结渲染一致）。
     *
     * [newReleased] 是快照坐标；[delta] 可能比快照区间多注入的换行——
     * state.content 与快照长度自此解耦（调用方从不比较二者）。
     * 2026-09-26：[alreadyReleased] 可能落在纯文字行中（增量直出）——delta 首
     * 行是行续段，无表头语义，注入判定跳过之。
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
        val firstAtLineStart = from == 0 || snapshot[from - 1] == '\n'
        var lineStart = 0
        var prevNonEmptyTextLine = false // 前行=非空且非 | 结尾（注入条件）
        var lineIdx = 0
        while (lineStart <= delta.length) {
            val nl = delta.indexOf('\n', lineStart)
            val lineEnd = if (nl < 0) delta.length else nl
            val line = delta.substring(lineStart, lineEnd)
            val atLineStart = lineIdx > 0 || firstAtLineStart
            if (atLineStart && isTableHeaderRow(line)) {
                val nextStart = if (nl < 0) -1 else nl + 1
                val nextNl = if (nextStart < 0) -1 else delta.indexOf('\n', nextStart)
                val nextEnd = if (nextNl < 0) delta.length else nextNl
                val sep = if (nextStart < 0) "" else delta.substring(nextStart, nextEnd)
                val prevIsText = if (lineIdx == 0) prevLineBeforeIsTextRow(snapshot, from) else prevNonEmptyTextLine
                if (isTableSeparatorRow(sep) && prevIsText) out.append('\n')
            }
            if (lineStart >= delta.length) break
            out.append(line)
            if (nl >= 0) out.append('\n')
            prevNonEmptyTextLine = line.isNotEmpty() && !line.endsWith("|")
            lineIdx++
            lineStart = lineEnd + 1
        }
        return out.toString()
    }

    /** 单批放行预算（字符）——纯文字直出的节奏上限与毕业/块铺开的单批量上限。 */
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
        val prevEnd = from - 1
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
