# 验证框架权威文档（Verification）

> 2026-09-09 #382 文档整合：本文档由 verification-requirements.md + qa-methodology.md 合并而成（两源文件保留 tombstone 指向此处）。

> 本文档定义 OC Beacon 项目每个开发阶段完成前**必须**执行的验证流程（框架层，原 verification-requirements.md），
> 以及完成声明的质量保证方法论（方法论层，原 qa-methodology.md）。
> 所有 agent（主 agent 和 subagent）在声称任务完成前必须遵守。

---

## 0. 铁律

```
NO COMPLETION CLAIMS WITHOUT FRESH VERIFICATION EVIDENCE
```

**必须加载 `verification-before-completion` 技能**作为验证指导原则。该技能定义的 Gate Function 是本项目的强制执行标准：

**验证方法论**：如何组织证据（多维度交叉验证、证据链完整性、操作可复现、并行验证节点）
见本文 §2 方法论章——§1 规定"验什么"（V1-V6 维度框架 + 铁律），
方法论章规定"怎么验到交叉印证"（≥2 独立维度互证才算完成）。

```
BEFORE claiming any status:
1. IDENTIFY — 什么命令能证明这个声明？
2. RUN      — 执行完整命令（新鲜的、完整的）
3. READ     — 读取完整输出，检查退出码，计算失败数
4. VERIFY   — 输出是否确认声明？
5. ONLY THEN — 才能做出声明
```

---

## 1. V1-V6 验证框架（旧称 4+1 维）

每个 Layer 完成后，必须通过以下维度的验证（编号迁移见 [`docs/numbering-charter.md`](numbering-charter.md)：
维度1→V1 · 维度2→V2 · 2b→V3 · 2c→V4 · 维度3→V5 · 维度5→V6）：

### V1 代码层面验证（旧称维度 1）(Code-Level Verification)

| 检查项 | 命令 | 超时 | 通过标准 |
|--------|------|------|----------|
| Kotlin 编译 | `./gradlew :app:compileDevDebugKotlin` | 120s | BUILD SUCCESSFUL, 0 errors |
| 全量单元测试 | `./gradlew :app:testDevDebugUnitTest --rerun` | 180s | 0 failures |
| AndroidTest 编译 | `./gradlew :app:compileDevDebugAndroidTestKotlin` | 120s | BUILD SUCCESSFUL |
| 全量构建 | `./gradlew :app:assembleDevDebug` | 300s | BUILD SUCCESSFUL |

**规则：**
- 编译检查在**每个 Task** 完成后执行
- 全量单元测试在**每个 Layer** 全部 Task 完成后执行
- 全量构建在**重大里程碑**（Layer 1/3/5 完成后）执行
- 禁止用上次运行结果替代——必须在**当前消息中**执行命令

### V2 自动化框架 + 模拟器截图验证（旧称维度 2）(E2E Screenshot Verification)

使用 **Maestro** CLI 进行自动化 UI 验证。

**流程文件结构：**
```
maestro/
├── l{n}-{feature}.yaml    # 每个 Layer 对应的 E2E flow
└── README.md              # 运行说明
```

**要求：**
- 每个 Layer 中涉及 UI 变更的 Task，必须编写对应的 Maestro flow
- Flow 中必须包含 `takeScreenshot` 步骤
- Flow 必须包含 `assertVisible` 断言（不是仅截图）
- **禁止使用 `manual` tag** — 所有 flow 必须可通过 `maestro test` 全自动运行，不允许任何手动步骤

**运行（需模拟器）：**
```bash
maestro test maestro/l{n}-{feature}.yaml
```

**约束：** 无模拟器时至少生成 flow 文件，标注待验证。

### V3 模拟器实机调用 + 截屏走查（铁律；旧称维度 2b）

> **本维度是整个验证体系中最重要的环节。没有通过本维度的验证，任何 Layer 的完成声明无效。**

**适用范围：** 任何涉及 UI 变更或与 UI 有关联的能力（包括但不限于：新增/修改 Composable、ViewModel 状态变更影响 UI、事件处理导致 UI 更新、Repository 状态影响 UI 展示）。

**前置条件：**
- Android 模拟器运行中（`adb devices` 可见 `emulator-XXXX device`）
- App 已安装到模拟器（`./gradlew :app:installDevDebug`）
- Maestro CLI 已安装（`maestro --version`）

