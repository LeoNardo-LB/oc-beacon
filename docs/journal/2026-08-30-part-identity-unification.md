# part-identity-unification（2026-08-30）

> 状态：已完结（用户验收收卡 2026-08-30）
> 关联：#266（本批次即其实施，已迁出）· #265（竞态两层守卫的根因层收口）· #246（锚点稳定性受益方）
> 来源：用户指令「修复这几个问题」（#265 收尾时登记的遗留清单）

## 零轮 · 遗留清单定性

#265 收尾时登记的四项遗留，本批次逐项处置：

1. **part 身份双轨（#266 本体）**：完结替换后 part 仍会"改名"（派生 id → 服务端 id）
2. **contains 守卫理论误伤面**：真正新 part 的首段 delta 恰与既有文本逐字重合会被误丢
3. **reasoning registered append 无去重**（MessageMergeEngine L561 盲拼接）
4. **DB 存量脏行自愈依赖重同步**（E2E-1 的 116 字脏行）

## 一轮 · 真机 E2E 复现（活体抓到，三方取证）

- 复现环境：真机 e69a99d8 + debug-entry（server 617b2c29，opencode2 serve beta-18600）+ 会话 Dedup指令测试
- **12:15 诗歌轮复现尾段 ×2**（同 #265 E2E-1 症状）：渲染 = 服务器权威全文（185 字）+「答案。」+ 其三整段重复；**服务器 REST 权威文本根本没有「答案。」与第二遍其三**（185 字干净结尾）
- 三方取证：设备截图（渲染形态）/ Room 拉库（text 行恰为 69 字尾段残留）/ 服务器 REST（185 字全文）——重复内容是**客户端侧多出的**，非服务器数据
- SSE 全局事件流抓包（curl /api/event + x-opencode-directory 头；不带此头只有 housekeeping 事件）拿到完整一轮 22 事件序列，契约实测：**delta 与 ended 携带相同 ordinal（partId 恒等）**、无任何服务端 part id 字段、无 message.part.updated；delta 交付存在丢失（两批之间缺段）且 reasoning.delta 与 text.delta 交错到达，ended 全量值同 id 覆盖自愈
## 二轮 · 根因定性（两层，均为 #265 守卫盲区）

**A. 渲染层：idx>=0 终态 part 盲拼接（本次可见重复的直接根因）**

- 模型流式输出尾部自重复（「答案。」+ 其三再现），服务器把多余部分截断，权威全文只存干净 185 字
- 滞留尾 delta 在 text.ended 全量覆盖**之后** flush：partId 相同 → 已注册 → 绕过 #265 源头守卫（其分支条件是"partId 未注册"）→ applyDelta idx>=0 分支仅有 endsWith 去重，尾段非全文后缀 → 盲拼接 → 251 字
- #265 两层守卫（源头 isStaleDelta + applyDelta idx<0 包含判定）均只护"未注册"路径，注册路径零防护

**B. 落盘层：增量 append 从未真正追加（DB 脏行家族的真正根源，#97 H-6 时代引入）**

- 真机插桩（[DEBUG-p266] append 前后行回读）实证：appendPartText 每次调用后行 text **等于本次 delta**（9→17→17→13→10→11 逐批覆盖，非累加）
- 机制：appendPartTexts 先 dao.upsertMessages(骨架)（@Insert REPLACE = DELETE+INSERT），cached_parts FK onDelete=CASCADE → **每次 flush 级联删光该消息全部 part 行**，再只插回本批 delta
- 历史上行"看起来完整"全靠 step.started/step.ended 的 MessageUpdated 全量快照与 REST 同步兜底覆盖（payload JSON 行）；payload NULL 行恒为最后一批——#265 E2E-1 的"116 字脏行"、本批 12:15 轮的 69 字行、haiku 轮 28 字行，同一机制
- 重进会话即被服务端数据覆盖自愈（本批实测：E2E-1 那条 2927 字行尾句 ×1，无需手动清理——遗留 4 的处置结论）
## 三轮 · 修复（本批 commit）

