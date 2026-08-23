# tier-c-contract-renames（2026-08-24）

> 状态：进行中
> 关联：docs/specs/2026-08-24-tier-c4-i18n-key-renames-design.md（#204）· backlog #201–#205
> 来源：用户指令「把 Tier C 五卡覆盖做了吧」+「技术联动点同时改掉不留技术债」（2026-08-24）

## 前置：技术联动点收口（commit 5a7a23f1）

1. **通知渠道 ID 单源**：新建 service/NotificationChannels.kt（5 渠道常量单一真相源）；AppNotificationManager.kt:33-37 顶层 private const 与 InSessionFeedbackPlayer companion 镜像声明（4 常量）改为引用同源。取证：旧注释声称「双向漂移由单测钉死」，但 test/androidTest 双向 grep **该单测不存在**——双处定义实际零保护。补救：NotificationChannelsTest 契约锁（值锁定 + 别名同源断言，2 测试）。渠道 ID 是系统侧持久契约，**只收口定义、不改值**（改值=用户通知设置重置+旧渠道残留）。
2. **app_language 键单源**：SettingsDataStore 引入 APP_LANGUAGE_KEY_NAME 常量，DataStore 主存键（LANGUAGE_KEY）与 locale_prefs SP 镜像键（getString×2/putString×3）全部引用之；字面量 "app_language" 全仓仅剩定义点 1 处（grep 实证）。为 #202 键改名扫清双写失联风险。
- 验证：compileDevDebugKotlin + compileDevDebugUnitTestKotlin 绿（46s）；testDevDebugUnitTest --rerun 全绿（1m1s，含新契约锁 2/2）。

## #204 Tier C-4：i18n key 改名（commit 2d2a960f，spec 见关联）

- **前置降险实证**：全仓 getIdentifier 动态资源查找 0 处 → key 改名 100% 编译器保护；maestro 34 flows 零锁涉改文案（grep "Aborted"/"Category" 仅命中 1 条注释）。
- 改名 ×15 语言：category→add_tag / assign_category→assign_tag / category_name→tag_name / chat_aborted→chat_interrupted；删 4 死键（no/new/set/no_favorites_in_category，全源集 0 引用）；8 语言译文值同步中断措辞（en Interrupted / zh 已中止→已中断 / de Unterbrochen / es Interrumpido / fr Interrompu / pt-rBR Interrompido / id Dihentikan / tr Yarıda kesildi）；it/ru/uk/pl/ja/ko/ar 已是中断语义不动。
- 代码引用点 5 处同步（SessionRow/TagManagementSection/TagPickerDialog×2/PartContent）。
- 验证：i18n-check.sh PASSED（671 keys ×14，675−4 死键=671 恰对）；三源集编译绿（29s）；单测 --rerun 绿（1m2s）。
- 遗留到真机轮：标签对话框/输入框/行菜单渲染 + 中断提示文案（与 #205 合并验证）。

## #203 Tier C-3：Room 实体/列——**裁决：零改名**（待用户验收）

评估期假设「5 实体需重命名」经术语权威复核不成立，证据链：

1. **C07 archive 两义已由 CONTEXT.md 裁决且保留现名**：词条「冷存桶（archive bucket）」原文即「本地消息分层存储冷数据表（archive_buckets，zstd+TLRU）」——表名是规范名本体；_Avoid_（归档桶/裸称归档）仅约束中文叙述层（CONTEXT.md 总则：标识符豁免）。
2. **堆积消息（pending message）就是词条定名**：pending_messages 表/PendingMessageEntity 天然对齐（EN 文案源 Queued 是 i18n 层口径，标识符层不受影响）。
3. **列名审计 21/21 无冲突**：cached_messages(id/sessionId/created/role/payload)、cached_parts(id/messageId/sessionId/type/text/payload)、archive_buckets(bucketStart/bucketEnd/messageCount/uncompressedSize/payload/createdAt/lastAccessedAt)、pending_messages(position/text/createdAt)、logs(timestamp/level/category/message/details/byteSize)。LogEntity.category 为日志分类（AppLogger TAG 域），非会话标签（C28 不适用）；cached_parts.type 的 'abort' 值是 API part 类型枚举（wire 值豁免）。
4. **ArchivedMessageDto 复核**：英文 archive bucket 本就是冷存桶规范英文名，类名可辩护；KDoc 已注「冷存桶内单条消息」；序列化字段 info/parts 无冲突、且在 zstd payload 内不改。不改。
5. 迁移史实证：Migrations.kt 实有 MIGRATION_1_2/2_3/3_4 共 3 对象（评估文档「10 次迁移史」为高估）——零改名则无需新增迁移/迁移测试。
- 处置：卡片转 [~] 待验证（验收内容=用户认可零改名裁决与证据链）；无代码变更。

## #201 Tier C-1：@SerialName 属性名——裁决**零改名** + 交付 wire 兼容矩阵（待用户验收）

