import Foundation

/// The credential to present on a single WebSocket upgrade.
public enum GatewayCredential: Sendable {
    case ticket(String, headers: [String: String])
    case localToken(String, headers: [String: String])
}

/// Supplies a fresh WebSocket credential for every connection or reconnect.
public protocol HermesAuth: Sendable {
    func credential(baseURL: URL, http: any HTTPTransport) async throws -> GatewayCredential
    /// Called once per connection attempt after Hermes rejected its credential: the ticket request or the
    /// WebSocket upgrade answered 401 or 403 (`authenticationFailed`). Sign in again or refresh a token (or
    /// wait for another caller doing so) and return `true` to retry the attempt with a new credential;
    /// `false` makes the rejection final.
    func renew(after failure: HermesGatewayError) async throws -> Bool
}

public extension HermesAuth {
    func renew(after failure: HermesGatewayError) async throws -> Bool { false }
}

/// Authenticated dashboard ticket flow. A ticket is single-use and expires after 30 seconds.
public struct DashboardTicketAuth: HermesAuth {
    public let headers: @Sendable () async throws -> [String: String]

    public init(headers: @escaping @Sendable () async throws -> [String: String]) {
        self.headers = headers
    }

    public func credential(baseURL: URL, http: any HTTPTransport) async throws -> GatewayCredential {
        let headers = try await headers()
        guard let url = DashboardURL.components(base: baseURL, path: "/api/auth/ws-ticket")?.url else {
            throw HermesGatewayError.transport("Invalid dashboard URL")
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        let (data, response) = try await http.send(request)
        if response.statusCode == 401 || response.statusCode == 403 {
            throw HermesGatewayError.authenticationFailed("WebSocket ticket request returned HTTP \(response.statusCode)")
        }
        guard (200..<300).contains(response.statusCode) else {
            throw HermesGatewayError.transport("WebSocket ticket request returned HTTP \(response.statusCode)")
        }
        let ticket = try JSONDecoder().decode(TicketResponse.self, from: data).ticket
        guard !ticket.isEmpty else { throw HermesGatewayError.decoding("Empty WebSocket ticket") }
        return .ticket(ticket, headers: headers)
    }
}

private struct TicketResponse: Decodable {
    let ticket: String
}

/// The local dashboard's legacy session token. Gated dashboards reject this credential.
public struct LocalTokenAuth: HermesAuth {
    public let token: String
    public let headers: [String: String]

    public init(token: String, headers: [String: String] = [:]) {
        self.token = token
        self.headers = headers
    }

    public func credential(baseURL: URL, http: any HTTPTransport) async throws -> GatewayCredential {
        .localToken(token, headers: headers)
    }
}
