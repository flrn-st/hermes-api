package hermes.api.android

import android.util.Log
import hermes.api.runtime.GatewayLogLevel
import hermes.api.runtime.GatewayLogger

/** Writes gateway diagnostics to Logcat under [tag]. */
public class LogcatGatewayLogger(private val tag: String = "HermesGateway") : GatewayLogger {
    override fun log(level: GatewayLogLevel, message: String) {
        when (level) {
            GatewayLogLevel.DEBUG -> Log.d(tag, message)
            GatewayLogLevel.INFO -> Log.i(tag, message)
            GatewayLogLevel.ERROR -> Log.e(tag, message)
        }
    }
}
