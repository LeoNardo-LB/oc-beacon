package dev.leonardo.ocbeacon.ui.screens.sessions.dsh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import dev.leonardo.ocbeacon.domain.model.ServerFeatures
import dev.leonardo.ocbeacon.domain.model.ServerUiSlot
import dev.leonardo.ocbeacon.ui.components.dsh.DshTokenDialog
import dev.leonardo.ocbeacon.ui.components.dsh.DshTokenNeededBanner
import dev.leonardo.ocbeacon.ui.extension.SessionListHeaderSlotHost
import dev.leonardo.ocbeacon.ui.extension.ServerUiExtension
import dev.leonardo.ocbeacon.ui.extension.ServerUiSlotHost
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DSH token 待输入横幅 + 凭据录入对话框（#391 切片9）。
 *
 * 挂载：SESSION_LIST_HEADER 槽位；DSH 私有状态（待输入 / 交换结果）由同作用域的
 * [DshTokenEntryViewModel] 承载，通用屏幕不再持有 token 交换状态或对话框。
 */
@Singleton
class DshTokenBannerExtension @Inject constructor() : ServerUiExtension {

    override val slot: ServerUiSlot = ServerUiSlot.SESSION_LIST_HEADER

    override val order: Int = 100

    /** token/cookie 凭据式鉴权（OpenCode 基本面认证不走此插槽）。 */
    override fun isEnabled(caps: ServerCapabilities): Boolean =
        ServerFeatures.AUTH_TOKEN in caps

    @Composable
    override fun Content(host: ServerUiSlotHost) {
        require(host is SessionListHeaderSlotHost) {
            "SESSION_LIST_HEADER 槽位收到不匹配的宿主: " + host::class.simpleName
        }
        val viewModel: DshTokenEntryViewModel = hiltViewModel()
        val tokenNeeded by viewModel.tokenNeeded.collectAsStateWithLifecycle()
        val exchange by viewModel.exchange.collectAsStateWithLifecycle()

        var showDialog by remember { mutableStateOf(false) }
        var exchangePending by remember { mutableStateOf(false) }
        // 交换成功（Exchanging → Idle）自动关窗；Rejected 留窗示错（原 SessionListScreen 语义）
        LaunchedEffect(exchange) {
            when (exchange) {
                DshTokenEntryViewModel.ExchangeState.Exchanging -> exchangePending = true
                DshTokenEntryViewModel.ExchangeState.Idle ->
                    if (exchangePending) {
                        exchangePending = false
                        showDialog = false
                    }
                DshTokenEntryViewModel.ExchangeState.Rejected -> Unit
            }
        }

        if (tokenNeeded) {
            DshTokenNeededBanner(onEnterToken = { showDialog = true })
        }
        if (showDialog) {
            DshTokenDialog(
                exchanging = exchange == DshTokenEntryViewModel.ExchangeState.Exchanging,
                rejected = exchange == DshTokenEntryViewModel.ExchangeState.Rejected,
                onSubmit = viewModel::submit,
                onDismiss = {
                    viewModel.dismiss()
                    showDialog = false
                },
            )
        }
    }
}
