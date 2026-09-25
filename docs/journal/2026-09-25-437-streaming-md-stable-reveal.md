# 437-streaming-md-stable-reveal（2026-09-25）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 阶段 A-D 实施与真机验证（2026-09-25 下午批次）

**交付**（5 commits，含 cd0a879b）：
- 阶段A：SafePrefixGate 纯函数两级放行（空行毕业/标记回退/setext 扣末行）+ 27 判定用例 + pilot 接线 + STABLE_REVEAL_PILOT 开关（dev先行）
- 阶段B：HeldTailAgingState 超龄状态机（300ms/500ms 节流，8用例）+ HeldTailReveal 锁高降亮区（呼吸光标+min(natural,locked)裁剪）
- 阶段C：gate 归一化增量前移——表格粘边空行注入（与 ensureBlankLineBeforeGfmTables 同判定，幂等）+ 完结变换字符扣留（tasklist ☐☑✅ 与 $$）
- 阶段D：FlapDetector 非前缀风暴探测+重建限频（5用例）+ MDPilot 观测日志（gate放行量/扣留量/超龄时长）
- 真机崩溃修复：流式 snapshot 失配帧（AST[3,29] vs content len0，#432 同族）——heading2-6 越界兜底组件（SafeHeading+safeHeadingText）+ pilot 分支 key(state) 强制实例重建消帧；heading1 的 ATX 提取同护
- 降亮分级：纯文字扣留尾全亮（转正字面无缝=V6 体感），含标记才 alpha 0.5
- 补修 5 处令牌门禁遗漏（Motion/SpacingTokens，含 #435 引入的 2 处）

**真机证据**（小米 houji，DSH-3080 通道）：
1. 流式管线全链路：gate 按批放行（9/36/99/1051/5857ch）→ 库 stable AST 增长（3→6→9→12块）→ 库内 unstable tail≈0（第一道闸生效铁证）→ 降亮区 300ms 超龄触发（heldMs 305-380）
2. markdown 混排回复：H2/粗体/表格/代码块成型完整，held 20ch 完结 flush 上屏（EOF 一字不丢）
3. 贴底构型：drop(fii=0∧fiso=0) 免派发；尾段钉固构型（swipe 300px）：36 次增长全部 reading-away 免派发，视口零扰动
4. 帧差录屏（22s@10fps，220帧）：无基线两态往返翻转对（~350px@2-5Hz 签名消失）；尖峰 7-10 均为单调增长事件（500ms 锁高刷新/空行毕业），尖峰后 0.1-0.4 静默带=新内容稳定停留
5. 全程 0 FATAL（修复前 heading 路径必崩——重放历史即复现）
6. 全量单测绿（SafePrefixGate 27 + HeldTailAging 8 + FlapDetector 5 + 既有回归）；lint 5 error 清零

**环境备忘**：opencode serve 49374 密码随机生成（/tmp/service-test.json + OCBEACON_SERVICE_JSON 覆盖）；DSH 通道=debug_server_type dsh + debug_token(~/.dsh/token)；reverse 4199→49374（debug-entry.sh 默认 4199→4199 需手改）

**V6 人工项**：流式观感（纯文字全亮流出/标记内容按块成型+降亮区过渡）；完结切换瞬间（阶段C 高度差吸收实测）

## 验收三 bug 诊断与修复（2026-09-25 晚批次）

**Bug 2（表格不能复制）——根修 22f3e68d**：
- 定罪链（/diagnosing-bugs 全流程）：dump long-clickable=true（可达性在）→ 探针（事件到达 cell 但 combinedClickable 零触发）→ 双 pass 探针（Main 相 Press consumed=true）→ 移除法（去掉 cell 内 clickableMarkdown 后长按立即复活）→ **根因：clickableMarkdown 的 detectTapGestures 在等待 tap 期间消费 down，同树外层手势（表格 cell combinedClickable 长按菜单 + SelectionContainer 长按选择）全部失效**
- 考古定罪：#429 交付版（d3462892）装回实测长按同样死——菜单**从未在真机工作过**（#429 只验证了 TSV 单测）；用户此前的复制能力=逐字选择，被 #429 的 DisableSelection 移除——「不能复制」实为能力真空
- 修复：clickableMarkdown 手势改为等价轻量实现（awaitEachGesture + awaitFirstDown(requireUnconsumed=false) + waitForUpOrCancellation，仅在命中链接时消费 up）——down 透传，长按/选择语义在外层复活
- 真机双实证：长按表格 → 「复制单元格/复制表格（TSV）」菜单 ✓；长按正文 → 选区把手 + 复制/全选/朗读工具条 ✓
- 注入有效性对照：launcher 图标长按菜单可弹（motionevent 注入有效，排除测试伪影）