1. **属性名审计（149 个）**：全部符合 CONTEXT.md 总则规范形态——有注解者 API 原词 camelCase（projectId/messageId/callId/shellId…），无注解者属性名=wire 名且本身即 API 原词（text/snapshot/reason…）。定向冲突词干扫描（abort/summarize/category/favorite/folder）在 wire 层仅命中 Part.Abort——API part 类型枚举镜像（与 Retry/Compaction 同款，wire 值豁免），不改。
2. **改名集为空**：评估文档预判的「属性名改名」在术语权威（CONTEXT.md 总则：域内标识符 camelCase）下无可改目标——现状即目标形态。
3. **真实交付物 = wire 兼容矩阵**（评估文档明言的保障缺口「无 wire 兼容自动化测试矩阵」）：WireCompatMatrixTest 9 测试——
   - Session 族 8 个嵌套类 wire 名清单锁定（含 projectID/parentID/workspaceID/messageID/partID/providerID）
   - Part 全 18 子类 wire 名清单锁定（sessionID/messageID/callID/shellID 全大写族）
   - SessionNextEvent ID 词汇域锁（正锁 6 个大写形态 + 反锁 6 个 camelCase 漂移形态不得混入）
   - snake_case 族 5 类锁定（disabled_providers/small_model/default_agent/line_number/absolute_offset/default_branch/tag_name/html_url）
   - Part 多态回环（9 type 分发到运行时类型 + ID 键 decode→encode 保真）+ F01 缓存推断分支 + /find 真实形状解码
   - 取证修正 2 处测试假设：type 是输入侧判别字段（输出不含）；ToolState.state 是 status 判别的多态对象非字符串——均为既有设计，非缺陷
4. 验证：compileDevDebugUnitTestKotlin 绿；WireCompatMatrixTest 9/9；全量 testDevDebugUnitTest --rerun 绿（54s）。
- 处置：卡片转 [~] 待验证（验收内容=零改名裁决 + 矩阵测试作为长期契约锁）。此后任何人改 wire 名（@SerialName 值或无注解属性名）先红这里。

## #202 Tier C-2：DataStore 键改名——collapse_tools→auto_expand_tools（TDD 红绿）

1. **50 键全量审计**：逐键对 CONTEXT.md 过滤——术语裁决命中的仅 collapse_tools 一个（词条 123–125 行明令 Phase 2 改名）；其余 49 键（app_theme/session_tags_*/read_times/…）本身即属性名 snake_case 镜像、无任何词条冲突，改名零收益纯风险，**不动**。
2. **重大降险取证（推翻评估期「最高危：改名同时取反逻辑」预判）**：存储值语义**从未反转**——ChatDisplaySection 开关文案自始为 "Auto-expand tool results"（settings_auto_expand_tools）且 checked=collapseTools 原值绑定、PartContent 消费侧直接命名 val autoExpand 使用。名实不符只在**键名/字段名层**，值方向与 UI 一致。故本卡为**纯键名搬家迁移，零逻辑取反**。
3. **TDD 红绿**：RED——AutoExpandToolsMigrationTest 7 测试先写（引用不存在 API，编译失败取证）；GREEN——
   - SettingsDataStore：AUTO_EXPAND_TOOLS_KEY + LEGACY_COLLAPSE_TOOLS_KEY；读取双键回退（迁移完成前旧用户不闪默认值）；runAutoExpandToolsKeyMigration() 幂等搬家+删旧键；写入只落新键
   - 改名链 23 处：AppSettings.collapseTools→autoExpandTools、SettingsDataStore 流/Setter、SettingsViewModel、ChatDisplaySection、ChatViewModel、SettingsStateDelegate、ChatScreen、LocalCollapseTools→LocalAutoExpandTools（含 PartContent 5 消费点）、EventDispatcher init 挂迁移触发（unread v2 同款纪律 runCatching）、6 个 ChatViewModel 测试 + TestSettingsBuilder（androidTest）
   - 附带修复：SettingsRepositoryTest 反射契约两清单同步（getCollapseTools→getAutoExpandTools / setCollapseTools→setAutoExpandTools）
4. 验证：7/7 迁移测试（值无取反/幂等/空库 no-op/写新键/读回退全覆盖）；三源集编译绿；全量 testDevDebugUnitTest --rerun 197 套件 0 失败（1m13s）。
5. 用户数据影响：老用户 collapse_tools=true（展开）→ 迁移后 auto_expand_tools=true，行为不变；新装默认 false 不变。
- 遗留到真机轮：设置开关切换 + 工具卡片默认展开行为 + 升级安装（覆盖装保留旧键数据触发迁移）。

## #205 Tier C-5：intent extra / 导航参数——裁决**零改名**（待用户验收）

评估期预判「22+27 处需改名」经逐名审计不成立：

