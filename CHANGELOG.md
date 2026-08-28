# Changelog

本项目遵循 [Semantic Versioning](https://semver.org/) 与 [Keep a Changelog](https://keepachangelog.com/)。
**CHANGELOG 仅在正式版（stable release）发布时更新**；beta/dev 预发布的变更在正式版发布时统一汇总。发版流程见 [docs/release-workflow.md](docs/release-workflow.md)。
## 版本体系说明（2026-08-07）

本项目仍处于**开发阶段**，尚未达到正式发布状态（功能与稳定性未满足 1.0.0 标准）。因此版本体系重置为 `0.1.0` 起重新计数（`0.1.0 -> 0.1.1 -> ... -> 1.0.0`），并清理了历史 1.x 发布与 Tag。以下 1.x 条目保留仅供历史追溯。

## [0.3.0] - 2026-08-29

> 0.3.0 线（beta.1–beta.9）历经 0.3.1 / 0.3.2 两条开发线大量迭代后的首个正式版。主题：**shell 命令主对话流化 + 事件卡统一 + 滚动稳定性机制级重做 + 全应用术语体系**。

### Added

- **#252 shell 命令卡时间线化**：用户发起的 shell 命令作为主对话内容按消息时间序渲染（对齐 opencode 官方语义），Room 持久化跨进程不丢；shell 卡默认展开、输出按代码块逐行呈现
- **#234 对话流事件卡统一**：task / subagent 完成、shell 完成、目录变更三类事件统一 EventCard（标签行 / 描述行 / 严重度配色 / 展开两段式），#232 单行通知退役、#67 气泡形态翻案落地
- **#243 连续同内容 shell 卡去重**：首张 + ×N 计数折叠（纯函数 + 单测 + 真机 E2E）
- **#256 双方言 shell 接口加固**：V2 schema 漂移双形态容错（shellID/callID、output 对象/字符串）、SSE 本地投影乐观卡、V1 超时 + 409 退避、truncated 全量续读、悬挂降级
- **#151 错误日志 GitHub 上报**：设备流认证 + 指纹查重 + 错误可读化 + CI 凭据注入
- **#132 调试通道**：一键连接指定服务器（debug intent 直达会话列表）
- **#150 V1 连接提速**：排序探测 + SSE 先行并行预加载
- **#32 归档压缩二期**：热/冷分层 + 整桶 zstd + TLRU
- **双 FAB 贴边滑动**：滚动到底与菜单 FAB 沿屏幕边缘拖动停靠、等高贴边（#192/#194）
- **术语体系 CONTEXT.md**：46 词条 + 15 语言显示词全量对齐（KT 系列）

### Changed

- **渲染前补偿体系**：ExpandReveal / DeferredReveal / 压缩卡全族统一——列表高度变化在渲染前移动视窗，无可见补偿动画；换道 PreRenderShiftChannel 帧界注入（#241/#258）
- **限速 fling（SafeFling）**：视口自适应限帧位移，高速穿越未组合区域不瞬移不下跳
- **prefetch 机制级加固**：isPausableCompositionInPrefetchEnabled=false 走整 item 预组合旧路径（绕开框架缺陷 331365999）
- **滚动巨帧根治**：长消息分片 + 归一化后台化 + fallback 异步解析
- **V1/V2 压缩形态统一**：压缩消息归一化为轮次分割线（#224）
- **发送交互**：悲观发送 + 失败保留输入框 + 飞机内 loading
- **堆积/TODO 入口**：贴底工具栏 → M3 官方 FAB Menu 演进终态（右下单 FAB 交错菜单）

### Fixed

- **fling 中 FATAL 崩溃**：pausable prefetch 组栈失衡（IntStack.peek2 AIOOBE，框架缺陷 331365999 家族）——关闭 pausable 预组合机制级绕开
- **shell 卡半截显示**：markdown 状态流 Loading 首帧 × 揭示竞态——ShellOutputBlock verbatim 直渲染根除
- **消息「叠在一起」**：空 part 全通道滤除 + 越界绘制构造性封死（#232/#233）
- **V2 会话打开卡顿/页面乱**：空 part 增殖、Room 回灌、去重根治（冷开 1.6s）
- **GitHub 上报系列**：API 模板转义、查重限定词、设备流凭据编码
- 思考块 -18px 漂移、发送抖动、fling 下跳、V1 连接提速等多项真机回归修复

## [0.2.0] - 2026-08-08

### Added

- 统计栏计时改为 turn 级跨度（首条 created → 末条 completed，流式不重置）
- 输入栏同步常驻 + 标签占位防上抬；Chat 消息列表与 FileViewer 内容渐变呈现（fade 300ms）

### Changed

- 文件查看器加载动画统一为跳动点风格（与主对话流一致）

## [0.1.1] - 2026-08-07

维护版：消除全部构建警告 + CI actions 升级（无业务/功能变化）。

### Changed

- CI actions 升级到最新：checkout v7.0.1 / setup-java v5.7.0 / upload-artifact v7.0.1（消除 Node 20 / setup-java v4 deprecation 警告）
- 构建警告全消：移除已弃用的 `android.enableJetifier`（项目全 androidx）、注解 target 显式化（`@param:`）、死代码/多余 `!!`/Elvis 清理、`Icons.AutoMirrored.Filled.MenuBook` 替换弃用图标

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
- 撤销（revert）mergeMessageMeta REST completed 合并（保留 SSE 兜底），仅保留 CommandExecuted 精确标记
- REST 快照不再终结 SSE 流式状态 + CommandExecuted 按 messageId 精确标记
- turnGroups/streamingMsgId 直接以 rawMessages 为 key，修复 stale 引用冻结流式输出
- 暗色模式下轮次分割线与输入框分隔线改用 outline 提升可见度
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