**验证步骤：**

| 步骤 | 命令 | 超时 | 通过标准 |
|------|------|------|----------|
| 1. 安装 App | `./gradlew :app:installDevDebug` | 300s | `Installed on 1 device` |
| 2. 运行 Maestro flow | `maestro test maestro/l{n}-{feature}.yaml` | 120s/flow | 所有步骤 COMPLETED |
| 3. 运行 androidTest | `./gradlew :app:connectedDevDebugAndroidTest` | 300s | `Finished N tests`, BUILD SUCCESSFUL |
| 4. 截屏确认 | 检查 Maestro 输出中的 `takeScreenshot` 步骤 | — | COMPLETED |

**Maestro flow 实机运行要求：**
- 每个 Layer 中涉及 UI 的功能，其 Maestro flow **必须在模拟器上实际运行并全部通过**
- **禁止使用 `manual: true` 标记** — 如果 flow 依赖外部条件（如服务器连接），必须在 flow 中通过条件检测（`extendedWaitUntil`）优雅处理，而非要求手动操作
- Flow 中的 `takeScreenshot` 步骤必须全部 COMPLETED（截图成功生成）
- Flow 中的 `assertVisible` 断言必须全部通过

**androidTest 实机运行要求：**
- 所有 `androidTest` 文件中的测试**必须在模拟器上实际执行**
- 通过 `connectedDevDebugAndroidTest` 运行，不允许仅编译检查
- 0 failures, BUILD SUCCESSFUL

**禁止的替代行为：**
- ❌ 仅 `compileDevDebugAndroidTestKotlin`（编译通过不等于运行通过）
- ❌ 仅创建 Maestro YAML 文件而不实际运行
- ❌ "上次运行是通过的"（必须当前消息中执行）
- ❌ 跳过模拟器测试直接声称 UI 功能完成

**特殊情况处理：**
- 无模拟器可用时：在 Layer 完成报告中明确标注 "⚠️ 未进行实机验证"，列出需要补测的 items
- Maestro flow 需要外部依赖（如服务器）：通过 `extendedWaitUntil` 超时或条件分支优雅降级，**禁止使用 `manual` 标记**
- androidTest 因环境问题失败：记录失败原因，作为 known issue 跟踪

### V4 E2E 测试分档（冒烟 / 全面+回归；旧称维度 2c）

> 2026-08-06 新增：解决"端到端测试阶段耗时过长"问题。E2E 分两档执行，
> 替代"每个 Layer 全部 flow 必跑"的一刀切要求。flow 清单与命令见
> `maestro/README.md`。

#### 档位 A：冒烟测试（Smoke）—— 每阶段收尾 / 发版前置

**目标**：15 分钟内验证核心链路无回归（启动安全 + 核心用户旅程）。

**清单**（10 个 flow，见 `maestro/README.md` §Smoke）：
`l1-app-launch` → `l1-home-screen` → `l1-connection-error` → `l1-crash-recovery`
→ `e2e-server-setup` → `e2e-session-list` → `l4-chat-ui` → `e2e-settings-flow`
→ `terminal-smoke` → `e2e-rotation-restoration`

**通过标准**：所有步骤 COMPLETED + `assertVisible` 通过 + 无崩溃。

**触发时机**：
- 每个 Layer/阶段收尾（替代原"该 Layer 全部 flow 必跑"）
- 发版前置快速验证
- 冒烟失败 → 转全面档定位

#### 档位 B：全面 + 回归测试（Full）—— 正式发版前 / 大版本收尾

**目标**：全量验证（含完整旅程与专项）。

**范围**：
- `maestro/` 下全部 flow（含 `e2e-*` 完整旅程与专项）
- `./gradlew :app:connectedDevDebugAndroidTest`（androidTest 全量实机）
- 全量单元测试：`./gradlew :app:testDevDebugUnitTest --rerun`
- 编译 + 构建：`compileDevDebugKotlin` + `assembleBetaRelease`

**回归策略**：变更相关 flow 必跑 + 冒烟档全跑 + 全量兜底。

**通过标准**：全部 flow COMPLETED + androidTest 0 failures + 单测 0 failures。

**触发时机**：
- 正式发版（stable）前必跑
- 大版本 / 跨层重构收尾

### V5 代码分支日志输出验证（旧称维度 3）(Log Branch Verification)

