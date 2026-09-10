package dev.leonardo.ocbeacon.ui.screens.sessions.dsh

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.leonardo.ocbeacon.data.api.dsh.DshConnectionRegistry
import dev.leonardo.ocbeacon.data.api.dsh.extractDshToken
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.repository.ServerRepository
import dev.leonardo.ocbeacon.service.SseConnectionManager
import dev.leonardo.ocbeacon.ui.navigation.routes.safeDecodeParam
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DSH token 录入 VM（#391 切片9）——从共享 SessionListViewModel 下沉的 DSH 私有状态。
 *
 * 只承载 DSH 0.1.2 token 待输入 / 交换结果；由 DshTokenBannerExtension 在
 * SESSION_LIST_HEADER 槽位消费（hiltViewModel 取同一 NavBackStackEntry 作用域实例）。
 */
@HiltViewModel
class DshTokenEntryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sseConnectionManager: SseConnectionManager,
    private val dshConnectionRegistry: DshConnectionRegistry,
    private val serverRepository: ServerRepository,
) : ViewModel() {

    /** token 交换结果态（对话框消费；Idle=初始或成功——成功即连接循环自动续行）。 */
    sealed interface ExchangeState {
        data object Idle : ExchangeState
        data object Exchanging : ExchangeState
        data object Rejected : ExchangeState
    }

    private val serverId: String = safeDecodeParam(savedStateHandle.get<String>("serverId") ?: "")

    /** 本服务器是否等待 token 输入（探测双形态 401 时置位）。 */
    val tokenNeeded: StateFlow<Boolean> = sseConnectionManager.dshTokenNeededServers
        .map { needed -> serverId in needed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _exchange = MutableStateFlow<ExchangeState>(ExchangeState.Idle)
    val exchange: StateFlow<ExchangeState> = _exchange.asStateFlow()

    /** 用户提交粘贴内容（URL/启动行/裸 token 三形态）→ 解析 → 交换。 */
    fun submit(raw: String) {
        val token = extractDshToken(raw)
        if (token == null) {
            _exchange.value = ExchangeState.Rejected
            return
        }
        _exchange.value = ExchangeState.Exchanging
        viewModelScope.launch {
            val base = serverRepository.getServer(serverId)
                ?.let { ServerConnection.from(it).baseUrl }
                ?.takeIf { it.isNotBlank() }
            if (base == null) {
                _exchange.value = ExchangeState.Rejected
                return@launch
            }
            val ok = dshConnectionRegistry.exchangeToken(base, token)
            _exchange.value = if (ok) ExchangeState.Idle else ExchangeState.Rejected
            // ok=true：cookie 入注册表 → 连接循环 awaitCookie 恢复（自动续行，无需手动重连）
        }
    }

    /** 关闭对话框重置错误态（再次打开不携带上轮拒绝）。 */
    fun dismiss() {
        _exchange.value = ExchangeState.Idle
    }
}
