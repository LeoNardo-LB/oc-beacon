package dev.leonardo.octether.ui.theme

import androidx.compose.ui.graphics.Color

// ── Status indicator colors ──────────────────────────────────────────
// Used by ServerCard connection indicator and McpServerRow status dots.
val StatusConnected = Color(0xFF4CAF50) // Green
val StatusFailed = Color(0xFFF44336)    // Red
val StatusWarning = Color(0xFFFFC107)   // Amber — needs auth / pending

// ── Diff indicator colors ────────────────────────────────────────────
val DiffAdded = Color(0xFF4CAF50)     // Green — added lines
val DiffRemoved = Color(0xFFE53935)   // Red — removed lines

// ── Agent identity colors ────────────────────────────────────────────
// Match the TUI's opencode theme (local.tsx color array). Intentionally
// NOT tied to colorScheme: each agent must stay identifiable by color
// across sessions, themes, and light/dark mode.
val AgentSecondary = Color(0xFF5C9CF5) // build (blue)
val AgentAccent = Color(0xFF9D7CD8)    // plan (purple)
val AgentSuccess = Color(0xFF7FD88F)   // green
val AgentWarning = Color(0xFFF5A742)   // orange
val AgentPrimary = Color(0xFFFAB283)   // peach
val AgentError = Color(0xFFE06C75)     // red
val AgentInfo = Color(0xFF56B6C2)      // cyan

// ── Badge colors ─────────────────────────────────────────────────────
val QueuedBadgeColor = Color(0xFFFFD700)      // Gold background
val QueuedBadgeTextColor = Color(0xFF1A1A1A)  // Dark text on gold
