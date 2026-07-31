package dev.leonardo.ocbeacon.ui.screens.viewer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

object RenderHtmlBuilder {

    private val jsonPretty = Json { prettyPrint = true; prettyPrintIndent = "  " }

    fun build(fileType: FileType, content: String, isDark: Boolean, bgHex: String, fgHex: String): String {
        val headerBg = if (isDark) "#2a2a2a" else "#f0f0f0"
        val borderColor = if (isDark) "#444" else "#ccc"

        val bodyHtml = when (fileType) {
            FileType.CSV -> buildCsvTable(content, borderColor, headerBg)
            FileType.JSON -> buildJsonPre(content)
            FileType.SVG -> content.ifBlank { "<p style=\"color:#888;text-align:center\">Empty SVG</p>" }
            else -> "<pre>${escapeHtml(content)}</pre>"
        }

        return """
        <!DOCTYPE html>
        <html>
        <head>
        <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=5">
        <style>
            body { margin:0; padding:12px 16px; background:$bgHex; color:$fgHex; font-family:monospace; font-size:14px; line-height:1.5; }
            table { border-collapse:collapse; width:100%; }
            th, td { border:1px solid $borderColor; padding:6px 10px; text-align:left; }
            th { background:$headerBg; font-weight:bold; }
            pre { white-space:pre-wrap; word-wrap:break-word; margin:0; }
        </style>
        </head>
        <body>
        $bodyHtml
        </body>
        </html>
        """.trimIndent()
    }

    private fun buildCsvTable(content: String, borderColor: String, headerBg: String): String {
        if (content.isBlank()) return "<table><tbody></tbody></table>"
        val delimiter = if (content.contains('\t')) '\t' else ','
        val rows = parseCsvLines(content, delimiter)
        if (rows.isEmpty()) return "<table><tbody></tbody></table>"

        val sb = StringBuilder()
        sb.append("<table>")
        rows.forEachIndexed { index, row ->
            val tag = if (index == 0) "th" else "td"
            sb.append("<tr>")
            row.forEach { cell ->
                sb.append("<$tag>").append(escapeHtml(cell)).append("</$tag>")
            }
            sb.append("</tr>")
        }
        sb.append("</table>")
        return sb.toString()
    }

    private fun parseCsvLines(content: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val currentField = StringBuilder()
        val currentRow = mutableListOf<String>()
        var inQuotes = false
        var i = 0
        while (i < content.length) {
            val c = content[i]
            when {
                inQuotes -> {
                    if (c == '"') {
                        if (i + 1 < content.length && content[i + 1] == '"') {
                            currentField.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        currentField.append(c)
                    }
                }
                c == '"' -> { inQuotes = true }
                c == delimiter -> {
                    currentRow.add(currentField.toString())
                    currentField.clear()
                }
                c == '\n' -> {
                    currentRow.add(currentField.toString())
                    currentField.clear()
                    rows.add(currentRow.toList())
                    currentRow.clear()
                }
                c == '\r' -> { }
                else -> currentField.append(c)
            }
            i++
        }
        if (currentField.isNotEmpty() || currentRow.isNotEmpty()) {
            currentRow.add(currentField.toString())
            rows.add(currentRow.toList())
        }
        return rows
    }

    private fun buildJsonPre(content: String): String {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return "<pre>{}</pre>"
        return try {
            val element: JsonElement = Json.parseToJsonElement(trimmed)
            val pretty = jsonPretty.encodeToString(JsonElement.serializer(), element)
            val highlighted = pretty
                .replace(Regex("""(".*?")(\s*:)""")) { match ->
                    "<span style=\"color:#7ec699\">${match.groupValues[1]}</span>${match.groupValues[2]}"
                }
                .replace(Regex(""":\s*(".*?")""")) { match ->
                    ": <span style=\"color:#f9c859\">${match.groupValues[1]}</span>"
                }
            "<pre>$highlighted</pre>"
        } catch (e: Exception) {
            "<p style=\"color:#e57373\">Invalid JSON: ${escapeHtml(e.message ?: "parse error")}</p><pre>${escapeHtml(trimmed)}</pre>"
        }
    }

    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
}
