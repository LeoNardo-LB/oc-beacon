# D64 根因调查 — 超长消息会话滚动失效（Phase 1 证据收集）

## 元信息

| 项 | 值 |
|----|----|
| 调查日期 | 2026-08-10 |
| 调查阶段 | Phase 1 证据收集（systematic-debugging，不改代码） |
| 缺陷 | backlog #64（R2 发现，疑似预有） |
| 设备 | emulator-5554，Android 10 API 29 |
| 新版 commit | 34092594（含 #40-#43） |
| 对照 commit | 4c416fb1（34092594^，#43 修复前） |
| 测试会话 | 调研harness记忆系统实践（25 条消息，directory D:\Develop\workspace） |

## 核心结论

### 🎯 #43 回归【坐实】（对照实验决定性证据）

| 版本 | swipe 后 bounds | 滚动状态 |
|------|----------------|----------|
| **新版 34092594（#43 后）** | 全部追踪元素 **零变化** | ❌ 完全失效 |
| **旧版 4c416fb1（#43 前）** | 上滑 -141px / 下滑元素移出视口 | ✅ 双向生效 |

→ R2 报告"疑似预有问题"的定性**被推翻**。#43（ScrollCompensation 反射初始化探测 + 调用防御）是回归源。

### 方法论修正（重要）

**截图哈希对比不可靠**：复现阶段 swipe 后哈希变化（928896→929216 字节），曾误判"部分滚动生效"。但 uiautomator dump 的 **bounds 对比显示元素位置零变化**——哈希差异来自状态栏时间/电量像素噪音。

**铁律：滚动验证必须用 uiautomator dump 的 bounds 对比，截图哈希只能作辅助**。R2 报告的哈希对比方法存在假阳性风险（本次恰好结论正确，因截图时序噪音更少）。

## 1. 复现结果（新版 34092594）

- 操作：进入超长会话，swipe 上滑 3 次（`input swipe 540 1800 540 500 120`）+ 多区域路径测试
- bounds 对比（关键元素，swipe 前后）：
  - "三、修正后的完整架构" [74,579][525,644] → **不变**
  - "总结：你的设计..." [74,1487][1006,1773] → **不变**
  - "需要我把这张图..." [74,1788][1006,1900] → **不变**
  - "08:59" [74,1948][147,1978] → **不变**
  - HorizontalScrollView(mermaid) [74,945][1006,1451] → **不变**
- 区域测试（上方/下方/边缘 swipe）：所有路径 bounds 均**零变化**
- 结论：滚动**完全失效**（非"部分失效""路径依赖"）

### 量化数据

| 项 | 值 | 来源 |
|----|----|------|
| 会话标题 | 调研harness记忆系统实践 | app 顶栏 dump |
| 消息数 | 25 | app 顶栏 badge |
| directory | D:\Develop\workspace | app 顶栏 dump |
| 最后一条消息特征 | 超长 AI 回复（多段中文分析 + mermaid flowchart 代码块 + 引用块）；可见尾部含"三、修正后的完整架构"、flowchart LR 定义（~400字符）、"图 12：RRF→PPR→验证器→综合排行"引用块、总结段 | uiautomator dump 可见部分 |
| 可见尾部渲染高度 | 占满内容区（y=231→2020，约 1789px），仅为消息尾部，整条更长 | dump bounds |
| 本地 DB 完整内容 | **未缓存**（cached_messages.payload 仅存元信息 747 字节；完整内容 app 实时从 server 拉取，内存渲染） | run-as 读 ocbeacon.db |

## 2. 系统观测数据（滚动操作期间）

### gfxinfo 帧统计（6 次 swipe + 等待）

```
Total frames rendered: 20      ← 极少！正常滚动应数百帧
Janky frames: 10 (50.00%)
50th percentile: 18ms
90th percentile: 22ms
95th/99th percentile: 28ms     ← 无超长帧（最大 28ms，无卡顿）
Number Missed Vsync: 2
```

**解读**：6 次有力 swipe 只产生 20 帧渲染，且**无任何长帧**。证明不是性能卡顿（渲染不慢），而是**滚动手势根本未转化为滚动渲染**（输入事件未被列表消费）。

### logcat（滚动后，main buffer）

- 总量：94 行 / 12924 字节（极少）
- 关键词扫描（Exception/Error/FATAL/ANR/Compose/LazyList/WebView/Choreographer/Skipped/GC）：**全部 0 命中**
- AndroidRuntime 30 行全是 `adb shell input` 命令进程自身日志（非应用异常）
- **应用层无任何异常日志**

### crash buffer

- **0 字节（空）**，无崩溃

### top（CPU/MEM）

- dev.leonardo.ocbeacon.dev: CPU **3.8%** / MEM **15.0%** — 正常，无高负载

### uiautomator dump — scrollable 节点

```
1. android.view.View [0,231][1080,2041]              ← 主聊天列表（视口高 1810px）
2. android.widget.HorizontalScrollView [74,945][1006,1451]  ← mermaid flowchart 水平容器（占视口 28%，原生 View 非 Compose）
```

