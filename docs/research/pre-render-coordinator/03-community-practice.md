# 调研：社区实践与已知坑 —— LazyColumn(reverseLayout 聊天场景)条目高度变化时的视口稳定

- 主题：Google Issue Tracker / StackOverflow / 开源聊天应用源码 / 官方示例中,关于「LazyColumn 条目高度动画变化导致视口跳动/闪烁/震荡」的社区实践与已知坑
- 日期：2026-09-21
- 调研方法：本会话 `web_search` 端点余额不足(HTTP 402);通用搜索引擎(DDG/Bing/Google/Mojeek/Ecosia/Startpage)在本网络环境下全部被反爬墙、区域重定向或 DNS 污染拦截,cn.bing.com 仅返回无关内容。故改用**结构化渠道直取**:
  - StackExchange 官方 API(问题/答案全文,含投票数与采纳状态);
  - GitHub REST API + raw.githubusercontent + git 克隆(源码逐行核对、issue 全文抓取);
  - 被引站点通过二级来源(SO 答案内嵌链接、GitHub issue 引用)回溯。
- 局限声明(如实记录,未证实处均已标注):
  - **issuetracker.google.com 本环境不可达**(连接层被阻,非 SPA 渲染问题);issue 正文无法机读,issue 编号一律以 SO 答案/GitHub issue 的第一方引用为锚;
  - **reddit.com DNS 污染不可达**,pullpush.io 存档 API 明确拒绝爬取(HTTP 429);r/androiddev 经验帖无法直读;
  - **slack-chats.kotlinlang.org(Kotlin Slack 公开存档)持续返回 Vercel Security Checkpoint HTTP 429**,无法机读;
  - Now in Android、Jetsnack 源码未能机读验证(见 §5 的推理边界声明)。
- 本文所有源码行号均为 **2026-09-21 抓取时点的快照**,上游移动后以引用的 GitHub 永久链接为准。

---

## 1. Issue Tracker 与官方仓库 issue(任务项 1)

### 1.1 Google Issue Tracker(b/)