通过 instrumented test 验证关键代码路径的日志输出。

**覆盖场景：**

| 场景 | 验证方式 |
|------|----------|
| 网络断开 → 重连 | Logcat 过滤 `SseConnectionManager` + `NetworkMonitor` |
| SSE 超时 → 冷却 | Logcat 过滤 `SSE read timeout` + `cooldown` |
| 崩溃 → 重启 | Logcat 过滤 `crash_occurred` |
| 权限请求 → 自动批准/拒绝 | Logcat 过滤 `PermissionEventHandler` |

**要求：**
- 新增的关键业务逻辑必须有对应的 `Log.i`/`Log.w` 输出
- instrumented test 中验证 `Log.isLoggable()` 或使用 `LogcatRule` 读取日志
- 无模拟器时标注待验证

### V6 用户人工验证（旧称维度 5）(User-in-the-Loop Verification)

> 2026-08-08 新增；2026-09-07 勘误重构：原「时间性现象必须由用户完成」的可量化面已可由录屏+像素仪器覆盖，
> 本维收窄为四类真人工项（对齐 backlog 验证方针/ai-acceptance-workflow §1）。截图是静态快照、断言是布尔判定
> ——主观的流畅/自然/手感仍只有用户能判定，自动化通过 ≠ 主观体验正确。

**适用场景（仅限四类——先穷举仪器仍不可覆盖才入本维；Agent 不得以「是 UI/时间性现象」为由默认转人工）：**
- 真手指连续手势/体感（注入手势为平台批处理伪影，#245 两轮证伪）
- 需用户凭据/跨设备操作
- 数日级真实使用观察
- 主观体验拍板：视觉流畅度、过渡自然度、交互反馈感（「好不好用」的最终确认）

> 时间性现象（加载过渡/闪烁/布局跳动/计时/流式节奏）**不是本维触发条件**：先走 screenrecord+video-analyzer 与像素采样取客观证据，其中仅主观观感部分落入上一类。

**流程（必须满足全部）：**
1. **标注**：Agent 在完成声明中显式列出哪些验证点属于时间性/主观现象（自动化无法覆盖）
2. **清单**：Agent 提供人工验证清单——每项包含「操作路径 + 预期现象 + 判定标准」
3. **构建**：对应 debug APK 已构建并安装到用户可操作的设备/模拟器
4. **执行**：用户按清单逐项操作并反馈（通过/失败/备注）
5. **修复循环**：失败项 → Agent 修复 → 重新构建 → 用户复验
6. **记录**：完成声明引用用户反馈记录（验证人、通过项、修复后通过项）

**清单格式：**

| # | 操作路径 | 预期现象 | 判定标准（怎么算通过） |
|---|---------|---------|----------------------|
| 1 | 点击进入历史会话 | 蒙版盖住消息区+输入栏，就绪后一次揭开 | 无闪烁、无布局上抬 |
| 2 | 发送消息触发流式 | 统计栏计时连续累加不重置 | 全程观察无跳变 |

**与自动化的分工（不互为替代）：**
- 自动化（单测/Maestro/截图/androidTest/真机仪器——dump·像素·logcat·Room·curl·录屏分析）：覆盖逻辑正确性、结构/流程/色值/时间性可量化面
- 人工验证：仅覆盖上述四类（真手指体感/凭据/长期观察/主观拍板）
- 人工反馈"看着不对" → 进入修复循环，不因"自动化都过了"而搁置

### V4a 完整测试框架验证（旧称维度 4；全面档归 V4 体系）(Comprehensive Test Framework)

#### 4a. 单元测试 (Unit Tests)

**测试基础设施：**
- JUnit 4 + MockK 1.14.9 + Turbine 1.2.1 + kotlinx-coroutines-test
- `isReturnDefaultValues = true`（注意：mock 可能静默返回 null/0/false）

**覆盖要求：**

| 代码类型 | 最低覆盖 | 测试文件位置 |
|----------|----------|-------------|
| Domain model / sealed class | 所有分支 + 边界值 | `src/test/.../domain/model/` |
| Mapper / utility function | 正常路径 + null/空/异常输入 | `src/test/.../data/mapper/` |
| Repository implementation | Mock API + 状态变化 | `src/test/.../data/repository/` |
| ViewModel | State flow + 事件处理 | `src/test/.../ui/screens/` |
| UseCase | 委托验证 + 异常传播 | `src/test/.../domain/usecase/` |

