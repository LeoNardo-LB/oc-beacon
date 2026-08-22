# Android UI 元素精确抓取/定位工具调研（2026-08-23）

> **目标**：评估各 UI 元素精确抓取/定位工具能否替代或补全「截图 + 视觉模型识别」在真机 E2E 中的能力缺口。
> **方法**：本地取证（/tmp 全量 dump 分析、git 时间线、Material3 源码解包、docs/journal 实证回溯）+ 三路并行网络调研（官方文档 / AOSP 源码 / issue tracker，引用见各节）。
> **约束**：纯调研 + 本地分析，未触碰设备；所有结论中标注「待实测」的项汇总于文末清单，留待下一轮在真机（houji `e69a99d8`）上执行。
> **关联**：docs/real-device-testing.md（真机 runbook）、docs/journal/2026-08-21-arch-review-deepening.md「adb 伪影边界」、docs/journal/2026-08-20-queue-todo.md #158、docs/research/audit-2026-08-10/D64-investigation.md。

---

## 执行摘要（推荐结论）

**结论：不替代，补全——语义树通道为主、视觉通道收窄为「仲裁+观感」，两者按判定类型路由。** 具体为三层组合：

1. **主通道保持 uiautomator dump 并健壮化**（语义非空校验 + 重试退避 + `--windows` 拿多窗口 + logcat AccessibilityNodeInfoDumper 取证）——release 无关、零部署、像素级 bounds。本调研把「Compose 节点缺失」的机制边界首次钉全（§1：8 类已文档化原因 + unmerged 树澄清），并**修正了任务前提**：ui5.xml 实为会话列表屏、含 FAB 的聊天屏 dump 从未产出过（§0.1/0.2），「FAB 缺失」待真机仲裁而非定论。
2. **等待/断言密集流程迁往 Maestro**（已有 34 个 flow 基建）——源码级确认 instrument 的是自家驱动 APK，**devRelease 非 debuggable 完全可用**；同一棵 a11y 树但多窗口/hintText/常驻连接加成，`assertScreenshot(cropOn)` 承接视觉断言。痛点 2（sleep 猜测）由 7s/17s 自动等待消灭。
3. **视觉模型收窄到不可替代区**（颜色/布局/图标形态/整体观感），其坐标职责让给 **OCR 文本 bbox**（确定性像素矩形）与**指针位置标定**（固定控件一次性入库）。痛点 3（testTag）成本大幅下调：`testTagsAsResourceId` 已全局开启且 dump/Maestro 双通道实证可用，新控件 1 行 tag 即双通道可见。

**一票否决项**：Espresso/compose.ui.test（需同签名 test APK=改装机集，与 devRelease 装机冲突）；Appium（能力被 Maestro 覆盖、部署更重）。**待实测后可升级项**：若 dump/Scanner/Maestro 三方都看不到 FAB → 补 testTag 走 resource-id（§9.3）；若仍缺 → 自研 AccessibilityService 侧车（§7e）。

---

## 0. 本地取证修正：先厘清事实基线

调研起点是痛点 1 的既成叙述：「聊天屏右下 FAB 视觉可见，但 /tmp/ui5.xml dump 中无该节点」。对 /tmp 全部 57 个 dump、git 历史与同期截图交叉取证后，**该叙述需要三处修正**——但「语义树暴露不全/不稳」这个母题本身有三类真实实证（§0.3），调研结论不受影响。

### 0.1 ui5.xml 是会话列表屏，不是聊天屏

对 ui5.xml 全量解析（134 节点，全部 `dev.leonardo.ocbeacon.dev` 包）：

| 证据 | 内容 | 归属 |
|---|---|---|
| 文本节点 | 「搜索会话…」提示、会话标题列表（验收测试会话AB / Kotlin安卓学习教程规划 / 反驳缺勤通知的几点意见…）、路径与时间戳 | 会话列表专属 |
| content-desc | 搜索 / 切换会话×9 / 收藏×9 / 有未读消息 / **新建会话**（`sessions_new`，[936,194][1008,266]） | 会话列表专属 |
| 底部导航 | 会话 / 设置 | 会话列表专属 |

同期截图 /tmp/e192-1.png（03:40:26，视觉模型核验）同样是会话列表（唯一"悬浮按钮"是右上角 + 新建）。**ui5.xml 里没有 FAB 是因为它本来就不在那个屏上**。

### 0.2 「dump 缺 FAB 节点」证据链的时间线修正

| 时间 | 事件 | 取证 |
|---|---|---|
| ≤08-22 04:41 | /tmp 现存全部聊天屏 dump（uiF/uiG/uiM/uiP/uiQ/uiO/ui_chat/ui6/ui8…） | ChatFabMenu **尚未存在** |
| 08-22 16:57 | 堆积/TODO 入口改 M3 官方 FAB Menu（commit `0b91d771`，ChatFabMenu.kt 创建），随后第十六~二十轮迭代 | git log --follow |
| 08-23 03:38–03:40 | ui3/ui4/ui5 dump（会话列表）+ e192-0/e192-1 截图 | 本节取证 |
| 08-23 03:51 | /tmp/f2.png 截图：**聊天屏，但 FAB 位于屏幕顶部**——#192 align bug（modifier 未透传，两个 FAB 全部掉向左上） | 视觉核验 + git |
| 08-23 03:53:40 | HEAD `d23a87e7` "fix(fab): #192 pass through align modifier - **both FABs fell to top-left**" 修复 | git log -1 |

即：**含 ChatFabMenu 的构建从未产出过聊天屏 dump**（现存聊天 dump 全部早于 08-22 16:57），「FAB 视觉可见但 dump 缺节点」的直接对照实验从未发生过。视觉截图里 FAB「可见」但位置在顶部（bug 态）。→ 列入待实测清单第 1 条。

### 0.3 但「语义树不全/不稳」母题有三类真实实证（痛点 1 仍成立，形态修正）

