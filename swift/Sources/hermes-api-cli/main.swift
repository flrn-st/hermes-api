import Foundation
import HermesAPI

@main
struct HermesAPICLI {
    static func main() async throws {
        let args = Array(CommandLine.arguments.dropFirst())
        guard args.count == 3, args[0] == "smoke", args[1] == "--url",
              let url = URL(string: args[2]) else {
            throw CLIError.usage
        }
        let environment = ProcessInfo.processInfo.environment
        let auth: any HermesAuth
        if let ticket = environment["HERMES_LIVE_TICKET"], !ticket.isEmpty {
            auth = StaticTicketAuth(ticket: ticket)
        } else if let token = environment["HERMES_LIVE_TOKEN"], !token.isEmpty {
            auth = LocalTokenAuth(token: token)
        } else {
            throw CLIError.usage
        }
        let gateway = HermesGateway(configuration: .init(baseURL: url, auth: auth))
        do {
            try await gateway.connect()
            let result = try await gateway.ping(PingParams())
            guard result.pong else { throw CLIError.pingFailed }
            _ = try await gateway.gateway.capabilities(PingParams())
            FileHandle.standardOutput.write(Data("Hermes \(HermesAPI.hermesRelease) gateway ping passed\n".utf8))
            await gateway.disconnect()
        } catch {
            await gateway.disconnect()
            throw error
        }
    }
}

private struct StaticTicketAuth: HermesAuth {
    let ticket: String

    func credential(baseURL: URL, http: any HTTPTransport) async throws -> GatewayCredential {
        .ticket(ticket, headers: [:])
    }
}

private enum CLIError: Error {
    case usage
    case pingFailed
}
