import Foundation

/// A dashboard session from a password provider (`POST /auth/password-login`, such as Hermes' bundled `basic`
/// provider). Hermes answers with a session cookie, which `transport`'s cookie storage then carries on every
/// request, so give the gateway and REST configurations this same transport.
///
/// The session is signed in again when Hermes rejects it (a REST 401, or a rejected gateway ticket or upgrade);
/// concurrent rejections share one sign-in. Gateway tickets are always requested through `transport`, whichever
/// HTTP transport the gateway was configured with, because only it holds the cookie.
public actor PasswordSessionAuth: HermesRESTAuth, HermesAuth {
    public struct Credentials: Sendable, Hashable {
        public var username: String
        public var password: String
        public var provider: String

        public init(username: String, password: String, provider: String = "basic") {
            self.username = username
            self.password = password
            self.provider = provider
        }
    }

    private let transport: any HTTPTransport
    private let session: HermesREST
    private let credentials: @Sendable () async throws -> Credentials
    private var signingIn: Task<Void, any Error>?

    /// - Parameter credentials: Read for every sign-in, so an app can keep them in the keychain.
    public init(address: HermesDashboardAddress, transport: any HTTPTransport,
                credentials: @escaping @Sendable () async throws -> Credentials) {
        self.transport = transport
        // The sign-in routes are public: the session itself carries no credential.
        session = HermesREST(configuration: .init(address: address, transport: transport))
        self.credentials = credentials
    }

    /// Signs in now, for example to check credentials the user entered. Throws `HermesRESTError.http` with
    /// status 401 when Hermes rejects them.
    public func signIn() async throws {
        if let signingIn { return try await signingIn.value }
        let task = Task { [session, credentials] in
            let credentials = try await credentials()
            let result = try await session.web.authPasswordLogin(body: _PasswordLoginBody(
                password: credentials.password, provider: credentials.provider, username: credentials.username))
            guard result.ok else { throw HermesRESTError.http(status: 401, body: "") }
        }
        signingIn = task
        defer { signingIn = nil }
        try await task.value
    }

    /// Ends the session on Hermes; the cookie storage drops the cookie Hermes clears.
    public func signOut() async throws {
        _ = try await session.web.authLogout()
    }

    public func authorizationHeaders() async throws -> [String: String] { [:] }

    public func renew(rejected: [String: String], response: RESTResponse) async throws -> Bool {
        try await signInAgain()
    }

    public func credential(baseURL: URL, http: any HTTPTransport) async throws -> GatewayCredential {
        try await DashboardTicketAuth(headers: { [:] }).credential(baseURL: baseURL, http: transport)
    }

    public func renew(after failure: HermesGatewayError) async throws -> Bool {
        try await signInAgain()
    }

    /// Rejected credentials make the rejection final instead of an error to retry.
    private func signInAgain() async throws -> Bool {
        do {
            try await signIn()
            return true
        } catch HermesRESTError.http(let status, _) where status == 401 || status == 403 {
            return false
        }
    }
}
