# card-unification（2026-08-24 · #215 卡片体系统一执行批次）

> 状态：三批完成；2026-08-24 用户统一验收发现遗留问题（§验收反馈·一）转修复中
> 关联：#215（backlog）· spec `docs/specs/2026-08-24-card-unification-design.md` · M3 调研 `docs/research/2026-08-24-m3-card-container-fit.md`
> 来源：用户规划诉求（卡片行为统一 + 容器共存性重审 + M3 适配调研）；历史裁决推翻获用户授权（2026-08-18 容器并存残留态，spec §2 证据链）

## 批1：容器统一试点（2026-08-24）

**改动**（3 文件，纯视觉零行为变化）：

1. `ToolProgressCard.kt`：M3 Card（surfaceVariant 淡底 + shapes.small 8dp）→ `AmoledSurface` 统一语言（surface + tonal 1dp + smallMedium 6dp，AMOLED 纯黑+边框）
2. `TokenUsageCard.kt`：裸 Surface（surfaceContainerLow + small 8dp）→ 同上统一
3. `TodoListCard.kt`：圆角 small(8dp) → smallMedium(6dp) 对齐 scaffold 家族（其余参数本就一致：surface + tonal 1dp + AMOLED 边框）

**验证证据**：

- 编译：`:app:compileDevDebugKotlin` BUILD SUCCESSFUL（16s）
- 单测全套件：`testDevDebugUnitTest --rerun` BUILD SUCCESSFUL（1m）
- 插桩全量（e69a99d8 真机）：**OK (135 tests)**——容器换装零回归
- 真机活体：冷启动→会话列表→「社保投诉立案求助」会话→顶栏 context 圆环（1%）→ ContextDetailDialog → TokenUsageCard（"13,136 tokens" + Input 行）渲染正常无崩溃
- 视觉验证局限（如实披露）：2dp 圆角差与 tonal 底色变化细微，视觉模型无法可靠判别新旧；以「渲染正常 + 135 插桩绿」为机械门，三主题截图对比留待批3 收尾统一做

**环境注记**：MIUI 后台启动限制拦截 `am start`（前台为 launcher 时）——用 `monkey -c android.intent.category.LAUNCHER` 唤起；context 圆环为 Canvas 绘制无 a11y 节点，定位靠 ⋮ 菜单 bounds（[1080,194]）反推左侧圆环位（tap 1030,230 命中）。

## 批2：交互契约统一——本体点击=展开唯一入口（2026-08-24）

**改动**（3 文件）：

1. `ToolCardScaffold.kt`：删右侧 chevron IconButton + 删 `showExpandIcon`/`onClick` 参数（唯一使用者 TaskToolCard 同批改造）；标题行 clickable 加 `enabled = hasContent` 门（无内容禁点，消灭死点击）；KDoc 同步。历史包袱收敛：2026-08-12「纯 Icon 无 onClick」bug 的产物 chevron 整体退役
2. `TaskToolCard.kt`：本体点击回归=展开（不再被导航覆盖）；跳转收编为右侧 22dp 箭头 IconButton（#180 语义保留：Running 期拿到子智能体会话 id 即可跳转）；#181「标题行=导航+chevron=展开」并存方案随契约统一收束
3. `PartContent.kt`：**ShellCard 首击陷阱修复**——`isExpanded ?: false` 与 toggle 默认参 `true` 不一致致首次点击 null→!true=false 视觉无变化（需双击）；默认参改 `false` 对齐。此陷阱为存量 bug（chevron 时代同样存在），本体点击升为主交互后暴露并顺带修复

**验证证据**：

- 编译绿 + 插桩全量两轮 **OK (135 tests)**（改动后 + 首击陷阱修复后）
- 真机活体（受控演示会话，REST 触发 bash 工具）：①工具卡 chevron 全消失（Expand desc 仅剩 ReasoningBlock 1 处——批3 范围）②冷态（force-stop 重启清内存展开态）**单击卡本体即展开**（像素 diff 全屏变化 + 输出节点出现）③演示会话已删（DELETE 204）
- 演示取证实录：进度卡（ToolProgressCard）瞬态性太强（sleep 6 工具 2 帧轮询窗口难截），留用户日常使用中自然观察——容器已批1 统一，无行为变化

