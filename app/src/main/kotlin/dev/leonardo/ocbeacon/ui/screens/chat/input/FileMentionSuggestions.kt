package dev.leonardo.ocbeacon.ui.screens.chat.input

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * File mention suggestion popup shown when user types "@<query>".
 *
 * #310⑤：DSH 下同时呈现会话源候选（sessionReferenceResolver 域,会话行在前——
 * 与 [dev.leonardo.ocbeacon.domain.model.mergeMentionCandidates] 排序语义一致）；
 * 点选会话行以服务器权威 mention 串 @[label](dsh-session:id) 替换 trigger 词。
 */
@Composable
internal fun FileMentionSuggestions(
    results: List<String>,
    sessions: List<dev.leonardo.ocbeacon.domain.model.MentionCandidate.SessionMention> = emptyList(),
    onFileSelected: (String) -> Unit,
    onSessionSelected: (dev.leonardo.ocbeacon.domain.model.MentionCandidate.SessionMention) -> Unit = {},
) {
    AnimatedVisibility(
        visible = results.isNotEmpty() || sessions.isNotEmpty(),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        val configuration = LocalConfiguration.current
        val maxHeight = (configuration.screenHeightDp * 0.4f).dp

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(vertical = SpacingTokens.XS.dp)
        ) {
            // #310⑤ 会话源候选行（在前——merge 排序语义；quoted 形态下恒空）
            items(
                sessions.take(5),
                key = { "session-" + it.sessionId }
            ) { session ->
                SessionMentionRow(session = session, onClick = { onSessionSelected(session) })
            }
            items(
                results.take(10),
                key = { it }
            ) { path ->
                val isDir = path.endsWith("/")
                // Split into directory part + filename for display
                val displayPath = if (isDir) path.trimEnd('/') else path
                val lastSlash = displayPath.lastIndexOf('/')
                val dirPart = if (lastSlash >= 0) displayPath.substring(0, lastSlash + 1) else ""
                val namePart = if (lastSlash >= 0) displayPath.substring(lastSlash + 1) else displayPath

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFileSelected(path) }
                        .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)
                ) {
                    Icon(
                        imageVector = if (isDir) Icons.Default.Folder else Icons.Default.Description,
                        contentDescription = stringResource(R.string.a11y_icon_file),
                        modifier = Modifier.size(16.dp),
                        tint = if (isDir)
                            MaterialTheme.colorScheme.tertiary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM)
                    )
                    Text(
                        text = buildAnnotatedString {
                            if (dirPart.isNotEmpty()) {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED))) {
                                    append(dirPart)
                                }
                            }
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                                append(namePart)
                            }
                            if (isDir) {
                                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED))) {
                                    append("/")
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/**
 * #310⑤ 会话源候选行（自有形态,#313 原则）：会话图标 + label（+同工作区徽标）
 * + cwd 次行小字；点击以服务器权威 mention 串插入草稿。
 */
@Composable
private fun SessionMentionRow(
    session: dev.leonardo.ocbeacon.domain.model.MentionCandidate.SessionMention,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = SpacingTokens.LG.dp, vertical = SpacingTokens.SM.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp)
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Chat,
            contentDescription = stringResource(R.string.chat_mention_session_a11y, session.label),
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = session.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (session.sameWorkspace) {
                    Text(
                        text = stringResource(R.string.chat_mention_same_workspace),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.FAINT),
                                MaterialTheme.shapes.extraSmall
                            )
                            .padding(horizontal = SpacingTokens.XS.dp, vertical = 1.dp)
                    )
                }
            }
            if (!session.cwd.isNullOrBlank()) {
                Text(
                    text = session.cwd,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
