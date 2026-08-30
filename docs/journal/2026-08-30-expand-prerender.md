# expand-prerender（2026-08-30）

> 状态：已完结（方向放弃——见二轮终局裁决）
> 关联：`docs/archive/specs/2026-08-30-expand-prerender-design.md` · backlog #262（已迁出）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 零轮（2026-08-30）：grill 共识与 spec 落册

- grill 14 问全定案（零位移验收/tap 锚点/对称收起/纯 clip/tap 时测量/EV 试点/channel 保留/spec 先行/分级承诺/折叠不组合+缓存/测试替换/tween200/边界分级）——全文见 `docs/specs/2026-08-30-expand-prerender-design.md` §3。
- 架构事实图（ExpandReveal 六槽位 / PreRenderShiftChannel / 证据链）由 subagent 取证，沉淀于 spec §2（file:line 索引）。
- 待评审：spec §5 实现层选型 A-E（测量挂点/预移通道/hit-test 门控/缓存失效键/试点窗口）；§4.3 收起侧「布局同步缓动」与卡片「布局恒定」表述的显式偏差。
- 顺带产出：backlog.md 空行纪律修复（commit 6a21b46d，对齐 69e133e1 版结构）。

## 一轮（2026-08-30）：EV 试点实现

- **组件**（722e20d3 + 7645e38e）：`components/PreRenderExpand.kt`——纯状态机 `PreRenderExpandMachine`（IDLE/MEASURING/REVEALING/EXPANDED/CLOSING；配对不变量：未位移几何永不上报/揭示仅 shiftSettled 后/version 入队自增；稳态首测全量上报守恒 everReportedSteady）+ `PreRenderExpandState`（tap 预移：贴底 request-position / mid-list dispatchRawDelta；finalH rememberSaveable 缓存 contentKey 键）+ 组合件（SubcomposeLayout 无限高实测、折叠态不组合、drawWithCache 幕布、动画期指针吞没）。
- **两路径落地**：缓存命中 tap 预移同帧原子（applyTapShift measure 块外）；首次展开 MEASURING withhold + USER_EXPAND 帧界配对（复用 PreRenderShiftChannel 已验证运输层）。收起 = 布局与 clip 200ms 同步缓动 + 动画循环逐帧 dispatchRawDelta(-δ)（帧回调相同 drain 时序）+ 浮点余量校正。
- **修复记录**：fraction 双真相源 bug（machine vs state.clipFraction——收起缓动会恒读 1f，统一为 machine 单一 internal set）；稳态首测缺全量上报（测试逼出：新组合展开卡会隐身一帧，对齐旧 everMeasured 守恒）。
- **EV 换道**（365cd605）：EventCard 退役 AnimatedVisibility + expandRevealCompensation modifier，toggle 走 `preExpand.toggle()`。
- **验证**：compileDevDebugKotlin 绿 ×3；`components.*` 179 测试 178 绿——唯一红 = ChunkReproTest（#261 环境夹具，与本改动无关，既有登记）；`PreRenderExpandMachineTest` 13/13。
- **装机**：assembleDevDebug → adb install -r 成功（houji 23127PN0CC）；debug-entry.sh 冷启直达 SessionList，服务器连接 OK，logcat 无 FATAL/错误。
- **待办**：用户 V6 八项清单（spec §9）；铺开 TODO/QPC/QC → RB/TC；清理 commit（ExpandReveal.kt 全家 + channel 分支①② + 死测试）。

## 二轮（2026-08-30）：终局裁决——撤销一切展开补偿，回归出厂默认

- **裁决链**：用户先撤销 #262 试点（「算了…按原来或默认的展开收起模式」→ 94d345d9 撤销 EV 换道、spec 归档）；随后扩大范围（「之前的卡片展开、收起有关的改造**也**不要了」）并要求 grill 定界。Round 定案：Q1a 只拆展开交互侧（流式 COMP-* 家族与 channel 保留）；Q2 调研（本机 Compose animation 1.11.2 源码实证：M3 无展开卡默认动画，AV 出厂默认 = fadeIn+expandIn / shrinkOut+fadeOut，spring StiffnessMediumLow 无弹跳、BottomEnd/Bottom 揭幕）；Q6 用户选 **(a) 纯出厂默认**（连 2026-08-28 #241「从上到下 Top 锚定」裁决一并让位——理由：改得快，后续调参数即可翻回）；Q3 原生推挤行为（标签行被顶起等）确认为终态；Q4a 死代码全清；Q5 简化验收。
- **拆除范围**（064c47fc，14 文件 -597 行）：ExpandReveal.kt 整文件（状态机/modifier/4 spec/LocalChatListState）；六槽位（RB/TC/EV/TODO/QPC/QC）+ CompactionCard + SyntheticNotificationCard 全部裸 AV；EventCard.expandRevealListState / MessageCard.eventRevealListState / SyntheticNotificationCard 透传参数删除；CML 的 LocalChatListState provide 与三处调用点删除；PreRenderShiftChannel 瘦身为流式专用（USER_EXPAND 源 + 贴底展开分支①退役，贴底收缩分支与 mid-list dispatchRawDelta 分支保留）；ExpandRevealCompensatorTest 删除、RevealCompensatorsTest 剥离展开侧 5 用例（余流式 6 用例）。
- **验证**：compileDevDebugKotlin 绿；components 156 测试 155 绿（唯一红 = ChunkReproTest #261 环境夹具，既有登记）；assembleDevDebug + 真机 install -r 成功；debug-entry 冷启 SessionList 干净（无 FATAL、ExpandReveal/EV-REVEAL log 痕迹清零）。
- **回归接受项（用户终态确认）**：mid-list 展开标签行被顶起、贴底展开上方内容上推、spring 从容时长（~0.5s）+ fade + 默认揭幕方向——均为原生语义。
- **backlog #262 处置**：方向整体放弃，卡片迁出（本 journal 即归档处）。
