# 可观测性验证指南（Observability Verification Guide）

> 定位：代码改动（新增/优化/修复）后的**质量确保手册**——通过一切可观测手段验证系统逻辑真实生效，而不只是"编译通过"。
> 与现有文档的关系：
> - `docs/verification-requirements.md` — 4 维验证框架（**做什么**：代码层/自动化/日志分支/测试框架/人工）
> - `docs/regression-guide.md` — 回归验证清单（**验证哪些能力域** + 验证手段编码 [UI]/[LOG]/[DATA]/[PERF]/[MANUAL]）
> - **本文档** — 可观测手段的操作手册（**怎么观测**：具体命令、工具、证据采集规范）
> 三者配合：改代码 → 按本文档观测 → 按 verification-requirements 声明完成 → 按 regression-guide 做回归。

## 一、可观测手段全景

> **方法论铁律：不确定方法/请求/API 的返回值或数据结构时，先实际请求测试**（curl 直测服务器、MockEngine 单测、模拟器实测），拿到真实响应再写/改代码——禁止凭猜测假设 API 行为。本次 #83 修复正是靠 curl 实测 1.18.18 全端点才发现"不存在的路径返回 HTML fallback"。

| 手段 | 观测对象 | 工具/命令 | 适用场景 |
|------|---------|----------|---------|
| **Logcat 日志** | App 运行日志（AppLogger/Log） | `adb logcat -d -v time --pid=<pid>` | 一切逻辑分支验证 |
| **App 内 Diagnostics** | AppLogger 聚合（应用内日志屏） | App → 设置 → Diagnostics | 无 adb 环境时看日志 |
| **Room 数据库** | 本地落库（ocbeacon.db） | `adb shell run-as ... sqlite3`（见 §2） | 存储层、消息落盘、会话状态 |
| **SSE 事件流** | 服务器推送事件 | logcat 过滤 `SseClient` / `V2 event` | 流式 turn、后台轮次完成、会话状态变更 |
| **网络请求** | App ↔ 服务器 HTTP 流量 | 服务器日志 / curl 复测 / OkHttp 日志 | API 端点行为、错误响应 |
| **服务器端观测** | opencode server 行为 | `curl` 直测 + serve 日志 + 数据库 | 区分"客户端 bug"与"服务器行为" |
| **UI 截图** | 界面状态 | `adb exec-out screencap -p` + 智谱 `analyze_image` | UI 验证（见 §4） |
| **系统服务** | 崩溃/ANR/内存 | `adb logcat -b crash -d`、`dumpsys` | 崩溃排查 |
| **性能** | 帧率/启动耗时 | `dumpsys gfxinfo`、`am start -W` | 性能回归 |

## 二、数据库直查（Room / SQLite）

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

## 三、日志观测规范

### 3.1 抓取规范

```bash
PID=$(adb shell pidof dev.leonardo.ocbeacon.dev)
# 按 PID + tag 过滤（裸 logcat 会被系统噪音淹没——D64 教训）
adb logcat -d -v time --pid=$PID | grep -E "TAG_A|TAG_B"
# 崩溃日志
adb logcat -b crash -d
```

### 3.2 关键 Tag 速查（随代码演进维护）

| Tag | 内容 |
|-----|------|
| `ApiVersionDetector` | 版本探测结果（Detected V1/V2 + version + 交叉验证日志） |
| `V2Api` | V2 API 请求/解析（含 Non-JSON (HTML) response 防御日志） |
| `SseClient` / `SseClientV2` | SSE 连接状态、事件解析 |
| `LogSessionLoad` | 会话加载链路 |
| `AppLogger` 体系 | 应用内 Diagnostics 同源 |

### 3.3 埋点要求（新增/修改逻辑时）

- **关键决策点**必须有 `AppLogger.i/w/e`（如版本判定、fallback 触发、HTML 防御命中）
- 日志要**可行动**：包含关键值（version、status、content-type、body 预览）
- 高频路径（每事件）用 DEBUG 门控（参考 #40 已清理的每事件日志）
- 用户可见错误路径必须记录异常堆栈（`AppLogger.e(tag, msg, e)`）

## 四、UI 截图观测（无视觉模型时用智谱）

当前模型若不支持图像输入，截图用智谱 MCP 分析：

```bash
adb -s emulator-5554 exec-out screencap -p > /tmp/opencode/shot.png
```

然后调用 MCP 工具 `analyze_image`（参数 `image_source`=本地路径, `prompt`=具体问题）：
- 问具体问题（"是否显示错误提示？"、"列表有几条会话？"），不要泛泛"描述一下"
- 关键截图命名 `场景_序号.png` 便于追溯

## 五、网络层观测

### 5.1 服务器直测（区分客户端/服务器问题）

```bash
# V1 服务器（Basic auth）
curl -s -u "opencode:PASS" http://127.0.0.1:4096/session | head -c 200
# V2 服务器
curl -s -u "opencode:PASS" http://127.0.0.1:4199/api/session | head -c 200
```

### 5.2 已知服务器行为（实测，见 docs/v1-v2-differences.md）

- V1 1.18.18 过渡形态对**不存在的路径**返回 200 + HTML（SPA fallback）——客户端必须防御
- V1 `/api/health` 返回 `{"healthy":true}`（无 version/pid）；V2 `/api/health` 返回 `{healthy, version, pid}`
- V2 预发布版本号 `0.0.0-next-xxxx`（major=0）——版本解析不能只靠 major

## 六、代码改动观测流程（标准作业）

```
1. 改动前：确认可观测点（日志 tag / 数据库表 / 端点）
   └─ 缺失 → 先埋点（AppLogger/日志），再改逻辑
2. 改动后：编译 ✅ → 单元测试 ✅（含新增用例）
3. 部署：assembleDevDebug → adb install -r
   （2026-08-13 规则：dev flavor versionCode 为时间戳，install -r 覆盖安装保留数据；禁止卸载重装）
4. 跑场景：按 docs/simulator-walkthrough-v1v2.md 类清单操作 UI
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

## 六点五、滚动问题取证（ScrollDiag 插桩，2026-08-20 滚动稳定性批次引入）

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

## 七、本次修复（#83）的可观测证据模板

| 观测点 | 手段 | 预期证据 |
|--------|------|---------|
| 版本判定 | logcat `ApiVersionDetector` | `Detected V1 API at ... (version=1.18.18)` |
| HTML 防御 | logcat `V2Api` | `Non-JSON (HTML) response from server`（如触发） |
| 会话消息加载 | logcat `LogSessionLoad` | `加载完成: N 条`，无 JSON 解析错误 |
| 服务器持久化 | 数据库 servers 表 | `apiVersion=V1, serverVersion=1.18.18` |
| 用户可见 | 截图 + 智谱 | 会话界面正常无报错 |

## 附：run-as 活库取证拉取（#290，2026-09-01）

直接 `adb exec-out run-as ... cat databases/ocbeacon.db` 有两个坑：
1. **缺 WAL 假损坏**——必须三件套（主 db + -wal + -shm）一起拉，否则 sqlite3 报
   `database disk image is malformed`（实测两次踩坑）；
2. **活写撕裂**——app 正在流式落盘时非原子拷贝可能页撕裂，integrity_check 不过。

标准姿势：`./scripts/pull-app-db.sh <serial> <out-prefix>`——三件套 + integrity
循环校验（默认 6 次，间隔 2s），输出首个一致快照路径。取证结论必须以 integrity
ok 的快照为准。
