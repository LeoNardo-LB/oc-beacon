# Release Notes 模板与发版流程集成 — 设计文档

- 日期：2026-08-08
- 状态：已批准（用户确认方案 A + 英文分类名 + 中文内容）
- 关联文件：`docs/release-notes-template.md`（新建）、`scripts/release.sh`、`.github/workflows/release.yml`、`docs/release-workflow.md`、`AGENTS.md`

## 1. 背景与问题

GitHub Release 的说明（body）目前只有一行 `**Full Changelog**: <compare 链接>`，没有人工撰写的高层说明。用户希望：每次发版时说明按固定模板撰写，对比本版本与上个版本的区别，涵盖新增/修复/改进/优化等分类。

**根因**：CI（`.github/workflows/release.yml` 第 132 行）用 `gh release create --generate-notes` 让 GitHub 自动生成说明。GitHub 自动生成机制为 PR 驱动项目设计（按 PR label 分类），本项目直接 commit 到 master、无 PR/label，故输出贫乏（仅 Full Changelog 链接）。

## 2. 行业惯例调研结论

综合 Keep a Changelog、GitHub 官方文档、AnnounceKit、Worknotes、iTerm2 release-notes-guidelines、Changelog.dev、GitHub Copilot Release Notes 等来源：

1. **Release Notes ≠ CHANGELOG**：CHANGELOG 是完整运行记录（开发者/工具读）；Release Notes 是单次发版的用户公告，按变更类型分类、用户视角、突出亮点。
2. **标准分类**（Keep a Changelog 六类）：Added / Changed / Deprecated / Removed / Fixed / Security。
3. **结构惯例**：高层摘要（1-2 句主题）→ 分类明细（仅列有内容的类）→ 破坏性变更置顶加粗 → 完整变更链接。
4. **写作规则**：用户视角（不粘贴 commit message）；跳过内部变更（refactor/CI/依赖升级/测试）；每条 1-3 句、加粗关键名词；新增写"做什么 + 去哪找"，修复写"问题 → 已解决"；空节省略。

## 3. 已确认决策

| 决策点 | 选择 |
|--------|------|
| 填写机制 | **半自动**：release.sh 生成草稿 → 发布者润色 → 随发版 commit → CI 用 `--notes-file` |
| 流转路径 | **方案 A（仓库中转）**：根目录 `RELEASE_NOTES.md` 固定名，每版本覆盖；留档靠 tag + GitHub Release 页面 |
| 语言 | 内容中文为主 |
| 分类标题 | 英文（`### Added` 等），与现有 CHANGELOG.md 一致 |
| 适用范围 | **每次发版**（beta/dev/stable 都生成），范围 = last tag → HEAD |
| 分类集合 | Keep a Changelog 标准六类，空节省略 |

## 4. 设计

### 4.1 新建 `docs/release-notes-template.md`（核心交付物）

三部分内容：

**① 模板本体**（发布者复制到 GitHub Release 说明）：

```markdown
## OC Beacon <版本号> — <YYYY-MM-DD>

> <版本摘要：本版主题一句话，1-2 句>

### Added
- <新增特性：做什么 + 在哪找（用户视角）>

### Changed
- <改进/优化：旧行为 → 新行为>

### Deprecated      ← 平时无内容，使用前整节删除
### Removed         ← 破坏性变更前置 **BREAKING:**
### Fixed
- <修复：用户看到的问题 → 现在已解决>

### Security        ← 平时无内容，使用前整节删除

---
完整变更记录：[Full Changelog](https://github.com/LeoNardo-LB/oc-beacon/compare/<上一tag>...<本tag>)
```

**② 写作规则**（发布者对照检查）：
- 用户视角：不粘贴 commit message，不写内部实现（重构/CI/依赖升级/测试一律跳过）
- 每条 1-3 句，加粗关键名词便于扫读
- 新增特性写"做什么 + 入口在哪"；修复写"用户看到的问题 → 现在好了"
- 破坏性变更必须醒目（**BREAKING:** + 迁移说明）
- 空节省略，只用有内容的分类
- 版本摘要是必填项，不许留空