1. **applyDelta 终态守卫**（渲染层，堵 A）：part 已注册且终态（time.end 非空，ended 全量值已落位）→ 丢弃一切后续 delta——ended 是官方 replayable full-value boundary，其后 delta 必为过期重放/服务器截断残留；Tool part 无终态语义不受影响
2. **contains 守卫收窄**（堵遗留 2 误伤面）：idx<0 重建前的过期判定（#265 引入）从"同 kind 任意既有 part 包含"收窄为"**终态** part 包含"——流式期未注册 delta 与既有 part 的内容重叠可能是合法新 part 首段（判丢弃=内容丢失），照常重建；handler 侧 isStaleDelta 源头过滤同步收窄，两路语义对齐。#266 身份统一后 partId 恒等，该守卫仅在换代漂移场景兜底
3. **reasoning registered append endsWith 去重**（堵遗留 3）：与 Text 分支对齐，消除盲拼接
4. **mergePart 身份回填**（#266 方向 A 落地）：合并结果保留 existing 的派生 id（existing.id.ifBlank { incoming.id }）——流式身份跨完结稳定，#246 锚点、Room 行键、未来一切 partId 键控逻辑不再站在漂移面上；改名即产 Room 孤儿行（upsertParts 只 REPLACE 不删缺席行）的机制随之消失。方向 B（全链路服务端 id）经实测评估搁置：当前服务器 text/reasoning 事件无服务端 id 字段，"服务端 id"本质仍是 ordinal 派生，方向 A 即可达成本目标
5. **insertMessagesIfAbsent**（堵 B，Room 层）：增量落盘骨架消息改 @Insert(IGNORE)——存在即跳过，FK 依赖保证不变、永不触发级联；元数据更新仍由全量快照路径负责；appendPartText 的 UPSERT 旧行引用同步表名限定（cached_parts.text），消除 DO UPDATE 内未限定列名的解析歧义

测试：MessageMergeEngineTest +9（终态守卫 text/reasoning、reasoning 去重、收窄后流式重复重建、终态包含保留、身份回填 text/reasoning/legacy 空 id）；MessageEventHandlerAppendOnlyTest +2（同 id 终态滞留 delta 不拼接活体复现回归、流式未注册重叠重建）；#265 既有 2 例前置修正为终态语义（收窄后完结替换必为终态）。全量 2173，唯一红 #261（环境夹具，基线一致）。

## 四轮 · 真机 E2E 复测（V3 实机走查，本批自验）

- 干净构建装机后同款 prompt（三段诗 ×4 + 俳句 + 算术思考题等）连发 7+ 轮：渲染尾段均 ×1、无残渣；BACK 后重进会话（Room 种子路径）渲染完整
- Room 拉库逐字比对：本会话**全部 17 条助手消息 text 行与服务器 REST 逐字一致**（82/55/226/255/41/185/1788/2022/1473/2927/353/315/299…），历史脏行已全部对齐
- **思考块展开走查**：算术题触发可见思考 → 展开折叠卡（截图取证）→ 内容连贯无重复段；reasoning 行 payload 254 字与服务器 reasoning 逐字一致、尾串 ×1
- SSE 抓包全程无异常事件序

## 五轮 · 验证分档勘误（方法论注记）

初版把「尾句 ×1 / 重进会话完整 / 思考块无重复」列为 V6 用户验收项——**分档错误，已修正**：按 verification-requirements.md，V6 人工验证仅限**时间性/主观现象**（闪烁/动画/计时/布局跳动），内容正确性属 V3 实机走查，Agent 必须自验。三项均已在四轮补齐实机证据（如上），本批**无遗留 V6 项**。AGENTS.md 要求的 verification-before-completion 完成前核验亦于本节补做。

## 验收收卡（2026-08-30）

- V1-V3 自验证据复盘（见四轮）：全量单测 2173（唯一红 #261 环境项）；真机七轮渲染尾段 ×1；17 条助手消息 text 行与服务器逐字一致；思考块展开 reasoning 行 254 字与服务器一致尾串 ×1；重进会话完整——无 V6 时间性/主观验证遗留
- 验收：用户阅读两轮验证报告后指令「清理可以结束的卡片」（本卡验证闭环、无剩余工作）→ 当场迁移入册
- 关联收口：遗留 4（E2E-1 脏行）经 B 层根因修复（FK 级联）后家族性消失，设备存量脏行已由重同步自愈实证
