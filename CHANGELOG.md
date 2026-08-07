# Changelog

鏈」鐩伒寰?[Semantic Versioning](https://semver.org/) 涓?[Keep a Changelog](https://keepachangelog.com/)銆?
**CHANGELOG 浠呭湪姝ｅ紡鐗堬紙stable release锛夊彂甯冩椂鏇存柊**锛沚eta/dev 棰勫彂甯冪殑鍙樻洿鍦ㄦ寮忕増鍙戝竷鏃剁粺涓€姹囨€汇€傚彂鐗堟祦绋嬭 [docs/release-workflow.md](docs/release-workflow.md)銆?
## 版本体系说明（2026-08-07）

本项目仍处于**开发阶段**，尚未达到正式发布状态（功能与稳定性未满足 1.0.0 标准）。因此版本体系重置为 `0.1.0` 起重新计数（`0.1.0 -> 0.1.1 -> ... -> 1.0.0`），并清理了历史 1.x 发布与 Tag。以下 1.x 条目保留仅供历史追溯。

## [0.1.0] - 2026-08-07

首个正式版（0.1.0-beta.1/beta.2 累积转正）。

### Added

- 会话未读红点：turn 完全结束后显示、进入会话消费、一键已读、基线防历史会话全亮
- 红点派生状态模型：判定 = 会话 Idle + 服务器完成时刻 > 已读位置（全服务器时钟域，远端服务器不再受时钟偏差影响）
- tag 多选过滤（AND 语义）与收藏过滤（FOLDER/RECENT 视图）

### Changed

- 会话列表状态切片重构：23 源魔法索引（values[0..22]）→ 嵌套分组 combine + 具名数据类，根治索引错位 bug 与无关重算
- 红点时间戳体系重构：删除 unreadBaseline/旧 lastReplyTime 持久化，maxCompleted 增量维护 + 同步落盘（杀进程不丢）
- 红点判定状态门控：Busy（turn 进行中）不红点，Idle 后才红点

### Fixed

- 含工具调用的 turn 红点被客户端时钟污染（CommandExecuted 覆盖 completed）
- REST 滞后快照移除红点数据（recompute 只增不减）
- 连接停止（上滑关闭应用）清空红点数据（clearForServer 不再触碰红点事实数据）
- 杀进程重启后未读红点不恢复（持久化 seed 恢复）

## [1.2.0] - 2026-08-06

### Added

- 4 项 UX 修正——弹窗列表左上对齐+表单直展+按钮行合并、设置页按钮下移+空占位、收藏入口回归（本服务器仅收藏视图）、category_name 改标签名称（14 语言）
- 设置页统一列表组件（SettingsSectionHeader + SettingsListRow）——MCP 与标签管理收敛
- 长按详情弹框展示已有 tag（纯展示）+ 抽公共 TagChipsRow
- 分配弹窗 FilterChip 多选 + 底部新增按钮内联表单 + 空状态占位
- 设置页标签管理（增删改 + 展开关联会话 + 逐会话解除）
- 会话行多标签横排显示（复用 basicMarquee 滚动）
- 分配弹窗复选框多选 + 确定保存 + 新增标签自动勾选
- 会话列表状态改多标签（SessionItem.tags + resolvedTags + ViewModel 新接口）
- SettingsRepository 标签接口（替换分类 + 收藏视图统一为内置标签）
- Tag 实体 + DataStore 存储层（多对多分配 + 内置收藏标签 + 原子清理 + 旧收藏迁移）
- 流式期间即显示统计栏（实时耗时 ticker + 圆形进度条）
- 会话详情弹窗移除收藏按钮并按三行重排（复制ID+重命名/分配tag/删除）
- 会话行收藏图标 + 分类 tag 右对齐与溢出滚动 + assign_category 改 Assign tag

### Changed

- 删除废弃分类代码（SessionCategory/Categories DataStore/PickerDialog）
- 删除跨服务器收藏/标签入口与代码（收藏统一为内置标签）
- 滚动性能全链路修复（v1-v6）——cache window 对称预组合 + renderableTurns 内容指纹缓存 + ChatMessage 实例缓存 + turnGroups/jumpTargets 签名缓存
- 盘符列表渐进加载（边探测边显示）+ 30s 缓存 + 单探测 2s 超时，打开其他项目不再等待最慢盘符

### Fixed

- 统计栏 turn 级流式判定（气泡出现即显示实时耗时）+ 会话状态进度条移至输入模块第一行附件左侧
- SettingsListRow 标题单行省略（titleMaxLines 参数，恢复原视觉行为）
- 收藏迁移成功后删除 legacy key（防止取消收藏后复活）
- 暗色表格边界增强（outline 网格线）+ 补齐行分隔线 + 主题切换文字过曝修复（remember 键含颜色）
- 回退 mergeMessageMeta REST completed 合并（保留 SSE 兜底），仅保留 CommandExecuted 精确标记
- REST 快照不再终结 SSE 流式状态 + CommandExecuted 按 messageId 精确标记
- turnGroups/streamingMsgId 直接以 rawMessages 为 key，修复 stale 引用冻结流式输出
- 暗色模式下回合分割线与输入框分隔线改用 outline 提升可见度
## [1.1.1] - 2026-08-06

### Fixed

- 修复多语言显示（源污染/缺失翻译/硬编码提取/真 plurals/孤儿清理/无障碍标签）
## [1.1.0] - 2026-08-05

### Added

- 会话按完整目录路径分组，移除项目感知聚合
- 收藏直达会话，设置页移除收藏入口
- 32 个硬编码中文字符串全量本地化（15 语言覆盖）
- 代码/diff 查看页右上角换行控制（与渲染切换同栏），默认不换行
- diff 与源码视图双向切换（Source/Diff 图标按钮）
- 返回会话列表时自动滚动回顶部
- 自动化发版工作流（版本号与 CHANGELOG 管理）

### Fixed

- 表格单元格背景填满整格，恢复原始行区分度
- 持久通知状态随连接刷新、总开关覆盖事件通知、跨服务器去重键、focus 统一
- 移除文件查看器分享/复制按钮
- diff 视图过滤元数据行（diff --git / index / --- / +++ / @@）
- 修复行尾误报（.gitattributes）
- release 构建使用正式 keystore 而非 debug 签名
## [1.0.3] - 2026-08-05

棣栦釜姝ｅ紡鐗堛€傚熀浜?v1.0.3-beta.1锛堟灦鏋勯噸鏋?+ 鍏ㄩ潰娓呯悊锛夎浆姝ｃ€?
### Changed

- 渚濊禆鏂瑰悜淇锛歚ServerTerminalWorkspace`/`TerminalTabState` 杩佺Щ鍒?`data/terminal`锛屾秷闄?data鈫抲i 杩濊
- 鏂板 domain 鎺ュ彛锛歚SessionStateRepository`锛堜細璇濈姸鎬佸崟涓€鐪熺浉婧愮殑 UI 瑙嗚锛夈€乣ProviderRepository` 鎵╁睍锛?8 鏂规硶锛?- `ServerSettingsViewModel` 閲嶆瀯锛氱Щ闄?`ProviderApi`/`SystemApi` 鐩存帴娉ㄥ叆锛屽畬鍏ㄤ緷璧?domain 灞?- God Files 鎷嗗垎锛欳hatViewModel 1182鈫?96 琛屻€丼essionListScreen 701鈫?81銆丼essionListViewModel 679鈫?02銆丼ettingsDataStore 688鈫?89
- 39 涓紪璇?warning 鍏ㄩ儴娓呴浂锛坉eprecated API 杩佺Щ銆佸啑浣欎唬鐮佹竻鐞嗭級
- 渚濊禆绮剧‘鍖栵細`hilt-navigation-compose` 鈫?`hilt-lifecycle-viewmodel-compose`銆佺Щ闄ゆ湭鐢ㄤ緷璧?- 浠撳簱娓呯悊锛氬瀮鍦炬枃浠躲€佸巻鍙?docs 绮剧畝锛?0MB鈫?28KB锛夈€?4 涓璁?spec 褰掓。

### Removed

- 9 涓浂璋冪敤 UseCase锛圕onnectServer銆丆reateSession銆丟etMessages 绛夛級
- ChatViewModel 姝绘敞鍏?`SseClient`
- 鏈湴鏈嶅姟鍣紙Termux锛夊姛鑳姐€乣AppDialog` 閬楃暀缁勪欢
