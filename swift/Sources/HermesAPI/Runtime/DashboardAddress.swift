import Foundation

/// Where a Hermes dashboard is reached.
///
/// `HermesGateway` resolves it before every connection attempt and `HermesREST` before every request
/// attempt, so an app can move between addresses (a LAN and a VPN address, say) without rebuilding its
/// clients or losing the gateway's sessions. `previousFailure` is why the attempt before could not reach
/// the dashboard, or `nil` when there was none: answer quickly then (the address in use), and look for
/// another address only after a failure.
public struct HermesDashboardAddress: Sendable {
    private let resolver: @Sendable (_ previousFailure: (any Error)?) async throws -> URL

    /// A dashboard that is always at `url`.
    public init(_ url: URL) {
        resolver = { _ in url }
    }

    public init(resolve: @escaping @Sendable (_ previousFailure: (any Error)?) async throws -> URL) {
        resolver = resolve
    }

    public func resolve(previousFailure: (any Error)?) async throws -> URL {
        try await resolver(previousFailure)
    }
}

/// URLs of a dashboard's routes. A dashboard can be served under a path, behind a reverse proxy
/// (`https://host/hermes/`) or a relay (`https://relay/agents/<id>`), so a route's path is appended to the
/// base URL's path instead of replacing it.
enum DashboardURL {
    /// The base URL with `path` (percent-encoded, starting with `/`) appended to its path. The base URL's
    /// query and fragment are dropped.
    static func components(base: URL, path: String) -> URLComponents? {
        guard path.hasPrefix("/"), !path.hasPrefix("//"),
              var components = URLComponents(url: base, resolvingAgainstBaseURL: true) else { return nil }
        var prefix = components.percentEncodedPath
        while prefix.hasSuffix("/") { prefix.removeLast() }
        components.percentEncodedPath = prefix + path
        components.percentEncodedQuery = nil
        components.fragment = nil
        return components
    }
}
