package dev.leonardo.ocbeacon.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.ui.screens.settings.components.ImageCompressionMaxSideDialog
import dev.leonardo.ocbeacon.ui.screens.settings.components.ImageCompressionQualityDialog
import dev.leonardo.ocbeacon.ui.screens.settings.components.LanguagePickerDialog
import dev.leonardo.ocbeacon.ui.screens.settings.components.MessageCountPickerDialog
import dev.leonardo.ocbeacon.ui.screens.settings.components.RecentDirectoryCountDialog
import dev.leonardo.ocbeacon.ui.screens.settings.components.ReconnectModePickerDialog
import dev.leonardo.ocbeacon.ui.screens.settings.components.TerminalFontSizeDialog
import dev.leonardo.ocbeacon.ui.screens.settings.components.ThemePickerDialog
import dev.leonardo.ocbeacon.ui.screens.settings.sections.AdvancedSection
import dev.leonardo.ocbeacon.ui.screens.settings.sections.AppearanceSection
import dev.leonardo.ocbeacon.ui.screens.settings.sections.AutoApproveRulesSection
import dev.leonardo.ocbeacon.ui.screens.settings.sections.ChatBehaviorSection
import dev.leonardo.ocbeacon.ui.screens.settings.sections.ChatDisplaySection
import dev.leonardo.ocbeacon.ui.screens.settings.sections.GeneralSection
import dev.leonardo.ocbeacon.ui.screens.settings.sections.NotificationsSection
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

