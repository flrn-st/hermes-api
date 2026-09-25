package st.flrn.hermes.api.android

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import st.flrn.hermes.api.runtime.HermesGateway

/**
 * Suspends the gateway while no activity is visible and resumes it when one is. In the background
 * Android's app standby and Doze cut the network anyway; closing the socket first saves the radio, stops
 * the heartbeat and retries, and lets Hermes keep the turn running and replay it on return.
 *
 * Bind once, for example in `Application.onCreate`: `gateway.bindToProcessLifecycle()`.
 */
public class GatewayLifecycleObserver(
    private val gateway: HermesGateway,
    private val lifecycle: Lifecycle,
    /** How long a streaming turn may keep the socket open after the app leaves the foreground. */
    private val backgroundGraceMillis: Long = 25_000,
) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        owner.lifecycleScope.launch { gateway.enterForeground() }
    }

    override fun onStop(owner: LifecycleOwner) {
        owner.lifecycleScope.launch { gateway.enterBackground(backgroundGraceMillis) }
    }

    public fun unbind() {
        lifecycle.removeObserver(this)
    }
}

/** Follows the whole app's foreground state through [ProcessLifecycleOwner]. Call on the main thread. */
public fun HermesGateway.bindToProcessLifecycle(backgroundGraceMillis: Long = 25_000): GatewayLifecycleObserver {
    val lifecycle = ProcessLifecycleOwner.get().lifecycle
    return GatewayLifecycleObserver(this, lifecycle, backgroundGraceMillis).also(lifecycle::addObserver)
}
