# Maestro E2E Test Flows

## Prerequisites
- Android emulator running (API 26+)
- Maestro CLI installed: `curl -Ls "https://get.maestro.mobile.dev" | bash`
- App installed: `.\gradlew :app:installDevDebug`

## Flow 分层

| 层级 | 前缀 | 内容 | 触发时机 |
|------|------|------|---------|
| 基础层 | `l1-` | 启动/稳定性/连接错误/崩溃恢复/首页（5-8 步，最快） | 冒烟档必选 |

**测试服务器前置**：e2e-chat-flow / e2e-file-viewer-annotation / e2e-md-preview-toggle / e2e-workspace-git-changes / e2e-tool-card-view 点击会话 "System issue analysis"——需在测试服务器（10.0.2.2:4096）预建该英文名会话（D3-3 E2E 全英文锁定裁决，2026-08-23）。 |
| 功能层 | `l2-` ~ `l5-` | 会话列表/搜索/归档/加载更多、聊天 UI、工具进度、token 权限 | 功能验证/全面档 |
| 完整旅程 | `e2e-` | 端到端完整流程与专项（服务器设置/文件查看/工作区/设置/旋转） | 全面档 |
| 辅助 | `terminal-smoke` / `util-has-server` | 终端冒烟 / 服务器存在性检测 | 按需 |

> 2026-08-06：`e2e-phase234-full.yaml` 已删除（`e2e-phase234-combined.yaml` 的冗余子集）。

## E2E 两档标准（维度 2c，见 docs/verification-requirements.md）

### 档位 A：冒烟测试（Smoke）—— 每阶段收尾 / 发版前置

目标：15 分钟内验证核心链路（启动安全 + 核心用户旅程）。

```bash
maestro test \
  maestro/l1-app-launch.yaml \
  maestro/l1-home-screen.yaml \
  maestro/l1-connection-error.yaml \
  maestro/l1-crash-recovery.yaml \
  maestro/e2e-server-setup.yaml \
  maestro/e2e-session-list.yaml \
  maestro/l4-chat-ui.yaml \
  maestro/e2e-settings-flow.yaml \
  maestro/terminal-smoke.yaml \
  maestro/e2e-rotation-restoration.yaml
```

通过标准：所有步骤 COMPLETED + `assertVisible` 通过 + 无崩溃。

### 档位 B：全面 + 回归测试（Full）—— 正式发版前 / 大版本收尾

```bash
# 1. 全部 flow（32 个，含 e2e-* 完整旅程）
maestro test maestro/*.yaml

# 2. androidTest 全量实机
.\gradlew :app:connectedDevDebugAndroidTest

# 3. 全量单元测试
.\gradlew :app:testDevDebugUnitTest --rerun
```

回归策略：变更相关 flow 必跑 + 冒烟档全跑 + 全量兜底。

## Single flow

```bash
maestro test maestro/l1-app-launch.yaml
```

## With screenshots / junit

```bash
maestro test --format junit --output maestro-results/ maestro/l1-app-launch.yaml
```

截图默认保存在 `maestro-screenshots/`。

## 服务器依赖

需要外部服务器连接的 flow（如 `e2e-chat-flow`）通过 `util-has-server.yaml`
检测服务器可用性，`extendedWaitUntil` 优雅降级——禁止使用 `manual` 标记。
