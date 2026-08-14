package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.ui.components.amoledOutlinedTextFieldColors
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 会话列表搜索栏 + 分类过滤 chips。
 *
 * 内部管理搜索输入的 debounce（300ms）和状态。
 */
@Composable
internal fun SessionSearchBar(
    isAmoled: Boolean,
    categories: List<Tag>,
    categoryFilter: Set<String>,
    onCategoryToggle: (String) -> Unit,
    onClearFilters: () -> Unit,
    onSearch: (String) -> Unit,
    onClearSearch: () -> Unit,
) {
    var searchInput by rememberSaveable { mutableStateOf("") }
    val searchJob = remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    OutlinedTextField(
        value = searchInput,
        onValueChange = { newQuery ->
            searchInput = newQuery
            searchJob.value?.cancel()
            searchJob.value = scope.launch {
                delay(300)
                onSearch(newQuery)
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = SpacingTokens.SM.dp),
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.a11y_icon_search))
        },
        trailingIcon = {
            if (searchInput.isNotEmpty()) {
                IconButton(onClick = {
                    searchInput = ""
                    onClearSearch()
                }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.sessions_clear_search))
                }
            }
        },
        placeholder = { Text(stringResource(R.string.search_sessions)) },
        singleLine = true,
        colors = if (isAmoled) {
            amoledOutlinedTextFieldColors()
        } else {
            OutlinedTextFieldDefaults.colors()
        }
    )

    // 分类过滤 chip：多选（AND 语义）；全部取消时自动回到"全部"选中态
    if (categories.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.SM.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(
                selected = categoryFilter.isEmpty(),
                onClick = onClearFilters,
                label = { Text(stringResource(R.string.all)) },
            )
            categories.forEach { category ->
                TagChip(
                    tag = category,
                    selected = category.id in categoryFilter,
                    onClick = { onCategoryToggle(category.id) },
                )
            }
        }
    }
}
