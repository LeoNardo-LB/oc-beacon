package dev.leonardo.ocbeacon.debug

import dev.leonardo.ocbeacon.BuildConfig
import dev.leonardo.ocbeacon.domain.model.DebugProfile

/**
 * #132 调试通道 —— 预置连接套餐。
 *
 * 仅在 debug 构建（[BuildConfig.DEBUG]）下生效：release 构建 [profiles] 恒为空，
 * Home 页入口不渲染，且密码字段为空字符串（buildConfigField 仅 debug 注入）。
 * 范围决策（用户确认）：dev flavor + 所有 debug buildType。
 *
 * 用法：
 * - UI：Home 页调试通道入口 → 点套餐 → [activateDebugProfile]
 * - 外部参数：`adb shell am start ... --es debug_profile <id>`（MainActivity 读取）
 */
object DebugChannel {

    val profiles: List<DebugProfile>
        get() = if (BuildConfig.DEBUG) builtinProfiles() else emptyList()

    fun find(id: String): DebugProfile? = profiles.firstOrNull { it.id == id }

    private fun builtinProfiles(): List<DebugProfile> {
        val pwd = BuildConfig.DEBUG_CHANNEL_PASSWORD
        return listOf(
            DebugProfile("v1real", "V1 真机", "http://192.168.110.53:4096", "opencode", pwd),
            DebugProfile("v2real", "V2 真机", "http://192.168.110.53:4199", "opencode", pwd),
            DebugProfile("v1emu", "V1 模拟器", "http://10.0.2.2:4096", "opencode", pwd),
            DebugProfile("v2emu", "V2 模拟器", "http://10.0.2.2:4199", "opencode", pwd),
        )
    }
}
