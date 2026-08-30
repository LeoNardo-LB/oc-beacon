# streaming-md-pilot（2026-08-30）

> 状态：待验证（P0-a/P0-b 完成 + 装机烟测通过；P1 真机 A/B + V6 六项清单待用户执行）
> 关联：docs/specs/2026-08-30-streaming-markdown-state-pilot-design.md · #265（本批次即其实施）
> 来源：用户指令「先提交之前的代码，然后开始吧 设立目标去做」（spec 评审放行）

## 零轮 · 前置核验（spec 信源把关）

- spec 四项源码级论断全部独立复核过验：`Snapshot.stableAst/unstableAstTail`（javap）、m3 `Markdown(streamingMarkdownState=…)` 重载（javap，四重载在列）、`markdown-jvm 0.7.9`（POM）、现状主线程全量重解析（与 MarkdownContent.kt:579 既往字节码勘注一致）
- 勘误：本人首次 m3 验证扑空系抄错缓存 hash 目录（2101→2103），非 spec 之误

## 一轮 · P0-a 接线（commit a3ad01c6）

- `StreamingMarkdownPilot.kt`：开关对象（BuildConfig.STREAMING_MD_PILOT）+ `rememberPilotStreamingMarkdownState` 前缀差分包装
  - 修正了 spec §1 伪代码的重建缺陷：非前缀分支若只 `resetKey++`，新实例首跑会因 prev 已指向 markdown 而永远 append 不到内容——实现改为「非前缀 → prev 置空 + resetKey++，新实例首跑走整串 append」
  - append 主线程（组合协程），每次 append 记录 stable/tail 规模（MDPilot tag，spec 实施期待验证问题 1 的增长曲线取证）
- `MarkdownContent.kt` 三分支：pilot 插在 preParsedState（完结态接管点）之后、asyncParse/legacy 之前；条件 `overrideState == null && !asyncParse && enabled && !isUser`
- flavor 开关：dev=true / beta=stable=false（buildConfigField）；回退 = 置 false 一行
- 编译 ✅（1m12s）；全量单测 2159，唯一失败 ChunkReproTest（#261 环境夹具，基线一致）

## 二轮 · P0-b 组件缓存静态审计（零修正，同 commit）

解析器源码级实证（markdown-jvm 0.7.9-sources.jar，`StreamingMarkdownFile.kt`）：

- **不稳定尾**：每次 append 对 unstableText 全量 `parseStreaming` → 尾节点全新实例（L105-121）→ 组件层 `remember(model.content, model.node)` 的 node 键必然失效 → 缓存正确重算，无过期渲染
- **稳定块**：入 stableChildrenBacking 后永不重解析/替换（接口文档 L37）→ 实例+offset 不变 → 缓存恒命中（纯收益）
- 逐 append 解析成本 = O(不稳定文本)；129K 单段病态情形（尾不收敛）退化为与现状全量重解析等价——不劣于现状
- 尾块「毕业」进稳定区时实例更换一次（fresh tree filter）→ 一次额外重组，可忽略

## 三轮 · 装机烟测（2026-08-30 10:56）

- assembleDevDebug ✅（44s）· e69a99d8 `adb install -r` Success ✅ · debug-entry：ext-71e988cc → server 617b2c29 激活，直达 SessionList ✅ · logcat 400 行 FATAL/AndroidRuntime 扫描**零命中** ✅
- 注：MDPilot 增长曲线日志需真实流式 turn 触发——由用户 A/B 会话顺带产出（清单第 1 项）

## 四轮 · 端到端实测（2026-08-30 11:02-11:35，uiautomator+logcat+服务器 API 三方取证）

**驱动方式**：真机 UI 驱动（type.sh 打字/坐标点按/截图）+ logcat MDPilot 曲线 + gfxinfo 帧统计 + `/api/session` 服务器原始文本对照 + Room DB 拉库取证。

### E2E-1（pilot 开）：增长曲线 ✅ + 发现真 bug

- 25 次 append，4.3s 流完 2811 字符；**stable 单调 3→6→9→12、tail 恒 1**——spec 实施期待验证问题 1 的答案：解析器稳定块按块毕业、不稳定尾恒为当前开放块
- **发现结尾句渲染 ×2**（服务器原始仅 ×1）：三方归因（截图/DB 拉库/服务器 API）锁定重复发生在 app 数据层——完结全量替换（partId 换代为服务端序）之后，48ms 批缓冲滞留的尾 delta（116 字）才 flush，`MessageMergeEngine.applyDelta` 走 idx<0 兜底**新建 part**→ 尾句 ×2（DB 的 116 字 text 行 + 1 字 reasoning 行即其落盘证据）
- A/B 判定：legacy 关闭版同场景渲染尾句 ×1——**排除试点引入，但当时样本各 1**；后续以代码定性（applyDelta else 分支无内容守卫，与渲染路径无关，理论两条路径均暴露，属时序运气）

### E2E-2（修复后 pilot 开）：验证通过 ✅

- 修复（d964e1a9）：applyDelta else 分支增设**过期 delta 守卫**——同 kind 既有 part 全文已包含该 delta → 丢弃；#223 真重建场景（内容从未到达）不受影响。单测 3 新增（text 丢弃/reasoning 丢弃/#223 保留），全量 2162 唯一红仍 #261
- 真机复测：新提示词（deserts 文档）流式 25 append（stable 单调、tail=1）、完结渲染尾句 "stars hang closer than anywhere else on Earth." **×1 与服务器原始逐字一致**、0 FATAL

## V6 用户 A/B 验证清单（P1，交付）

对照方式：dev flavor 开关即 A/B——如需对照旧路径，把 dev 的 STREAMING_MD_PILOT 置 false 重装一版即可。

1. **流式打字期无闪烁/高度振荡**（对照旧路径主观无劣化）——流式期 logcat 过滤 `MDPilot` 应见 stable 单调增长、tail 有界振荡（增长曲线即 spec 实施期待验证问题 1 的答案）
2. **完结瞬间跳变不可感知**：巨型清单消息（>3000 字符段落、表格/任务列表/引用）完结时归一化+分片接管，高度补偿应吃掉跳变
3. **流中标题/行内码/链接颜色实时正确**（P0-b 审计项可视确认——尾部缓存按 append 失效）
4. **重生成/编辑**：流中触发非前缀内容变化，渲染无旧内容残留
5. **超长消息流式后期**滚动/fling/贴底自愈手感无回归（#258 域抽测）
6. 内容一致性：长回复完结后流式末帧与最终渲染 diff 无丢字/重字（肉眼）

## 达标后（P2，未做）

- beta/stable 放开开关；防闪烁铁律文案补「流式路径用 StreamingMarkdownState」；#265 关单迁册
