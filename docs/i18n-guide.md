# 国际化（i18n）工作流指南

> 适用：任何涉及**用户可见文案**的改动——新增 UI 文案、修改措辞、删除文案、占位符调整。改代码前先读本指南。

## 1. 原则：无翻译框架，agent 直接维护

- **没有翻译框架**（lokit 已于 2026-08-07 移除，无 AI 翻译工具）。
- 15 种语言全部为**人工维护的多语言文件**，翻译由开发 agent 直接完成。
- 每个涉及文案的任务必须：改英文源 → 同步 14 个语言文件 → 跑检查脚本 → 编译。

## 2. 文件与语言

| 文件 | 语言 |
|------|------|
| `app/src/main/res/values/strings.xml` | **英文（唯一源）** |
| `app/src/main/res/values-{ar,de,es,fr,id,it,ja,ko,pl,pt-rBR,ru,tr,uk,zh-rCN}/strings.xml` | 14 种翻译 |

- **源语言永远只写英文**。系统语言不在 15 种之内的用户加载 `values/`（英文）。
- 语言代码：`pt-BR` → 目录名 `values-pt-rBR`、`zh-CN` → `values-zh-rCN`（Android 目录命名规则）。

## 3. 硬规则（违反会造成 bug）

1. **英文源必须纯英文**：不允许 CJK 汉字与全角标点（U+FF00–U+FFEF）。
   历史事故（2026-08-07）：英文源混入 4 处中文（`category`="新增 Tag"、`no_tags_placeholder`="暂无标签…"等），导致英文系统显示中文。
2. **占位符全语言一致**：`%1$s`、`%d` 等格式符必须与英文源完全一致（`%d` 与 `%1$d` 等价可混用，但位置参数如 `%2$s` 不可乱改）。不一致会导致**运行时崩溃**。
3. **所有 UI 文案必须走 `stringResource`**：Kotlin 代码中禁止硬编码可见字符串（含默认参数值——调用方必须传 `stringResource`）。
4. **复用 key 优先**：同文案不新建 key（如"添加"已有 `add`，不要造 `add_2`）。新增前先 grep 现有 key。
5. **删除 key 必须清 15 个文件**：孤儿 key 是翻译膨胀与维护噪音的来源。
6. **plurals 数量词**：目前有 4 个 `<plurals>` 块（如消息计数），新增数量词时 15 个文件都要有对应 `<plurals>` 及全部 item 数量。

## 4. 工作流

### 新增 / 修改文案
1. 编辑 `values/strings.xml`（英文值），遵循命名规范（`<模块>_<含义>`，如 `session_rename`、`menu_quick_navigate`）。
2. **同步编辑 14 个语言文件**：agent 直接翻译（术语见 §5），保持占位符一致。
3. 运行 `bash scripts/i18n-check.sh`（Windows: `pwsh scripts/i18n-check.ps1`），必须 PASSED。
4. 编译验证（`./gradlew :app:compileDevDebugKotlin`）。

### 删除文案
1. 从 15 个文件删除对应 `<string>`/`<plurals>`。
2. 跑检查脚本确认无孤儿 key。

### 新增语言
1. 复制 `values/` → `values-<code>/`（注意 pt-BR/zh-CN 的目录命名）。
2. 翻译全部 key（可由 agent 完成）。
3. 更新本指南 §2 语言清单。

## 5. 术语一致性（既有翻译为固定基准）

| 概念 | en | zh-CN | ja | ko | 其他语言 |
|------|----|-------|----|----|---------|
| Tag | Tag | 标签/添加标签 | タグ | 태그 | 见各文件 |
| Session | Session | 会话 | セッション | 세션 | — |
| Server | Server | 服务器 | サーバー | 서버 | — |

- 同一概念全语言沿用既有译法，新文案不引入新译名。
- 对话框标题与按钮的动词建议与既有翻译语义一致（如按钮 `assign_category` 各语言均为"添加标签"类语义）。

## 6. 检查脚本与 CI

`scripts/i18n-check.ps1`（替代 lokit）检查三项：
1. **Key 完整性**：14 语言 key 集合 == 英文源（缺失/孤儿报告）
2. **英文源纯净性**：无 CJK/全角标点
3. **占位符一致性**：每语言每 key 与英文源对齐（`%1$d` 归一化为 `%d` 比较）

- 本地：`bash scripts/i18n-check.sh`（有错退出码 1；Windows: `pwsh scripts/i18n-check.ps1`）
- **CI**：`release.yml` 在构建前自动运行（`bash scripts/i18n-check.sh`，CI 在 ubuntu 上运行 bash 版），失败即发版失败

## 7. 常见错误复盘（2026-08-07）

| 错误 | 表现 | 预防 |
|------|------|------|
| 英文源写中文 | 英文系统显示中文 | §3.1 + 脚本纯净性检查 |
| 语言文件漏加 key | 该语言回退英文（功能可用但未翻译） | 脚本完整性检查 |
| 占位符不一致 | 运行时崩溃/数字错位 | 脚本占位符检查 |
| 中英混杂文案（如"新增 Tag"） | 中文界面出现英文词 | §5 术语基准，审阅时留意 |
