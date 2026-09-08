# 观测与探测手册（Probing Manual）

> 2026-09-09 #382 文档整合：本文档由 observability-verification-guide.md + android-ui-probing-guide.md + verification-simulator-perf.md 合并而成（三源文件保留 tombstone 指向此处）。

> 定位：**「怎么用仪器拿证据」的单一入口**——代码改动后的可观测验证（logcat / Room / 网络）、真机 UI 元素精确探测（dump / 像素 / vision / 手势）、性能量化观测（GC / 内存）的操作手册：具体命令、工具、证据采集规范。
> 沿革：UI 探测部分 2026-08-23 定稿，依据调研报告 docs/research/2026-08-23-android-ui-probing-tools.md（8 工具评估与源码级引用）+ #192 E2E 实战（5 个根因排查全程实证）。
> 配套：`docs/verification-requirements.md`（验证框架——做什么）、`docs/regression-guide.md`（回归清单——验证哪些能力域）、`docs/qa-methodology.md`（交叉验证方法论）、`docs/real-device-testing.md`（真机装包/连通/intent）。
> 配合方式：改代码 → 按本文档观测取证 → 按 verification-requirements 声明完成 → 按 regression-guide 做回归。

## 一、开篇导航：三层路由与决策树

### 1.1 三层路由（先读这个）

| 判定类型 | 首选通道 | 兜底 |
|---|---|---|
| 文本/描述可定位元素（按钮、行、输入框） | uiautomator dump（text/content-desc + bounds） | Maestro |
| FAB / 自绘小控件（dump 失明区） | **像素探针**（颜色锚点）→ 功能探测（tap 后看副作用） | vision 模型（仅仲裁） |
| 布局/观感/颜色/图标形态 | vision 模型（描述性提问） | 截图 diff 像素统计 |
| 等待/轮询密集流程 | Maestro flow（7s/17s 自动等待） | sleep+dump 门卫循环 |

**铁律：单一通道不做最终判定。** dump 说没有 ≠ 屏幕上没有（Compose FAB 实证全失明）；vision 说有 ≠ 真有（小圆钮 ~50% 幻觉率，含引导性提问时更高）。

### 1.2 决策树速查

```
元素操作目标
├─ dump 里有（text/desc/id）→ bounds 校验 → tap → 门卫验证
├─ dump 里没有 → 像素基准色对比 → 在场？
│   ├─ 是 → 坐标 tap/快滑 → 副作用验证（dump 变化/像素翻转/logcat）
│   └─ 否 → 截图 + vision 描述性提问 → 仍不确定 → 加 Log/onGloballyPositioned 探针重建
└─ 需要等待 → Maestro flow 或 dump 轮询（≤5 次退避）
```

### 1.3 可观测手段全景

> **方法论铁律：不确定方法/请求/API 的返回值或数据结构时，先实际请求测试**（curl 直测服务器、MockEngine 单测、模拟器实测），拿到真实响应再写/改代码——禁止凭猜测假设 API 行为。本次 #83 修复正是靠 curl 实测 1.18.18 全端点才发现"不存在的路径返回 HTML fallback"。

| 手段 | 观测对象 | 工具/命令 | 适用场景 |
|------|---------|----------|---------|
| **Logcat 日志** | App 运行日志（AppLogger/Log） | `adb logcat -d -v time --pid=<pid>` | 一切逻辑分支验证 |
| **App 内 Diagnostics** | AppLogger 聚合（应用内日志屏） | App → 设置 → Diagnostics | 无 adb 环境时看日志 |
| **Room 数据库** | 本地落库（ocbeacon.db） | `adb shell run-as ... sqlite3`（见「三、Room 直查」） | 存储层、消息落盘、会话状态 |
| **SSE 事件流** | 服务器推送事件 | logcat 过滤 `SseClient` / `V2 event` | 流式 turn、后台轮次完成、会话状态变更 |
| **网络请求** | App ↔ 服务器 HTTP 流量 | 服务器日志 / curl 复测 / OkHttp 日志 | API 端点行为、错误响应 |
| **服务器端观测** | opencode server 行为 | `curl` 直测 + serve 日志 + 数据库 | 区分"客户端 bug"与"服务器行为" |
| **UI 截图** | 界面状态 | `adb exec-out screencap -p` + 智谱 `analyze_image` | UI 验证（见「七、vision 模型纪律」） |
| **系统服务** | 崩溃/ANR/内存 | `adb logcat -b crash -d`、`dumpsys` | 崩溃排查 |
| **性能** | 帧率/启动耗时 | `dumpsys gfxinfo`、`am start -W` | 性能回归 |

