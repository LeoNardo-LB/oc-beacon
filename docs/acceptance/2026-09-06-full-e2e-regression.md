# 2026-09-06 全功能端到端真机回归——总报告

- **范围**:本会话交付的全部功能(#309–#337 原批+级联续批+#334 回归+#329 平价),三步法全流程(checklist v2 双轮纯净审查 PASS → 六波纯净执行)
- **方法**:docs/ai-acceptance-workflow.md 三步法;六波(W1 会话树/W2 消息卡/W3 队列流式/W4 设置工作区/W5 配对通知/W6 通用清点)严格真机串行
- **设备**:192.168.110.239:5555(MIUI houji);构建 11:14:11(W1-W2)→18:39:55 含 A2 修复(W3-W6)→**22:04:17 终版(含全部三修复)**
- **主线健康**:六波 FATAL=0/ANR=0(logcat 合计 ~189 万行+crash buffer 六件 0 字节);服务器条目恒=2;非测试会话零触碰;数据零丢失(H2 integrity=ok)
- **checklist**:`2026-09-06-full-e2e-regression-checklist.md`(55 item 全终态,0 待填)· 波次摘要 `e2e-2026-09-06/W1~W6-notes.md` · 证据 `/tmp/e2e-full/`(xml/png ~500 项+录屏 10 段+各波 logcat)

## 总记分板(55 item)

| 波 | 域 | ✔ | 修复闭环 | 改判非缺陷 | BLOCKED(预案内) | 环境✘ |
|---|---|---|---|---|---|---|
| W1 | A 会话树/列表/导航 | A1※A3 A4 A5 A6 A7 A8 +A2 | A2(行升顶滞后) | — | A1 宿主向量(主测补✔) | — |
| W2 | B 消息卡片与交互 | B1-B7,B9-B15(14) | — | B8(批准续跑=模型行为) | — | — |
| W3 | C 队列/输入 + F 流式 | 全部 8 | — | — | — | — |
| W4 | D 搜索/设置/工作区 | D1-D7(6+核心) | D8①(mkdir 双缺陷) D2①(Add 死代码) | C3 跳转(措辞笔误) | D8②③(order/remove 无 UI 面=数据层分类) D9(契约休眠) D10(注¹) | — |
| W5 | E 配对/通知 | E1 E2 E3 + E4①③ + E5(撤除/派生) | — | E3c(断开撤除=超契约发明) | E3d(注¹) E5②(tap 同 E4②) | E4②(MIUI 组卡 adb 不可达) |
| W6 | G 通用 + H 清点 | G1 G2 G3 G5 G6 H1 H2 H3(8) | — | — | — | G4(4199 服务端模型故障) |

**合计:39 ✔ + 4 修复闭环 + 3 改判非缺陷 + 5 BLOCKED(全部预案内:契约休眠/环境无场景/数据层分类) + 2 环境✘(服务端侧) + 2 待人工(E4②/E5② tap 直达)**

## 修复闭环(本轮 E2E 直接产出,全部 TDD+全量单测绿)

| # | 缺陷 | 根因 | 修复 commit | 设备验证 |
|---|---|---|---|---|
| A2 | 旧会话活动后列表行 >86s 不升顶(≤~5min 缓存才动) | web 在 api-session/activity 以 updatedAt 单调合并即时重排(mod04 handleSessionActivity);app 仅当补开信号丢弃时间载荷 | **60a44edf**:engine 透传合成 host/session-activity 帧→mapper 最小 SessionUpdated→defendSessionReplacement max 合并(web 同款语义) | ✔ 18:42 API 发消息→6s 内行升首位+时间戳新鲜(x2/x4 对照;修复前 86s+2 次重取仍旧) |
| D8① | DSH 上「新建目录」必败+mkdir 临时会话泄漏为正式行 | DSH 命令注册表无 shell/exec→双回退必败;finally deleteSession 无能力位静默失败 | **fed82377**:原生 directoryPicker/createDirectory {path,name} 优先(callJson 原语)+V1/V2 回落旧通道+明确失败不回落+清理失败 AppLogger.w 留痕 | wire/三态单测绿+服务器端点 schema 实证;UI 成功路径留人工清单 |
| D2① | 提供方页「新增自定义 provider」入口不可达(整套表单死代码) | Add 图标误接 onRefresh;showCreate 全历史无置真 | **9bf0bf9c**:Add=开 DshCustomProviderCreateDialog;刷新独立成钮(复用 workspace_refresh 键,i18n 867×14 ✔) | ✔ 22:1x 真机 tap Add→表单全字段在场(路由 ID/显示名/Base URL/协议/密钥/发现模型) |

## 改判记录(证据链)

- **B8 批准后未续跑**→非缺陷:18:21:09.843 replyToQuestion success(answers=[[Approve],[]] Q2 空数组=守卫对话框合法产物)→轮次继续 10s 正常收尾——工具已解析、模型拿含空 Q2 结果**自行决定**等待;PlanChip 缺席=服务器拒非 plan 会话进 plan mode(exit_plan_mode 语义)
- **E3c 断开不撤通知**→非 #320 缺陷:契约三径=应答/轮末/会话删除(4e25b716 原文),「断开撤除」为 checklist 超范围发明;连带观察(通知无 TTL/仅三撤径)入 #339 产品裁量
- **C3 QueueSheet 无「跳转」**→web 平价(mod20 locale 定音:queue.edit/remove/steer 三动作),checklist 撰写笔误

## 新立卡(记录项)

| 卡 | 内容 | 依据 |
|---|---|---|
| #338 | 轮次台账负时长 -207ms/中断轮 0ms 族——created/completed 时间腿混源嫌疑 | W2 B4/W3 F3/W5 E4 三例;RenderableTurn.kt:220 |
| #339 | 重连 resync 通知族:伪 Idle 边沿误撤 pending 通知+旧错误轮重发 flood+注册表未水化阻断重发布(改动面大记录) | W5 三源实证+21:06:59 时序钉死 |
| #340 | resync 期 Room 持久化背压丢写(SSE 生产>Room 写入) | W6 G5 诊断屏 WARN 族 |

## 待人工项(V6 域,新增)

1. **E4②/E5② 通知组卡手指 tap 直达**:MIUI shade 组子卡 adb 注入 tap 不触发 contentIntent(独立卡正常)——需真机手指复核「adb 特有 vs 组通知可点性」;素材保全:e2e06-e5 会话 tea/coffee 问题 pending 中(勿应答先测);录屏 W5-E4-seg1~4 在档
2. **新建目录 UI 成功路径**(fed82377 后):新会话流→新建目录→应成功且列表无「mkdir」残留行
3. **断开服务器通知滞留裁量**(#339 产品面):断开后 9 条通知全留的现状是否符合期望

## 环境事件与勘误(过程记录)

- 服务器 11:08 重启→token 轮换→冷启 401(W1 经 dsh-pair 重配对,条目保持 2);断连期间 reverse 曾掉(重连时重建)
- E1 深链模板两处勘误(参数名 url= 非 endpoint=;token≥20 字符)——checklist 已修正
- 4199 服务端模型通道故障(opencode-go·Omen Alpha provider.transport)——G4 ✘ 定性服务端侧,修复后补单轮重测
- W4 归档的「无标题会话」未在 W6 终检复现(跨 wave 分歧:服务器侧可能清理 blank 行,如实记录)
- 执行层:豆包 IME 吞大写/空格(波间差异)/uiautomator 对 badge digit·V2 markdown 区·TextToolbar 失明族+3 新例(经验全部沉淀各 notes)

## 结论

本会话全部功能(#309–#337+#334/#329)**在本轮全量真机 E2E 中验证通过或闭环**:39 直接 ✔、4 缺陷根因修复(1 项设备复验+2 项单测/wire 验证)、3 项证据改判、5 项预案内 BLOCKED、2 项服务端环境✘、2 项待人工。回归域 12/12 覆盖(去留表在 checklist);主线零崩溃零数据丢失。遗留全部卡片化(#338/#339/#340)并入既有人工验收清单。
