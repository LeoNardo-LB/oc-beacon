# Release Notes 模板（GitHub Release 发版说明）

> 本模板是 **GitHub Release 说明（Release Notes）的撰写规范**，与 [docs/release-workflow.md](release-workflow.md) §4.5 配套使用。
> **每次发版（beta / dev / stable）的 GitHub Release 说明都必须按本模板撰写。**
>
> **生成机制**：`./scripts/release.sh <flavor>` 发版时自动生成 `RELEASE_NOTES.md` 草稿（基于 last tag → HEAD 的 commits 自动分类），发布者**润色后**随发版 commit 提交，CI 用 `--notes-file RELEASE_NOTES.md` 发布。

## 模板本体

发布时复制以下结构到 GitHub Release 说明（**空节删除**）：

```markdown
## OC Beacon <版本号> — <YYYY-MM-DD>

> <版本摘要：本版主题一句话，1-2 句>

### Added
- <新增特性：做什么 + 在哪找（用户视角）>

### Changed
- <改进/优化：旧行为 → 新行为>

### Deprecated
- <已弃用功能的预告与替代方案>        ← 平时无内容，整节删除

### Removed
- <已移除的功能；破坏性变更前置 **BREAKING:**>   ← 平时无内容，整节删除

### Fixed
- <修复：用户看到的问题 → 现在已解决>

### Security
- <安全修复；涉及 CVE 附链接>          ← 平时无内容，整节删除

---
完整变更记录：[Full Changelog](https://github.com/LeoNardo-LB/oc-beacon/compare/<上一tag>...<本tag>)
```

## 写作规则

1. **用户视角**：不粘贴 commit message，不写内部实现（重构 / CI / 依赖升级 / 测试一律跳过）。
2. **版本摘要是必填项**：1-2 句概括本版主题（如"本版聚焦会话列表性能与未读红点体验"），不许留空。
3. **每条 1-3 句**，加粗关键名词便于扫读。
4. **新增特性**写"做什么 + 入口在哪"（如"会话列表新增**未读红点**：进入会话自动消费"）。
5. **修复**写"用户看到的问题 → 现在已解决"（如"修复**杀进程后未读红点丢失**：重启后仍能恢复"）。
6. **破坏性变更置顶醒目**：`**BREAKING:**` 前缀 + 迁移/影响说明。
7. **空节省略**：只用有内容的分类。
8. **范围**：只写本版本（last tag → HEAD）相对上个版本的变化，不重复历史内容。
9. **术语**：用词遵循 [CONTEXT.md](../CONTEXT.md) 术语表。高频速查：会话（非对话）· 轮次完成/轮次（非任务完成/回合）· 堆积消息（非排队/待发）· 子智能体（非子代理/Sub-agent）· 智能体（非 Assistant）· 撤销（非回退）· 中断（协议 interrupt；本地停止亦同）· 压缩（非 summarize）· 合成通知（非系统通知）· 目录（非文件夹）。引用 UI 文案以发版时实态为准。

## 分类与 commit 映射

草稿由 `scripts/release.sh` 按 [docs/release-workflow.md](release-workflow.md) §4.3 的映射自动分类生成（初稿），发布者负责润色成用户视角。
