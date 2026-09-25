package hermes.api

import kotlin.system.exitProcess
import hermes.api.live.LiveScenarioEnvironment
import hermes.api.live.LiveScenarios

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
