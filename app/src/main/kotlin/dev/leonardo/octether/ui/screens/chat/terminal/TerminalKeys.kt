package dev.leonardo.octether.ui.screens.chat.terminal

internal data class FnBindingResult(
    val output: String,
    val showVolumeUi: Boolean = false,
    val toggleKeyboard: Boolean = false,
)

internal fun applyTerminalModifiers(input: String, ctrl: Boolean, alt: Boolean): String {
    if (input.isEmpty()) return input
    var out = input
    if (ctrl) {
        out = out.map { ch -> ctrlTransform(ch) }.joinToString("")
    }
    if (alt) {
        out = "\u001B$out"
    }
    return out
}

internal fun applyTermuxFnBindings(input: String, cursorApp: Boolean): FnBindingResult {
    if (input.isEmpty()) return FnBindingResult(output = "")

    val up = if (cursorApp) "\u001BOA" else "\u001B[A"
    val down = if (cursorApp) "\u001BOB" else "\u001B[B"
    val right = if (cursorApp) "\u001BOC" else "\u001B[C"
    val left = if (cursorApp) "\u001BOD" else "\u001B[D"

    val out = StringBuilder()
    var showVolumeUi = false
    var toggleKeyboard = false
    for (ch in input) {
        when (ch.lowercaseChar()) {
            'w' -> out.append(up)
            'a' -> out.append(left)
            's' -> out.append(down)
            'd' -> out.append(right)

            'p' -> out.append("\u001B[5~")
            'n' -> out.append("\u001B[6~")

            't' -> out.append('\t')
            'i' -> out.append("\u001B[2~")
            'h' -> out.append('~')
            'u' -> out.append('_')
            'l' -> out.append('|')

            '1' -> out.append("\u001BOP")
            '2' -> out.append("\u001BOQ")
            '3' -> out.append("\u001BOR")
            '4' -> out.append("\u001BOS")
            '5' -> out.append("\u001B[15~")
            '6' -> out.append("\u001B[17~")
            '7' -> out.append("\u001B[18~")
            '8' -> out.append("\u001B[19~")
            '9' -> out.append("\u001B[20~")
            '0' -> out.append("\u001B[21~")

            'e' -> out.append('\u001B')
            '.' -> out.append(28.toChar()) // Ctrl+\

            'b', 'f', 'x' -> {
                out.append('\u001B')
                out.append(ch.lowercaseChar())
            }

            // Termux also handles FN+v (volume UI) and FN+q/k (toggle toolbar),
            // which are app-specific actions. We consume them with no terminal output.
            'v' -> showVolumeUi = true
            'q', 'k' -> toggleKeyboard = true

            else -> Unit
        }
    }
    return FnBindingResult(
        output = out.toString(),
        showVolumeUi = showVolumeUi,
        toggleKeyboard = toggleKeyboard,
    )
}

internal fun ctrlTransform(ch: Char): Char {
    return when {
        ch in 'a'..'z' -> (ch.code - 96).toChar()
        ch in 'A'..'Z' -> (ch.code - 64).toChar()
        ch == ' ' -> 0.toChar()
        ch == '[' -> 27.toChar()
        ch == '\\' -> 28.toChar()
        ch == ']' -> 29.toChar()
        ch == '^' -> 30.toChar()
        ch == '_' -> 31.toChar()
        else -> ch
    }
}