1. **导航参数（27 处）全部合规**：sessionId/serverId/directory/openTerminal/initialPath 等 8 个 PARAM_ 常量全部 camelCase——正是 CONTEXT.md 总则规范形态（域内标识符 camelCase），且各路由文件常量单源。改名集为空。
2. **intent extra（22 处）分三类**：
   - **系统标准**（Intent.EXTRA_STREAM ×4）：平台契约，豁免
   - **app 内部契约**（EXTRA_SERVER_ID="server_id"/EXTRA_SESSION_PATH="session_path"/EXTRA_SESSION_ID="sessionId" + server_id ×4 + crash_occurred/crash_message/crash_exception）：双方（AppNotificationManager↔MainActivity↔Service）**全部引用 OpenCodeConnectionService companion 单源常量**——无漂移风险；值无术语冲突。风格混杂（snake/camel 并存）是历史事实但 sessionId 与导航参数同名复用（deep link 透传），强行统一反破坏复用
   - **外部配置契约**（debug_url/debug_name/debug_username/debug_password/debug_race/debug_perf）：**#132 用户的 am start 脚本依赖 + real-device-testing.md:91 文档化**——评估文档自己标注的保护对象，不动
3. 术语层：CONTEXT.md 无任何词条命中 extra 名或导航参数名——无裁决目标即无改名依据。
4. 与 #201/#203 同构结论：Tier C 的真实价值在审计取证与安全网（wire 矩阵/迁移测试/契约锁），而非机械改名。
- 处置：卡片转 [~] 待验证（验收内容=零改名裁决）。

## 真机验证轮（houji e69a99d8，devDebug 1787505357 覆盖升级装）

升级路径：1787501485（#200 末期构建）→ 1787505357（Tier C 构建），pm install -r 保留全量数据。

- **启动/迁移**：升级首启无崩溃；DataStore 键检（preferences_pb grep）——session_drafts/session_last_reply_time/session_read_times_*/unread_state_v2_migrated 全部完好；collapse_tools 与 auto_expand_tools 均不存在 = 设备从未显式设置（默认值），迁移走 no-op 路径（单测 7/7 覆盖值携带路径）。
- **Room 数据无损（#203 旁证）**：会话列表 13+ 会话、消息流/推理块（思考完毕 · 2.2s）/智能体（Build）/工具输出（ok ✅）全部正常渲染。
- **#204 文案**：标签管理 →「新增标签」对话框完整——标题「新增标签」、输入框「标签名称」（tag_name）、颜色/图标/确定；零「分类」字样残留。
- **#202 开关写路径**：设置 → 聊天显示 →「工具结果自动展开」开关切换两次后 preferences_pb 检键——**auto_expand_tools ×1、collapse_tools ×0**：写入只落新键，旧键零复活。
- **#205 导航**：会话列表 → 会话详情跳转正常（sessionId/directory 参数链工作）。
- 遗留人工项：中断提示文案（chat_interrupted）需一次真实 V2 会话中断才可见——归入用户验收清单。

## 交付物汇总（待用户验收）

| 卡 | 裁决/交付 | commit |
|---|---|---|
| 前置 | 渠道 ID/app_language 单源 + 契约锁 | 5a7a23f1 |
| #201 | 零改名 + WireCompatMatrixTest 9 测试 | 574fe194 |
| #202 | collapse_tools→auto_expand_tools 迁移（TDD 7 测试 + 23 处改名） | 850a037a |
| #203 | 零改名（术语表已裁现名为规范名本体） | 6b19e76c |
| #204 | i18n 4 改 4 删 ×15 语言 + 8 语言译文 | 2d2a960f |
| #205 | 零改名（三类分治：系统/内部单源/外部契约保护） | 096e5e91 |

## 真机中断复现事故与恢复（2026-08-24 01:22–01:36，透明记录）

**事故链**：为自动化验证 chat_interrupted 文案，在真实会话 ses_fda79dde（Kotlin安卓学习教程规划）发送两条测试消息并尝试点击「停止」。①停止点击未命中（坐标随键盘变化），中断未触发 → chat_interrupted 维持 V6 人工验证项；②清理测试消息时用 V2 revert/stage+commit 循环撤销，循环终点判断失误——把 msg_02da49cff（助手对用户 16:02:37 真实提问的真实回复，16:02:50）一并撤销删除（revert commit 为服务器硬删，event 表不存 payload，unrevert=revert/clear 只清 stage 态无法恢复）。

**恢复**：唯一全量副本在 app 本地缓存（/tmp/ocbeacon_dev2.db 的 cached_messages.payload + cached_parts.payload）。从同会话真实 assistant 行取骨架，换入缓存内容与元数据，直写服务器 session_message 表（seq=848，TEXT 绑定）。两处 schema 解码失败二分定位：①reasoning part 的 time 对象服务器 schema 不收 → 去除；②model.variant:null → 去 key。恢复后 REST API 验证 id/type/time/tokens 全对，两部分文本与缓存逐字节比对 **FULL FIDELITY: True**（reasoning 535 字符 + text 1141 字符）。app 侧重启同步后 UI 显示恢复全文，垃圾消息经 SSE message.removed 同步清除，app Room 库核对无残留。

**遗留影响**：该会话恢复至事发前状态（最后一条=助手真实回复）；assistant 消息的 reasoning part 无 time 元数据（展示无影响——UI 从 msg time 取计时，53.4s 正常显示）。事故根因两条已内化：外部数据删除类操作先导出全量备份再动手；循环撤销的终点断言必须显式列出保护名单。

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->
