import Foundation
import HermesAPI

@main
struct HermesAPICLI {
    static func main() async throws {
        let args = Array(CommandLine.arguments.dropFirst())
        guard args.count == 3, args[0] == "smoke", args[1] == "--url",
              let url = URL(string: args[2]),
              let token = ProcessInfo.processInfo.environment["HERMES_LIVE_TOKEN"], !token.isEmpty else {
            throw CLIError.usage
        }
        let gateway = HermesGateway(configuration: .init(baseURL: url, auth: LocalTokenAuth(token: token)))
        do {
            try await gateway.connect()
            let result = try await gateway.ping(PingParams())
            guard result.pong else { throw CLIError.pingFailed }
            FileHandle.standardOutput.write(Data("Hermes \(HermesAPI.hermesRelease) gateway ping passed\n".utf8))
            await gateway.disconnect()
        } catch {
            await gateway.disconnect()
            throw error
        }
    }
}

private enum CLIError: Error {
    case usage
    case pingFailed
}
