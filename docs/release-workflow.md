# 发版工作流（Release Workflow）

> **本文档是发版工作的唯一权威指南。** 任何发版、版本号变更、tag 操作、GitHub Release 操作之前**必须先读本文档**。
> 最后更新：2026-08-05

---

## 1. 总览：一键发版

```bash
# 从项目根目录执行（Git Bash）
./scripts/release.sh beta    # 发 beta 预发布
./scripts/release.sh stable  # 发 stable 正式版
./scripts/release.sh dev     # 发 dev 预览
```

脚本自动完成：**分析 commit → 计算版本 → 更新 version.properties → 更新 CHANGELOG（仅正式版）→ commit → tag → push → 触发 CI 构建与 Release**。

**原则**：
- 发版流程**优先走自动化脚本**，避免手工操作（手工 bump 曾导致版本错误）。
- 脚本不可用时才走 §5 手动流程，且必须逐项核对。

---

## 2. 版本号规则（SemVer 2.0.0 适配）

### 2.1 格式

```
MAJOR.MINOR.PATCH[-LABEL.NUMBER]
```

| 字段 | 含义 | 递进条件 |
|------|------|---------|
| MAJOR | 大版本 | 不兼容的架构变更、完整重写、品牌重塑 |
| MINOR | 功能版本 | 新功能、新屏幕、新 API 对接（向下兼容） |
| PATCH | 修复版本 | Bug 修复、性能优化、UI 调整（向下兼容） |
| LABEL | 预发布标签 | `beta`（公开测试）或 `dev`（开发预览） |
| NUMBER | 预发布序号 | 同一版本的第 N 次预发布，从 1 开始 |

### 2.2 版本号与 commit 类型的对应（脚本自动推导）

| commit 类型（Conventional Commits） | 版本递进 |
|-------------------------------------|---------|
| `feat:` | MINOR |
| `fix:` / `perf:` / `refactor:` | PATCH |
| `BREAKING CHANGE` / `feat!:` | MAJOR |
| `docs:` / `chore:` / `test:` / `style:` | 不递进（不触发发版） |

### 2.3 版本号示例

```
1.0.0              ← 正式稳定版
1.0.1-beta.1       ← 1.0.1 的第一个 beta 测试版
1.0.1-beta.2       ← 1.0.1 的第二个 beta（修复测试反馈）
1.0.1              ← 1.0.1 正式版（beta 结束后发布）
1.1.0-beta.1       ← 1.1.0 新功能 beta
1.1.0-dev.3        ← 1.1.0 的第三个开发预览（worktree 构建）
```

### 2.4 单一真相源

- **`version.properties`**（项目根目录，唯一来源）:
  ```properties
  VERSION_CODE=5
  VERSION_NAME=1.0.3
  ```
- `VERSION_CODE`：整数，**永远只增不减**，每次构建 +1（Android 硬性要求）。**由脚本自动递增，禁止手工改动。**
- `VERSION_NAME`：显示字符串，遵循上述 SemVer 格式。
- `app/build.gradle.kts` 从 `version.properties` 读取 — 禁止在 build.gradle.kts 中硬编码版本号。
- CI 通过 grep `version.properties` 提取版本 — **不要改变文件格式**。

---

## 3. 发版自动化设计

### 3.1 流程总览

```
[开发者/AI]
    │  ./scripts/release.sh <flavor>
    ▼
[本地脚本 release.sh]
    1. 检查 git 工作树干净
    2. 分析 commits（last tag → HEAD）推导 bump 类型
    3. 计算新版本号（含 VERSION_CODE 递增）
    4. 更新 version.properties
    5. 更新 CHANGELOG.md（仅 stable 正式版）
    6. git commit "chore: bump version to vX.Y.Z"
    7. git tag vX.Y.Z
    8. git push origin master + git push origin vX.Y.Z
    ▼
[CI（.github/workflows/release.yml）自动触发]
    9. 按 tag 后缀选择 flavor（无后缀→stable / -beta→beta / -dev→dev）
    10. 构建对应 release APK（release keystore 签名）
    11. 复制为 oc-beacon-<VERSION>.apk
    12. 创建/更新 GitHub Release（仅附一个 APK）
```

**为什么这样分**：
- 版本计算、bump、CHANGELOG 由**本地脚本**完成——需要 git 历史上下文和写仓库权限，不适合 CI 冷启动。
- APK 构建由 **CI 完成**——干净环境、密钥不落本地、构建可复现。
- **人类/AI 的唯一动作就是运行 `./scripts/release.sh <flavor>`**，其余全自动。

### 3.2 脚本参数

```bash
./scripts/release.sh <flavor> [--dry-run] [--force-bump=major|minor|patch]
```

| 参数 | 说明 |
|------|------|
| `flavor` | `beta`（默认）/ `stable` / `dev` |
| `--dry-run` | 只打印将要执行的步骤，不修改任何文件、不推送 |
| `--force-bump` | 跳过 commit 分析，强制指定递进类型 |

### 3.3 版本号推导规则（脚本内部）

- **beta / dev（预发布）**：
  - 若上一个 tag 是**同主版本的预发布**（如 `v1.0.3-beta.1` → `v1.0.3-beta.2`），序号 +1。
  - 否则基于 **最后一个正式版 tag** 按 commit 推导 bump 后追加 `-beta.1`。
- **stable（正式版）**：
  - 基于**最后一个正式版 tag** 按 commit 推导 bump，去掉预发布标签。
