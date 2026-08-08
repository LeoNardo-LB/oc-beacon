# 提问组件改进实现计划（#26 + #27 + #28 + 会话列表待回答 + 双端同步）

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复提问组件单选/复选语义、三按钮交互流程、样式规范对齐，新增会话列表"待回答"标记与通知 REST 兜底，修复双端问题状态不同步。

**Architecture:** 纯逻辑修复集中在 QuestionParser（multiple 解析）与 MessageDataDelegate（loadPendingQuestions 全量替换）；UI 交互改造在 QuestionCard（三按钮体系）+ QuestionPagerView（页码感知）；会话列表展示走 SessionListViewModel 现有状态切片管道新增 1 源；通知兜底在 OpenCodeConnectionService 层定期拉取 REST `/question` 填充 eventDispatcher。

**Tech Stack:** Kotlin + Jetpack Compose + Hilt + Ktor + JUnit4 + MockK + Turbine

## Global Constraints

- 构建：`.\gradlew :app:compileDevDebugKotlin`（120s 超时）；单测：`.\gradlew :app:testDevDebugUnitTest --rerun`（180s 超时）；APK：`.\gradlew :app:assembleDevDebug`（300s 超时）
- **ChatScreen.kt 编辑协议**：本项目不涉及 ChatScreen.kt 主文件；涉及 ChatMessageList.kt 时先读 `docs/chatscreen-editing-protocol.md`
- **i18n 15 语言**：英文源 `app/src/main/res/values/strings.xml` + 14 翻译文件（ar/de/es/fr/id/it/ja/ko/pl/pt-rBR/ru/tr/uk/zh-rCN）；新增/修改文案后跑 `scripts/i18n-check.ps1`
- **日志**：新日志用 `AppLogger`，不用 `android.util.Log`
- **仅本次范围**：不重构无关代码；不碰 PermissionRequestCard；不改服务器端行为
- **提交纪律**：`git add` 只加本任务文件；工作区存在无关未提交文件（AGENTS.md/release.yml/backlog.md/docs/release-workflow.md/scripts/release.sh/docs/release-notes-template.md/docs/release-notes-template-design.md）——**不要 git add 这些**

---

### Task 1: QuestionParser multiple 解析（#26 数据链路）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/QuestionParser.kt`
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/QuestionParserTest.kt`

**Interfaces:**
- Consumes: 现有 `ParsedQuestion(displayText, answers, rawExtra)`、`QHistItem(text, options, answers, isMultiple)`、`parseQuestionContent(raw)`、`parseQuestionFromToolData(id, input, output)`
- Produces: `ParsedQuestion` 新增字段 `isMultiple: Boolean = false`；`parseQuestionFromToolData` 返回的 `QHistItem.isMultiple` 从工具输入 JSON 的 `multiple` 字段解析（缺省 false）；`parseQuestionContent` 在 JSON 格式（格式 2）解析 `multiple` 字段

- [ ] **Step 1: 写失败测试**

在 `QuestionParserTest.kt` 追加：

```kotlin
// ===== parseQuestionContent multiple =====

@Test
fun `JSON format - parses multiple true`() {
    val r = QuestionParser.parseQuestionContent("""{"question":"Which?","multiple":true}""")
    assertTrue(r.isMultiple)
}

@Test
fun `JSON format - multiple absent defaults false`() {
    val r = QuestionParser.parseQuestionContent("""{"question":"Which?"}""")
    assertFalse(r.isMultiple)
}

@Test
fun `opencode text format - multiple defaults false`() {
    val raw = """Asked 1 question. questions: [{"question":"Pick"}]
        |User has answered: "A". You can continue.""".trimMargin()
    assertFalse(QuestionParser.parseQuestionContent(raw).isMultiple)
}

// ===== parseQuestionFromToolData multiple =====

@Test
fun `tool data - parses multiple from question json`() {
    val input = mapOf(
        "questions" to buildJsonArray {
            add(buildJsonObject {
                put("question", "Pick many")
                put("multiple", true)
                put("options", buildJsonArray {
                    add(buildJsonObject { put("label", "A") })
                    add(buildJsonObject { put("label", "B") })
                })
            })
        }
    )
    val items = QuestionParser.parseQuestionFromToolData("id", input, "")
    assertEquals(1, items.size)
    assertTrue(items[0].isMultiple)
}

@Test
fun `tool data - multiple absent defaults false`() {
    val input = mapOf(
        "questions" to buildJsonArray {
            add(buildJsonObject {
                put("question", "Pick one")
                put("options", buildJsonArray { add(buildJsonObject { put("label", "A") }) })
            })
        }
    )
    val items = QuestionParser.parseQuestionFromToolData("id", input, "")
    assertEquals(1, items.size)
    assertFalse(items[0].isMultiple)
}