**披露**：演示会话 `ses_fcba2837affe`（"演示：工具进度卡"）创建于服务器、验证后已删（204）；两次 REST prompt 触发真实工具执行（echo demo-ok / sleep 6），无真实会话污染。

## 批3：自绘卡收编 + EmbeddedCardContainer 退役（2026-08-24）

**改动**（5 文件 + 1 删除）：

1. `ReasoningBlock.kt`（批3a）：标题行本体 clickable=展开/收起（含触觉），28dp chevron IconButton 删除——**推翻 2026-08-16「职责分离」规范**（用户授权，注释勘误留档）；复制维持内容区 SelectionContainer
2. `SyntheticNotificationCard.kt`（批3b）：第 2 行标题区 clickable(enabled=hasOutput)=展开/收起，展开钮删除；**跳转/定位语义按钮保留**（C2 契约：本体=展开，跳转走按钮）
3. `FileCard.kt`（批3d）：EmbeddedCardContainer → AmoledSurface 统一语言；**EmbeddedCardContainer.kt 组件删除**（唯一剩余用户迁移完毕，08-18 三修残留态终结；活跃/历史提问卡此前已自发迁 scaffold 语言）
4. `TodoListCard.kt`（批3e）：手写 Surface+border 模式 → AmoledSurface（与全家同源）
5. CompactionCard 收起态分割线 chip 维持现状（spec §4 例外裁决：流分隔符非信息卡，无代码改动）

**验证证据**：

- 编译绿 + 单测全套件绿 + 插桩全量 **OK (135 tests)**
- 真机活体（受控会话 ses_fcb71841cffe，REST 触发 reasoning+shell turn，验证后已删 204）：
  - **Expand/Collapse a11y 节点全网清零**（会话含 Reasoning+Shell+代码块，0 个展开按钮）
  - **ReasoningBlock 冷态单击本体即展开**（像素 diff 全屏变化，一次命中）
  - 通知卡静态确认：toggle 仅标题行 1 处，跳转/定位按钮保留
- 用户报告「真机一直闪退」核查：logcat 零 FATAL、进程健康——实为插桩全量（135 类连续拉起/杀 Activity）+ LeakCanary 误入 + 冷启动验证的正常自动化现象，已向用户解释并获知会（后续长批次照常执行，不逐一预告）

**遗留**：视觉三主题截图对比（spec §7 验收项）留用户统一验收时一并做或豁免（机械门已全绿）。

## 验收反馈·一：展开/收起滚动方向不对称（2026-08-24 用户报告，取证完成待修复）

**现象（用户原话）**：展开的时候是往上推的，收起的时候是往下收（应该也是往上推的才对）。

**取证（演示会话「卡片演示场」ses_fcb64b273ffe，bash 卡三段坐标测量）**：

- 展开后卡 icon y：599 → 668（下移 69px，顶部露出更早内容=用户感知「往上推」）
- 收起后卡 icon y：668 → 349（**上移 319px**——收起侧视口大幅回跳=用户感知「往下收」）
- 两方向位移量 69 vs 319 严重不对称，实锤行为异常

**根因线索（ScrollDiag 逐帧日志）**：

- 收起时 AnimatedVisibility shrinkVertically 逐帧收缩（RESIZE 5185→4968，-57/-34/-28…，inProgress=false 全程无主动滚动）——即滚动位移是 LazyColumn 在 item 高度变化下的**被动锚定行为**，非代码主动 scrollToItem
- 该 turn item 总高 ~5000px（跨两屏超级 item，卡在 item 内部）；列表为倒序布局（index 0=底部）——倒序 + 超大 item 内部高度突变，LazyColumn 的 firstVisibleItem 锚定在「锚点之上的内容高度变化」时产生视口跳变，且收缩/扩张两个方向的跳变量天然不对称
- 现有高度补偿（ChatMessageList:1091 layout{} 补偿 + requestScrollToItemNoCancel）**仅对流式消息（isStreamingMsg）且仅 delta>0**——历史卡展开/收起完全无补偿
- 候选修复：把 layout 补偿扩展到 toggle 触发的 item 高度变化（正负双向），展开/收起均锚定 firstVisible（offset±delta）使视口顶稳定；需实验验证倒序布局下 offset 语义与补偿方向

