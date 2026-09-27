import Foundation
import HermesAPI
import HermesAPILiveScenarios

@main
struct HermesAPICLI {
    static func main() async throws {
        let args = Array(CommandLine.arguments.dropFirst())
        if args.count == 3, args[0] == "bench", args[1] == "--fixture" {
            try bench(fixture: URL(fileURLWithPath: args[2]))
            return
        }
        guard args.count == 3, args[0] == "smoke", args[1] == "--url" else {
            throw LiveScenarioError.usage
        }
        var values = ProcessInfo.processInfo.environment
        values["HERMES_LIVE_URL"] = args[2]
        do {
            try await LiveScenarios.run(LiveScenarioEnvironment(values))
        } catch {
            FileHandle.standardError.write(Data("Live scenario failed: \(error)\n".utf8))
            exit(1)
        }
        FileHandle.standardOutput.write(Data("Hermes \(HermesAPI.hermesRelease) gateway live scenarios passed\n".utf8))
    }
}

/// Prints each benchmark in milliseconds and fails when one exceeds its budget.
private func bench(fixture: URL) throws {
    let results = try Benchmarks.run(fixture: fixture)
    var failed = false
    for (name, value) in results.sorted(by: { $0.key < $1.key }) {
        let budget = Benchmarks.budgets[name] ?? 0
        print("\(name.padding(toLength: 40, withPad: " ", startingAt: 0)) \(String(format: "%10.2f", value)) ms  (budget \(Int(budget)))")
        failed = failed || value > budget
    }
    if failed { exit(1) }
}

private extension LiveScenarioError {
    static var usage: LiveScenarioError { LiveScenarioError("usage: hermes-api-cli smoke --url <dashboard URL> | bench --fixture <liveness.jsonl>") }
}
