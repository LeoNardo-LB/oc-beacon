# Phase 1: ChatViewModel Decomposition

**Date:** 2026-07-10  
**Status:** Draft  
**Parent:** Codebase optimization roadmap

## 1. Problem

ChatViewModel.kt is a 1205-line God Object with 63 functions, 223 properties, and 22 constructor dependencies. It already has a delegate pattern started (6 delegates) but is only half-done:

- **46 one-line pass-through functions** forward to delegates — acceptable as public API
- **16 business logic functions** contain heavy logic that belongs in delegates — these are the problem
- The biggest offender: `buildContextDetailState` (~200 lines) computes context usage breakdown inline

## 2. Goal

Move all heavy logic into delegates. ChatViewModel becomes a thin orchestrator:
- **Before:** 1205 lines, 63 functions
- **After:** ~500 lines (46 pass-throughs + 16 thin coordinators + state exposure + lifecycle)
- Each delegate owns its domain logic completely

## 3. Extraction Plan

### 3.1 ToolCacheDelegate (new) — ~120 lines extracted

**Move:** `cacheToolPart` (L236-274) + `cleanReadToolOutput` (L275-344)

These 2 private functions handle tool snapshot caching logic:
- Parse Part.Tool state (Completed/Running/Pending/Error)
- Extract filePath, filediff, before/after
- Clean read tool output (strip ANSI, format code)
- Store in ToolSnapshotCache

**New file:** `ToolCacheDelegate.kt` — takes `ToolSnapshotCache` + `ChatRepository` as deps

**ChatViewModel keeps:** `fun cacheToolPart(part) = toolCache.cacheToolPart(part)` (pass-through)

### 3.2 ContextDetailDelegate (new) — ~200 lines extracted

**Move:** `buildContextDetailState` (L641-840)

This is the biggest single function. It computes context usage breakdown from:
- Token stats (input, output, cache, reasoning)
- Context window size from provider
- Per-part token estimation
- Breakdown by category (tools, reasoning, code, user, assistant, other)

**New file:** `ContextDetailDelegate.kt` — takes `TokenStatsTracker` + `SettingsRepository` as deps

**ChatViewModel keeps:** `val contextDetailState = contextDetail.state` (property exposure)

### 3.3 SessionActionsDelegate (extend) — ~120 lines moved

**Move:** `sendMessage` (L913-934) + `sendParts` (L955-1005) + `refreshSessionTitleDelayed` (L935-954) + `abortSession` (L1018-1037) + `revertMessage` (L1074-1118)

These 5 functions coordinate send/abort/revert flows. They currently live in the ViewModel but logically belong in SessionActionsDelegate (which already has 590 lines of session-related logic).

**Risk:** SessionActionsDelegate grows from 590 to ~710 lines. Still large but all session-action logic is co-located.

### 3.4 TerminalDelegate (extend) — ~30 lines moved

**Move:** `openTerminalSession` (L1152-1157) + `reconnectTerminalTab` (L1162-1164)

Small terminal lifecycle functions that belong with the existing terminal delegate.

## 4. What Stays in ChatViewModel

- 46 pass-through functions (public API for ChatScreen)
- `onSessionFocused` / `onSessionUnfocused` (lifecycle coordination across delegates)
- `getConnectionParams` (5 lines, reads from multiple delegates)
- `getLastAssistantText` (3 lines, reads from messageData)
- `appendDiagnosticLog` (5 lines, diagnostic only)
- State exposure properties (messageListState, interactionState, etc.)
- `init` block (delegate wiring + ViewModel scope launch)

## 5. Task Order (dependency-safe)

1. **ToolCacheDelegate** — no dependencies on other extractions
2. **ContextDetailDelegate** — no dependencies on other extractions
3. **TerminalDelegate extension** — small, low risk
4. **SessionActionsDelegate extension** — largest, highest risk, do last

Each task: extract → compile check → run integration tests → commit

## 6. Verification

After each extraction:
1. `.\gradlew :app:compileDevDebugKotlin` (120s timeout)
2. `.\gradlew :app:testDevDebugUnitTest --rerun` (180s timeout) — existing ViewModel tests must pass
3. Commit only after both pass

Final verification:
4. `.\gradlew :app:connectedDevDebugAndroidTest` — integration tests must still pass (27+)

## 7. Out of Scope

- Removing pass-through functions (they're the ViewModel's API, keep them)
- Splitting delegates that are already large (MessageDataDelegate 572 lines — Phase 2)
- ChatScreen.kt decomposition (Phase 3)
