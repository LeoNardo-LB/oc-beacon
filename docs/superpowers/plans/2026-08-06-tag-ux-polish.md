# 标签系统 UX 优化实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 完成标签系统 5 项 UX 优化：多语言同步、分配弹窗 FilterChip 布局改造、文案改名、详情弹框 tag 展示行、设置页统一列表组件。

**Architecture:** 纯 UI 层改造（无数据层变更）。TagPickerDialog 用 Material3 FilterChip（官方 API 全覆盖选中样式）+ FlowRow 流式布局；SessionRow 抽公共 TagChipsRow；设置页抽 SettingsSectionHeader/SettingsListRow 统一组件；strings 改 value 复用 key + lokit 同步。

**Tech Stack:** Jetpack Compose (Material3 FilterChip/FlowRow) + lokit + JUnit4（回归）

## Global Constraints

- 弹窗标题与长按菜单按钮文案统一为"新增 Tag"（复用现有 key 改 value：`assign_category`、`category`——不新增 key）
- FilterChip 选中样式全用官方参数（leadingIcon 勾选、selectedContainerColor 加深、selectedBorderColor 边框）——不自定义组件
- 选中文字色按 tag 颜色 luminance 取反（`> 0.5f` 用黑，否则白）保证可读
- 详情弹框 tag 行纯展示（不响应点击）；无 tag 不显示该行
- 设置页统一组件：区块标题 `padding(16,12)` + `titleSmall`；行 `padding(16,12)` + leading 间距 16dp + `bodyLarge` 标题 + `bodySmall` 副标题
- 会话设置页标签管理保持列表形态（不改为 chips）
- 新日志用 AppLogger；不提交无关文件（工作树 6 个 chat 未提交文件勿碰）；不 push
- 编译：`.\gradlew :app:compileDevDebugKotlin`（工作目录 D:\Develop\code\app\oc-beacon）；单测：`.\gradlew :app:testDevDebugUnitTest --rerun`；构建：`.\gradlew :app:assembleBetaRelease`（300s）
- lokit 同步：项目根运行 `lokit`（读取 lokit.yaml，source=en，14 语言：ru,de,es,fr,it,id,pt-BR,ja,ko,zh-CN,uk,tr,ar,pl）

---

### Task 1: 多语言同步（文案 + lokit）

**Files:**
- Modify: `app/src/main/res/values/strings.xml`
- （lokit 自动生成）`app/src/main/res/values-*/strings.xml`（14 个语言目录）

**Interfaces:**
- Produces: `R.string.assign_category` = "新增 Tag"（原"分配 Tag"）、`R.string.category` = "新增 Tag"（原"分类"）、`R.string.delete_category` 删除

- [ ] **Step 1: 确认当前 strings 现状**

`app/src/main/res/values/strings.xml` 中：
- `assign_category` 当前值（预期"分配 Tag"）——被 `SessionRow.kt`（长按菜单按钮）引用
- `category` 当前值（预期"分类"）——被 `TagPickerDialog.kt`（弹窗标题）引用
- `delete_category`——孤儿（被删的 SessionCategoryPickerDialog 遗留，无代码引用——grep 确认 0 引用）

- [ ] **Step 2: 修改 values/strings.xml**

```xml
<!-- assign_category: "分配 Tag" → "新增 Tag" -->
<string name="assign_category">新增 Tag</string>
<!-- category: "分类" → "新增 Tag" -->
<string name="category">新增 Tag</string>
<!-- 删除 delete_category（孤儿） -->
```

- [ ] **Step 3: 跑 lokit 同步**

Run（项目根）: `lokit`
Expected: 14 个 `values-*` 语言目录的 strings.xml 更新（assign_category/category 新值 + delete_category 移除）

- [ ] **Step 4: 验证无引用破坏**

Run: `.\gradlew :app:compileDevDebugKotlin`
Expected: PASS（key 名未变，仅 value 变；delete_category 已确认无引用）

- [ ] **Step 5: 提交**

```bash
git add app/src/main/res
git commit -m "i18n(tags): 文案改名（新增 Tag）+ 清理孤儿 delete_category + lokit 同步 14 语言"
```

---

