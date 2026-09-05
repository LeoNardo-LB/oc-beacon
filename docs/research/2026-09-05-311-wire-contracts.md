# #311 批3 子项 wire 契约钉死（2026-09-05,源码取证）

> srv=/home/linuxbrew/.linuxbrew/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai;mod=/tmp/dshweb/mods

## ①-a 归档
- 'workspace/archiveSession' {sessionId}→{archivedSessionIds:SessionId[]}（**集合替换式**;workspace-controller types.d.ts:101-107,typert:28）
- 归档态**不在 session.list**（SessionSummary 无 archived 键,types.d.ts:138-147）;走 workspace follow 流:baseline {items,archivedSessionIds}+增量 {type:'archived',archivedSessionIds}（types.d.ts:110-129）
- web:会话行菜单"archive"→uiWorkspace.archiveSession→controller（mod29:927/2111/77;mod05:319）

## ①-d 多 workspace
- workspace.list→WorkspaceView{workspaceId,path,title,sessionIds[],createdAt,updatedAt}（名字键=title!types.d.ts:8-19）
- 归属=sessionIds 显式数组(host 维护);session 行无 workspaceId 仅 cwd?;未入组=stray 按 cwd/recency 兜底（mod29:381-383）
- web 连接语义:connectWorkspace 优先复用 blank+cwd===path+在组+未归档会话,否则 sessions.create({workspaceId})（mod29:46-58;session types.d.ts:244-249）

## ② deliverables（client-only 转录 fold,无 RPC）
- 三事件:turn/start(定 turn id)·tool/call(记 mutationPath)·tool/result(**surfaceOp==='append' 且 content[0].isError!==true 才计**;callId↔message.source.callId,mod28:133-172)
- 产出判定=成功写类工具的 args 路径:write(file_path+content)/edit(file_path+old/new)/str_replace_editor(create/str_replace/insert)（mod28:59-93）;首见序去重;turn 尾槽文件列表,空不挂载

## ③ 工具卡
- ask_user_question:工具名恒 'ask_user_question'（dsh-tool-ask-user index.js:15-16）;web toolview slot 同名（mod25:1646）;未答=交互问卷,已答=普通 ToolRow
- skill:工具名 'skill' 参数 {name}（dsh-tool-skill index.js:59-65）;result.content=指令全文;web 折叠卡 data-tool="skill"（mod32:107/233）
- 目录:skills/list({sessionId})→{skills:[{name,description,whenToUse?,modelInvocable}]}（session types.d.ts:200-218）
- 两者皆标准 tool/call|result 事件按 name 分派,Part.Tool 渲染 name 路由即可

## ④ 等待审批/提问状态点
- 真服务器**不推** approvals/questions（SessionControlFrame 仅 baseline/queue/jobs/projection,types.d.ts:471-484）
- web=客户端本地 PendingInteractionDomain,由 waterfall 事件 approval/request 与 user-questions/request 驱动（mod14:28-67;mod21:281;mod43:880）;session 行指示 pendingInteraction∈{approval,plan-review,question}（mod29:384-393）
- app 等价=响应既有 PermissionAsked/QuestionAsked 事件(已接),非 list/control 字段

## 实现序建议
workspace 数据层(archive RPC+follow+建模)→归档 UI(行菜单/左滑)→多 workspace UI→deliverables fold→工具卡(question/skill)→FSM 待审批态(承重规则先读架构文档)
