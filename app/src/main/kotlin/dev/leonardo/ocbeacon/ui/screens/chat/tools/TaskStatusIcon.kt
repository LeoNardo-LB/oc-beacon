package dev.leonardo.ocbeacon.ui.screens.chat.tools

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.ui.theme.AgentError
import dev.leonardo.ocbeacon.ui.theme.AgentSuccess

/**
 * 统一任务状态图标系统（2026-08-12 用户要求：进行中/成功/失败的完整状态表示）。
 *
 * 语义（全局统一，禁止在别处另起状态图标）：
 * - RUNNING：转圈（primary 蓝）——进行中
 * - SUCCESS：CheckCircle（AgentSuccess 绿）——完成
 * - ERROR：ErrorOutline（AgentError 红）——失败/异常
 * - UNKNOWN：Info（primary 蓝）——未知/解析失败
 *
 * 尺寸统一 18dp（紧凑场景可传 [sizeDp] 缩小，颜色语义不变）。
 * 使用点：后台面板行、工具卡片、后台通知气泡。
 */
enum class TaskStatus { RUNNING, SUCCESS, ERROR, UNKNOWN }

@Composable
fun TaskStatusIcon(
    status: TaskStatus,
    modifier: Modifier = Modifier,
    sizeDp: Int = 18,
    contentDescription: String? = null,
) {
    when (status) {
        TaskStatus.RUNNING -> CircularProgressIndicator(
            modifier = modifier.size(sizeDp.dp),
            strokeWidth = 2.dp,
            color = MaterialTheme.colorScheme.primary
        )
        TaskStatus.SUCCESS -> Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = contentDescription,
            modifier = modifier.size(sizeDp.dp),
            tint = AgentSuccess
        )
        TaskStatus.ERROR -> Icon(
            imageVector = Icons.Default.ErrorOutline,
            contentDescription = contentDescription,
            modifier = modifier.size(sizeDp.dp),
            tint = AgentError
        )
        TaskStatus.UNKNOWN -> Icon(
            imageVector = Icons.Outlined.Info,
            contentDescription = contentDescription,
            modifier = modifier.size(sizeDp.dp),
            tint = MaterialTheme.colorScheme.primary
        )
    }
}