**Bug 1（钉固构型闪烁后回位）——修复 8bd5ead1**：
- 根因：转正（空行毕业放行）时降亮区收缩（held 同帧）与 Markdown 正文扩张（snapshot collectAsState 下一帧）两帧错位 → item 净高先减一帧再长回 = 视口单次往返
- 修复：pilot 中 held 收缩侧延一帧（withFrameNanos 等扩张帧落地），增长侧不延——净高度从此单调
- 真机实证（6fps 帧差+方向判定）：钉固后序列无往返对；可疑连续对（11.2/10.3）经净差判定=持续增长（40→43=14.9 增大非归零）✓；fling 后规律单帧增长（500ms 锁高量子）+静止带
- 用户补充确认场景：流式非贴底（上拉阅读）时闪烁——正是此构型

**Bug 3（fling 中视窗跟随 SSE 下移）——未复现，登记观察**：
- 引擎排查清白：flush 任务 isScrollInProgress 每帧 rebaseAll（弃配）✓；GUARD reanchor 0 次；MSGEFFECT 竞态窗口分析（fling 等待+复验 autoScroll）均安全
- 合成复现两轮（80-120ms 快 fling + 流式）未现持续拖动（帧差无连续大差串）
- 推测：可能为 bug1 转正跳变在 fling 中的感知（已修），或需真手指特定时序——待用户验收观察，若复现按 [DEBUG-drift] 日志路径深挖

**顺带登记（未定位）**：真机两次内容区黑屏（渲染层卡死，force-stop 恢复，低频，出现在长操作序列后）——与 #437 改动关联未证实，观察中

**验证**：全量单测绿；0 FATAL；SGR-435 引擎工作正常（39-55 drop 贴底/读历史免派发）

## 验收四~六轮：用户高度固定模型落地与引擎底层重构（2026-09-25 晚）

**用户数学模型（定案）**：reverseLayout 不变量 `scrollPos(t) − ΔH(t) = S₀`——settle 后视口相对「settle 时刻内容底」钉死。绝对位置固定（LazyList 默认）=跟随的充要条件，非不变量。

**落地链（8596515b + b0c90d27）**：
1. 配对规则全域化：增长源在锚之下（itemIndex ≤ anchorIndex）即 +Δ，仅贴底原点（fii=0∧fiso=0）物理免派发。撤销两轮错误修复（深处免派发/视口 offset 锁——都把绝对位置固定当不变量）
2. gate 放行量子化 ≤400ch/批（单帧 1830px 暴涨→≤330px，防 LazyList 锚定校正的吸底-弹回 LEAP）
3. 派发改渲染前反射 requestPosition（对齐 #427 引擎先例：待定位由下一遍 measure 原子消费+拒绘一帧），替代 dispatchRawDelta（渲染后修正=先画增长态再跳位=闪烁，用户定罪「像做补偿而非计算好 set 进去」）
4. **引擎底层重构（用户授权）**：ChatScrollController 流式期间（sessionMeta.isStreaming）MSGEFFECT/GUARD/rearm 全面静默——原为非流式异步增长设计，流式期间运行=每-part requestScrollToItem(0) 拉底（上滑瞬间被拽回）+守卫把配对位移当离底漂移重锚（拉锯震荡）+rearm 在流式结束瞬间补拉（「输出完成后拉一段」）

**测试**：Ledger 用例更新至公式语义（贴底原点/深处/读历史/不在布局四分支+量子化截断用例），全量绿；0 FATAL

**验证局限登记**：adb 注入 swipe 在流式高频重组（48ms/批重排）中被手势取消，始终无法脱离贴底完成非贴底机内验证（真手指不受影响——连续压力事件流）；链路各环节（配对计算/反射 set/引擎静默）逻辑闭环+单测覆盖，**待用户真手指验收**：发送→10s→上滑脱离→静置阅读，视窗应纹丝不动；logcat SGR-435 pair d=.. set(fii, fiso) 行可见每次补偿