## 二、logcat 规范

### 2.1 抓取规范

```bash
PID=$(adb shell pidof dev.leonardo.ocbeacon.dev)
# 按 PID + tag 过滤（裸 logcat 会被系统噪音淹没——D64 教训）
adb logcat -d -v time --pid=$PID | grep -E "TAG_A|TAG_B"
# 崩溃日志
adb logcat -b crash -d
```

### 2.2 关键 Tag 速查（随代码演进维护）

| Tag | 内容 |
|-----|------|
| `ApiVersionDetector` | 版本探测结果（Detected V1/V2 + version + 交叉验证日志） |
| `V2Api` | V2 API 请求/解析（含 Non-JSON (HTML) response 防御日志） |
| `SseClient` / `SseClientV2` | SSE 连接状态、事件解析 |
| `LogSessionLoad` | 会话加载链路 |
| `AppLogger` 体系 | 应用内 Diagnostics 同源 |

### 2.3 埋点要求（新增/修改逻辑时）

- **关键决策点**必须有 `AppLogger.i/w/e`（如版本判定、fallback 触发、HTML 防御命中）
- 日志要**可行动**：包含关键值（version、status、content-type、body 预览）
- 高频路径（每事件）用 DEBUG 门控（参考 #40 已清理的每事件日志）
- 用户可见错误路径必须记录异常堆栈（`AppLogger.e(tag, msg, e)`）

### 2.4 滚动问题取证（ScrollDiag 插桩，2026-08-20 滚动稳定性批次引入）

滚动类问题（卡顿/跳变/视口瞬移）的真机取证基建——ChatMessageList.kt 内置 DEBUG-only 插桩，无需改代码直接抓：

```bash
adb -s e69a99d8 logcat -c
# …执行滚动操作（fling/拖拽）…
adb -s e69a99d8 logcat -d -v time | grep ScrollDiag
```

| 日志 | 含义 | 判读 |
|------|------|------|
| LEAP idx A->B off X->Y inProgress= | 首可见项位置两次发射间跳变 | dOff >350 或 dIdx>1 = 疑似程序化瞬移；对照 inProgress 区分手势中/停稳后 |
| gesture=true/false idx= off= | 滚动手势起止 + 当时位置 | fling 起 ~150ms 内 false = fling 被杀（主线程阻塞或 requestScrollToItem 取消） |
| RESIZE key= h A->B (d=±N) | item 组合后高度变化 | 长回复 d>+1000 = markdown 渐进测量（异步解析迟到）→ 必然触发锚点修正瞬移 |
| COMP-MSG / COMP-TOOL fire delta= | 高度补偿触发 | 流式外的触发 = 补偿泄漏到非流式场景 |

配套客观手段：
- 逐帧视频分析：screenrecord（等录完再 pull，提前 pull 会得到无 moov 的废文件）→ ffmpeg 抽帧 → 模板匹配算帧间位移（/tmp/frames2.py 可复用）→ 检测位移不连续（同向暴增/反转/停稳后突跳）
- gfxinfo：dumpsys gfxinfo <pkg> reset → 操作 → 再 dump；janky% + p90/p99（卡顿定量）

## 三、Room 数据库直查（SQLite）

App 数据落在模拟器应用私有目录。Room 数据库文件通常为 `databases/ocbeacon.db`（以实际 DAO 配置为准）。

```bash
# 1. 进入应用数据目录
adb shell run-as dev.leonardo.ocbeacon.dev

# 2. 用 sqlite3 查询（Android 模拟器自带 sqlite3）
cd databases
sqlite3 ocbeacon.db
.tables                    # 列出所有表
.schema messages           # 查看表结构
SELECT id, created, status FROM messages ORDER BY created DESC LIMIT 10;
SELECT * FROM servers;
```

**注意**：
- dev flavor 包名 `dev.leonardo.ocbeacon.dev`（beta/stable 各自不同）
- 若 `run-as` 被拒绝（release 签名），可用 `adb pull` 前提是 debuggable 构建：
  ```bash
  adb shell run-as dev.leonardo.ocbeacon.dev cp databases/ocbeacon.db /sdcard/ && adb pull /sdcard/ocbeacon.db
  ```
- 数据库是**证据**：验证"消息是否落盘"、"会话状态是否持久化"、"归档是否生效"时必须直查，不能只看 UI。

### 3.1 run-as 活库取证拉取（#290，2026-09-01）

直接 `adb exec-out run-as ... cat databases/ocbeacon.db` 有两个坑：
1. **缺 WAL 假损坏**——必须三件套（主 db + -wal + -shm）一起拉，否则 sqlite3 报
   `database disk image is malformed`（实测两次踩坑）；
