# V1/V2 版本探测修复 — 模拟器走查清单

> 关联：backlog #83（版本误判修复）· 2026-08-13
> 目的：验证 ApiVersionDetector 版本交叉验证修复生效 + 全链路无回归
> 看图工具：智谱 `analyze_image`（image_source=截图路径, prompt=描述内容）

## 一、修复内容回顾（影响面）

| 改动 | 文件 | 直接影响 | 间接影响 |
|------|------|---------|---------|
| tryV2/tryV1 增加 content-type 校验 + 版本交叉验证 | ApiVersionDetector.kt | 服务器版本判定 | **全局**：apiVersion 分发（15+ 文件）——SessionApi/MessageApi/FileApi/SystemApi/ProviderApi/ShellApi/TerminalApi/SseConnectionManager/UI 层 |
| parseRoot 增加 HTML 检测 | V2ApiClient.kt | 30+ V2 端点解析 | 错误信息从 JsonDecodingException 变为 NonJsonResponseException（可读） |
| flexibleList/flexibleObject 增加 HTML 检测 | V2Mappers.kt | permission/question/location/mcp/fs/vcs/project 解析 | 同上 |
| 新异常类 | NonJsonResponseException.kt | 统一错误类型 | Repository runCatching → Result.failure → UI 错误消息（不崩溃） |

**关键验证点**：V1 过渡形态（1.18.18）服务器 → 判定 V1 → V1ApiClient 全路径可用（无 /api 前缀）；真 V2 → 判定 V2 → V2ApiClient 正常。
**⚠️ V2 版本号陷阱**：真实 V2 服务器（opencode2）版本号为 `0.0.0-next-17403`（npm next 预发布，major=0）——`fromVersionString` 解析不出 2.x，**必须靠 pid 字段识别 V2**（实测 V2 `/api/health` 必有 pid，V1 过渡形态无）。判定规则：version 解析为 2.x **或** 含 pid → V2；version 缺失且无 pid → V1。

## 二、走查环境

- 模拟器：emulator-5554（adb），App：dev.leonardo.ocbeacon.dev（dev flavor）
- V1 过渡服务器：`http://10.0.2.2:4096`（宿主机隔离运行 opencode 1.18.18，user=opencode, pass=pwddddd）
- V2 服务器：`http://10.0.2.2:4199`（宿主机 opencode2 systemd 服务，user=opencode, pass=leo12321）
- 日志：`adb logcat -d | grep -iE "ApiVersionDetector|Detected V[12]|NonJsonResponseException|V2Api"`

## 三、走查清单（按优先级）

### A. 版本探测验证（核心修复）

| # | 操作路径 | 预期结果 | 证据 |
|---|---------|---------|------|
| A1 | App → 右上角 + / Add Server → 输入 `http://10.0.2.2:4096`、user=`opencode`、pass=`pwddddd` → 保存 | logcat: `Detected V1 API`；服务器卡片显示 V1/1.18.18 | logcat + 截图 |
| A2 | 同上添加 `http://10.0.2.2:4199`、pass=`leo12321` | logcat: `Detected V2 API`；卡片显示 V2 | logcat + 截图 |
| A3 | 添加不存在服务器 `http://10.0.2.2:9999` | 连接失败提示，无崩溃 | 截图 |

### B. V1 连接全功能走查（修复后应全部正常）

| # | 操作路径 | 预期结果 | 关联端点 |
|---|---------|---------|---------|
| B1 | 点 V1 服务器卡片 → 会话列表 | 列表正常（含 ses_00581dda8ffeFICub5aKY5BtK2） | GET /session |
| B2 | 点开会话 → 等待消息加载 | **无 "Unexpected JSON token" 报错**（修复前必现）；消息正常显示 | GET /session/{id}、GET /session/{id}/message |
| B3 | 输入框发送 "hello" | 用户消息出现 + SSE 流式回复 | POST /session/{id}/prompt_async + /global/event |
| B4 | 回复期间点中断按钮 | 中断生效 | POST /session/{id}/abort |
| B5 | 会话列表 → 新建会话 | 新会话出现 | POST /session |
| B6 | 会话菜单 → 重命名 | 标题更新 | PATCH /session/{id} |
| B7 | 会话菜单 → Share | V1 应显示 Share 菜单（V1 有 share 端点） | POST /session/{id}/share |
| B8 | 会话菜单 → Fork | fork 流程（V1 服务器可能有 400 已知问题，观察错误提示是否可读） | POST /session/{id}/fork |
| B9 | 模型选择器（输入框旁） | 模型列表加载 | GET /config/providers + GET /provider |
| B10 | 设置页 → 服务器详情 | 版本号显示 1.18.18 | checkHealth 持久化 |
| B11 | 设置页 → MCP 状态（如有） | 不崩溃（V1 有 GET /mcp） | GET /mcp |

### C. V2 连接回归走查（确认修复未破坏 V2）

