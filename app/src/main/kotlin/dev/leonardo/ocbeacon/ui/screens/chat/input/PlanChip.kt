package dev.leonardo.ocbeacon.ui.screens.chat.input

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.DshPlanProjection
import dev.leonardo.ocbeacon.domain.model.ServerType
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * #310③ PlanChip 显隐纯逻辑（仿 SubagentComposerGate 风格，单测 PlanChipGateTest）：
 * - DSH only（serverType 门——OpenCode 无 plan 投影域）；
 * - 无投影（首帧前/清空）→ 不出；
 * - 有效目标态（[DshPlanProjection.effective]，pending ? !active : active——
 *   官方 dsh-client-ui-plan PlanChip 同判据）→ 出 chip：切换中=进行中语义，
 *   稳态开=实心；
 * - 退出中（active+pending）与稳态关 → 不出。
 */
internal object PlanChipGate {

    fun chipVisible(serverType: ServerType, plan: DshPlanProjection?): Boolean =
        serverType == ServerType.Dsh && plan != null && plan.effective
}

/**
 * Plan 模式状态 chip（#313 原则：composer 选择器行自有形态，与权限药丸同族）。
 *
 * - 稳态开（active && !pending）：实心 chip（secondaryContainer 底 + 关闭图标），
 *   点击 → 发 /plan off（既有 commands/execute 斜杠命令链）；
 * - 切换中（pending && !active）：同底 + 进行中进度点语义，点击同样 off
 *   （取消尚未生效的开启选择）。
 *
 * 显隐由 [PlanChipGate] 在调用方判定，本组件不判服务器类型。
 */
@Composable
internal fun PlanChip(
    pending: Boolean,
    onExit: () -> Unit,
) {
    val container = MaterialTheme.colorScheme.secondaryContainer
    val content = MaterialTheme.colorScheme.onSecondaryContainer
    val a11y = stringResource(
        if (pending) R.string.plan_chip_pending_a11y else R.string.plan_chip_active_a11y,
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.XS.dp),
        modifier = Modifier
            .clip(ShapeTokens.smallMedium)
            .background(container)
            .clickable(onClick = onExit)
            .padding(horizontal = SpacingTokens.SM.dp, vertical = SpacingTokens.XS.dp)
            .semantics { contentDescription = a11y },
    ) {
        if (pending) {
            // 进行中语义：12dp 进度点（M3 原生指示器，无额外依赖）
            CircularProgressIndicator(
                strokeWidth = 1.5.dp,
                modifier = Modifier.size(12.dp),
                color = content,
            )
        }
        Text(
            text = stringResource(R.string.plan_chip_label),
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
        Icon(
            Icons.Filled.Close,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = content,
        )
    }
}