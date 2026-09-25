package st.flrn.hermes.api

import kotlin.system.exitProcess
import st.flrn.hermes.api.live.LiveScenarioEnvironment
import st.flrn.hermes.api.live.LiveScenarios

/** Invoked by the dedicated Gradle smoke task against a tagged Hermes server. */
suspend fun main() {
    try {
        LiveScenarios.run(LiveScenarioEnvironment(System.getenv()))
    } catch (error: Exception) {
        System.err.write("Live scenario failed: $error\n".toByteArray())
        exitProcess(1)
    }
    System.out.write("Hermes ${HermesAPI.hermesRelease} gateway live scenarios passed\n".toByteArray())
}
