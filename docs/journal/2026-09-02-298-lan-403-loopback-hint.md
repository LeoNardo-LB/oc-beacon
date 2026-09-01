# 298-lan-403-loopback-hint（2026-09-02）

> 状态：完结（真机双路径 E2E PASS，2026-09-02 迁卡）
> 关联：backlog #298 · `docs/research/2026-09-01-dsh-web-vs-android-gap.md` §11.2（并行会话差距调研，本批消费其结论）
> 来源：#298 卡（2026-09-02 深调发现，源码+活体双证）

## §〇 概览

| 项 | 内容 |
|---|---|
| 卡片 | #298 LAN 部署下设置面 403——权限/预设默认档静默降级 |
| 根因 | DSH `PRIVILEGED_METHODS` 15 项硬门禁只放行 loopback Host；LAN/Tailscale 连接对 `settings.*` 恒 403 → `settingsNamespace` 吞错返 null → 两个默认档区块**整块消失**（比卡面记录的「回退三档」更彻底——行组件 `currentValue==null` 早退） |
| 修复 | 403 形态识别（`DshApiError.httpStatus==403`）→ 抛 domain 级 `DshSettingsForbiddenException` → VM 置 blocked 标志 → 行组件保留渲染 loopback 标注（i18n 15 语言） |
| commit | 51bfa059 `fix: #298 LAN 连接默认档 403 显式标注——特权面失败不再静默降级整块消失` |

## §一 服务端形态取证（部署版实证）

部署版 `/home/linuxbrew/.linuxbrew/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-client-connection/lib/index.js`：

```js
// :504 PRIVILEGED_METHODS = new Set([...]) 15 项：agentPreset.read/copy/openDocument/remove、
//     host.pickDirectory/openPath、settings.describe/openDocument/update/replace/mutate、
//     credentials.describe/set/unset、llm.discoverModels
// :538 if (method !== void 0 && PRIVILEGED_METHODS.has(method) && !isTrustedApiRequest(request, []))
//         return new Response("forbidden", { status: 403 });
```

要点：
- 响应 = **HTTP 403 + 纯文本 body `forbidden`**（非 JSON 信封）→ app 侧可靠特征就是 `DshApiError.httpStatus==403`（`code==null`，搬运层错误，DshRpcClient §5 分类）。
- 恒以**空信任表**校验（`isTrustedApiRequest(request, [])`）——`trustedHosts` 配置也不解锁，唯一放行 = Host 头 loopback。app 走 OkHttp 按 URL 自动生成 Host（DshRpcClient 注释 §1.6 P-1 明示勿覆写），故 LAN URL 必然 403、adb reverse（127.0.0.1）必然放行。
- 波及面核对：app 的四个特权调用（get/setPermissionDefault、get/setDefaultAgentPreset）全部经 `settingsNamespace`/`settingsMutateSet`；roster `agentPreset.list` **不在**名单内（LAN 下正常）——与卡面一致。
- **不做 Host 头伪造绕栅栏**（卡面安全边界裁决，遵守）。

## §二 修复设计

分层（依赖方向 UI → Domain ← Data）：

1. **Domain**（`domain/repository/DshSettingsRepository.kt`）：新增 `DshSettingsForbiddenException`；接口 KDoc 声明错误契约——403 抛它，其余失败维持 null/false 静默降级。
2. **Data**（`data/api/dsh/DshApiClient.kt`）：
   - `settingsNamespace`：`rpc.call` 失败分支检测 `e is DshApiError && e.httpStatus == 403` → logcat 锚点 `settings.describe forbidden for ns=… (403, loopback-only)` + 上抛；其余失败维持原 log+null。
   - `settingsMutateSet`：同栅栏检测（settings.mutate 同属特权面）；实现为 `outcome.isFailure` 分支（教训：`Result<Unit>.getOrElse{...}.isSuccess` 不成立——getOrElse 返回 Unit 而非 Result，编译期即暴露）。
3. **VM**（`SessionListViewModel.kt`）：`permissionDefaultBlocked`/`agentPresetDefaultBlocked` 两个 StateFlow；四个函数（load×2/set×2）内 try/catch——catch 在函数体内，覆盖 init 加载与后续所有调用方；load 入口先复位 false（服务器切换后自愈）。
4. **UI**：
   - `PermissionDefaultRow`/`AgentPresetDefaultRow` 新增 `blocked` 参数：blocked → 共享 `DefaultsBlockedSection`（标题保留 + 分隔线 + 标注文案；非点击、无箭头——不复用 SettingsSectionHeader 是因其箭头常驻会误导可展开）；非 blocked 维持原 `currentValue==null` 早退。
   - `ServerSettingsContent`/`SessionListScreen` 透传接线。
5. **i18n**：新 key `server_settings_defaults_loopback_hint`（英文源 + 14 语言），`scripts/i18n-check.sh` PASS（761 keys × 14）。坑：乌克兰语 `з'єднання` 撇号须转义 `\'`（Android 资源编译 `Invalid unicode escape sequence`）。

文案（zh-rCN）：`默认档仅支持 loopback 连接访问。请使用 adb reverse（127.0.0.1）连接后管理。`

## §三 验证

### V2 单元测试
- 新增 5 个（`DshApiClientTest`）：
  - `getPermissionDefault throws forbidden on 403 describe`（403+forbidden body → 抛 domain 异常）
  - `getPermissionDefault null on non-403 server error`（5xx 维持静默 null——只有栅栏 403 上抛）
  - `getDefaultAgentPreset throws forbidden on 403 describe`
  - `setPermissionDefault throws forbidden on 403 describe`（写路径经 getter 先行读）
  - `setDefaultAgentPreset throws forbidden when mutate 403`（describe 放行 + mutate 403 → 独立分支）
