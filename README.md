# OC Tether

An enhanced fork of [crim50n/oc-remote](https://github.com/crim50n/oc-remote) — an unofficial Android client for [OpenCode](https://github.com/anomalyco/opencode) servers with a native Material 3 UI.

> **OC Tether is an independent community fork of OC Remote, not affiliated with, endorsed by, or sponsored by the original OC Remote project, its author (@crim50n), or the OpenCode team.**
>
> 本仓库是 [crim50n/oc-remote](https://github.com/crim50n/oc-remote) 的增强 fork。为与原版共存，已将 applicationId 从 `dev.minios.ocremote` 改为 `dev.leonardo.octether`，两者可在同一设备上同时安装。

## Relationship with Upstream

This fork diverges from upstream `v1.6.7` and has accumulated **1400+ commits** of new features, bug fixes, architecture refinements, and UI polish. It ships under the same MIT License, with full credit to the original author [@crim50n](https://github.com/crim50n).

| | Upstream | This Fork |
|---|----------|-----------|
| Application ID | `dev.minios.ocremote` | `dev.leonardo.octether` (+ `.dev` / `.beta` suffixes for parallel install) |
| compileSdk | 34 | 37 |
| targetSdk | 34 | 35 |
| Compose BOM | 2024.12.01 | 2026.05.01 |

Both apps can be installed **side-by-side** on the same device thanks to the distinct application ID. Version numbers follow [Semantic Versioning](AGENTS.md#version-management) — see `version.properties` for the current release.

## What's New in This Fork

### 🆕 Workspace Browser

A brand-new module for browsing and inspecting the remote project directly from your phone:

- **File Tree** — flattened, lazy-loaded directory tree with a "show ignored" filter
- **Git Changes Panel** — status badges (added / modified / deleted / untracked) for every changed file
- **Code Viewer** — syntax-highlighted source view with line numbers; lazy rendering handles large files
- **Diff Viewer** — unified diff with hunk navigation and color-coded added/removed lines
- **Open from Chat** — tool cards (Read / Edit / Write) can open referenced files straight in the viewer

### 🆕 Pluggable Tool Cards

A rewritten, extensible tool-call rendering system:

- **Registry-based** — `ToolCardResolver` registry makes adding new tool types trivial
- **Rich card types** — `ApplyPatch` (with inline diff preview), `WebSearch` (results list), `WebFetch` (URL + summary), `Glob` (match count + expandable file list), `Task`, and more
- **Interactive** — copy output, expand/collapse, and open referenced files from the card

### 🆕 Token & Context Analytics

- **Message metadata** — model name, duration, and token usage shown per assistant message
- **Token usage card** — total / cached / input / output breakdown
- **Context detail dialog** — per-category breakdown with cache hit-rate and cost metrics

### 🆕 Session List Improvements

- **Recent / History modes** — toggle between chronological recent sessions and full history
- **Pull-to-refresh** — manually refresh the session list
- **Sequential pending cards** — permission / question cards shown one at a time with a position indicator (1/N)
- **Retry tracking** — visible retry state with countdown
- **Session categories** — custom name/color/icon tags, per-session assignment, filter chips
- **Cross-server favorites** — star sessions across servers, unified favorites list with offline snapshots

### 🆕 Message-Level Status Indicators

User messages now carry delivery state, so you always know whether your message reached the server:

- **Optimistic send** — your message appears instantly instead of waiting for a server round-trip
- **Three-state badge** — Sending (spinner) → Sent (briefly) → Failed (tap to retry)
- **Self-healing sync** — if real-time updates stall, the app re-syncs message state automatically

### ⚡ SSE Streaming Overhaul

Hundreds of commits dedicated to making real-time streaming rock-solid:

- **Delta batching** (48 ms window) — eliminates jitter from rapid SSE deltas
- **Drift compensation** — viewport stays pinned during content growth
- **Scroll anchor lock** — prevents unwanted scroll jumps while streaming
- **Revert filter** — undo operations don't flash old content

### 🔔 Notification System

- **Unified content** — notifications surface the latest user message
- **Deduplication** — prevents permission / question notification flooding
- **Foreground suppression** — no notifications while viewing the active session
- **MessagingStyle** — richer task-complete notifications

### 🎨 Unified Theme Token System

A comprehensive design-token system replacing hardcoded values across the UI:

- **AlphaTokens** — 7 semantic opacity levels (FAINT / MUTED / MEDIUM / HIGH / AMOLED / …)
- **SpacingTokens** — 6 grid-based spacing constants (XS / SM / MD / LG / XL / XXL)
- **ShapeTokens & MotionTokens** — component shapes and animation durations
- **ButtonTokens** — unified button colors / borders / spacing
- **ListItemTokens** — three density levels for Material 3 `ListItem` content padding
- All AMOLED branches migrated from `Color.Black` to semantic tokens

### 🏗️ Architecture Hardening

- **Clean Architecture enforcement** — fixed domain → data cross-layer violations
- **ISP refactor** — `ServerRepository` split into 4 focused sub-interfaces
- **ChatViewModel split** — single `uiState` → 4 independent `StateFlow`s
- **Fat UseCases** — ViewModels delegate to UseCases, never repositories directly
- **DTO rename** — all transfer objects suffixed `*Dto` for clarity
- **Lifecycle-aware** — `collectAsStateWithLifecycle` throughout

### 🔍 Diagnostics & In-app Updates

- **In-app log viewer** — Settings → Diagnostics: level filtering, search, privacy-sanitized export, crash capture (SQLite, auto-prune)
- **Update checker** — About → Check for Updates: GitHub Release discovery, SHA-256 verified APK download, system installer handoff

### 🌐 i18n

- Hardcoded Chinese strings migrated to string resources
- Workspace and notification strings localized across all 15 locales

## Inherited Features

All features from upstream `v1.6.7` are preserved and continually improved:

- Native Material 3 chat UI with markdown, code blocks, tables, syntax highlighting
- Real-time message streaming with smart auto-scroll
- Terminal mode — PTY over WebSocket, Termux-like full-screen terminal with dedicated keys
- Multi-session management with per-session draft persistence (text, images, @file mentions)
- Model & agent selection (74 provider icons)
- 15-locale localization (en, ru, de, es, fr, it, pt-BR, id, ja, ko, zh-CN, uk, tr, ar, pl)
- Multi-server connection with auto-reconnect (exponential backoff)
- Local runtime via Termux — run OpenCode directly on-device
- AMOLED dark mode, Material You dynamic colors, customizable chat density & font size
- Foreground service keeping connections alive in the background
- Slash commands — `/new`, `/fork`, `/compact`, `/share`, `/rename`, `/undo`, `/redo`, `/shell`
- Swipe-to-revert user messages

## Download

Pre-built APKs are available on the [Releases](../../releases) page.

## Building

**Requirements:** JDK 21, Android SDK (compileSdk 37)

```bash
# Dev flavor (debug signing, coexists with the beta/stable builds)
./gradlew :app:assembleDevRelease

# Beta flavor (release signing — needs keystore config)
./gradlew :app:assembleBetaRelease
```

| Flavor | Application ID | Purpose |
|--------|---------------|---------|
| `dev` | `dev.leonardo.octether.dev` | Development preview (worktree builds) |
| `beta` | `dev.leonardo.octether.beta` | Public beta — coexists with stable |
| `stable` | `dev.leonardo.octether` | Production release (overwrites previous stable) |

See [AGENTS.md](AGENTS.md) for detailed build instructions, product flavors, signing configuration, and architecture overview.

## Tech Stack

- **Kotlin** + **Jetpack Compose** (BOM 2026.05.01)
- **Hilt** (KSP) for dependency injection
- **Ktor** (OkHttp engine) for HTTP & SSE
- **Material 3** design system
- Clean Architecture — domain / data / ui layers
- JDK 21 · compileSdk 37 · minSdk 26 · targetSdk 35

## Requirements

- Android 8.0+ (API 26)
- An OpenCode server reachable over the network (or run one locally via Termux)

## Acknowledgments

- **[@crim50n](https://github.com/crim50n)** — original author of [oc-remote](https://github.com/crim50n/oc-remote), on which this fork is built. The vast majority of the foundation — native UI, terminal mode, session management, multi-server, local runtime — is his work.
- The [OpenCode](https://github.com/anomalyco/opencode) team — for the server software this client connects to.

## License

MIT License — see [LICENSE](LICENSE).

    Copyright (c) 2026 crims0n <https://github.com/crim50n>
    Copyright (c) 2026 LeoNardo-LB <https://github.com/LeoNardo-LB> (fork enhancements)
