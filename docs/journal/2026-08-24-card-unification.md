# card-unification（2026-08-24 · #215 卡片体系统一执行批次）

> 状态：进行中（批1/批2 完成，待用户验收；批3 未启动）
> 关联：#215（backlog）· spec `docs/specs/2026-08-24-card-unification-design.md` · M3 调研 `docs/research/2026-08-24-m3-card-container-fit.md`
> 来源：用户规划诉求（卡片行为统一 + 容器共存性重审 + M3 适配调研）；历史裁决推翻获用户授权（2026-08-18 容器并存残留态，spec §2 证据链）

## 批1：容器统一试点（2026-08-24）

**改动**（3 文件，纯视觉零行为变化）：

1. `ToolProgressCard.kt`：M3 Card（surfaceVariant 淡底 + shapes.small 8dp）→ `AmoledSurface` 统一语言（surface + tonal 1dp + smallMedium 6dp，AMOLED 纯黑+边框）
2. `TokenUsageCard.kt`：裸 Surface（surfaceContainerLow + small 8dp）→ 同上统一
3. `TodoListCard.kt`：圆角 small(8dp) → smallMedium(6dp) 对齐 scaffold 家族（其余参数本就一致：surface + tonal 1dp + AMOLED 边框）

**验证证据**：

- 编译：`:app:compileDevDebugKotlin` BUILD SUCCESSFUL（16s）
- 单测全套件：`testDevDebugUnitTest --rerun` BUILD SUCCESSFUL（1m）
- 插桩全量（e69a99d8 真机）：**OK (135 tests)**——容器换装零回归
- 真机活体：冷启动→会话列表→「社保投诉立案求助」会话→顶栏 context 圆环（1%）→ ContextDetailDialog → TokenUsageCard（"13,136 tokens" + Input 行）渲染正常无崩溃
- 视觉验证局限（如实披露）：2dp 圆角差与 tonal 底色变化细微，视觉模型无法可靠判别新旧；以「渲染正常 + 135 插桩绿」为机械门，三主题截图对比留待批3 收尾统一做

**环境注记**：MIUI 后台启动限制拦截 `am start`（前台为 launcher 时）——用 `monkey -c android.intent.category.LAUNCHER` 唤起；context 圆环为 Canvas 绘制无 a11y 节点，定位靠 ⋮ 菜单 bounds（[1080,194]）反推左侧圆环位（tap 1030,230 命中）。

## 批2：交互契约统一——本体点击=展开唯一入口（2026-08-24）

**改动**（3 文件）：

1. `ToolCardScaffold.kt`：删右侧 chevron IconButton + 删 `showExpandIcon`/`onClick` 参数（唯一使用者 TaskToolCard 同批改造）；标题行 clickable 加 `enabled = hasContent` 门（无内容禁点，消灭死点击）；KDoc 同步。历史包袱收敛：2026-08-12「纯 Icon 无 onClick」bug 的产物 chevron 整体退役
2. `TaskToolCard.kt`：本体点击回归=展开（不再被导航覆盖）；跳转收编为右侧 22dp 箭头 IconButton（#180 语义保留：Running 期拿到子智能体会话 id 即可跳转）；#181「标题行=导航+chevron=展开」并存方案随契约统一收束
3. `PartContent.kt`：**ShellCard 首击陷阱修复**——`isExpanded ?: false` 与 toggle 默认参 `true` 不一致致首次点击 null→!true=false 视觉无变化（需双击）；默认参改 `false` 对齐。此陷阱为存量 bug（chevron 时代同样存在），本体点击升为主交互后暴露并顺带修复

**验证证据**：

- 编译绿 + 插桩全量两轮 **OK (135 tests)**（改动后 + 首击陷阱修复后）
- 真机活体（受控演示会话，REST 触发 bash 工具）：①工具卡 chevron 全消失（Expand desc 仅剩 ReasoningBlock 1 处——批3 范围）②冷态（force-stop 重启清内存展开态）**单击卡本体即展开**（像素 diff 全屏变化 + 输出节点出现）③演示会话已删（DELETE 204）
- 演示取证实录：进度卡（ToolProgressCard）瞬态性太强（sleep 6 工具 2 帧轮询窗口难截），留用户日常使用中自然观察——容器已批1 统一，无行为变化

**披露**：演示会话 `ses_fcba2837affe`（"演示：工具进度卡"）创建于服务器、验证后已删（204）；两次 REST prompt 触发真实工具执行（echo demo-ok / sleep 6），无真实会话污染。

## 发版插叙（同日）

v0.3.2-dev.1 发版（用户裁决 0.3.2 dev 线）：`release.sh dev --force-bump=patch`（脚本默认推导 dev.23 续 0.3.1 线，force 开新线符合「beta 已发、dev 转 0.3.2 迭代」语义）；RELEASE_NOTES 按模板润色（用户视角 5 Added/4 Changed/8 Fixed）；CI success，资产 oc-beacon-0.3.2-dev.1.apk（7.5MB）上线。发版与批1 无冲突（脚本本地仅 bump+tag+push，构建在 CI）。
