# DSH agentPreset 体系调研（v0.1.1-rc.2 三源交叉）— 2026-08-31

> 调研代理报告归档（443cfaa5）。源：本地 openapi 文档 + 官方源码（$DSH/config/agent-presets/ + deps/dsh-client-ui-agent-preset）+ 活体探测（agentPreset.list / session.list 410 条）。

## 1. 枚举（活体，roster order）
standard(标准模式) · code(PTC 模式, **isDefault:true**) · minimal(极简模式) · cordis(创造模式)。字段 id/trust:'system'/isDefault/name/description；顶层 authorable:true, hasDocument:true。「创造」= cordis 定论（cordis/preset.yml:1-2；Web 中文文案 dsh-client-ui-agent-preset/lib/client.js:89-90）。

## 2. 各 preset 实际改变什么（只改工具面+persona+skills；不改模型路由/沙箱/审批/持久化）
- standard：全量基线（文件/Shell/检索/Skills/计划/目标/子代理/工作流），252 行
- code：= standard 逐行不变 + 尾部 1 行 tool-presentation(mode:code)（工具经 Code Mode SDK 呈现），263 行
- minimal：重写双工具档——persona complete:true 固定提示词 + includeRuntimeContext:false；仅 persistent shell + str_replace_editor；**无上下文压缩**，88 行
- cordis：= standard + 3 处增改（见 §3）
- 模型路由四档相同（host plane 持有）

## 3. cordis（创造）特殊处理——有实质差别
1. 独有 tool-cordis 自指工具集：cordis_inspect/define/run/stop/undefine（读活体运行时、定义/运行/回滚临时插件，模型 JS 在 vm 对活运行时求值）；trust 等同 shell
2. 独有捆绑双 skill：editing-cordis-compositions + cordis-plugin-development（customSkillDirs 指向预设自带 skills/）
3. persona 换长文：讲授 host/agent 双平面、自建预设路径 ~/.dsh/.agent-presets/<id>/、禁改 shipped
定位：让 Agent 帮用户创作自定义预设的增强档（standard 超集）。

## 4. 锁定语义
- blank 判定 = 事件流无 turn/start（/plan、/goal 不破坏 blank）——dsh-host-apiproxy/lib/types/api-proxy.js:356-358
- 非 blank select → 错误 agent-preset-locked（"preset is fixed"）；错误族 not-found/conflict/invalid/read-only
- 成功 select 追加会话事件 agent-preset/selected {agentPreset} 并非 scoped 重发

## 5. 与 subagent 委派关系
子代理经 composeFrom 继承父 preset（bind 非 mount）；durable header 记录 preset id。subagent.list 的 mode(one-shot|continuable) 与 preset 无关。

## 6. 官方 Web 四 surface
① General 设置行（新会话默认，写 agent-presets 设置 ns default）② 新建会话屏 chip（workspace picker 旁，staged 选择：会话 current 且 blank 时经 select 落地，一次用尽）③ 会话头只读标签 ④ settings.section id=agent-presets 管理区（roster 卡+copy 对话框+cordis 虚线 add-card）。shipped 中文名按 locale 解析。list 不 loopback-pinned；read/copy/openDocument/remove 是 loopback-pinned。

## 7. oc-beacon 现状缺口
- DshApiClient.kt:528-529 listAgents 返回空（注释「待 UI 卡」）；无 select
- 错误码已备（DshEnvelope.kt:36-71 五个 agent-preset-*；DshApiError.kt:68-79 分类）
- DshEventMapper.kt:275 agent-preset/selected → Ignored（Tier2）
- DshSessionMapper.kt:19 running/blank/origin/agentPreset 无 Session 槽位

## 附：活体佐证
agentPreset 分布：code 绝大多数（含 test-lab）、standard、minimal、undefined ~6 条（字段引入前存量【推测】）。blank 会话可携任意 preset。
