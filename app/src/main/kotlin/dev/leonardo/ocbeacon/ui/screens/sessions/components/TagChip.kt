package dev.leonardo.ocbeacon.ui.screens.sessions.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Done
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Tag
import dev.leonardo.ocbeacon.domain.model.TagType
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import dev.leonardo.ocbeacon.ui.theme.SpacingTokens

/**
 * 标签的本地化显示名。
 *
 * 内置收藏标签的名称数据持久化为中文"收藏"（[Tag.name] 是用户数据，不可直接改），
 * 展示时替换为本地化的 "Favorites"；用户标签直接用 [Tag.name]。
 */
@Composable
internal fun Tag.displayName(): String = when (type) {
    TagType.FAVORITE -> stringResource(R.string.favorites_title)
    TagType.USER -> name
}

/**
 * 小徽标形态的 tag（会话行 / 详情对话框展示用）：无 chip 边框，浅色底 + tag 色文字/图标。
 * 与 [TagChip]（交互式 FilterChip）区分——展示场景用小徽标，交互场景用 chip。
 */
@Composable
fun TagBadge(tag: Tag) {
    val tagColor = SessionCategoryStyle.color(tag.color)
    Row(
        modifier = Modifier
            .background(tagColor.copy(alpha = AlphaTokens.SELECTED))
            .padding(horizontal = SpacingTokens.XS.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Icon(
            imageVector = SessionCategoryStyle.icon(tag.icon),
            contentDescription = null,
            modifier = Modifier.size(10.dp),
            tint = tagColor,
        )
        Text(
            text = tag.displayName(),
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = tagColor,
            maxLines = 1,
        )
    }
}

/**
 * 统一的 Tag 展示/交互 chip（全应用唯一形态：搜索栏筛选 / 分配对话框 / 详情对话框）。
 *
 * 视觉规格：
 * - 未选中：tag 色浅底（[AlphaTokens.FAINT]）+ tag 色文字/图标 + tag 色半透明边框
 * - 选中：tag 色高饱和底（[AlphaTokens.HIGH]）+ 黑白对比文字 + ✓ + tag 色实体边框
 *
 * [onClick] 为 null 时仅展示（详情对话框），点击无副作用。
 */
@Composable
fun TagChip(
    tag: Tag,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tagColor = SessionCategoryStyle.color(tag.color)
    val onColor = if (tagColor.luminance() > 0.5f) Color.Black else Color.White

    FilterChip(
        selected = selected,
        onClick = { onClick?.invoke() },
        modifier = modifier,
        label = {
            Text(
                text = tag.displayName(),
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) onColor else tagColor,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = if (selected) Icons.Filled.Done else SessionCategoryStyle.icon(tag.icon),
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
                tint = if (selected) onColor else tagColor,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = tagColor.copy(alpha = AlphaTokens.FAINT),
            selectedContainerColor = tagColor.copy(alpha = AlphaTokens.HIGH),
            labelColor = tagColor,
            selectedLabelColor = onColor,
            selectedLeadingIconColor = onColor,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = tagColor.copy(alpha = AlphaTokens.FAINT),
            selectedBorderColor = tagColor,
            selectedBorderWidth = 1.dp,
        ),
    )
}
