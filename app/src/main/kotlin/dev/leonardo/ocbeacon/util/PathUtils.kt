package dev.leonardo.ocbeacon.util

/**
 * 跨平台路径工具。
 *
 * 本应用是远程客户端——服务器可能运行在 Linux、Windows 或 macOS 上。
 * 来自服务器的路径字符串可能使用 `/`、`\` 或两者混用。
 * 这些辅助函数无需了解服务器的操作系统即可处理所有分隔符。
 */
object PathUtils {

    private val SEPARATORS = charArrayOf('/', '\\')

    /** 从路径中提取文件名，同时处理 / 和 \ 分隔符。 */
    fun fileName(path: String): String {
        val idx = path.lastIndexOfAny(SEPARATORS)
        return if (idx >= 0) path.substring(idx + 1) else path
    }

    /** 提取目录部分（最后一个分隔符之前的所有内容）。 */
    fun parentDir(path: String): String {
        val idx = path.lastIndexOfAny(SEPARATORS)
        return if (idx > 0) path.substring(0, idx) else ""
    }

    /**
     * 从 [path] 中去除 [prefix]，并规范化分隔符差异。
     * 返回 prefix 之后的路径部分，不带前导分隔符。
     * 若 prefix 不匹配则返回原始路径。
     */
    fun relativePath(path: String, prefix: String): String {
        if (prefix.isBlank()) return path
        // 先尝试精确前缀匹配
        if (path.startsWith(prefix)) {
            return path.removePrefix(prefix).trimStart(*SEPARATORS)
        }
        // 再尝试忽略分隔符差异进行匹配（例如 path 使用 \，prefix 使用 /）
        val normalizedPath = path.replace('\\', '/')
        val normalizedPrefix = prefix.replace('\\', '/')
        if (normalizedPath.startsWith(normalizedPrefix)) {
            return normalizedPath.removePrefix(normalizedPrefix).trimStart('/')
        }
        return path
    }

    /**
     * 用 `/` 分隔符拼接两段路径。
     * 处理 [base] 和 [relative] 两端的尾随/前导分隔符。
     * 若 [base] 为空白则原样返回 [relative]。
     */
    fun joinPath(base: String, relative: String): String {
        if (base.isBlank()) return relative
        if (relative.isBlank()) return base
        val normalizedBase = base.trimEnd(*SEPARATORS)
        val normalizedRelative = relative.trimStart(*SEPARATORS)
        return "$normalizedBase/$normalizedRelative"
    }
}
