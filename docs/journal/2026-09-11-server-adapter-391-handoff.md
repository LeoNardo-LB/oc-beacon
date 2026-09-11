# ServerAdapter 架构批次 · 上下文手册（Handoff）

> 生成时间：2026-09-11 · 分支 `master` · HEAD `16e95294` · 工作树干净 · 提交均本地未推送（`origin/master`）
> 目标：goal-5b50d96d-759b-4090-a8d2-52ef24aad414（**active**，revision 7，rounds 18/256）

## 0. 一句话

按 backlog #391 与 `docs/specs/2026-09-10-server-adapter-architecture-design.md` 实现 **ServerAdapter 服务器类型插件化架构**（9 切片，契约前两片冻结；含 DSH 0.1.5/V3 适配）：架构主体 + 四条静态 lint 规则 + 两轴 code-review + OpenCode V2/DSH V012 双面模拟器 E2E **均已完成并验证**；仅余 DSH V3 **P1 渲染补全**（#398）与少量取证依赖项。

## 1. 权威文档与入口

| 用途 | 路径 |
|---|---|
| 本批唯一权威 spec | `docs/specs/2026-09-10-server-adapter-architecture-design.md` |
| 批次执行 journal（逐切片证据） | `docs/journal/2026-09-10-server-adapter-architecture-391.md` |
| DSH 0.1.5 影响研究（§7 P0/§8 修复清单） | `docs/archive/2026-09-10-dsh-0.1.5-rc1-impact.md` |
| V3 事件载荷实况取证（#398 实现依据） | `docs/research/2026-09-11-dsh-v3-event-payloads.md` |
| 架构文档（含适配层章节 + 白名单规则） | `docs/architecture.md`（「服务器适配层」节） |
| 待办卡片 | `backlog.md`：**#391 #397 #398 #399 #400**（#396 已修未迁） |
| 静态门禁实现 | `lint-checks/src/main/kotlin/dev/leonardo/ocbeacon/lintrules/` |

## 2. 已完成（41 commits：`193ff675..HEAD`）

### 2.1 九切片状态

| 切片 | 状态 | 要点 |
|---|---|---|
| 1 领域契约+注册表+多绑定 | ✅ | `ServerAdapterResolver`（能力/世代/传输种类/插槽）；`ServerAdapterRegistry` 构造期校验覆盖 |
| 2 能力派生+核心标志 | ✅ | `ServerPorts.derivedFeatures()` + `CoreFlags` + `privateFeatures`；删集中矩阵 |
| 3 五类私有能力端口化 | ✅ | subagents/goals/feedback/queue/serverSettings；仓库守卫删除 |
| 4 删手写路由门面 | ✅ | 调用方经 `adapters.ports(conn)` |
| 5 界面插槽注册表+私有界面迁移 | ✅ | `ServerUiExtension`/`ServerUiSlotRegistry`/`LocalServerUiSlots`；PROVIDER_SETTINGS/SERVER_SETTINGS/SESSION_LIST_HEADER 三槽迁移 |
| 6 连接策略抽取 | ✅ | SSE/MUX 两实现；`probe` 统一一次握手；监督层去类型 |
| 7 按代词汇+V3 适配 | 🟡 P0 ✅ | 未知词汇 `UNKNOWN_DEGRADED` 不再拒绝重建；surfaceOp 越界→`STRUCTURAL_VIOLATION`；assistant-stream 实时流；**P1 渲染部分见 §4** |
| 8 收尾静态强制 | ✅ | 四条 Lint 规则 + 版本探测器去类型 + 选择器注册表驱动 + 契约测试 + 架构文档 |
| 9 统一 UIUX | ✅ 机制 | 区域插槽 + **条目动作注册表**（`ServerActionContribution`/`ServerActionRegistry`）+ 令牌门禁；审计 BAD 项已归零 |

### 2.2 关键类型与文件

