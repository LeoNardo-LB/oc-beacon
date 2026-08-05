package dev.leonardo.ocbeacon.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.screens.sessions.components.isAmoledTheme

/**
 * 用于选择器对话框的可复用单选列表。
 * 处理高亮、勾选图标、AMOLED 主题，以及自动滚动到选中项。
 */
@Composable
fun <K> AppPickerList(
    options: List<Pair<K, String>>,
    selectedKey: K,
    onSelect: (K) -> Unit,
) {
    val isAmoled = isAmoledTheme()
    val listState = rememberLazyListState()
    val selectedIndex = remember(options, selectedKey) {
        options.indexOfFirst { it.first == selectedKey }.coerceAtLeast(0)
    }
    LaunchedEffect(selectedIndex) {
        listState.scrollToItem(selectedIndex)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(options, key = { it.first.toString() }) { (key, label) ->
            val isSelected = key == selectedKey
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(ShapeTokens.medium)
                    .background(
                        when {
                            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = AlphaTokens.MUTED)
                            else -> Color.Transparent
                        }
                    )
                    .then(
                        if (isSelected && isAmoled) {
                            Modifier.border(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.MEDIUM),
                                shape = ShapeTokens.medium,
                            )
                        } else Modifier
                    )
                    .clickable { onSelect(key) }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                )
                if (isSelected) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(R.string.a11y_icon_selected),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}
