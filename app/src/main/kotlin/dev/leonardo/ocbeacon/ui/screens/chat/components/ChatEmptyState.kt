package dev.leonardo.ocbeacon.ui.screens.chat.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.AgentPreset
import dev.leonardo.ocbeacon.ui.screens.chat.ChatViewModel
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 会话无消息时显示的空状态。
 *
 * UI-A（DSH Agent 预设卡）：DSH 服务器 + blank 会话时，在提示文案下方渲染
 * agentPreset.list roster 的 4 张预设卡（name+description 原文）。点卡立即
 * selectAgentPreset（会话此时必 blank），成功后该卡高亮（当前 agentPreset）；
 * roster 加载失败 → 空列表 → 卡区整体隐藏（软降级）；select locked → snackbar。
 *
 * 经 hiltViewModel 取同一 ChatViewModel（与 ChatScreen 共享 NavBackStackEntry
 * owner），避免改动 ChatScreen.kt 调用点。
 */
@Composable
fun ChatEmptyState(
    modifier: Modifier = Modifier
) {
    val viewModel = hiltViewModel<ChatViewModel>()
    val sessionMeta by viewModel.sessionMetaState.collectAsStateWithLifecycle()
    val agentPresets by viewModel.agentPresets.collectAsStateWithLifecycle()
    val capabilities by viewModel.serverCapabilities.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val lockedMsg = stringResource(R.string.agent_preset_locked)
    val failedMsg = stringResource(R.string.agent_preset_switch_failed)
    LaunchedEffect(Unit) {
        viewModel.agentPresetError.collect { resId ->
            snackbarHostState.showSnackbar(
                if (resId == R.string.agent_preset_locked) lockedMsg else failedMsg
            )
        }
    }

    val showCards = capabilities.agentPresetSupported &&
        sessionMeta.sessionIsBlank &&
        agentPresets.isNotEmpty()

    Box(modifier = modifier) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.chat_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.MEDIUM)
            )
            Text(
                text = stringResource(R.string.chat_type_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.FAINT)
            )

            if (showCards) {
                Spacer(Modifier.height(SpacingTokens.MD.dp))
                Text(
                    text = stringResource(R.string.agent_preset_cards_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = AlphaTokens.HIGH),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SpacingTokens.XS.dp)
                )
                agentPresets.forEach { preset ->
                    AgentPresetCard(
                        preset = preset,
                        selected = preset.id == sessionMeta.sessionAgentPreset,
                        onClick = { viewModel.selectAgentPreset(preset.id) },
                    )
                }
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

/** 单张预设卡（Material3 Card）：选中态高亮 primaryContainer + primary 描边。 */
@Composable
private fun AgentPresetCard(
    preset: AgentPreset,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = ShapeTokens.medium,
        colors = CardDefaults.cardColors(containerColor = container),
        border = if (selected) BorderStroke(1.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = preset.name,
                style = MaterialTheme.typography.titleSmall,
                color = contentColor,
            )
            if (preset.description.isNotBlank()) {
                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = contentColor.copy(alpha = AlphaTokens.MEDIUM),
                )
            }
        }
    }
}
