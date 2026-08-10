# ChatScreen.kt Editing Protocol

- NEVER edit ChatScreen.kt in parallel across multiple agents
- ALWAYS Read before Edit to confirm current content
- After each Edit, run `./gradlew :app:compileDevDebugKotlin` (Windows: `.\gradlew.bat :app:compileDevDebugKotlin`)
- Commit after each successful compilation
- If compilation fails: `git checkout -- ChatScreen.kt`, re-read, retry
- One commit = one logical change (no bundling)

## Rationale

ChatScreen.kt contains the core chat UI (sub-composable extraction has progressively shrunk it; check the current line count before editing). Earlier beta cycles (beta.62-64, when it was 8000+ lines) showed repeated code loss when multiple changes were made simultaneously. This protocol ensures each change is validated and committed before the next begins.