**增强测试覆盖清单（每个 Layer 必须检查）：**
- [ ] 正常路径 (happy path)
- [ ] 边界值（空集合、最大值、零值、负数）
- [ ] 异常路径（IOException、超时、非瞬态错误）
- [ ] 并发场景（多协程、状态竞争）
- [ ] 数据不可解析场景（非法 JSON、缺失字段）

#### 4b. Instrumented 测试 (androidTest)

**测试基础设施：**
- `HiltTestRunner` + `HiltTestApplication`
- `createComposeRule()` 用于 Compose UI 测试
- `androidTestImplementation` 依赖已在 `build.gradle.kts` 中声明

**文件位置：** `app/src/androidTest/kotlin/dev/leonardo/ocbeacon/`

**覆盖要求：**

| UI 组件 | 测试内容 |
|---------|----------|
| 新 Composable | 渲染验证（关键文本可见）+ 交互（按钮点击） |
| 含倒计时组件 | 初始状态 + 倒计时触发 + 倒计时结束 |
| 含列表组件 | 空列表 + 有数据列表 + 长列表滚动 |
| 含错误状态组件 | 正常状态 + 错误状态 + 重试 |

---

## 2. 方法论层（原 qa-methodology.md）：交叉验证 · 证据链 · 可复现 · 并行节点 · 关闭判定

> 本章回答"怎么验、验到什么程度、如何组织证据"；不重复命令细节（见 §1 与各引用文档）。

### 2.0 核心原则（一句话）

```
NO COMPLETION CLAIMS WITHOUT CROSS-VERIFIED, REPRODUCIBLE EVIDENCE
```

任何"已修复/已完成"声明必须同时满足：
1. **多维度交叉验证**：同一结论至少 2 个**独立维度**的证据互相印证（单一维度证据 = 未验证）；
2. **证据链完整**：从"操作步骤"到"原始证据文件"到"结论"全链路可追溯；
3. **操作可复现**：按文档步骤，任何人（含后续 agent）可重跑并得到相同结果。

### 2.1 证据通道与 V1-V6 维度映射

原方法论层以「五个独立维度」（D1-D5）叙述验证手段。按编号体系统一裁决
（[`docs/numbering-charter.md`](numbering-charter.md)：**D 前缀全局退役**，验证维度统一为 V1-V6），
现改写为「证据通道 → V 维度」的映射表述，**不再保留第二套独立编号体系**：

| 证据通道（原 D 系口径） | 映射到 | 手段 | 回答的问题 | 证据形态 |
|------|------|------|-----------|----------|
| **代码检查**（原 D1） | **V1** 代码层面验证 | git diff / 源码走查 / grep 断言 | 修复是否在根因处、是否有补丁式掩盖 | commit diff、关键代码行引用 |
| **编译+测试**（原 D2） | **V1 + V4a** | compile / 单测 / i18n-check | 是否破坏构建与既有行为 | 构建输出、测试报告（tests/failures 数） |
| **模拟器操作**（原 D3） | **V2 / V3 / V4** | adb 驱动 UI（tap/input/scroll）+ 截图 + UI dump | 用户视角的行为是否符合预期 | 截图（PNG）、uiautomator dump（XML）、操作序列记录 |
| **可观测性**（原 D4） | **V5**（+观测手册手段） | logcat（按 PID+tag）/ Room 直查 / 服务器 curl | 内部状态与数据流是否与设计一致 | 日志原文、sqlite 查询输出、curl 响应 |
| **用户人工验收**（原 D5） | **V6**（仅四类人工项） | 真手指体感 / 用户凭据 / 数日观察 / 主观拍板 | 仪器不可覆盖的体验与凭据面 | 用户反馈记录、人工验证清单 |

> （合并注：原 D5 行把「真机/时间性现象（闪烁/动画/fling）」列为人工验收手段，与 §1 V6 的 2026-09-07 勘误冲突——以更新日期的勘误为准：时间性现象的可量化面先走录屏+video-analyzer 与像素采样取证，仅主观观感部分落入 V6。）
> （合并注：原文「维度编号对齐 regression-guide §2 的 D0-D4（D0=D1+D2、D1=D2、…）」的对应式基于 charter 定稿前的口径混淆；numbering-charter 已澄清 regression 的 D0-D4 是**回归档位**（迁移为 R0-R4，场景矩阵轴），与验证维度是两套轴，故删除该对应式。）

