package dev.leonardo.ocbeacon.ui.screens.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.CodeTypography
import dev.leonardo.ocbeacon.ui.theme.DiffAdded
import dev.leonardo.ocbeacon.ui.theme.DiffRemoved
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 渲染 unified diff [FileViewerUiState.diff] 补丁，带可选的 hunk 导航。
 *
 * 过滤文件级元数据行（`diff --git` / `index` / `---` / `+++` / mode / Binary files），
 * 只展示 hunk 头与内容行 —— 避免把 git 命令输出直接暴露给用户。
 *
 * D4-005：直接滚动到 [DiffHunk.patchStartLineIndex]，而非按内容反查行号 ——
 * 后者既慢又对重复的 hunk 头部很脆弱。
 *
 * D3-005：行颜色派生自 [DiffAdded]/[DiffRemoved] + [AlphaTokens.DIFF_BG]；
 * 不存在 `DiffAddedBg`/`DiffAddedFg` 这类 token（主题中没有定义）。
 */
@Composable
fun DiffView(
    uiState: FileViewerUiState,
    wordWrap: Boolean = false,
    onNextHunk: () -> Unit,
    onPrevHunk: () -> Unit
) {
    val patch = uiState.diff?.patch ?: return
    val (lines, indexMap) = remember(patch) { filterPatchLines(patch) }
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    LaunchedEffect(uiState.currentHunkIndex, uiState.hunks) {
        val target = uiState.hunks.getOrNull(uiState.currentHunkIndex) ?: return@LaunchedEffect
        // 元数据行（含 @@ 头）被过滤后行号会偏移：从 hunk 头起向后找第一条可见行
        var visibleIndex = -1
        for (i in target.patchStartLineIndex until indexMap.size) {
            if (indexMap[i] >= 0) {
                visibleIndex = indexMap[i]
                break
            }
        }
        if (visibleIndex >= 0) listState.animateScrollToItem(visibleIndex)
    }

    Column(Modifier.fillMaxSize()) {
        if (uiState.hunks.isNotEmpty()) {
            DiffHunkNavigator(
                current = uiState.currentHunkIndex,
                total = uiState.hunks.size,
                onPrevHunk = onPrevHunk,
                onNextHunk = onNextHunk
            )
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize()
        ) {
            // 不设 key：diff 内容包含大量重复行（空行、注释），hashCode/内容 key 会冲突
            itemsIndexed(lines) { _, line -> DiffLine(line, wordWrap) }
        }
    }
}

/**
 * 过滤 unified diff 的文件级元数据行，返回可见行 + 原始行号 → 可见行号映射。
 * 过滤内容：`diff --git` / `index` / `---` / `+++` / mode / Binary files / `@@` hunk 头。
 */
private fun filterPatchLines(patch: String): Pair<List<String>, IntArray> {
    val original = patch.lines()
    val visible = mutableListOf<String>()
    val map = IntArray(original.size) { -1 }
    original.forEachIndexed { idx, line ->
        if (!isPatchMetadataLine(line)) {
            map[idx] = visible.size
            visible.add(line)
        }
    }
    return visible to map
}

/**
 * 判断是否为 unified diff 元数据行（内容行前总有 + / - / 空格前缀，
 * 因此 `--- `/`+++ ` 前缀只会出现在文件头，不会误伤内容）。
 */
private fun isPatchMetadataLine(line: String): Boolean {
    if (line.startsWith("@@")) return true
    if (line.startsWith("diff --git ")) return true
    if (line.startsWith("index ") && line.matches(Regex("^index [0-9a-f]+\\.\\.[0-9a-f]+( \\d+)?$"))) return true
    if (line.startsWith("--- ") || line.startsWith("+++ ")) return true
    if (line.startsWith("new file mode ") || line.startsWith("deleted file mode ")) return true
    if (line.startsWith("old mode ") || line.startsWith("new mode ")) return true
    if (line.startsWith("similarity index ")) return true
    if (line.startsWith("rename from ") || line.startsWith("rename to ")) return true
    if (line.startsWith("Binary files ")) return true
    return false
}

@Composable
private fun DiffLine(line: String, wordWrap: Boolean) {
    val colorScheme = MaterialTheme.colorScheme
    val (background, foreground) = when {
        line.startsWith("+") -> DiffAdded.copy(alpha = AlphaTokens.DIFF_BG) to DiffAdded
        line.startsWith("-") -> DiffRemoved.copy(alpha = AlphaTokens.DIFF_BG) to DiffRemoved
        else -> Color.Transparent to colorScheme.onSurface
    }
    Text(
        text = line,
        style = CodeTypography,
        color = foreground,
        softWrap = wordWrap,
        modifier = Modifier
            .fillMaxWidth()
            // 不换行时行内水平滚动（长行超出视口可横向滑动）
            .then(if (!wordWrap) Modifier.horizontalScroll(rememberScrollState()) else Modifier)
            .background(background)
            .padding(horizontal = SpacingTokens.SM.dp, vertical = 1.dp)
    )
}

@Composable
private fun DiffHunkNavigator(
    current: Int,
    total: Int,
    onPrevHunk: () -> Unit,
    onNextHunk: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(SpacingTokens.SM.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onPrevHunk,
            enabled = current > 0
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowUp,
                contentDescription = stringResource(R.string.a11y_icon_hunk_previous)
            )
        }
        IconButton(
            onClick = onNextHunk,
            enabled = current < total - 1
        ) {
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.a11y_icon_hunk_next)
            )
        }
        Spacer(Modifier.width(SpacingTokens.SM.dp))
        Text(
            text = "[${current + 1}/$total]",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
