import Foundation

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