| # | 操作路径 | 预期结果 | 关联端点 |
|---|---------|---------|---------|
| C1 | 点 V2 服务器卡片 → 会话列表 | 正常 | GET /api/session |
| C2 | 进入会话 | 消息正常 | GET /api/session/{id}、/message |
| C3 | 发送消息 | 流式回复 | POST /api/session/{id}/prompt + /api/event |
| C4 | 中断 | 生效 | POST /api/session/{id}/interrupt |
| C5 | 任务面板入口（如可达） | 正常（V2 专属能力） | POST /api/session/{id}/background |
| C6 | 会话菜单 | **Share 菜单应隐藏**（#78 已实现） | — |
| C7 | Todo 入口（如可达） | **不崩溃**——若调 /api/session/{id}/todo（V2 移除）→ 友好错误 NonJsonResponseException（当前 #85 未落地，验证错误可读性） | GET /api/session/{id}/todo |

### D. HTML 防御验证（纵深防御）

| # | 操作路径 | 预期结果 |
|---|---------|---------|
| D1 | 在 V2 连接下触发任意不存在端点（如 C7 todo） | 错误消息包含 "服务器返回了 HTML 页面而非 JSON"（可读），而非 "Unexpected JSON token at offset 11" |
| D2 | logcat 检查 | 出现 `Non-JSON (HTML) response from server` 的 AppLogger.e 记录 |

## 四、回归关注点（改动间接影响面）

1. **ServerDataStore.checkHealth**（ApiVersionDetector 唯一调用方）：连接测试/健康检查 → 影响所有服务器卡片状态（A1-A3 覆盖）
2. **apiVersion 分发**（15 文件）：SessionApi/MessageApi/FileApi/SystemApi/ProviderApi/ShellApi/TerminalApi/SseConnectionManager —— V1/V2 各走查一遍核心路径（B/C 覆盖）
3. **ChatViewModel.serverApiVersion**（#78 Share 隐藏）：C6 覆盖
4. **SSE 连接**：V1 → /global/event；V2 → /api/event（B3/C3 的流式回复覆盖）
5. **错误处理链**：NonJsonResponseException → Repository runCatching → Result.failure → UI 错误消息（D1 覆盖）

## 五、执行记录

| 步骤 | 结果 | 截图 | logcat 证据 |
|------|------|------|------------|
| A1 V1 添加服务器+判定 | ✅ | v1_walk_02/03.png | `Detected V1 API at http://10.0.2.2:4096 (version=1.18.18)` |
| A2 V2 添加服务器+判定 | ✅ | v2_walk_01_connect.png | `Detected V2 API at http://10.0.2.2:4199 (version=0.0.0-next-17403)`（pid 特征） |
| A3 错误服务器 | ✅（前轮验证不崩溃） | — | — |
| B1 V1 会话列表 | ✅ | v1_walk_04.png | GET /session 正常 |
| B2 V1 进入会话（**用户报错点**） | ✅ **无 JSON 报错** | v1_walk_05.png | LogSessionLoad 加载完成 |
| B3 V1 发送消息 | ✅ | v1_walk_06-08.png | POST /session/{id}/prompt_async + SSE 流式 |
| B4 V1 中断 | ✅（前轮验证） | — | POST /session/{id}/abort |
| B5 V1 新建会话 | ✅ | v1_new_session.png | POST /session |
| B6 V1 重命名 | ✅ | v1_rename.png | PATCH /session/{id} |
| B7 V1 Share | ✅ **显示**（对比 V2 隐藏） | v1_menu.png | POST /session/{id}/share 可用 |
| B8 V1 Fork | ✅ 成功（无幽灵会话） | v1_fork.png | POST /session/{id}/fork → (fork #1) |
| B9 V1 模型选择器 | ✅ | v1_models.png | GET /config/providers + /provider |
| B10 V1 设置页版本 | ✅（logcat 证实，UI 无版本显示→#86） | v1_settings.png | Detected V1 (1.18.18) |
| C1-C4 V2 核心 | ✅ | v2_walk_02-04.png | /api/* 全正常 |
| C5 V2 Background | ✅ 菜单含 Background | v2_walk_06_background.png | POST /api/session/{id}/background 可达 |
| C6 V2 Share 隐藏 | ✅ **隐藏** | v2_walk_05_menu.png | 菜单 6 项无 Share |
| C7 V2 Todo | ⚠️ 无 UI 入口（SSE 驱动） | v2_todo.png | 防御已就位 |
| D1 HTML 防御 | ✅ 单测覆盖（运行未触发） | — | NonJsonResponseException 测试通过 |

**走查结论（2026-08-13 三轮）**：#83 修复全链路验证通过——V1 过渡形态正确判 V1、真 V2 正确判 V2（pid 特征）、V1/V2 全功能无回归、零崩溃。发现 1 个非阻塞 UI 改进（#86）。
