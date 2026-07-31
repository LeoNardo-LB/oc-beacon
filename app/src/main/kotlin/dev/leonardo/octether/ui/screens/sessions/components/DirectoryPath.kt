package dev.leonardo.octether.ui.screens.sessions.components

/**
 * Value object representing a browsable directory path in the file browser.
 *
 * Encapsulates all path manipulation (parent, child, display, separator logic)
 * so callers never deal with raw string slicing or separator guessing.
 *
 * ## Design decisions
 * - Does NOT use `java.io.File` — this runs on Android (Linux kernel) where
 *   `File("D:\\path")` doesn't treat `\` as a separator.
 * - Path operations are done via string manipulation using the server's own
 *   separator convention (inferred from the path).
 * - Two special roots: [unixRoot] (`/`) and [windowsDrivesRoot] (virtual drive-picker).
 * - `isWindows` is inferred once at construction from the raw path string.
 * - All operations produce new [DirectoryPath] instances — this is a value type.
 */
@ConsistentCopyVisibility
data class DirectoryPath private constructor(
    /** The raw, normalized path string sent to the server API. */
    val rawPath: String,
    /** Whether this path uses Windows conventions (backslash separator, drive letters). */
    val isWindows: Boolean,
) {

    /** The separator character used by the server for this path. */
    private val sep: Char get() = if (isWindows) '\\' else '/'

    // ── Queries ──────────────────────────────────────────────────────

    /** Whether this is the virtual Windows drive-picker root. */
    val isDrivesRoot: Boolean get() = this === windowsDrivesRoot || rawPath == DRIVES_ROOT_SENTINEL

    /** Whether this is the topmost navigable level (can't go up further). */
    val isRoot: Boolean
        get() = when {
            isDrivesRoot -> true
            // Drive roots (C:\, D:\) can go up to the drives list, so they are NOT root.
            else -> rawPath == "/"
        }

    /** Whether this path is a Windows drive root like `C:\` or `D:\`. */
    val isDriveRoot: Boolean
        get() = isWindows && rawPath.length <= 3 &&
                rawPath.matches(Regex("[A-Za-z]:[/\\\\]?"))

    /** The display-friendly path (replaces home prefix with `~` if provided). */
    fun display(homeDir: String? = null): String {
        if (isDrivesRoot) return "Drives"
        if (homeDir.isNullOrBlank()) return rawPath
        return if (rawPath.startsWith(homeDir)) "~${rawPath.removePrefix(homeDir)}" else rawPath
    }

    // ── Navigation ───────────────────────────────────────────────────

    /**
     * Navigate up to the parent directory.
     * Returns `null` if already at the topmost level.
     *
     * Windows behavior:
     * - `D:\Users\Admin` → `D:\Users`
     * - `D:\Users` → `D:\`
     * - `D:\` → drives root
     *
     * Unix behavior:
     * - `/home/user` → `/home`
     * - `/home` → `/`
     * - `/` → null
     */
    fun parent(): DirectoryPath? {
        if (isRoot) return null
        if (isDriveRoot) return windowsDrivesRoot

        // Normalize: strip trailing separators (but keep the drive root's trailing \)
        val normalized = rawPath.trimEnd(sep)
        val lastSep = normalized.lastIndexOf(sep)

        if (lastSep < 0) return null

        val parentStr = normalized.substring(0, lastSep)

        // Windows: "D:" (empty parent after stripping) → drive root
        if (isWindows && parentStr.matches(Regex("[A-Za-z]:$"))) {
            return forPath(parentStr + sep)
        }

        // Unix: "/something" → parent is "/" (root)
        if (!isWindows && parentStr.isEmpty()) {
            return unixRoot
        }

        return if (parentStr.isNotEmpty()) forPath(parentStr) else null
    }

    /**
     * Navigate into a child directory by [name].
     * Joins using the correct separator for this path's convention.
     */
    fun child(name: String): DirectoryPath {
        val base = rawPath.trimEnd(sep)
        return forPath("$base$sep$name")
    }

    // ── Standard overrides ───────────────────────────────────────────

    override fun toString(): String = rawPath

    // ── Companion (factory) ──────────────────────────────────────────

    companion object {
        /** Sentinel for the virtual Windows drive-picker. Never sent to the server. */
        private const val DRIVES_ROOT_SENTINEL = ":///drives"

        /** Virtual root representing the Windows drive-picker page. */
        val windowsDrivesRoot: DirectoryPath = DirectoryPath(DRIVES_ROOT_SENTINEL, isWindows = true)

        /** Unix filesystem root. */
        val unixRoot: DirectoryPath = DirectoryPath("/", isWindows = false)

        /**
         * Construct from a raw server path string.
         * Infers [isWindows] from the presence of `\` or a drive-letter prefix.
         */
        fun forPath(rawPath: String): DirectoryPath {
            val isWindows = rawPath.contains('\\') || rawPath.matches(Regex("^[A-Za-z]:.*"))
            return DirectoryPath(rawPath, isWindows)
        }
    }
}
