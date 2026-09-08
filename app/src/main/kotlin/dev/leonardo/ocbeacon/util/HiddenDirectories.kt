package dev.leonardo.ocbeacon.util

/**
 * 纯函数：目录隐藏匹配（hidden-directories 过滤器）。
 *
 * 用于把临时/草稿目录（如 /tmp/opencode 下任意深度的子目录）从项目与会话目录列表中过滤掉。
 * 模式语义：
 *  - 空模式列表 → 什么都不隐藏（默认设置，零行为变化）
 *  - 空白行被忽略；'#' 开头的行视为注释（便于临时禁用单个模式）
 *  - 匹配前归一化：'\' → '/'，去除末尾 '/'（保留根 "/"）
 *  - '*' 匹配任意字符（含路径分隔符），'?' 匹配单个字符 → 前缀式隐藏：
 *    以 "/tmp" 为基准尾接通配星号时，同时隐藏 "/tmp" 本身与其下任意深度的路径
 *  - 大小写敏感（远程服务器路径以服务器 OS 为准）
 */
object HiddenDirectories {

    /** 归一化目录或模式：'\' → '/'，去除首尾空白与末尾 '/'（根 "/" 除外）。 */
    fun normalize(path: String): String {
        val replaced = path.replace('\\', '/').trim()
        return if (replaced.length > 1) replaced.trimEnd('/') else replaced
    }

    /** [directory] 是否命中 [patterns] 中任意一个 glob 模式。 */
    fun isHidden(directory: String, patterns: Collection<String>): Boolean {
        if (patterns.isEmpty()) return false
        val target = normalize(directory)
        if (target.isEmpty()) return false
        return patterns.any { pattern ->
            val p = normalize(pattern)
            p.isNotEmpty() && !p.startsWith("#") && globToRegex(p).matches(target)
        }
    }

    /** 解析多行文本（每行一个模式）为模式列表：去空行与注释行。 */
    fun parsePatterns(raw: String): List<String> = raw
        .lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }

    private fun globToRegex(glob: String): Regex {
        // 尾部「斜杠+星」特判：模式同时命中基准目录本身与其下任意深度路径。
        val base = if (glob.endsWith("/*")) glob.removeSuffix("/*") else glob
        val body = buildString {
            base.forEach { c ->
                when (c) {
                    '*' -> append(".*")
                    '?' -> append('.')
                    else -> append(Regex.escape(c.toString()))
                }
            }
        }
        return Regex(if (base != glob) "$body(/.*)?" else body)
    }
}
