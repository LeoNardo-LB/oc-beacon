package dev.leonardo.ocbeacon.ui.screens.chat.tools.cards

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.ui.screens.chat.tools.SkillCardState
import dev.leonardo.ocbeacon.ui.screens.chat.tools.skillRowModel
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import androidx.compose.ui.unit.dp

/**
 * #311 Task5 skill 工具卡（契约 ③；web mod32 SkillRow 折叠卡语义）：
 * 名称 + 状态摘要 + 指令折叠卡——展开态默认收起（#227 屏幕级展开记忆，
 * 同 ShellCard ?: false 初值），展开后为「说明」头 + 指令全文滚动区
 * （等宽字体、260dp 高度上限，web instructionsCard 同构）。
 *
 * SSE 铁律（同台账）：仅已结算 part 渲染——结果未到（RUNNING 态）不挂卡，
 * 避免流式进行中卡片闪现（question 分支「活跃不渲染」同哲学）。
 * [ToolCardScaffold] 复用（标题行/展开动画/复制按钮）。
 */
@Composable
internal fun SkillToolCard(
    part: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    val model = remember(part.id, part.state) { skillRowModel(part) }
    if (model.state == SkillCardState.RUNNING) return
    val errorColor = MaterialTheme.colorScheme.error
    SkillToolCardContent(
        model = model,
        isError = model.state == SkillCardState.ERROR,
        errorColor = errorColor,
        isExpanded = isExpanded,
        onToggleExpand = onToggleExpand,
    )
}

@Composable
private fun SkillToolCardContent(
    model: dev.leonardo.ocbeacon.ui.screens.chat.tools.SkillRowModel,
    isError: Boolean,
    errorColor: androidx.compose.ui.graphics.Color,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
) {
    ToolCardScaffold(
        icon = Icons.AutoMirrored.Filled.MenuBook,
        iconTint = when (model.state) {
            SkillCardState.ERROR -> errorColor
            SkillCardState.STOPPED -> MaterialTheme.colorScheme.tertiary
            else -> MaterialTheme.colorScheme.primary
        },
        title = stringResource(R.string.chat_skill_title),
        copyText = model.output ?: "",
        isExpanded = isExpanded,
        isRunning = false,
        hasContent = model.output != null,
        isAmoled = isAmoledTheme(),
        onToggleExpand = onToggleExpand,
        // 标题行 = 「Skill · 名称/错误摘要」（mod32: title + separator + summary，
        // ERROR 态摘要位换错误首行并着错误色）
        titleContent = {
            Text(
                text = stringResource(R.string.chat_skill_title),
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
            )
            Text(
                text = "·",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT),
                modifier = Modifier.padding(horizontal = SpacingTokens.XS.dp),
            )
            Text(
                text = model.errorSummary ?: model.name,
                style = MaterialTheme.typography.labelMedium,
                color = if (isError) errorColor
                else MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        },
    ) {
        // 指令折叠卡：说明头 + 全文滚动区（默认收起由 isExpanded 控制）
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            shape = ShapeTokens.smallMedium,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 260.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(R.string.chat_skill_instructions),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SpacingTokens.MD.dp, vertical = SpacingTokens.XS.dp),
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT))
                Text(
                    text = model.output ?: "",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = if (isError) errorColor
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MUTED),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SpacingTokens.MD.dp, vertical = SpacingTokens.SM.dp),
                )
            }
        }
    }
}
