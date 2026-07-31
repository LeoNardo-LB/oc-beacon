package dev.leonardo.ocbeacon.domain.model

/**
 * Classifies a URL string from a markdown `[text](url)` link into one of three types.
 * Pure Kotlin — no Android dependencies.
 */
sealed interface LinkTarget {
    /** Web URL: http://, https://, ftp://, mailto: */
    data class Web(val url: String) : LinkTarget

    /** Path relative to the session working directory: src/Foo.kt, ../docs/api.md */
    data class RelativePath(val path: String) : LinkTarget

    /** Absolute path: /home/user/Foo.kt or C:\Users\Foo.kt */
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

    /** Known file extensions for file-path detection in inline code. */
    val FILE_EXTENSIONS: Set<String> = setOf(
        // Languages
        "kt", "kts", "java", "py", "js", "jsx", "ts", "tsx", "mjs", "cjs",
        "go", "rs", "c", "cpp", "cc", "cxx", "h", "hpp", "hxx", "cs", "rb",
        "php", "swift", "m", "mm", "scala", "clj", "cljs", "ex", "exs",
        "erl", "hs", "lua", "pl", "pm", "r", "dart", "vue", "svelte",
        // JVM / Build
        "gradle", "groovy", "xml", "properties", "toml", "sbt",
        // Web / Config
        "html", "htm", "css", "scss", "sass", "less", "json", "json5",
        "yaml", "yml", "ini", "cfg", "conf", "env",
        // Docs
        "md", "mdx", "rst", "txt", "adoc", "tex", "pdf",
        // Data
        "csv", "tsv", "sql", "db", "sqlite",
        // Shell
        "sh", "bash", "zsh", "fish", "bat", "ps1", "cmd",
        // Other
        "lock", "log", "diff", "patch",
    )

    /** Extensionless filenames that are valid clickable file paths. */
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
     * Heuristic: does this inline code content look like a file path or filename?
     *
     * - Contains a path separator (/ or \) → true (package names use '.', not '/')
     * - Has an extension → extension must be in [FILE_EXTENSIONS]
     * - No extension → must be in [SPECIAL_FILENAMES]
     */
    fun isLikelyFilePath(text: String): Boolean {
        if (text.contains('/') || text.contains('\\')) return true
        // Check special filenames (including hidden .files) before extension regex
        if (text in SPECIAL_FILENAMES || text.lowercase() in SPECIAL_FILENAMES) return true
        val extMatch = fileExtensionRegex.find(text)
        if (extMatch != null) {
            return extMatch.groupValues[1].lowercase() in FILE_EXTENSIONS
        }
        return false
    }
}
