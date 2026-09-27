package hermes.api

import java.nio.file.Path
import kotlin.system.exitProcess
import hermes.api.live.Benchmarks

/** Invoked by the Gradle bench task: prints each benchmark and fails when one exceeds its budget. */
fun main(args: Array<String>) {
    val results = Benchmarks.run(Path.of(args.firstOrNull() ?: "../fixtures/${System.getenv("HERMES_RELEASE") ?: "v0.21.5"}/liveness.jsonl"))
    var failed = false
    for ((name, value) in results.toSortedMap()) {
        val budget = Benchmarks.budgets[name] ?: 0.0
        println("%-40s %10.2f ms  (budget %.0f)".format(name, value, budget))
        failed = failed || value > budget
    }
    if (failed) exitProcess(1)
}