- 领域契约：`domain/adapter/ServerAdapterResolver.kt`（+`TransportKind`）、`domain/model/{ServerFeature,ServerCapabilities,CoreFlags,ServerUiSlot}.kt`
- 数据适配层：`data/adapter/{ServerAdapter,ServerPorts,ConnectionStrategy,ServerAdapterRegistry,ServerAdapterModule,OpenCodeServerAdapter,DshServerAdapter}.kt`
- DSH 端口薄委托：`data/adapter/dsh/{DshConnectionStrategy,DshGoalPort,DshFeedbackPort,DshSubagentPort,DshQueuePort,DshWorkspacePort,DshReferencePort,DshAttachmentPort}.kt`
- OpenCode：`data/adapter/opencode/OpenCodeReferencePort.kt`
- 新端口契约：`data/api/{workspace/WorkspaceApi,reference/ReferenceApi,attachment/AttachmentApi}.kt`
- 界面插槽/动作：`ui/extension/{ServerUiExtension,ServerUiSlotRegistry,LocalServerUiSlots,ServerActionContribution,ServerActionRegistry,LocalServerActions}.kt`
- DSH 私有界面：`ui/screens/{server/providers/dsh,sessions/dsh,components/dsh}/`
- 静态门禁：`lint-checks/.../ServerTypeWhitelistDetector.kt`、`ServerTypeUiBoundaryDetector.kt`、`TokenBypassDetector.kt`、`SpacingTokenBypassDetector.kt`、`BeaconIssueRegistry.kt`

### 2.3 关键验证结论

- **能力唯一真相 = 端口可选性**：`能力 = CoreFlags + derivedFeatures(端口) + privateFeatures`；`workspace` 端口派生 `WORKSPACE`+`SESSION_ARCHIVE`。
- **两条 seam 契约测试**：同一套断言跑真实+假适配器；seam-1 = 启用贡献的槽位必须在适配器 `uiSlots` 内。
- **两轴 code-review**（Standards/Spec）已完成并修正：白名单文件级收窄、插槽两级门禁全覆盖、`DshServerAdapter` 改注入 `ServerSettingsRepository`。

## 3. 验证与门禁（当前全绿）

```
./gradlew :app:compileDevDebugKotlin          # 编译
./gradlew :app:testDevDebugUnitTest --rerun    # 全量单测（3273+ 例）
./gradlew :app:compileDevDebugAndroidTestKotlin
./gradlew :app:lintDevDebug                   # 主门禁：0 error（257 历史 warning 在 baseline）
./gradlew :app:lintDevRelease                 # CI 变体：BUILD SUCCESSFUL
./gradlew :app:updateLintBaselineDevDebug     # 仅存量变化时：整文件再生 baseline
./scripts/backlog-check.sh                    # backlog 机械不变量
```

- `app/lint-baseline.xml`：**0 条 error 存量**（四条规则均零存量）；仅历史 warnings。
- 已知 flake：`RenderSupplyCoordinatorTest T11` 全量偶发、隔离必绿（跨类污染，与本批无关）。
- 纪律：Gradle **禁止并发**（同一 checkout）；`set -o pipefail`（曾因裸 `| tail` 掩盖编译失败）；编译 120s / 单测 180s / 全量 300s 超时。

## 4. 剩余工作（目标保持 active）

### #398（最高优先，DSH V3 P1 渲染）

- ✅ 已做：`user/message` 的 reasoning/tool-call 块不再丢弃；`system/message` → 注入类精简卡（`mapSystemMessage`）+ 真机实测。
- ⏳ `deliverables/presented`（15 例）→ 接既有 **client-only** 折叠面 `ui/screens/chat/tools/TurnDeliverables.kt`（当前从工具调用 args 折；改由服务器权威事件供给）。载荷见研究文档。
- ⏳ `subagent/catalog`（16 例）→ 先厘清与既有子智能体目录投影是否双源。
- ⏳ `feedback/message-put|message-delete`（归档 0 样本）→ 需新会话取证。
- ⏳ 按代事件词汇表（spec 切片7 结构项；当前为单体 `when`）。
- ⚠️ **`assistant/attempt` 维持静默**（692 例中 681 例后随 `llm/retry`，是瞬态尝试；逐条渲染会刷屏）——不要按旧研究文档把它当终态错误渲染。

### #400（E2E，已基本完成）

- ✅ OpenCode V2 面 + DSH V012 面模拟器 E2E 均通过（连接/列表/转录/实时流/FAB 能力过滤/404 优雅降级/无崩溃）。
- ⏳ 未覆盖：V3 五类事件**渲染**（即 #398）；无独立 DSH 靶机时的真机面。

### #397 / #399

- #397：四条规则已落，0 存量 error。**已无必需动作**（可待用户验收后迁移）。
- #399：条目动作注册表 + 令牌门禁 + 审计 BAD 归零**均已完成**；待验收迁移。
- #396：devDebug lint 4 项存量已修（journal 切片8上），卡片仍 `[ ]` 未迁。

## 5. 环境手册（可复用）

### 5.1 服务器

| 服务器 | 端点 | 凭据 |
|---|---|---|
| OpenCode | `http://127.0.0.1:4199`（HTTP 200） | user `opencode`；password 在 `/persistent/home/leo-tkp/.config/opencode/service.json` |
| DSH 0.1.5-rc.1 | `http://127.0.0.1:3080`（401 需 token） | token 在 `~/.dsh/token`（43 字符） |