1. **Compose 弹层节点不可见**（runbook 明文纪律）：「Compose 弹层（Popup/sheet 内自绘组件）节点不可见，按 content-desc/文本定位」——docs/real-device-testing.md:110。ui9.xml（关闭工作表 [0,0][1200,619] + 关闭按钮，仅 54 节点）即实例：ModalBottomSheet 展开时树只剩蒙层。
2. **a11y 树偶发「结构在、语义空」**（journal #158，2026-08-21 真机首例）：快速导航 sheet + 远跳（loadAround 路径）周期后 ~2s，dump 91 节点但**全部 text/content-desc 为空**，~15s 自愈；两晚 12 次跳转 1 次退化（~8%），机制未定位（候选：全屏遮罩增删后 Compose semantics 刷新延迟）。→ 门卫式 dump 必须**校验语义非空**，不能只看节点数。
3. **MIUI dump 慢 + 视觉模型误读对照**：MIUI 上 dump 2–3s/次；视觉模型曾把状态栏图标读成「标签」（journal「adb 伪影边界」）——正是本调研要弥合的缺口。

### 0.4 免改码程度被低估：testTag→resource-id 通道已全局打开且实证有效

- **`testTagsAsResourceId = true` 已在全局主题开启**（ui/theme/Theme.kt:168，根 Box `Modifier.semantics{}`；git 追溯至 07-31 rebrand 之前即存在）。
- **历史聊天屏 dump 已实证 resource-id 可见**：uiF/ui_chat/ui6/uiQ 中 `chat-message-list`/`more_vert`/`chat-input`/`chat-send`/`tool_card_open_file` 均以 resource-id 形式出现在 uiautomator dump 里。
- 现有 testTag 覆盖：workspace/search/viewer 域 + chat 域 8 个（more_vert / menu_open_workspace / chat-message-list / tool_card_open_file / chat-input / chat-send / chat-busy-menu / chat-busy-menu-item）。**ChatFabMenu 的 FAB 与底部栏图标按钮、ChatScrollBottomFab 无 testTag**。
- 含义：**「补 testTag」不是架构级埋点工程，而是每控件 1 行 `Modifier.testTag("...")` 的就地改动**，且改完 dump 与 Maestro `id:` 选择器**立即**可见（无需 testTagsAsResourceId 再接线）。痛点 3 的成本评估由此大幅下调。

### 0.5 M3 源码层：ToggleFloatingActionButton 的语义应该是「在树里」的

解包本地 Gradle 缓存 material3-android-1.5.0-alpha26-sources.jar（app 显式依赖此版本，app/build.gradle.kts:164）：

- `ToggleFloatingActionButton`（FloatingActionButtonMenu.kt:501–570）实现 = Box + graphicsLayer/drawBehind + **标准 `Modifier.toggleable(...)`**（含 toggle/role 语义）+ 动态尺寸 layout。Icon 的 contentDescription 在 toggleable 内层——按 Compose 语义合并规则，**该节点应出现在 a11y 树且携带 content-desc（打开任务菜单/收起菜单）**。
- 菜单项有专门的隐藏机制：`MenuItemVisibilityModifier`（:687–706）实现 `SemanticsModifierNode.shouldClearDescendantSemantics = !visible()`——**展开菜单的子项在不可见时会从语义树清除**（这解释了「收起态菜单项不占树」，但不影响 toggle 按钮本身）。
- 结论：若实测 dump 仍缺 FAB 节点，嫌疑按序为 ① MIUI/系统对虚拟节点结构的修剪、② dump idle 等待与 morph 动画的时序竞争、③ `itemVisible` 父数据修饰符异常传播——均列入实测清单（§待实测 2–4）。

---

## 1. uiautomator dump 本身的极限

**执行链（AOSP 源码，Android 10–14 结构一致）**：`/system/bin/uiautomator` 是 shell 脚本（设 CLASSPATH 后 `app_process` 起 `com.android.commands.uiautomator.Launcher`，[uiautomator.sh](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/uiautomator/cmds/uiautomator/uiautomator.sh)）；子命令 = help / runtest（已废弃）/ **dump** / **events**（[Launcher.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/Launcher.java)）。`DumpCommand` 经 `UiAutomationConnection` → `IAccessibilityManager.registerUiTestAutomationService` 把 CLI（shell uid）注册成一次性无障碍服务——**节点树就是 AccessibilityNodeInfo，跨进程向 App 实时查询**，与 TalkBack 同一管线，与目标 app 是否 debuggable 无关。

