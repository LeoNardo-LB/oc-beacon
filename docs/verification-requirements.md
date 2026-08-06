# 验证要求文档 (Verification Requirements)

> 本文档定义了 OC Remote 项目每个开发阶段完成前**必须**执行的验证流程。
> 所有 agent（主 agent 和 subagent）在声称任务完成前必须遵守。

---

## 0. 铁律

```
NO COMPLETION CLAIMS WITHOUT FRESH VERIFICATION EVIDENCE
```

**必须加载 `verification-before-completion` 技能**作为验证指导原则。该技能定义的 Gate Function 是本项目的强制执行标准：

```
BEFORE claiming any status:
1. IDENTIFY — 什么命令能证明这个声明？
2. RUN      — 执行完整命令（新鲜的、完整的）
3. READ     — 读取完整输出，检查退出码，计算失败数
4. VERIFY   — 输出是否确认声明？
5. ONLY THEN — 才能做出声明
```

---

## 1. 四维验证框架

每个 Layer 完成后，必须通过以下四个维度的验证：

### 维度 1: 代码层面验证 (Code-Level Verification)

| 检查项 | 命令 | 超时 | 通过标准 |
|--------|------|------|----------|
| Kotlin 编译 | `.\gradlew :app:compileDevDebugKotlin` | 120s | BUILD SUCCESSFUL, 0 errors |
| 全量单元测试 | `.\gradlew :app:testDevDebugUnitTest --rerun` | 180s | 0 failures |
| AndroidTest 编译 | `.\gradlew :app:compileDevDebugAndroidTestKotlin` | 120s | BUILD SUCCESSFUL |
| 全量构建 | `.\gradlew :app:assembleDevDebug` | 300s | BUILD SUCCESSFUL |

**规则：**
- 编译检查在**每个 Task** 完成后执行
- 全量单元测试在**每个 Layer** 全部 Task 完成后执行
- 全量构建在**重大里程碑**（Layer 1/3/5 完成后）执行
- 禁止用上次运行结果替代——必须在**当前消息中**执行命令

### 维度 2: 自动化框架 + 模拟器截图验证 (E2E Screenshot Verification)

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

### 维度 2b: 模拟器实机调用 + 截屏走查（铁律）

> **本维度是整个验证体系中最重要的环节。没有通过本维度的验证，任何 Layer 的完成声明无效。**

**适用范围：** 任何涉及 UI 变更或与 UI 有关联的能力（包括但不限于：新增/修改 Composable、ViewModel 状态变更影响 UI、事件处理导致 UI 更新、Repository 状态影响 UI 展示）。

**前置条件：**
- Android 模拟器运行中（`adb devices` 可见 `emulator-XXXX device`）
- App 已安装到模拟器（`.\gradlew :app:installDevDebug`）
- Maestro CLI 已安装（`maestro --version`）

**验证步骤：**

| 步骤 | 命令 | 超时 | 通过标准 |
|------|------|------|----------|
| 1. 安装 App | `.\gradlew :app:installDevDebug` | 300s | `Installed on 1 device` |
| 2. 运行 Maestro flow | `maestro test maestro/l{n}-{feature}.yaml` | 120s/flow | 所有步骤 COMPLETED |
| 3. 运行 androidTest | `.\gradlew :app:connectedDevDebugAndroidTest` | 300s | `Finished N tests`, BUILD SUCCESSFUL |
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

### 维度 2c: E2E 测试分档（冒烟 / 全面+回归）

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
- `.\gradlew :app:connectedDevDebugAndroidTest`（androidTest 全量实机）
- 全量单元测试：`.\gradlew :app:testDevDebugUnitTest --rerun`
- 编译 + 构建：`compileDevDebugKotlin` + `assembleBetaRelease`

**回归策略**：变更相关 flow 必跑 + 冒烟档全跑 + 全量兜底。

**通过标准**：全部 flow COMPLETED + androidTest 0 failures + 单测 0 failures。

**触发时机**：
- 正式发版（stable）前必跑
- 大版本 / 跨层重构收尾

### 维度 3: 代码分支日志输出验证 (Log Branch Verification)

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

### 维度 4: 完整测试框架验证 (Comprehensive Test Framework)

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

## 2. 每个 Task 的验证流程

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

## 3. 每个 Layer 完成后的验证清单

- [ ] **V1**: `compileDevDebugKotlin` 编译通过（当前消息中执行）
- [ ] **V2**: `testDevDebugUnitTest --rerun` 全部通过，0 failures（当前消息中执行）
- [ ] **V3**: 新增代码有对应的增强单元测试（边界/异常/并发）
- [ ] **V4**: 涉及 UI 的变更有 Maestro flow 文件
- [ ] **V4b**: Maestro flow **按档位实机运行**：每阶段收尾跑**冒烟档**（维度 2c §A，10 个 flow）；正式发版前跑**全面档**（维度 2c §B，全量 flow + androidTest）
- [ ] **V5**: 涉及 UI 的变更有 androidTest 文件
- [ ] **V5b**: `connectedDevDebugAndroidTest` **在模拟器上实际执行通过**（维度 2b 铁律）
- [ ] **V6**: `compileDevDebugAndroidTestKotlin` 编译通过
- [ ] **V7**: 关键业务路径有 Log 输出
- [ ] **V8**: git diff 审查——无预期外的文件改动

## 4. Subagent 验证协议

当通过 subagent 执行任务时：

1. **Subagent 报告 DONE** → 主 agent 不信任报告
2. **主 agent 必须独立验证**：
   - 检查 `git diff` 确认文件确实被修改
   - 运行编译检查确认代码编译通过
   - 运行单元测试确认测试通过
3. **验证失败** → 将 subagent 结果标记为不可信，重新派发

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

*本文档是 OC Remote 项目的强制验证标准。违反本标准的完成声明等同于虚假声明。*