### 5.2 模拟器 E2E（无头）

```
# 启动（绝对路径，后台 shell 的 PATH 无 emulator）
/home/linuxbrew/.linuxbrew/share/android-commandlinetools/emulator/emulator \
  -avd Pixel6_Android36 -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -no-snapshot &
adb wait-for-device   # 直至 getprop sys.boot_completed=1

# OpenCode 面
./gradlew :app:assembleDevDebug
adb -s emulator-5554 install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
./scripts/debug-entry.sh emulator-5554 dev.leonardo.ocbeacon.dev

# DSH 面（关键：debug_token 注入通道）
adb -s emulator-5554 reverse tcp:3080 tcp:3080
TOKEN=$(cat ~/.dsh/token)
adb -s emulator-5554 shell am start -n dev.leonardo.ocbeacon.dev/dev.leonardo.ocbeacon.MainActivity \
  --es debug_url http://127.0.0.1:3080 --es debug_name Local-DSH \
  --es debug_server_type dsh --es debug_token "$TOKEN"

# UI 断言（仪器级，无需视觉）：uiautomator dump + grep text=；logcat 查握手/映射
adb -s emulator-5554 shell uiautomator dump /sdcard/ui.xml
```

- 真机无线调试不可用：机场公共 WiFi 客户端隔离（10.3.2.3 ARP FAILED）——用模拟器。
- DSH 本机缺 `settings.describe` / `pluginInventory.list`（404）→ 设置页 DSH 私有区块自门控不渲染属**预期降级**。

### 5.3 DSH V3 载荷取证（#398 用）

```
find ~/.dsh/sessions -name 'session.v3.jsonl.zstd' -print0 | xargs -0 -I{} sh -c 'zstd -dc "$1"' _ {} | \
  awk '/"type":"deliverables\/presented"/ { print; exit }'
```

## 6. 踩坑与纪律（务必遵守）

1. **Kotlin 块注释可嵌套**：KDoc 里写 `ui/**` 或 `Dsh*/OpenCode*` 会开/闭嵌套注释吞掉代码——改用 `ui 目录`、`Dsh / OpenCode` 措辞。
2. **value class 不能作 vararg 参数类型**（`ServerFeature`）——测试用 `Set<ServerFeature>`。
3. **Gradle 禁止并发**（同一 checkout）；**必须 `set -o pipefail`**——裸 `| tail` 曾把编译失败变成 exit 0。
4. **`.gitignore` 有 `lint/` 规则**：lint 规则包名用 `lintrules`（不是 `lint`），否则源码被忽略。
5. `updateLintBaseline` 会**整文件再生** baseline（含版本头/warnings）——只在确有存量变化时运行，diff 会很大。
6. `:lint-checks` 是**工具链模块**（不进 APK），spec Out of Scope「不拆模块」针对应用层，不冲突（评审已确认）。
7. **术语**：`回退` 禁表 revert（用 `撤销`）；提交带 type 前缀；backlog 卡片只经 `./scripts/backlog.sh` 改动。
8. 新日志用 `AppLogger`（非 `android.util.Log`）；导航参数用 `NavUtils.safeDecodeParam`；远程路径用 `PathUtils`。
9. **`cacheable` baseline 教训**：自定义 lint 规则的包名/服务文件改动后，务必用临时探针验证规则真的加载（`lint found N error`）。

## 7. 下一步建议（按优先级）

1. `deliverables/presented` → `TurnDeliverables`（自包含，边界清晰）。
2. `subagent/catalog` 双源核对 + 落地。
3. `feedback/message-*` 新会话取证后补映射。
4. 按代事件词汇表（结构项，较大重构，回归风险高——先补契约测试再动）。
5. 全部完成后：跑 §3 全套 + 真机/模拟器验收，再考虑 mark goal complete / 迁移 #391 等卡片。

## 8. 交接检查清单

- [x] 工作树干净，HEAD `16e95294`，41 commits 本地未推送
- [x] 全量单测 / lintDevDebug / lintDevRelease / androidTest 编译 绿
- [x] lint baseline 0 error 存量
- [x] OpenCode V2 + DSH V012 模拟器 E2E 通过
- [x] journal 逐切片证据、研究文档、卡片状态同步
- [ ] #398 剩余四类渲染 + 按代词汇表（下一轮）
- [ ] 用户验收后迁移 #391/#397/#399/#400/#396 卡片
