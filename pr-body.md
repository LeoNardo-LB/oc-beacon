## Summary

Adds a **hidden directories** setting: a user-configurable list of glob patterns (e.g. `/tmp/*`) whose matching project/session directories disappear from the app's directory-driven surfaces.

**Motivation.** OpenCode servers accumulate sessions across hundreds of throwaway scratch directories. One real-world case: agents (and CI-like benchmark harnesses) run sessions under `/tmp/opencode/<harness>/<run>/...` — a few hundred distinct directories. The session list is project-driven (sessions are fetched per `project.worktree`), so every scratch directory becomes a permanent entry on the phone, and there is currently no way to hide them. The server API offers no exclude parameter (`GET /project` is an unfiltered list; `GET /session` supports only `scope/path/roots/start/search/limit`), so the filter has to live client-side.

## What it does

- New setting **Settings → Chat behavior → Hidden directories**, persisted in DataStore (`hidden_directory_patterns`, a string set). One glob per line, `#` lines are comments, blank lines ignored.
- Default is **empty → zero behavior change** until the user configures patterns.
- Applied consistently in every directory-driven surface:
  - **Project list** (`SessionListViewModel.fetchAllSessions`) — hidden projects are not displayed *and not fetched*, saving per-project session requests. A "server has projects but all are hidden" case clears the list explicitly instead of falling into the no-projects path.
  - **Quick new-session dialog** (`recentSessionDirectories`) — hidden directories don't appear as quick-start targets.
- Matching semantics (`util/HiddenDirectories.kt`, pure function): paths and patterns are normalized (`\` → `/`, trailing `/` stripped); `*` crosses path separators so `/tmp/*` hides `/tmp` itself and everything below it; `?` matches a single char; everything else is literal (regex metacharacters escaped); case-sensitive.
- 14 new unit tests (`HiddenDirectoriesTest`), including regex-metacharacter and Windows-backslash cases.
- i18n: 5 new string keys across all 14 translations + English (`scripts/i18n-check.sh` passes: 774 keys × 14 languages).

## Verification

- `scripts/i18n-check.sh` — **passes locally** (774 keys × 14 languages, no placeholders, English purity OK).
- Compile/build: dispatched the `Build Release APK` workflow (`flavor=dev`) on the feature branch in the fork — result will be linked/updated here. *(No local Android toolchain available.)*
- Not yet verified on a real device; the settings change takes effect on the next session-list refresh (documented in-code — avoids re-fetching while the user is still editing patterns).

## Open questions for the maintainer

1. Would a **built-in default** pattern (e.g. `/tmp/*`, `/private/tmp/*`) be preferable to shipping with filtering fully off? I kept it opt-in to preserve existing behavior.
2. Should hidden projects also be excluded from the workspace/file browser entry points, or is the project list + quick-new-session dialog the full set of directory-driven surfaces? (I found `OpenProjectDialog` is a manual browser fed with `emptyList()`, so I left it alone.)
3. Interested in upstreaming this to `crim50n/oc-remote` as well? The same scratch-directory spam applies there.

## 中文摘要

新增「隐藏目录」设置：用户可配置 glob 模式列表（如 `/tmp/*`），命中的项目/会话目录将从项目列表与快捷新建会话对话框中隐藏。动机：OpenCode 服务器会在大量临时目录（如 `/tmp/opencode/...`）下积累会话，服务器 API 无排除参数，只能在客户端过滤。默认为空（零行为变化）；项目级过滤同时省去对隐藏项目的会话拉取请求。匹配为纯函数（`util/HiddenDirectories.kt`），带 14 个单元测试；5 个新字符串已覆盖全部 14 种语言（i18n 检查通过：774 键 × 14 语言）。

MIT,如上游觉得合适也乐意回馈 crim50n/oc-remote。🫡