@Test
fun `tool data - JSONArray fallback parses multiple`() {
    val output = """questions: [{"question":"Pick many","multiple":true,"options":[{"label":"A"}]}]
        |User has answered: "A", "B". You can continue.""".trimMargin()
    val items = QuestionParser.parseQuestionFromToolData("id", emptyMap(), output)
    assertTrue(items.isNotEmpty())
    assertTrue(items[0].isMultiple)
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.ui.screens.chat.util.QuestionParserTest" --rerun`
Expected: FAIL（`ParsedQuestion.isMultiple` 不存在 → 编译失败）

- [ ] **Step 3: 实现**

`QuestionParser.kt` 修改：

```kotlin
internal data class ParsedQuestion(
    val displayText: String,
    val answers: List<String>,
    val rawExtra: String,
    val isMultiple: Boolean = false
)
```

`parseQuestionContent` 的 JSON 分支（格式 2）返回处：

```kotlin
// JSONObject 解析中补充：
val isMultiple = json.optBoolean("multiple", false)
// 返回：
ParsedQuestion(displayText = q, answers = answers, rawExtra = "", isMultiple = isMultiple)
```

`parseQuestionFromToolData` 两处构造 `QHistItem` 补充 `isMultiple`：

```kotlin
// 结构化 JsonArray 分支（qObj 已有）：
val multiple = qObj["multiple"]?.jsonPrimitive?.booleanOrNull ?: false
items.add(QHistItem(qText, opts, emptyList(), isMultiple = multiple))

// JSONArray 回退分支（obj 已有）：
items.add(QHistItem(qText, opts, emptyList(), isMultiple = obj.optBoolean("multiple", false)))
```

注意：`parseQuestionContent` 格式 1（opencode 文本）与格式 3（纯文本）不解析 multiple，保持默认 false。

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.ui.screens.chat.util.QuestionParserTest" --rerun`
Expected: PASS（含既有测试，7 个新测试）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/QuestionParser.kt app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/util/QuestionParserTest.kt
git commit -m "feat: QuestionParser 解析 multiple 字段（历史链路单选/复选语义修复）"
```

---

### Task 2: CollapsibleQuestionPart 图标按 multiple + 调试日志清理（#26 UI）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/QuestionPartContent.kt:114-158`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/PartContent.kt:121-131`

**Interfaces:**
- Consumes: Task 1 的 `ParsedQuestion.isMultiple`
- Produces: `CollapsibleQuestionPart` 答案图标按 `parsed.isMultiple` 分支；`PartContent.kt` 删除调试日志（`AppLogger.e("TOOL ELSE:...")`、`AppLogger.e("PartContent", "isQuestionTool=...")`、`DebugLogger.log` 5 行）

- [ ] **Step 1: 修改 CollapsibleQuestionPart 图标**

`QuestionPartContent.kt` 的 `CollapsibleQuestionPart` 内答案图标（当前第 134-139 行固定 `Icons.Default.RadioButtonChecked`）：

```kotlin
Icon(
    imageVector = if (parsed.isMultiple) Icons.Default.CheckBox else Icons.Default.RadioButtonChecked,
    contentDescription = null,
    modifier = Modifier.size(14.dp),
    tint = accentColor
)
```

- [ ] **Step 2: 删除 PartContent 调试日志**

`PartContent.kt` 第 121 行 `AppLogger.e("PartContent", "TOOL ELSE: ...")`、第 124 行 `AppLogger.e("PartContent", "isQuestionTool=...")`、第 127-131 行 5 行 `DebugLogger.log(...)` 全部删除。保留 `isQuestionTool` 判断逻辑（第 122-123 行）与后续正常分支。删除后检查 import（`DebugLogger` 若无其他使用则移除 import）。

- [ ] **Step 3: 编译**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/QuestionPartContent.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/PartContent.kt
git commit -m "fix: 历史提问多选答案显示复选框 + 清理 PartContent 调试日志残留"
```

---

### Task 3: 三按钮交互体系（#27）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/dialog/QuestionCard.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/QuestionPartContent.kt`（QuestionPagerView 增加页码感知）
- Modify: `app/src/main/res/values/strings.xml` + 14 翻译文件
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/dialog/QuestionCardLogicTest.kt`（新建）

**Interfaces:**
- Consumes: `SseEvent.QuestionAsked`（已有）、`QuestionPagerView(questions, selectedAnswers, readOnly, onOptionClick)`（已有）
- Produces: `QuestionPagerView` 新增参数 `pagerState: PagerState? = null`、`onPageSelected: (Int) -> Unit = {}`；`QuestionCard` 内新增纯函数 `unansweredQuestionIndexes(answers: List<List<String>>, questionCount: Int): List<Int>`（1-based 索引，供弹窗文案与测试）

**设计要点：**
- pagerState 提升到 QuestionCard（`rememberPagerState(pageCount = { question.questions.size })`），多问题时传给 QuestionPagerView 渲染；单问题时 QuestionPagerView 内部走单页分支（不建 pagerState）
- 底部按钮区三按钮：忽略（左）/ 下一个 + 提交（右）；`currentPage` 取自 pagerState（单问题固定 0）
- "下一个"：`scope.launch { pagerState.animateScrollToPage((pagerState.currentPage + 1).coerceAtMost(pageCount - 1)) }`；末页时 enabled=false
- 提交：enabled = answersPerQuestion.any { it.isNotEmpty() }（部分回答可提交，保持）；点击时若 `unansweredQuestionIndexes` 非空 → AlertDialog（"第 X、Y 个问题没有回答"）→【继续提交/取消】
- 单选场景（单问题非 multiple）：点击选项 toggle（可取消选中），不再立即提交
- 历史模式（initiallySubmitted）：不显示按钮区（保持现状）

- [ ] **Step 1: 写失败测试**

新建 `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/dialog/QuestionCardLogicTest.kt`：

```kotlin
package dev.leonardo.ocbeacon.ui.screens.chat.dialog

import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionCardLogicTest {

    @Test
    fun `unansweredQuestionIndexes - empty answers returns all`() {
        val idx = unansweredQuestionIndexes(listOf(emptyList(), emptyList()), questionCount = 2)
        assertEquals(listOf(1, 2), idx)
    }

    @Test
    fun `unansweredQuestionIndexes - some answered returns only unanswered`() {
        val idx = unansweredQuestionIndexes(listOf(listOf("A"), emptyList(), listOf("B")), questionCount = 3)
        assertEquals(listOf(2), idx)
    }

    @Test
    fun `unansweredQuestionIndexes - all answered returns empty`() {
        val idx = unansweredQuestionIndexes(listOf(listOf("A"), listOf("B")), questionCount = 2)
        assertEquals(emptyList<Int>(), idx)
    }

    @Test
    fun `unansweredQuestionIndexes - short answers list pads with unanswered`() {
        val idx = unansweredQuestionIndexes(listOf(listOf("A")), questionCount = 3)
        assertEquals(listOf(2, 3), idx)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.ui.screens.chat.dialog.QuestionCardLogicTest" --rerun`
Expected: FAIL（函数未定义）

- [ ] **Step 3: 在 QuestionCard.kt 定义纯函数**

```kotlin
/**
 * 返回未回答问题的问题编号列表（1-based，按问题顺序）。
 * answers 长度可能小于 questionCount（Pager 懒加载时未访问页无答案项），缺失视为未回答。
 */
internal fun unansweredQuestionIndexes(
    answers: List<List<String>>,
    questionCount: Int
): List<Int> {
    return (0 until questionCount)
        .filter { idx -> answers.getOrNull(idx).isNullOrEmpty() }
        .map { it + 1 }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.ui.screens.chat.dialog.QuestionCardLogicTest" --rerun`
Expected: PASS

- [ ] **Step 5: 改造 QuestionPagerView（页码感知）**

`QuestionPartContent.kt` 的 `QuestionPagerView` 签名与多问题分支：

```kotlin
@Composable
internal fun QuestionPagerView(
    questions: List<SseEvent.QuestionAsked.Question>,
    selectedAnswers: List<Set<String>>,
    readOnly: Boolean = false,
    onOptionClick: ((pageIndex: Int, label: String) -> Unit)? = null,
    pagerState: androidx.compose.foundation.pager.PagerState? = null,
    onPageSelected: (Int) -> Unit = {}
) {
    if (questions.size <= 1) {
        // 单页分支不变
        questions.firstOrNull()?.let { q ->
            QuestionOptionRows(q, selectedAnswers.firstOrNull() ?: emptySet(), readOnly) { onOptionClick?.invoke(0, it) }
        }
    } else {
        val scope = rememberCoroutineScope()
        val density = androidx.compose.ui.platform.LocalDensity.current
        var maxPageHeight by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val state = pagerState ?: rememberPagerState(pageCount = { questions.size })
        androidx.compose.runtime.LaunchedEffect(state.currentPage) {
            onPageSelected(state.currentPage)
        }
        Column {
            SecondaryTabRow(selectedTabIndex = state.currentPage, containerColor = Color.Transparent) {
                questions.indices.forEach { i ->
                    Tab(selected = state.currentPage == i,
                        onClick = { scope.launch { state.animateScrollToPage(i) } },
                        text = { Text("Q${i + 1}", style = MaterialTheme.typography.labelSmall) })
                }
            }
            HorizontalPager(
                state = state,
                modifier = Modifier.fillMaxWidth().then(
                    if (maxPageHeight > 0) Modifier.height(with(density) { maxPageHeight.toDp() }) else Modifier
                ),
                beyondViewportPageCount = 1,
                pageSpacing = 8.dp,
            ) { page -> /* 不变，QuestionOptionRows 调用不变 */ }
        }
    }
}
```

注意：原代码 `val pagerState = rememberPagerState(...)` 改为 `val state = pagerState ?: rememberPagerState(...)`，其余引用 `pagerState` 改为 `state`（Tab onClick / HorizontalPager state / 页面偏移计算 / maxPageHeight 逻辑不变）。

- [ ] **Step 6: 改造 QuestionCard（三按钮 + 弹窗 + 单选 toggle）**

`QuestionCard.kt` 主要改动：

```kotlin
// 顶部新增 import：
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.LaunchedEffect

// 在 isSingle 定义后新增 pagerState：
val pagerState = if (question.questions.size > 1) {
    rememberPagerState(pageCount = { question.questions.size })
} else null

// 在 answersPerQuestion 后新增当前页状态与对话框状态：
var showUnansweredDialog by remember(question.id) { mutableStateOf(false) }
var currentPage by remember(question.id) { mutableIntStateOf(0) }

// QuestionPagerView 调用增加参数：
QuestionPagerView(
    questions = question.questions,
    selectedAnswers = answersPerQuestion.map { it.toSet() },
    readOnly = submitted,
    pagerState = pagerState,
    onPageSelected = { currentPage = it },
    onOptionClick = { pageIndex, label ->
        if (!submitted) {
            performHaptic(hapticView, hapticOn)
            val current = answersPerQuestion.getOrNull(pageIndex)?.toMutableList() ?: mutableListOf()
            if (isSingle) {
                // 单选：toggle——选中项取消则清空，否则替换为该项
                answersPerQuestion[pageIndex] = if (current == listOf(label)) emptyList() else listOf(label)
            } else {
                if (label in current) current.remove(label) else current.add(label)
                if (pageIndex < answersPerQuestion.size) answersPerQuestion[pageIndex] = current
            }
        }
    }
)

// 底部按钮区替换（!initiallySubmitted 时）：
if (!initiallySubmitted) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = { if (!submitted) { performHaptic(hapticView, hapticOn); submitted = true; onReject() } },
            enabled = !submitted
        ) {
            Text(stringResource(R.string.chat_dismiss))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)) {
            if (!isSingle && pagerState != null) {
                Button(
                    onClick = {
                        performHaptic(hapticView, hapticOn)
                        scope.launch { pagerState.animateScrollToPage((currentPage + 1).coerceAtMost(question.questions.size - 1)) }
                    },
                    enabled = !submitted && currentPage < question.questions.size - 1
                ) {
                    Text(stringResource(R.string.question_next))
                }
            }
            Button(
                onClick = {
                    if (!submitted) {
                        performHaptic(hapticView, hapticOn)
                        val unanswered = unansweredQuestionIndexes(answersPerQuestion.toList(), question.questions.size)
                        if (unanswered.isNotEmpty()) {
                            showUnansweredDialog = true
                        } else {
                            submitted = true
                            onSubmit(answersPerQuestion.map { it.toList() })
                        }
                    }
                },
                enabled = !submitted && answersPerQuestion.any { it.isNotEmpty() }
            ) {
                Text(stringResource(R.string.question_submit))
            }
        }
    }
}
```

在 AmoledCard 内部末尾（AnimatedVisibility 之后、外层 Column 之内）增加弹窗：

```kotlin
if (showUnansweredDialog) {
    val unanswered = unansweredQuestionIndexes(answersPerQuestion.toList(), question.questions.size)
    val label = stringResource(
        R.string.question_unanswered_confirm,
        unanswered.joinToString("、") { it.toString() }
    )
    AlertDialog(
        onDismissRequest = { showUnansweredDialog = false },
        title = { Text(stringResource(R.string.question_unanswered_title)) },
        text = { Text(label) },
        confirmButton = {
            TextButton(onClick = {
                showUnansweredDialog = false
                submitted = true
                onSubmit(answersPerQuestion.map { it.toList() })
            }) { Text(stringResource(R.string.question_continue)) }
        },
        dismissButton = {
            TextButton(onClick = { showUnansweredDialog = false }) {
                Text(stringResource(R.string.chat_dismiss))
            }
        }
    )
}
```

所需 scope：`val scope = rememberCoroutineScope()`（QuestionCard 顶部新增）。

- [ ] **Step 7: i18n 新增文案（英文源 + 14 翻译）**

`values/strings.xml` 新增：

```xml
<string name="question_next">Next</string>
<string name="question_unanswered_title">Unanswered questions</string>
<string name="question_unanswered_confirm">Questions %1$s have not been answered yet.</string>
<string name="question_continue">Continue</string>
```

按 i18n 惯例翻译 14 语言（参考现有 `question_submit`/`chat_dismiss` 的翻译风格）。中文示例（values-zh-rCN）：`question_next`=下一步、`question_unanswered_title`=有未回答的问题、`question_unanswered_confirm`=第 %1$s 个问题没有回答。、`question_continue`=继续提交

- [ ] **Step 8: 运行 i18n 检查 + 编译 + 全部单测**

Run: `powershell -File scripts/i18n-check.ps1`
Expected: 无 key 缺失/占位符不一致

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: 全部通过（含既有测试，无回归）

- [ ] **Step 9: 提交**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/dialog/QuestionCard.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/QuestionPartContent.kt app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/dialog/QuestionCardLogicTest.kt app/src/main/res/values/strings.xml app/src/main/res/values-*/strings.xml
git commit -m "feat: 提问卡片三按钮体系（忽略/下一个/提交）+ 未答完提交确认弹窗 + 单选可取消"
```

---

### Task 4: 样式规范对齐（#28）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/QuestionPartContent.kt`（QuestionOptionRows / QuestionPagerView）
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/dialog/QuestionCard.kt`（间距/外边距）

**Interfaces:**
- Consumes: `SpacingTokens`、`ShapeTokens`、`AlphaTokens`（已有）
- Produces: 视觉尺寸调整（无签名变化）

**设计要点（仅调 token/尺寸，不改结构）：**
- `QuestionOptionRows` 选项行：`Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp))` → `Modifier.defaultMinSize(minHeight = 48.dp).padding(horizontal = SpacingTokens.MD.dp, vertical = SpacingTokens.XS.dp)`（高度对齐 M3 触摸目标；图标行居中）
- 选项行图标：`Modifier.size(16.dp)` → `Modifier.size(24.dp)`（4 处：正常选项行、自定义答案已选行、自定义输入行图标、CollapsibleQuestionPart 答案图标 14.dp → 18.dp）
- 选项行文字：bodyMedium 保持；description bodySmall 保持
- `QuestionCard` 外层 `padding(SpacingTokens.MD.dp)` 保持；`QuestionPagerView` 多问题分支 TabRow 与内容之间加 `Arrangement.spacedBy(SpacingTokens.SM.dp)`（消除"缩在一起"）；选项间 `spacedBy(SpacingTokens.SM.dp)` 保持
- `QuestionOptionRows` 的问题文本（若有）保持 bodySmall
- `CollapsibleQuestionPart` 外层 `padding(4.dp)` → `padding(SpacingTokens.XS.dp)`；内层展开区 `padding(start = 20.dp, ...)` 保持

**验证：** 编译 + 真机目测（行高统一、图标大小、间距舒展）。先确认 `SpacingTokens.XS` 存在（若无则用 `SpacingTokens.SM` 一半值或 4.dp 常量并在备注说明）。

- [ ] **Step 1: 确认 SpacingTokens 取值**

读 `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/theme/SpacingTokens.kt`，记录 XS/SM/MD 的 dp 值。若 XS 不存在，用 `SM.dp / 2` 并加注释。

- [ ] **Step 2: 调整 QuestionOptionRows 样式**

`QuestionPartContent.kt` 的 `QuestionOptionRows`：选项行 `Row` 增加 `defaultMinSize(minHeight = 48.dp)`（需要 `import androidx.compose.foundation.layout.defaultMinSize`），图标 16→24dp（含正常选项与自定义答案已选行）。自定义输入行图标 14→24dp。

- [ ] **Step 3: 调整 QuestionCard / QuestionPagerView 间距**

`QuestionCard.kt` 底部按钮 Row 与内容之间、`QuestionPagerView` 多问题 TabRow 与 HorizontalPager 之间加 `SpacingTokens.SM.dp` 间距（用 `Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp))` 包裹已有内容或直接加 `Spacer(Modifier.height(SpacingTokens.SM.dp))`，选结构改动最小的方式）。

- [ ] **Step 4: 编译**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/QuestionPartContent.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/dialog/QuestionCard.kt
git commit -m "style: 提问组件样式规范对齐（选项行 48dp 触摸目标 / 图标 24dp / 间距统一）"
```

