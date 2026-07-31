package dev.leonardo.octether.data.api

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Network connectivity state.
 */
sealed class NetworkState {
    /** Network is connected and available. */
    data object Available : NetworkState()

    /** Network is about to be lost (grace period). */
    data object Losing : NetworkState()

    /** Network has been lost. */
    data object Lost : NetworkState()

    /** No network is available at all. */
    data object Unavailable : NetworkState()

    /** Convenience check for connected state. */
    val isOnline: Boolean
        get() = this is Available
}

/**
 * Monitors network connectivity state via [ConnectivityManager].
 *
 * Exposes a [StateFlow]<[NetworkState]> that can be observed by ViewModels
 * and services to react to network changes.
 */
@Singleton
class NetworkMonitor @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "NetworkMonitor"
    }
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _networkState = MutableStateFlow<NetworkState>(detectInitialState())

    /** Observable network state. */
    val networkState: StateFlow<NetworkState> = _networkState.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    /**
     * Start monitoring network changes. Call once during service/init.
     * Idempotent — calling multiple times is safe.
     */
    fun startMonitoring() {
        if (callback != null) return

        val networkRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "Network available")
                _networkState.value = NetworkState.Available
            }

            override fun onLosing(network: Network, maxMsToLive: Int) {
                _networkState.value = NetworkState.Losing
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "Network lost")
                _networkState.value = NetworkState.Lost
            }

            override fun onUnavailable() {
                Log.i(TAG, "Network unavailable")
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
                }
            }
        }

        connectivityManager.registerNetworkCallback(networkRequest, cb)
        callback = cb

        // Set initial state immediately
        _networkState.value = detectInitialState()
    }

    /**
     * Stop monitoring. Call during service teardown.
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
