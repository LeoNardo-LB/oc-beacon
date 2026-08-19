package dev.leonardo.ocbeacon.ui.screens.sessions.components

/**
 * 文件浏览器中可浏览目录路径的值对象。
 *
 * 封装所有路径操作（父目录、子目录、显示、分隔符逻辑），
 * 调用方不必再处理原始字符串切片或分隔符猜测。
 *
 * ## 设计决策
 * - 不使用 `java.io.File` — 此代码运行在 Android（Linux 内核）上，
 *   `File("D:\\path")` 不会把 `\` 当作分隔符。
 * - 路径操作通过字符串处理完成，使用服务器自身的
 *   分隔符约定（从路径推断）。
 * - 两个特殊根：[unixRoot]（`/`）和 [windowsDrivesRoot]（虚拟盘符选择器）。
 * - `isWindows` 在构造时从原始路径字符串一次性推断。
 * - 所有操作都返回新的 [DirectoryPath] 实例 — 这是一个值类型。
 */
@ConsistentCopyVisibility
data class DirectoryPath private constructor(
    /** 发送给服务器 API 的原始、规范化路径字符串。 */
    val rawPath: String,
    /** 此路径是否使用 Windows 约定（反斜杠分隔符、盘符）。 */
    val isWindows: Boolean,
) {

    /** 服务器为此路径使用的分隔符字符。 */
    private val sep: Char get() = if (isWindows) '\\' else '/'

    // ── 查询 ──────────────────────────────────────────────────────

    /** 是否为虚拟 Windows 盘符选择器根。 */
    val isDrivesRoot: Boolean get() = this === windowsDrivesRoot || rawPath == DRIVES_ROOT_SENTINEL

    /** 是否为最顶层可导航层级（无法再向上）。 */
    val isRoot: Boolean
        get() = when {
            isDrivesRoot -> true
            // 盘符根（C:\、D:\）可以向上回到盘符列表，所以不是 root。
            else -> rawPath == "/"
        }

    /** 此路径是否为 Windows 盘符根，如 `C:\` 或 `D:\`。 */
    val isDriveRoot: Boolean
        get() = isWindows && rawPath.length <= 3 &&
                rawPath.matches(DRIVE_ROOT_REGEX)

    /** 显示友好的路径（若提供 home 前缀，则用 `~` 替换）。 */
    fun display(homeDir: String? = null): String {
        if (isDrivesRoot) return "Drives"
        if (homeDir.isNullOrBlank()) return rawPath
        return if (rawPath.startsWith(homeDir)) "~${rawPath.removePrefix(homeDir)}" else rawPath
    }

    // ── 导航 ───────────────────────────────────────────────────

    /**
     * 向上导航到父目录。
     * 若已处于最顶层则返回 `null`。
     *
     * Windows 行为：
     * - `D:\Users\Admin` → `D:\Users`
     * - `D:\Users` → `D:\`
     * - `D:\` → 盘符根
     *
     * Unix 行为：
     * - `/home/user` → `/home`
     * - `/home` → `/`
     * - `/` → null
     */
    fun parent(): DirectoryPath? {
        if (isRoot) return null
        if (isDriveRoot) return windowsDrivesRoot

        // 规范化：去除尾部分隔符（但保留盘符根的尾随 \）
        val normalized = rawPath.trimEnd(sep)
        val lastSep = normalized.lastIndexOf(sep)

        if (lastSep < 0) return null

        val parentStr = normalized.substring(0, lastSep)

        // Windows："D:"（剥离后父路径为空）→ 盘符根
        if (isWindows && parentStr.matches(BARE_DRIVE_REGEX)) {
            return forPath(parentStr + sep)
        }

        // Unix："/something" → 父路径为 "/"（根）
        if (!isWindows && parentStr.isEmpty()) {
            return unixRoot
        }

        return if (parentStr.isNotEmpty()) forPath(parentStr) else null
    }

    /**
     * 通过 [name] 导航进入子目录。
     * 使用此路径约定对应的正确分隔符进行拼接。
     */
    fun child(name: String): DirectoryPath {
        val base = rawPath.trimEnd(sep)
        return forPath("$base$sep$name")
    }

    // ── 标准重写 ───────────────────────────────────────────────────

    override fun toString(): String = rawPath

    // ── Companion（工厂） ──────────────────────────────────────────

    companion object {
        /** 虚拟 Windows 盘符选择器的哨兵值。绝不发送给服务器。 */
        private const val DRIVES_ROOT_SENTINEL = ":///drives"

        // #106-4：路径判定正则预编译（原 isDriveRoot/parent/forPath 每次调用现场编译）
        private val DRIVE_ROOT_REGEX = Regex("[A-Za-z]:[/\\\\]?")
        private val BARE_DRIVE_REGEX = Regex("[A-Za-z]:$")
        private val WINDOWS_PATH_HINT_REGEX = Regex("^[A-Za-z]:.*")

        /** 表示 Windows 盘符选择器页面的虚拟根。 */
        val windowsDrivesRoot: DirectoryPath = DirectoryPath(DRIVES_ROOT_SENTINEL, isWindows = true)

        /** Unix 文件系统根。 */
        val unixRoot: DirectoryPath = DirectoryPath("/", isWindows = false)

        /**
         * 从原始服务器路径字符串构造。
         * 根据是否包含 `\` 或盘符前缀推断 [isWindows]。
         */
        fun forPath(rawPath: String): DirectoryPath {
            val isWindows = rawPath.contains('\\') || rawPath.matches(WINDOWS_PATH_HINT_REGEX)
            return DirectoryPath(rawPath, isWindows)
        }
    }
}
