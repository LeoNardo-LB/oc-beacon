# DSH 权限/沙箱/审批体系全貌（v0.1.1-rc.2）

三源交叉：① `docs/api/dsh-openapi.yaml` + notes ② 官方客户端编译 JS（`/home/linuxbrew/.linuxbrew/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/`，下称 pkg）③ 活体 127.0.0.1:3080（rpcId perm-1…perm-10c；写探测仅用指定 test 会话）。

## 1. 旋钮数量与取值
- 底层 2 旋钮 + 1 派生层：`SandboxMode` = read-only | workspace-write | danger-full-access（pkg dsh-sandbox/lib/types/index.d.ts:19）；`ApprovalPolicy` = ask | never（pkg dsh-user-approval/lib/types/index.d.ts）；`permission preset` = 预设表键 + 派生 `custom`（非预设状态，只显示不可切，pkg dsh-permission-presets/lib/types/index.d.ts:52）。
- 预设 = name → {sandbox, approval, name?, description?}（同上 :38-47）。默认表仅 workspace-write+danger-full-access（pkg dsh-permission-presets/lib/index.js:78-95）；**本部署为 3 档**（settings enum + permissions 投影活体均 3 值）。read-only 档加入处的静态组合点未定位【待验证】。

## 2. 读写 RPC（openapi + 活体双验）
- 读·当前会话：session.list / session.history 尾页 `projections.values.permissions` = `{options:[{value,name,description?}],currentValue}`（yaml:5860-5867；活体 perm-6c）。
- 读·新会话默认：settings.describe ns=permission（活体 perm-4：enum=3 档，base=workspace-write，user=danger-full-access）。
- 写·当前会话：**POST /api/commands/execute**（typert 通道，不在 52 方法表）body 信封 method="commands/execute"，payload `{args:{agentId,line:"/permission <preset>",images:[]}}` → `{ok:true,value:{commandId,result:{kind:"success",text:"preset <preset>"}}}`（活体 perm-10b）。空参=查询当前档；未知名→kind:"error"（pkg dsh-permission-presets/lib/index.js:170-190）。commands/list 返回命令 roster（活体 perm-9a，含 permission 条目）。
- 写·新会话默认：settings.update ns=permission defaultPreset（pkg dsh-client-ui-permission-presets settings-store.d.ts:35-68；未实测）。
- ⚠️ **session.prompt 不派发斜杠命令**：apiproxy 实现直接 `agent.followup()`（pkg dsh-host-apiproxy/lib/index.js:2761-2781 无派发逻辑）；活体 perm-7b 实证 leading-/ 文本变成 user/message 进模型。yaml:749-751 的"dispatches a slash command"描述与活体不符（DISCREPANCY）。

## 3. 作用域
- 每会话：三个 knob 事件写会话日志（test 会话 seq 0-2 活体实证：permission/preset + sandbox/mode + approval/policy，会话创建即 pin，pkg index.js pinInitialPermission）。全局仅"新会话默认档"（settings ns=permission）。
- 中途可改：命令执行不开 turn、直接追加日志（pkg dsh-commands/lib/types/index.d.ts:102-138）；UI 锁定条件=会话失活（removed/inert/!live），**非 turn 运行中**（pkg dsh-client-ui-conversation/lib/client.js:3617-3620,3884-3889）。
- 进行中回合生效：sandbox 策略在每个操作边界重读会话状态（pkg dsh-sandbox-policy/lib/types/index.d.ts:15-21）；approval 有"live switch notices"（pkg dsh-user-approval/lib/types/index.d.ts approval/policy 注释）→ 结构上支持；回合中切换的活体验证【待验证】。

## 4. 变更事件帧
- durable（走 session/event）：`permission/preset {preset}`、`sandbox/mode {mode}`、`approval/policy {policy, source?:"delegation"}`（活体 seq 0-2）。
- 命令生命周期：`command/run {commandId,name,args,source}` + `command/done {commandId,kind,text}`（活体 14507-14508；本次同值切换 permission 层零追加=幂等实证）。
- 投影推送：session/projection key="permissions"（帧形同 yaml:5652 样本；权限键推送未单独采样【待验证】）。
- 交互审批（ask 档触发）：mux `approval/requested`/`approval/resolved` + POST /api/respond 回程（yaml:7772-7820,4347-4370）。

## 5. 官方 Web UI 位置与形态
- 包 `@deepseek-ai/dsh-client-ui-conversation`，组件 `PermissionSelect`：client.js:3332（定义）、:3884（装配 accessSelect）、:4089（与 plan 控件同排，composer bar 输入区控制行，输入框第一行下方左侧）。
- 形态=**下拉菜单**（Menu 向上弹）非分段控件：触发钮 glyph+当前档名+chevron；三档各有 glyph；label 规则 kebab→Title Case，danger-full-access→产品名 **"Full access"**（client.js:3318-3330）。档数=3+派生 custom（不可选，:3372 过滤）。
- **Full access 强制 RiskConfirmation 二次确认**（acknowledge 勾选后才可 confirm，client.js:3359-3388,3425-3440）。
- 写路径 `command("/permission <id>")` → session.command → typert commands/execute（client.js:10141-10146；dsh-client-runtime/lib/client.js:7365-7372）。
- 第二入口：设置页 PermissionRow（slot `settings.general.item`）改新会话默认档（pkg dsh-client-ui-permission-presets/lib/types/client/PermissionRow.d.ts:1-5,22）。

## 6. 用户三档与 DSH 档位映射
- 一一对应：read-only→read-only、workspace write→workspace-write、Full access→danger-full-access（UI 产品名即 "Full access"）。
- 每档实为 (sandbox × approval) 组合：workspace-write=(workspace-write,**ask**)、danger-full-access=(danger-full-access,**never**)（源码默认表）；read-only 档的 approval 值活体未暴露【推测为 (read-only, ask)，待验证】。
- 档数非硬编码：由部署预设表 Config.presets 决定；客户端应按 options 动态渲染。

## 7. oc-beacon 现状（app/src/main/kotlin/dev/leonardo/ocbeacon/data/api/dsh/）
- 已实现：session.prompt queue/steer（DshApiClient.kt:359-394）· approval/requested|resolved→PermissionAsked/Replied（DshEventMapper.kt:95-125）· /api/respond 回程（DshApiClient.kt:451-506）· settings 读最小映射（:813）。
- 缺口：permissions 投影未解析（session/projection→Ignored，DshEventMapper.kt:155）· 三 knob 事件→Ignored(POLICY_STATE)（:279-280）· command/run|done→Ignored(COMMAND)（:285）· **commands/list|execute 无任何引用** · listPendingPermissions stub emptyList()（DshApiClient.kt:474-477）。
- 要点：切换须走 commands/execute 新调用 + permissions 投影解析；现有 sendMessage（session.prompt）已被活体证伪为有效切换路径。