2. **活写撕裂**——app 正在流式落盘时非原子拷贝可能页撕裂，integrity_check 不过。

标准姿势：`./scripts/pull-app-db.sh <serial> <out-prefix>`——三件套 + integrity
循环校验（默认 6 次，间隔 2s），输出首个一致快照路径。取证结论必须以 integrity
ok 的快照为准。

## 四、网络层观测（curl V1/V2）

### 4.1 服务器直测（区分客户端/服务器问题）

```bash
# V1 服务器（Basic auth）
curl -s -u "opencode:PASS" http://127.0.0.1:4096/session | head -c 200
# V2 服务器
curl -s -u "opencode:PASS" http://127.0.0.1:4199/api/session | head -c 200
```

### 4.2 已知服务器行为（实测，见 docs/v1-v2-differences.md）

- V1 1.18.18 过渡形态对**不存在的路径**返回 200 + HTML（SPA fallback）——客户端必须防御
- V1 `/api/health` 返回 `{"healthy":true}`（无 version/pid）；V2 `/api/health` 返回 `{healthy, version, pid}`
- V2 预发布版本号 `0.0.0-next-xxxx`（major=0）——版本解析不能只靠 major

## 五、uiautomator dump 健壮化配方

```bash
adb -s <serial> shell rm -f /sdcard/ui.xml          # 旧文件残留是最大坑（失败时 dump 命令不报错）
adb -s <serial> shell uiautomator dump /sdcard/ui.xml
adb -s <serial> shell cat /sdcard/ui.xml > /tmp/ui.xml
grep -o 'text="目标"[^>]*bounds="\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]"' /tmp/ui.xml | grep -o '\[[0-9]*,[0-9]*\]\[[0-9]*,[0-9]*\]' | head -1
```

- **bounds 校验必做**：Popup/离屏节点会给出 y>2670 之类的坐标（实证 y=776713）。tap 前判 `0<y<屏高`。
- 一行文本可能出现多次（列表行 + Popup），取 bounds 在视口内的第一个。
- Compose 已全局开 `testTagsAsResourceId`：新增 UI 加 `Modifier.testTag("xx")` 后 dump 里就是 resource-id（每控件 1 行成本）。
- **已知失明区**（本仓实证）：FloatingActionButton/ToggleFloatingActionButton 收起态、FAB 菜单展开药丸、部分自绘控件。**自定义 semantics（contentDescription）的组件可见**——#192 边缘拉杆（BadgedBox+semantics）在 dump 有 bounds。
- Android 12+ 可试 `--windows` 多窗口；必要时 `dumpsys accessibility` 交叉验证。

## 六、像素探针（dump 失明区的硬证据）

Python 纯标准库 PNG 解码（免 ImageMagick；脚本在手册附录或 /tmp/probe.py 模式）：探针点颜色 + 区域色彩统计（top-colors 直方图）。判据：

- **颜色锚点**：先在「确定在场」状态采一次基准色（如 FAB 容器 #394d56 / 图标 #5b87c3），之后同点探针对比变化 = 状态翻转的硬证据。
- 区域扫描看 top-colors 分布是否突变（隐藏/出现）。
- 暗色主题下别用「亮度」启发式（secondaryContainer 暗蓝与背景亮度接近，曾因此误判「FAB 不渲染」3 轮）。

## 七、vision 模型纪律（含截图采集）

截图采集（当前模型若不支持图像输入，截图用智谱 MCP 分析）：

```bash
adb -s emulator-5554 exec-out screencap -p > /tmp/opencode/shot.png
```

然后调用 MCP 工具 `analyze_image`（参数 `image_source`=本地路径, `prompt`=具体问题）；关键截图命名 `场景_序号.png` 便于追溯。使用纪律：

- 只做**描述性/仲裁性**提问（「这是什么屏」「展开菜单是否有药丸堆栈」「是否显示错误提示？」「列表有几条会话？」），不要泛泛「描述一下」。
- **禁止**依赖它给小目标的坐标（<10% 屏幕面积的圆钮幻觉率高）；坐标一律 dump bounds 或像素探针。
- 提问要具体（区域+特征），不给引导性预期（「应该有个按钮吧」→ 必幻觉）。
- 并发调用会静默失败——串行使用。

## 八、手势注入（input swipe/tap）已知坑

