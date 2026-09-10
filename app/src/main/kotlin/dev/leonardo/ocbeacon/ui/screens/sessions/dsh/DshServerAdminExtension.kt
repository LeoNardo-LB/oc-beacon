package dev.leonardo.ocbeacon.ui.screens.sessions.dsh

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import dev.leonardo.ocbeacon.domain.model.ServerFeatures
import dev.leonardo.ocbeacon.domain.model.ServerUiSlot
import dev.leonardo.ocbeacon.ui.extension.ServerSettingsSlotHost
import dev.leonardo.ocbeacon.ui.extension.ServerUiExtension
import dev.leonardo.ocbeacon.ui.extension.ServerUiSlotHost
import dev.leonardo.ocbeacon.ui.screens.sessions.SessionListViewModel
import dev.leonardo.ocbeacon.ui.screens.sessions.components.DshPluginInventorySection
import dev.leonardo.ocbeacon.ui.screens.sessions.components.DshServerConfigSection
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DSH 服务器配置表单 + 插件清单区块（#391 切片9 迁移自 ServerSettingsContent 的
 * 硬嵌 item 块）。
 *
 * 挂载：SERVER_SETTINGS 槽位；启用条件 = 服务器设置特权面能力位（SERVER_SETTINGS），
 * 不读服务器类型。宿主是会话列表设置页的外层 LazyColumn，本贡献只返回 item 内容。
 */
@Singleton
class DshServerAdminExtension @Inject constructor() : ServerUiExtension {

    override val slot: ServerUiSlot = ServerUiSlot.SERVER_SETTINGS

    override val order: Int = 100

    override fun isEnabled(caps: ServerCapabilities): Boolean =
        ServerFeatures.SERVER_SETTINGS in caps

    @Composable
    override fun Content(host: ServerUiSlotHost) {
        require(host is ServerSettingsSlotHost) {
            "SERVER_SETTINGS 槽位收到不匹配的宿主: " + host::class.simpleName
        }
        // 与会话列表设置页共享同一 NavBackStackEntry 作用域的 ViewModel
        val viewModel: SessionListViewModel = hiltViewModel()
        val inventory by viewModel.pluginInventory.collectAsStateWithLifecycle()
        val forms by viewModel.settingsForms.collectAsStateWithLifecycle()
        val blocked by viewModel.settingsFormsBlocked.collectAsStateWithLifecycle()

        Column(modifier = Modifier.fillMaxWidth()) {
            DshServerConfigSection(
                forms = forms,
                blocked = blocked,
                onSaveField = viewModel::saveSettingField,
                onSaveSecret = viewModel::saveSecretField,
            )
            DshPluginInventorySection(inventory = inventory)
        }
    }
}
