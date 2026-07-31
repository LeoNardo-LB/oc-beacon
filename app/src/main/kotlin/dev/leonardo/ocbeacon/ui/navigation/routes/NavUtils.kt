package dev.leonardo.ocbeacon.ui.navigation.routes

import java.net.URLDecoder

/**
 * Safely URL-decode a navigation parameter value.
 *
 * Navigation's StringType passes the raw (still-encoded) query parameter value
 * to [androidx.navigation.NavBackStackEntry.arguments]. We need to decode it
 * ourselves. However, [URLDecoder.decode] throws [IllegalArgumentException]
 * when encountering malformed percent sequences like `%NR` or `%25` (which can
 * appear in user passwords/paths). This wrapper falls back to the original
 * string in that case.
 */
internal fun safeDecodeParam(value: String): String = try {
    URLDecoder.decode(value, "UTF-8")
} catch (_: IllegalArgumentException) {
    // Malformed percent sequence (e.g. %NR) — return as-is to avoid crash.
    value
}