| 坑 | 对策 |
|---|---|
| **reverseLayout 列表**（本仓聊天列表）翻旧消息 = **下滑**（1000→2000），上滑是回底 | 看不到滚动效果先换方向 |
| 慢速 input swipe（>400ms/短距离）输给触摸 slop 方向竞争（拖拽类手势吃不到 delta，实证 50px 只到 2.4px） | 手势测试用**快滑 100-250ms** |
| MIUI 返回手势区 ~24dp：拖拽终点落在区内被系统截断（手势 cancel、无 settle） | 终点留在离缘 ≥28dp |
| tap 命中判定要「tap→dump/像素→验证副作用」，坐标 tap 本身无回执 | 门卫式每步验证 |
| adb input text 纯 ASCII 可靠（冒号/斜杠正常）；标点 >;/: 在部分输入法丢失 | 命令组合用 base64 或分多段 |

> 真机侧补充陷阱——IME 在场判定（用 `dumpsys input_method`，勿用像素分析）、注入 tap 间歇丢弃的二分定位与冷启恢复配方——沉淀在 `docs/real-device-testing.md`（2026-09-07 #345 定性），此处不重复。

## 九、Maestro（等待/断言密集场景）

- devRelease 非 debuggable **完全可用**（其 instrumentation 用自家 dev.mobile.maestro APK，与目标包解耦——源码级核验过）。
- 与 dump 同一棵 a11y 树（同样受失明区限制）+ 自动等待 7s/17s + assertScreenshot(cropOn)。
- 现有 flow 在 maestro/。新 flow 优先用于「导航链长、中间态多」的场景。

## 十、性能观测配方（GC / 内存 / onTrimMemory）

> 目的：为已修复的性能类 issue 提供**可量化证据**（GC 计数/分配量/内存占用/事件落盘写量），而非仅"正确性验证"。执行环境：模拟器 emulator-5554（API 36）+ V2 服务器 10.0.2.2:4199。

### 10.1 SSE 流式 GC/分配量观测（#97 H-5+M-6+M-15+H-6）

原理：
- H-5：ByteArray 管线消除了逐字节装箱（List<Byte> → ByteArrayOutputStream）
- M-6：prettyPrint 关闭 → JSON 体积 -30-50% → 编码分配减少
- H-6：增量落盘 → Room 写量从"整条消息"降为"delta 文本"

观测方法（logcat GC 统计）：
```bash
# 1. 触发一段长流式输出（模拟器上发送消息，内容约 500-1000 token）
# 2. 期间抓 GC 日志（Android Runtime 会打印 GC 事件）
adb -s emulator-5554 logcat -c
adb -s emulator-5554 logcat | grep -E "GC_|GcCause|dalvik" > /tmp/gc_streaming.log

# 3. 流式结束后统计 GC 次数与分配量（API 24+ 的 GC 日志格式）
grep -c "GC_" /tmp/gc_streaming.log
grep "GC_" /tmp/gc_streaming.log | grep -oE "freed [0-9]+K" | awk -F" " "{s+=\$2} END {print s "K freed"}"
```

基线对照：
- 修复前基线：需要从 git 历史构建（ddfc683c 之前的父 commit）跑同场景
- 修复后：当前 master
- 对比指标：流式期间 GC 事件数、累计 freed 字节、卡顿（GC 暂停 ms）

预期结论：
- GC 事件数下降（分配减少）
- 流式更平滑（无长 GC 暂停）

### 10.2 长会话内存占用观测（#95 热视图上限 + #98 无界容器）

原理：
- #95：每会话内存热视图上限 1000 条（Room 对齐）
- #98：ToolSnapshotCache LRU 200 / pendingInputs 有界 / 注册表 onDispose 清理

观测方法（dump 内存 + dumpsys）：
```bash
# 1. 构建 2000+ 条消息的长会话（脚本批量发送或加载已存在长会话）
# 2. 观察内存稳定上限
adb -s emulator-5554 shell dumpsys meminfo dev.leonardo.ocbeacon.dev | grep -E "TOTAL|Java Heap|Native"

# 3. 反复滚动聊天（触发 mdRegistry/readiness 注册表增删）
adb -s emulator-5554 shell input swipe 500 1500 500 500 200
# 4. 再次 dump 对比——注册表清理后不应增长
```

预期结论：
- 长会话内存不再随消息数线性增长（稳定在 1000 条热视图 + 存档）
- 滚动后注册表条目数稳定（无累积）

### 10.3 onTrimMemory 触发与清理观测（#115 D2-16）

原理：
- OpenCodeApp.onTrimMemory 在 RUNNING_LOW/UI_HIDDEN 级别清理 ToolSnapshotCache

