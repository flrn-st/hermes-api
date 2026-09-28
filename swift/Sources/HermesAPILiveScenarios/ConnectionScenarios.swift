import Foundation
import HermesAPI
#if canImport(Security)
import Security
#endif

/// Connection setups apps use beyond the loopback token: a password session's cookie authorizing the
/// gateway, and a self-hosted dashboard behind a self-signed certificate the app pins.
enum ConnectionScenarios {
    struct PasswordSession: Decodable, Sendable {
        let url: URL
        let username: String
        let password: String
    }

    struct PinnedServer: Decodable, Sendable {
        let url: URL
        /// DER, base64-encoded.
        let certificate: String
    }

    /// Signs in with a password, then uses the cookie session for REST and for the gateway's ticket.
    static func passwordSession(_ server: PasswordSession) async throws {
        let session = URLSession(configuration: .ephemeral)
        defer { session.invalidateAndCancel() }
        let transport = URLSessionHTTPTransport(session: session)
        let address = HermesDashboardAddress(server.url)
        let auth = PasswordSessionAuth(address: address, transport: transport) {
            .init(username: server.username, password: server.password)
        }
        let rest = HermesREST(configuration: .init(address: address, auth: auth, transport: transport))
        // No sign-in yet: the first request is rejected, and the session signs in and repeats it.
        _ = try await rest.sessions.emptyCount()
        let gateway = HermesGateway(configuration: .init(
            address: address, auth: auth, transport: URLSessionGatewayTransport(session: session),
            httpTransport: transport, networkMonitor: nil))
        do {
            try await gateway.connect()
            guard try await gateway.ping(PingParams()).pong else {
                throw LiveScenarioError("Gateway ping over a password session returned false")
            }
            await gateway.disconnect()
        } catch {
            await gateway.disconnect()
            throw error
        }
        try await auth.signOut()
        do {
            _ = try await HermesREST(configuration: .init(address: address, transport: transport)).sessions.emptyCount()
            throw LiveScenarioError("The signed-out session still reached a gated route")
        } catch HermesRESTError.http(let status, _) where status == 401 {}
    }

    /// Reaches the main server over HTTPS and WSS with a session that trusts only the harness's certificate,
    /// and checks that a session without that trust is refused.
    static func pinnedServer(_ server: PinnedServer, token: String) async throws {
        guard let certificate = Data(base64Encoded: server.certificate) else {
            throw LiveScenarioError("Invalid pinned certificate")
        }
        let session = URLSession(configuration: .ephemeral, delegate: PinnedCertificate(certificate), delegateQueue: nil)
        defer { session.invalidateAndCancel() }
        let address = HermesDashboardAddress(server.url)
        let auth = LocalTokenAuth(token: token)
        let rest = HermesREST(configuration: .init(address: address, auth: auth,
                                                   transport: URLSessionHTTPTransport(session: session)))
        _ = try await rest.sessions.emptyCount()
        let gateway = HermesGateway(configuration: .init(
            address: address, auth: auth, transport: URLSessionGatewayTransport(session: session),
            httpTransport: URLSessionHTTPTransport(session: session), networkMonitor: nil))
        do {
            try await gateway.connect()
            guard try await gateway.ping(PingParams()).pong else {
                throw LiveScenarioError("Gateway ping over TLS returned false")
            }
            await gateway.disconnect()
        } catch {
            await gateway.disconnect()
            throw error
        }
        let untrusting = URLSession(configuration: .ephemeral)
        defer { untrusting.invalidateAndCancel() }
        let refused = HermesREST(configuration: .init(
            address: address, auth: auth, transport: URLSessionHTTPTransport(session: untrusting),
            retry: RESTRetryPolicy(maxAttempts: 1)))
        do {
            _ = try await refused.sessions.emptyCount()
            throw LiveScenarioError("A session without the pinned certificate reached Hermes")
        } catch HermesRESTError.transport {}
    }
}

/// Trusts exactly one server certificate, as an app pinning its self-hosted dashboard does.
final class PinnedCertificate: NSObject, URLSessionDelegate, Sendable {
    private let certificate: Data

    init(_ certificate: Data) { self.certificate = certificate }

    func urlSession(
        _ session: URLSession, didReceive challenge: URLAuthenticationChallenge
    ) async -> (URLSession.AuthChallengeDisposition, URLCredential?) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust else {
            return (.performDefaultHandling, nil)
        }
        guard let trust = challenge.protectionSpace.serverTrust,
              let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let leaf = chain.first, SecCertificateCopyData(leaf) as Data == certificate else {
            return (.cancelAuthenticationChallenge, nil)
        }
        return (.useCredential, URLCredential(trust: trust))
    }
}
