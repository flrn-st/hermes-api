import Foundation
import HermesAPI
import HermesAPILiveScenarios

@main
struct HermesAPICLI {
    static func main() async throws {
        let args = Array(CommandLine.arguments.dropFirst())
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

private extension LiveScenarioError {
    static var usage: LiveScenarioError { LiveScenarioError("usage: hermes-api-cli smoke --url <dashboard URL>") }
}