**状态**：待修复（#215 卡转回 [ ]）。演示会话保留作修复验证场。

**修复尝试·方案一（双向 delta 补偿）——实测更糟，已回滚（2026-08-25）**

- 实现：非流式 turn item 挂 layout 补偿，delta≠0 时 requestScrollToItemNoCancel(first, offset+delta)（镜像 SSE 流式补偿公式，双向放开）
- 真机实测（同演示会话同卡）：展开位移 69→**160px**、收起 319→**862px**——放大 2-3 倍
- 结论：倒序 LazyColumn 的锚定语义与该补偿公式方向相反——toggle 场景卡在视口内（锚点之后），LazyColumn 默认锚定本应稳定（但实测有 69/319 的被动跳变，见上），叠加 offset+delta 补偿变成双重滚动
- 正确修法需实验矩阵：卡位于视口上/中/下带 × 展开/收起 × 补偿方向（+delta/-delta/不补）× firstVisibleItem 是否为变化 item——9 格实验锁定每格正确行为后再实现；适合委派专项深挖

**修复尝试·方案二/三 + 终版（2026-08-25 修复完成，用户裁决保留动画）**

**定因（实验矩阵 + 预统一对照，推翻方案一的结论）**：

- 矩阵（uiautomator dump 卡 icon 前后 y + ScrollDiag 锚点轨迹，脚本 /tmp/matrix.sh 思路）：演示会话 bash 卡（单次 toggle delta=±1344px），卡位于视口 U/M/L 三带 × 展开/收起——**无补偿基线全部大漂移**：展开 -1344~-1380px 或冲出视口，收起 +1344px 或冲出视口
- 决定性对照：checkout 统一批次**之前**的构建（66c4b4e5）重跑矩阵——L 展开同样 -1344px、收起同样冲出视口。**两方向跳变是存量机制，非卡片统一批次引入**（批1/2/3 diff 核查：容器/交互/chevron 改动，未触碰动画与滚动力学）
- 规律锁定（锚点轨迹逐帧日志）：倒序 LazyColumn 对 item **内部**高度变化**零锚定修正**——(firstVisibleItemIndex, offset) 在整个 AnimatedVisibility 动画期间 freeze（锚 item 视觉底边被动钉住），被点卡随内容 ∓delta 漂移。用户报告的 69/-319 只是部分值（卡的屏幕位置决定锚 item 与被动钉住的相对关系，具体数值随位置变化，机制同一）
- 方案一的「方向相反」结论是误判：其放大根因是**共享 lastHeight 把测量渐变当 toggle 增量重复补偿**（实现伪影），非公式方向错误

**通道定因（为什么 SSE 流式补偿公式在 toggle 动画下失效——v1/v2 存档）**：

- v1（逐帧 requestScrollToItemNoCancel(first, off+frameDelta)）：逐帧请求被测量回写（applyMeasureResult→updateFromMeasureResult）中途丢弃——RESIZE/COMP 日志错位实证：请求 (1,140)/(2,79) 落空，锚点停在 (0,5086) 直到动画结束才跳 (2,912)——动画全程漂移+终态跳变
- v2（绝对位置+跨 item 重定基）：同样被回写拒绝（锚 item 的 off 存在 ~140px 不可用上界，越过即拒）
- 结论：**request-position 通道与动画期间的测量回写存在结构性竞争**；SSE 流式能用的前提是 shouldCompensate 门控（用户已滚离底部，回写与请求不撞车）

**方案三·snap 试验（用户叫停复杂补丁后的极简路线）**：三卡高度动画 snap() + 一次性/短窗 position 修正——6 格全 dy=0，但高度动画没了（手感变化）

**终版（用户裁决：恢复动画）——动画 + scrollToBeConsumed 注入通道**：