### 2.2 交叉验证矩阵（核心）

#### 2.2.1 规则

**每一个关键结论（bug 修复、功能完成、回归通过）必须由 ≥2 个独立维度交叉印证。**

| 结论类型 | 必选维度组合（至少 2 个，建议 3 个） |
|----------|------------------------------------|
| 逻辑/状态修复（如 FSM、契约解析） | V1 代码检查 + V5 可观测性（日志/DB）[+ V3 实机走查] |
| UI 行为修复（如消息显示、菜单文案） | V3 实机走查 + V5 可观测性（logcat 事件）[+ V1 代码检查] |
| 崩溃/异常修复 | V5 日志（崩溃消失）+ V3 模拟器（原路径重跑不崩）[+ V1 根因代码] |
| 数据/存储修复 | V5 DB 直查（落库值正确）+ V1 代码检查 [+ V3 UI 呈现] |
| 性能/时间性修复 | V3 仪器取证（录屏+video-analyzer、像素采样；基线对比见 regression-guide §2.1）+ V6 主观拍板（仅主观观感部分）（合并注：原「模拟器+人工」组合按 V6 2026-09-07 勘误重构——时间性可量化面归仪器，不默认转人工） |
| 文案/i18n | V3 模拟器（界面可见）+ 脚本检查（i18n-check，V1/V4a 通道） |

#### 2.2.2 交叉验证的"独立性"要求

两个维度必须**来自不同证据源**才算交叉：

- ✅ 有效交叉：logcat 事件（V5）+ 截图可见气泡（V3）——观测通道不同
- ✅ 有效交叉：单元测试断言（V4a）+ Room 落库值（V5）——验证层次不同
- ❌ 无效交叉：两张不同时刻的截图（都是 V3，单维度）
- ❌ 无效交叉：代码检查 + 同一次编译输出（若结论是"编译通过"则合法；
   若结论是"功能正确"则不构成交叉）

#### 2.2.3 无法交叉时的处理

- 时间性现象（fling、闪烁）→ 先走仪器：screenrecord+video-analyzer、像素采样取客观证据；
  其中仅**主观观感**部分落入 **V6 人工验证清单**，并明确标注"自动化未覆盖，
  待用户真机确认"（不得声称完成）；
- 环境受限（无模拟器）→ 明确标注缺失维度，**降级为部分验证**并登记 backlog；
- 维度冲突（日志说 A、UI 显示 B）→ **以 V5 可观测性为准排查**，冲突本身是 bug 证据，记录归档。

### 2.3 证据链完整性

#### 2.3.1 证据链定义

```
操作步骤（可复现） → 原始证据（文件/输出） → 断言（预期 vs 实际） → 结论
```

每一环都必须可追溯：

1. **操作步骤**：记录在验证清单/文档中（如 `docs/simulator-walkthrough-v1v2.md` 的走查清单），
   含环境（模拟器/服务器/版本）、前置条件、具体操作序列；
2. **原始证据**：落盘到 `docs/research/<topic>/`（命名规则见 regression-guide §5.1）：
   - 截图 `*.png` + UI dump `*.xml`（模拟器操作通道，V2/V3）
   - logcat 原文 `*.log`（可观测性，V5；必须按 PID+tag 过滤，观测细节见 `docs/probing.md`）
   - DB 直查输出 `*.txt` / curl 响应（可观测性，V5）
   - 测试报告 XML / 构建输出（V1/V4a）
   - commit diff（V1 代码检查）
3. **断言**：每个证据旁标注预期值（如"日志应出现 `[send-seed]`、DB 应新增 1 行"），
   并记录实际值；
4. **结论**：仅当断言全部满足且 ≥2 维度交叉时才可下结论。

#### 2.3.2 证据必须"新鲜"

- 禁止引用上次会话/上次构建的证据——必须在**当前验证轮次**中重新采集；
- 每个声称完成的消息中必须包含**本次执行**的命令输出（本文 §5 输出规范）。

#### 2.3.3 证据归档

- 修复类工作：证据随 commit 提交（同 PR）；
- 发版类工作：证据归档到对应 release 目录；
- 研究类工作：证据归档到 `docs/research/<topic>/`，并在文档中引用。

