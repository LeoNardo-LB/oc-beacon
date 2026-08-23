# 盘点：strings.xml 全量（英文源 values/ + 14 语言翻译 values-*/）

> Phase 1 事实收集（只记录事实，不做术语裁决）。裁决背景：规范名以 OpenCode API 术语为权威源；UI 文案纳入术语表；注释未来统一中文；不改标识符。
> 本文件随读取进度全量覆盖写盘。英文源已精读完毕；翻译文件逐批进行中。

## 覆盖清单

| 文件 | 状态 | 注释语言现状 | 备注 |
|------|------|----------|------|
| values/strings.xml | ✓ 已读 | 中英混合 | 786 行；XML 注释中英混合（分区标题英文 + 功能沿革注释中文）；术语密度最高，是 API 一致性主战场 |
| values-ar/strings.xml | ✓ 已读 | 中英混合（分区英文注释 + 2 条中文沿革注释） | 726 行/**615 条，缺 55 key**（完整清单见覆盖缺口节）；شل/شِل/Shell 三种 shell 写法；working directory 误译「مجلد عمل」（folder 词） |
| values-de/strings.xml | ✓ 已读 | 中英混合（分区英文注释 + 3 条中文沿革注释） | 710 行/**670 条全覆盖**；conversation 双词 Unterhaltung/Gespräch；Sie/du 敬语漂移 |
| values-es/strings.xml | ✓ 已读 | 中英混合（分区英文注释 + 3 条中文沿革注释） | 710 行/**670 条全覆盖**；3 处西语文案混入全角中文括号（） |
| values-fr/strings.xml | ✓ 已读 | 中英混合（分区英文注释 + 3 条中文沿革注释） | 708 行/670 条；唯一已译 chat_images_optimized_summary 的文件；Sous-agent 无英文残留；menu_compact_session 用 Réduire 与 Compacter 漂移 |
| values-id/strings.xml | ✓ 已读 | 中英混合（分区英文注释 + 2 条中文沿革注释；**670 条全覆盖**；**保留最全的英文分区注释结构**，含 Slash Commands/Tool Display/Patch File Actions/Chat pagination 等） | 716 行/**670 条全覆盖**；sub-agent/fork/shell 全部借词不译 |
| values-it/strings.xml | ✓ 已读 | 中英混合（分区英文注释 + 3 条中文沿革注释） | 710 行/**670 条全覆盖**；通知文案残留英文 Sub-agent；tag/Provider 不译 |
| values-ja/strings.xml | ✓ 已读 | 中英混合（分区英文注释 + 2 条中文沿革注释） | 710 行/**643 条，缺 27 key**（任务面板/shell 状态/未读/更新检查等组，完整清单见覆盖缺口节）；通知残留英文 Sub-agent/Agent |
| values-ko/strings.xml | ✓ 已读 | 中英混合（分区英文注释 + 3 条中文沿革注释） | 710 行/670 条全覆盖；토킹→토큰 错字、两句语法错字；디렉터리/디렉토리 表记漂移；通知残留 Sub-agent/Agent；3 处全角括号（同 es/tr） |
| values-pl/strings.xml | ✓ 已读 | 中英混合（分区英文注释 + 3 条中文沿革注释） | 718 行/**670 条全覆盖**；subagent 四种拼写（pod-agent/Sub-agenci/sub-agent/Podagent）；powłoka/Shell 混用；fork 误译 Rozdziel |
| values-pt-rBR/strings.xml | ✓ 已读 | 中英混合（分区英文注释 + 3 条中文沿革注释） | 710 行/670 条全覆盖；subagente/Sub-agente 连字符漂移；turno/rodada turn 漂移；「Criar sessao」缺波浪号 |
| values-ru/strings.xml | ✓ 已读 | 中英混合（分区英文注释 + 3 条中文沿革注释） | 719 行/**567 条，缺 103 key**（覆盖最落后，与 uk 并列）；под-агент/Суб-агент、реплика/ход、каталог/директория 三重漂移 |
| values-tr/strings.xml | ✓ 已读 | 中英混合（分区英文注释 + 3 条中文沿革注释） | 710 行/**670 条全覆盖**；conversation 漂移 sohbet/konuşma；Kabuk/Shell 混用；3 处全角括号（同 es） |
| values-uk/strings.xml | ✓ 已读 | 中英混合（分区英文注释 + 3 条中文沿革注释） | 718 行/**567 条，缺 103 key**（与 ru 同列表）；тур/ход、каталог/директорія 漂移；reasoning 译词疑似生造 |
| values-zh-rCN/strings.xml | ✓ 已读 | 中英混合（分区英文注释 + 3 条中文沿革注释 + Missing translations 标记） | 713 行/**670 条全覆盖**；agent 译名四分裂（智能体/代理/Agent/Sub-agent） |

## 术语观察（英文源 values/strings.xml）

| 概念 | 观察到的变体 | 位置 文件:行 | 与 API 词一致? |
|------|--------------|--------------|----------------|
| 会话 | session（主流，~40 处 key/文案） | values/strings.xml:37-85 等全域 | ✅ 一致（API: session） |
| 会话（口语变体） | conversation ×2："Start a conversation with OpenCode"；"Originating task not found in this conversation" | values/strings.xml:119, 656 | ❌ 非规范词 |
| 会话（屏名变体） | chat："Chat"（标题占位）、"Open chat"、Chat Display/Chat Behavior 设置区 | values/strings.xml:586, 647, 358-359 | ❌ 同屏双名（Session 屏内称 Chat） |
| 消息 | message（全文一致） | values/strings.xml:145-153, 197 等 | ✅ 一致（API: message） |
| 轮次 | turn："dividers between messages in the same turn"、"when this turn ends" | values/strings.xml:372, 99 | ✅ 一致（API/文档: turn） |
| 智能体 | agent / Assistant（标签文案 chat_label_agent="Assistant"） | values/strings.xml:179, 593 | ✅ API 有 agent；"Assistant" 是角色显示名（API role: assistant） |
| 子智能体 | sub-agent（带连字符）："Running sub-agent"、"Sub-agent" 工具卡 | values/strings.xml:173, 282, 176, 185-186 | ⚠️ API 词为 subagent/task agent；连字符拼写不统一（见下行） |
| 子智能体 | subagent（无连字符）："Subagents" 标签、"%1$d subagent(s) running in foreground" | values/strings.xml:241, 239, 243 | ⚠️ 与 sub-agent 并存，两种拼写 |
| 智能体入口 | Agents（贴底工具栏） | values/strings.xml:276 | ⚠️ 同屏有 Agents / Subagents 两级叫法 |
| 任务 | task（Tasks 面板、task_sheet_*、Task activities） | values/strings.xml:184, 240-245 | ✅ 一致（API: task） |
| 工具 | tool（tool_* 前缀 20+ 条） | values/strings.xml:270-287 | ✅ 一致（API: tool） |
| 工具调用角色 | Tool calls（上下文分解角色行） | values/strings.xml:594 | ✅ 一致（API part: tool） |
| 排队消息 | queued（文案）/ stack、stacked（key 名） | values/strings.xml:96-118, 584 | ⚠️ key 用 stack 族（chat_busy_menu_stack、pending_tab_stacked），显示词统一 Queued；两套词并存于 key 与文案之间 |
| 上下文压缩 | compact："Compact session"、"Session compacted"、"Context compacted" | values/strings.xml:260, 141, 565, 573 | ✅ API 命令 /compact；但见下行冲突 |
| 上下文压缩（动词变体） | compress(ing)："Compressing context: %1$s" | values/strings.xml:125-126 | ❌ compact 与 compress 混用同一概念 |
| 图片压缩 | compress/optimize："Optimize image attachments"、"Compress"（a11y） | values/strings.xml:381, 636, 662 | ⚠️ compress 一词二义（上下文压缩 vs 图片压缩）；图片侧 key=compress、文案=Optimize |
| 思考 | thinking："Thinking"、"Thought for %1$s" | values/strings.xml:188-189, 200 | ⚠️ API part 类型为 reasoning；thinking 是 UI 词 |
| 推理 | reasoning："Auto-expand reasoning"、"Reasoning"（token 分类） | values/strings.xml:228, 369-370 | ✅ 一致（API: reasoning） |
| 撤销/重做 | undo / redo / revert 三词并存，各自独立命令 | values/strings.xml:127-129, 151-153, 199, 264-265 | ✅ API 各有对应（undo/redo/revert），但 revert 文案 "This will undo this message…" 用 undo 作解释动词 |
| 目录（服务端项目目录） | directory："Directory Details"、"Empty directory"、"Recent directories" | values/strings.xml:69, 53, 350-353 | ✅ 一致（API: directory/project） |
| 目录（客户端分组） | folder："Create folder"、"Folders" 视图 | values/strings.xml:40-47, 701 | ⚠️ 与 directory 并存；folder=客户端分组、directory=服务端目录，未在文案层显式区分 |
| 项目 | project："Open other project…" | values/strings.xml:49-52 | ✅ 一致（API: project） |
| 工作区 | workspace："View Workspace"、workspace_* | values/strings.xml:570, 685-698 | ✅ 一致（API: workspace/file） |
| 权限 | permission（key/文案主流） | values/strings.xml:300-317 | ✅ 一致（API: permission event） |
| 权限-拒绝 | deny（key：permission_deny）vs Reject（显示词） | values/strings.xml:304, 308-312 | ⚠️ key=deny、文案=Reject，英文源内部已分裂 |
| 问题 | question（全文一致） | values/strings.xml:319-328, 475-476, 490 | ✅ 一致（API: question event） |
| 分类/标签 | category（key：category、no_category、new_category、assign_category）vs Tag（显示词"Add Tag"、"Tag name"） | values/strings.xml:511-533 | ❌ key 与文案两套词（category vs tag）混用同一概念 |
| 收藏 | favorite："Favorites"、"No favorite sessions yet" | values/strings.xml:505-512 | ✅（客户端本地概念，无 API 对应） |
| Token | token（chat_token_*、"%d tokens"） | values/strings.xml:225-231, 548 | ✅ 一致（API: token usage） |
| 输入/输出 | Input / Output / Reasoning / Cache read / Cache write | values/strings.xml:226-230 | ✅ 一致（API usage 字段） |
| 供应商 | provider | values/strings.xml:428-450 | ✅ 一致（API: provider） |
| 模型 | model、model variant（a11y_icon_model_variant） | values/strings.xml:209-210, 453-457, 681-682 | ✅ 一致（API: model） |
| Shell | shell（shell mode、shell command） | values/strings.xml:92-93, 284, 292-295 | ✅ 一致 |
| 终端 | terminal（Terminal 工具卡、terminal_* tab 系） | values/strings.xml:273, 121-123, 705-709 | ✅ 一致；但 Shell/Terminal 两概念并存易混（shell mode 发命令 vs Terminal PTY 标签页） |
| 子会话 | sub-session："open the sub-session for the full transcript" | values/strings.xml:232 | ⚠️ API 无 sub-session 词（task/subagent session），自造复合词 |
| 会话状态 | key: idle/busy/retry；显示词: Idle / Working… / Retrying | values/strings.xml:58-60, 54-56 | ✅ FSM 状态名（idle/busy/retry）保留在 key；文案用口语显示词 |
| 未读 | unread："Unread messages"、"Mark all as read" | values/strings.xml:784-785 | ✅（红点时钟域相关 UI 词） |
| 分享 | share / unshare | values/strings.xml:138-144, 262-263, 575 | ✅ 一致（API: share） |
| 导出 | export（menu_export_session 等） | values/strings.xml:576-578 | ✅ 一致 |
| Diff | diff（Diff 摘要、Diff 切换） | values/strings.xml:87-88, 738-739 | ✅ 一致 |
| 连接 | connection / connect / disconnect | values/strings.xml:9-13, 467-483 | ✅ 一致 |
| 批注 | annotation（Phase 3 功能） | values/strings.xml:747-763 | ⚠️ 客户端本地概念；注释称"Modification note" |
| 服务器 | server / OpenCode server / OpenCode Connection | values/strings.xml:7-33, 467-468 | ✅ 一致 |
| TODO | TODO（面板标签，与 Queued 并列） | values/strings.xml:104, 107, 118 | ✅ 一致（API: todo） |

## 英文源 XML 注释清单（注释语言现状：中英混合）

- 分区标题注释全英文（<!-- Home Screen --> 等 ~30 处）
- 功能沿革注释全中文：values/strings.xml:86（#120 D2-32 Diff 摘要）、95（堆积消息 busy 菜单）、101（堆积/TODO 面板）、105（抽屉 segment 常显版）、124（#120 D2-10 压缩中提示）、246（任务面板前台/后台标记）、274（堆积/TODO 常驻抽屉显隐）、275（贴底工具栏 agent/shell 入口）、566（转后台 synthetic 系统提示）
- 空分区注释（无条目）：Patch File Actions(298)、Tool card grouping(745)

## 翻译文件逐文件事实（随读随记）

### values-zh-rCN/strings.xml（简体中文，713 行，670 string + 4 plurals——全覆盖）

**术语事实（A）**：
- session=会话（全文一致）；但 chat_empty=「与 OpenCode 开始**对话**」（对应英文 conversation），chat_locate_task_not_found 又译「此**会话**中未找到」——同一英文 conversation 在 zh 内部漂移：对话 vs 会话
- chat_title_placeholder=「Chat」——英文残留未译（与英文源同为占位符，疑似故意，待裁决）
- agent 译名四分裂：chat_label_agent=智能体；chat_status_running_subagent/tool_sub_agent/task_sheet_subagents_tab=子代理；a11y_icon_question=「来自**代理**的问题」、settings_notifications_desc=「**代理**完成任务时通知」（代理）；chat_system_notification/chat_subagent_completed_notification=「**Sub-agent** 完成通知」、chat_background_agent_completed=「**Agent** 完成」（英文残留）
- compact=压缩（cmd_compact=压缩会话、chat_session_compacted=会话已压缩、chat_summarized=上下文已压缩、chat_compressing_context=正在压缩上下文）——英文 compact/compress 分裂在 zh 全部合并为「压缩」；图片侧 a11y_icon_compress=压缩、settings_compress_images=优化图片附件（优化 vs 压缩并存）
- token 双译：chat_token_usage_total=「%,d 个 **token**」（不译）；settings_compress_images_desc=「减少**令牌**消耗」——token vs 令牌分裂
- thinking=思考中/思考完毕；reasoning=推理（chat_token_reasoning、settings_expand_reasoning=自动展开推理内容）——区分保持
- turn=回合（settings_turn_dividers=回合分割线）
- queued=排队（chat_queued=排队中、pending_sheet_title=排队消息）；chat_busy_menu_stack_desc=「**暂存本地**，本轮结束后自动发送」——用"暂存"解释 stack
- category/tag 三分裂：set_category=设置分类、no_category=无分类、new_category=新建分类、no_favorites_in_category=该分类下没有收藏（分类）；category/assign_category=添加标签、category_name=标签名称、tag_label=标签（标签）；new_tag=「新增 **Tag**」（英文残留）
- favorite=收藏；draft=草稿
- directory=目录、folder=文件夹、project=项目、workspace=工作空间、working directory=工作目录——分层清晰
- Shell 处理不一：tool_shell/task_sheet_shells_tab/shell_kill=「Shell」（不译）；chat_send_shell=发送 Shell 命令、chat_shell_mode_hold_send_hint=Shell 模式；tool_terminal=终端
- permission=权限；permission_deny/reject_button 均=拒绝（英文 deny/Reject 分裂在 zh 合并）
- question=问题/提问/提问请求（notification_channel_questions=提问请求 vs chat_question_label=问题）
- provider=提供方；model=模型
- 状态：idle=空闲、busy=处理中…、retry=重试中（英文 Working→处理中）
- unread=有未读消息、mark_all_read=一键已读

**失实/漂移候选（B）**：
- zh:633 注释「Missing translations (added)」——补译批次标记，说明该文件存在按批补译历史
- 覆盖：670/670 全覆盖（初版盘点误报缺 chat_images_optimized_summary——该条为多行条目，单行解析漏计，已用全量 key 对照修正）

### values-ja/strings.xml（日语，710 行，643 string + 4 plurals，缺 27 key）

**术语事实（A）**：
- session=セッション（全文一致）；chat_empty=「OpenCodeと**会話**を始める」、chat_locate_task_not_found=「この**会話**で」——英文 conversation 在 ja 统一译会話（内部一致，但与 session=セッション 未作区分）
- agent=エージェント；subagent=サブエージェント；但 chat_system_notification/chat_subagent_completed_notification=「**Sub-agent** 完了」、chat_background_agent_completed=「**Agent** 完了」（英文残留，与 zh 同款问题——这两个 key 疑似从未翻译）
- compact=コンパクト化（cmd_compact、chat_session_compacted、chat_summarized=コンテキストがコンパクト化されました、menu_compact_session）；compress=圧縮（chat_compressing_context=コンテキストを圧縮中、a11y_icon_compress=圧縮）——ja 保留英文 compact/compress 分裂（コンパクト化 vs 圧縮）
- token=トークン（全文一致，含 settings_compress_images_desc=トークン消費）——无 zh 式分裂
- thinking=思考中；reasoning=推論——区分保持
- turn=ターン；queued=キュー（pending_sheet_title=キュー、chat_busy_menu_stack=メッセージをキューに追加）
- category/tag：set_category=カテゴリを設定、no_category=カテゴリなし、new_category=新しいカテゴリ；category/assign_category=タグを追加、tag_label=タグ——复刻英文分裂，无英文残留
- favorite=お気に入り；draft=下書き
- directory=ディレクトリ、folder=フォルダ（sessions_view_folders）/「フォルダーを作成」（sessions_create_folder）——**フォルダ vs フォルダー 表记漂移**
- project=プロジェクト、workspace=ワークスペース、working directory=作業ディレクトリ
- shell=シェル（tool_shell=シェル、chat_send_shell=シェルコマンドを送信）；terminal=ターミナル——ja 全译，与 zh 的「Shell 不译」策略不同
- permission=権限；deny/reject 均=拒否（合并）
- question=質問（全文一致）
- provider=プロバイダー；model=モデル
- 状态：idle=アイドル、busy=作業中…、retry=再試行中
- about_unofficial=「これはOpenCodeチーム非公式のコミュニティプロジェクトです」——**语义漂移**：英文源说明是 OC Remote 的独立社区 fork、不隶属原项目作者 @crim50n 与 OpenCode 团队；ja 只说「OpenCode 团队非官方社区项目」，丢失 fork 关系与作者免责声明

**失实/漂移候选（B）**：
- **缺 27 个 key**（vs EN 670，全量对照）：chat_shell_output_summary、shell_status_running/exit/done/failed、task_toolbar_action、task_toolbar_subagents、a11y_icon_tasks、task_sheet_subagents_tab/shells_tab/empty_subagents/empty_shells/title、task_foreground/background、task_sheet_history_tab、shell_kill/close/no_output、update_up_to_date/ready_to_install/view_release、server_settings_config_update_failed/providers_load_failed、a11y_icon_selected、session_unread_indicator、mark_all_read——任务面板 + shell 状态 + 未读 + 部分更新/错误文案未译（运行时回退英文）
- about_unofficial 语义缩水（见上）
### values-de/strings.xml（德语，710 行，670 string——全覆盖）

**术语事实（A）**：
- session=Sitzung（全文一致）；conversation 双词：chat_empty=「Starte eine **Unterhaltung**」 vs chat_locate_task_not_found=「in diesem **Gespräch**」——同一英文词两种德译
- agent 双词：chat_label_agent/chat_role_assistant=**Assistent**（助手）；toolbar_agent=**Agenten**、chat_background_agent_completed=「**Agent** abgeschlossen」、a11y_icon_question=「Frage vom **Agenten**」、settings_notifications_desc=「wenn **Agent**…」——Assistent vs Agent 并存；subagent=Sub-Agent（连字符，一致）
- compact/compress 全并作 **komprimieren**（chat_session_compacted=Sitzung komprimiert、chat_summarized=Kontext komprimiert、chat_compressing_context=Kontext wird komprimiert、a11y_icon_compress=Komprimieren）；图片侧 settings_compress_images=Bildanhänge **optimieren**（optimieren vs komprimieren 并存，同 zh 优化/压缩）
- turn 双词：settings_turn_dividers=**Runden**-Trennlinien vs chat_busy_menu_stack_desc=「wenn dieser **Durchlauf** endet」——同一 turn 两种译法
- queued=Warteschlange（einreihen）；chat_queued=「IN WARTESCHLANGE」（全大写，英文 QUEUED 亦全大写）
- token=Token/Tokenverbrauch（一致）；thinking=Denkt nach；reasoning=**Schlussfolgerung**
- category=Kategorie vs tag=Tag（复刻英文分裂，无英文残留）
- directory=Verzeichnis、folder=Ordner；但 settings_recent_directory_count=「Zuletzt verwendete **Ordner** in neuer Sitzung」——directory 误用 Ordner（key 名是 directory，译词串到 folder）；workspace=Arbeitsbereich、working directory=Arbeitsverzeichnis
- Shell 一律不译（Shell-Befehl、Shell-Modus、Shells）；Terminal=Terminal
- permission=Berechtigung；deny/reject 均=Ablehnen（合并）；question=Frage；provider=Provider；model=Modell
- 状态：idle=Leerlauf、busy=Arbeitet…、retry=Wiederholen
- unread=Ungelesene Nachrichten、mark_all_read=Alle als gelesen markieren

**失实/漂移候选（B）**：
- **敬语漂移**：chat_shell_mode_hold_send_hint=「Halten **Sie**…」（敬称 Sie）vs pending_empty=「kannst **du**… tippen」（昵称 du）——同一文件内 Sie/du 混用
- about_unofficial=「inoffizielles Community-Projekt, nicht vom OpenCode-Team unterstützt」——丢失英文源的 OC Remote fork 关系与 @crim50n 免责声明（语义缩水，同 ja）
- 覆盖：670/670 全覆盖（初版误报缺 chat_images_optimized_summary 系多行条目解析漏计，已修正）

### values-es/strings.xml（西班牙语，710 行，670 string——全覆盖）

**术语事实（A）**：
- session=sesión；conversation=conversación（chat_empty 与 chat_locate_task_not_found 一致——14 语言中少数内部一致的 conversation 译法）
- agent 双词：chat_label_agent=**Asistente** vs chat_background_agent_completed=「**Agente** completado」、a11y_icon_question=「Pregunta del **agente**」、settings_notifications_desc=「el **agente**」——Asistente vs Agente；subagent=sub-agente
- compact=compactar / compress=comprimir——保留英文分裂（同 ja）
- turn=turno（divisores de turno、al terminar este turno——一致）
- queued=en cola；chat_queued=「EN COLA」（全大写）
- token=tokens（一致）；thinking=Pensando；reasoning=razonamiento
- category=categoría vs tag=**etiqueta**（tag 译 etiqueta，分裂保持）
- directory=directorio、folder=carpeta、workspace=espacio de trabajo、working directory=directorio de trabajo
- Shell 不译（comando shell、Shells）；Terminal=Terminal
- permission=permiso；deny/reject 均=Rechazar（合并）；question=pregunta；provider=proveedores；model=modelo
- 状态：idle=Inactivo、busy=Trabajando…、retry=Reintentando

**失实/漂移候选（B）**：
- **全角括号 bug**：chat_context_composition=「Composición del contexto（estimada）」、chat_context_msg_summary=「Mensaje %1$d（Usuario…）」、chat_context_other_note=「Resto（incluye system, etc.）」——3 处西语文案使用中文全角括号（），疑为从 zh 模板复制的残留
- about_unofficial 语义缩水（同 de/ja：丢失 OC Remote fork 与 @crim50n 免责）
- 覆盖：670/670 全覆盖（初版误报缺 chat_images_optimized_summary 系多行条目解析漏计，已修正）
### values-fr/strings.xml（法语，708 行，670 string——全覆盖）

**术语事实（A）**：
- session=session（不译，全文一致）；conversation=conversation（两处一致）
- agent 双词：chat_label_agent=**Assistant** vs toolbar_agent=**Agents**、chat_background_agent_completed=「**Agent** terminé」、a11y_icon_question=「Question de l'**agent**」——Assistant vs agent；subagent=**sous-agent**（已译）；chat_system_notification/chat_subagent_completed_notification=「**Sous-agent** terminé」——14 语言中少数把这两条通知也译掉的（zh/ja/de/es/it 均残留英文）
- compact 双动词：cmd_compact=「**Compacter** la session」、chat_session_compacted=「Session **compactée**」 vs menu_compact_session=「**Réduire** la session」——同一命令两种动词；compress=Compression（Compression du contexte）——保留英文 compact/compress 分裂
- turn=tour（两处一致）；queued=file d'attente；chat_queued=「EN ATTENTE」（全大写）
- token=**jetons**（已译：chat_token_usage_total=%,d jetons、chat_images_optimized_summary 亦用 jetons）——与 it/de/es 的不译策略不同，与 zh 令牌同理
- thinking=Réflexion；reasoning=raisonnement——区分保持
- category=catégorie vs tag=**tag**（英文不译）；favorite=favoris
- directory=répertoire、folder=dossier；但 settings_recent_directory_count=「**Dossiers** récents dans une nouvelle session」——directory 译成 Dossiers（与 de 同款 folder/directory 串位漂移）；workspace=espace de travail、working directory=répertoire de travail
- permission 双词：permission_title=「**Autorisation** requise」 vs permission_always_confirm_message 用「**permission**」（法语借词）——Autorisation vs permission 漂移；deny/reject 均=Refuser（合并）
- question=question；provider=**fournisseurs**（已译，多数语言不译）；model=modèles
- 状态：idle=Inactif、busy=En cours…、retry=Nouvelle tentative

**失实/漂移候选（B）**：
- 覆盖：670/670 全覆盖（与其他 9 个完整语言一致；初版「唯一含 chat_images_optimized_summary」为多行条目解析漏计误报，已修正——该多行 key 各完整语言均有）
- about_unofficial 语义缩水（同前：丢失 OC Remote fork 与 @crim50n 免责）

### values-it/strings.xml（意大利语，710 行，670 string——全覆盖）

**术语事实（A）**：
- session=sessione；conversation=conversazione（两处一致）
- agent 双词：chat_label_agent=**Assistente** vs chat_background_agent_completed=「**Agente** completato」、a11y_icon_question=「Domanda dall'**agente**」、settings_notifications_desc=「completamento dell'**agente**」——Assistente vs agente；subagent=**sotto-agente**；但 chat_system_notification/chat_subagent_completed_notification=「**Sub-agent** completato」——英文残留（同 zh/ja）
- compact=compattare（Sessione compattata、Contesto compattato）vs compress=compressione（Compressione contesto）——保留英文分裂
- turn=turno（一致）；queued=in coda；chat_queued=「IN CODA」（全大写）
- token=**token**（不译，一致）；thinking=Elaborando/Pensiero；reasoning=ragionamento
- category=categoria vs tag=**tag**（不译）；favorite=preferiti
- directory=directory、folder=cartella；settings_recent_directory_count=「Directory recenti」（无 de/fr 的串位漂移）；workspace=area di lavoro、working directory=directory di lavoro
- permission=permesso；deny/reject 均=Rifiuta（合并）；question=domanda；provider=**Provider**（不译）；model=modello
- 状态：idle=Inattivo、busy=Operativo…、retry=Riprova
- sub-session=sotto-sessione（chat_task_output_truncated）

**失实/漂移候选（B）**：
- 通知文案英文残留 Sub-agent（chat_system_notification、chat_subagent_completed_notification）
- about_unofficial 语义缩水（同前）
- 覆盖：670/670 全覆盖（初版误报缺 chat_images_optimized_summary 系解析漏计，已修正）
### values-ru/strings.xml（俄语，719 行，567 string + 4 plurals×4 形，缺 103 key）

**术语事实（A）**：
- session=**сессия**（音译，全文一致）；conversation=разговор（两处一致）；chat_title_placeholder=«**Чат**»——已译（多数欧洲语言保留 Chat 原文）
- agent=**Агент**（chat_label_agent=Агент，直译 agent 而非 assistant）；chat_role_assistant=Ассистент（角色行区分保持）
- subagent 前缀漂移：tool_sub_agent/chat_status_running_subagent=«**под**-агент» vs chat_system_notification/chat_subagent_completed_notification=«**Суб**-агент завершён»——под vs Суб 两前缀；chat_background_agent_completed=«Агент завершён」（已译，无英文残留）
- compact/compress 全并作 **сжать/сжатие**（Сессия сжата、Контекст сжат、Сжатие контекста、a11y_icon_compress=Сжать）；图片侧 settings_compress_images=**Оптимизировать**（оптимизировать vs сжать 并存）
- turn 三词：settings_turn_dividers=«Разделители **реплик**» vs settings_turn_dividers_desc=«в одной **реплике**」 vs chat_busy_menu_stack_desc=«по завершении этого **хода**」——реплика（对白）vs ход（一着）漂移
- queued=очередь；chat_queued=«В очереди」（非全大写，与其他语言的大写 QUEUED 风格不同）
- token=токенов（音译一致）；thinking 漂移：chat_status_thinking=«**Обработка**」（处理）vs chat_thinking_complete=«**Размышлял**»/chat_thinking_in_progress=«**Размышление**…」；reasoning=**Размышление**（settings_expand_reasoning、chat_token_reasoning）——**thinking 与 reasoning 在 ru 内均落入 размышление 词族，两概念边界消失**（且 thinking 又有 Обработка 变体）
- category=категория vs tag=тег；但 no_tags_placeholder=«Нет **меток**»——тег vs метка 漂移
- directory 漂移：session_directory_details/sessions_empty_directory=«**каталог**」 vs chat_link_no_workdir=«рабочей **директории**」——каталог vs директория；folder=папка；workspace=рабочая область；project=проект
- permission=разрешение；deny/reject 均=Отклонить（合并）；question=вопрос；provider=Провайдеры（音译）；model=модель
- 状态：idle=**Ожидание**（等待，非「空闲」直译）、busy=Выполняется…、retry=Повтор попытки
- session_untitled=«Без названия」（无 session 字样）
- 复数规则完整实现（one/few/many/other 四形）

**失实/漂移候选（B）**：
- **缺 103 个 key**（vs EN 670，覆盖最落后，与 uk 并列）：任务面板/shell 状态/未读组 + 诊断报告整组（settings_diagnostics、diagnostics_* 14 条、report_* 8 条）+ 更新检查整组（update_* 10 条）+ 批注功能整组（annotation_* 18 条）+ workspace 搜索/viewer 切换（9 条）+ server_settings 错误族（8 条）等——完整清单见「覆盖缺口台账」节
- about_unofficial 语义缩水（同前）

### values-uk/strings.xml（乌克兰语，718 行，567 string + 4 plurals×4 形，缺 103 key）

**术语事实（A）**：
- session=сесія；conversation=розмова（两处一致）；chat_title_placeholder=«Чат»（已译，同 ru）
- agent=Агент（chat_label_agent）；chat_role_assistant=Асистент
- subagent 前缀漂移：под-агент（tool_sub_agent 等）vs «**Суб**-агент завершено»（两条通知）——同 ru 的 под/Суб 漂移
- compact/compress 全并作 **стиснути/стиснення**；图片侧 Оптимізувати（同 ru 的优化/压缩并存）
- turn 漂移：settings_turn_dividers=«Роздільники」（**标题无 turn 词**）vs desc=«в одному **турі**」 vs stack_desc=«після завершення цього **ходу**»——тур vs ход，且标题丢失概念词
- queued=черга；token=токенів（音译）
- thinking=Думає/Думав；reasoning=«**Розумовіння**」——该词非标准乌克兰语词典词（标准或为 міркування/обґрунтування）；settings_expand_reasoning=«**Авторозгортань** розумовіння»——«Авторозгортань» 词形疑为格位误用（应为 авторозгортання）；事实：reasoning 译词生造 + settings 标题语法异常
- category=категорія vs tag=тег（一致，无 ru 的 метка 漂移）
- directory 漂移：session_directory_details=«**каталог**» vs sessions_empty_directory=«порожня **директорія**» vs chat_link_no_workdir=«робочого **каталогу**»——каталог vs директорія（同 ru）
- menu_fork_session=«**Розділити** сесію」——fork 译作「分割」，语义偏移（fork=分叉复制，розділити=切开）；cmd_fork 未同样漂移（Створити нову сесію з повідомлення）
- permission=дозвіл；deny/reject 均=Відхилити（合并）；question=питання；provider=Провайдери；model=модель
- 状态：idle=Очікує、busy=Працює…、retry=Повтор спроби
- 复数规则完整实现（one/few/many/other）

**失实/漂移候选（B）**：
- 缺 103 个 key（与 ru 完全同列表，见「覆盖缺口台账」节）
- chat_session_unshared=«Сесію **розділено**」——unshare（取消分享）误译为「已分割」，与 share=Поділитися（分享）撞词（розділити 一词二义：分割/分享），语义冲突
- about_unofficial 语义缩水（同前）
### values-pl/strings.xml（波兰语，718 行，670 string——全覆盖）

**术语事实（A）**：
- session=sesja；conversation=rozmowa（两处一致）
- agent=**Agent**（直译，chat_label_agent=Agent）；chat_role_assistant=Asystent（区分保持）
- **subagent 四种拼写**：Pod-agent（tool_sub_agent、chat_status_running_subagent）vs Sub-agenci（task_sheet_subagents_tab）vs sub-agent(ów)（task_toolbar_subagents）vs **Podagent**（chat_system_notification、chat_subagent_completed_notification）——同一概念四种写法，14 语言中最严重的拼写漂移
- compact=kompaktować（Sesja skompaktowana、Kompaktuj）vs compress=Kompresowanie（Kompresowanie kontekstu）——保留英文分裂
- turn=tura（Podzielniki tur、w tej samej turze、po zakończeniu tej tury——三处一致）
- queued=kolejka；chat_queued=「W KOLEJCE」（全大写）
- token=tokenów（不译）；thinking=Myśli；reasoning=Rozumowanie——区分保持
- category=kategoria vs tag=**tag**（不译）
- **fork 语义漂移**：menu_fork_session=「**Rozdziel** sesję」（rozdziel=分割）vs cmd_fork=「Utwórz nową sesję z wiadomości」（意译避开）——fork 一处误译一处意译（同 uk 模式）
- **shell 双词混用**：powłoka（已译：polecenie powłoki、Tryb powłoki、Powłoki、Usuń powłokę）vs Shell（不译：toolbar_shell、tool_shell、task_sheet_empty_shells 的「poleceń powłoki」外混 Shell）——同一屏 Powłoki 标签 + Shell 标签并存
- permission 双词：permission_title=「Wymagana **zgoda**」 vs permission_always_confirm_message/settings_auto_allow_permissions=「**uprawnienie**」——zgoda vs uprawnienie；deny/reject 均=Odrzuć（合并）
- directory=katalog（含 settings_recent_directory_count=Ostatnie **katalogi**——无 de/fr 串位漂移）；folder=folder/Foldery；workspace=obszar roboczy；working directory=katalog roboczy
- question=pytanie；provider=Dostawcy；model=model
- 状态：idle=Bezczynny、busy=Pracuje…、retry=Ponawianie próby

**失实/漂移候选（B）**：
- subagent 四拼写并存（见上，主候选修订项）
- about_unofficial 语义缩水（同前）
- 覆盖：670/670 全覆盖（初版误报缺 chat_images_optimized_summary 系解析漏计，已修正）

### values-tr/strings.xml（土耳其语，710 行，670 string——全覆盖）

**术语事实（A）**：
- session=oturum；conversation **双词**：chat_empty=「bir **sohbet** başlat」 vs chat_locate_task_not_found=「Bu **konuşmada**」——sohbet（聊天）vs konuşma（对话）漂移
- agent=**Ajan**（直译）；chat_role_assistant=Asistan；subagent=**alt agent**（三处一致）+ 通知已译（Alt ajan tamamlandı）——**无英文残留**（14 语言中最干净的通知翻译之一，与 fr 并列）
- compact=**küçült**（Oturum küçültüldü、Bağlam küçültüldü、Oturumu küçült）vs compress=**sıkıştır**（Bağlam sıkıştırılıyor、Sıkıştır）——保留英文分裂（küçült vs sıkıştır）
- turn 双词：settings_turn_dividers=「**Sıra** ayırıcıları」 vs chat_busy_menu_stack_desc=「bu **tur** sona erdiğinde」——sıra（排）vs tur（回合）漂移
- queued=kuyruk；chat_queued=「SIRADA」（全大写）
- token=token（不译）；thinking=Düşünüyor；reasoning=Akıl yürütme——区分保持
- category=kategori vs tag=**etiket**（已译）
- fork=çatalla（正确的 fork 术语，无 uk/pl 误译）
- **shell 双词混用**：Kabuk（已译：Kabuk komutu gönder、Kabuk modu aktif）vs Shell（不译：toolbar_shell、tool_shell、task_sheet_shells_tab、shell_kill、task_sheet_empty_shells）——与 pl 相反方向的混用
- permission=izin；deny/reject 均=Reddet（合并）；question=soru；provider=Sağlayıcılar；model=model
- directory=dizin（一致，无串位）；folder=klasör；workspace=çalışma alanı；working directory=çalışma dizini
- 状态：idle=Boşta、busy=Çalışıyor…、retry=Yeniden deneniyor
- render=「İşle」（处理——render 语义偏移，多数语言保留 Render/Рендер/Renderuj）

**失实/漂移候选（B）**：
- **全角括号 bug**（同 es）：chat_context_composition=「Bağlam oluşumu（tahmini）」、chat_context_msg_summary=「Mesaj %1$d（Kullanıcı %2$d · Asistan %3$d）」、chat_context_other_note=「Geri kalan（sistem vb. dahil）」——3 处混入中文全角括号
- about_unofficial 语义缩水（同前）
- 覆盖：670/670 全覆盖（初版误报缺 chat_images_optimized_summary 系解析漏计，已修正）
### values-ar/strings.xml（阿拉伯语，726 行，615 string，缺 55 key）

**术语事实（A）**：
- session=جلسة；conversation=محادثة（两处一致）
- chat_title_placeholder=「Chat」（英文残留，同多数语言）
- agent=**الوكيل**（chat_label_agent；兼有「代理」义）；chat_role_assistant=المساعد；subagent=**وكيل فرعي**；两条通知已译（اكتمل الوكيل الفرعي）——无英文残留（与 fr/tr 同为干净组）
- compact=**تجميع**（تم تجميع الجلسة、تجميع الجلسة）vs compress=**ضغط**（ضغط السياق、جارٍ ضغط السياق）——保留英文分裂
- turn=**دور**（فواصل الأدوار、عند انتهاء هذا الدور——一致；دور 兼有 role 义）
- queued=قائمة الانتظار/قيد الانتظار
- token=**رمزي/الرموز**（已译——chat_token_usage_total=%,d رمزي、settings_compress_images_desc=لتقليل استخدام الرموز）
- thinking=يفكر/تفكير；reasoning=الاستدلال——区分保持
- category=فئة vs tag=**وسم**（已译）
- fork=تفرع（正确的「分叉」词根，无 uk/pl 误译）
- **shell 三写**：شل（tool_shell）vs شِل（chat_send_shell=أمر شِل、وضع الشِل نشط——多出 kasra 变音符）vs Shell（toolbar_shell 英文残留）——同一概念三种形
- terminal=طرفية
- directory=دليل；folder=مجلد；workspace=مساحة العمل；**working directory 误译**：chat_link_no_workdir=「لا يوجد **مجلد** عمل」（مجلد=folder）——working directory 落入 folder 词而非 دليل
- permission=إذن；deny/reject 均=رفض（合并）；question=سؤال；provider=المزودون；model=نموذج
- 状态：idle=خامل（空闲直译）、busy=يعمل…、retry=إعادة المحاولة

**失实/漂移候选（B）**：
- **缺 55 个 key**（vs EN 670）：任务面板/shell 状态/未读组 + settings_recent_directory_count 族（3 条）+ 诊断入口（4 条）+ 更新检查整组（10 条）+ server_settings 错误族（8 条）+ 杂项（menu_quick_navigate、session_retry_attempt、terminal_tab_title、pdf_prev/next、tool_search_pattern/path、a11y_icon_selected）——完整清单见「覆盖缺口台账」节
- about_unofficial 语义缩水（同前）

### values-id/strings.xml（印尼语，716 行，670 string——全覆盖）

**术语事实（A）**：
- session=sesi；conversation=percakapan（两处一致）
- agent=agen；chat_role_assistant=asisten；subagent=**sub-agent**（借词一致：Menjalankan sub-agent、task_sheet_subagents_tab、task_toolbar_subagents）+ 通知已译（Sub-agent selesai——借词嵌入）
- compact=**kompak**（Sesi dikompak、Kompak sesi、Konteks dikompak）vs compress=**mengompresi/kompres**（Mengompresi konteks、a11y_icon_compress=Kompres）——保留英文分裂（kompak vs kompres）
- turn=**giliran**（Pembagi giliran、saat giliran ini berakhir——一致）
- queued=antrean；chat_queued=「ANTRIAN」（全大写）
- token=token（不译）；thinking=berpikir；reasoning=penalaran——区分保持
- category=kategori vs tag=**tag**（不译）
- fork=**Fork**（menu_fork_session=「Fork sesi」——借词保留，无 uk/pl 误译）
- shell=**shell**（全小写借词一致：perintah shell、Mode shell aktif、Hapus shell）；terminal=Terminal
- directory=direktori（含 settings_recent_directory_count=Direktori terbaru——无串位漂移）；folder=folder；workspace=ruang kerja；working directory=direktori kerja
- permission=izin；deny/reject 均=Tolak（合并）；question=pertanyaan；provider=penyedia；model=model
- 状态：idle=Tidak aktif、busy=Bekerja…、retry=Mencoba ulang
- settings_reconnect_mode=「Mode **reconnect**」（半借词）；chat_share_url_copied=「URL **share** disalin」（share 借词嵌入）
- 注释结构事实：id 保留英文源最全的分区注释（Slash Commands、Tool Display Titles、Patch File Actions、Chat pagination、Chat menu actions 等——其他 13 个翻译文件均无这些分区注释）

- about_unofficial 语义缩水（同前）
- 覆盖：670/670 全覆盖（初版误报缺 chat_images_optimized_summary 系解析漏计，已修正）
### values-pt-rBR/strings.xml（巴西葡语，710 行，670 string——全覆盖）

**术语事实（A）**：
- session=sessão；conversation=conversa（两处一致）
- agent 双词：chat_label_agent=**Assistente** vs toolbar_agent=**Agentes**、chat_background_agent_completed=「**Agente** concluído」、a11y_icon_question=「Pergunta do **agente**」——Assistente vs agente（同 es/it/fr/de）
- subagent 连字符漂移：subagente（无连字符：Executando subagente、Subagentes、subagente(s)）vs 通知两条=「**Sub-agente** concluído」（有连字符）——同一文件两种拼写
- compact=compactar vs compress=comprimir——保留英文分裂
- turn 双词：settings_turn_dividers=「Divisores de **turno**」 vs chat_busy_menu_stack_desc=「quando esta **rodada** terminar」——turno vs rodada 漂移
- queued=fila；chat_queued=「NA FILA」（全大写）
- token=tokens（不译）；thinking=Pensando；reasoning=raciocínio——区分保持
- category=categoria vs tag=**tag**（不译）
- fork=**Bifurcar**（正确的 fork 术语，无 uk/pl 误译）
- shell=shell（不译，一致）；terminal=Terminal
- directory=diretório（含 settings_recent_directory_count——无串位漂移）；folder=pasta；workspace=área de trabalho；working directory=diretório de trabalho
- permission=permissão；deny/reject 均=Rejeitar（合并）；question=pergunta；provider=Provedores；model=modelo
- 状态：idle=Inativo、busy=Trabalhando…、retry=Tentando novamente

**失实/漂移候选（B）**：
- sessions_create_session=「Criar **sessao**」——**缺波浪号**（应为 sessão；同文件 sessions_title=Sessões 正确）
- subagente/Sub-agente 连字符漂移（见上）
- about_unofficial 语义缩水（同前）

### values-ko/strings.xml（韩语，710 行，670 string——全覆盖）

**术语事实（A）**：
- session=세션；conversation=대화（两处一致）
- chat_title_placeholder=「Chat」（英文残留，同多数语言）
- agent=에이전트；chat_role_assistant=어시스턴트；subagent=서브 에이전트（空格分写，三处一致）；但 chat_system_notification/chat_subagent_completed_notification=「**Sub-agent** 완료」、chat_background_agent_completed=「**Agent** 완료」——英文残留（同 zh/ja/it/pt-rBR）
- compact/compress 全并作 **압축**（세션 압축됨、컨텍스트 압축 중、a11y_icon_compress=압축）——同 zh/de/ru/uk 合并组；图片侧 settings_compress_images=이미지 첨부 **최적화**（최적화 vs 압축 并存）
- turn=턴（两处一致）；queued=대기열；chat_queued=「대기중」（无空格）
- token=토큰；但 chat_token_usage_total=「%,d **토킰**」——**错字**（토킭/토킰 应为 토큰）；settings_compress_images_desc 用 토큰 正确——同文件两种拼法
- thinking=생각 중；reasoning=추론——区分保持
- category=카테고리 vs tag=태그（均已译）
- fork=**포크**（音译，正确）
- shell=**셸**（音译一致：셸 명령어、셸 모드、셸 제거）；但 toolbar_shell=「Shell」英文残留——셸/Shell 二写
- terminal=터미널
- directory 表记漂移：디렉터리（sessions_empty_directory、settings_recent_directory_count、chat_link_no_workdir 的 작업 디렉터리）vs **디렉토리**（session_directory_details=디렉토리 세부정보、permission_always_confirm_message=디렉토리）——外来语表记两代并存（디렉터리 为标准形）；folder=폴더；workspace=워크스페이스
- permission=권한；deny/reject 均=거부（合并）；question=질문；provider=프로바이더；model=모델
- 状态：idle=**대기 중**（等待，非空闲直译）、busy=작업 중…、retry=재시도 중

**失实/漂移候选（B）**：
- **错字 ×2**：chat_token_usage_total=「%,d 토킰」（→토큰）；chat_task_output_truncated=「출력이 **잘린되었습니다** … **여버** 확인하세요」（→잘렸습니다 / 열어）——一句两个语法错字
- **全角括号 bug**（同 es/tr）：chat_context_composition=「컨텍스트 구성（추정）」、chat_context_msg_summary=「메시지 %1$d（사용자…）」、chat_context_other_note=「나머지（시스템 등 포함）」——3 处混入中文全角括号
- 通知文案英文残留 Sub-agent/Agent（同 zh/ja/it）
- about_unofficial 语义缩水（同前）

## 覆盖缺口台账（全量 key 对照：EN 670 strings + 4 plurals = 674 keys）

| 文件 | string 数 | 缺 key 数 | 缺失内容分组 |
|------|-----------|-----------|--------------|
| values-de/es/fr/id/it/ko/pl/pt-rBR/tr/zh-rCN（10 文件） | 670 | **0** | 完整覆盖（4 plurals 也齐） |
| values-ja | 643 | **27** | 任务面板+shell 状态组（19）、更新检查 3 条（update_up_to_date/ready_to_install/view_release）、server_settings 错误 2 条、a11y_icon_selected、未读 2 条（session_unread_indicator、mark_all_read） |
| values-ar | 615 | **55** | 上表 27 条全部 + settings_recent_directory_count 族 3 条 + 诊断入口 4 条（settings_diagnostics/desc、diagnostics_title、report_close）+ menu_quick_navigate + **更新检查整组 10 条**（update_check…view_release）+ **server_settings OAuth/错误族 8 条** + session_retry_attempt、terminal_tab_title、pdf_previous/next_page、tool_search_pattern/path |
| values-ru | 567 | **103** | 上表 55 条全部 + **诊断报告整组**（report_to_github、report_authorizing/hint、report_preview/submit/needs_config/public_warning/retry/cancel、diagnostics_empty/desc/share/copy/clear/clear_confirm_title/clear_confirm_message/log_level/search_hint/crashes/dropped——22 条）+ **批注功能整组 annotation_* 18 条** + workspace 搜索 3 条 + viewer 切换 5 条 + fileviewer_error_tool_snapshot_missing + chat_link_file_not_found + chat_step_progress |
| values-uk | 567 | **103** | 与 ru **完全同列表**（103 条逐一相同） |

事实备注：缺 key 的运行时行为 = 回退英文源显示（Android 资源解析机制）；ja/ar/ru/uk 四文件即当前 i18n 覆盖债的主体。

## 译名漂移对照（逐概念 × 15 文件；⚠=文件内部漂移，✗=英文残留/错字）

| 文件 | session | conversation* | agent(标签) | subagent | compact/compress | thinking/reasoning | token | turn | tag/category | directory/folder | shell |
|------|---------|---------------|-------------|----------|------------------|--------------------|-------|------|--------------|------------------|-------|
| EN(源) | session ✅ | conversation ⚠(与 session/chat 并存) | agent+Assistant ⚠ | sub-agent/subagent ⚠ | compact/compress/summarize ⚠ 三词 | thinking/reasoning 分立 | token | turn ✅ | category(key)/tag(词) ⚠ | directory/folder/project 分层 | Shell/Terminal 分立 |
| zh | 会话 ✅ | ⚠ 对话(chat_empty)/会话(locate) | ⚠ 智能体/代理/Agent ✗/Sub-agent ✗ | 子代理 ⚠+Sub-agent ✗ | 全并「压缩」⚠ | 思考/推理 分立 | ⚠ token(不译)/令牌 | 回合 ✅ | ⚠ 标签/分类/Tag ✗ | 目录/文件夹/项目 分层 ✅ | ⚠ Shell 不译/终端 |
| ja | セッション ✅ | 会話（一致） | エージェント ⚠+Agent ✗ | サブエージェント ⚠+Sub-agent ✗ | ⚠ コンパクト化/圧縮 分立 | 思考/推論 分立 | トークン ✅ | ターン ✅ | ⚠ カテゴリ/タグ | ディレクトリ/⚠フォルダ vs フォルダー | 全译シェル ✅ |
| ko | 세션 ✅ | 대화（一致） | 에이전트 ⚠+Agent ✗ | 서브 에이전트 ⚠+Sub-agent ✗ | 全并압축 ⚠+최적화 | 생각/추론 分立 | ⚠ 토큰/토킰 ✗错字 | 턴 ✅ | 태그/카테고리 分立 | ⚠ 디렉터리/디렉토리 | 셸 ✅+Shell ✗(toolbar) |
| de | Sitzung ✅ | ⚠ Unterhaltung/Gespräch | ⚠ Assistent/Agent(en) | Sub-Agent ✅（一致） | 全并 komprimieren ⚠+optimieren | Denkt nach/Schlussfolgerung | Token ✅ | ⚠ Runde/Durchlauf | Tag/Kategorie 分立 | ⚠ Verzeichnis/Ordner(directory 误用 Ordner) | Shell 不译 |
| es | sesión ✅ | conversación（一致） | ⚠ Asistente/Agente | sub-agente ✅ | ⚠ compactar/comprimir 分立 | Pensando/razonamiento | tokens ✅ | turno ✅ | etiqueta/categoría 分立 | directorio/carpeta 分层 ✅ | shell 不译；⚠3 全角括号 |
| fr | session ✅ | conversation（一致） | ⚠ Assistant/agent | sous-agent ✅（通知亦译） | ⚠ compacter/Réduire/compression 三词 | Réflexion/raisonnement | **jetons 已译** | tour ✅ | tag(不译)/catégorie | ⚠ répertoire/dossier(directory 误用 Dossiers) | shell 不译 |
| it | sessione ✅ | conversazione（一致） | ⚠ Assistente/agente | ⚠ sotto-agente/Sub-agent ✗ | ⚠ compattare/compressione 分立 | Elaborando/ragionamento | token ✅ | turno ✅ | tag(不译)/categoria | directory/cartella 分层 ✅ | shell 不译 |
| ru | сессия ✅ | разговор（一致） | Агент ✅（一致） | ⚠ под-агент/Суб-агент | 全并 сжать ⚠+Оптимизировать | ⚠ Обработка/Размышление vs Размышление（**thinking/reasoning 撞词**） | токенов 音译 ✅ | ⚠ реплика/ход | ⚠ тег/метка/категория | ⚠ каталог/директория/папка | Shell 不译 |
| uk | сесія ✅ | розмова（一致） | Агент ✅ | ⚠ під-агент/Суб-агент | 全并 стиснути ⚠+Оптимізувати | Думає/**Розумовіння 生造** | токенів 音译 ✅ | ⚠ тур/ход+标题丢词 | тег/категорія 分立 | ⚠ каталог/директорія/папка | Shell 不译；⚠fork 误译 Розділити |
| pl | sesja ✅ | rozmowa（一致） | Agent ✅（一致） | ⚠ **四拼写** pod-agent/Sub-agenci/sub-agent/Podagent | ⚠ kompaktować/kompresja 分立 | Myśli/Rozumowanie | tokenów ✅ | tura ✅（三处一致） | tag(不译)/kategoria | katalog/folder 分层 ✅ | ⚠ powłoka(译)/Shell(不译) 混用；⚠fork 误译 Rozdziel |
| tr | oturum ✅ | ⚠ sohbet/konuşma | Ajan ✅（一致） | alt agent ✅（通知亦译） | ⚠ küçült/sıkıştır 分立 | Düşünüyor/Akıl yürütme | token ✅ | ⚠ sıra/tur | etiket/kategori 分立 | dizin/klasör 分层 ✅ | ⚠ Kabuk(译)/Shell(不译) 混用；⚠3 全角括号 |
| ar | جلسة ✅ | محادثة（一致） | الوكيل ✅ | وكيل فرعي ✅（通知亦译） | ⚠ تجميع/ضغط 分立 | يفكر/الاستدلال 分立 | **رمزي 已译** | دور ✅ | وسم/فئة 分立 | ⚠ دليل/مجلد；working dir 误用 مجلد | ⚠ شل/شِل/Shell 三写 |
| id | sesi ✅ | percakapan（一致） | agen ✅ | sub-agent 借词 ✅（通知亦译） | ⚠ kompak/mengompresi 分立 | berpikir/penalaran | token ✅ | giliran ✅ | tag(不译)/kategori | direktori/folder 分层 ✅ | shell 借词 ✅+Fork 借词 |
| pt-rBR | sessão ✅（⚠1 处缺波浪号） | conversa（一致） | ⚠ Assistente/Agente | ⚠ subagente/Sub-agente | ⚠ compactar/comprimir 分立 | Pensando/raciocínio | tokens ✅ | ⚠ turno/rodada | tag(不译)/categoria | diretório/pasta 分层 ✅ | shell 不译 |

*conversation = 英文源 chat_empty(:119) 与 chat_locate_task_not_found(:656) 两处的非规范口语词。*

关键横向事实：
1. **session**：15/15 文件一致（音译或对译），全项目最稳定术语——与 API 权威词完全对齐。
2. **conversation**：EN 自身即非规范；翻译侧 5 文件内部再分裂（zh 对话/会话、de Unterhaltung/Gespräch、tr sohbet/konuşma；其余 9 文件内部一致但与 session 译词不同）。
3. **agent 标签**：EN 用 Assistant；de/es/fr/it/pt 跟随「助手」词根但同时另有 agente/Agent 词根并存；zh/ko/ja 有 Agent/Sub-agent 英文残留通知；ru/uk/pl/tr/ar/id 用「agent」直译且一致。
4. **subagent**：无一文件做到完全统一——最好（de/es/tr/ar/id）也只是单文件一致；pl 四拼写、ru/uk 前缀漂移、pt 连字符漂移、5 文件通知残留英文。
5. **compact/compress**：EN 三词族（compact/compress/summarize key）在翻译侧裂成两阵营——合并组（zh/ko/de/ru/uk）与区分组（ja/es/fr/it/pl/tr/ar/id/pt）；fr 内部再添第三词 Réduire。
6. **thinking/reasoning**：13 文件两词分立；ru 撞词（两概念同落 Размышление 族）、uk 生造词。
7. **deny/Reject**：EN key/文案分裂，但 14/14 翻译全部合并为单一拒绝词——翻译侧已完成事实上的一致化，EN 自身待修。
8. **turn**：9 文件一致；de/ru/uk/tr/pt 内部漂移（Runde/Durchlauf、реплика/ход、тур/ход、sıra/tur、turno/rodada）。
9. **tag/category**：EN key=category/文案=tag 的分裂被 14/14 翻译忠实复刻（均保留两词）；zh 再添 Tag 英文残留、ru 再添 метка 第三词。
10. **directory→folder 串位**：de（Ordner）、fr（Dossiers）把 directory 译成 folder 词；ar 把 working directory 译成 مجلد（folder）；ru/uk/ko 是 directory 本词的两种表记内漂移。
11. **全角括号残留**：es/tr/ko 三文件的 chat_context_composition/msg_summary/other_note 共 9 处——疑似从 zh 模板复制引入。

## 失实文案

| 文件:行/key | 现文案摘录 | 事实依据 | 修订方向 |
|---------|-----------|----------|----------|
| values/strings.xml:515,532 | category/assign_category 两条 key 显示词均为 "Add Tag"；category_name="Tag name" | key 名 category 族与显示词 Tag 族指向同一功能（本地会话分类），两套词汇并存于同一文件 | 待裁决：统一为 tag 或 category（Phase2b 输入） |
| values/strings.xml:304 | permission_deny → "Reject"（而 permission_reject_* key 才叫 Reject） | key=deny、key=reject 两族并存，显示词都是 Reject 族 | 待裁决：deny/reject 择一 |
| values/strings.xml:565 | chat_summarized → "Context compacted" | key 用 summarized、文案用 compacted；同文件 cmd_compact 亦为 compact | key 与文案词汇分裂（历史遗留 summarized） |
| values/strings.xml:125 | chat_compressing_context → "Compressing context: %1$s" | 同概念在 :260/:141/:565 均为 compact；compress 仅此处用于上下文 | 统一为 compacting |
| values/strings.xml:239 | task_toolbar_subagents → "%1$d subagent(s) running in foreground" | :247 task_foreground="Foreground"（前台=阻塞主会话），术语一致但 subagent 拼写在 :173/:282 为 sub-agent | 统一 subagent 拼写 |
| values/strings.xml:662 | a11y_icon_compress → "Compress" | 图片优化功能显示词为 Optimize（:381/:636），此 a11y 描述仍用 Compress | 与 Optimize 统一或明确指图片压缩 |
| values/strings.xml:607 | about_opencode_url = https://github.com/anomalyco/opencode | 需核验：OpenCode 仓库 org 曾为 sst（github.com/sst/opencode）；anomalyco org 真实性待查证 | Phase 2 核验链接 |
| values-zh-rCN(new_tag) | 「新增 Tag」 | 同文件其余 tag 族均译「标签」（tag_label=标签、edit_tag=编辑标签） | 统一为「新增标签」 |
| values-zh-rCN/ja/ko/it/pt-rBR（chat_system_notification 等 2 key） | 「Sub-agent 完成通知」/「Sub-agent 完了」/「Sub-agent 완료」/「Sub-agent completato」/「Sub-agente concluído」 | 同文件已有本地化词（子代理/サブエージェント/서브 에이전트/sotto-agente/subagente） | 用各语言既有 subagent 译词 |
| values-zh-rCN/ja/ko（chat_background_agent_completed） | 「Agent 完成」/「Agent 完了」/「Agent 완료」 | 同文件 chat_label_agent 已译（智能体/エージェント/에이전트） | 同上 |
| values-es/-tr/-ko（chat_context_composition 等 3 key×3 文件） | 如 es「Composición del contexto（estimada）」 | 西/土/韩语文案使用中文全角括号（）（共 9 处），疑从 zh 模板复制 | 改半角括号 |
| values-ko(chat_token_usage_total) | 「%,d 토킰」 | 韩语 token 正确拼法为 토큰（同文件 settings_compress_images_desc 已用 토큰） | 改 토큰 |
| values-ko(chat_task_output_truncated) | 「출력이 잘린되었습니다 — … 여버 확인하세요」 | 잘린되었습니다→잘렸습니다、여버→열어（一句两处语法错字） | 修正错字 |
| values-pt-rBR(sessions_create_session) | 「Criar sessao」 | 葡语正确拼法 sessão（同文件 sessions_title=Sessões 正确） | 补波浪号 |
| values-de(settings_recent_directory_count) | 「Zuletzt verwendene Ordner in neuer Sitzung」 | key 为 directory；de 已定 Verzeichnis=directory、Ordner=folder，此处 directory 译成 Ordner（串位） | 改 Verzeichnisse |
| values-fr(settings_recent_directory_count) | 「Dossiers récents dans une nouvelle session」 | 同上：fr 已定 répertoire=directory、dossier=folder | 改 Répertoires |
| values-ar(chat_link_no_workdir) | 「لا يوجد مجلد عمل」 | working directory 应译 دليل عمل（دليل=directory）；مجلد=folder | 改 دليل |
| values-uk(menu_fork_session) | 「Розділити сесію」 | розділити=分割；fork 语义为分叉复制（EN fork、zh 分叉、ja フォーク、ko 포크、tr çatalla、ar تفرع 均正确） | 改 форк/відгалузити 类词 |
| values-uk(chat_session_unshared) | 「Сесію розділено」 | unshare=取消分享；розділено=已分割，与 Поділитися（分享）撞词，语义反转风险 | 改「Сесію більше не спільно」类 |
| values-pl(menu_fork_session) | 「Rozdziel sesję」 | rozdziel=分割（同 uk 模式）；cmd_fork 处用意译避开 | 改 forkuj/rozgałęź 类词 |
| values-ru(thinking vs reasoning 族) | thinking=「Обработка」，reasoning=「Размышление」，chat_thinking_complete=「Размышлял」 | thinking 与 reasoning 两概念在 ru 均落 размышление 词族（+Обработка 第三词），API part 类型 reasoning 的译名边界消失 | 分配两个稳定词（如 мышление/рассуждение） |
| values-uk(settings_expand_reasoning) | 「Авторозгортань розумовіння」 | розумовіння 非标准词；Авторозгортань 词形格位异常（应为 авторозгортання） | 改标准词 міркування/обґрунтування |
| values-de(chat_shell_mode_hold_send_hint vs pending_empty) | 「Halten Sie…」（敬称）vs「kannst du…」（昵称） | 同文件 Sie/du 敬语混用 | 统一敬语策略 |
| values-ja(sessions_create_folder vs sessions_view_folders) | 「フォルダーを作成」vs「フォルダ」 | フォルダー（长音）与フォルダ（短音）两种表记并存 | 统一表记 |
| 全部 14 语言(about_unofficial) | 各语言均只剩「非官方社区项目、不隶属 OpenCode 团队」 | 英文源完整义：OC Remote 的独立社区 fork + 不隶属原项目作者 @crim50n + 不隶属 OpenCode 团队——翻译丢失 fork 关系与作者点名免责（法律文本缩水） | Phase2b 重译补全免责声明 |

## 待裁决冲突（合并编号；EN=英文源内部，i18n=翻译侧）

1. **概念「会话」三词**：session（EN 规范+15/15 一致）vs conversation（EN :119/:656）vs chat（EN :586/:647/:358 Chat 屏名）｜EN+zh/de/tr 内部再漂移｜API 权威词 session
2. **概念「智能体」显示词**：EN 标签用 Assistant（:179）vs agent（其余）→ de/es/fr/it/pt 译「助手」词根+agente 词根并存；zh 智能体/代理/Agent/Sub-agent 四变体；ru/uk/pl/tr/ar/id 直译 agent 一致｜全文件｜API 词 agent、role 值 assistant——标签显示词待裁决
3. **概念「子智能体」拼写**：EN sub-agent vs subagent（:173 vs :239）→ i18n 放大：pl 四拼写、ru/uk под/Суб 前缀、pt subagente/Sub-agente 连字符、zh/ja/ko/it/pt 通知残留英文、ja/ar/id 连字符借词、fr/es/tr/ar 无连字符一致｜全文件｜API 词 subagent（无连字符）
4. **概念「上下文压缩」词族**：EN compact vs compress vs key=summarized → i18n 两阵营：合并组（zh/ko/de/ru/uk）vs 区分组（ja/es/fr/it/pl/tr/ar/id/pt）；fr 再添 Réduire｜全文件｜API 命令 /compact
5. **compress 一词二义**：上下文压缩（EN :125）vs 图片压缩（EN :381 key/settings_compress_images 显示词 Optimize、a11y :636/:662 Compress）→ i18n 图片侧普遍用 Optimize 词根｜EN+全部翻译｜建议图片侧统一 Optimize、上下文侧统一 compact
6. **thinking vs reasoning**：EN 分立；API part 类型为 reasoning（thinking 非 API 词）→ ru 译内撞词、uk 生造词、13 文件分立｜全文件｜裁决点：EN thinking 是否改 reasoning
7. **概念「本地分类」key/文案分裂**：key=category 族 vs 文案=Tag 族（EN :511-533）→ 14/14 翻译复刻两词；zh 再添 Tag 残留、ru 再添 метка｜全文件｜CONTEXT.md 无既有词条（本地 UI 概念）
8. **directory/folder/project 三层**：EN 分层正确但未显式命名；i18n 串位——de/fr 把 directory 译成 folder 词、ar 把 working directory 译成 مجلد、ru/uk/ko directory 双表记｜7 文件｜API 词 directory/project
9. **deny vs Reject**：EN key=deny（:304）vs 文案 Reject（:304/:308/:312）→ 14/14 翻译已合并为单一拒绝词（事实一致化完成）——EN 自身待修｜EN
10. **shell 翻译策略三态**：全译（ja/ko）vs 不译（de/es/fr/it/ru/uk/pt/zh 部分）vs 混用（pl/tr/ar+ko toolbar 残留）｜10+ 文件｜需定「Shell 是否作产品名保留原文」
11. **turn 漂移**：EN 一致；i18n 内部漂移 5 文件（de/ru/uk/tr/pt）｜与 CONTEXT.md 词条「流式 turn」一致 ✅ 无冲突
12. **token 翻译策略**：借词/音译（11 文件）vs 意译（fr jetons、ar رمزي）vs zh 半分裂（token+令牌）+ko 错字｜全文件
13. **fork 语义**：uk/pl 译作「分割」（Розділити/Rozdziel）有语义反转风险；其余 8 种语言 fork 词根正确｜2 文件
14. **覆盖缺口**：ja 27 / ar 55 / ru 103 / uk 103 key 未译（运行时回退英文）——Phase2b i18n 票直接输入｜4 文件｜ru/uk 缺失列表完全一致
15. **about_unofficial 免责缩水**：14/14 翻译丢失「OC Remote fork+@crim50n 免责」内容｜全部翻译
16. **英文残留策略**：chat_title_placeholder=Chat（15/15 保留，疑似故意）；zh Tag、五语言 Sub-agent/Agent 通知、ko/ar toolbar Shell｜多文件
17. **about_opencode_url org 待核验**：EN :607 = github.com/anomalyco/opencode（历史上属 sst org）｜EN｜Phase 2 核验

### 与 CONTEXT.md 既有词条的关系（8 条核对）

- 「流式 turn」词条用 **turn**：与 settings_turn_dividers 的 turn 一致 ✅；其 _Avoid_「流式消息」在 strings 无出现
- 「红点时钟域」：session_unread_indicator=Unread messages（:784）为其 UI 侧展示词，无冲突
- 其余 6 条（渲染供给/跳转稳定窗口/必需协作者/状态簇/版本 seam/连接生命周期）在 strings 无对应文案
- CONTEXT.md 尚无 UI 文案词条（session/agent/subagent/turn/tag 均未收录）——本次盘点为 Phase 2 提供候选

## 统计

- 文件总数 **15**（EN 源 + 14 翻译），**15/15 精读完**（EN 786 行全文含补读 375-404；每翻译文件全 key 解析 + 域词逐条读取）
- key 规模：EN 670 strings + 4 plurals = 674 keys；完整覆盖 10 文件（de/es/fr/id/it/ko/pl/pt-rBR/tr/zh-rCN）；ja 643/缺 27；ar 615/缺 55；ru 567/缺 103；uk 567/缺 103
- 术语概念：英文源观察表 **42 行**；横向矩阵 **11 概念族 × 15 文件**
- 失实/漂移条目：EN 7 + i18n 18 = **25 条**
- 待裁决冲突：**17 项**（Top5：①session/conversation/chat ②agent 显示词 ③subagent 拼写 ④compact 词族 ⑤覆盖缺口 ja/ar/ru/uk）
- 注释语言现状：**15/15 中英混合**（英文分区注释 + 中文功能沿革注释）；无纯中文/纯英文/无注释文件；id 保留最全英文分区注释；10 文件含「Missing translations (added)」补译标记