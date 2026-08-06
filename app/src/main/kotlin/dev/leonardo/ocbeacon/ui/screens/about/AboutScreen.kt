package dev.leonardo.ocbeacon.ui.screens.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.R
import dev.leonardo.ocbeacon.data.update.UpdateState
import dev.leonardo.ocbeacon.ui.components.rememberUpdateInstallLauncher
import dev.leonardo.ocbeacon.ui.theme.AlphaTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onNavigateBack: () -> Unit = {},
    viewModel: AboutViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val version = BuildConfig.VERSION_NAME
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()

    val installLauncher = rememberUpdateInstallLauncher(
        onInstallerLaunched = { viewModel.markInstallerLaunched() },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(48.dp))

            // 应用名
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(4.dp))

            // Version
            Text(
                text = stringResource(R.string.about_version, version),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // Description
            Text(
                text = stringResource(R.string.about_description),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(4.dp))

            // 非官方声明
            Text(
                text = stringResource(R.string.about_unofficial),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MEDIUM),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            // 更新检查卡片（Google Play stable 渠道隐藏——自更新由 Play 分发接管）
            if (BuildConfig.ENABLE_AUTO_UPDATE) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    UpdateCheckContent(
                        state = updateState,
                        onCheckForUpdate = { viewModel.checkForUpdate() },
                        onDownload = { release -> viewModel.prepareInstall(release) },
                        onInstall = { apkPath -> installLauncher(apkPath) },
                        onViewRelease = { url ->
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                        },
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            Spacer(Modifier.height(24.dp))

            // Links
            val githubUrl = stringResource(R.string.about_github_url)
            val opencodeUrl = stringResource(R.string.about_opencode_url)

            OutlinedCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                // GitHub 仓库
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_github)) },
                    supportingContent = {
                        Text(githubUrl, style = MaterialTheme.typography.bodySmall)
                    },
                    leadingContent = {
                        Icon(Icons.Default.Code, contentDescription = stringResource(R.string.a11y_icon_code))
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = stringResource(R.string.a11y_icon_open_in_browser),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                        )
                    },
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(githubUrl)))
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                )

                // OpenCode 项目
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_opencode)) },
                    supportingContent = {
                        Text(opencodeUrl, style = MaterialTheme.typography.bodySmall)
                    },
                    leadingContent = {
                        Icon(Icons.Default.Code, contentDescription = stringResource(R.string.a11y_icon_code))
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = stringResource(R.string.a11y_icon_open_in_browser),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = AlphaTokens.MUTED)
                        )
                    },
                    modifier = Modifier.clickable {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(opencodeUrl)))
                    }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = AlphaTokens.FAINT)
                )

                // License
                ListItem(
                    headlineContent = { Text(stringResource(R.string.about_license)) },
                    supportingContent = {
                        Text(stringResource(R.string.about_license_value))
                    },
                    leadingContent = {
                        Icon(Icons.Default.Description, contentDescription = stringResource(R.string.a11y_icon_description))
                    }
                )
            }
        }
    }
}

@Composable
private fun UpdateCheckContent(
    state: UpdateState,
    onCheckForUpdate: () -> Unit,
    onDownload: (dev.leonardo.ocbeacon.data.update.AvailableUpdate) -> Unit,
    onInstall: (String) -> Unit,
    onViewRelease: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        when (state) {
            is UpdateState.Idle -> {
                FilledTonalButton(
                    onClick = onCheckForUpdate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.update_check))
                }
            }

            is UpdateState.Checking -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(R.string.update_checking),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            is UpdateState.UpToDate -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.update_up_to_date),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = onCheckForUpdate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.update_check))
                }
            }

            is UpdateState.Available -> {
                val release = state.release
                Text(
                    text = stringResource(R.string.update_available, release.versionName),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                release.releaseNotes?.let { notes ->
                    if (notes.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = notes.take(200),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onViewRelease(release.releaseUrl) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.update_view_release))
                    }
                    Button(
                        onClick = { onDownload(release) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.update_download_install))
                    }
                }
            }

            is UpdateState.Downloading -> {
                val release = state.release
                Text(
                    text = state.progressPercent?.let {
                        stringResource(R.string.update_downloading, it)
                    } ?: stringResource(R.string.update_downloading, 0),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (state.progressPercent != null) {
                    LinearProgressIndicator(
                        progress = { state.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }

            is UpdateState.ReadyToInstall -> {
                val release = state.release
                Text(
                    text = stringResource(R.string.update_available, release.versionName),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { onInstall(state.apkPath) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.update_ready_to_install))
                }
            }

            is UpdateState.Error -> {
                Text(
                    text = state.message.ifBlank { stringResource(R.string.update_error) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(12.dp))
                FilledTonalButton(
                    onClick = onCheckForUpdate,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.update_check))
                }
            }
        }
    }
}