### 2.4 操作可复现性

#### 2.4.1 可复现定义

验证操作必须**书面化**到可被后续 agent/用户按步骤重跑的程度：

1. **环境固定**：记录模拟器（`adb devices`）、App 版本（`dumpsys package ... versionName`）、
   服务器版本（`/api/health` 或版本探测日志）、网络拓扑（宿主机 `10.0.2.2` 等）；
2. **步骤最小化**：每个验证节点 = 一个可独立执行的操作序列（如"连接服务器 → 打开会话 → 发送消息"）；
3. **断言明确**：每步附"通过标准"（可机器核对的预期值）；
4. **超时与重试**：SSE/网络类操作标注等待时长（如"等待 ≤5s 出现气泡"）；
5. **失败路径**：记录"若未出现预期值，检查什么"（如先查 logcat 再看 DB）。

#### 2.4.2 可复现清单模板

```markdown
## 验证节点：<名称>
- 环境：emulator-5554 · dev <versionName> · server <version>
- 前置：<服务器已连接 / 会话已存在>
- 步骤：
  1. <操作 1>
  2. <操作 2>
- 通过标准：
  · <断言 1>（证据：<文件>）
  · <断言 2>（证据：<文件>）
- 交叉验证：<维度组合>
```

#### 2.4.3 E2E 双文档模式（期望文档 + 实操文档）

大型端到端验证（如对话全生命周期）**必须**采用双文档模式，将"期望"与"实操"分离：

| 文档 | 角色 | 内容 | 示例 |
|------|------|------|------|
| **期望文档**（plan） | 测试前编写，规定"测什么、期望看到什么" | checkbox + 自然语言用例；分类（正向/逆向/极端）；**时间点驱动断言**（0s 截图→期望 X→x 秒后截图→期望 Y→未达则等 x 秒重试最多 N 遍）；**每阶段日志/DB 期望**（哪个 tag 出现什么、数据库增删什么） | `docs/dialogue-e2e-test-plan.md` |
| **实操文档**（runbook） | 测试中实时记录，对比期望 | 每轮执行：操作时间线 + 实际观察 + 对比期望 + 判定（PASS/FAIL/受限）+ **问题归属分类**（操作问题 / 观测问题 / 代码问题） | `docs/dialogue-e2e-test-runbook.md` |

**工作流**：
1. 写期望文档（可执行、可核对、含重试策略）→ 2. 按期望执行 → 3. 实操文档实时记录 → 4. 逐条对比期望 → 5. 差异分类：
   - **操作问题**（步骤/时机/环境）→ 修正操作重测；
   - **观测问题**（日志缺失/截图时机差/命令错误）→ 补观测手段重测；
   - **代码问题**（行为与期望不符）→ 根因修复后重测；
6. 未达成项必须登记归属分类与状态，不得静默跳过。

**编排评估**（期望文档 §0）：每项验证先分类 **DYN（动态 UI）/ STA（静态）/ SCR（脚本）**，
核心断言优先 SCR（logcat/DB/curl——可重复可机器核对），视觉断言用 DYN（截图+视觉分析），
同一结论至少 1 SCR + 1 DYN 交叉。

### 2.5 条目关闭判定（backlog 完结的验证门槛，2026-09-04 用户裁决）

backlog 条目（bug 修复/适配/工具链类）在声称完成并关闭前，除 §2.2 交叉验证外还须满足：

1. **真机端到端测试**：在真实设备上跑通该条目声称修复/实现的完整链路（非仅单测/模拟器）；
2. **可观测性优先**：以 V5 可观测性手段（logcat 按 tag 过滤 / Room 直查 / 服务器 curl / UI dump）观测内部行为，评估**观测到的行为与设计预期一致**；
3. **仪器优先、例外人工**（对齐 backlog「验证方针」2026-09-03 定规）：**UIUX 不豁免**——凡存在可注入/可观测的自动化仪器（uiautomator dump/tap、logcat/Room、curl、debug 注入、网络扰动）即以仪器 E2E 验证并关闭；**仅 4 类例外**走 V6 人工：①真手指连续手势/体感；②需用户凭据/跨设备操作；③数日级真实使用观察；④主观体验拍板（"好不好用"的最终确认）。判定次序：先穷举仪器 → 构造红回路 → E2E 验证关闭；找不到仪器才登记人工项并写明原因；
4. **多 case 层层递进**：不得只测单一路径——用例组织遵循层次递进：
   - **L1 连接层**（可达/鉴权/恢复）→ **L2 数据面**（列表/历史/流式/动态变更）→ **L3 交互面**（提问/审批/切换等用户动作闭环）→ **L4 回归面**（既有能力域无回归）；
   - 每层内覆盖**正向 + 逆向 + 边界**（如 token：正确/错误/过期；探测：三态各验）；
   - 逐层推进：上层失败不进入下层（先修再续），全部通过方可判关。