**窗口范围（关键）**：默认 `getRootInActiveWindow()` 只 dump **活动窗口**；对话框/IME/悬浮窗都是独立 window——弹对话框时 dump 得到的是对话框根，底下 App 窗口（含 FAB）整体消失。**Android 12+ 新增 `--windows`**（本调研直接拉 [android-14 tag 源码](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r2/cmds/uiautomator/cmds/uiautomator/src/com/android/commands/uiautomator/DumpCommand.java) 仲裁确认：L70 `--windows` 分支；android-10 无）——置 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` + `getWindowsOnAllDisplays()`，输出 displays/window(title,bounds,active,focused,layer,type)/hierarchy 嵌套结构。houji（Android 14）**可用**，可一并拿到对话框与主窗口。

**`--compressed` 语义（源码级）**：默认**非压缩**；`--compressed` = 清掉 `FLAG_INCLUDE_NOT_IMPORTANT_VIEWS` → App 侧预取不返回「不重要」节点。`isImportantForAccessibility` 判定见 [View.java](https://developer.android.com/reference/android/view/View#isImportantForAccessibility())。⚠️ **Compose 中只有 testTag 的节点恰恰是 not important**（delegate 源码注释明说）——本仓库 testTag 已成定位依赖，**禁用 --compressed**。dumper 自身唯一节点过滤是 `!isVisibleToUser()` → 静默跳过（仅 logcat `Skipping invisible child`，tag=AccessibilityNodeInfoDumper）。

**idle 等待**：`uiAutomation.waitForIdle(1000, 10_000)`（[UiAutomation.java](https://developer.android.com/reference/android/app/UiAutomation#waitForIdle(long,%20long))）——等「1s 内无任何无障碍事件」，总限 10s；持续动画/高频重组下永不 idle → `ERROR: could not get idle state.` 且**不产出文件**。参数 CLI 不可调。

**节点缺失的全部已文档化原因**（对照本仓库痛点）：
1. `isVisibleToUser=false` → 子树静默跳过。Compose 置 false 的场景（[delegate 源码](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/AndroidComposeViewAccessibilityDelegateCompat.android.kt)）：`hideFromAccessibility()`（旧名 invisibleToUser）、**触摸 bounds 宽/高为 0**（setInvisibleIfEmptyBounds）、可滚动容器 offscreen 边缘子项、**AndroidViewHolder（AndroidView 互操作容器）故意置 false**（TalkBack workaround 历史决定，设计文档明说）。
2. `getChild(i)==null`（App 进程忙/冻结/竞态）→ 跳过（仅 logcat）。
3. 默认单活动窗口（上）。4. `--compressed` 过滤（上）。5. 祖先 `clearAndSetSemantics`（[官方](https://developer.android.com/develop/ui/compose/accessibility/merging-clearing)：替换元素及**全部后代**的语义）。6. **Compose 剪枝**：alpha=0 或被完全遮挡的节点在生成 a11y 树时即删（[内部设计文档](https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui/src/androidMain/kotlin/androidx/compose/ui/platform/accessibility/android_a11y_implementation_notes.md)）。7. Lazy 容器只组合视口内条目（[lists 文档](https://developer.android.com/develop/ui/compose/lists)）。8. 时序竞态：semantics 变更是消息循环批处理任务、**bounds 变更有 100ms 节流** → dump 与 composition 有真实竞态窗口。

**反直觉澄清（双源确认：官方 semantics 文档 + Compose 内部设计文档）**：Compose 暴露给无障碍服务的是**未合并（unmerged）semantics 树**——「Icon 的 contentDescription 被 merge 进 FAB 祖先」**不是**缺节点的原因；合并只发生在服务端（TalkBack 类）算法。dump 里 FAB 的 Icon(desc) 应是独立节点；contentDescription 挂在非叶子节点时 Compose 还会造 fake 子节点承载。⇒ 若实测 FAB 真缺失，按可能性排序：isVisibleToUser=false > active window 不对 > 时序竞态 > --compressed（未用则排除）。

release 构建无关性：dump 走 shell 的 UiAutomation 连接，不需 instrumentation、不需 debuggable；Compose semantics 暴露在 release/debug 完全一致。

## 2. adb shell dumpsys accessibility / cmd accessibility

**`adb shell dumpsys accessibility`：不能拿到节点树**（[AccessibilityManagerService.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java) dump 方法源码确认）——只打印服务/事件簿记：已注册 client、绑定的 a11y 服务、逐用户事件计数、uiautomator 运行态、窗口管理器状态。

**`adb shell cmd accessibility`**（[AccessibilityShellCommand.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/accessibility/java/com/android/server/accessibility/AccessibilityShellCommand.java)）：子命令仅 help / get-bind-instant-service-allowed / set-bind-instant-service-allowed / call-system-action / start-trace / stop-trace（main 另有 check-hidraw）——**无任何 dump 树类子命令**。

**adb-only 拿全树的唯一官方通道** = `uiautomator dump [--windows]`（§1）。**诊断配套**：dump 失败时同步 `adb logcat -s AccessibilityNodeInfoDumper` 看「Skipping invisible child / Null child」明细——这是把「静默缺节点」变成「有日志可查」的现成手段，零成本。事件流用 `uiautomator events`（§7a）。价值定位：**环境取证**（如 MIUI 是否抑制 a11y 服务），不是定位工具。

## 3. compose.ui.test / Espresso：为何不适用（论证）

**结论先行：对「已装好的 devRelease 生产包」不可用，但论证需要精确化——OS 级门槛是「同签名 test APK」而非 debuggable 本身。**

- **AOSP 源码逐版本核对**（Android 9 / 10 / main 的 `ActivityManagerService.startInstrumentation`）：**不存在「目标 app 必须 debuggable」的检查**。真正的硬门槛是：① instrumentation(test) APK 与目标包**签名必须匹配**（不匹配抛 SecurityException）；② user build 上调用者须为 root/shell uid。源码：[AMS（android-10）](https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-10.0.0_r1/services/core/java/com/android/server/am/ActivityManagerService.java)、[AMS（main）](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/core/java/com/android/server/am/ActivityManagerService.java)。
- **反证（官方文档）**：Macrobenchmark 官方明确要求对**非 debuggable** 的 release 副本跑 instrumentation（"Set it up as non-debuggable… typically a copy of the release variant signed locally with debug keys"）：[macrobenchmark-overview](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview)。
- `testOnly` 与 `adb install -t` 的官方定义：[<application> 元素](https://developer.android.com/guide/topics/manifest/application-element)、[adb 文档](https://developer.android.com/studio/command-line/adb)。

**对本仓库的含义**：要让 compose.ui.test/Espresso 跑起来，必须在设备上安装一个与 `dev.leonardo.ocbeacon.dev` **同签名**的 androidTest APK——而本机一切构建都是 debug 签名、CI release 包是 CI keystore（docs/real-device-testing.md 签名备忘），现装包无法凭空附加 instrumentation；即使重签重装，也等于**改变装机集**（与「装机为 devRelease」约束冲突），且 R8 混淆下 Espresso 断言可观测性进一步受限。仓库现有 androidTest（`connectedDevDebugAndroidTest`，app/build.gradle.kts:244-248 已配 espresso/ui-test-junit4）继续服务 debug 档，真机 release E2E 与它分轨。

**与 testTag 的关系（关键）**：Compose 官方互操作文档指出 `Modifier.testTag` 要被 UiAutomator 体系看见需 `testTagsAsResourceId = true`（[Testing interop](https://developer.android.com/develop/ui/compose/testing/interoperability)）——**这是生产代码路径的 semantics 属性，不受 instrumentation 门控**，release 包同样生效（本仓库已全局开启，§0.4）。

## 4. Maestro（项目已有 maestro/ 目录与 34 个 flow）

> 官方文档已重组：maestro.mobile.dev 全部 301 → **docs.maestro.dev**（每页 URL 加 `.md` 取 Markdown 原文，[全站索引](https://docs.maestro.dev/sitemap.md)）；旧 "Drivers" 页面在新文档不存在（docs 仓库全部历史也无）。

**驱动机制（源码核实，CRITICAL）**：**只有一种 Android 驱动，instrument 的是 Maestro 自己的 app**——[AndroidDriver.kt](https://github.com/mobile-dev-inc/maestro/blob/main/maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt)：每次运行装 2 个 APK（内嵌 CLI jar，非在线下载）：`dev.mobile.maestro`（含自有输入法服务）+ `dev.mobile.maestro.test`（instrumentation test APK，内含 @Test 启动的 gRPC 服务器，设备端口 7001）；`am instrument -w … dev.mobile.maestro.test/androidx.test.runner.AndroidJUnitRunner`——目标**仅为自家包**。传输走内嵌 dadb 直隧道，无 XML 文件 round-trip。默认每次重装驱动（`--no-reinstall-driver` 可跳过），结束卸载。

**release 可用性（文档+源码双确认）**：[android-native](https://docs.maestro.dev/get-started/supported-platform/android/android-native.md) "Zero Instrumentation… You test the exact binary your users receive"；[android 总页](https://docs.maestro.dev/get-started/supported-platform/android.md) "it pilots the device from the outside"。**devRelease 非 debuggable 完全可用**；唯一前提是设备能装驱动 APK（MIUI 已验证可装第三方）。

**纠错（来自源码考古）**：① **不存在 Espresso driver 可选**——那是 [Appium Espresso driver](https://appium.github.io/appium.io/docs/en/drivers/android-espresso/)（已归档）的概念；"Eta" 是旧 iOS 驱动名。② Android 13 问题不是「读树被限制」而是**交互时序**（ghost tap [#1202](https://github.com/mobile-dev-inc/maestro/issues/1202)、share sheet 闪退 [#814](https://github.com/mobile-dev-inc/maestro/issues/814)），官方修复于 1.27.0（[CHANGELOG](https://github.com/mobile-dev-inc/maestro/blob/main/CHANGELOG.md)）。

**选择器/匹配语义（源码：[Filters.kt](https://github.com/mobile-dev-inc/maestro/blob/main/maestro-client/src/main/java/maestro/Filters.kt)、[Orchestra.kt](https://github.com/mobile-dev-inc/maestro/blob/main/maestro-orchestra/src/main/java/maestro/orchestra/Orchestra.kt)）**：
- `text`：默认**全串正则 + 大小写不敏感**；Android 上同时匹配 text ∪ hint ∪ contentDescription；部分匹配需自写 `.*foo.*`
- `id`：= resource-id（同时匹配完整 id 与去 `pkg:id/` 前缀短名）；`index`（0 基，负数=从末尾数）；`point`（百分比/像素）；状态 `enabled/checked/focused/selected`；关系 `above/below/leftOf/rightOf/containsChild/childOf/containsDescendants`；`traits`/`width`/`height`；多条件 AND
- ⚠️ **无 `desc:` 选择器键**（[YamlElementSelector.kt](https://github.com/mobile-dev-inc/maestro/blob/main/maestro-orchestra/src/main/java/maestro/orchestra/yaml/YamlElementSelector.kt) 字段表无此项；新版 Jetpack 文档页的 `description:` 示例是文档 bug）——**匹配 contentDescription 用 `text`**（本仓库 flow 至今 0 处 `desc:`，无存量迁移成本）
- **无 XPath（Android）**；多匹配非严格（firstOrNull 取第一个）
- tapOn 坐标 = 元素可见 bounds **中心**；可见性判定含「中心点最顶层节点须是其自身 + 与屏幕交集 ≥10%」

**Compose 支持**：[jetpack 专页](https://docs.maestro.dev/get-started/supported-platform/android/jetpack.md)（黑盒经 accessibility 元数据）；testTag 匹配 = `testTagsAsResourceId` 开启后用 `id:`（[旧版文档存档](https://web.archive.org/web/20241211044858/https://maestro.mobile.dev/platform-support/android-jetpack-compose)完整示例）——**本仓库已全局开启且 10 个 flow 已在用 `id:`**（§0.4，本地统计：more_vert×9、workspace_search_*、back_button、tool_card_open_file）。已知 OPEN issue：[#2704](https://github.com/mobile-dev-inc/maestro/issues/2704)（`mergeDescendants` 合并文本查不到——与 unmerged 树暴露的关系待实测，可能影响 merged 节点的文本匹配）。

**等待/断言原语（对齐本仓库痛点 2）**：`assertVisible/NotVisible` 自动轮询最长 **7s**；tapOn 元素查找默认 **17s**；`extendedWaitUntil{visible,timeout}`；`waitForAnimationToEnd{timeout}`；`assertTrue`（JS 表达式）；`retry{maxRetries 0-3}`；`tapOn` 自带 `retryTapIfNoChange/waitToSettleTimeoutMs`；**`assertScreenshot`（本地开源功能）**：`thresholdPercentage`（默认 95% 相似度）+ `cropOn`（裁剪到某元素再比对）——[文档](https://docs.maestro.dev/reference/commands-available/assertscreenshot.md)。artifacts 自动落盘步骤截图 + 失败步骤层级 JSON + logcat（[artifacts](https://docs.maestro.dev/maestro-flows/workspace-management/test-reports-and-artifacts.md)）。

**部署**：`curl -fsSL https://get.maestro.mobile.dev | bash`（需 Java 17+，[安装文档](https://docs.maestro.dev/maestro-cli/how-to-install-maestro-cli.md)）；装好后**完全离线**（`MAESTRO_DISABLE_UPDATE_CHECK`/`MAESTRO_CLI_NO_ANALYTICS` 可关联网）；headless：`maestro --device e69a99d8 test flow.yaml`；OSS 核心 Apache-2.0 免费（Cloud 才付费）。⚠️ 小米专项（[known-issues](https://docs.maestro.dev/extra-materials/troubleshooting/known-issues.md)）：Redmi "Failed to activate device" → 开发者选项连点「恢复默认」后开启 **Disable permission monitoring**。