| 编号 | 主题 | 状态 | 官方回复 | 来源锚点 |
|---|---|---|---|---|
| [234223556](https://issuetracker.google.com/issues/234223556) | LazyColumn key 锚定导致滚动位置随条目移动/重排而跳(社区在 comment 2 提出**列表头部加空 item** 对抗锚定) | 未验证(不可达) | 未验证 | [SO 74320761 高赞答案](https://stackoverflow.com/q/74320761) 原文链接直指 `issuetracker.google.com/issues/234223556#comment2` |

- 除上表外,androidx-compose-foundation lazy 包源码注释中与本话题相关的 b/ 引用为**零**——本次抓取的 LazyListState.kt 中唯一 b/ 引用是 `b/420551535`(prefetch 策略弃用标记,与本话题无关)。androidx 团队对锚定行为的**官方立场是"按设计工作"而非 bug**:机制完整写在公开 KDoc 里(见 §6),没有看到官方承诺改变该行为的 issue 记录。
- 交叉印证:[compose-samples#696](https://github.com/android/compose-samples/issues/696)(官方组织维护的仓库 issue,见 §1.2)被 android 官方标记 **stale 自动关闭**,从未被接为 bug 修复——与"按设计"立场一致。

### 1.2 官方示例仓库 issue:Jetchat 滚动跳

- [android/compose-samples#696](https://github.com/android/compose-samples/issues/696)「[Jetchat] Scroll position jumps on data updates」(已关闭,标 stale):
  - 报告内容:用户滚到聊天历史中间,新消息到达时**视口每次都被拉走**;楼主给出复现步骤(去掉 Jetchat 的 `resetScroll()` 后向上滚动、再发消息)。
  - 社区评论给出两层结论:
    1. jasonsaruulo:「The "jump" is because new messages are added to the bottom. Strictly seen, the scroll position is retained (y coordinate wise) but as a new message pushes up all the other items, there is this "jump"」——**锚定是"保条目"不是"保像素"**,新增/增高发生在锚定条目之前时视口必然跳;
    2. fwh-dc(报告者):在自己的商业聊天应用中用 `items(key = { it.id })` 解决;并反问官方要不要把 key 加进 Jetchat 示例——**至今未加,Jetchat 的 items 一直有 key(见 §4.1),但 issue 本身无人认领,自动过期关闭**。

### 1.3 工业级聊天 SDK 的同类 issue

- [GetStream/stream-chat-android#2808](https://github.com/GetStream/stream-chat-android/issues/2808)「LazyColumn in Messages not automatically scrolling down to keep new messages visible」(bug 标签,已关闭带修复验收项):
  - 新消息到达时 Compose 版 `Messages` 不自动滚到底,用户需手动滚;acceptance criteria 两项勾选完成。说明**"新消息到达时的视口行为"连成熟 SDK 都需要显式工程化**,默认锚定行为不可依赖。

---

## 2. StackOverflow 高票/高相关问题(任务项 2)

> 以下问题均经 StackExchange API 抓取全文(2026-09-21),票数/答案数为抓取时点值。

### 2.1 key 锚定语义与视口跳动(印证:锚定保"条目"不保"像素")

- [Q79238746](https://stackoverflow.com/q/79238746)(1▲,答案 2▲ 已采纳)「LazyColumn Reverse Layout With Key Not Working - Auto Stick New Message to Bottom」:
  - 现象:reverseLayout 聊天,**加 key 后新消息不贴底**;不加 key 反而贴底。
  - 采纳答案(tyg)对语义的解释与官方源码完全一致:「When keys are used the current scroll position is **anchored to the key**. When new items are added the current scroll position is retained… When the user currently scrolled up in the chat history and a new message comes in the scroll position shouldn't be changed」;给出的方案是 `LaunchedEffect(messages.size)` + `firstVisibleItemIndex` 阈值判断手动滚。
- [Q74320761](https://stackoverflow.com/q/74320761)(4▲,答案 4▲)「Compose LazyColumn key, messes up scrolling when sorting the items」:
  - 排序后视口跳/动画错乱;高赞答案直接采用 **issue tracker 234223556 的"头部空 item"workaround**:`item(key="0"){ Spacer(...) }` 置顶使锚定失效。
- [Q74668917](https://stackoverflow.com/q/74668917)(9▲)「How to use LazyColumn's animateItemPlacement() without autoscrolling on changes?」:
  - toggle 待办导致条目跨列表移动时**视口自动跳**;回答者 z.g.y 引用 `LazyListState.updateScrollPositionIfTheFirstItemWasMoved` 源码注释解释这是 key 锚定的内建行为,workaround 是**牺牲首条动画**(首条 key 退化为 index)或头部加哑 item——两条 workaround 都是"对抗锚定",无人提出补偿式方案。

### 2.2 流式 AI 生成中的滚动抢夺(与本仓库场景同型,★重点印证)

- [Q78159275](https://stackoverflow.com/q/78159275)(1▲,**0 答案**)「LazyColumn disable auto scrolling when last item content is getting updated」:
  - 原文要点(附[复现视频](https://youtu.be/JHC6LX3i7s0)):reverseLayout=true 的 AI 聊天,最后一条消息流式更新期间,**用户手动向上滚会被持续拉回**,直到生成结束;且只有"正在生成的消息可见"时发生。
  - **这是社区与本仓库最同型的坑**:流式条目增高 → 视口被拉扯;截至抓取日无任何回答——属社区未解问题。
- [Q79849410](https://stackoverflow.com/q/79849410)(-1▲,答案 1▲ 已采纳)「LazyColumn scroll offset breaks when content size changes dynamically」:
  - 聊天屏加载骨架(shimmer,大高度)→ 真实内容到达后收缩 → **底部按钮被切出视口且列表滚不到**;采纳答案只给了"动态计算 padding"的平庸方案,无人给出锚定/补偿级解法。

### 2.3 reverseLayout 特有坑(印证:反向布局语义陷阱成串)

- **isScrolledToBottom 语义反转**:Stream 官方源码注释([Messages.kt:266-268](https://github.com/GetStream/stream-chat-android/blob/develop/stream-chat-android-compose/src/main/java/io/getstream/chat/android/compose/ui/messages/list/Messages.kt)):`// reverseLayout influences the scrolling behavior. // When reverseLayout is true, canScrollBackward is false when the list is scrolled to the bottom` → 正确写法 `isScrolledToBottom = !canScrollBackward`。
- **sticky header 变 sticky footer**:[Q76029742](https://stackoverflow.com/q/76029742)(8▲,无答案)。
- **键盘交互**:[Q78041734](https://stackoverflow.com/q/78041734)、[Q79453091](https://stackoverflow.com/q/79453091)、[Q78177556](https://stackoverflow.com/q/78177556)(isImeVisible 引发状态丢失);compose_chat 的做法是监听 `WindowInsets.isImeVisible` 弹出时显式 `animateScrollToItem(0)`(见 §4.4)。
- **Arrangement.Bottom 替代方案**:[Q79253432](https://stackoverflow.com/q/79253432) 采纳答案:「不使用 reverseLayout,改 `verticalArrangement = Arrangement.Bottom`」——与 Stream SDK 的实际组合(§4.2)互相印证。

### 2.4 闪烁/复用类

- [Q69450780](https://stackoverflow.com/q/69450780)(5▲,答案 3▲ 已采纳)聊天图片消息闪:根因是默认 key=index 导致条目复用错位;采纳答案(Phil Dukhov,Compose 社区高频回答者):`itemsIndexed(key = { _, msg -> msg.id })`。**与本项目"无状态 Markdown 重组闪烁"同属"条目身份不稳定"大类。**
- [Q78184607](https://stackoverflow.com/q/78184607)(4▲)「How can we animate addition and removal of items in a lazy list」:animateItem(Placement)对 add/remove 不动画、shuffle 才动画;答案确认 key 是动画正确性的前提。

### 2.5 高票通用"贴底"方案(票型最高的基线做法)

- [Q72111299](https://stackoverflow.com/q/72111299)(15▲;答案 13▲+7▲)「Enable Auto-scrolling LazyColumn to the bottom」:
  - 事实基线:reverseLayout=true 时 Room Flow 新插入条目**天然贴底**(index 0 侧插入不动锚定);reverseLayout=false 时需手动 `LaunchedEffect(messages.size){ animateScrollToItem(...) }`。**这是整个 SO 社区票数最高的聊天贴底模式:LaunchedEffect(条目数) + animateScrollToItem。**

---

## 3. Reddit r/androiddev 与 Kotlin Slack 存档(任务项 3)

**可达性受限,未能取得一手帖**(reddit DNS 污染、pullpush 429 反爬、slack-chats Vercel checkpoint 429,三路均试)。

替代证据链(同主题、可比对社区语气的一手材料):

- SO Q78159275 的提问者就是"AI 流式输出拉扯滚动"的亲历者,并上传了 YouTube 演示视频——这类内容在 Reddit 上通常同样以"录屏+求方案"形态出现,本仓库 `docs/research/sse-scroll-stability-iron-laws.md` 记录的内部回归史与该问题逐条对应(高度振荡/拉扯/突发卡顿)。
- Stream 官方 SDK 源码注释(§4.2)承载了 Slack #compose 频道中 Stream 工程师反复回答的滚动问题的事实质料——他们的 `shouldScrollToBottomOnNewMessage` 文档注释逐条写了"什么时候不打扰用户",这就是社区反复争论后的工程结论。
- ⚠️ 若后续需要 Reddit/Slack 一手引用,需在可访问 reddit.com 与 slack-chats.kotlinlang.org 的网络环境补一轮检索;本文不虚构。

---

## 4. 开源聊天应用源码(任务项 4)

> 4 个真实项目全部逐行核对(2026-09-21 快照)。汇总表见 §4.5。

### 4.1 Jetchat(Google 官方示例)——「默认行为 + 显式 Jump 按钮」,零补偿

源码:[Conversation.kt](https://github.com/android/compose-samples/blob/main/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/Conversation.kt) / [JumpToBottom.kt](https://github.com/android/compose-samples/blob/main/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/JumpToBottom.kt)

- `LazyColumn(reverseLayout = true, state = scrollState)`(Conversation.kt:340-342);
- 条目带身份:`item(key = "header_today", contentType = "header")`、`item(key = content.id, contentType = "message")`(L357-366)——**key + contentType 双保险**(复用稳定 → 闪烁少);
- JumpToBottom 按钮(L378-398):显示条件 `firstVisibleItemIndex != 0 || firstVisibleItemScrollOffset > 56.dp`(L386-395,阈值常量 L653),点击 `scrollState.animateScrollToItem(0)`——reverseLayout 中 index 0 即最新消息;
- **消息展开/收起**:无任何处理;**流式追加**:无此场景(静态数据);发送消息时 `resetScroll()` 即 `scrollState.scrollToItem(0)`(L236-238)。
- **结论:官方示例直接依赖 key 锚定的默认行为 + 用户手动 Jump,没有任何程序化视口稳定逻辑。** 它自己都因此吃过 issue(#696,§1.2)。

### 4.2 GetStream stream-chat-android(工业级 SDK)——「政策化滚动 + 焦点居中补偿」,无高度补偿

源码:[Messages.kt](https://github.com/GetStream/stream-chat-android/blob/develop/stream-chat-android-compose/src/main/java/io/getstream/chat/android/compose/ui/messages/list/Messages.kt) / [MessagesLazyListState.kt](https://github.com/GetStream/stream-chat-android/blob/develop/stream-chat-android-compose/src/main/java/io/getstream/chat/android/compose/ui/messages/list/MessagesLazyListState.kt)

- 布局组合:`reverseLayout = true`(Messages.kt:201)**且**默认 `verticalArrangement = Arrangement.Bottom`(L122/197)——内容不满一屏时贴底;L204-207 注释解释 reverseLayout 自下而上组合会打乱语义顺序,需显式 `itemTraversalOrder`;
- 条目身份:`itemsIndexed(key = { _, item -> item.id })`(L224-226);
- 贴底判定:`isScrolledToBottom = !lazyListState.canScrollBackward`(L266-268,reverseLayout 语义反转);
- **新消息滚动政策**(L404-427 + L522-550):`shouldScrollToBottomOnNewMessage = focusedItemIndex == -1 && !isScrollInProgress && areNewestMessagesLoaded && (firstVisibleItemIndex < 3 || newMessageState is MyOwn)`——
  - 阈值是 **firstVisibleItemIndex < 3**(不看精确 0,容差 3 条);
  - **自己的消息无条件滚**(MyOwn);
  - 正在滚动/正在聚焦某条/最新页未加载时不滚;
  - `rememberSaveable(lastScrollToBottomOnNewMessage)` 防配置变更后重复滚(L404-407 注释)。
- **焦点消息居中**(唯一接近"补偿"的逻辑,[MessagesLazyListState.kt](https://github.com/GetStream/stream-chat-android/blob/develop/stream-chat-android-compose/src/main/java/io/getstream/chat/android/compose/ui/messages/list/MessagesLazyListState.kt)):`updateParentSize`/`updateFocusedMessageSize`(onSizeChanged 上报,Messages.kt:229-232)→ 计算 `focusedMessageOffset` → `LazyListState.scrollToFocusedItem`:`snapshotFlow { isScrollInProgress }.first { !it }` **等待滚动静默后** `animateScrollToItem(focusedItemIndex, offset)`(Messages.kt:356-360)——**"跳转前先等视口静默"是个被工业 SDK 固化的时序细节**;
- 加载更多分页边界处理:`snapshotFlow { totalItemsCount to canScrollForward }.distinctUntilChanged`(L283-291)处理"内容不满一屏但有更旧消息"的边角。
- **消息卡片展开/收起**:SDK 有消息折叠状态,但**滚动侧无配对补偿**——展开长消息时视口行为交由 key 锚定默认行为。**流式追加**:按"新消息到达"政策滚;**已可见消息原地增高**(我们的流式 Markdown 场景)**SDK 无对应逻辑**。

### 4.3 Taewan-P/gpt_mobile(多模型 AI 聊天,★与本仓库场景最接近)——「方向感知 + 布局流重钉」

源码:[ChatScreen.kt](https://github.com/Taewan-P/gpt_mobile/blob/main/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatScreen.kt) + 仪表化测试 [ChatBottomAutoScrollerInstrumentedTest.kt](https://github.com/Taewan-P/gpt_mobile/blob/main/app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatBottomAutoScrollerInstrumentedTest.kt)

- **弃用 reverseLayout**:正序 `LazyColumn`(ChatScreen.kt:275-277)+ 尾部 1dp 锚点条目 `item(key = "chat-bottom-anchor")`(L313-315)——靠"重钉到最后一项"而非反向布局天然贴底;
- 初始定位:`rememberChatListState(messageCount) = key(messageCount > 0){ rememberLazyListState(initialFirstVisibleItemIndex = messageCount) }`(L636-639)——**用初始 index 直达底部**,加载历史不闪跳(测试 `openingLoadedHistory_startsAtBottomWithoutAutoScroll` 固化);
- 用户意图感知(L140):`isUserDragging = listState.interactionSource.collectIsDraggedAsState()` + `lastScrolledBackward`(LazyListState 自带的"上次滚动方向");
- 跟随状态机(L205-212, L641-647):`nextFollowBottom = when { !canScrollForward -> true; isUserScrolling && isScrollingAway -> false; else -> isFollowing }`——**迟滞(hysteresis)设计**:回滚即脱跟,到底自动复跟;
- 自动滚动开关(L647-655):`shouldAutoScrollToBottom = isFollowing && !isUserDragging && !(isScrollInProgress && isScrollingAway)`;
- **重钉执行器 ChatBottomAutoScroller(L657-671)**:

```kotlin
LaunchedEffect(listState, isEnabled) {
    snapshotFlow { listState.layoutInfo }
        .collectLatest { layoutInfo ->
            val latest = layoutInfo.totalItemsCount - 1
            if (latest >= 0 && listState.canScrollForward) listState.requestScrollToItem(latest)
        }
}
```

  ——**监听"布局结果"而非"数据变化"**:`layoutInfo` 在任何条目尺寸变化后都会发射,`collectLatest` 取消过期重钉,`requestScrollToItem`(无动画、取消进行中滚动、见 §6)立即重钉最后一条。**这就是开源世界对本仓库"流式增高→视口稳定"问题的最完整公开答案:布局驱动的重钉,而不是数据驱动的 scrollToItem。**
- "活跃流式消息"标识:`isActiveMessage = index == lastMessageIndex`(L288)传入条目渲染——流式消息有独立渲染路径(对应本仓库 `isStreamingMsg`);
- **仪表化测试直接测试"内容变高"场景**(GrowingChatList fixture:末条 Spacer 高度 `320.dp + additionalHeight`,变高 480.dp):
  - `growingContentWhileFollowing_keepsTheTrueBottomVisible`(L44-66):跟随中内容变高 → 前后断言 `canScrollForward == false`(始终贴底);
  - `bottomButton_reenablesFollowingForLateContentGrowth`(L82-140):脱跟后点 ScrollToBottomButton → 重新激活跟随 → 内容再变高仍贴底。
- **边界**:重钉方案在"用户停在中间、上方条目增高"时会把人拉走或不动(isEnabled=false 时完全不动作)——它解"跟随贴底",不解"原位稳定"。

### 4.4 leavesCZY/compose_chat(中文社区 IM 最佳实践项目)——「reverseLayout + Flow 贴底 + IME 补偿」

源码:[MessagePanel.kt](https://github.com/leavesCZY/compose_chat/blob/master/app/src/main/java/github/leavesczy/compose_chat/ui/chat/main/MessagePanel.kt)

- `LazyColumn(reverseLayout = true, contentPadding = PaddingValues(top=10.dp, bottom=60.dp), verticalArrangement = Arrangement.spacedBy(30.dp))`(L82-89);
- 条目身份:`key = { message -> message.detail.msgId }` + **按消息类型/收发方拆 contentType**(`"ownTextMessage" / "friendTextMessage" / "TimeMessage"…`,L91-115)——contentType 粒度比官方示例更细(复用池按渲染形态隔离);
- 贴底:`scrollToLatestMessageFlow.collectLatest { delay(10); listState.animateScrollToItem(0) }`(L66-72)——**Flow 事件驱动 + 10ms delay 让位布局**;
- **IME 补偿**:`snapshotFlow { isImeVisible }.filter { it }.collectLatest { listState.animateScrollToItem(0) }`(L73-79)——键盘弹出即重滚到最新;
- 消息展开/收起、流式追加:无(IM 消息静态)。

### 4.5 汇总对照

| 项目 | reverseLayout | key | contentType | 新消息贴底 | 已见消息增高 | 流式生成 | 展开收起补偿 |
|---|---|---|---|---|---|---|---|
| Jetchat(官方) | ✅ | ✅ id+header | ✅ | 默认锚定+手动 Jump | ❌无处理 | 无此场景 | ❌ |
| Stream SDK | ✅ + Arrangement.Bottom | ✅ item.id | (item 级) | 政策函数(阈值3条/MyOwn/静默判定) | ❌无处理 | 按"新消息"政策 | ❌(仅焦点跳转居中) |
| gpt_mobile | ❌(正序+尾部锚点) | ✅ pairKey | ❌ | snapshotFlow(layoutInfo)→requestScrollToItem 重钉 | ✅重钉覆盖(测试固化) | ✅(isActiveMessage 标识) | ❌ |
| compose_chat | ✅ | ✅ msgId | ✅(收发方/类型) | Flow→delay(10)→animateScrollToItem(0) | ❌ | ❌ | ❌ |
| skydoves/chatgpt-android | —(直接复用 Stream SDK 的 Messages,自身不写列表滚动逻辑) | | | | | | |

(skydoves 源码:[ChatGPTMessages.kt](https://github.com/skydoves/chatgpt-android/blob/main/feature-chat/src/main/kotlin/com/skydoves/chatgpt/feature/chat/messages/ChatGPTMessages.kt) 中滚动均委托 `listViewModel.scrollToMessage`,底层即 Stream 组件,故并入 Stream 行。)

---

## 5. 官方示例 Jetsnack / Now in Android(任务项 5)

- **Jetchat** 是 compose-samples 中唯一聊天列表样例,已详于 §4.1(处理方式 = 无处理)。
- **Now in Android / Jetsnack**:两者均无 reverseLayout 聊天列表、无"列表内条目展开后视口保持"场景(Jetsnack 的展开是详情页 hero 动画;NIA 的列表条目固定短高度)。⚠️ 局限声明:本次网络窗口内未能机读两仓库全文核实;上述判断基于仓库公开 README 与目录结构,置信度中高,如需引证可后续补抓。
- 官方生态里**不存在**"条目高度动画变化时的视口稳定"参考实现——官方对聊天滚动场景的表态就是 Jetchat 的"key 锚定 + 手动 Jump"。

---

## 6. 机制基础:androidx 官方源码引用(解释 §2 全部现象)

来源:[androidx/androidx 镜像](https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt) androidx-main 分支(2026-09-21 抓取;与 cs.android.com 同源):

1. **锚定保"条目"不保"像素"**——`LazyListScrollPosition.updateScrollPositionIfTheFirstItemWasMoved`(LazyListScrollPosition.kt:88-105):「In addition to keeping the first visible item index we also store the **key** of this item… allows us to **detect when there were items added or removed before our current first visible item and keep this item as the first visible one even given that its index has been changed**」——实现即 `itemProvider.findIndexByKey(lastKnownFirstItemKey, index)`。
2. **显式滚动会"遗忘"锚**——`requestPositionAndForgetLastKnownKey`(同文件:81-86):「clear the stored key as we have a direct request to scroll to [index] position and the next [checkIfFirstVisibleItemWasMoved] shouldn't override this」。
3. **`requestScrollToItem` 的语义**(LazyListState.kt:469-476):取消进行中滚动 → `snapToItemIndexInternal(forceRemeasure = false)`;其内部注释(L482-499)信息量极大:
   - 「by default **we maintain the scroll position by key, not index**」;
   - 位置真变了就 `itemAnimator.reset()`——「we don't want offset changes to be animated. **this offset should be considered as a scroll, not the placement change**」——**这就是重钉方案不触发 placement 动画、不闪的官方保证**;反之位置没变时不 reset,「if there is an offset change for an item, this change should be animated」。
4. **animateItem 与高度变化**:animateItem 的 KDoc 本次未能机读(网络窗口),但 SO Q78184607/Q74320761 的社区共识与上 3 条源码语义一致:**placement 动画以"条目旧位置→新位置"差分为基础,条目自身高度变化引发的位移不在动画模型内,会直接体现为视口跳**。

---

## 7. 提炼(任务书要求的三个落点)

### 7.1 印证——大家踩过、我们也踩过的坑

1. **流式生成拉扯视口**:SO Q78159275(AI 回复生成中手动上滚被持续拉回,0 答案)= 本仓库"视口跳底"回归的社区镜像;连"只有生成消息可见时才发生"的现象粒度都一致(对应本项目 `layout{} 补偿只应用于流式 turn` 铁律)。
2. **无状态重组 → 高度振荡 → 闪烁**:Q69450780(图片闪,key=index 复用错位)= 本仓库"无状态 Markdown 每次重组重解析 → 高度振荡"同属条目身份/测量不稳定大类;解法方向一致(稳定身份 + 稳定测量)。
3. **reverseLayout 语义陷阱串**:isScrolledToBottom 反转(Stream 源码注释明文)、sticky header 变 footer(Q76029742)、键盘(Q78041734 等)——本仓库"autoScroll/shouldCompensate 以 isScrollInProgress + isAtBottom 双 key"铁律里 `isAtBottom` 的判定正是 Stream 踩过的同一个坑(`!canScrollBackward` 写法)。
4. **"锚定保条目不保像素"引起的跳**:compose-samples#696、Q74320761、Q74668917、Q79238746 四案同因——新增/增高发生在锚定条目之前(视觉方向上"下方")时视口必跳;社区 workaround(头部空 item、首条 key 退化)全部是破坏性对抗,无人做补偿。
5. **数据驱动 scrollToItem 的时序竞争**:Stream 的 `snapshotFlow{isScrollInProgress}.first{!it}` 再滚 + compose_chat 的 `delay(10)` 再滚 = 本仓库"48ms 批处理 + scheduleFlush 不取消进行中定时器"要解的同一类"滚动/布局静默前不动作"时序问题。

### 7.2 空白——没人解决好,是我们的机会

1. **"已可见条目原地增高"的原位补偿(committed-height delta compensation)在全部开源实现中缺失**:四案无一实现"条目变高 Δpx → 视口偏移 +Δpx"的配对补偿;Stream 只做"焦点跳转居中",gpt_mobile 只做"贴底重钉"。**我们的 layout{} 高度补偿方向在社区是空白,且 gpt_mobile 的"重钉"方案已示范了纯重钉路线在"用户回看"场景的局限。**
2. **流式 Markdown 渐进解析的高度振荡无任何开源处理**:所有聊天 SDK 假设消息文本静态不可变(Stream 消息不可变、gpt_mobile 追加纯文本)。AI 流式 Markdown 端到端方案(前缀差分、StreamingMarkdownState 类思路)在开源客户端里没有对标实现。
3. **animateItem 与高度变化的组合无先例**:社区只有"对抗锚定"的 hack;官方动画模型不覆盖条目自身高度变化(§6.4)。
4. **贴底判定无公认标准**:阈值五花八门(Jetchat 56dp offset / Stream 3 条 + canScrollBackward 反转 / gpt_mobile canScrollForward + 方向迟滞),没有经过证明的"锚定稳定判定"公共件。
5. **"滚动静默"原语缺失**:各家自造(snapshotFlow first{!inProgress} / delay(10) / cancel in-progress),没有公共的"等待布局与滚动静默再动作"封装。

### 7.3 开源项目的最小可用做法(可直接借鉴的工程事实)

1. **基线(必须做对)**:reverseLayout=true + `key = 消息稳定 id` + `contentType`(Jetchat/compose_chat/Stream 一致);贴底判定在 reverseLayout 下用 `!canScrollBackward`(Stream)。
2. **贴底跟随的最稳公开实现 = gpt_mobile 三件套**(§4.3):`interactionSource.collectIsDraggedAsState()` + `lastScrolledBackward` 方向迟滞状态机 + `snapshotFlow { layoutInfo } → collectLatest → requestScrollToItem(last)`。其中 **requestScrollToItem 的官方语义(取消进行中滚动、位置变化时重置 itemAnimator、不触发 placement 动画)是不闪的关键**(§6.3);且有仪表化测试固化"内容变高仍贴底"。
3. **新消息政策化滚动 = Stream shouldScrollToBottomOnNewMessage**(§4.2):focused==-1、非滚动中、最新页已加载、(距底 ≤3 条 或 自己的消息);rememberSaveable 防重复。
4. **跳转类动作先等静默**:`snapshotFlow { isScrollInProgress }.first { !it }`(Stream scrollToFocusedItem)。
5. **官方样例层级的最小可用 = Jetchat**:key+contentType + JumpToBottom(阈值 56dp)——当产品可接受"不自动跟"时这是零风险基线。

---

## 8. 来源清单

**Google Issue Tracker / 官方仓库 issue**
- https://issuetracker.google.com/issues/234223556 (经 SO 74320761 答案引用;正文未验证)
- https://github.com/android/compose-samples/issues/696
- https://github.com/GetStream/stream-chat-android/issues/2808

**StackOverflow(StackExchange API 全文抓取,2026-09-21)**
- https://stackoverflow.com/q/79238746 · /q/74320761 · /q/74668917 · /q/78159275 · /q/79849410 · /q/69450780 · /q/78184607 · /q/72111299 · /q/76029742 · /q/79253432 · /q/78041734 · /q/79453091 · /q/78177556

**开源项目源码(逐行核对,行号为 2026-09-21 快照)**
- Jetchat: https://github.com/android/compose-samples/blob/main/Jetchat/app/src/main/java/com/example/compose/jetchat/conversation/Conversation.kt · …/JumpToBottom.kt
- Stream: https://github.com/GetStream/stream-chat-android/blob/develop/stream-chat-android-compose/src/main/java/io/getstream/chat/android/compose/ui/messages/list/Messages.kt · …/MessagesLazyListState.kt
- gpt_mobile: https://github.com/Taewan-P/gpt_mobile/blob/main/app/src/main/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatScreen.kt · https://github.com/Taewan-P/gpt_mobile/blob/main/app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/presentation/ui/chat/ChatBottomAutoScrollerInstrumentedTest.kt
- compose_chat: https://github.com/leavesCZY/compose_chat/blob/master/app/src/main/java/github/leavesczy/compose_chat/ui/chat/main/MessagePanel.kt
- chatgpt-android: https://github.com/skydoves/chatgpt-android/blob/main/feature-chat/src/main/kotlin/com/skydoves/chatgpt/feature/chat/messages/ChatGPTMessages.kt

**androidx 官方源码(机制依据)**
- https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListState.kt (L469-476, L482-499, L725-732)
- https://github.com/androidx/androidx/blob/androidx-main/compose/foundation/foundation/src/commonMain/kotlin/androidx/compose/foundation/lazy/LazyListScrollPosition.kt (L79-105)
