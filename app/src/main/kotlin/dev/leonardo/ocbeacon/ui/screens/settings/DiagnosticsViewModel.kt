package dev.leonardo.ocbeacon.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.data.github.DeviceCodeRequest
import dev.leonardo.ocbeacon.data.github.DeviceFlowResult
import dev.leonardo.ocbeacon.data.github.ErrorReportService
import dev.leonardo.ocbeacon.data.github.GitHubApiError
import dev.leonardo.ocbeacon.data.github.GitHubDeviceFlowAuth
import dev.leonardo.ocbeacon.data.github.GitHubTokenStore
import dev.leonardo.ocbeacon.data.github.ReportEnvironment
import dev.leonardo.ocbeacon.data.github.ReportLogEntry
import dev.leonardo.ocbeacon.data.repository.DiagnosticLogEntry
import dev.leonardo.ocbeacon.data.repository.DiagnosticLogRepository
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** #151 上报状态机（spec §失败处理：401 引导重授权 / 限流提示 / 草稿保留）。 */
sealed class ReportUiState {
    object Idle : ReportUiState()
    object NeedsGitHubAppConfig : ReportUiState()
    data class Authorizing(val code: DeviceCodeRequest) : ReportUiState()
    data class Preview(val body: String, val fingerprint: String) : ReportUiState()
    object Submitting : ReportUiState()
    data class Done(val message: String) : ReportUiState()
    data class Failed(val message: String, val retryableBody: String?, val needsAuth: Boolean) : ReportUiState()
}

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val repository: DiagnosticLogRepository,
    private val reportService: ErrorReportService,
    private val deviceFlowAuth: GitHubDeviceFlowAuth,
    private val tokenStore: GitHubTokenStore,
) : ViewModel() {

    val entries: StateFlow<List<DiagnosticLogEntry>> = repository.entries.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        emptyList(),
    )

    val logLevel: StateFlow<String> = repository.logLevel.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        "INFO",
    )

    suspend fun export(): String {
        AppLogger.flush()
        return DiagnosticLogRepository.export(entries.value)
    }

    fun droppedEntryCount(): Long = AppLogger.droppedEntryCount()

    fun clear() {
        viewModelScope.launch { repository.clear() }
    }

    fun setLogLevel(level: String) {
        viewModelScope.launch { repository.setLogLevel(level) }
    }

    // ============ GitHub 上报（#151） ============

    private val _reportState = MutableStateFlow<ReportUiState>(ReportUiState.Idle)
    val reportState: StateFlow<ReportUiState> = _reportState.asStateFlow()

    private var pollJob: Job? = null

    val hasToken: StateFlow<Boolean> = tokenStore.hasToken
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /** 入口：无 token → device flow；有 token → 直接构建预览。 */
    fun startReport() {
        val clientId = dev.leonardo.ocbeacon.BuildConfig.GITHUB_APP_CLIENT_ID
        val clientSecret = dev.leonardo.ocbeacon.BuildConfig.GITHUB_APP_CLIENT_SECRET
        if (clientId.isBlank() || clientSecret.isBlank()) {
            _reportState.value = ReportUiState.NeedsGitHubAppConfig
            return
        }
        viewModelScope.launch {
            if (tokenStore.loadToken() != null) buildPreview()
            else beginDeviceFlow(clientId, clientSecret)
        }
    }

    private suspend fun beginDeviceFlow(clientId: String, clientSecret: String) {
        deviceFlowAuth.requestDeviceCode(clientId, clientSecret).onSuccess { code ->
            _reportState.value = ReportUiState.Authorizing(code)
            pollToken(clientId, clientSecret, code)
        }.onFailure {
            _reportState.value = ReportUiState.Failed(it.message ?: "授权请求失败", null, false)
        }
    }

    private fun pollToken(clientId: String, clientSecret: String, code: DeviceCodeRequest) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            var interval = code.intervalSeconds.toLong().coerceAtLeast(1)
            val deadline = System.currentTimeMillis() + code.expiresInSeconds * 1000L
            while (System.currentTimeMillis() < deadline) {
                delay(interval * 1000)
                when (val r = deviceFlowAuth.pollToken(clientId, clientSecret, code.deviceCode).getOrNull()) {
                    is DeviceFlowResult.Success -> {
                        tokenStore.saveToken(r.accessToken)
                        buildPreview()
                        return@launch
                    }
                    DeviceFlowResult.Pending -> Unit
                    is DeviceFlowResult.Failed -> {
                        _reportState.value = ReportUiState.Failed("授权失败：${r.reason}", null, false)
                        return@launch
                    }
                    null -> return@launch
                }
            }
        }
    }

    fun cancelAuthorization() {
        pollJob?.cancel()
        _reportState.value = ReportUiState.Idle
    }

    /** 构建预览（spec：复用脱敏管道导出 → 机器块 + 日志段组装）。 */
    private suspend fun buildPreview() {
        AppLogger.flush()
        val errorEntries = entries.value.filter { it.level in ErrorReportService.ERROR_LEVELS }
        if (errorEntries.isEmpty()) {
            _reportState.value = ReportUiState.Failed("无错误日志可上报", null, false)
            return
        }
        val latest = errorEntries.last()
        val fingerprint = reportService.fingerprintForError(latest.category, latest.message)
        val env = ReportEnvironment(
            deviceModel = android.os.Build.MODEL,
            androidVersion = android.os.Build.VERSION.RELEASE ?: "?",
            sdkInt = android.os.Build.VERSION.SDK_INT,
            appVersion = dev.leonardo.ocbeacon.BuildConfig.VERSION_NAME,
            flavor = dev.leonardo.ocbeacon.BuildConfig.FLAVOR,
            locale = java.util.Locale.getDefault().toLanguageTag(),
            runtimeMaxMemoryMb = Runtime.getRuntime().maxMemory() / (1024 * 1024),
        )
        val logSection = reportService.buildLogSection(
            entries.value.map { ReportLogEntry(it.timestamp, it.level, it.category, it.message) }
        )
        val installId = tokenStore.installId()
        val body = reportService.machineBlock(fingerprint, env, installId) +
            "\n## 错误日志（最近 ERROR/FATAL + 上下文）\n\n```\n" + logSection + "\n```\n" +
            "\n---\n_由 OC Beacon 用户通过 Diagnostics 屏手动上报；内容已经过脱敏管道。_"
        _reportState.value = ReportUiState.Preview(body, fingerprint)
    }

    /** 预览可编辑：编辑结果回传。 */
    fun updatePreviewBody(body: String) {
        val cur = _reportState.value
        if (cur is ReportUiState.Preview) _reportState.value = cur.copy(body = body)
    }

    fun submitReport() {
        val cur = _reportState.value
        if (cur !is ReportUiState.Preview) return
        _reportState.value = ReportUiState.Submitting
        viewModelScope.launch {
            reportService.report(
                fingerprint = cur.fingerprint,
                issueTitle = "错误上报",
                issueBody = cur.body,
                commentBody = cur.body,
            ).onSuccess { outcome ->
                _reportState.value = ReportUiState.Done(when (outcome) {
                    is ErrorReportService.Outcome.IssueCreated -> "已创建 issue #" + outcome.number + "\n" + outcome.url
                    is ErrorReportService.Outcome.Commented -> "已追加到 issue #" + outcome.number
                    ErrorReportService.Outcome.SuppressedDuplicate -> "24 小时内已上报过同一错误，本次静默跳过"
                })
            }.onFailure { e ->
                val needsAuth = e is GitHubApiError.Unauthorized
                _reportState.value = ReportUiState.Failed(
                    message = when {
                        e is GitHubApiError.Unauthorized -> "授权已失效，请重新授权"
                        e is GitHubApiError.RateLimited -> "GitHub 限流，请稍后再试"
                        else -> e.message ?: "上报失败"
                    },
                    retryableBody = cur.body,
                    needsAuth = needsAuth,
                )
            }
        }
    }

    /** 失败重试（spec：草稿保留 + 一键重试）。 */
    fun retrySubmit() {
        val cur = _reportState.value
        if (cur !is ReportUiState.Failed) return
        if (cur.needsAuth) { startReport(); return }
        cur.retryableBody?.let { body ->
            _reportState.value = ReportUiState.Preview(body, "")
            // fingerprint 丢失的边界：needsAuth=false 的重试走原文重建不可行——重新开始
            startReport()
        } ?: startReport()
    }

    fun dismissReport() {
        pollJob?.cancel()
        _reportState.value = ReportUiState.Idle
    }
}