/**
 * 设置屏幕 — 全局应用偏好。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToDiagnostics: () -> Unit,
) {
    val currentLanguage by viewModel.appLanguage.collectAsStateWithLifecycle()
    val currentTheme by viewModel.appTheme.collectAsStateWithLifecycle()
    val chatDensity by viewModel.chatDensity.collectAsStateWithLifecycle()

    val initialMessageCount by viewModel.initialMessageCount.collectAsStateWithLifecycle()
    val recentDirectoryCount by viewModel.recentDirectoryCount.collectAsStateWithLifecycle()
    val reconnectMode by viewModel.reconnectMode.collectAsStateWithLifecycle()
    val terminalFontSize by viewModel.terminalFontSize.collectAsStateWithLifecycle()
    val imageAttachmentMaxLongSide by viewModel.imageAttachmentMaxLongSide.collectAsStateWithLifecycle()
    val imageAttachmentWebpQuality by viewModel.imageAttachmentWebpQuality.collectAsStateWithLifecycle()

    var showLanguageDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showChatDensityPicker by remember { mutableStateOf(false) }
    var showMessageCountDialog by remember { mutableStateOf(false) }
    var showRecentDirectoryCountDialog by remember { mutableStateOf(false) }
    var showReconnectModeDialog by remember { mutableStateOf(false) }
    var showTerminalFontSizeDialog by remember { mutableStateOf(false) }
    var showImageMaxSideDialog by remember { mutableStateOf(false) }
    var showImageQualityDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.close)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .widthIn(max = 600.dp)
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
            // ======== General ========
            GeneralSection(
                viewModel = viewModel,
                onShowLanguageDialog = { showLanguageDialog = true },
                onShowReconnectModeDialog = { showReconnectModeDialog = true },
            )

            // ======== Appearance ========
            AppearanceSection(
                viewModel = viewModel,
                onShowThemeDialog = { showThemeDialog = true },
            )

            // ======== 聊天显示 ========
            ChatDisplaySection(
                viewModel = viewModel,
                onShowChatDensityPicker = { showChatDensityPicker = true },
            )

            // ======== 聊天行为 ========
            ChatBehaviorSection(
                viewModel = viewModel,
                onShowMessageCountDialog = { showMessageCountDialog = true },
                onShowImageMaxSideDialog = { showImageMaxSideDialog = true },
                onShowImageQualityDialog = { showImageQualityDialog = true },
                onShowTerminalFontSizeDialog = { showTerminalFontSizeDialog = true },
                onShowRecentDirectoryCountDialog = { showRecentDirectoryCountDialog = true },
            )

            // ======== Advanced ========
            AdvancedSection(
                viewModel = viewModel,
                onNavigateToDiagnostics = onNavigateToDiagnostics,
            )

            // ======== Notifications ========
            NotificationsSection(viewModel = viewModel)

            // ======== Permissions ========
            AutoApproveRulesSection(viewModel = viewModel)
        }

        if (showThemeDialog) {
            ThemePickerDialog(
                currentTheme = currentTheme,
                onThemeSelected = { theme ->
                    viewModel.setTheme(theme)
                    showThemeDialog = false
                },
                onDismiss = { showThemeDialog = false }
            )
        }

        if (showLanguageDialog) {
            LanguagePickerDialog(
                currentLanguage = currentLanguage,
                onLanguageSelected = { languageCode ->
                    viewModel.setLanguage(languageCode)
                    showLanguageDialog = false
                },
                onDismiss = { showLanguageDialog = false }
            )
        }

        if (showChatDensityPicker) {
            AlertDialog(
                onDismissRequest = { showChatDensityPicker = false },
                title = { Text(stringResource(R.string.settings_chat_font)) },
                text = {
                    Column {
                        listOf(
                            "normal" to R.string.settings_chat_font_normal,
                            "compact" to R.string.settings_chat_font_compact
                        ).forEach { (value, labelRes) ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        viewModel.setChatDensity(value)
                                        showChatDensityPicker = false
                                    }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(
                                    selected = chatDensity == value,
                                    onClick = null,
                                )
                                Text(
                                    stringResource(labelRes),
                                    modifier = Modifier.padding(start = 12.dp)
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showChatDensityPicker = false }) {
                        Text(stringResource(R.string.close))
                    }
                },
            )
        }

        if (showMessageCountDialog) {
            MessageCountPickerDialog(
                currentCount = initialMessageCount,
                onCountSelected = { count ->
                    viewModel.setInitialMessageCount(count)
                    showMessageCountDialog = false
                },
                onDismiss = { showMessageCountDialog = false }
            )
        }

        if (showRecentDirectoryCountDialog) {
            RecentDirectoryCountDialog(
                currentCount = recentDirectoryCount,
                onCountSelected = { count ->
                    viewModel.setRecentDirectoryCount(count)
                    showRecentDirectoryCountDialog = false
                },
                onDismiss = { showRecentDirectoryCountDialog = false }
            )
        }

        if (showReconnectModeDialog) {
            ReconnectModePickerDialog(
                currentMode = reconnectMode,
                onModeSelected = { mode ->
                    viewModel.setReconnectMode(mode)
                    showReconnectModeDialog = false
                },
                onDismiss = { showReconnectModeDialog = false }
            )
        }

        if (showTerminalFontSizeDialog) {
            TerminalFontSizeDialog(
                currentSize = terminalFontSize,
                onSizeSelected = { size ->
                    viewModel.setTerminalFontSize(size)
                    showTerminalFontSizeDialog = false
                },
                onDismiss = { showTerminalFontSizeDialog = false }
            )
        }

        if (showImageMaxSideDialog) {
            ImageCompressionMaxSideDialog(
                currentMaxSide = imageAttachmentMaxLongSide,
                onSelected = { px ->
                    viewModel.setImageAttachmentMaxLongSide(px)
                    showImageMaxSideDialog = false
                },
                onDismiss = { showImageMaxSideDialog = false }
            )
        }

        if (showImageQualityDialog) {
            ImageCompressionQualityDialog(
                currentQuality = imageAttachmentWebpQuality,
                onSelected = { quality ->
                    viewModel.setImageAttachmentWebpQuality(quality)
                    showImageQualityDialog = false
                },
                onDismiss = { showImageQualityDialog = false }
            )
        }
        }
    }
}
