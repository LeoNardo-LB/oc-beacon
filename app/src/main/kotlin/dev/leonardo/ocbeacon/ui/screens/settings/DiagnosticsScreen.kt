package dev.leonardo.ocbeacon.ui.screens.settings

import android.content.Intent
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.util.copyToClipboard
import dev.leonardo.ocbeacon.data.repository.DiagnosticLogEntry
import dev.leonardo.ocbeacon.data.repository.DiagnosticLogRepository
import dev.leonardo.ocbeacon.ui.components.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import dev.leonardo.ocbeacon.util.DateFormatters
import java.util.Date

private val LEVELS = listOf("ERROR", "WARN", "INFO", "DEBUG")

/**
 * 日志条目的内容派生稳定键（L-11）——队列头淘汰/过滤变化时存留条目 key 保持稳定，
 * 避免原 timestamp_index 拼接导致全表 key 失效 → 全量重组合 + 滚动跳动。
 * timestamp 非唯一（同一毫秒多条日志），叠加 category + message hash 保证唯一性。
 */
private fun logEntryKey(entry: DiagnosticLogEntry): String =
    "${entry.timestamp}_${entry.category}_${entry.message.hashCode()}"

@Composable
private fun levelColor(level: String): Color = when (level) {
    "FATAL", "ERROR" -> MaterialTheme.colorScheme.error
    "WARN" -> MaterialTheme.colorScheme.tertiary
    "INFO" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onNavigateBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val logLevel by viewModel.logLevel.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var selectedLevels by remember { mutableStateOf(emptySet<String>()) }
    var searchQuery by remember { mutableStateOf("") }
    var showActionsMenu by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var showLevelDialog by remember { mutableStateOf(false) }
    var expandedEntryKey by remember { mutableStateOf<String?>(null) }

    val filteredEntries = remember(entries, selectedLevels, searchQuery) {
        entries.filter { entry ->
            (selectedLevels.isEmpty() || entry.level in selectedLevels) &&
                (searchQuery.isBlank() ||
                    entry.message.contains(searchQuery, ignoreCase = true) ||
                    entry.category.contains(searchQuery, ignoreCase = true))
        }
    }

    val crashCount = entries.count { it.level == "FATAL" }

    suspend fun exportText(): String = buildString {
        val timeRange = entries.takeIf { it.isNotEmpty() }?.let {
            "${java.time.Instant.ofEpochMilli(it.first().timestamp)}..${java.time.Instant.ofEpochMilli(it.last().timestamp)}"
        } ?: "empty"
        appendLine("OC Beacon diagnostics")
        appendLine("Generated: ${java.time.Instant.now()}")
        appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
        appendLine("Persistent log level: $logLevel")
        appendLine("Time range: $timeRange")
        appendLine("Dropped queue entries: ${viewModel.droppedEntryCount()}")
        appendLine("Included: lifecycle, connection, REST/SSE result classes, and crashes; no chat or terminal payloads")
        appendLine()
        append(viewModel.export().ifBlank { context.getString(R.string.diagnostics_empty) })
    }

    fun shareAsFile() {
        scope.launch {
            val text = exportText()
            val file = withContext(Dispatchers.IO) {
                val directory = File(context.cacheDir, "diagnostics").apply { mkdirs() }
                File(directory, "diagnostics.txt").apply { writeText(text) }
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "OC Beacon diagnostics")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, context.getString(R.string.diagnostics_share)))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.diagnostics_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.close))
                    }
                },
                actions = {
                    IconButton(onClick = ::shareAsFile, enabled = entries.isNotEmpty()) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.diagnostics_share))
                    }
                    Box {
                        IconButton(onClick = { showActionsMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(expanded = showActionsMenu, onDismissRequest = { showActionsMenu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.diagnostics_log_level)) },
                                leadingIcon = { Icon(Icons.Default.Tune, contentDescription = null) },
                                onClick = {
                                    showActionsMenu = false
                                    showLevelDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.diagnostics_copy)) },
                                leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                                enabled = entries.isNotEmpty(),
                                onClick = {
                                    scope.launch {
                                        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                                        clipboard?.copyToClipboard("diagnostics", exportText())
                                    }
                                    showActionsMenu = false
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(stringResource(R.string.diagnostics_clear), color = MaterialTheme.colorScheme.error)
                                },
                                leadingIcon = {
                                    Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                },
                                enabled = entries.isNotEmpty(),
                                onClick = {
                                    showActionsMenu = false
                                    showClearConfirmation = true
                                },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // ---- 级别过滤 chip ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LEVELS.forEach { level ->
                    FilterChip(
                        selected = level in selectedLevels,
                        onClick = {
                            selectedLevels = if (level in selectedLevels) {
                                selectedLevels - level
                            } else {
                                selectedLevels + level
                            }
                        },
                        label = { Text(level) },
                    )
                }
            }

            // ---- 搜索栏 ----
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                placeholder = { Text(stringResource(R.string.diagnostics_search_hint)) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions.Default,
            )

            // ---- 汇总栏 ----
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "${filteredEntries.size} / ${entries.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (crashCount > 0) {
                    Text(
                        "$crashCount ${stringResource(R.string.diagnostics_crashes)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                val dropped = viewModel.droppedEntryCount()
                if (dropped > 0) {
                    Text(
                        "$dropped ${stringResource(R.string.diagnostics_dropped)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            // ---- 条目列表 / 空状态 ----
            if (filteredEntries.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        stringResource(R.string.diagnostics_empty),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        stringResource(R.string.diagnostics_empty_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val dateFormat = remember { DateFormatters.diagnostics() }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    // L-11：内容派生稳定键（timestamp+category+message hash）。
                    // timestamp 非唯一（同一毫秒多条日志），叠加内容字段保证唯一；
                    // 队列头淘汰时存留条目 key 不变（原 timestamp_index 拼接全表失效）。
                    itemsIndexed(filteredEntries, key = { _, entry -> logEntryKey(entry) }) { index, entry ->
                        val entryKey = logEntryKey(entry)
                        DiagnosticLogItem(
                            entry = entry,
                            timeLabel = dateFormat.format(Date(entry.timestamp)),
                            isExpanded = expandedEntryKey == entryKey,
                            onClick = {
                                expandedEntryKey = if (expandedEntryKey == entryKey) null else entryKey
                            },
                        )
                    }
                }
            }
        }
    }

    // ---- 清空确认 ----
    if (showClearConfirmation) {
        ConfirmDialog(
            title = stringResource(R.string.diagnostics_clear_confirm_title),
            message = stringResource(R.string.diagnostics_clear_confirm_message),
            confirmLabel = stringResource(R.string.diagnostics_clear),
            onDismiss = { showClearConfirmation = false },
            onConfirm = {
                showClearConfirmation = false
                viewModel.clear()
            },
        )
    }

    // ---- 日志级别对话框 ----
    if (showLevelDialog) {
        AlertDialog(
            onDismissRequest = { showLevelDialog = false },
            title = { Text(stringResource(R.string.diagnostics_log_level)) },
            text = {
                Column {
                    DiagnosticLogRepository.LOG_LEVELS.forEach { level ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { viewModel.setLogLevel(level); showLevelDialog = false }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = level == logLevel, onClick = { viewModel.setLogLevel(level); showLevelDialog = false })
                            Text(level)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLevelDialog = false }) { Text(stringResource(R.string.close)) }
            },
        )
    }
}

@Composable
private fun DiagnosticLogItem(
    entry: DiagnosticLogEntry,
    timeLabel: String,
    isExpanded: Boolean,
    onClick: () -> Unit,
) {
    val color = levelColor(entry.level)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = entry.level,
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.widthIn(min = 48.dp),
                )
                Spacer(Modifier.size(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = buildString {
                            append(timeLabel)
                            append("  ")
                            append(entry.category)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(2.dp))
                    Text(
                        text = entry.message,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = if (isExpanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (isExpanded && entry.details.isNotEmpty()) {
                Spacer(Modifier.size(8.dp))
                entry.details.forEach { (key, value) ->
                    Text(
                        text = "$key=$value",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
