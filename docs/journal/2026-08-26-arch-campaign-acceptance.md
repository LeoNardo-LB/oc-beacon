# 2026-08-26 架构战役验收（用户授权自动化验证）

> 用户指示：不在现场，授权通过日志/数据库/dumpsys 等观测手段验证，确认无问题即标记完成。
> 设备：houji（e69a99d8），构建 0.3.2-dev.2（07:58 安装，含全部 19 commit）。

## 自动化验证矩阵与证据

### #234 part 管线深化（e4c7367e/2b2be2cc/986c65c5）
- **流式生命周期**：真实发送消息（20:41），logcat 捕获完整链——用户消息播种（msg_...2001）→ assistant 开流（msg_...d001, completed=null）→ 48ms 批 flush（batch #33401）→ completed=1787748150044 落定；零崩溃
- **Room 直查**（拉库 + 主机 sqlite3）：新消息 = 1×text(315字符) + 1×reasoning；重复文本组=0；空 payload=0
- **空 part 不变量**：唯一空 reasoning part 为自定义 id（`..._r` 服务器原生 id，非派生契约）——#223 文档化例外（自定义 id 不折叠），启动清扫清理 + 读路径 merge 过滤兜底，行为符合设计
- **kind 正确性**：reasoning 与 text 分属独立 part（id 后缀 _r/_t），无串流
- **fling 压力**：终版构建 8+22 次激进交替 fling——进程存活、crash 缓冲零内容、SafeFling 防御零触发

### #233 卫生项（4b48c67b）
- 冷启动正常（含 C7 迁出的 unread bootstrap 启动路径）；设置页完整渲染
- DataStore 直查：全部设置键在位（expand_reasoning/show_turn_dividers/notifications_enabled/silent_notifications/haptic_feedback/auto_allow_permissions/keep_screen_on/auto_expand_tools）；zombie 键 show_pending_todo_drawer 残留为死字节（无读写者，零行为影响）

### #237 自主批次（C4+C10/C5/C7+C8/C1）
- **C4 压缩分割线**：/compact 触发服务器取消（Compaction cancelled）→ **失败分割线 + snackbar「Failed to compact session」正确渲染**（#219 失败路径）；V1 会话中「Context compacted」摘要分割线正确呈现（V1 认领路径）
- **C10 分页**：362 消息会话上滑——自动加载指示器出现、消息零重复（DB 权威核验 0 重复 id）、零空气泡；11 例 JVM 矩阵覆盖触发面
- **C1 双方言**：V2 全链路（POST model→agent→prompt 三连编排 + SSE 流式 + 轮询 /api/*）；V1 服务器（1.18.18）列表加载、进会话消息渲染、压缩分割线——pick(conn) 路由两侧实证
- **C5 存储**：未读键（session_read_times_<serverId>/all_read_<serverId>/session_last_reply_time）与设置键同文件共存 = 零迁移铁证；两服务器 read-times 各自在位；列表「Unread messages」badge 活信号
- **C7/C8**：EventDispatcher 迁出后启动链正常；V1 /status 返回 ConfigInvalidError 被分类捕获（SessionApi E 级日志）不崩溃——ApiErrorTranslator 防御路径实证

### #236 通知族（6a25d762/122c2b07）
- **①后台轮次完成通知（活体）**：dumpsys notification 实证——opencode_tasks 渠道（importance=4，带音）+ 服务器分组聚合通知；标题「就绪 · 杭州公积金仲裁资料清单」+ 正文=最后一条用户消息（与设计逐字吻合）
- **②前台抑制（活体）**：前台完成轮次（shell 工具执行 completed=1787748964905）→ dumpsys 计数不变（零新通知）——抑制半路径实证；提示音动作由 29 例路由矩阵 JVM 覆盖（音频输出依赖设备音量，非可盲测项）
- **③权限通知 / ④错误连发**：29 例路由矩阵 JVM 覆盖（含 permission 事件族/Q3 R4 streak 门控）；活体触发需特定服务器条件未合成。4 场景人工清单保留于本文件供日常使用中顺带复核

## 结论
四卡全部标记完成。#236 ③④ 为「JVM 矩阵覆盖 + 端口接线活体实证」级验证（非全活体），已在卡片迁移时注明——如日常使用遇通知异常按本文件清单回归。

## 全程崩溃记录
crash 缓冲全程为空；进程 19486 全程存活（20:39–20:56 观测窗）。
