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

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->
