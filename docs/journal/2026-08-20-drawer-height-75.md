# 2026-08-20 主对话抽屉高度统一批次（75% 屏高）
> 状态：部分完结（活跃 #164）
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）
> 条目编号：抽屉高度=#164


- [~] **主对话抽屉屏占比高度统一——最小/最大高度统一为 75%** `ui`
  - 需求（2026-08-20 用户确认）：主对话内所有抽屉高度保持一致，min = max = 75% 屏高。此前状态：四个抽屉只有 75% 上限（2026-08-16 决策"去 30% 下限内容自然收缩"），内容少时抽屉塌缩、各抽屉高度不一致
  - 实现：新增 `ui/theme/SheetTokens.kt`（ChatSheetHeightFraction=0.75f，文档见 ui-conventions.md §Sheet tokens）；TaskSheet（含 ShellDetailView 详情态同高、输出区改 weight+scroll）/ ModelPickerDialog / QuickNavigateSheet（列表补 weight(1f)）/ PendingTodoSheet（tab 内容包 Box weight(1f)）内容根改固定 `height(屏高×75%)`；四者均补 `rememberModalBottomSheetState(skipPartiallyExpanded = true)` 防固定高度先落半展开锚点。注：SystemPromptDialog 同步改齐（见下条死代码）
  - 验证：编译 ✅ 全量单测 --rerun ✅；**真机 E2E**（2026-08-20 用户方针：后续测试一律真机，不用模拟器；小米 23127PN0CC serial e69a99d8，屏 1200x2670@480dpi）——4 抽屉像素级顶边一致性 + 空内容撑满 + 内部滚动 + logcat FATAL，**2026-08-20 全 PASS**：4 抽屉顶边逐像素完全一致（y=619px，max−min=0，双检测器互证）；空队列抽屉撑满不塌缩（y1500–2600 std=0.0 纯留白、直达屏底）；model_picker 列表内滚动顶边不变；350ms 早帧几何已就位全高（无半展开锚点）；FATAL=0。证据 /tmp/sheet75r/（6 张正式截图 + logcat 42k 行）
  - **真机测试 runbook（本批次打通，后续复用）**：① 装包**一律用 pm install 静默法**（2026-08-20 实证 3 轮 0.4s 无弹窗）：adb -s e69a99d8 push <apk> /data/local/tmp/t.apk && adb -s e69a99d8 shell pm install -r /data/local/tmp/t.apk && adb -s e69a99d8 shell rm /data/local/tmp/t.apk——MIUI 确认弹窗只挂在 adb install 流程（PackageInstaller UI），shell 直装不经过；降级加 -d。次选：adb install 需 MIUI 开「USB 安装」且屏幕解锁常亮（弹窗手点；svc power stayon usb 保常亮）② 服务器打通用 adb reverse tcp:4199 tcp:4199（设备 127.0.0.1:4199 → 宿主机）③ 一键配置服务器走 debug 构建 intent：am start -n dev.leonardo.ocbeacon.dev/dev.leonardo.ocbeacon.MainActivity --es debug_url http://127.0.0.1:4199 --es debug_username opencode --es debug_password <pwd>（仅 BuildConfig.DEBUG 生效；dev-release 本地无 keystore 回退 debug 签名且非 debuggable 不可降级覆盖）④ 本地 keystore 已失（仅 CI Secrets 存留）——本地构建恒为 debug 签名，与 CI release 包互不覆盖，切换需卸载重装
  - ⚠️ 待用户验收：观感（固定高度后空内容抽屉底部留白是否符合预期）——测试构建已在真机可直接体验
- [x] **新增 P3：SystemPromptDialog + extractSystemPrompt 疑似死代码（2026-08-20 抽屉统一批次顺带发现）** `refactor`
  - **2026-08-20 清理完成 ✅**：全库 grep（main+test）确认零调用方后删除 SystemPromptDialog.kt 整文件 + 15 语言 2 个孤儿键（chat_system_prompt_title/empty，646 键一致）；编译 ✅ 全量单测 --rerun ✅ i18n-check PASSED。附带成果：本地 devRelease 首次以新 keystore 签名成功（8fbc136e…，与 keytool 指纹一致）
