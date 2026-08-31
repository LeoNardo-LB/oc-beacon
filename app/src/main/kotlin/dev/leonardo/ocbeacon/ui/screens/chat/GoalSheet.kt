package dev.leonardo.ocbeacon.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.DshGoalProjection
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 目标（Goal）面板（backlog #286，Web 语义对齐）。
 *
 * 视图状态机：
 * - 无目标 / phase==complete → 空态：创建表单（objective + maxGoalRounds）；
 * - 激活/暂停/受阻 → 详情：phase 标签 + objective + rounds N/M + blockedReason
 *   （blocked 内联展示 message）+ 按钮 pause(active)/resume(paused)/edit(表单)/clear；
 * - 完成态不渲染条目（Web 语义：complete 即回到空态创建表单，面板内一致）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GoalSheet(
    goal: DshGoalProjection?,
    onDismiss: () -> Unit,
    onCreate: (objective: String, maxGoalRounds: Long?) -> Unit,
    onEdit: (objective: String, maxGoalRounds: Long?) -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onClear: () -> Unit,
) {
    SheetScaffold(
        title = stringResource(R.string.toolbar_goal),
        onDismiss = onDismiss,
    ) {
        val phase = goal?.goal?.phase
        if (goal == null || phase == "complete") {
            GoalCreateForm(onCreate = onCreate)
        } else {
            var editing by remember { mutableStateOf(false) }
            if (editing) {
                GoalEditForm(
                    goal = goal,
                    onSave = { objective, rounds ->
                        editing = false
                        onEdit(objective, rounds)
                    },
                    onCancel = { editing = false },
                )
            } else {
                GoalDetail(
                    goal = goal,
                    onPause = onPause,
                    onResume = onResume,
                    onEditClick = { editing = true },
                    onClear = onClear,
                )
            }
        }
    }
}

/** 详情视图（phase 标签 + objective + rounds + blockedReason + 动作按钮）。 */
@Composable
private fun GoalDetail(
    goal: DshGoalProjection,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEditClick: () -> Unit,
    onClear: () -> Unit,
) {
    val snapshot = goal.goal
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
    ) {
        val phaseLabel = when (snapshot.phase) {
            "paused" -> stringResource(R.string.goal_phase_paused)
            "blocked" -> stringResource(R.string.goal_phase_blocked)
            else -> stringResource(R.string.goal_phase_active)
        }
        val phaseColor = when (snapshot.phase) {
            "paused" -> MaterialTheme.colorScheme.secondary
            "blocked" -> MaterialTheme.colorScheme.error
            else -> MaterialTheme.colorScheme.primary
        }
        Surface(
            color = phaseColor.copy(alpha = AlphaTokens.FAINT),
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = phaseLabel,
                style = MaterialTheme.typography.labelMedium,
                color = phaseColor,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        Text(
            text = snapshot.objective,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 6,
            overflow = TextOverflow.Ellipsis,
        )

        val rounds = goal.roundsStarted
        Text(
            text = if (snapshot.maxGoalRounds > 0) {
                stringResource(R.string.goal_rounds_progress, rounds, snapshot.maxGoalRounds)
            } else {
                stringResource(R.string.goal_rounds_plain, rounds)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
        )

        snapshot.blockedReason?.let { reason ->
            if (reason.message.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Text(
                        text = stringResource(R.string.goal_blocked_reason, reason.message),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(SpacingTokens.XS.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            when (snapshot.phase) {
                "active" -> OutlinedButton(onClick = onPause, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.goal_action_pause))
                }
                "paused" -> Button(onClick = onResume, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.goal_action_resume))
                }
                else -> Unit
            }
            OutlinedButton(onClick = onEditClick, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.goal_action_edit))
            }
            TextButton(onClick = onClear, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.goal_action_clear), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** 创建表单（无 goal / complete 空态）。 */
@Composable
private fun GoalCreateForm(onCreate: (String, Long?) -> Unit) {
    var objective by remember { mutableStateOf("") }
    var maxRounds by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
    ) {
        Text(
            text = stringResource(R.string.goal_create_heading),
            style = MaterialTheme.typography.titleSmall,
        )
        OutlinedTextField(
            value = objective,
            onValueChange = { objective = it },
            label = { Text(stringResource(R.string.goal_objective_label)) },
            placeholder = { Text(stringResource(R.string.goal_objective_placeholder)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = maxRounds,
            onValueChange = { maxRounds = it.filter(Char::isDigit).take(4) },
            label = { Text(stringResource(R.string.goal_max_rounds_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = { onCreate(objective.trim(), maxRounds.toLongOrNull()?.takeIf { it > 0 }) },
                enabled = objective.isNotBlank(),
            ) {
                Text(stringResource(R.string.goal_btn_create))
            }
        }
    }
}

/** 编辑表单（prefill 当前 objective/maxGoalRounds）。 */
@Composable
private fun GoalEditForm(
    goal: DshGoalProjection,
    onSave: (String, Long?) -> Unit,
    onCancel: () -> Unit,
) {
    var objective by remember { mutableStateOf(goal.goal.objective) }
    var maxRounds by remember {
        mutableStateOf(goal.goal.maxGoalRounds.takeIf { it > 0 }?.toString() ?: "")
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
    ) {
        OutlinedTextField(
            value = objective,
            onValueChange = { objective = it },
            label = { Text(stringResource(R.string.goal_objective_label)) },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = maxRounds,
            onValueChange = { maxRounds = it.filter(Char::isDigit).take(4) },
            label = { Text(stringResource(R.string.goal_max_rounds_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
            ),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onCancel) {
                Text(stringResource(R.string.cancel))
            }
            Button(
                onClick = {
                    val rounds = maxRounds.toLongOrNull()?.takeIf { it > 0 }
                    onSave(objective.trim(), rounds)
                },
                enabled = objective.isNotBlank(),
            ) {
                Text(stringResource(R.string.goal_btn_save))
            }
        }
    }
}
