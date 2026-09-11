# #400 C2 字面复验报告（system/message 注入卡 → 字面可见性）

- 日期：2026-09-12（本地）
- 设备：`emulator-5554`（sdk_gphone64_x86_64，独占）
- 应用：`dev.leonardo.ocbeacon.dev` versionName=0.3.0，versionCode=1789140195
- 安装包 md5：`cc7fa9f8ebd3491cffc09043cac857e2`（== 前置要求）
- DSH 服务器：`http://127.0.0.1:3080`（adb reverse tcp:3080 → tcp:3080 已确认；curl 返回 401=需鉴权，正常）
- 工作区：/home/leo-tkp/Documents/code/mine/oc-beacon
- 验证会话：标题「请检索一下是否有GIS、GEO的MCP」→ `session-a84edbf7-5247-4751-a52a-d70b1c028438`（travel 工作区）
- 复验性质：**只读 + 设备操作**；未改产品代码、未跑 Gradle。

## 一、结论

| 判据 | 结果 | 证据 |
|------|------|------|
| C2 字面断言：dump 含 `You are an AI agent powered by DeepSeek Harness` | **PASS** | 展开系统注入卡后 `exp1.xml` / `exp2.xml` 各含 1 个 TextView，文本前缀精确命中 |
| system/message（非空 content）→ 注入类精简卡 | **PASS** | 7 条 system/message 全部经 `SysMsgDiag`（EventCard 分支）渲染，折叠行可点开 |
| 卡标签是否为「插件配置」 | **否（重要偏差点）** | system/message 卡实际标签为「工具目录已变更」；「插件配置」标签属于 user/message 的 source.kind=plugin 注入 |
| 回归：crash buffer 无 ocbeacon | **PASS** | `logcat -d -b crash | grep -i ocbeacon` 无输出（exit=1） |

**判定：字面可见 = PASS**；但请见 §四 标签偏差，这与派单背景中「plugin → 插件配置折叠卡」的预期不一致。

## 二、会话与数据核对（DB 直查，只读取证）

从设备 Room DB（`/data/data/dev.leonardo.ocbeacon.dev/databases/ocbeacon.db`，经 exec-out 流式导出到 /tmp，不改动设备文件）查得：

- 该会话共 468 条 cached_messages：assistant 442 / user 19 / **system 7**。
- 7 条 system/message 的 id 前缀均为 `dsh-sys-v2-to-v3-system-*`（最后一条为旧格式 `dsh-sys-2ee10337-...`），payload `injectionKind=plugin`、`role=system`。
- 7 条的首段文本均以 **"You are an AI agent powered by DeepSeek Harness."** 开头（长度 35651~36497）。
- 全部 7 条都在 App 已缓存并渲染的转录窗口内（第 1 条 rank=0，其余 rank 97/98/99/121/235/251）。

明细见 `docs/acceptance/2026-09-12-400-c2/db-injection-messages.txt`。

## 三、操作步骤与字面断言

1. 起 DSH（token 读 `~/.dsh/token`，`am force-stop` 后以 debug intent 启动）；首页列表**未出现**令牌横幅，列表直接上线。
2. 按标题「请检索一下是否有GIS、GEO的MCP」进入会话（dump0.xml 定位 clickable 行 `[42,1009][1038,1177]` → tap `540 1093`）。
3. 等待转录加载（dump1/dump2），滚动到**转录顶部**（第 1 条 rank=0 即系统注入）。
4. 顶部第一张折叠卡（标签「工具目录已变更」，时间 2026-09-07 08:19:44 = rank 0 系统注入）bounds `[32,310][1048,436]` → tap `540 373` 展开 → dump `exp1.xml`。
5. **字面命中**（节点级证据）：

