# Changelog

本项目遵循 [Semantic Versioning](https://semver.org/) 与 [Keep a Changelog](https://keepachangelog.com/)。

**CHANGELOG 仅在正式版（stable release）发布时更新**；beta/dev 预发布的变更在正式版发布时统一汇总。发版流程见 [docs/release-workflow.md](docs/release-workflow.md)。

## [1.0.3] - 2026-08-05

首个正式版。基于 v1.0.3-beta.1（架构重构 + 全面清理）转正。

### Changed

- 依赖方向修复：`ServerTerminalWorkspace`/`TerminalTabState` 迁移到 `data/terminal`，消除 data→ui 违规
- 新增 domain 接口：`SessionStateRepository`（会话状态单一真相源的 UI 视角）、`ProviderRepository` 扩展（+8 方法）
- `ServerSettingsViewModel` 重构：移除 `ProviderApi`/`SystemApi` 直接注入，完全依赖 domain 层
- God Files 拆分：ChatViewModel 1182→596 行、SessionListScreen 701→381、SessionListViewModel 679→302、SettingsDataStore 688→289
- 39 个编译 warning 全部清零（deprecated API 迁移、冗余代码清理）
- 依赖精确化：`hilt-navigation-compose` → `hilt-lifecycle-viewmodel-compose`、移除未用依赖
- 仓库清理：垃圾文件、历史 docs 精简（10MB→828KB）、24 个设计 spec 归档

### Removed

- 9 个零调用 UseCase（ConnectServer、CreateSession、GetMessages 等）
- ChatViewModel 死注入 `SseClient`
- 本地服务器（Termux）功能、`AppDialog` 遗留组件
