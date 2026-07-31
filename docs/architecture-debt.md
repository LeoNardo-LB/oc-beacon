# Architecture Debt Register

Generated: 2026-07-13
Status: Documented for future refactoring sessions

## 1. Dependency Direction Violations (data → ui)

### Critical: ServerTerminalRegistry imports UI type
- **File**: `data/repository/ServerTerminalRegistry.kt:5`
- **Violation**: `import dev.leonardo.octether.ui.screens.chat.ServerTerminalWorkspace`
- **Root cause**: `ServerTerminalWorkspace` (487 lines) is architecturally a data-layer class
  (manages terminal connections via TerminalApi, PtyToTermlibAdapter) but was placed in
  `ui/screens/chat/` during initial development.
- **Fix**: Move `ServerTerminalWorkspace.kt` + `TerminalTabUi` to `data/terminal/`
- **Impact**: 5 files need import updates (ServerTerminalRegistry, TerminalDelegate,
  TerminalTabItem, ChatViewModel, + the file itself)
- **Risk**: Medium (mechanical move but 487 lines + same-package references)

### ui → data violations (acceptable for now)
- ChatViewModel injects: SseClient, SessionStateService, ServerTerminalRegistry
- SessionListViewModel injects: FileApi, SessionApi, SystemApi, TerminalApi
- ServerSettingsViewModel: bypasses domain layer entirely (ProviderApi, SystemApi)
- **Note**: These are documented in AGENTS.md as current state. Full fix requires
  creating domain-layer interfaces for SSE/state/terminal — a major architecture effort.

## 2. Thin UseCase Layer (29/30 pass-through)

29 of 30 UseCases are pure delegation (`suspend fun invoke(...) = repo.method(...)`).
Only `SubmitAnnotationsUseCase` contains business logic.

**Options for future**:
- A) Delete all thin UseCases, have ViewModels call Repositories directly
  - Pro: Removes 29 files of boilerplate
  - Con: Breaks 20+ test files, goes against documented Clean Architecture
- B) Keep as-is (current state, documented in AGENTS.md)
- C) Generate via code generation / KSP

**Recommendation**: Option B for now. The UseCases serve as a seam for future
business logic and testing. The boilerplate cost is low (9-43 lines each).

## 3. God Files (>500 lines)

| File | Lines | Action |
|------|-------|--------|
| ChatViewModel.kt | 1104 | Continue delegate extraction (Phase 5 pattern) |
| ChatScreen.kt | 841 | Continue sub-composable extraction |
| ChatMessageList.kt | 598 | Extract custom FlingBehavior + scroll logic |
| SessionListScreen.kt | 566 | Extract tree node rendering |
| SettingsDataStore.kt | 544 | Split into per-setting DataStores |
| SessionActionsDelegate.kt | 543 | Already a delegate — acceptable |
| FileViewerScreen.kt | 538 | Extract annotation/highlight logic |
| MessageDataDelegate.kt | 527 | Already a delegate — acceptable |

**Note**: Recent refactoring (Phase 1-4) already extracted 5 delegates from
ChatViewModel. Continue this pattern rather than large rewrites.

## 4. Missing Tests (High Priority)

| Module | Files | Risk |
|--------|-------|------|
| OpenCodeConnectionService.kt | 0 tests | HIGH — core lifecycle |
| home/ (all files) | 0 tests | HIGH — user entry point |
| settings/ (all files) | 0 tests | MEDIUM |
| data/dto/ | 0 tests | MEDIUM — serialization |
| chat/input/ (10 files) | 0 tests | MEDIUM |

## 5. Compose UI Gaps

- Zero `@Preview` annotations in entire project
- 10+ interactive Icons missing `contentDescription` (mainly QuestionPartContent)
- 1 hardcoded Chinese string (`QuickNavigateSheet.kt:108` — "关闭")
