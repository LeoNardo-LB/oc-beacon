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
