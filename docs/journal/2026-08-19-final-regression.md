# 终局回归记录（2026-08-19）
> 状态：部分完结（活跃 #156）
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）
> 条目编号：Room tokens 持久化=#156


- **D0 静态全绿**：compileDevDebugKotlin ✓ / 全量单测 --rerun ✓ / lint **0 errors**（门禁 no new issues）✓ / i18n-check 628 键 ×14 语言 ✓ / androidTest 编译 ✓
- **能力域 A（启动/连接/列表）**：冷启 2436ms（2 次取优）/ 热启 115ms / crash buffer 0B / dropbox 本次窗口零新增（历史 7 条 08-17 并发构建损坏签名与本次无关）/ Accept_AB 已连接 API v2 / 列表 12 会话 / 搜索实时过滤 ✓（CJK 注入受模拟器 LatinIME 限制，AB 代测同路径）/ 滚动 gfxinfo Janky 74% p50=73 p90=97 p99=117ms（debug+软渲染基线）✓（证据 /tmp/regress-a/ 4 截图）
- **能力域 B（聊天发送流/控制/草稿）**：草稿保存恢复 ✓ / 发送即显+输入清空 ✓ / 流式回复 ✓（服务器侧全文 banana apple cherry）/ 停止生成 ✓（截停后空 assistant）/ 模型切换 ✓（model-switched 事件）/ FATAL=0 / 测试会话已清理（证据 /tmp/regress-b/ 4 截图 + REST 双侧验证）
- **能力域 C（卡片/终端）**：工具卡/权限卡/文件查看器/Markdown 渲染——当日早前轮次证据（/tmp/verify-regex/ 21 截图、/tmp/verify-permcard/、/tmp/verify-dm/）；**终端模式本轮实测**：更多选项→终端 进入（黑面像素+键盘 overlay+TerminalDelegate 日志+IME）→ BACK×2 退出正常（/tmp/regress-b/06_terminal.png）
- **全程 FATAL=0、crash buffer 0 字节**；服务器配置 diff=0 复验


- [~] **新增 P3：Room 缓存行 tokens 持久化缺口（token 图标修复的残留，2026-08-19 f37f482d 顺带发现）** `data` `storage`
  - **2026-08-20 修复完成（c71ac4ec，方向 A）**：upsertSsePriority 合并时 CAS 内对比 assistant 行 tokens/cost（null→值 视为变更），变更行经既有 persistSseUpdate→persistQueue 增量落库；节流=变更检测本身（值未变 0 写库；SSE_PRIORITY 仅 REST 快照触发，不在 48ms delta 路径）。新增 4 测试（null→值触发/值未变不重写/无变化行 0 写/流式整行写不增加），全量 1756 绿。**真机 E2E 复验 PASS（2026-08-20，/tmp/tokverify/）**：Room 直查 assistant 行 tokens 落库 44/45（唯一例外为无正文元数据空壳行，tokens:null=0）+ payload 摘录 tokens:{input:130656,...}；落库链路日志完整（seed 107 → L3 REST refresh → reconciled → Room）；冷启 tap+0.65~0.9s 顶栏 context 圆环已在位（双视觉模型 + 像素检测互证）；历经冷启+离线循环后 13:01 终验仍 44/45 稳定；logcat 19.1 万行 FATAL=0。附带观察：离线时顶栏圆环隐藏系 contextWindow 依赖 /api/provider 会话级 REST（ChatViewModel.kt:568-571 已声明可接受）——与 tokens 缓存无关；若期望离线也显示圆环需将 contextWindow 纳入本地持久化（见下条 P3）
  - 现象：V2MessageMapper 补 tokens 映射后（f37f482d），重进会话 UI 图标恢复（REST→内存→UI 链通），但 Room cached_messages 的 assistant 行 tokens 仍为 null（新产生的消息实测同样）
  - 链路分析：REST refresh 走 upsertSsePriority 只更新内存（_messages/_parts），不触发 Room 重写；Room 写入仅在 SSE persistSseUpdate（handleMessageUpdated/delta flush）窗口——重进后的 REST 数据不落库
  - 影响：冷启动/离线瞬间统计图标短暂缺失（REST 成功后立即恢复）；在线使用全程可见。低优先级
  - 方向：SSE_PRIORITY 合并后对 tokens/cost 变化的行触发增量 persist（或 REST refresh 后 persist 变更行）
  - 工时：~2h | 难度：中 | 涉及：MessageEventHandler/MessageStore | 优先级：P3