> **2026-09-04 補充裁决（/grill-me 定稿；2026-09-07 勘误：原文「UIUX 改动仍需人工验收」与本节第 3 条仪器优先自相矛盾且致可仪器化项误入人工清单，重构为类别制）**：关闭权限按本节第 3 条四类人工例外执行——仪器可验面（含全部 UI 结构/流程/色值）证据确凿即关闭；仅四类人工项在 AI 三步验收全绿后待人工确认（清单按域边界汇总提交，不逐卡打断；**每项标注类别与「为何仪器不可行」**；时间性现象以录屏+video-analyzer 作前置客观证据）。完整执行协议见 [`ai-acceptance-workflow.md`](ai-acceptance-workflow.md)。
>
> 关闭动作本身仍遵守 backlog 纪律：完结条目当场迁入 journal，卡片区不留尸。

### 2.6 并行验证节点（subagent 委派）

#### 2.6.1 适用场景

验证工作满足以下条件时**必须**考虑并行化：

- 存在 ≥3 个互相独立的验证节点（不同能力域 / 不同服务器 / 不同 UI 路径）；
- 单节点耗时 > 1 分钟（如等待 SSE 流式、网络请求、模拟器操作序列）；
- 主 agent 上下文预算有限（模拟器交互/日志抓取会快速消耗上下文）。

#### 2.6.2 节点划分原则

1. **按能力域切分**（对齐 regression-guide §3 的 12 能力域）；
2. **节点间零共享可变状态**：不同节点操作不同会话/不同服务器/不同页面，避免并行干扰；
3. **每个节点自带完整自包含 prompt**：环境、步骤、断言、证据落盘路径——subagent 不共享
   主会话上下文，必须自给自足；
4. **证据落盘分离**：每个节点写入独立目录（`docs/research/<topic>/node-N-*/`），避免写冲突。

#### 2.6.3 委派协议（主 agent ↔ subagent）

```
主 agent：
  1. 划分节点（编号 N1..Nk），每个节点写清：环境/步骤/断言/证据路径
  2. 并行 launch 全部 subagent（每个节点一个）
  3. 等待全部返回 → 汇总结果到交叉验证矩阵
  4. 对每个节点做"抽查复核"：至少 1 个节点的证据文件真实存在且内容符合断言
     （防 subagent 虚报——本文 §4 Subagent 验证协议）

subagent：
  1. 按节点清单执行操作序列
  2. 采集原始证据（截图/dump/log/db）落盘到指定路径
  3. 返回结构化结果：通过/失败 + 断言对照 + 证据文件列表
  4. 禁止代替主 agent 下"功能完成"结论——只上报证据与观察
```

#### 2.6.4 并行验证的合并

- 所有节点返回后，主 agent 构建**交叉验证矩阵**（节点 × 维度），标注每个格子的证据文件；
- 任一同结论的节点失败 → 整体结论失败，定位差异（环境？竞态？真 bug？）；
- 合并结果归档为验证报告（结构见 regression-guide §5.2）。

---

## 3. Task 与 Layer 验证流程

### 3.1 每个 Task 的验证流程

```
Task 开始
  │
  ├── 编写测试代码
  │     └── 运行测试 → 失败（预期）
  │
  ├── 编写实现代码
  │     └── 运行测试 → 通过
  │
  ├── 编译检查
  │     └── compileDevDebugKotlin → BUILD SUCCESSFUL
  │
  ├── 如果涉及 UI 变更：
  │     ├── 编写 Maestro flow
  │     └── 编写 androidTest（如适用）
  │
  └── 提交代码
        └── commit message 包含 spec 编号
```

### 3.2 每个 Layer 完成后的验证清单

> （合并注：原清单以 V1-V9 序号标注检查项，与 V1-V6 维度编号冲突（如原"V2 单测"撞维度 V2 Maestro）；
> 已改为按检查项命名并标注对应维度，条目内容一条未增删。）

