package st.flrn.hermes.api.android

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import st.flrn.hermes.api.generated.gateway.PingParams
import st.flrn.hermes.api.live.LiveScenarioEnvironment
import st.flrn.hermes.api.live.LiveScenarios
import st.flrn.hermes.api.runtime.GatewayConnectionState
import st.flrn.hermes.api.runtime.GatewayNetworkPath
import st.flrn.hermes.api.runtime.HermesGateway
import st.flrn.hermes.api.runtime.HermesGatewayConfiguration
import st.flrn.hermes.api.runtime.KtorGatewayTransport

/** Runs the harness live scenarios on the device with the Android adapters. `harness/live.py --clients
 *  android` passes the `HERMES_LIVE_*` values as instrumentation arguments. */
@RunWith(AndroidJUnit4::class)
class LiveScenarioInstrumentedTest {
    @Test
    fun taggedHermes() {
        val environment = environment()
        // Scenarios wait on real sockets and server restarts, so they run off the virtual-time dispatcher.
        runTest(timeout = 5.minutes) {
            withContext(Dispatchers.Default) { LiveScenarios.run(environment) }
        }
    }

    /** Airplane mode takes the default network away. The gateway must stop retrying until the network
     *  returns, then reconnect on its own. The harness socket runs over the device loopback (adb reverse),
     *  so only the network callback, not the socket itself, sees the outage. */
    @Test
    fun networkLossWaitsAndRecovers() {
        val environment = environment()
        runTest(timeout = 3.minutes) {
            withContext(Dispatchers.Default) {
                val transport = KtorGatewayTransport()
                val gateway = HermesGateway(HermesGatewayConfiguration(
                    environment.url, environment.auth, transport,
                    networkMonitor = environment.networkMonitor, logger = environment.logger,
                ))
                try {
                    gateway.connect()
                    airplaneMode(true)
                    withTimeout(30_000) { gateway.connectionStates.first { it == GatewayConnectionState.WaitingForNetwork } }
                    airplaneMode(false)
                    withTimeout(90_000) { gateway.connectionStates.first { it == GatewayConnectionState.Connected } }
                    check(gateway.methods.ping(PingParams()).pong) { "Ping failed after the network returned" }
                } finally {
                    airplaneMode(false)
                    gateway.disconnect()
                    transport.close()
                    awaitStableNetwork(environment)
                }
            }
        }
    }

    private fun environment(): LiveScenarioEnvironment {
        val arguments = InstrumentationRegistry.getArguments()
        val values = ARGUMENTS.mapNotNull { (argument, variable) -> arguments.getString(argument)?.let { variable to it } }
            .toMap()
        assumeTrue("No live Hermes server was provided", "HERMES_LIVE_URL" in values)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return LiveScenarioEnvironment(values, AndroidNetworkMonitor(context), LogcatGatewayLogger())
    }

    /** After airplane mode the default network settles in stages (cellular, then Wi-Fi), and every switch
     *  rightly reconnects the gateway. Later scenarios need a network that has stopped changing. */
    private suspend fun awaitStableNetwork(environment: LiveScenarioEnvironment) {
        val monitor = checkNotNull(environment.networkMonitor)
        withTimeout(90_000) {
            coroutineScope {
                val latest = MutableStateFlow<Pair<GatewayNetworkPath, Long>?>(null)
                val watcher = launch { monitor.paths().collect { latest.value = it to System.nanoTime() } }
                while (true) {
                    val (path, since) = latest.value ?: (null to System.nanoTime())
                    if (path?.isAvailable == true && System.nanoTime() - since >= STABLE_NETWORK_NANOS) break
                    delay(250)
                }
                watcher.cancel()
            }
        }
    }

    private fun airplaneMode(enabled: Boolean) {
        val command = "cmd connectivity airplane-mode ${if (enabled) "enable" else "disable"}"
        val output = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        // Reading to the end waits for the command to finish.
        ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
    }

    private companion object {
        const val STABLE_NETWORK_NANOS = 5_000_000_000L
        val ARGUMENTS = mapOf(
            "hermesUrl" to "HERMES_LIVE_URL",
            "hermesToken" to "HERMES_LIVE_TOKEN",
            "hermesLifecycle" to "HERMES_LIVE_LIFECYCLE",
            "hermesControl" to "HERMES_LIVE_CONTROL",
        )
    }
}