---

### Task 5: loadPendingQuestions 全量替换（双端同步修复）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/MessageDataDelegate.kt:369-424`
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/QuestionReplacementTest.kt`（新建，纯函数）

**Interfaces:**
- Consumes: `chatRepository.setQuestions(sid, List<SseEvent.QuestionAsked>)`、`chatRepository.getQuestionsSnapshot()`、`managePermissionUseCase.listPendingQuestions(...)`
- Produces: 纯函数 `resolvePendingQuestionReplacement(restQuestions: List<SseEvent.QuestionAsked>): List<SseEvent.QuestionAsked>`（返回 REST 结果，即全量替换语义）；`loadPendingQuestions` 改为 REST 成功时全量替换（含子会话聚合结果），不再拼 existingSseQs

**修复语义：**
- REST `GET /question` 是权威源。请求成功后以返回集合为准替换该会话的问题（含子会话问题），消除"已消失问题永久残留"
- 删除 `existingSseQs`/`existingIds`/`newQs` 合并逻辑
- `setQuestions(sid, emptyList())` 已有"清空该 session 键"语义（`if (qs.isEmpty()) current - sessionId`），服务器无问题时自然清空

- [ ] **Step 1: 写失败测试**

新建 `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/QuestionReplacementTest.kt`：

```kotlin
package dev.leonardo.ocbeacon.ui.screens.chat