HSV 内部为单个 `android.view.View`（mermaid 渲染内容），非 WebView。

### dumpsys window

- 焦点正常：`dev.leonardo.ocbeacon.dev/dev.leonardo.ocbeacon.MainActivity`

## 3. 对照实验（最关键）

### 实验设计

- git worktree 检出 `34092594^`（=4c416fb1，#43 修复前），构建 `assembleDevDebug`，install -r 安装
- 进入**同一个超长会话**（标题/directory/消息数一致），执行相同 swipe 操作
- 用 **bounds 对比**（非哈希）判断滚动是否生效

### 旧版结果（4c416fb1）

进入会话后（视口定位略不同，显示"五、总结"/"09:30"，mermaid HSV 在 [74,1323][1006,1520]）：

| 操作 | 追踪元素 "五、总结" y1 | 变化 |
|------|------------------------|------|
| T0 baseline | 1363 | — |
| T1 上滑 ×2 | 1222 | **-141px（向上移，方向正确）** |
| T2 下滑 ×2 | 元素移出视口（null） | **继续滚动** |

其他追踪元素（"阶段 2/3/4"、HSV）同步移动 141px。

→ **旧版滚动双向真实可控**（上滑内容上移、下滑内容下移），排除"进入会话自动滚动动画假象"。

### 对照结论

| | 旧版 4c416fb1（#43 前） | 新版 34092594（#43 后） |
|---|---|---|
| swipe 后 bounds | 变化（±141px） | **零变化** |
| 滚动 | ✅ 双向生效 | ❌ 完全失效 |

**#43（ScrollCompensation 反射初始化探测 + 调用防御 + 降级官方 requestScrollToItem）引入了手动滚动失效回归。**

## 4. 根因方向初步判断（基于数据，待 Phase 2 代码层验证）

数据指向：#43 改动**破坏了 LazyColumn 手动触摸滚动**（程序化滚动正常，见 R2）。

可疑机制（待代码验证，优先级排序）：
1. **反射初始化副作用**：#43 在 ChatMessageList 初始化阶段探测 `LazyListState` 内部字段（Compose BOM 2026.05.01）。若探测过程中反射修改了 LazyListState 内部状态（如 `scrollableMaxValue`/内部 gesture handler），可能禁用了触摸滚动通道
2. **requestScrollToItem 调用时机**：#43 封装的滚动调用若在某个回调（如 LaunchedEffect/onGloballyPositioned）中同步触发，可能与手动手势竞争（消费/取消手势）
3. **不是 HSV 拦截**：多区域测试证明所有路径（含避开 HSV 的边缘 swipe）均失效；且旧版同样有 HSV 却能滚动 → HSV 非元凶

#43 改动文件：`ScrollCompensation.kt`（3 处调用经 ChatMessageList 封装）。**Phase 2 应聚焦此文件的反射逻辑对 LazyListState 手势通道的影响**。

## 5. 证据文件清单（docs/research/audit-2026-08-10/metrics/）

| 文件 | 说明 |
|------|------|
| D64-repro-0-baseline.png ~ D64-repro-3-after-swipe.png | 复现实验截图（哈希对比，含噪音） |
| D64-region-above/below/edge-*.png | 区域路径测试截图 |
| D64-logcat-after-scroll.txt | 滚动后 logcat（94 行，干净） |
| D64-logcat-crash.txt | crash buffer（0 字节） |
| D64-gfxinfo.txt | 帧统计（20 帧，无长帧） |
| D64-top.txt | CPU/MEM（3.8%/15%） |
| D64-ui-dump-after.xml / D64-ui-dump-full.xml | 新版滚动后 UI dump（bounds 零变化） |
| D64-ui-old-before-swipe.xml.json | 旧版 baseline（bounds 记录） |
| D64-ui-old-in-session.xml / D64-ui-old-loaded.xml | 旧版进入会话 dump |

## 6. 收尾状态

- ✅ worktree 已清理（robocopy 镜像法解决 Windows 长路径限制）+ `git worktree prune`
- ✅ 模拟器已装回新版 APK（34092594，PID 18873 运行中，crash buffer 空）
- ✅ 临时 DB 文件已清理
- ⚠️ 当前工作区未改动（git status 干净，HEAD=34092594）

## 7. 给主会话的建议

1. **#64 性质变更**：从"预有问题"改为"#43 引入的回归"。建议更新 backlog #64 标注
2. **下一步（Phase 2）**：审查 `ScrollCompensation.kt` 反射初始化逻辑对 LazyListState 手势通道的影响——重点看初始化探测是否在列表 attach 前修改了内部字段，或封装调用是否在 Composition 中同步触发手势竞争
3. **修复方向（待验证）**：将反射探测延迟到首次程序化滚动时（懒初始化），避免影响 LazyListState 初始手势状态；或隔离反射对象与默认手势控制器
