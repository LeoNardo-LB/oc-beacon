package dev.leonardo.ocbeacon.ui.screens.chat.tools.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.domain.model.Part
import dev.leonardo.ocbeacon.domain.model.ToolState
import dev.leonardo.ocbeacon.ui.components.AmoledDefaultBorder
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractToolInput
import dev.leonardo.ocbeacon.ui.screens.chat.tools.extractToolOutput
import dev.leonardo.ocbeacon.ui.screens.chat.util.isAmoledTheme
import dev.leonardo.ocbeacon.ui.screens.chat.util.toolOutputContainerColor
import dev.leonardo.ocbeacon.ui.theme.CodeTypography
import dev.leonardo.ocbeacon.ui.theme.ShapeTokens
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private data class SearchResult(
    val title: String,
    val url: String,
    val summary: String
)

/**
 * WebSearch tool card — shows search query + result list.
 */
@Composable
internal fun WebSearchToolCard(
    tool: Part.Tool,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit
) {
    val isAmoled = isAmoledTheme()
    val input = extractToolInput(tool)
    val query = input["query"]?.jsonPrimitive?.contentOrNull ?: ""
    val output = extractToolOutput(tool)
    val isRunning = tool.state is ToolState.Running

    // Try to parse structured results from metadata
    val results = remember(tool.state) {
        val completed = tool.state as? ToolState.Completed
        val meta = completed?.metadata
        val resultsJson = meta?.get("results")?.jsonArray
        if (resultsJson != null) {
            resultsJson.mapNotNull { element ->
                val obj = element as? JsonObject ?: return@mapNotNull null
                SearchResult(
                    title = obj["title"]?.jsonPrimitive?.contentOrNull ?: "",
                    url = obj["url"]?.jsonPrimitive?.contentOrNull ?: "",
                    summary = obj["snippet"]?.jsonPrimitive?.contentOrNull ?: ""
                )
            }
        } else {
            // Fallback: parse output as text
            if (output.isBlank()) emptyList()
            else listOf(SearchResult(title = "", url = "", summary = output.take(2000)))
        }
    }

    val title = if (query.isNotBlank()) {
        val shortQuery = if (query.length > 40) query.take(37) + "..." else query
        "${stringResource(R.string.tool_web_search)} · $shortQuery"
    } else {
        stringResource(R.string.tool_web_search)
    }

    ToolCardScaffold(
        icon = Icons.Default.Search,
        iconTint = MaterialTheme.colorScheme.primary,
        title = title,
        copyText = output,
        isExpanded = isExpanded,
        isRunning = isRunning,
        hasContent = results.isNotEmpty() || output.isNotBlank(),
        isAmoled = isAmoled,
        onToggleExpand = onToggleExpand
    ) {
        Column {
            if (results.isNotEmpty()) {
                Surface(
                    shape = ShapeTokens.extraSmall,
                    color = toolOutputContainerColor(),
                    border = if (isAmoled) AmoledDefaultBorder else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(results, key = { "${it.title}_${it.url}" }) { result ->
                            SearchResultRow(result = result, isAmoled = isAmoled)
                        }
                    }
                }
            } else if (output.isNotBlank()) {
                Surface(
                    shape = ShapeTokens.extraSmall,
                    color = toolOutputContainerColor(),
                    border = if (isAmoled) AmoledDefaultBorder else null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SelectionContainer {
                        Text(
                            text = output.take(2000),
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = if (isAmoled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.AMOLED)
                                } else {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                }
                            ),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: SearchResult, isAmoled: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        if (result.title.isNotBlank()) {
            Text(
                text = result.title,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (result.url.isNotBlank()) {
            Text(
                text = result.url,
                style = CodeTypography.copy(
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = AlphaTokens.HIGH)
                ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (result.summary.isNotBlank()) {
            Text(
                text = result.summary,
                style = MaterialTheme.typography.bodySmall.copy(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
