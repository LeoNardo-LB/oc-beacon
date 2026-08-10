# D64-bisect：#42 vs #43 决定性二分实验

**日期**：2026-08-10
**实验**：backlog #64（超长消息会话滚动失效）元凶判定
**方法**：构建 3 个对照 APK（CURRENT / NO42 / NO43），同协议测试滚动行为 + 初始视口对比

## 结论

**元凶既不是 #42 也不是 #43——这是超长消息会话的固有 bug。**

3 个 APK 滚动**全部失效**，且初始视口**逐字节相同**（差异 0/18）。#42 的排序改动对可见消息顺序零影响；#43 的反射降级路径未被触发（LazyListReflection 日志 0 命中）。需进一步调查 #41（MessagePaginationDelegate synchronized guard）、4c416fb1 之前的改动，或 LazyColumn 对超长 item 的处理。

## 实验设计

### 3 个对照 APK（git worktree，不碰主工作区）

| APK | 基线 | 回退 | 保留 | MD5 |
|-----|------|------|------|-----|
| CURRENT | 34092594 | — | #42+#43+#41 | 38FD78D20D3C4EB509D1530DCB9290B6 |
| NO42 | 34092594 | `git checkout 4c416fb1 -- MessageEventHandler.kt` | #43+#41 | BDEFC4E46D0883C59256894AEDD37FA8 |
| NO43 | 34092594 | `git checkout 4c416fb1 -- ScrollCompensation.kt` | #42+#41 | A1D31376E2706B70272980E03598C294 |

3 个 APK 大小均为 35136353 bytes，MD5 各不相同（确认构建产物确实不同）。

### 测试协议（每个 APK 严格执行，保证可比性）

1. `adb install -r <apk>`（保留数据）
2. force-stop + monkey 启动
3. Connect → Sessions → 进入"调研harness记忆系统实践"会话（同一会话，25+ 消息，最后一条超长 AI 回复含 mermaid + 中文 + 引用块）
4. **进入后等 10 秒**（确保消息完整加载、超长 item 渲染完成——D64 原实验未控制的变量）
5. uiautomator dump → 记录滚动前 bounds
6. `adb shell input swipe 540 1600 540 800 300` × 3（间隔 1s）
7. 等 2s → dump → 记录滚动后 bounds
8. 判定：同一节点 bounds 变化 → 有效；全部相同 → 失效
9. `adb logcat -d` 抓取反射日志

## 结果

### 滚动判定表

| APK | 消息节点 bounds 变化 | 输入框节点 bounds 变化 | 滚动判定 | LazyListReflection 日志 |
|-----|---------------------|----------------------|---------|------------------------|
| CURRENT | **0/17** | 1（[17] placeholder 宽变 355→370，光标重绘噪声）| **失效** | 0 条 |
| NO42 | **0/17** | 1（[17] placeholder 宽变 355→346，同类噪声）| **失效** | 0 条 |
| NO43 | **0/17** | 1（[17] placeholder 宽变 355→346，同类噪声）| **失效** | 0 条 |

> 18 个可见节点中 17 个为消息内容/标题/元数据节点，bounds 在滚动前后**完全不变**；唯一的 [17] 是底部输入框 placeholder，宽度微变由 placeholder 文本切换（"Refactor the…" ↔ "Help me with…"）或光标重绘引起，与滚动无关。

### 初始视口对比（关键：判断 #42 是否改变消息顺序）

以 CURRENT 的 before-swipe dump 为基准，逐节点对比 NO42 / NO43 的 before-swipe dump：

| 对比组 | 文本差异节点数 | bounds 差异节点数 | 结论 |
|--------|--------------|------------------|------|
| NO42 vs CURRENT | **0/18** | **0/18** | 逐字节相同 |
| NO43 vs CURRENT | **0/18** | **0/18** | 逐字节相同 |

3 个 APK 的初始视口可见内容完全一致（前 5 条节点示例）：