- [ ] **编译**（V1）：`compileDevDebugKotlin` 编译通过（当前消息中执行）
- [ ] **单测**（V1 执行 / V4a 覆盖）：`testDevDebugUnitTest --rerun` 全部通过，0 failures（当前消息中执行）
- [ ] **增强覆盖**（V4a）：新增代码有对应的增强单元测试（边界/异常/并发）
- [ ] **Maestro flow 文件**（V2）：涉及 UI 的变更有 Maestro flow 文件
- [ ] **分档实跑**（V4）：Maestro flow **按档位实机运行**：每阶段收尾跑**冒烟档**（V4 §A，10 个 flow）；正式发版前跑**全面档**（V4 §B，全量 flow + androidTest）
- [ ] **androidTest 文件**（V4b）：涉及 UI 的变更有 androidTest 文件
- [ ] **实机 androidTest**（V3 铁律）：`connectedDevDebugAndroidTest` **在模拟器上实际执行通过**
- [ ] **AndroidTest 编译**（V1）：`compileDevDebugAndroidTestKotlin` 编译通过
- [ ] **日志覆盖**（V5）：关键业务路径有 Log 输出
- [ ] **git diff 审查**（V1 代码检查通道）：无预期外的文件改动
- [ ] **人工项**（V6）：涉及 V6 四类人工项（真手指体感/凭据/长期观察/主观拍板）时：提供人工验证清单（每项含类别+仪器不可行理由）+ 用户反馈记录；时间性现象的可量化面以录屏/像素证据计入 V2/V3，不触发本项

---

## 4. Subagent 验证协议

当通过 subagent 执行任务时：

1. **Subagent 报告 DONE** → 主 agent 不信任报告
2. **主 agent 必须独立验证**：
   - 检查 `git diff` 确认文件确实被修改
   - 运行编译检查确认代码编译通过
   - 运行单元测试确认测试通过
3. **验证失败** → 将 subagent 结果标记为不可信，重新派发

多节点并行委派的节点划分与结果合并规则见 §2.6 并行验证节点。

---

## 5. 输出规范

所有验证结果必须包含**实际命令输出**，而非概括：

```
✅ compileDevDebugKotlin → BUILD SUCCESSFUL in 7s (executed at 14:32)
✅ testDevDebugUnitTest → 89/89 tests passed, 0 failures (executed at 14:33)
❌ compileDevDebugAndroidTestKotlin → FAILED: unresolved reference 'X'
```

禁止的声明格式：
- ~~"应该通过了"~~
- ~~"看起来没问题"~~
- ~~"上次运行时是通过的"~~
- ~~"Subagent 说测试全部通过"~~

---

## 6. 文档关系

| 文档 | 角色 | 引用要点 |
|------|------|----------|
| `docs/verification.md`（本文） | 验证框架 + 方法论权威 | 铁律、V1-V6 维度、交叉验证矩阵、证据链、可复现、并行节点、关闭判定、输出规范 |
| `docs/probing.md`（观测与探测手册，即将由他人创建） | 观测手段细节 | logcat 规范（PID+tag）、Room 直查、服务器 curl、截图/UI dump 取证 |
| `docs/device-testing.md`（测试环境 runbook，即将创建） | 测试环境准备 | 模拟器/真机环境、装包与服务器连通配置 |
| `docs/regression-guide.md` | 回归执行 | 变更分类、12 能力域清单、R0-R4 回归档位、证据命名（§5.1）、验证报告结构（§5.2） |
| `docs/dialogue-e2e-test-plan.md` | E2E 期望文档实例（§2.4.3 模式） | 对话全生命周期用例 + 时间点断言 + 日志/DB 期望 |
| `docs/dialogue-e2e-test-runbook.md` | E2E 实操文档实例（§2.4.3 模式） | 执行记录 + 问题归属分类（操作/观测/代码） |
| `AGENTS.md` | 项目规则源 | 验证铁律入口、backlog 纪律、文档索引 |

---

*本文档是 OC Beacon 项目的强制验证标准与方法论权威。违反本标准的完成声明等同于虚假声明。*
*维护：验证手段演进时更新 §2.1 通道映射表；维度编号语义以 [`docs/numbering-charter.md`](numbering-charter.md) 为准。*
