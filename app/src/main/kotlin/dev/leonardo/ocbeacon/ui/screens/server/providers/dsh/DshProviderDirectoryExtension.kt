package dev.leonardo.ocbeacon.ui.screens.server.providers.dsh

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import dev.leonardo.ocbeacon.domain.model.ServerFeatures
import dev.leonardo.ocbeacon.domain.model.ServerUiSlot
import dev.leonardo.ocbeacon.ui.extension.ProviderSettingsSlotHost
import dev.leonardo.ocbeacon.ui.extension.ServerUiExtension
import dev.leonardo.ocbeacon.ui.extension.ServerUiSlotHost
import dev.leonardo.ocbeacon.ui.screens.server.DshCustomProvidersSection
import dev.leonardo.ocbeacon.ui.screens.server.ServerSettingsViewModel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DSH 提供商目录 / 自定义增删区块（#391 切片5 迁移自 ServerProvidersScreen 的
 * if (uiState.isDsh) 硬嵌块）。
 *
 * 挂载：PROVIDER_SETTINGS 槽位；启用条件 = 服务器设置特权面能力位（SERVER_SETTINGS），
 * 不读服务器类型——OpenCode 面端口缺席即不渲染。
 *
 * 宿主是通用提供商页的外层 LazyColumn（本贡献只返回一个 item 内容，不引入滚动容器）。
 */
@Singleton
class DshProviderDirectoryExtension @Inject constructor() : ServerUiExtension {

    override val slot: ServerUiSlot = ServerUiSlot.PROVIDER_SETTINGS

    override val order: Int = 100

    override fun isEnabled(caps: ServerCapabilities): Boolean =
        ServerFeatures.SERVER_SETTINGS in caps

    @Composable
    override fun Content(host: ServerUiSlotHost) {
        // 槽位契约：注册表保证宿主类型与本槽位匹配，不匹配显式失败
        require(host is ProviderSettingsSlotHost) {
            "PROVIDER_SETTINGS 槽位收到不匹配的宿主: " + host::class.simpleName
        }
        // 与通用提供商页共享同一 NavBackStackEntry 作用域的 ViewModel
        val viewModel: ServerSettingsViewModel = hiltViewModel()
        val uiState by viewModel.uiState.collectAsStateWithLifecycle()

        DshCustomProvidersSection(
            directory = uiState.dshDirectory,
            loading = uiState.dshDirectoryLoading,
            settingsBlocked = uiState.dshSettingsBlocked,
            error = uiState.dshProviderError,
            onRefresh = viewModel::loadDshProviderDirectory,
            onDiscover = viewModel::discoverDshModels,
            onCreate = viewModel::createDshCustomProvider,
            onDelete = viewModel::deleteDshCustomProvider,
        )
    }
}