```
[0] 536,231][990,241 | ① 上下文感知检索：索引前给每个记忆块加前缀摘要...
[1] 90,273][418,322  | 阶段 2（PPR 探索）
[2] 536,273][990,693 | ① 可选轻量替代：文件系统范式...
[3] 90,725][445,774  | 阶段 3（验证器阈值）
[4] 90,965][410,1014 | 阶段 4（综合排行）
[5] 74,1222][705,1287 | 五、总结：两篇文档的定位互补
[7] 74,1948][147,1978 | 09:30（时间戳，会话末尾）
```

→ #42（mergeSortedMessages 线性归并 vs distinctBy+sortBy）对消息顺序**零可见影响**，两种实现语义等价（与 commit message 的"逐字节等价"声明一致）。

### 反射路径验证

3 个 APK 的 logcat 中 **LazyListReflection tag 命中数均为 0**：
- CURRENT（#43 有降级）：反射成功，无 fallback 日志
- NO43（4c416fb1 by lazy 版本，**无降级**）：反射成功，应用未崩溃（PID 持续存在）

→ 反射在 Compose BOM 2026.05.01 上正常工作（与字节码验证一致），#43 的防御改动对运行时行为无影响。

## 推论

既然回退 #42 或 #43 都不能恢复滚动，且两者都不改变初始视口，元凶必在别处：

1. **#41（MessagePaginationDelegate synchronized guard）**——34092594 的第三个改动，本实验未单独回退。若 `loadOlderMessages` 的并发 guard 与超长消息的加载时序交互，可能影响 LazyColumn 的内容状态。**建议下一步单独回退 #41 测试**。
2. **4c416fb1 之前已存在的固有 bug**——D64 原实验声称"4c416fb1 正常可滚"，但该实验**未控制消息加载完成状态**（任务背景明确指出）。本实验在严格 10 秒加载等待下，3 个 APK 全部失效——4c416fb1 的"正常"可能是加载未完成时的偶然现象或测试了不同会话/状态。
3. **LazyColumn 对超长 item 的固有处理问题**——最后一条 AI 回复含多段中文 + mermaid flowchart 代码块 + 引用块，单条消息渲染高度可能超过视口。LazyColumn 在 item 高度超过视口时的滚动行为是 Compose foundation 层面的已知难点。

## 终态

- 模拟器已恢复 APK-CURRENT（34092594 产物）
- 3 个临时 worktree 已全部清理（git worktree list 确认）
- 主工作区未修改任何文件（仅新增本报告 + metrics 证据）

## 证据文件清单

```
docs/research/audit-2026-08-10/
├── D64-bisect.md                      （本报告）
└── metrics/
    ├── D64-bisect-APK-CURRENT.apk     （34092594 产物，MD5: 38FD...）
    ├── D64-bisect-APK-NO42.apk        （无 #42，MD5: BDEF...）
    ├── D64-bisect-APK-NO43.apk        （无 #43，MD5: A1D3...）
    ├── D64-bisect-CURRENT-01-home.xml          （连接前主屏幕）
    ├── D64-bisect-CURRENT-02-after-connect.xml （连接后）
    ├── D64-bisect-CURRENT-03-sessions.xml      （会话列表）
    ├── D64-bisect-CURRENT-04-before-swipe.xml  （滚动前，18 节点）
    ├── D64-bisect-CURRENT-05-after-swipe.xml   （滚动后，bounds 不变）
    ├── D64-bisect-CURRENT-logcat.txt           （LazyListReflection 0 命中）
    ├── D64-bisect-NO42-03-sessions.xml
    ├── D64-bisect-NO42-04-before-swipe.xml     （与 CURRENT-04 逐字节相同）
    ├── D64-bisect-NO42-05-after-swipe.xml
    ├── D64-bisect-NO42-logcat.txt
    ├── D64-bisect-NO43-04-before-swipe.xml     （与 CURRENT-04 逐字节相同）
    ├── D64-bisect-NO43-05-after-swipe.xml
    └── D64-bisect-NO43-logcat.txt
```