### Task 2: TagPickerDialog 改造（FilterChip + 底部新增按钮 + 内联表单 + 空状态）

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/TagPickerDialog.kt`

**Interfaces:**
- Consumes: Task 1 的 `R.string.category`（value="新增 Tag"）、现有签名 `onCreateTag: (name, color, icon) -> String`
- Produces: 无（签名不变）

- [ ] **Step 1: 结构调整（标签列表区）**

当前结构（约 88-128 行）：`Column(padding 20 + verticalScroll)` → 标题 → `tags.forEach { 复选框行 }` → Divider → 新建区 → 底部按钮。

改为：

```kotlin
Column(
    modifier = Modifier
        .padding(20.dp)
        .heightIn(max = 560.dp),   // 限制最大高度，列表区内部滚动
) {
    Text(text = stringResource(R.string.category), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))

    // 标签列表区：weight 填充中间空白 + minHeight
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .heightIn(min = 160.dp)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,   // 空状态垂直居中
    ) {
        if (tags.isEmpty()) {
            // 空状态占位
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    imageVector = Icons.Outlined.Label,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.no_tags_placeholder),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.FAINT),
                )
            }
        } else {
            // FilterChip 流式布局
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tags.forEach { tag ->
                    TagFilterChip(
                        tag = tag,
                        selected = tag.id in selected,
                        onToggle = {
                            selected = if (tag.id in selected) selected - tag.id else selected + tag.id
                        },
                    )
                }
            }
        }
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
    // 底部"新增 Tag"按钮 + 内联表单 + 主操作栏（见 Step 2/3）
}
```

- [ ] **Step 2: FilterChip 组件（方案 A 颜色）**

函数级 `@OptIn(ExperimentalLayoutApi::class)`（FlowRow 需要，标注在 `TagPickerDialog` 函数上——与现有 `@OptIn(ExperimentalMaterial3Api::class)` 合并）：

```kotlin
@Composable
private fun TagFilterChip(
    tag: Tag,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val tagColor = SessionCategoryStyle.color(tag.color)
    val onColor = if (tagColor.luminance() > 0.5f) Color.Black else Color.White
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = {
            Text(
                text = tag.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) onColor else tagColor,
            )
        },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Filled.Done,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                    tint = onColor,
                )
            }
        } else {
            {
                Icon(
                    imageVector = SessionCategoryStyle.icon(tag.icon),
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                    tint = tagColor,
                )
            }
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = tagColor.copy(alpha = AlphaTokens.FAINT),
            selectedContainerColor = tagColor.copy(alpha = AlphaTokens.HIGH),
            labelColor = tagColor,
            selectedLabelColor = onColor,
            selectedLeadingIconColor = onColor,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = tagColor.copy(alpha = AlphaTokens.FAINT),
            selectedBorderColor = tagColor,
            selectedBorderWidth = 1.dp,
        ),
    )
}
```

需要的 import：`androidx.compose.foundation.layout.FlowRow`（+ `@OptIn(ExperimentalLayoutApi::class)`）、`androidx.compose.material3.FilterChip`、`FilterChipDefaults`、`androidx.compose.ui.graphics.luminance`、`androidx.compose.material.icons.filled.Done`、`androidx.compose.material.icons.outlined.Label`、`androidx.compose.foundation.layout.heightIn`。

- [ ] **Step 3: 底部"新增 Tag"按钮 + 内联展开表单**

替换现有"新建标签区"（当前约 130-181 行：Divider 后 Text(new_category) + OutlinedTextField + 颜色/图标选择）与底部按钮行：

```kotlin
// 底部：新增 Tag 按钮（点击内联展开表单）
var showCreateForm by remember { mutableStateOf(false) }
Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
    TextButton(onClick = { showCreateForm = !showCreateForm }) {
        Icon(Icons.Default.Add, null, Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(stringResource(R.string.new_tag))
    }
}
AnimatedVisibility(visible = showCreateForm, enter = expandVertically(), exit = shrinkVertically()) {
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = newCategoryName,
            onValueChange = { newCategoryName = it },
            label = { Text(stringResource(R.string.category_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        // 颜色选择（现有 ColorDot 循环——从当前代码保留）
        // 图标选择（现有 IconOption 循环——从当前代码保留）
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { showCreateForm = false }) { Text(stringResource(R.string.close)) }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (newCategoryName.isNotBlank()) {
                        val newId = onCreateTag(newCategoryName.trim(), selectedColor, selectedIcon)
                        selected = selected + newId       // 自动勾选（现有机制）
                        newCategoryName = ""
                        showCreateForm = false             // 确认后收起
                    }
                },
                enabled = newCategoryName.isNotBlank(),
            ) { Text(stringResource(R.string.add)) }
        }
    }
}
Spacer(Modifier.height(12.dp))
// 主操作栏：取消 / 确定
Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
    TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
    Spacer(Modifier.width(8.dp))
    Button(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.ok)) }
}
```

需要的 import：`androidx.compose.animation.AnimatedVisibility`、`expandVertically`、`shrinkVertically`、`androidx.compose.material.icons.filled.Add`、`androidx.compose.foundation.layout.heightIn`（已有）、`Icons.Outlined.Label`（空状态）。

- [ ] **Step 4: 新增 string `no_tags_placeholder`**

`values/strings.xml` 添加：

```xml
<string name="no_tags_placeholder">暂无标签，点击下方按钮创建</string>
```

（Task 1 已跑过 lokit——本任务新增 1 条 strings 后需再跑一次 `lokit` 同步；或本任务结束后 Task 5 统一跑——**选择**：本任务 Step 5 跑 lokit，保持各任务独立可交付）

- [ ] **Step 5: 编译 + lokit + 提交**

Run: `.\gradlew :app:compileDevDebugKotlin` → PASS
Run: `lokit`（项目根）
```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/TagPickerDialog.kt app/src/main/res
git commit -m "feat(tags): 分配弹窗 FilterChip 多选 + 底部新增按钮内联表单 + 空状态占位"
```

---

### Task 3: 详情弹框 tag 展示行 + TagChipsRow 抽取

**Files:**
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/SessionRow.kt`

