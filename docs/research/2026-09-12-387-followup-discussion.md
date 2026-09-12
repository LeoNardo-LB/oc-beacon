# #387 后续专题讨论底稿（2026-09-12）

> 用途：为 #387 的后续「专题讨论」提供持久上下文——已落地什么、两端一手事实、待确认的指代、候选方案与风险。讨论结论若定方案，按 spec 约定另立 spec/卡。

## 一、已落地（本卡最小修复，勿重复实现）

- `DshEventMapper.mapUserMessage`：无 `source.kind` 且整条恰为一个闭合 `<system-reminder>` 块 → `injectionKind="context"`（映射单点，提交 `5b139e9a`）；实况/通知/未来消费者共用。
- 渲染层对**历史 Room 行**保留同判据兜底 `SystemInjection.isPureReminder`（历史行不经映射路径）。
- 单测：`DshV3AdaptationTest`（纯块 → context；闭合块+真问句混合 → null）。
- **局限**：本环境无 live「无字段」样本，该路径仅单测覆盖（DB 10 条纯 reminder 全带 kind；V2 宿主 `instructions/entries` 为空、`/api/event` 90s 无注入帧）。

## 二、事实基线（一手源，2026-09-12 调研）

- **dsh web**：注入判据 = `user/message.source.kind != "user"`（含缺失）→ context 节点；呈现 = `ContextInjectionRow`（折叠 DisclosureRow：图标 + 标题 + 来源 label + 一行 summary，展开体按 `source.form ∈ {instructions, catalog, snapshot, notice, relay, recall}` 结构化，限高 141px 滚动、2 万字符截断）；解析 **all-or-nothing**，失败整体回落 OpaqueBody（全文 + source 字段表）。dsh 源码注释：reminder 框架「cannot be separated from content」。
- **opencode web/app**：注入判据 = `TextPart.synthetic`；用户气泡只渲染**第一条非 synthetic** text part，synthetic 直接**隐藏**（混合消息 part 级天然只显真文）；工具收集上下文折叠成「Gathered context」组。
- **共同点**：两端都**不裸输出**注入内容（一个收起可展开、一个隐藏）；区别在暴露程度。
- **我们**：V2 服务器两类字段都不发 → **字段优先 + 嗅探兜底** 是唯一可行路线（现状）。

## 三、待确认：先把「agent 的内容」指代对齐

| 指代 | 现状 | 直出（不包裹）的后果 |
|---|---|---|
| **A. 上下文注入**（`<system-reminder>` 那类） | 无 kind 时折叠成 EventCard 小卡 | 回到文字墙 —— 本卡原始症状 |
| **B. 子智能体（subagent）输出** | 包在「子智能体卡」里 | 像普通消息直出，但失去来源/折叠/长输出治理 |
| **C. assistant 正文** | agent 气泡（角色/时间/状态承载） | 丢角色与状态语义，不建议 |

## 四、「是否用 dsh 来进行」的两种读法

1. **形态由服务器字段驱动**（dsh 的 `source.kind`/`source.form` 模型）——方向正确，客户端已在做「字段优先」；但 **V2 服务器不发这些字段**，V2 面只能嗅探兜底，无法完全用 dsh 的方式。
2. **接入 dsh 的某个「直出」通道**——当前 DSH 协议里没有「不包裹/直出」这类标记（能用的是 kind/form）；若指其他通道，需点名后再查。

## 五、候选方案（按指代）

- **A-① 对齐 dsh 薄壳**：折叠头一行（标题+来源 label）＋ 按 form 的结构化展开体。需把 source 字段从 wire 透传到 `Message/Part`，新增 form→行模型纯函数 + UI；收益主要在 DSH 面，V2 无字段收益有限。工作量中。
- **A-② 对齐 opencode synthetic**：`SystemInjection` 增「闭合块区间提取」纯函数，混合消息拆成「折叠小卡 + 真文气泡」。与 SSE 高度补偿、跳转 key（`chatEntryKey`）、part id 有交互，需专项回归（流式/跳转/搜索命中）。工作量中偏大，**建议单独立卡**。
- **A-③ 裸输出（不包裹）**：不建议——就是回到文字墙，且 dsh/opencode 都不这么做。
- **B 子智能体直出**：信息架构调整。先定三件事：直出后如何标源（谁说的）、长输出如何折叠、跳转/搜索命中如何算；建议先原型或 spec。
- **C 去 agent 气泡**：不建议（丢角色/时间/状态语义）。

## 六、讨论议程建议

1. 明确指代：A / B / C（或多选）。
2. 明确「用 dsh」：字段驱动 vs 其他通道。
3. 选方案 + 定验证矩阵：V2 无 live 样本，需先定如何构造无 kind 样本（DB 注入 / mock 服务器 / 等真实样本）。
4. 决定是否单独立卡（A-② 已建议独立卡）与是否写 spec。

## 相关文件

- 卡：#387（`[~]`，待本专题结论）
- 调研：`docs/research/2026-09-12-ux-research-405-401.md`（#401/#405 部分）
- journal：`docs/journal/2026-09-11-post-refactor-backlog-triage.md`（#387 各节点、第二批 web 端调研）