- **示例**：
  - 现状 `v1.0.3`，新增 `fix:` → `beta` 发版 = `1.0.4-beta.1`；`stable` 发版 = `1.0.4`。
  - 现状 `v1.0.4-beta.1`，再发 `beta` = `1.0.4-beta.2`；发 `stable` = `1.0.4`。

---

## 4. CHANGELOG 规则

### 4.1 核心规则：**CHANGELOG.md 只在正式版（stable）发布时更新**

- **正式版**：`release.sh stable` 自动把 `last stable tag → HEAD` 的 commits 分类写入 CHANGELOG.md 顶部。
- **预发布（beta/dev）**：**不更新 CHANGELOG.md**。预发布期间的变更会在正式版发布时统一汇总。
- **理由**（符合 Keep a Changelog 规范）：
  - CHANGELOG 面向最终用户，用户基于正式版做升级决策；beta/dev 是中间产物，写进去会造成噪音。
  - 正式版发布时一次性汇总所有变更（从上一个正式版到当前），内容完整且可读。
  - 预发布的 Release Notes 由 GitHub 自动生成（`--generate-notes`），不依赖 CHANGELOG.md。

### 4.2 CHANGELOG.md 格式

```markdown
# Changelog

本项目遵循 [Semantic Versioning](https://semver.org/) 与 [Keep a Changelog](https://keepachangelog.com/)。
**CHANGELOG 仅在正式版（stable release）发布时更新**；beta/dev 预发布的变更在正式版发布时统一汇总。

## [1.0.3] - 2026-08-05

### Added
- ...

### Changed
- ...

### Fixed
- ...

### Removed
- ...
```

### 4.3 commit → CHANGELOG 分类映射（脚本自动）

| commit 类型 | CHANGELOG 分类 |
|-------------|---------------|
| `feat:` | `Added` |
| `fix:` | `Fixed` |
| `perf:` / `refactor:` | `Changed` |
| `BREAKING CHANGE` | `Removed` 或 `Changed` 前置 `**BREAKING:**` 标注 |
| `docs:` / `chore:` / `test:` / `style:` / `build:` / `ci:` | 不写入（内部维护） |

> **注意**：自动分类是**初稿**——按 commit type 机械归类，无法理解语义（例如 `feat: remove xxx` 会归入 Added）。正式版发布时，脚本生成的 CHANGELOG 条目**允许人工润色**（调整分类、补充细节、移除噪音），符合 Keep a Changelog 规范。润色在 `release.sh stable` 执行后、push 前完成（脚本 commit 前会等待确认，见 §3.1 交互说明）。

---

## 5. 手动发版流程（脚本不可用时）

> ⚠️ 仅在 `release.sh` 不可用时使用。**严禁跳过 §2 的版本规则**。

```
1. bump version → 修改 version.properties（VERSION_CODE +1，VERSION_NAME 按 §2.3 规则）
2. commit → git commit -m "chore: bump version to vX.Y.Z"
3. build → .\gradlew --stop && .\gradlew :app:assembleBetaRelease（按 flavor 选任务）
4. push → git push origin master
5. tag → git tag -a "vX.Y.Z" -m "vX.Y.Z — 简要说明"
6. push tag → git push origin "vX.Y.Z"
7. 等待 CI 完成构建（见 §6 验证），或本地手动：
   复制 APK 为 oc-beacon-<VERSION>.apk，
   gh release create "vX.Y.Z" "oc-beacon-<VERSION>.apk" --prerelease(仅预发布) --title --notes
```

**严禁在 `version.properties` 修改前执行 `assemble*`**，否则 APK 内嵌版本号与 tag/release 名称不一致。

---

## 6. 发版后验证清单

- [ ] `gh release list`：新 Release 存在，类型正确（stable 非 prerelease / beta、dev 为 prerelease）
- [ ] `gh release view <TAG> --json assets`：**恰好 1 个 APK**，命名 `oc-beacon-<VERSION>.apk`
- [ ] `aapt2 dump badging` 验证 APK：包名/versionCode/versionName 正确
- [ ] **签名验证（所有 flavor）**：`apksigner verify --print-certs` 的证书 DN 应为 `CN=OC Beacon, OU=Development, O=LeoNardo-LB, C=CN`（release keystore，2026-08-06 起，alias=oc-tether），**不得是 `CN=Android Debug`**
  - 若为 debug 签名 → 检查 GitHub Secrets（`gh secret list` 需含 KEYSTORE_BASE64/ALIAS/PASSWORD）与 build.gradle.kts 的 `if (!hasPropertiesFile)` 回退逻辑
- [ ] stable 的 APK 签名是 release keystore（oc-tether），可覆盖安装
- [ ] CHANGELOG.md 已更新（仅 stable）
- [ ] 历史 Release 未被删除（保留所有版本供下载）

---

## 7. 红线

- **禁止**删除历史 Release 或 Tag（用户可下载所有历史版本）。
- **禁止**手工修改 `VERSION_CODE`（脚本自动 +1）。
- **禁止**在发版前未读本文档。
- **禁止**同一 Release 上传多个 APK（每版本只附一个 `oc-beacon-<VERSION>.apk`）。
- `gh` CLI 不走代理，直接用直连（不加 `HTTP_PROXY`）。
- 构建命令必须带超时：`compileDevDebugKotlin` 120s、`testDevDebugUnitTest` 180s、`assemble*` 300s。

---

## 8. 与 AGENTS.md 的关系

- AGENTS.md 是项目总规则；本文档是**发版专项细则**。
- AGENTS.md 中"Version Management"章节引用本文档（发版前必读）。
- 本文档变更时，同步检查 AGENTS.md 是否需要更新。
