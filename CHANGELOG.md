# Changelog

鏈」鐩伒寰?[Semantic Versioning](https://semver.org/) 涓?[Keep a Changelog](https://keepachangelog.com/)銆?
**CHANGELOG 浠呭湪姝ｅ紡鐗堬紙stable release锛夊彂甯冩椂鏇存柊**锛沚eta/dev 棰勫彂甯冪殑鍙樻洿鍦ㄦ寮忕増鍙戝竷鏃剁粺涓€姹囨€汇€傚彂鐗堟祦绋嬭 [docs/release-workflow.md](docs/release-workflow.md)銆?
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
