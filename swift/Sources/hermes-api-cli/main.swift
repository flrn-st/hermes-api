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
            if environment["HERMES_LIVE_LIFECYCLE"] == "1" {
                let session = try await gateway.session.create(.init(
                    cwd: .value("/tmp"), title: .value("HermesAPI live session"),
                    closeOnDisconnect: true))
                let submission = try await gateway.prompt.submit(.init(
                    sessionId: session.sessionId,
                    text: .string("Reply with a short greeting.")))
                guard submission.status != nil else { throw CLIError.turnFailed }
                let reply = try await waitForFixtureReply(
                    events: gateway.events, sessionID: session.sessionId)
                guard reply == "HermesAPI fixture reply." else { throw CLIError.turnFailed }
                _ = try await gateway.session.list(.init())
                let closed = try await gateway.session.close(.init(sessionId: session.sessionId))
                guard closed.closed else { throw CLIError.sessionCloseFailed }
                guard let token = environment["HERMES_LIVE_TOKEN"] else { throw CLIError.usage }
                let rest = HermesREST(configuration: .init(
                    baseURL: url, headers: { ["X-Hermes-Session-Token": token] }))
                let count = try await rest.sessions.emptyCount(profile: "default")
                guard count.count >= 0 else { throw CLIError.invalidRESTCount }
            }
            FileHandle.standardOutput.write(Data("Hermes \(HermesAPI.hermesRelease) gateway ping passed\n".utf8))
            await gateway.disconnect()
        } catch {
            await gateway.disconnect()
            throw error
        }
    }

    private static func waitForFixtureReply(
        events: AsyncStream<GatewayEvent>, sessionID: String
    ) async throws -> String {
        try await withThrowingTaskGroup(of: String.self) { group in
            group.addTask {
                var sawStart = false
                var streamed = ""
                for await event in events where event.sessionID == sessionID {
                    switch event.payload {
                    case .messageStart:
                        sawStart = true
                    case .messageDelta(let payload):
                        streamed += payload.text
                    case .messageComplete(let payload):
                        if case .string(let text)? = payload.text,
                           sawStart, streamed == text { return text }
                        throw CLIError.turnFailed
                    case .error:
                        throw CLIError.turnFailed
                    default:
                        continue
                    }
                }
                throw CLIError.turnFailed
            }
            group.addTask {
                try await Task.sleep(for: .seconds(30))
                throw CLIError.turnTimedOut
            }
            defer { group.cancelAll() }
            guard let first = try await group.next() else { throw CLIError.turnFailed }
            return first
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
    case sessionCloseFailed
    case turnFailed
    case turnTimedOut
    case invalidRESTCount
}