import dev.leonardo.ocbeacon.domain.model.SseEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionReplacementTest {

    private fun q(id: String) = SseEvent.QuestionAsked(
        id = id,
        sessionId = "ses_1",
        questions = emptyList()
    )

    @Test
    fun `rest result replaces previous snapshot entirely`() {
        val rest = listOf(q("que_2"), q("que_3"))
        assertEquals(listOf("que_2", "que_3"), resolvePendingQuestionReplacement(rest).map { it.id })
    }

    @Test
    fun `empty rest result clears session`() {
        assertEquals(emptyList(), resolvePendingQuestionReplacement(emptyList()))
    }

    @Test
    fun `rest result drops questions no longer pending`() {
        val rest = listOf(q("que_3"))
        assertEquals(listOf("que_3"), resolvePendingQuestionReplacement(rest).map { it.id })
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.ui.screens.chat.QuestionReplacementTest" --rerun`
Expected: FAIL（函数未定义）

- [ ] **Step 3: 在 MessageDataDelegate.kt 定义纯函数**

```kotlin
/**
 * REST GET /question 是全量权威源：以其返回集合为准替换该会话的待处理问题。
 * 不做与内存快照的合并——服务器上已消失的问题（他端已回答）必须被清除。
 */
internal fun resolvePendingQuestionReplacement(
    restQuestions: List<SseEvent.QuestionAsked>
): List<SseEvent.QuestionAsked> = restQuestions
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.ui.screens.chat.QuestionReplacementTest" --rerun`
Expected: PASS

- [ ] **Step 5: 修改 loadPendingQuestions**

`MessageDataDelegate.kt` 的 `loadPendingQuestions` 中，`sessionQuestions` 非空分支（当前 409-420 行）替换为：

```kotlin
if (sessionQuestions.isNotEmpty()) {
    chatRepository.setQuestions(sid, resolvePendingQuestionReplacement(sessionQuestions))
    if (BuildConfig.DEBUG) AppLogger.d(TAG, "Replaced ${sessionQuestions.size} questions for session $sid (REST authoritative)")
} else {
    // 服务器无 pending 问题——清空（含他端已回答的情况）
    chatRepository.setQuestions(sid, emptyList())
    if (BuildConfig.DEBUG) AppLogger.d(TAG, "No pending questions for session $sid, cleared")
}
```

删除原 `existingSseQs`/`existingIds`/`newQs` 合并块与"All N REST questions already present via SSE"日志分支。`sessionQuestions` 计算（含子会话）不变。

- [ ] **Step 6: 编译 + 全部单测**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: 全部通过（无回归）

- [ ] **Step 7: 提交**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/MessageDataDelegate.kt app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/QuestionReplacementTest.kt
git commit -m "fix: loadPendingQuestions 全量替换语义（双端同机问题状态同步）"
```

---

### Task 6: 会话列表"待回答"标记（新增 A）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListViewModel.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListUiState.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListStateBuilder.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/TreeNode.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/SessionRow.kt`
- Modify: `app/src/main/res/values/strings.xml` + 14 翻译文件
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListPendingQuestionTest.kt`（新建）

**Interfaces:**
- Consumes: `chatRepository.getAllQuestionsFlow(): Flow<Map<String, List<SseEvent.QuestionAsked>>>`（已有）、`SessionItem`、`SessionListDataInputs`、`buildContentState`
- Produces: `SessionItem` 新增 `hasPendingQuestion: Boolean = false`；`SessionListDataInputs` 新增 `pendingQuestionIds: Set<String> = emptySet()`；`SessionListStateBuilder.buildContentState` 接收它并传给 TreeNode；`SessionRow` 状态标签区新增"待回答"展示

**设计要点：**
- SessionListViewModel 的 `SessionDataPart` 增加第 6 源（`chatRepository.getAllQuestionsFlow()`），`dataFlow` 合并时提取 `pendingQuestionIds`（questions map 中 key 为该 session 且有非空列表 → 该 session 有待回答）
- 注意：`getAllQuestionsFlow` 返回全局 map（含其他 server）；SessionListViewModel 已按 `serverId` 过滤（`serverSessionIds`），`pendingQuestionIds` 只取当前 server 的 session id 且在 map 中的 key
- `SessionRow`：在状态标签 `when (item.status)` 前加 `if (item.hasPendingQuestion)` 显示 `stringResource(R.string.session_pending_question)`（labelSmall，color = primary）与 HelpOutline 小图标（14dp）

- [ ] **Step 1: 写失败测试**

新建 `app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListPendingQuestionTest.kt`：

```kotlin
package dev.leonardo.ocbeacon.ui.screens.sessions

import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.model.SessionStatus
import dev.leonardo.ocbeacon.domain.model.SseEvent
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.domain.repository.DraftRepository
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionListPendingQuestionTest {

    private val draftRepo = mockk<DraftRepository>(relaxed = true)

    private fun session(id: String) = Session(
        id = id,
        directory = "/proj",
        time = Session.Time(created = 1000, updated = 2000)
    )

    private fun baseData(pendingIds: Set<String>) = SessionListDataInputs(
        sessions = listOf(session("s1"), session("s2")),
        statuses = mapOf("s1" to SessionStatus.Idle, "s2" to SessionStatus.Idle),
        serverSessionMap = mapOf("server_1" to setOf("s1", "s2")),
        lastUserMessageTime = mapOf("s1" to 1L, "s2" to 2L),
        categoryAssignments = emptyMap(),
        sessionTags = emptyList(),
        favoritesOnly = false,
        lastReplyTime = emptyMap(),
        readTimes = emptyMap(),
        justRead = emptyMap(),
        allReadAt = 0L,
        pendingQuestionIds = pendingIds
    )

    private fun ui() = SessionListUiInputs(
        expandedPaths = emptySet(),
        selectedIds = emptySet(),
        baseDirectory = null,
        lastToggledDirectory = null,
        searchQuery = null,
        viewMode = SessionViewMode.RECENT,
        categoryFilterIds = emptySet()
    )

    private fun nodeFor(state: SessionListContentState, id: String) =
        state.treeNodes.filterIsInstance<dev.leonardo.ocbeacon.ui.screens.sessions.components.TreeNode.Session>()
            .first { it.session.id == id }

    @Test
    fun `session with pending question gets flag`() {
        val state = buildContentState(baseData(setOf("s1")), ui(), "server_1", draftRepo)
        assertTrue(nodeFor(state, "s1").session.hasPendingQuestion)
        assertFalse(nodeFor(state, "s2").session.hasPendingQuestion)
    }

    @Test
    fun `no pending questions leaves flags false`() {
        val state = buildContentState(baseData(emptySet()), ui(), "server_1", draftRepo)
        assertFalse(nodeFor(state, "s1").session.hasPendingQuestion)
    }

    @Test
    fun `pending ids from other server ignored`() {
        val state = buildContentState(baseData(setOf("s_other")), ui(), "server_1", draftRepo)
        assertFalse(nodeFor(state, "s1").session.hasPendingQuestion)
        assertFalse(nodeFor(state, "s2").session.hasPendingQuestion)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.ui.screens.sessions.SessionListPendingQuestionTest" --rerun`
Expected: FAIL（`SessionItem.hasPendingQuestion`/`SessionListDataInputs.pendingQuestionIds` 不存在 → 编译失败）

- [ ] **Step 3: SessionListUiState 增加字段**

```kotlin
data class SessionItem(
    val session: Session,
    val status: SessionStatus = SessionStatus.Idle,
    val hasDraft: Boolean = false,
    val tags: List<Tag> = emptyList(),
    val hasUnread: Boolean = false,
    /** 会话正等待用户回答 agent 的问题。 */
    val hasPendingQuestion: Boolean = false,
)

data class SessionListDataInputs(
    // ... 既有字段 ...
    val allReadAt: Long,
    /** 有待回答问题（agent 提问等待回答）的会话 id 集合。 */
    val pendingQuestionIds: Set<String> = emptySet(),
)
```

- [ ] **Step 4: SessionListViewModel 增加源**

`SessionDataPart` 增加字段与源：

```kotlin
private data class SessionDataPart(
    val sessions: List<Session>,
    val statuses: Map<String, SessionStatus>,
    val serverSessionMap: Map<String, Set<String>>,
    val lastUserMessageTime: Map<String, Long>,
    val lastReplyTime: Map<String, Long>,
    val questions: Map<String, List<SseEvent.QuestionAsked>>,
)

private val sessionDataFlow = combine(
    sessionRepository.getSessionsFlow(serverId),
    sessionStateService.statusFlow,
    sessionRepository.getServerSessionsFlow(),
    sessionRepository.getLastUserMessageTimeFlow(),
    sessionRepository.getLastCompletedReplyTimeFlow(),
    chatRepository.getAllQuestionsFlow(),
) { sessions, statuses, serverSessionMap, lastUserMessageTime, lastReplyTime, questions ->
    SessionDataPart(sessions, statuses, serverSessionMap, lastUserMessageTime, lastReplyTime, questions)
}
```

`dataFlow` 合并时计算 pendingQuestionIds：

```kotlin
SessionListDataInputs(
    // ... 既有字段 ...
    allReadAt = miscData.allReadAt,
    pendingQuestionIds = sessionData.questions
        .filterKeys { it in sessionData.serverSessionMap[serverId].orEmpty() }
        .filterValues { it.isNotEmpty() }
        .keys
        .toSet(),
)
```

注意：`chatRepository` 在 SessionListViewModel 是否已注入？若无，构造函数注入 `ChatRepository`（Hilt @Inject constructor 加参）。**重要**：SessionListViewModel 构造签名改动会破坏测试与 Fake——查看现有测试如何构造（SessionListViewModelPaginationTest 等），同步更新。

- [ ] **Step 5: SessionListStateBuilder 传递**

`buildContentState` 中两处 `TreeNode.Session(...)`（RECENT 分支 + buildTreeNodes 调用）传递 `hasPendingQuestion = session.id in data.pendingQuestionIds`。`buildTreeNodes` 签名需增加 `pendingQuestionIds: Set<String>` 参数（TreeNode.kt 修改，默认空集避免破坏其他调用）。

`SessionListStateBuilder.kt`：

```kotlin
// RECENT 分支
TreeNode.Session(
    id = session.id,
    session = SessionItem(
        session = session,
        status = data.statuses[session.id] ?: SessionStatus.Idle,
        hasDraft = session.id in draftRepository.getDraftSessionIds(),
        tags = resolvedTags[session.id].orEmpty(),
        hasUnread = isUnread(...),
        hasPendingQuestion = session.id in data.pendingQuestionIds
    )
)
// FOLDER 分支
buildTreeNodes(favoritesFilteredSessions, ui.expandedPaths, ui.baseDirectory, data.statuses, draftRepository.getDraftSessionIds(), resolvedTags, data.lastReplyTime, readTimes, data.allReadAt, data.pendingQuestionIds)
```

`TreeNode.kt` 的 `buildTreeNodes` 与 `buildFolderNodes`（若存在）增加 `pendingQuestionIds: Set<String> = emptySet()` 参数，两处 `SessionItem(...)` 构造补 `hasPendingQuestion = session.id in pendingQuestionIds`。

- [ ] **Step 6: SessionRow 展示**

`SessionRow.kt` 状态标签区（第二行 Row 内，`when (item.status)` 前）增加：

```kotlin
if (item.hasPendingQuestion) {
    Icon(
        Icons.Outlined.HelpOutline,
        contentDescription = null,
        modifier = Modifier.size(14.dp),
        tint = MaterialTheme.colorScheme.primary
    )
    Text(
        text = stringResource(R.string.session_pending_question),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}
```

需要 `import androidx.compose.material.icons.outlined.HelpOutline`（确认 `Icons.Outlined.HelpOutline` 存在；若 Material Icons Extended 不含，改用 `Icons.AutoMirrored.Filled.HelpOutline` 或现有 `Icons.Filled.HelpOutline`）。

- [ ] **Step 7: i18n 新增文案**

`values/strings.xml`：

```xml
<string name="session_pending_question">Pending answer</string>
```

14 语言翻译（参考 `sessions_working` 翻译风格；中文：待回答）。

- [ ] **Step 8: 运行 i18n 检查 + 编译 + 全部单测**

Run: `powershell -File scripts/i18n-check.ps1`
Expected: 无 key 缺失

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: 全部通过（新增 3 测试 + 既有无回归）

- [ ] **Step 9: 提交**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/ app/src/test/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/SessionListPendingQuestionTest.kt app/src/main/res/values/strings.xml app/src/main/res/values-*/strings.xml
git commit -m "feat: 会话列表显示待回答问题标记（Pending answer）"
```

---

### Task 7: 通知触发链路验证 + REST 兜底（新增 A）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/service/OpenCodeConnectionService.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/service/AppNotificationManager.kt`
- Test: `app/src/test/kotlin/dev/leonardo/ocbeacon/service/QuestionNotifyDiffTest.kt`（新建）

**Interfaces:**
- Consumes: `eventDispatcher.questions`（StateFlow）、`serverConfigRepository.getServer(id)`、`connectionManager.connections`（serverId → state）、`managePermissionUseCase.listPendingQuestions(serverId, directory)`、`appNotificationManager.showQuestionNotification(...)`、`shouldNotifyQuestion/markQuestionNotified`
- Produces: `AppNotificationManager` 新增 `notifyPendingQuestionsFromREST(server, questionsBySession: Map<String, List<SseEvent.QuestionAsked>>)`（对每个 session 的新增问题触发通知）；纯函数 `diffNewQuestionIds(previous: Map<String, Set<String>>, current: Map<String, List<SseEvent.QuestionAsked>>): Map<String, List<SseEvent.QuestionAsked>>`（按 sessionId 对比 id 集合，返回新增问题）

**前置验证（重要）：**
1. 安装 dev APK 到真机（或模拟器），连接真实服务器
2. 触发提问（让 agent 问单选/多选问题）
3. 观察 logcat `OpenCodeService` 是否打印 `SSE event: QuestionAsked`（`processEvent` 有 DEBUG 日志）
4. **若 SSE 推 QuestionAsked**：现有通知链路已生效，Task 7 仅做验证记录 + 关闭（说明无需兜底）
5. **若 SSE 不推**（探测预期）：实施以下 REST 兜底

**REST 兜底设计：**
- `OpenCodeConnectionService` 在连接建立后（`connect()` 成功路径）与连接恢复时，为每个 server 启动定时协程：每 30s 调 `managePermissionUseCase.listPendingQuestions(serverId, directory=null)`（REST GET /question），按返回的 `sessionID` 分组 → `appNotificationManager.notifyPendingQuestionsFromREST(server, grouped)`
- 通知去重/抑制：`notifyPendingQuestionsFromREST` 内部对每个 sessionId 复用 `shouldNotifyQuestion/markQuestionNotified`（key 已含 serverId），并检查 `sessionFocusHolder.shouldSuppressEvent`
- 子会话：沿用现有 `isChildSession` 冒泡逻辑
- 轮询协程生命周期：`serviceScope.launch`，server 断开时 cancel（或在协程内检查 `connectionManager.isConnected(serverId)` 自停）

- [ ] **Step 1: 写失败测试（纯函数）**

新建 `app/src/test/kotlin/dev/leonardo/ocbeacon/service/QuestionNotifyDiffTest.kt`：

```kotlin
package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.domain.model.SseEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class QuestionNotifyDiffTest {

    private fun q(id: String) = SseEvent.QuestionAsked(
        id = id,
        sessionId = "ses_1",
        questions = emptyList()
    )

    @Test
    fun `new questions are detected per session`() {
        val previous = mapOf("ses_1" to setOf("que_1"))
        val current = mapOf(
            "ses_1" to listOf(q("que_1"), q("que_2")),
            "ses_2" to listOf(q("que_3"))
        )
        val diff = diffNewQuestionIds(previous, current)
        assertEquals(listOf("que_2"), diff["ses_1"]?.map { it.id })
        assertEquals(listOf("que_3"), diff["ses_2"]?.map { it.id })
    }

    @Test
    fun `known questions not re-notified`() {
        val previous = mapOf("ses_1" to setOf("que_1"))
        val current = mapOf("ses_1" to listOf(q("que_1")))
        assertEquals(emptyMap<String, List<SseEvent.QuestionAsked>>(), diffNewQuestionIds(previous, current))
    }

    @Test
    fun `empty previous notifies all`() {
        val current = mapOf("ses_1" to listOf(q("que_1")))
        val diff = diffNewQuestionIds(emptyMap(), current)
        assertEquals(listOf("que_1"), diff["ses_1"]?.map { it.id })
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.service.QuestionNotifyDiffTest" --rerun`
Expected: FAIL（函数未定义）

- [ ] **Step 3: 在 AppNotificationManager.kt 定义纯函数**

```kotlin
/**
 * 对比上次已知问题 id 与当前问题列表，返回每个会话的新增问题（按 id 判断）。
 * REST 轮询兜底使用——SSE 不推 question 事件时也能发现新提问。
 */
internal fun diffNewQuestionIds(
    previous: Map<String, Set<String>>,
    current: Map<String, List<SseEvent.QuestionAsked>>
): Map<String, List<SseEvent.QuestionAsked>> {
    return current.mapNotNull { (sessionId, questions) ->
        val known = previous[sessionId].orEmpty()
        val newOnes = questions.filter { it.id !in known }
        if (newOnes.isEmpty()) null else sessionId to newOnes
    }.toMap()
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `.\gradlew :app:testDevDebugUnitTest --tests "dev.leonardo.ocbeacon.service.QuestionNotifyDiffTest" --rerun`
Expected: PASS

- [ ] **Step 5: AppNotificationManager 增加 REST 通知入口**

```kotlin
/**
 * 从 REST 轮询结果触发问题通知（SSE 兜底）。
 * 对每个会话：仅通知新增问题（diff）；去重/抑制复用既有逻辑。
 */
fun notifyPendingQuestionsFromREST(
    context: Context,
    notificationManager: NotificationManager,
    server: ServerConfig,
    questionsBySession: Map<String, List<SseEvent.QuestionAsked>>,
    previousKnown: Map<String, Set<String>>
) {
    val newQuestions = diffNewQuestionIds(previousKnown, questionsBySession)
    newQuestions.forEach { (sessionId, questions) ->
        val targetSessionId = if (isChildSession(sessionId)) {
            sessionById[sessionId]?.parentId ?: sessionId
        } else sessionId
        if (sessionFocusHolder.shouldSuppressEvent(server.id, targetSessionId)) return@forEach
        questions.forEach { question ->
            val text = question.questions.firstOrNull()?.question
                ?: question.questions.firstOrNull()?.header
                ?: ""
            showQuestionNotification(context, notificationManager, server, targetSessionId, text)
        }
    }
}
```

（依赖 `sessionById` 已由 init 订阅填充；`showQuestionNotification` 内部已有 `shouldNotifyQuestion` 去重 + `markQuestionNotified` 写入，天然防重复。）

- [ ] **Step 6: OpenCodeConnectionService 增加 REST 轮询**

`OpenCodeConnectionService` 注入 `ManagePermissionUseCase`（确认 DI 存在；若 UseCase 不在 service 层可用，改用 `chatRepository` 的对应方法或直接注入 `ManagePermissionUseCase`）。`connect()` 成功路径与 `onConnectionEstablished`（SseConnectionManager 回调处）启动：

```kotlin
private fun startQuestionPolling(server: ServerConfig) {
    serviceScope.launch {
        var previousKnown = emptyMap<String, Set<String>>()
        while (isActive) {
            if (!connectionManager.isConnected(server.id)) break
            try {
                val pending = managePermissionUseCase.listPendingQuestions(
                    server.id, directory = null
                ).getOrNull().orEmpty()
                val grouped = pending.groupBy { it.sessionID }
                appNotificationManager.notifyPendingQuestionsFromREST(
                    this@OpenCodeConnectionService, systemNotificationManager, server, grouped, previousKnown
                )
                previousKnown = grouped.mapValues { (_, qs) -> qs.map { it.id }.toSet() }
            } catch (e: Exception) {
                AppLogger.w(TAG, "[${server.displayName}] question polling failed: ${e.message}")
            }
            delay(QUESTION_POLL_INTERVAL_MS)
        }
    }
}
```

常量 `QUESTION_POLL_INTERVAL_MS = 30_000L`。需确认 `ManagePermissionUseCase.listPendingQuestions` 的确切签名（`chatRepository.listPendingQuestions` 或 use case 内部封装）——以实际代码为准，返回 `Result<List<QuestionRequest>>`（DTO 含 `sessionID` 字段；若 domain 模型无 sessionID 则在 DTO 层分组或改调 `MessageDataDelegate` 同款 use case）。**执行时先读 `ManagePermissionUseCase` 与 `MessageDataDelegate.loadPendingQuestions` 的调用签名对齐。**

- [ ] **Step 7: 编译 + 全部单测**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: BUILD SUCCESSFUL

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`
Expected: 全部通过（无回归）

- [ ] **Step 8: 真机验证**

安装 dev APK → 连接真实服务器 → 触发提问：
- 观察通知栏出现"Question · <会话标题>"通知（HIGH 优先级 + 振动）
- 观察 logcat `OpenCodeService` 是否有 `SSE event: QuestionAsked`（若无则确认走 REST 兜底）
- 若 SSE 实际推送，说明 Task 7 兜底为冗余保护（无害保留）

- [ ] **Step 9: 提交**

```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/service/OpenCodeConnectionService.kt app/src/main/kotlin/dev/leonardo/ocbeacon/service/AppNotificationManager.kt app/src/test/kotlin/dev/leonardo/ocbeacon/service/QuestionNotifyDiffTest.kt
git commit -m "feat: 提问通知 REST 轮询兜底（SSE 不推 question 事件时保证通知可达）"
```

---

### Task 8: 全量验证 + 收尾

**Files:**
- Modify: `backlog.md`（#26/#27/#28 状态流转 + 新增项标记）

**设计要点：**
- 全量单测、编译、i18n 检查
- 真机验证清单（见 spec「验证计划」8 项）
- backlog 状态更新：#26/#27/#28 勾选（验证通过后）；新增 A/B 项补登记或并入完成说明

- [ ] **Step 1: 全量构建与测试**

Run: `.\gradlew :app:compileDevDebugKotlin` → BUILD SUCCESSFUL
Run: `.\gradlew :app:testDevDebugUnitTest --rerun` → 全部通过
Run: `powershell -File scripts/i18n-check.ps1` → 通过
Run: `.\gradlew :app:assembleDevDebug` → BUILD SUCCESSFUL，APK 产出

- [ ] **Step 2: 安装 + 真机验证清单**

安装到真机 3B165D00SX600000（dev 包 dev.leonardo.ocbeacon.dev），验证：
1. 活动提问：多选显示复选框、单选显示单选框（含服务器省略 multiple 场景）
2. 历史消息：多选答案显示复选框（CollapsibleQuestionPart + QuestionExpandedOptions 两入口）
3. 三按钮：忽略/下一个（末页置灰）/提交；Tab 自由切换；未答完提交弹窗（"第 X 个问题没有回答"）→ 继续提交
4. 单选点选不立即提交，可取消选中
5. 样式：选项行高度统一、图标 24dp、间距舒展
6. 会话列表：有提问的会话显示"待回答"标记
7. 通知：提问时通知弹出（SSE 或 REST 兜底链路）
8. 双端同机：A 回答后 B 问题消失（验证 Task 5）

- [ ] **Step 3: 更新 backlog**

`backlog.md`：#26/#27/#28 验证通过后勾选；记录新增 A（会话列表待回答 + 通知兜底）与新增 B（双端同步）的完成说明（参照既有条目格式）。

- [ ] **Step 4: 提交**

```bash
git add backlog.md
git commit -m "docs: 提问组件改进批次完成（#26/#27/#28 + 新增项）"
```

---

## Self-Review

**1. Spec 覆盖检查：**
- 节 1（#26 数据链路）→ Task 1 + Task 2 ✅
- 节 2（#27 三按钮）→ Task 3 ✅
- 节 3（#28 样式）→ Task 4 ✅
- 节 4（会话列表待回答 + 通知兜底）→ Task 6 + Task 7 ✅
- 节 5（双端同步）→ Task 5 ✅
- 验证计划 → Task 8 ✅

**2. 占位符扫描：**
- Task 7 Step 6 含"以实际代码为准/执行时先读"——这是签名对齐说明（ManagePermissionUseCase 具体签名需读代码），已给出明确方向与替代方案，非空白占位 ✅

**3. 类型一致性：**
- `resolvePendingQuestionReplacement` 定义于 Task 5 并被 Task 5 使用，无跨任务依赖 ✅
- `diffNewQuestionIds` 定义于 Task 7 并被 Task 7 使用 ✅
- `ParsedQuestion.isMultiple` 定义于 Task 1，被 Task 2 消费 ✅
- `SessionItem.hasPendingQuestion` / `SessionListDataInputs.pendingQuestionIds` 定义于 Task 6 并被 Task 6 使用 ✅
- `unansweredQuestionIndexes` 定义于 Task 3 并被 Task 3 使用 ✅
