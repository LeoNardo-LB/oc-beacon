package dev.leonardo.ocbeacon.util

/**
 * Cross-platform path utilities.
 *
 * This app is a remote client — the server may run on Linux, Windows, or macOS.
 * Path strings arriving from the server can use `/`, `\`, or both.
 * These helpers handle all separators without needing to know the server's OS.
 */
object PathUtils {

    private val SEPARATORS = charArrayOf('/', '\\')

    /** Extract the file name from a path, handling both / and \ separators. */
    fun fileName(path: String): String {
        val idx = path.lastIndexOfAny(SEPARATORS)
        return if (idx >= 0) path.substring(idx + 1) else path
    }

    /** Extract the directory portion (everything before the last separator). */
    fun parentDir(path: String): String {
        val idx = path.lastIndexOfAny(SEPARATORS)
        return if (idx > 0) path.substring(0, idx) else ""
    }

    /**
     * Strip [prefix] from [path], also normalizing separator differences.
     * Returns the portion of path after prefix, without a leading separator.
     * Returns the original path if prefix doesn't match.
     */
    fun relativePath(path: String, prefix: String): String {
        if (prefix.isBlank()) return path
        // Try exact prefix match first
        if (path.startsWith(prefix)) {
            return path.removePrefix(prefix).trimStart(*SEPARATORS)
        }
        // Try matching ignoring separator differences (e.g. path uses \, prefix uses /)
        val normalizedPath = path.replace('\\', '/')
        val normalizedPrefix = prefix.replace('\\', '/')
        if (normalizedPath.startsWith(normalizedPrefix)) {
            return normalizedPath.removePrefix(normalizedPrefix).trimStart('/')
        }
        return path
    }

    /**
     * Join two path segments with a `/` separator.
     * Handles trailing/leading separators on both [base] and [relative].
     * Returns [relative] unchanged if [base] is blank.
     */
    fun joinPath(base: String, relative: String): String {
        if (base.isBlank()) return relative
        if (relative.isBlank()) return base
        val normalizedBase = base.trimEnd(*SEPARATORS)
        val normalizedRelative = relative.trimStart(*SEPARATORS)
        return "$normalizedBase/$normalizedRelative"
    }
}
