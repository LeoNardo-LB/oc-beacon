package dev.leonardo.ocbeacon.ui.theme

import androidx.compose.ui.graphics.Color

// ── 状态指示色 ──────────────────────────────────────────
// 用于 ServerCard 连接指示器和 McpServerRow 状态点。
val StatusConnected = Color(0xFF4CAF50) // 绿色
val StatusFailed = Color(0xFFF44336)    // 红色
val StatusWarning = Color(0xFFFFC107)   // 琥珀色 — 需要认证 / 等待中

// ── Diff 指示色 ────────────────────────────────────────────
val DiffAdded = Color(0xFF4CAF50)     // 绿色 — 新增行
val DiffRemoved = Color(0xFFE53935)   // 红色 — 删除行

// ── Agent 身份色 ────────────────────────────────────────────
// 匹配 TUI 的 opencode 主题（local.tsx 颜色数组）。刻意
// 不绑定 colorScheme：每个 agent 必须在会话、主题、浅/深模式之间
// 都能通过颜色识别。
val AgentSecondary = Color(0xFF5C9CF5) // build（蓝色）
val AgentAccent = Color(0xFF9D7CD8)    // plan（紫色）
val AgentSuccess = Color(0xFF7FD88F)   // 绿色
val AgentWarning = Color(0xFFF5A742)   // 橙色
val AgentPrimary = Color(0xFFFAB283)   // 桃色
val AgentError = Color(0xFFE06C75)     // 红色
val AgentInfo = Color(0xFF56B6C2)      // 青色

// ── 徽章色 ─────────────────────────────────────────────
val QueuedBadgeColor = Color(0xFFFFD700)      // 金色背景
val QueuedBadgeTextColor = Color(0xFF1A1A1A)  // 金色上的深色文本
