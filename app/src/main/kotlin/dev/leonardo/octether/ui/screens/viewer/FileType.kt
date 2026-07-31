package dev.leonardo.octether.ui.screens.viewer

enum class FileType {
    TEXT,
    MARKDOWN,
    IMAGE,
    SVG,
    CSV,
    JSON,
    HTML,
    PDF;

    val supportsRender: Boolean get() = this != TEXT && this != JSON

    /** PDF 的源码模式对 base64 数据无意义，不显示切换按钮 */
    val supportsSourceView: Boolean get() = this != PDF

    companion object {
        private val EXT_MAP: Map<String, FileType> = mapOf(
            "md" to MARKDOWN, "markdown" to MARKDOWN, "mdx" to MARKDOWN,
            "png" to IMAGE, "jpg" to IMAGE, "jpeg" to IMAGE,
            "gif" to IMAGE, "webp" to IMAGE, "bmp" to IMAGE,
            "svg" to SVG,
            "csv" to CSV, "tsv" to CSV,
            "json" to JSON,
            "html" to HTML, "htm" to HTML,
            "pdf" to PDF
        )

        fun fromExtension(filePath: String): FileType =
            EXT_MAP[filePath.substringAfterLast('.', "").lowercase()] ?: TEXT
    }
}
