package dev.leonardo.ocbeacon.ui.screens.chat.markdown

/**
 * #437 两级安全放行闸（spec §2，纯函数，零库依赖）。
 *
 * 使命：把交给 [com.mikepenz.markdown.model.StreamingMarkdownState.append] 的
 * 文本流变成「定案内容」——放行前缀内不含差终止符的构造，从源头消除
 * L4 不稳定尾回溯重释义（跳变主源）。库内 stable/unstable 分裂退居第二道
 * 防线：放行内容里的不稳定尾只能是纯文字（其字面临时排版 = 最终排版）。
 *
 * 放行规则（对快照 S、已放行长度 floor）：
 *  1. **空行毕业**：开放区中最后一个空行块之前的全部内容已定案（段落终结
 *     符落地，标记段的最终解释随之确定）→ 无条件放行至空行块后；
 *  2. **最后开放段**：
 *     a. 段内首个活动标记（`*_~#>|[]!-`` 反引号，或行首有序列表起始
 *        `数字+[.)]`）之前的纯文字区；出现标记 → 其后全部扣留等闭合；
 *     b. 纯文字区放行至**最后一个换行边界**；该边界若等于快照末尾
 *        （未来未知，末行有 setext 升格风险）→ 退至倒数第二个换行边界
 *        （扣住最后一行）。
 *
 * 不变量：
 *  - 结果单调不回退（>= alreadyReleased）——放行流只增不减，与 #435 高度
 *    引擎「锚即意图」配对天然兼容（单调量子增长）；
 *  - 扣留内容去路恒两条：毕业（空行/换行边界推进）或完结 EOF 全量 flush
 *    （preParsedState 分支接管整串渲染）——一字不丢；
 *  - 放行边界必满足 snapshot[b-1]=='\n' 或 b==段起点：行中间的纯文字
 *    前缀不放（未来同行出现标记字符会重释义已上屏内容）。
 */
internal object SafePrefixGate {

    /**
     * 活动标记字符集：出现即差终止符（其后内容回退扣留等闭合）。
     * ☐/☑/✅（#437 阶段 C）：normalizeTaskListMarkers 完结变换字符——流中
     * 字面放行会在完结时替换为 GFM 任务列表（重排），扣留等空行闭合。
     */
    private const val ACTIVE_MARKERS = "*_~#>|[]!`\u2610\u2611\u2705"

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
        // 行首跟踪必不可少：换行符本身落在行尾，若在行中位置判空行会把
        // 行尾 \n 误当空行行首、把 cand 推到错误位置（放行过多）。
        var cand = floor
        var i = floor
        var atLineStart = true // floor（上轮放行边界）恒为行首：换行后或空行后
        while (i < snapshot.length) {
            if (atLineStart && isBlankLineAt(snapshot, i)) {
                i = skipBlankLines(snapshot, i)
                cand = i
                // skipBlankLines 返回处必为行首（或 len）
            } else {
                atLineStart = snapshot[i] == '\n'
                i++
            }
        }

        // —— 第二级 a：最后开放段 [cand, end) 内首个活动标记
        var textLimit = snapshot.length
        var lineStart = true
        var j = cand
        while (j < snapshot.length) {
            val c = snapshot[j]
            if (lineStart && isOrderedListStart(snapshot, j)) { textLimit = j; break }
            if (ACTIVE_MARKERS.indexOf(c) >= 0) { textLimit = j; break }
            // $$（transformMathFallback 完结变换）——单 $ 是普通文字放行
            if (c == '$' && j + 1 < snapshot.length && snapshot[j + 1] == '$') { textLimit = j; break }
            lineStart = c == '\n'
            j++
        }

        // —— 第二级 b：纯文字区 [cand, textLimit) 放行至最后换行边界
        var lastNl = -1
        var secondNl = -1
        for (k in cand until textLimit) {
            if (snapshot[k] == '\n') { secondNl = lastNl; lastNl = k }
        }
        val boundary = when {
            // 区内无换行：未完行不放（行中间前缀有重释义风险）
            lastNl < 0 -> cand
            // 主规则：最后换行后已有快照内容占位——未来前缀不变，末行安全
            lastNl + 1 < snapshot.length -> lastNl + 1
            // 末换行即快照末尾：末行 setext 风险（未来可能来 "---"），扣住退一格
            secondNl >= 0 -> secondNl + 1
            // 仅一个换行且在快照末尾：整段扣
            else -> cand
        }
        return maxOf(floor, boundary)
    }

    /** 放行决策：新放行长度（快照坐标）+ 实际交给 append 的 delta 文本。 */
    internal class ReleaseDecision(val newReleased: Int, val delta: String)

    /**
     * #437 阶段 C：放行决策（含表格粘边空行注入）。
     *
     * 完结归一化 ensureBlankLineBeforeGfmTables 会在「文字行紧贴表头行」处
     * 补空行——若流中把粘连内容按原样放行，完结帧该内容从字面段落重排为
     * 表格（已放行内容收缩，引擎不配对收缩=跳变）。此处在放行 delta 内做
     * 与归一化同判定的增量前移：表头行（| 包裹）+分隔行（|[-:\s|]+|）且前
     * 行非空非 | 结尾 → 表头前插 \n。注入后归一化幂等（不再命中），流中
     * 与完结渲染一致。
     *
     * [newReleased] 是快照坐标；[delta] 可能比快照区间多注入的 \n——
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
        val prevEnd = from - 1 // from 前必是 \n（放行边界恒在换行后）或行中？
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