观测方法（logcat）：
```bash
# 1. 打开 FileViewer 加载大文件（填充 ToolSnapshotCache）
# 2. 模拟低内存（设备压力）：
adb -s emulator-5554 shell am send-trim-memory dev.leonardo.ocbeacon.dev RUNNING_LOW

# 3. 验证清理日志
adb -s emulator-5554 logcat -d | grep "onTrimMemory"
# 预期：onTrimMemory level=... - cleared ToolSnapshotCache
```

### 10.4 执行记录

| 日期 | 场景 | 观测项 | 结果 | 结论 |
|------|------|--------|------|------|
| 2026-08-14 | SSE 流式 GC（#97） | 700 字流式期间 GC 计数/暂停 | GC 并发 compact，freed 7-11MB/次，暂停 <12ms | 无卡顿 GC；H-6 增量落盘 UPSERT 修复前 937 字节正文丢失（part 行不存在）→ 修复后完整持久化 |
| 2026-08-14 | 回归 | D2-L25 saveable 迁移 | SessionList 进会话列表崩溃（TextFieldValue 无 Saver）→ 已修（TextFieldValue.Saver + DirectoryPath 回退） | 迁移引入的回归已闭环；模拟器实测进会话列表正常 |
| 2026-08-14 | 长会话内存（#95/#98） | 滚动 10+ 屏 PSS 变化 | 滚动后 PSS 260MB→256MB 收敛（无累积）；Java Heap 24→29MB（懒加载正常） | 注册表 onDispose 清理生效；#95 上限由 MessageEventHandlerMemoryCapTest 单测覆盖（1005→1000），长会话端到端待后续实测 |
| 2026-08-14 | onTrimMemory（#115 D2-16） | am send-trim-memory RUNNING_LOW | logcat：onTrimMemory level=10 - cleared ToolSnapshotCache | 低内存回调正确清理可重建缓存 |

## 十一、构建/签名速查（本仓）

- 装机 flavor=devRelease，本地 keystore 8fbc136e（**debug 构建签名不匹配装不上**，INSTALL_FAILED_UPDATE_INCOMPATIBLE 被 `| tail -1` 吞过——install 必看 exit code + lastUpdateTime）。
- release 构建里 `android.util.Log.d` **不被 R8 剥离**（实证 FAB192 探针日志可见）——排查 UI 可临时加 Log 探针（记得移除）。
- `onGloballyPositioned { Log }` 是定位「组件在哪/是否组合」的最强探针（positionInRoot+size）。

## 十二、代码改动观测流程（标准作业）

```
1. 改动前：确认可观测点（日志 tag / 数据库表 / 端点）
   └─ 缺失 → 先埋点（AppLogger/日志），再改逻辑
2. 改动后：编译 ✅ → 单元测试 ✅（含新增用例）
3. 部署：assembleDevDebug → adb install -r
   （2026-08-13 规则：dev flavor versionCode 为时间戳，install -r 覆盖安装保留数据；禁止卸载重装）
4. 跑场景：按 docs/simulator-walkthrough-v1v2.md 类清单操作 UI（该清单已归档至 docs/research/2026-08-13-simulator-walkthrough-v1v2.md）
5. 抓证据（三件套）：
   ├─ logcat（PID 过滤，关键 tag）
   ├─ 数据库（run-as sqlite3 直查）
   └─ 截图（智谱 analyze_image 确认界面）
6. 比对预期：日志值 == 设计值？数据库行 == 预期？UI 无报错？
   └─ 交叉验证（qa-methodology.md §2）：同一结论至少 2 个独立维度互证
       （如 logcat 事件 + DB 落库 + 截图可见三件套互相印证；单维度证据不算完成）
7. 回归：docs/regression-guide.md 按变更分类执行
8. 完成声明：只有证据齐全才能声称完成（verification-requirements.md 铁律）
```

### 12.1 证据模板示例（#83 修复）

| 观测点 | 手段 | 预期证据 |
|--------|------|---------|
| 版本判定 | logcat `ApiVersionDetector` | `Detected V1 API at ... (version=1.18.18)` |
| HTML 防御 | logcat `V2Api` | `Non-JSON (HTML) response from server`（如触发） |
| 会话消息加载 | logcat `LogSessionLoad` | `加载完成: N 条`，无 JSON 解析错误 |
| 服务器持久化 | 数据库 servers 表 | `apiVersion=V1, serverVersion=1.18.18` |
| 用户可见 | 截图 + 智谱 | 会话界面正常无报错 |

## 附录：实战案例索引

- #192 双 FAB 滑动隐藏 E2E（本手册全部条款的实证来源）：docs/journal/2026-08-23-acceptance-closeout.md §六
- 调研全文（8 工具评分矩阵、a11y 失明 8 类根因、源码引用）：docs/research/2026-08-23-android-ui-probing-tools.md