**与 uiautomator dump 的关系（源码定论）**：驱动内 [ViewHierarchy.kt](https://github.com/mobile-dev-inc/maestro/blob/main/maestro-android/src/androidTest/java/dev/mobile/maestro/ViewHierarchy.kt) 自述 "**Logic largely copied from AccessibilityNodeInfoDumper**"——同一棵 accessibility 树，**Compose 缺节点风险同源**；但**多出**：`UiDevice.getWindowRoots()`（反射，**多窗口**）、`hintText`/`error` 属性、NAF 标记、Toast 节点、常驻 gRPC 连接（无 dump 文件竞态）。⇒ Maestro ≈ dump 的严格超集（可见元素面）+ 工程层（等待/断言/报告）。

| 维度 | 评分 | 说明 |
|---|---|---|
| 坐标/元素精确性 | ●●●●● | bounds 中心 + index 消歧 |
| 等待/断言原语 | ●●●●● | 7s/17s 自动等待 + extendedWaitUntil + assertScreenshot |
| 免改源码 | ●●●● | text（含 desc）/hint 即用；id 需 tag（已全局开） |
| release 可用 | ●●●●● | instrument 自家包（源码+文档双确认） |
| 部署成本 | ●●●● | 一次性 CLI（内嵌驱动 APK）；每 flow 秒级 |
| Compose 兼容性 | ●●●● | 同源树 + 多窗口/hintText 加成；#2704 merged 文本风险 |

**适用场景**：等待/断言密集的回归流程（本仓库 35 个 flow 即此形态）；`assertScreenshot` 可承接「截图断言」刚需且**裁剪到元素再比对**（比全屏视觉模型抗噪）。

## 5. Appium UiAutomator2 driver

**机制**：Node.js Appium server（2/3）+ `appium driver install uiautomator2`；每会话经 adb 向设备装 3 个组件——uiautomator2-server 主 APK、其 instrumentation APK（`io.appium.uiautomator2.server.test`）、辅助 app `io.appium.settings`，再 `adb forward`（host:8200→device:6790）走 HTTP。架构图：[architecture.md](https://github.com/appium/appium-uiautomator2-driver/blob/master/docs/architecture.md)。

**定位能力**（[README 定位表](https://github.com/appium/appium-uiautomator2-driver)，含官方性能星级）：
- `id`→resource-id（缺包名前缀自动补全）⭐5；`accessibilityId`→content-desc ⭐5；`className` ⭐5
- `-android uiautomator`→原生 UiSelector/UiScrollable（可 scrollIntoView 自动滚动查找）⭐4；⚠️ 官方警告 Google 将弃用 UiSelector 家族（[uiselector 指南](https://github.com/appium/appium-uiautomator2-driver/blob/master/docs/uiautomator-uiselector.md)）
- `xpath`（与 page source 同一棵 XML，server ≥4.25.0 支持 XPath 2.0）⭐3
- 手势：W3C Actions 绝对坐标/元素中心（[actions.md](https://github.com/appium/appium-uiautomator2-driver/blob/master/docs/actions.md)）；百分比仅存在于扩展手势 `mobile: swipeGesture/scrollGesture` 等的 `percent`（[android-mobile-gestures.md](https://github.com/appium/appium-uiautomator2-driver/blob/master/docs/android-mobile-gestures.md)）

**release 可用性（源码核实）**：instrumentation 目标是**自家包**（server README 明示 `am instrument -w io.appium.uiautomator2.server.test/androidx.test.runner.AndroidJUnitRunner`，[server 仓库](https://github.com/appium/appium-uiautomator2-server)）——目标 app 无需 debuggable、无需改签名。⚠️ 注意 `appium:disableSuppressAccessibilityService` 默认会抑制设备上其他 a11y 服务。

**元素树与 dump 同源（关键判定）**：page source 由 [AccessibilityNodeInfoDumper.java](https://github.com/appium/appium-uiautomator2-server/blob/master/app/src/main/java/io/appium/uiautomator2/core/AccessibilityNodeInfoDumper.java) 生成，直接消费 `AccessibilityNodeInfo`——**与 `uiautomator dump` 完全同一棵树**。⇒ 痛点 1（Compose 节点缺失）在 Appium 下原样保留，它不会多看到任何节点；其价值在查询/等待/手势的工程化层。

**images 插件（视觉兜底）**：[@appium/images-plugin](https://github.com/appium/appium/tree/master/packages/images-plugin)（`appium plugin install images`）OpenCV matchTemplate，`-image` 策略传 base64 模板；匹配阈值默认 0.4；返回的 Image Element 本质是**屏幕坐标矩形**，click=矩形中心。同分辨率同 DPI 像素级；跨分辨率/主题/动画帧失配——与视觉模型同类的兜底层，非语义定位。文档：[find-by-image](https://github.com/appium/appium/blob/master/packages/images-plugin/docs/find-by-image.md)。

| 维度 | 评分 | 说明 |
|---|---|---|
| 坐标/元素精确性 | ●●●●● | bounds 中心 + XPath/UiSelector |
| 等待/断言原语 | ●●●●● | WebDriver implicit/explicit waits |
| 免改源码 | ●●●● | 同 dump：text/desc 即用，id 需 tag |
| release 可用 | ●●●●● | instrumentation 打自家包（源码核实） |
| 部署成本 | ●● | Node server + 设备侧 3 APK + 端口转发，冷启动秒级 |
| Compose 兼容性 | ●●●● | **同源树，痛点 1 不解决** |

**适用场景**：多设备矩阵/团队级 WebDriver 栈/需 XPath 复杂查询时。对本仓库（单机、agent 驱动、已有 Maestro）：能力被 Maestro 覆盖而部署更重 → **不推荐引入**；其 GUI 工具 [Appium Inspector](https://github.com/appium/appium-inspector) 倒可作为**免装 debug 的任意 app 树检查器**备选（对标退役的 uiautomatorviewer）。

## 6. 指针位置 + 录屏的几何定位法

**机制（全部源码核实）**：开发者选项「指针位置」开关 = `Settings.System.putInt(..., POINTER_LOCATION, 1)`（[Settings.java](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/provider/Settings.java)，键名 `pointer_location`；[Settings app 开关实现](https://android.googlesource.com/platform/packages/apps/Settings/+/refs/heads/main/src/com/android/settings/development/PointerLocationPreferenceController.java)——注意**关闭开发者选项会强制回 0**）。InputManagerService → WindowManager 创建全屏覆盖层 [PointerLocationView](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/com/android/internal/widget/PointerLocationView.java)：触摸点画**十字线 + 精确像素坐标 + 压力/速度轨迹**。

```bash
adb -s e69a99d8 shell settings put system pointer_location 1   # 开
adb -s e69a99d8 shell settings put system pointer_location 0   # 关
```

**用法定位**：一次性人工标定——人手点候选控件，从 overlay/录屏直读像素坐标，入坐标缓存表。**不是运行时动态定位**（每次 UI 变化需重标）。

**已知边界**：overlay 是 InputDispatcher 层 input monitor，机制上对 `input tap` 注入事件也应绘制，社区普遍这么用（[参考](https://stackoverflow.com/questions/46486648/how-to-show-touches-via-adb-in-android)），但**无官方文档承诺**——待实测清单 #12。配合 scrcpy（当前 [v4.1](https://github.com/Genymobile/scrcpy)，`--record` 录制 / Linux `--v4l2-sink` 帧流，见 [v4l2 文档](https://github.com/Genymobile/scrcpy/blob/master/doc/v4l2.md)）可半自动化：注入 tap 序列 + 帧差提取坐标。

| 维度 | 评分 |
|---|---|
| 坐标精确性 | ●●●●●（物理像素直读，零误差） |
| 等待/断言原语 | ●（无——纯标定工具） |
| 免改源码 | ●●●●● |
| release 可用 | ●●●●● |
| 部署成本 | ●●●●●（一条 settings 命令） |
| Compose 兼容性 | 不适用（几何通道，天然免疫语义缺失） |

## 7. 其他候选方案

**（a）`adb shell uiautomator events`——免装 app 的 a11y 事件流（源码核实的隐藏能力）**：AOSP uiautomator CLI 的 COMMANDS = {help, runtest, dump, events}；[EventsCommand.java](https://android.googlesource.com/platform/frameworks/uiautomator/+/refs/heads/main/cmds/uiautomator/src/com/android/commands/uiautomator/EventsCommand.java) "持续打印 accessibility events 直到终止"——TYPE_WINDOW_CONTENT_CHANGED / WINDOW_STATE_CHANGED 实时流，可直接观测「组合是否已稳定」（进会话后事件静默≈语义树就绪），比 sleep 猜测科学。限制：与 dump 互斥（占同一 shell instrumentation 通道）；输出是 event.toString，不含完整树。子命令注册：[Launcher.java](https://android.googlesource.com/platform/frameworks/uiautomator/+/refs/heads/main/cmds/uiautomator/src/com/android/commands/uiautomator/Launcher.java)。

**（b）dumpsys accessibility / cmd accessibility（取证用，非定位用）**：[AccessibilityManagerService](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/accessibility/java/com/android/server/accessibility/AccessibilityManagerService.java) 的 dump 只是**状态转储**（已注册 client/service、绑定策略）；[AccessibilityShellCommand](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/services/accessibility/java/com/android/server/accessibility/AccessibilityShellCommand.java) 子命令仅 get/bind-instant/call-system-action/trace 开关——**两者都不输出节点树**。价值：诊断「a11y 服务是否被抑制」（MIUI 场景）与 dump 失败时的环境取证。

**（c）uiautomatorviewer 已死**：随[已废弃的 SDK Tools 包](https://developer.android.com/studio/releases/sdk-tools)发布（官方："This SDK Tools package is deprecated"）。替代：[Appium Inspector](https://github.com/appium/appium-inspector)（走 uia2，可查任意已装 app 含 release）或下条。

**（d）Accessibility Scanner（Google 官方免费 app）**：[Play 链接](https://play.google.com/store/apps/details?id=com.google.android.apps.accessibility.auditor)、[帮助页](https://support.google.com/accessibility/android/answer/6376570)。作为 a11y 客户端「扫描当前屏幕任意 app」，列出全部可达节点/语义标签——**无需 debuggable、无需改目标 app**，是「FAB 到底有没有暴露给 a11y」的最快人工仲裁器（待实测 #2/#11 的主角）。

**（e）自研轻量 AccessibilityService app（终极形态，暂不推荐）**：普通 app + 用户授权 a11y 权限（可用 `adb shell settings put secure enabled_accessibility_services …` 静默启用，社区惯用法无官方承诺），即获 `getRootInActiveWindow()` 全树 + `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` 多窗口 + 完整事件流，对 release 目标零要求。代价：~200 行一次性开发 + 自装 app 维护。**仅当实测证实 dump/Scanner 双缺且 Maestro 也不可见时才值得**。

**（f）OCR 文本 bbox（视觉通道内的精确化）**：[Tesseract](https://github.com/tesseract-ocr/tesseract)（`tsv` 输出词级 bbox）/[PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR)（中文更强）对 screencap PNG 给**确定性像素矩形**——可复现可断言，矫正视觉模型「估坐标」；图标/低对比文本是盲区。现成框架：[Airtest](https://github.com/AirtestProject/Airtest)（OpenCV 免注入）、[Askui](https://docs.askui.com/)。Selendroid（2015 终版）/Robotium（2021 停滞）死亡确认，不展开。

---

## 8. 评估矩阵（6 维度汇总）

下表评分：●●●●● 强 → ● 弱；「⚠」= 依赖待实测项确认。

| 候选 | 坐标/元素精确性 | 等待/轮询/断言原语 | 免改源码 | release（非 debuggable）可用 | 部署成本 | Compose 语义树兼容性（痛点 1） |
|---|---|---|---|---|---|---|
| **A. uiautomator dump（现状+增强）** | ●●●●●（bounds 像素级） | ●●（shell 自建循环 + events 判稳） | ●●●●（text/desc 即用；id 需 tag） | ●●●●● | ●●●●●（零） | ●●●●（主控件实证可见；弹层→`--windows` 可解；偶发空树 #158 靠重试） |
| **B. dumpsys / cmd accessibility（+ `uiautomator events`）** | ●（不输出树） | ●●（events 流可判就绪） | ●●●●● | ●●●●● | ●●●●● | ●（取证用；events 判组合稳定） |
| **C. compose.ui.test / Espresso** | ●●●●● | ●●●●●（onNodeWithTag 等） | ●●（需 testTag） | ✗（须装**同签名** test APK=改装机集；OS 层无 debuggable 检查，§3） | ●●（须并行维护另一装机轨道） | ●●●●●（语义树一等公民） |
| **D. Maestro（唯一自研驱动）** | ●●●●●（元素中心+index） | ●●●●●（7s/17s 自动等待/extendedWaitUntil/assertScreenshot） | ●●●●（text 含 desc/hint 即用；`id:` 需 tag 已全局开） | ●●●●●（instrument 自家包，源码+文档双确认） | ●●●●（一次性 CLI；驱动 APK 内嵌离线） | ●●●●（同源树 + **多窗口**/hintText 加成；#2704 merged 文本风险） |
| **E. Appium UiAutomator2** | ●●●●●（XPath/id/百分比） | ●●●●●（WebDriver waits） | ●●●● | ●●●●●（instrument 自家包，源码确认） | ●●（Node server + 设备侧 3 apk + 端口转发） | ●●●●（同源树，allowInvisibleElements 可调） |
| **F. 指针位置 + 录屏/截图帧** | ●●●●●（物理像素直读） | ●（人工/半自动） | ●●●●● | ●●●●● | ●●●●● | 与语义树无关（纯几何，天然免疫） |
| **G. 截图 + OCR（宿主机）** | ●●●●（文本 bbox 精确；图标不行） | ●●（自建模板轮询） | ●●●●● | ●●●●● | ●●●（OCR runtime 安装） | 与语义树无关（视觉通道，免疫但无语义） |
| **H. Accessibility Scanner（官方 app）** | ●●●●（可浏览全树，手动导出） | ●（人工操作） | ●●●●●（目标 app 零改动） | ●●●●● | ●●●●（装一个 app） | ●●●●●⚠（官方 a11y 客户端视角，可作树完整性仲裁） |
| **I. 自研轻量 AccessibilityService app** | ●●●●●（全窗口树+事件流） | ●●●●（自建：logcat/文件回传） | ●●●●●（目标 app 零改动） | ●●●●●（普通 app+用户授权） | ●●（一次性 ~200 行开发） | ●●●●●（FLAG_RETRIEVE_INTERACTIVE_WINDOWS 全窗口） |

**读法**：没有单一工具六项全满。C（instrumentation 家族）在精确性与原语上最强，但 release 红线一票否决；语义树家族（A/D/E）精确性与原语好、release 可用，但共享同一 Compose 暴露边界（痛点 1 的根源在**应用→系统 a11y 树**这条链路，不在上层工具）；视觉/几何家族（F/G）免疫语义缺失但没有等待原语。→ 组合是必然解，见 §9。

## 9. 对本仓库 E2E 流程的落地建议

**定位结论：补全，不是替代。** 语义树通道（dump/Maestro）与视觉通道解决的是两类正交问题；缺口不在「换更强工具」，而在**分场景选通道 + 把语义树通道的已知边界管起来**。

### 9.1 通道分层（按判定/操作类型路由）

| 场景 | 首选通道 | 理由与现状证据 |
|---|---|---|
| 常规控件交互（有 text/content-desc/resource-id） | **dump → bounds 中心 tap**（现状保留） | 像素级精确；resource-id 通道已实证（§0.4） |
| 等待/断言密集的回归流程 | **Maestro flow** | 自动等待消灭 sleep 猜测；`extendedWaitUntil`/`assertVisible` 已是既有 34 个 flow 的骨架；`id:` 选择器已在 10 个 flow 使用 |
| dump 找不到节点（弹层/sheet/偶发空树） | ① 重试 + 语义非空校验 → ② testTag 定位 → ③ 一次性几何标定坐标 | 弹层缺节点是 runbook 已知纪律；#158 空树 ~15s 自愈必须重试 |
| 视觉性判定（颜色/布局/图标形态/整体观感） | **截图 + 视觉模型（保留）** | 语义树无像素信息；这是视觉通道的不可替代区 |
| 视觉通道内的坐标需求 | **OCR 文本 bbox**（优先）> 视觉模型估坐标 | 消「状态栏图标→标签」类误读的坐标面；文本类控件 bbox 与 dump 等精度（待实测 12） |
| 树完整性争议仲裁 | **Accessibility Scanner** / 指针位置 | 官方 a11y 客户端视角对照 dump，判定缺失在 app 侧还是 dump 侧 |

### 9.2 门卫式 dump 的健壮化（立即可做，零工具变更）

1. **语义非空 sanity**：门卫判据从「有节点」升级为「节点数 ≥ 阈值 且 非空 text/desc 计数 ≥ 阈值」——#158 的空树（91 节点全空文本）只有这个判据能拦住。
2. **重试退避**：MIUI dump 2–3s/次已知慢；失败/空树时 2s/5s/10s 三退避（#158 自愈窗口 ~15s）。
3. **免落盘直取**：`adb exec-out uiautomator dump /dev/tty` 跳过 /sdcard 写读（待实测 #6 确认 MIUI 支持）。
4. **多窗口**：弹层/对话框场景改 `uiautomator dump --windows`（Android 12+，houji 可用，§1）——一并发拿到对话框树与底下主窗口树，弹层缺节点的很大一部分由此消除。
5. **取证常态化**：dump 失败/缺节点时同步 `adb logcat -s AccessibilityNodeInfoDumper`（Skipping invisible child / Null child 有日志，§2）。
6. **禁用 `--compressed`**：会剔 not-important 节点，而 Compose 只有 testTag 的节点恰属此类（§1）。
7. **坐标缓存表**：对无语义的固定控件（弹层内自绘），用指针位置一次性标定并入库（复用 §9.1 第 3 行通道）。

### 9.3 testTag 策略（免改码评估的落地形态）

- **不搞全量埋点**：仅在「dump 实测缺节点 && 需要程序化交互」的控件上补——当前已知候选只有 ChatFabMenu 的 FAB/菜单项与 ChatScrollBottomFab（1 行 `Modifier.testTag("chat_fab_menu")` 级别，`testTagsAsResourceId` 已全局开）。
- 已有 8 个 chat 域 tag（chat-input/chat-send/more_vert…）+ workspace 域一批，Maestro `id:` 与 dump 双通道已验证可用——**新控件默认顺手加 tag**（成本≈0，收益双通道）。

### 9.4 明确不推荐引入

- **Espresso / compose.ui.test 跑真机装机包**：须装**同签名** androidTest APK = 改变装机集，与 devRelease 装机方针冲突（§3：OS 级门槛是签名匹配而非 debuggable，但对现装包等价不可用）；JVM 单测 + 已有 androidTest（debug 档）已覆盖其生态位。
- **Appium UiAutomator2**：能力≈Maestro（同管线、同 release 语义），部署成本显著更高（Node server + 设备侧双 apk + 会话管理）；在「单设备、agent 驱动、已有 Maestro 基建」的本仓库场景无增量收益。

### 9.5 痛点 1 的处置路径（依赖实测）

若实测清单 #1 证实 FAB 缺节点（dump 侧）：先 Accessibility Scanner 仲裁（#2/#11）——
- Scanner 能见 → dump 工具侧缺陷 → 交互改走 Maestro/坐标表，dump 边界写入 runbook；
- Scanner 也缺 → app 侧语义暴露问题（M3 实验组件/MIUI 修剪）→ 补 testTag 走 resource-id 通道（§9.3），并考虑给 FAB 提 upstream issue。

## 10. 待实测验证清单（下一轮真机执行）

> 全部为只读/可逆操作，不改变 app 数据；执行环境 houji `e69a99d8`，当前 HEAD 构建。每条附「预期结果 → 判定」。

**A. 痛点 1 根因仲裁（最高优先）**
1. 聊天屏 dump ×3（FAB 收起态，修复后构建）：`grep -c '打开任务菜单'` → 预期 ≥1：证实/证伪「dump 缺 FAB」（§0.2 指出该对照从未发生过）。
2. 同屏 `adb shell dumpsys accessibility` 与 Accessibility Scanner 截图对照 → 树里有无 FAB：分流「dump 工具缺陷」vs「app 侧语义缺失」。
3. FAB 展开态 dump：菜单项（堆积/Todo/智能体/Shell 药丸）是否出现（M3 `itemVisible` 语义清除机制的实测对照，§0.5）。
4. 快速连续 dump（进会话后 0s/+1s/+2s/+5s）：节点数与 desc 计数曲线 → 排除「组合未稳定」时序竞争。

**B. dump 机制边界**
5. `uiautomator dump --compressed` vs 默认 vs **`--windows`**：同屏三模式节点数/属性 diff（--windows 能否同时拿到主窗口+弹层；NAF 标记变化）。dump 时同步 `adb logcat -s AccessibilityNodeInfoDumper` 抓 Skipping/Null child 明细。
6. `adb exec-out uiautomator dump /dev/tty` 直出 → MIUI 是否支持免落盘读取。
7. #158 场景复现采样：快速导航 sheet + 远跳 ×12，每轮 dump 记录「节点数/非空 desc 数」→ 空树退化率与自愈窗口复核。
8. ModalBottomSheet / Popup 展开时 dump + Scanner 对照 → 弹层控件在官方客户端是否可见（决定 §9.1 弹层行策略）。

**C. Maestro（release 适配验证）**
9. 安装 Maestro CLI + 真机跑 `l1-app-launch.yaml`（devRelease 装机不动）→ driver apk 是否自动推装、instrument 自身是否成功、目标 app 是否被要求 debuggable。
10. `tapOn id:"chat-input"` 与 `tapOn text:"提问…"` 对照 → resource-id 通路在 Maestro 侧端到端复核。
11. 在 Maestro hierarchy（`maestro hierarchy`/debug output）中确认 FAB 节点有无 → 同管线不同客户端的仲裁数据。

**D. 几何/视觉通道**
12. 指针位置：`adb shell settings put system pointer_location 1` 后 `input tap` 注入事件是否触发坐标 overlay 显示（决定「注入 tap + 帧差」半自动标定法可行性）。
13. OCR 试跑：聊天屏 screencap PNG → 宿主机 PaddleOCR/Tesseract 中文文本 bbox ↔ dump text bounds 逐条 diff → 精度与召回率。
14. `cmd accessibility` 子命令清单 + `dumpsys accessibility` 完整输出留档（供 §2 结论实证补强）。

**E. 组装验证**
15. 用「dump 主通道 + 视觉仅仲裁」跑一遍 dialogue-e2e-test-plan 的冒烟子集 → 门卫误判率对比（修复前后各一轮）。
