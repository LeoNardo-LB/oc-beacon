package dev.leonardo.ocbeacon.ui.screens.sessions

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.SessionCategory
import dev.leonardo.ocbeacon.ui.screens.sessions.components.SessionCategoryPickerDialog
import dev.leonardo.ocbeacon.ui.screens.sessions.components.SessionCategoryStyle
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * 跨服务器收藏屏幕。列出所有已知服务器上的已收藏会话，
 * 服务器已连接时显示实时数据，否则显示持久化的快照。
 *
 * @param onNavigateBack 弹出本屏幕。
 * @param onOpenSession 用户点击服务器已连接的收藏时调用。
 *        接收 [CrossServerSessionItem]，调用方可据此路由到聊天屏幕。
 * @param onConnectServer 用户在提示对话框中选择连接离线服务器时调用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrossServerSessionsScreen(
    onNavigateBack: () -> Unit,
    onOpenSession: (CrossServerSessionItem) -> Unit,
    onConnectServer: (serverId: String) -> Unit,
    viewModel: CrossServerSessionsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    var selectedCategoryId by remember { mutableStateOf<String?>(null) }
    var offlinePromptItem by remember { mutableStateOf<CrossServerSessionItem?>(null) }
    var menuForItem by remember { mutableStateOf<CrossServerSessionItem?>(null) }
    var categoryPickerItem by remember { mutableStateOf<CrossServerSessionItem?>(null) }

    val visibleItems = remember(state.items, selectedCategoryId) {
        filterCrossServerFavorites(state.items, selectedCategoryId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.favorites_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // 分类过滤行
            if (state.filterCategories.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = selectedCategoryId == null,
                        onClick = { selectedCategoryId = null },
                        label = { Text(stringResource(R.string.all)) },
                    )
                    state.filterCategories.forEach { category ->
                        FilterChip(
                            selected = selectedCategoryId == category.id,
                            onClick = { selectedCategoryId = category.id },
                            label = { Text(category.name) },
                        )
                    }
                }
            }

            if (visibleItems.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Filled.Star,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (selectedCategoryId == null) stringResource(R.string.no_favorites)
                            else stringResource(R.string.no_favorites_in_category),
                            modifier = Modifier.padding(top = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    items(
                        visibleItems,
                        key = { item -> "${item.serverId}:${item.sessionId}" },
                    ) { item ->
                        CrossServerFavoriteCard(
                            item = item,
                            onClick = {
                                if (item.isConnected) {
                                    onOpenSession(item)
                                } else {
                                    offlinePromptItem = item
                                }
                            },
                            onLongClick = { menuForItem = item },
                        )
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(
                                alpha = AlphaTokens.FAINT
                            )
                        )
                    }
                }
            }
        }
    }

    // 离线提示对话框
    offlinePromptItem?.let { item ->
        AlertDialog(
            onDismissRequest = { offlinePromptItem = null },
            title = { Text(stringResource(R.string.server_not_connected)) },
            text = { Text(stringResource(R.string.server_not_connected_desc, item.serverName)) },
            confirmButton = {
                TextButton(onClick = {
                    offlinePromptItem = null
                    onConnectServer(item.serverId)
                }) { Text(stringResource(R.string.connect)) }
            },
            dismissButton = {
                TextButton(onClick = { offlinePromptItem = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // 长按操作菜单
    menuForItem?.let { item ->
        AlertDialog(
            onDismissRequest = { menuForItem = null },
            title = { Text(item.displayTitle()) },
            text = {
                Column {
                    MenuActionRow(text = stringResource(R.string.set_category)) {
                        categoryPickerItem = item
                        menuForItem = null
                    }
                    MenuActionRow(text = stringResource(R.string.remove_favorite)) {
                        viewModel.toggleFavorite(item.copy(isFavorite = true))
                        menuForItem = null
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { menuForItem = null }) { Text(stringResource(R.string.close)) }
            },
        )
    }

    // 分类选择器
    categoryPickerItem?.let { item ->
        SessionCategoryPickerDialog(
            categories = state.categories,
            assignedCategoryId = item.category?.id,
            onAssign = { categoryId ->
                viewModel.setSessionCategory(item, categoryId)
                categoryPickerItem = null
            },
            onCreateCategory = { name, color, icon ->
                viewModel.saveSessionCategory(id = null, name = name, color = color, icon = icon)
            },
            onDeleteCategory = { categoryId -> viewModel.deleteSessionCategory(categoryId) },
            onDismiss = { categoryPickerItem = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CrossServerFavoriteCard(
    item: CrossServerSessionItem,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val alpha = if (item.isConnected) AlphaTokens.HIGH else AlphaTokens.MUTED
    // 连接状态 / 分类图标
    val leadingIcon = item.category?.let { SessionCategoryStyle.icon(it.icon) }
        ?: if (item.isConnected) Icons.Filled.Star else Icons.Filled.CloudOff
    val leadingTint = item.category?.let { SessionCategoryStyle.color(it.color) }
        ?: MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        headlineContent = {
            Text(
                text = item.displayTitle(),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
            )
        },
        supportingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = item.serverName,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
                item.category?.let { category ->
                    Text(
                        text = "· ${category.name}",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = SessionCategoryStyle.color(category.color).copy(alpha = alpha),
                    )
                }
                if (!item.isConnected) {
                    Text(
                        text = stringResource(R.string.offline_suffix),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error.copy(alpha = alpha),
                    )
                }
            }
        },
        leadingContent = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = leadingTint.copy(alpha = alpha),
            )
        },
        trailingContent = {
            IconButton(onClick = onLongClick, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.more_actions),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha),
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    )
}

@Composable
private fun MenuActionRow(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(text, style = MaterialTheme.typography.bodyLarge) }
}

private fun CrossServerSessionItem.displayTitle(): String =
    session?.title?.takeUnless { it.isBlank() }
        ?: snapshot?.title?.takeUnless { it.isBlank() }
        ?: sessionId
