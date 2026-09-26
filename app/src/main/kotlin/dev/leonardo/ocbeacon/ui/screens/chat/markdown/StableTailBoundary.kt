package dev.leonardo.ocbeacon.ui.screens.chat.markdown

/**
 * R2 dual-container stable boundary: last blank-line-block end inside [0, released).
 * Content before it is graduated (settled) - migrates into stable prefix state;
 * after it is the active tail. No blank line = 0 (all tail).
 */
internal fun stableTailBoundary(snapshot: String, released: Int): Int {
    val limit = released.coerceIn(0, snapshot.length)
    var boundary = 0
    var i = 0
    while (i < limit) {
        if (snapshot[i] == NL) {
            var j = i
            while (j < limit && snapshot[j] == NL) j++
            if (j - i >= 2) boundary = j
            i = j
        } else {
            i++
        }
    }
    return boundary
}

private const val NL = '\n'