**Interfaces:**
- Consumes: Task 1 的文案（assign_category value 已改，无需代码改动）
- Produces: `private fun TagChipsRow(tags: List<Tag>, modifier: Modifier = Modifier)`（SessionRow.kt 内私有）

- [ ] **Step 1: 抽公共 TagChipsRow**

SessionRow.kt 现有会话行 chips 渲染（约 201-234 行：`item.tags.forEach { tag -> ... }`）抽取为：

```kotlin
@Composable
private fun TagChipsRow(tags: List<Tag>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .basicMarquee()
            .clip(RoundedCornerShape(4.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tags.forEach { tag ->
            Row(
                modifier = Modifier
                    .background(SessionCategoryStyle.color(tag.color).copy(alpha = AlphaTokens.SELECTED))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Icon(
                    imageVector = SessionCategoryStyle.icon(tag.icon),
                    contentDescription = null,
                    modifier = Modifier.size(10.dp),
                    tint = SessionCategoryStyle.color(tag.color),
                )
                Text(
                    text = tag.name,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = SessionCategoryStyle.color(tag.color),
                    maxLines = 1,
                )
            }
        }
    }
}
```

会话行原位置改为调用 `TagChipsRow(tags = item.tags)`（保持外层 Box weight(1f) + CenterEnd 结构不变）。

- [ ] **Step 2: SessionDetailsDialog 加 tag 行**

`SessionDetailsDialog`（约 289 行起）的 `SelectionContainer { Column { DetailRow... } }` 区域（约 310-337 行）——在 Diff DetailRow 之后（约 336 行）加：

```kotlin
if (item.tags.isNotEmpty()) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.tag_label),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TagChipsRow(tags = item.tags, modifier = Modifier.weight(1f))
    }
}
```

- [ ] **Step 3: 新增 string `tag_label`**

`values/strings.xml` 添加：

```xml
<string name="tag_label">标签</string>
```

- [ ] **Step 4: 编译 + lokit + 提交**

Run: `.\gradlew :app:compileDevDebugKotlin` → PASS
Run: `lokit`（项目根）
```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/SessionRow.kt app/src/main/res
git commit -m "feat(tags): 长按详情弹框展示已有 tag（纯展示）+ 抽公共 TagChipsRow"
```

---

### Task 4: 设置页统一组件（SettingsSectionHeader + SettingsListRow）

**Files:**
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/SettingsSectionHeader.kt`
- Create: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/SettingsListRow.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/ServerSettingsContent.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/TagManagementSection.kt`
- Modify: `app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/McpServerRow.kt`

**Interfaces:**
- Produces:
  - `@Composable fun SettingsSectionHeader(title: String, expanded: Boolean, onClick: () -> Unit, trailing: (@Composable () -> Unit)? = null, modifier: Modifier = Modifier)`
  - `@Composable fun SettingsListRow(leading: @Composable () -> Unit, title: String, subtitle: String? = null, trailing: @Composable () -> Unit = {}, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier)`

- [ ] **Step 1: 创建 SettingsSectionHeader**

```kotlin
package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 设置页可展开区块标题（统一样式：padding 16/12 + titleSmall + 箭头）。 */
@Composable
fun SettingsSectionHeader(
    title: String,
    expanded: Boolean,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.weight(1f))
        trailing?.invoke()
        if (trailing != null) Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = if (expanded) Icons.Default.KeyboardArrowDown
            else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
```

- [ ] **Step 2: 创建 SettingsListRow**

```kotlin
package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** 设置页列表行（统一样式：padding 16/12 + leading 16dp + bodyLarge 标题 + bodySmall 副标题）。 */
@Composable
fun SettingsListRow(
    leading: @Composable () -> Unit,
    title: String,
    subtitle: String? = null,
    subtitleColor: Color? = null,   // 默认 onSurfaceVariant；McpServerRow 传状态色
    trailing: @Composable () -> Unit = {},
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val clickable = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickable)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}
```

- [ ] **Step 3: ServerSettingsContent 用 SettingsSectionHeader**

MCP 区块标题（当前 61-84 行 Row）替换为：