- 实现（3 文件 + 2 文件）：① `ToolCardScaffold/ReasoningBlock/SyntheticNotificationCard` 恢复默认高度动画（淡入淡出本就保留）；② `ScrollCompensation.kt` 新增反射助手 `requestScrollShift`（写 `LazyListState.scrollToBeConsumed` 私有字段 + poke `measurementScopeInvalidator`，反射失败静默降级）；③ `ChatMessageList.kt` toggle 翻转开 1200ms 修正窗（`toolExpandedStates` collect），非流式 Turn/Chunk item 常驻挂 `toggleAnchorCorrection` layout——窗口内每次 delta≠0 测量注入等量下移，与被动上推逐帧对消
- 为什么这条通道行：`scrollToBeConsumed` 是用户真实滚动（scrollBy/fling）的必经路径——测量开始时**无条件消费**，消费结果随测量回写，不存在「请求被回写丢弃」竞争；跨 item 折算（负 off/超 item 高度）由测量标准流程原生处理。逐帧注入 frame-delta，总和精确等于总 delta
- 铁律合规：流式分支（isStreamingMsg 的 layout 补偿 + shouldCompensate 门控）零触碰——toggle 修正只挂在 else 分支与 Chunk（分片不含流式 turn）；窗口仅在 toolExpandedStates 翻转时开启

**验证证据**：

- 矩阵 6 格（U/M/L × 展开/收起，终态卡 icon 位移）：**全部 dy=0**（原版基线：展开 -1344~-1380/收起 +1344 或冲出视口）；两方向完全对称
- 动画过程逐帧（screenrecord 12fps 帧相关法）：最大单帧位移 68px（一帧消费滞后，下一帧自愈）——对比原版动画期间 -1344px 单向流动
- 插桩全量（e69a99d8）：**OK (135 tests)**；单测 `testDevDebugUnitTest --rerun` 绿
- SSE 流式回归（自建「滚动回归」会话 ses_fca9e0948ffe，REST `/api/session/{id}/prompt` 平铺契约触发 bash 工具，验证后已删 204）：流式进行中 4 采样点全程贴底（FAB 隐藏）、终态完整回复可见+统计栏贴底缘、COMP-MSG 零触发（贴底路径由 msgCount 锚定负责，补偿通道闲置=正常）、toggle 窗口零污染；演示会话「卡片演示场」保留
- commits：a4eedab6（动画+注入终版）及其前置 349fabf1/2615483c（snap 路线存档）

**遗留**：V6 人工验收（用户真机手感——动画保留版）；矩阵未覆盖 ReasoningBlock/通知卡的独立格（同通道同机制，批内已 snap→恢复一致处理）

## 验收反馈·一·终版裁决：撤销全部补偿，动画回 M3 默认（2026-08-25）

**用户裁决（原话）**：「动画还是不对，不要有补偿逻辑！直接用M3属性的动画就行！不要自定义动画！」

**撤销内容**（方案三整体退役，实现存档 git history a4eedab6 供未来复用）：

- `ChatMessageList.kt`：toggle 修正时间窗（TOGGLE_ANCHOR_WINDOW_MS）+ toggleAnchorCorrection 修饰符（Turn/Chunk 两处挂载）+ 相关 import，全部删除
- `ScrollCompensation.kt`：requestScrollShift（scrollToBeConsumed 反射注入通道）删除
- 三卡动画 spec 全部清空回 Compose 默认：`ToolCardScaffold`/`ReasoningBlock` 的显式 fadeIn+expandVertically 参数 → 无参 AnimatedVisibility；`SyntheticNotificationCard` 的 tween(150) 覆盖（2026-08-12 存量）一并清除

**验证**：编译绿 + 插桩 135 全绿 + 真机实测确认行为（展开 dy=143 / 收起 dy=846——即倒序 LazyColumn 原生锚定行为，无任何补偿干预；动画手感为 Compose 默认 spring）。

**定论**：视口漂移是倒序 LazyColumn 对 item 内高度变化的存量机制（统一批次前同样存在，矩阵数据存档 §验收反馈·一）；修复尝试三方案（offset 补偿/修正窗/注入通道）均被用户否决——接受原生行为，保持代码零补偿。若未来 Compose 版本改进倒序锚定，此问题自然消解。

## 发版插叙（同日）

v0.3.2-dev.1 发版（用户裁决 0.3.2 dev 线）：`release.sh dev --force-bump=patch`（脚本默认推导 dev.23 续 0.3.1 线，force 开新线符合「beta 已发、dev 转 0.3.2 迭代」语义）；RELEASE_NOTES 按模板润色（用户视角 5 Added/4 Changed/8 Fixed）；CI success，资产 oc-beacon-0.3.2-dev.1.apk（7.5MB）上线。发版与批1 无冲突（脚本本地仅 bump+tag+push，构建在 CI）。
