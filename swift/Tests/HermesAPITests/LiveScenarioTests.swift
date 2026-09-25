import Foundation
import HermesAPILiveScenarios
import Testing

/// Runs the harness live scenarios inside the test process, which on iOS is the simulator.
/// `harness/live.py --clients ios` sets these variables through `TEST_RUNNER_` forwarding.
@Suite struct LiveScenarioTests {
    @Test(.enabled(if: ProcessInfo.processInfo.environment["HERMES_LIVE_SCENARIOS"] == "1"))
    func taggedHermes() async throws {
        try await LiveScenarios.run(LiveScenarioEnvironment(ProcessInfo.processInfo.environment))
    }
}