```kotlin
item {
    SettingsSectionHeader(
        title = stringResource(R.string.mcp_servers_title),
        expanded = mcpExpanded,
        onClick = { mcpExpanded = !mcpExpanded },
    )
}
```

（删除原 Row/clickable/箭头代码与不再使用的 import——KeyboardArrowDown/KeyboardArrowRight 若仅此使用则移除）

- [ ] **Step 4: McpServerRow 内部用 SettingsListRow**

`McpServerRow.kt` 内部行结构替换为：

```kotlin
SettingsListRow(
    leading = { Icon(Icons.Default.Build, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    title = server.name,
    subtitle = buildString { append(server.type).append(" · ").append(statusDot(server.status)).append(" ").append(server.status) },
    trailing = {
        Switch(
            checked = server.status == "connected",
            onCheckedChange = { onToggle() },
            enabled = !isLoading && server.status != "needs_auth" && server.status != "needs_client_registration",
        )
    },
)
```

（删除 Row/Spacer/Column 手动布局；subtitle 颜色由 SettingsListRow 统一——原 statusColor 着色保留在 subtitle 文本内不行——**注意**：原实现 subtitle 用 statusColor（状态色）——SettingsListRow 的 subtitle 固定 onSurfaceVariant——**取舍**：状态色丢失或 SettingsListRow 增加 subtitleColor 参数——**决定**：SettingsListRow 增加 `subtitleColor: Color? = null` 参数（默认 onSurfaceVariant），McpServerRow 传 statusColor(server.status)——保留状态语义）

```kotlin
// SettingsListRow 签名追加：
subtitleColor: Color? = null,
// 实现：
Text(..., color = subtitleColor ?: MaterialTheme.colorScheme.onSurfaceVariant)
```

- [ ] **Step 5: TagManagementSection 用统一组件**

- 区块标题 Row（92-113 行）→ `SettingsSectionHeader(title = stringResource(R.string.tag_management_title), expanded, onClick, trailing = { Text("(${tags.size})", labelSmall) })`
- 标签行（140-190 行）→ `SettingsListRow(leading = { Icon(tag icon) }, title = tag.name, subtitle = "(${sessionCount})", trailing = { 编辑/删除 IconButton 们 }, onClick = { 展开 })`
- 关联会话行（约 197-213 行）→ `SettingsListRow(leading = { Icon(Icons.Outlined.ChatBubbleOutline, null, size 16) }, title = session?.title ?: sessionId.take(12), trailing = { TextButton("解除") { onRemoveAssignment(...) } }, modifier = Modifier.padding(start = 32.dp))`（保持二级缩进）
- 删除原手动 Row 布局与不再使用的 import（HorizontalDivider 等按需保留）

- [ ] **Step 6: 编译 + 提交**

Run: `.\gradlew :app:compileDevDebugKotlin` → PASS
Run: `.\gradlew :app:testDevDebugUnitTest --rerun` → 全绿
```bash
git add app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/SettingsSectionHeader.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/SettingsListRow.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/ServerSettingsContent.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/TagManagementSection.kt app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/sessions/components/McpServerRow.kt
git commit -m "feat(tags): 设置页统一列表组件（SettingsSectionHeader + SettingsListRow）——MCP 与标签管理收敛"
```

---

### Task 5: 构建 + 回归 + 真机验证

**Files:**
- 无代码改动

- [ ] **Step 1: 全量单测**

Run: `.\gradlew :app:testDevDebugUnitTest --rerun`（180s）
Expected: BUILD SUCCESSFUL（全部通过）

- [ ] **Step 2: 完整构建**

Run: `.\gradlew :app:assembleBetaRelease`（300s）
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 安装 + 真机验证清单**

```bash
adb -s <device> install -r app/build/outputs/apk/beta/release/app-beta-release.apk
adb -s <device> logcat -c
```

验证清单：
1. 长按会话 → 详情弹框：有 tag 会话显示"标签: chips"行（纯展示）；无 tag 会话不显示该行
2. 详情弹框按钮显示"新增 Tag"（原"分配 Tag"）
3. 点"新增 Tag"→ 弹窗标题"新增 Tag"；标签为 FilterChip 流式布局：点击选中（勾选图标 + 颜色加深 + 边框实体化）、再点取消
4. 无标签时弹窗显示空状态占位（图标 + "暂无标签"文案）
5. 底部"新增 Tag"按钮 → 内联展开表单 → 添加后自动勾选并收起
6. 确定保存多选；取消不保存
7. 设置页：MCP 与标签管理区块标题/行样式统一；标签展开关联会话 + 解除正常
8. 切换语言（系统语言）：新文案（新增 Tag/标签/暂无标签）正确显示

- [ ] **Step 4: 提交（如有遗漏）**

无代码改动时跳过；如验证发现问题修复后单独提交。