**③ 分类与 commit 映射**：引用 `docs/release-workflow.md` §4.3（单一真相源，不重复维护）。

### 4.2 `scripts/release.sh` 改动

- 新增函数 `gen_release_notes()`，仿照现有 `gen_changelog_entry()`（§125-173）：
  - 范围：**last tag → HEAD**（复用现有 `CUR_TAG`/`LAST_STABLE` 逻辑决定 since，优先当前版本的 tag）
  - 分类复用现有 case 逻辑（feat→Added、fix→Fixed、perf/refactor→Changed、BREAKING→Removed、docs/chore/test/style/build/ci 跳过）
  - 输出：模板结构（`## OC Beacon <version> — <date>` + 摘要占位 + 六节 + Full Changelog 链接）
  - 写根目录 `RELEASE_NOTES.md`（覆盖）
- 流程插入（在更新 version.properties 之后、CHANGELOG 更新之前）：
  1. 生成 RELEASE_NOTES.md 草稿
  2. 暂停等待润色：复用现有 CHANGELOG 润色确认交互模式（"请润色 RELEASE_NOTES.md（填版本摘要、改为用户视角），完成后按回车继续"）
  3. commit 时 `git add RELEASE_NOTES.md`（与 version.properties、CHANGELOG 同 commit）
- `--dry-run`：只打印草稿内容，不写文件

### 4.3 `.github/workflows/release.yml` 改动

将第 129-133 行 `gh release create` 的 `--generate-notes` 替换为：

```bash
if [ -f RELEASE_NOTES.md ]; then NOTES_ARGS="--notes-file RELEASE_NOTES.md"; else NOTES_ARGS="--generate-notes"; fi
```

CI checkout 的是发版 commit（含润色后的 RELEASE_NOTES.md），天然版本化；文件缺失时 fallback 回 `--generate-notes`。

### 4.4 `docs/release-workflow.md` 改动

- 新增 **§4.5 "Release Notes 规则"**（沿用文档已有的 §5.5 插队编号先例）：
  - 模板位置：引用 `docs/release-notes-template.md`
  - 生成机制：`release.sh` 自动生成草稿 + 发布者润色（所有 flavor）
  - 范围：last tag → HEAD，对比本版本与上个版本
  - 与 CHANGELOG 关系：CHANGELOG 仅 stable 更新（§4.1 不变）；Release Notes **每次发版都有**（beta/dev 预发布也写）
- §1 总览、§3.1 流程图说明文字同步更新（生成草稿 → 润色 → commit 步骤）
- §5 手动流程：加一步"按模板撰写 RELEASE_NOTES.md"（`gh release create` 加 `--notes-file`）
- §6 验证清单：加一项"Release 说明非空，非仅 Full Changelog 链接"
- §8：提到 AGENTS.md 需要同步

### 4.5 `AGENTS.md` 改动

文档索引表新增：`docs/release-notes-template.md`（🟡 SHOULD，发版说明写作参考）。

## 5. 明确不做

- CHANGELOG 更新逻辑不变（仅 stable 更新，§4.1）
- 版本号推导、APK 构建、签名流程全部不动
- 不引入 AI 自动生成（Copilot Release Notes 等），保持半自动 + 人工润色
- 不删除历史 Release/Tag 政策不变

## 6. 成功标准

1. `docs/release-notes-template.md` 存在，含模板 + 写作规则 + 分类映射引用
2. `release.sh` 发版流程中生成 RELEASE_NOTES.md 草稿并有润色确认交互（`--dry-run` 可预览）
3. `release.yml` 优先用 `--notes-file RELEASE_NOTES.md`，缺失时 fallback
4. `docs/release-workflow.md` 引用模板文件（§4.5 + 手动流程 + 验证清单）
5. 下次发版产出的 GitHub Release 说明为模板结构（人工撰写，非仅 Full Changelog 链接）
