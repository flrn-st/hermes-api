import Foundation

/// Credentials for dashboard REST requests.
///
/// Hermes accepts three: the loopback dashboard's session token (`X-Hermes-Session-Token`), a native
/// app's OAuth access token (`Authorization: Bearer`), and browser session cookies (left to the
/// transport's cookie storage). `HermesREST` asks for headers before every attempt and, after a 401,
/// lets the credential renew itself once before it retries.
public protocol HermesRESTAuth: Sendable {
    /// Headers that authenticate the next request.
    func authorizationHeaders() async throws -> [String: String]
    /// Called once after a request answered 401. `rejected` holds the headers that request carried.
    /// Return `true` when the credential has changed since, and the request should be sent again.
    func renew(rejected: [String: String], response: RESTResponse) async throws -> Bool
}

public extension HermesRESTAuth {
    func renew(rejected: [String: String], response: RESTResponse) async throws -> Bool { false }
}

extension LocalTokenAuth: HermesRESTAuth {
    public func authorizationHeaders() async throws -> [String: String] {
        headers.merging(["X-Hermes-Session-Token": token]) { _, token in token }
    }
}

extension DashboardTicketAuth: HermesRESTAuth {
    public func authorizationHeaders() async throws -> [String: String] { try await headers() }
}

/// Fixed headers, for example a reverse proxy's credential on top of cookie sessions.
public struct StaticHeadersAuth: HermesRESTAuth, HermesAuth {
    public let values: [String: String]

    public init(_ values: [String: String]) { self.values = values }

    public func authorizationHeaders() async throws -> [String: String] { values }

    public func credential(baseURL: URL, http: any HTTPTransport) async throws -> GatewayCredential {
        try await DashboardTicketAuth(headers: { values }).credential(baseURL: baseURL, http: http)
    }
}

/// A native app's dashboard session: the Bearer tokens `POST /auth/native/token` issued.
///
/// The access token is refreshed through `POST /auth/native/refresh` when it is about to expire or a
/// request answers 401. Concurrent callers share one refresh, and `onRotate` receives every new token
/// pair so the app can persist it (Hermes rotates the refresh token on each use). It also authorizes
/// gateway connections, so one credential serves both surfaces.
public actor NativeSessionAuth: HermesRESTAuth, HermesAuth {
    public struct Tokens: Sendable, Hashable, Codable {
        public var accessToken: String
        public var refreshToken: String
        /// Unix seconds; `nil` when unknown.
        public var expiresAt: Int?
        public var provider: String

        public init(accessToken: String, refreshToken: String, expiresAt: Int? = nil, provider: String = "") {
            self.accessToken = accessToken
            self.refreshToken = refreshToken
            self.expiresAt = expiresAt
            self.provider = provider
        }
    }

    private var tokens: Tokens
    private var refreshing: Task<Tokens, any Error>?
    private let refresher: HermesREST
    private let onRotate: @Sendable (Tokens) async -> Void
    /// Refresh this long before `expiresAt`, so a request does not race the expiry.
    private let leeway: Int

    public init(baseURL: URL, tokens: Tokens, transport: any HTTPTransport = URLSessionHTTPTransport(),
                leeway: Duration = .seconds(30), onRotate: @escaping @Sendable (Tokens) async -> Void = { _ in }) {
        self.tokens = tokens
        // The refresh endpoint is public; the refresher itself carries no credential.
        refresher = HermesREST(configuration: .init(baseURL: baseURL, transport: transport))
        self.onRotate = onRotate
        self.leeway = Int(leeway.components.seconds)
    }

    public var current: Tokens { tokens }

    public func authorizationHeaders() async throws -> [String: String] {
        if let expiresAt = tokens.expiresAt, Int(Date().timeIntervalSince1970) + leeway >= expiresAt {
            _ = try await refresh(replacing: tokens.accessToken)
        }
        return ["Authorization": "Bearer \(tokens.accessToken)"]
    }

    public func renew(rejected: [String: String], response: RESTResponse) async throws -> Bool {
        guard let header = rejected["Authorization"], header.hasPrefix("Bearer ") else { return false }
        let token = String(header.dropFirst("Bearer ".count))
        return try await refresh(replacing: token).accessToken != token
    }

    public func credential(baseURL: URL, http: any HTTPTransport) async throws -> GatewayCredential {
        do {
            return try await DashboardTicketAuth(headers: { try await self.authorizationHeaders() }).credential(baseURL: baseURL, http: http)
        } catch HermesGatewayError.authenticationFailed {
            _ = try await refresh(replacing: tokens.accessToken)
            return try await DashboardTicketAuth(headers: { try await self.authorizationHeaders() }).credential(baseURL: baseURL, http: http)
        }
    }

    /// Refreshes unless another caller already replaced `rejected`; every caller waits for the same refresh.
    private func refresh(replacing rejected: String) async throws -> Tokens {
        if tokens.accessToken != rejected { return tokens }
        if let refreshing { return try await refreshing.value }
        let task = Task { [refresher, tokens] in
            let body = _NativeRefreshBody(provider: tokens.provider.isEmpty ? nil : tokens.provider,
                                          refreshToken: tokens.refreshToken)
            let issued = try await refresher.web.authNativeRefresh(body: body)
            return Tokens(accessToken: issued.accessToken, refreshToken: issued.refreshToken,
                          expiresAt: issued.expiresAt, provider: issued.provider)
        }
        refreshing = task
        defer { refreshing = nil }
        let fresh = try await task.value
        tokens = fresh
        await onRotate(fresh)
        return fresh
    }
}
