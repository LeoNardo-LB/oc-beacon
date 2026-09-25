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

    /** 活动标记字符集：出现即差终止符（其后内容回退扣留等闭合）。 */
    private const val ACTIVE_MARKERS = "*_~#>|[]!`"

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