- 目标类 59/0/0；全量 `:app:testDevDebugUnitTest --rerun` **2537/0/0**（+5）。
- 断言惯例勘误：JUnit4 无 `assertFailsWith`，仓库同域惯例 = `runCatching` + `outcome.exceptionOrNull() is X`（对齐 compactSession throws 先例）。

### V1 编译/构建
- `compileDevDebugKotlin` ✅；`assembleDevDebug` ✅；WiFi ADB `install -r` Success（192.168.110.239:5555；dev flavor 时间戳 versionCode 覆盖装，未卸载）。

### V5/V6 真机双路径 E2E（subagent 委派，进行中）
- Path B 基线（adb reverse / 127.0.0.1:3080）：两区块正常带当前值、无标注、无 403 锚点。
- Path A（LAN 直连 http://192.168.110.248:3080）：两区块保留 + loopback 标注可见；logcat 双锚点行。
- 切回恢复：两区块复原。
- 顺带 #158：快速定位抽屉跳转 ×10 + dump 计数（预期 0 退化，35 连不复现）。
- （结果回填于 §四）

## §四 E2E 结果（subagent 多模态验收，2026-09-02 05:53–06:07）

**总判定 PASS**（Path B 基线 / Path A LAN / 切回恢复三段全过）。

| 路径 | 判定 | 亲眼所见（截图裁决） |
|---|---|---|
| B 基线（127.0.0.1:3080，/tmp/298/shot-b-baseline.png） | ✅ | 两区块在场带当前值（完全访问 / PTC 模式）+ 展开箭头；权限行可展开三档；无 loopback 提示 |
| A LAN（http://192.168.110.248:3080，/tmp/298/shot-a-lan.png） | ✅ | 两区块**保留渲染**，标注原文「默认档仅支持 loopback 连接访问。请使用 adb reverse（127.0.0.1）连接后管理。」；无当前值无箭头；会话列表 LAN 下正常加载（session.list 非特权） |
| B 恢复（shot-b-restore.png） | ✅ | 两区块复原（完全访问 / PTC 模式 + 箭头），标注消失——服务器切换自愈成立 |

logcat 锚点（4 条仅现于 Path A 窗口 05:57:35，B 期与恢复期计数 0）：

```
09-02 05:57:35.863 W/DshApi(23116): settings.describe forbidden for ns=permission (403, loopback-only)
09-02 05:57:35.863 W/SessionListViewModel(23116): permission default blocked: loopback-only connection (403)
09-02 05:57:35.960 W/DshApi(23116): settings.describe forbidden for ns=agent-presets (403, loopback-only)
09-02 05:57:35.960 W/SessionListViewModel(23116): agent preset default blocked: loopback-only connection (403)
```

**顺带 #158 计数**：快速定位抽屉开 → 点项跳转 ×10（logcat scroll-to 事件 10 次一一对应），dump 恒 34499B、输入栏在场 ×20——**0/10 退化**，累计 35 连不复现（箭头 15 + 抽屉 20）。

**偏差记录**（不影响判定）：
- adb daemon 中途重启一次（05:46），重连后照常执行；logcat 回放重复段按时间戳判定锚点不受影响。
- debug-entry 冷启固定指向 Host-4199（opencode，两区块本就被 DSH 能力位门控）——Path B 基线取自 app 内既有 DSH 条目「127.0.0.1:3080」，符合契约定义（adb reverse/127.0.0.1）。
- 服务器表单键盘上移导致首填错位，已核对重填（名称/URL/DSH 类型 dump 核对后保存）。
- 清理全确认：LAN-298-test 条目已删、app 停回 127.0.0.1:3080、dsh server（pid 1949112）未动探活 200、reverse 保持激活、未触真实工作会话、无 gradle。

## §五 迁卡记录

#298 验收通过（用户 2026-09-02 委派裁决：subagent 多模态验收 = 用户验收），卡片从 backlog P2 迁入本文件，原文：

```markdown
- [ ] **#298 LAN 部署下设置面 403——settings.* 属服务端特权名单，权限/预设默认档在真机 WiFi 场景静默降级** `dsh` `infra`
  - 2026-09-02 深调发现（源码+活体双证）：dsh-client-connection 有 PRIVILEGED_METHODS 15 项硬门禁（settings 全域/credentials 全域/llm.discoverModels/agentPreset.read·copy·openDocument·remove/host.pickDirectory·openPath），恒以空信任表校验——trustedHosts 也不解锁，只有 Host 头为 loopback 才放行；活体：settings.describe@LAN IP → 403，@127.0.0.1 → 200
  - 波及：#282-a/#283-a1 的 getPermissionDefault/setPermissionDefault/get/setDefaultAgentPreset（DshApiClient.kt:1150-1203）在真机 WiFi/Tailscale 连接下全部 403 → settingsNamespace 返 null → UI 回退已知三档（静默降级，无提示）；adb reverse（Host=127.0.0.1）不受影响——既有 E2E 均走 adb reverse 故未暴露
  - 方向：403/forbidden 错误形态识别 → 默认档 UI 显式标注「需 loopback 连接（adb reverse）」；不做 Host 头伪造绕栅栏（属安全边界）
  - → docs/research/2026-09-01-dsh-web-vs-android-gap.md §11.2
```

勘误：卡面「UI 回退已知三档」不准——实际 `currentValue==null` 早退导致**两区块整块消失**（本批修复后为保留渲染+标注）。#158 卡同步追记 35 连不复现。