```
exp1.xml: TextView bounds=[74,296][1006,391]，长度 35651
  "You are an AI agent powered by DeepSeek Harness.\n\nThe DeepSeek Harness implementation checkout is at ..."
exp2.xml: TextView bounds=[74,741][1006,1505]，长度 35651
  "You are an AI agent powered by DeepSeek Harness.\n\nThe DeepSeek Harness implementation checkout is at ..."
ASSERT_FULL("You are an AI agent powered by DeepSeek Harness") = True（两份 dump 均为 True）
```

6. 为验证可复现，又展开相邻第 2 张系统注入卡（`exp2.xml`），同样命中。
7. 用「快速定位」面板另跳一处（`j12.xml`），页面中仍可见已展开的系统注入卡正文字面节点 `[74,684][1006,1448]`（head 同上），第 3 次命中。
8. 截图 `expanded-cards.png`。

完整节点抽取见 `literal-nodes.txt`。

## 四、渲染分支取证（解释标签来源）

DEBUG 构建的 `ChatMessageList.kt` 两条分支日志（`render-branch-logs.txt`）：

- `SysMsgDiag: #234 event-card branch RENDER id=0f4ff178 / d937c5e1 / e09767c0 / de9ced07 / 8a33d822 / f23e1c58 / 017385b0`
  → 正是上述 7 条 system/message，全部走 **role=="system" 分支**，渲染为 EventCard，标签 `chat_event_tool_catalog_changed`＝「工具目录已变更」。
- `InjCard: render ... kind=plugin role=user`（如 `seq-...-2886/2888`）
  → 「插件配置」/「上下文注入」标签只出现在 **user/message + source.kind** 路径。

即：**system/message 的 injectionKind=plugin 被 role=="system" 分支抢先**，因此不会显示「插件配置」。字面可见性不受影响——系统注入卡的折叠正文即注入全文。

## 五、回归

```
$ adb -s emulator-5554 logcat -d -b crash | grep -i ocbeacon
（无输出，exit=1）
```

结果写入 `crash-check.txt`（0 行）。

## 六、证据清单（均位于 docs/acceptance/2026-09-12-400-c2/）

| 文件 | 说明 |
|------|------|
| `dump0.xml` | 会话列表（目标标题可见 + clickable 行 bounds） |
| `dump1.xml` / `dump2.xml` | 进入会话后转录加载 dump |
| `scroll1..8.xml` | 向上滚动过程帧 |
| `top/t2..t40.xml`、`top/topfinal.xml` | 滚动至顶过程帧（topfinal=顶部，含 rank 0 系统卡） |
| **`exp1.xml`** | **展开 rank 0 系统注入卡后的 dump（字面命中）** |
| **`exp2.xml`** | **展开相邻第 2 张系统注入卡后的 dump（字面命中）** |
| **`j12.xml`** | **快速定位另跳一处后仍见已展开系统卡正文字面（第 3 次命中）** |
| `exp1b.xml` | exp1 之后复 dump |
| `expanded-cards.png` | 展开后截图 |
| **`literal-nodes.txt`** | **字面断言的节点级抽取（bounds/length/head）** |
| `db-injection-messages.txt` | 7 条 system/message 的 DB 明细（时间/id/injectionKind/首段） |
| `render-branch-logs.txt` | SysMsgDiag / InjCard 渲染分支日志 |
| `crash-check.txt` | crash buffer 回归结果（空） |
| `bot.xml`、`u1..6.xml`、`cur.xml` | 底部与「插件配置」搜索过程帧（未在底部帧命中插件配置卡） |

## 七、局限与说明

- 本判定只证明「system/message 注入卡展开后字面可见」，不主张标签为「插件配置」。
- 「插件配置」卡（user/message plugin 注入）未在本轮成功展开取证（其位于转录后段，快速定位/滚动未稳定命中）；但 7 条字面承载消息经 DB 确认全部是 system/message，与「插件配置」标签无涉。
- 会话首页显示「28」（轮次/条目计数）与 DB 468 条 cached_messages 不冲突：前者为列表元数据计数口径。
