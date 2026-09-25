package hermes.api.android

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import hermes.api.runtime.GatewayNetworkMonitor
import hermes.api.runtime.GatewayNetworkPath

/**
 * Follows the system default network, the one new sockets use. A switch (Wi-Fi to cellular, or another
 * Wi-Fi) reports a new [GatewayNetworkPath.network], which makes the gateway reconnect at once instead of
 * waiting for its heartbeat. Losing every network pauses reconnect attempts until one returns.
 */
public class AndroidNetworkMonitor(context: Context) : GatewayNetworkMonitor {
    private val connectivity = checkNotNull(context.applicationContext.getSystemService(ConnectivityManager::class.java)) {
        "ConnectivityManager is unavailable"
    }

    override fun paths(): Flow<GatewayNetworkPath> = callbackFlow {
        var current: Network? = connectivity.activeNetwork
        trySend(path(current))
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                current = network
                trySend(path(network))
            }

            override fun onLost(network: Network) {
                // A replaced default network reports its successor through onAvailable first.
                if (network != current) return
                current = null
                trySend(path(null))
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.conflate().distinctUntilChanged()

    private fun path(network: Network?) = GatewayNetworkPath(isAvailable = network != null, network = network?.toString())
}
