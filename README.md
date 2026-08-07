# OC Beacon

[crim50n/oc-remote](https://github.com/crim50n/oc-remote) 的增强 fork —— [OpenCode](https://github.com/anomalyco/opencode) 服务器的非官方 Android 客户端，原生 Material 3 UI。

> **OC Beacon 是 OC Remote 的独立社区 fork，与 OC Remote 原项目、其作者（@crim50n）或 OpenCode 团队无关联、未获其背书或赞助。**
>
> 本仓库是 [crim50n/oc-remote](https://github.com/crim50n/oc-remote) 的增强 fork。为与原版共存，已将 applicationId 从 `dev.minios.ocremote` 改为 `dev.leonardo.ocbeacon`，两者可在同一设备上同时安装。

## 与上游的关系

本 fork 从上游 `v1.6.7` 分叉，已累积 **1400+ 提交**的新功能、bug 修复、架构优化与 UI 打磨。沿用 MIT 许可证，对原作者 [@crim50n](https://github.com/crim50n) 完全致谢。

| | 上游 | 本 fork |
|---|----------|-----------|
| Application ID | `dev.minios.ocremote` | `dev.leonardo.ocbeacon`（另有 `.dev` / `.beta` 后缀用于并行安装） |
| compileSdk | 34 | 37 |
| targetSdk | 34 | 36 |
| Compose BOM | 2024.12.01 | 2026.05.01 |

得益于不同的 application ID，两个应用可以**同时安装**在同一设备上。版本号遵循 [Semantic Versioning](docs/release-workflow.md)——当前版本见 `version.properties`。

## 本 fork 的新功能

### 🆕 工作区浏览器

全新的模块，直接在手机上浏览和检查远程项目：

- **文件树** — 扁平化、懒加载目录树，带"显示忽略项"过滤器
- **Git 变更面板** — 每个变更文件的状态徽章（新增 / 修改 / 删除 / 未跟踪）
- **代码查看器** — 语法高亮源码视图 + 行号；懒渲染支持大文件
- **Diff 查看器** — 统一 diff，hunk 导航，增删行颜色区分
- **从聊天打开** — 工具卡片（Read / Edit / Write）可直接在查看器中打开引用文件

### 🆕 可插拔工具卡片

重写后的可扩展工具调用渲染系统：

- **基于注册表** — `ToolCardResolver` 注册表让新增工具类型变得简单
- **丰富的卡片类型** — `ApplyPatch`（内联 diff 预览）、`WebSearch`（结果列表）、`WebFetch`（URL + 摘要）、`Glob`（匹配数 + 可展开文件列表）、`Task` 等
- **可交互** — 复制输出、展开/折叠、从卡片打开引用文件

### 🆕 Token 与上下文分析

- **消息元数据** — 每条 assistant 消息显示模型名、耗时与 token 用量
- **Token 用量卡片** — 总量 / 缓存 / 输入 / 输出细分
- **上下文详情对话框** — 分类细分，含缓存命中率与成本指标

### 🆕 会话列表改进

- **最近 / 历史模式** — 在按时间排序的最近会话与完整历史间切换
- **下拉刷新** — 手动刷新会话列表
- **顺序待处理卡片** — 权限 / 问题卡片一次只显示一张，带位置指示（1/N）
- **重试跟踪** — 可见的重试状态与倒计时
- **会话分类** — 自定义名称/颜色/图标标签、按会话分配、过滤器 chips
- **跨服务器收藏** — 跨服务器星标会话、统一收藏列表 + 离线快照

### 🆕 消息级状态指示器

用户消息现在携带投递状态，让你始终知道消息是否到达服务器：

- **乐观发送** — 消息立即出现，无需等待服务器往返
- **三态徽章** — 发送中（spinner）→ 已发送（短暂）→ 失败（点击重试）
- **自愈同步** — 实时更新停滞时，应用自动重新同步消息状态

### ⚡ SSE 流式传输重构

数百个提交致力于让实时流式传输坚如磐石：

- **Delta 批处理**（48ms 窗口）— 消除快速 SSE delta 的抖动
- **漂移补偿** — 内容增长时视口保持钉住
- **滚动锚定锁** — 流式传输期间防止意外的滚动跳转
- **Revert 过滤** — 撤销操作不闪旧内容

### 🔔 通知系统

- **统一内容** — 通知展示最新用户消息
- **去重** — 防止权限 / 问题通知刷屏
- **前台抑制** — 正在查看活跃会话时不发通知
- **MessagingStyle** — 更丰富的任务完成通知

### 🎨 统一主题令牌系统

全面的设计令牌系统，取代 UI 中的硬编码值：

- **AlphaTokens** — 7 级语义透明度（FAINT / MUTED / MEDIUM / HIGH / AMOLED / …）
- **SpacingTokens** — 6 个网格间距常量（XS / SM / MD / LG / XL / XXL）
- **ShapeTokens & MotionTokens** — 组件形状与动画时长
- **ButtonTokens** — 统一按钮颜色 / 边框 / 间距
- **ListItemTokens** — Material 3 `ListItem` 内容 padding 的三种密度级别
- 所有 AMOLED 分支从 `Color.Black` 迁移到语义令牌

### 🏗️ 架构加固

- **Clean Architecture 强制执行** — 修复 domain → data 跨层违规
- **ISP 重构** — `ServerRepository` 拆分为 4 个聚焦的子接口
- **ChatViewModel 拆分** — 单一 `uiState` → 4 个独立 `StateFlow`
- **胖 UseCase** — ViewModel 委托给 UseCase，绝不直接调 repository
- **DTO 重命名** — 所有传输对象以 `*Dto` 结尾
- **生命周期感知** — 全链路使用 `collectAsStateWithLifecycle`

### 🔍 诊断与应用内更新

- **应用内日志查看器** — 设置 → 诊断：级别过滤、搜索、隐私脱敏导出、崩溃捕获（SQLite，自动清理）
- **更新检查器** — 关于 → 检查更新：GitHub Release 发现、SHA-256 校验的 APK 下载、系统安装器交接

### 🌐 i18n

- 硬编码中文字符串迁移到 string resources
- 工作区与通知字符串已本地化到全部 15 种语言

## 继承的功能

上游 `v1.6.7` 的全部功能均已保留并持续改进：

- 原生 Material 3 聊天 UI，支持 markdown、代码块、表格、语法高亮
- 实时消息流式传输 + 智能自动滚动
- 终端模式 — WebSocket 上的 PTY，类 Termux 全屏终端，带专用按键
- 多会话管理，每会话草稿持久化（文本、图片、@file 提及）
- 模型与 agent 选择（74 个提供商图标）
- 15 种语言本地化（en, ru, de, es, fr, it, pt-BR, id, ja, ko, zh-CN, uk, tr, ar, pl）
- 多服务器连接 + 自动重连（指数退避）
- AMOLED 深色模式、Material You 动态色、可自定义聊天密度与字号
- 后台保活的前台服务
- 斜杠命令 — `/new`、`/fork`、`/compact`、`/share`、`/rename`、`/undo`、`/redo`、`/shell`
- 滑动撤销用户消息

## 下载

预构建 APK 见 [Releases](../../releases) 页面。

## 构建

**要求：** JDK 21、Android SDK（compileSdk 37）

```bash
# Dev flavor（debug 签名，与 beta/stable 构建共存）
./gradlew :app:assembleDevRelease

# Beta flavor（release 签名——需要 keystore 配置）
./gradlew :app:assembleBetaRelease
```

| Flavor | Application ID | 用途 |
|--------|---------------|---------|
| `dev` | `dev.leonardo.ocbeacon.dev` | 开发预览（worktree 构建） |
| `beta` | `dev.leonardo.ocbeacon.beta` | 公开测试版——与 stable 共存 |
| `stable` | `dev.leonardo.ocbeacon` | 正式发布（覆盖安装之前的 stable） |

详细的构建说明、product flavor、签名配置与架构概览见 [AGENTS.md](AGENTS.md)。

## 技术栈

- **Kotlin** + **Jetpack Compose**（BOM 2026.05.01）
- **Hilt**（KSP）依赖注入
- **Ktor**（OkHttp engine）HTTP 与 SSE
- **Material 3** 设计系统
- Clean Architecture — domain / data / ui 三层
- JDK 21 · compileSdk 37 · minSdk 26 · targetSdk 36

## 要求

- Android 8.0+（API 26）
- 网络可达的 OpenCode 服务器

## 致谢

- **[@crim50n](https://github.com/crim50n)** — [oc-remote](https://github.com/crim50n/oc-remote) 原作者，本 fork 的基础。绝大多数地基工作——原生 UI、终端模式、会话管理、多服务器——都出自他手。
- [OpenCode](https://github.com/anomalyco/opencode) 团队 — 本客户端所连接的服务端软件。

## 许可证

MIT License — 见 [LICENSE](LICENSE)。

    Copyright (c) 2026 crims0n <https://github.com/crim50n>
    Copyright (c) 2026 LeoNardo-LB <https://github.com/LeoNardo-LB> (fork enhancements)
