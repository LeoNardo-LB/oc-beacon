package dev.leonardo.ocbeacon.domain.model

/**
 * 将 markdown `[text](url)` 链接中的 URL 字符串分类为三种类型之一。
 * 纯 Kotlin 实现——无 Android 依赖。
 */
sealed interface LinkTarget {
    /** Web URL：http://、https://、ftp://、mailto: */
    data class Web(val url: String) : LinkTarget

    /** 相对于会话工作目录的路径：src/Foo.kt、../docs/api.md */
    data class RelativePath(val path: String) : LinkTarget

    /** 绝对路径：/home/user/Foo.kt 或 C:\Users\Foo.kt */
    data class AbsolutePath(val path: String) : LinkTarget
}

object LinkClassifier {
    private val windowsAbsoluteRegex = Regex("[A-Za-z]:[\\\\/].*")

    fun classify(url: String): LinkTarget = when {
        url.startsWith("http://", ignoreCase = true) ||
        url.startsWith("https://", ignoreCase = true) ||
        url.startsWith("ftp://", ignoreCase = true) ||
        url.startsWith("mailto:", ignoreCase = true) -> LinkTarget.Web(url)

        url.startsWith("/") -> LinkTarget.AbsolutePath(url)

        url.startsWith("file://", ignoreCase = true) -> {
            // file:///home/user/foo → AbsolutePath("/home/user/foo")
            val afterScheme = url.substringAfter("file://")
            LinkTarget.AbsolutePath(afterScheme)
        }
        windowsAbsoluteRegex.matches(url) -> LinkTarget.AbsolutePath(url)

        else -> LinkTarget.RelativePath(url)
    }

    /** 已知的文件扩展名，用于行内代码中的文件路径检测。 */
    val FILE_EXTENSIONS: Set<String> = setOf(
        // 编程语言
        "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "mjs", "cjs",
        "go", "rs", "c", "cpp", "cc", "cxx", "h", "hpp", "hxx", "cs", "rb",
        "php", "swift", "m", "mm", "scala", "clj", "cljs", "ex", "exs",
        "erl", "hs", "lua", "pl", "pm", "r", "dart", "vue", "svelte",
        // JVM / 构建
        "gradle", "groovy", "xml", "properties", "toml", "sbt",
        // Web / 配置
        "html", "htm", "css", "scss", "sass", "less", "json", "json5",
        "yaml", "yml", "ini", "cfg", "conf", "env",
        // 文档
        "md", "mdx", "rst", "txt", "adoc", "tex", "pdf",
        // 数据
        "csv", "tsv", "sql", "db", "sqlite",
        // Shell
        "sh", "bash", "zsh", "fish", "bat", "ps1", "cmd",
        // 其他
        "lock", "log", "diff", "patch",
    )

    /** 无扩展名但可被识别为可点击文件路径的文件名。 */
    val SPECIAL_FILENAMES: Set<String> = setOf(
        "Makefile", "makefile", "GNUmakefile",
        "Dockerfile", "Containerfile",
        "LICENSE", "LICENSE.md", "LICENSE.txt",
        "README", "CHANGELOG", "AUTHORS", "CONTRIBUTING",
        ".gitignore", ".gitattributes", ".editorconfig",
        ".env", ".env.local", ".env.production",
        ".npmrc", ".nvmrc", ".ruby-version",
        "Jenkinsfile", "Vagrantfile", "Gemfile", "Rakefile",
        "WORKSPACE", "BUILD", "BUILD.bazel",
    )

    private val fileExtensionRegex = Regex("\\.([A-Za-z0-9]+)$")

    /**
     * 启发式判断：此行内代码内容是否看起来像文件路径或文件名？
     *
     * - 包含路径分隔符（/ 或 \）→ true（包名使用 '.'，不使用 '/'）
     * - 有扩展名 → 扩展名必须在 [FILE_EXTENSIONS] 中
     * - 无扩展名 → 必须在 [SPECIAL_FILENAMES] 中
     */
    fun isLikelyFilePath(text: String): Boolean {
        if (text.contains('/') || text.contains('\\')) return true
        // 在扩展名正则之前先检查特殊文件名（包含隐藏的 .files）
        if (text in SPECIAL_FILENAMES || text.lowercase() in SPECIAL_FILENAMES) return true
        val extMatch = fileExtensionRegex.find(text)
        if (extMatch != null) {
            return extMatch.groupValues[1].lowercase() in FILE_EXTENSIONS
        }
        return false
    }
}
