package dev.leonardo.ocbeacon.data.api

import dev.leonardo.ocbeacon.logging.AppLogger

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 网络连接状态。
 */
sealed class NetworkState {
    /** 网络已连接且可用。 */
    data object Available : NetworkState()

    /** 网络即将丢失（宽限期）。 */
    data object Losing : NetworkState()

    /** 网络已丢失。 */
    data object Lost : NetworkState()

    /** 完全没有可用网络。 */
    data object Unavailable : NetworkState()

    /** 是否处于已连接状态的便捷判断。 */
    val isOnline: Boolean
        get() = this is Available
}

/**
 * 通过 [ConnectivityManager] 监控网络连接状态。
 *
 * 暴露一个可被 ViewModel 和服务观察、以响应网络变化的
 * [StateFlow]<[NetworkState]>。
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @param:dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "NetworkMonitor"
    }
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkState = MutableStateFlow<NetworkState>(detectInitialState())

    /** 可观察的网络状态。 */
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * 开始监控网络变化。在 service/init 期间调用一次。
     * 幂等——多次调用是安全的。
     */
    fun startMonitoring() {
        if (callback != null) return

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                AppLogger.i(TAG, "Network available")
                _networkState.value = NetworkState.Available
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                _networkState.value = NetworkState.Losing
            }

            override fun onLost(network: Network) {
                AppLogger.w(TAG, "Network lost")
                _networkState.value = NetworkState.Lost
            }

            override fun onUnavailable() {
                AppLogger.i(TAG, "Network unavailable")
                _networkState.value = NetworkState.Unavailable
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val hasInternet = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_INTERNET
                )
                val validated = networkCapabilities.hasCapability(
                    NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )
                if (hasInternet && validated) {
                    _networkState.value = NetworkState.Available
                } else if (!validated) {
                    // #133（D2-L41）：失去 VALIDATED（captive portal / 认证墙）——
                    // 网络名义可用但请求会被劫持/失败。原实现只处理 validated 分支，
                    // 失去验证后状态卡在旧值（Available）→ 连接层误判在线。
                    _networkState.value = NetworkState.Unavailable
                }
            }
        }

        connectivityManager.registerNetworkCallback(networkRequest, cb)
        callback = cb

        // 立即设置初始状态
        _networkState.value = detectInitialState()
    }

    /**
     * 停止监控。在 service 销毁时调用。
     */
    fun stopMonitoring() {
        callback?.let { connectivityManager.unregisterNetworkCallback(it) }
        callback = null
    }

    private fun detectInitialState(): NetworkState {
        val activeNetwork = connectivityManager.activeNetwork ?: return NetworkState.Unavailable
        val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkState.Unavailable
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return if (hasInternet && validated) NetworkState.Available else NetworkState.Unavailable
    }
}
