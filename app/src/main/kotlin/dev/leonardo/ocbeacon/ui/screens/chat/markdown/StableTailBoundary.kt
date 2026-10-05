package dev.leonardo.ocbeacon.ui.screens.chat.markdown

/**
 * R2 dual-container stable boundary: last blank-line-block end inside [0, released).
 * Content before it is graduated (settled) - migrates into stable prefix state;
 * after it is the active tail. No blank line = 0 (all tail).
 *
 * #516 围栏原子性（2026-10-05 真机定罪）：代码围栏（``` / ~~~）内部的空行
 * **不是**稳定性边界——边界切进围栏会把一段代码分属冻结块/尾块两侧、各自
 * 独立解析渲染 = 一整块代码碎成多段（163 行单围栏 34 个内空行被逐一切碎）。
 * 围栏必须整体驻留：闭栏行之后的首个空行 run 才恢复边界资格。闭栏判定与
 * CommonMark 近似：开栏字符同型（` vs ~ 互不为对方的内容所闭）+ ≥3 标记。
 */
internal fun stableTailBoundary(snapshot: String, released: Int): Int {
    val limit = released.coerceIn(0, snapshot.length)
    var boundary = 0
    var i = 0
    var fenceChar: Char? = null // 非空=围栏开启（记录开栏字符；同字符标记行闭栏）
    while (i < limit) {
        if (i == 0 || snapshot[i - 1] == NL) {
            fenceMarkerAt(snapshot, i, limit)?.let { marker ->
                fenceChar = if (fenceChar == marker) null
                else if (fenceChar == null) marker
                else fenceChar // 异型标记行是围栏内容，不翻转
            }
        }
        if (snapshot[i] == NL) {
            var j = i
            while (j < limit && snapshot[j] == NL) j++
            if (j - i >= 2 && fenceChar == null) boundary = j
            i = j
        } else {
            i++
        }
    }
    return boundary
}

/**
 * [i] 位于行首（调用方保证）时的围栏标记行判定：≤3 空格缩进 + ≥3 个连续
 * ` 或 ~。返回标记字符（开/闭同型判定用），非标记行返回 null。
 * [packBlocks]（StreamingGraduationPlan）与 [stableTailBoundary] 共用——
 * 两层切点语义单一真相源。
 */
internal fun fenceMarkerAt(snapshot: String, i: Int, limit: Int): Char? {
    var p = i
    var spaces = 0
    while (p < limit && snapshot[p] == ' ' && spaces < 3) { p++; spaces++ }
    if (p >= limit) return null
    val c = snapshot[p]
    if (c != '`' && c != '~') return null
    var len = 0
    while (p < limit && snapshot[p] == c) { p++; len++ }
    return if (len >= 3) c else null
}

private const val NL = '\n'
