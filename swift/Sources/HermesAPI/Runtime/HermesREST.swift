import Foundation
import os

/// One REST request as generated methods build it. `path` is already percent-encoded.
public struct RESTRequest: Sendable, Hashable {
    public var method: String
    public var path: String
    public var query: [String: String]
    public var body: Data?
    public var contentType: String?

    public init(method: String, path: String, query: [String: String] = [:], body: Data? = nil,
                contentType: String? = nil) {
        self.method = method
        self.path = path
        self.query = query
        self.body = body
        self.contentType = contentType
    }
}

/// A REST response with any status; generated methods decide which statuses succeed.
public struct RESTResponse: Sendable, Hashable {
    public let status: Int
    /// Header values by lowercased name.
    public let headers: [String: String]
    public let body: Data

    public init(status: Int, headers: [String: String], body: Data) {
        self.status = status
        self.headers = Dictionary(headers.map { ($0.key.lowercased(), $0.value) }, uniquingKeysWith: { first, _ in first })
        self.body = body
    }
}

/// The narrow HTTP boundary used by generated REST methods.
public protocol RESTCalling: Sendable {
    func send(_ request: RESTRequest) async throws -> RESTResponse
}

public enum HermesRESTError: Error, Sendable, Equatable {
    /// Hermes could not be reached (after any retries the policy allows).
    case transport(String)
    /// No response arrived within the configured timeout (after any retries the policy allows).
    case timeout
    /// A status the operation does not document as a success.
    case http(status: Int, body: String)
    case decoding(String)

    /// FastAPI's `detail` message for an HTTP error, when the body carries one.
    public var detail: String? {
        guard case .http(_, let body) = self,
              case .object(let fields)? = try? JSONDecoder().decode(JSONValue.self, from: Data(body.utf8)) else {
            return nil
        }
        switch fields["detail"] {
        case .string(let message)?: return message
        case .object(let detail)?:
            if case .string(let message)? = detail["message"] ?? detail["error"] { return message }
            return nil
        default: return nil
        }
    }

    /// 401 or 403: the credential was missing, expired or not allowed.
    public var isAuthenticationFailure: Bool {
        if case .http(let status, _) = self { return status == 401 || status == 403 }
        return false
    }
}

/// When `HermesREST` sends a request again.
///
/// Safe methods (`GET`, `HEAD`) are retried after a timeout, a lost connection, 429 or 502/503/504.
/// Any method is retried when the connection could not be established, because Hermes never saw the
/// request. Waits grow exponentially with full jitter; a `Retry-After` header wins when it is shorter
/// than `maximumDelay`.
public struct RESTRetryPolicy: Sendable {
    /// Attempts per request, including the first. `1` disables retries.
    public var maxAttempts: Int
    public var initialDelay: Duration
    public var maximumDelay: Duration
    public var retryableStatuses: Set<Int>

    public init(maxAttempts: Int = 6, initialDelay: Duration = .milliseconds(250), maximumDelay: Duration = .seconds(8),
                retryableStatuses: Set<Int> = [429, 502, 503, 504]) {
        self.maxAttempts = max(1, maxAttempts)
        self.initialDelay = initialDelay
        self.maximumDelay = maximumDelay
        self.retryableStatuses = retryableStatuses
    }

    public static let none = RESTRetryPolicy(maxAttempts: 1)

    static func isSafe(_ method: String) -> Bool { ["GET", "HEAD", "OPTIONS"].contains(method.uppercased()) }

    func delay(beforeAttempt attempt: Int, retryAfter: Duration?) -> Duration {
        if let retryAfter, retryAfter <= maximumDelay { return retryAfter }
        let exponent = min(attempt - 2, 16)
        let cap = min(maximumDelay, initialDelay * (1 << exponent))
        let milliseconds = cap.components.seconds * 1000 + cap.components.attoseconds / 1_000_000_000_000_000
        return .milliseconds(Int64.random(in: 0...max(0, milliseconds)))
    }
}

/// A file or other raw body; `contentType` is the server's `Content-Type`, if any.
public struct RESTBinary: Sendable, Hashable {
    public let data: Data
    public let contentType: String?

    public init(data: Data, contentType: String?) {
        self.data = data
        self.contentType = contentType
    }
}

/// A redirect the operation answers with; the transport must not follow it.
public struct RESTRedirect: Sendable, Hashable {
    public let status: Int
    public let location: String

    public init(status: Int, location: String) {
        self.status = status
        self.location = location
    }
}

/// A file part of a multipart upload.
public struct RESTFile: Sendable, Hashable {
    public let filename: String
    public let contentType: String
    public let data: Data

    public init(filename: String, contentType: String = "application/octet-stream", data: Data) {
        self.filename = filename
        self.contentType = contentType
        self.data = data
    }
}

/// Percent-encoding for path segments and query values: everything but RFC 3986 unreserved characters.
public enum RESTPath {
    private static let unreserved = CharacterSet(charactersIn:
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")

    public static func segment(_ value: String) -> String {
        // Every Unicode scalar outside the unreserved set is encoded, so this cannot fail.
        value.addingPercentEncoding(withAllowedCharacters: unreserved) ?? ""
    }
}

/// A `multipart/form-data` body.
public struct RESTMultipart: Sendable {
    public let boundary: String
    private var parts: [(headers: String, data: Data)] = []

    public init(boundary: String = "hermes-api-\(UUID().uuidString)") { self.boundary = boundary }

    public var contentType: String { "multipart/form-data; boundary=\(boundary)" }

    public mutating func text(_ name: String, _ value: String) {
        parts.append(("Content-Disposition: form-data; name=\"\(Self.quoted(name))\"\r\n", Data(value.utf8)))
    }

    public mutating func file(_ name: String, _ file: RESTFile) {
        parts.append((
            "Content-Disposition: form-data; name=\"\(Self.quoted(name))\"; filename=\"\(Self.quoted(file.filename))\"\r\n"
                + "Content-Type: \(file.contentType)\r\n",
            file.data
        ))
    }

    public func encoded() -> Data {
        var body = Data()
        for part in parts {
            body.append(Data("--\(boundary)\r\n\(part.headers)\r\n".utf8))
            body.append(part.data)
            body.append(Data("\r\n".utf8))
        }
        body.append(Data("--\(boundary)--\r\n".utf8))
        return body
    }

    private static func quoted(_ value: String) -> String {
        value.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "%22")
            .replacingOccurrences(of: "\r", with: "%0D").replacingOccurrences(of: "\n", with: "%0A")
    }
}

public extension RESTResponse {
    /// The error for a status the operation does not document.
    func undocumented() -> HermesRESTError {
        .http(status: status, body: String(decoding: body.prefix(1024), as: UTF8.self))
    }

    func json<Result: Decodable>(_ type: Result.Type, status expected: Int) throws -> Result {
        guard status == expected else { throw undocumented() }
        do {
            return try JSONDecoder().decode(type, from: body)
        } catch {
            throw HermesRESTError.decoding(String(describing: error))
        }
    }

    func text(status expected: Int) throws -> String {
        guard status == expected else { throw undocumented() }
        guard let text = String(data: body, encoding: .utf8) else {
            throw HermesRESTError.decoding("REST text body is not UTF-8")
        }
        return text
    }

    func binary(status expected: Int) throws -> RESTBinary {
        guard status == expected else { throw undocumented() }
        return RESTBinary(data: body, contentType: headers["content-type"])
    }

    func redirect(status expected: Int) throws -> RESTRedirect {
        guard status == expected else { throw undocumented() }
        guard let location = headers["location"] else { throw HermesRESTError.decoding("Redirect has no Location") }
        return RESTRedirect(status: status, location: location)
    }

    func empty(status expected: Int) throws {
        guard status == expected else { throw undocumented() }
    }
}

public struct HermesRESTConfiguration: Sendable {
    public let baseURL: URL
    /// Authenticates every request; `nil` for public routes or cookie sessions the transport carries.
    public let auth: (any HermesRESTAuth)?
    /// Extra headers on every request, for example a reverse proxy's credential.
    public let headers: @Sendable () async throws -> [String: String]
    public let transport: any HTTPTransport
    /// Bounds each attempt, from sending the request to the last byte of the response.
    public let timeout: Duration
    public let retry: RESTRetryPolicy
    public let logger: Logger

    public init(
        baseURL: URL,
        auth: (any HermesRESTAuth)? = nil,
        headers: @escaping @Sendable () async throws -> [String: String] = { [:] },
        transport: any HTTPTransport = URLSessionHTTPTransport(),
        timeout: Duration = .seconds(60),
        retry: RESTRetryPolicy = RESTRetryPolicy(),
        logger: Logger = Logger(subsystem: "hermes.api", category: "rest")
    ) {
        self.baseURL = baseURL
        self.auth = auth
        self.headers = headers
        self.transport = transport
        self.timeout = timeout
        self.retry = retry
        self.logger = logger
    }
}

/// Typed REST requests for responses reviewed against a tagged Hermes handler.
public struct HermesREST: RESTCalling {
    private let configuration: HermesRESTConfiguration

    public init(configuration: HermesRESTConfiguration) { self.configuration = configuration }

    public var methods: RESTMethodCatalog { RESTMethodCatalog(caller: self) }

    public func send(_ request: RESTRequest) async throws -> RESTResponse {
        let prepared = try urlRequest(for: request)
        let retry = configuration.retry
        var attempt = 1
        var renewed = false
        while true {
            try Task.checkCancellation()
            var outgoing = prepared
            for (name, value) in try await configuration.headers() { outgoing.setValue(value, forHTTPHeaderField: name) }
            let credential = try await configuration.auth?.authorizationHeaders() ?? [:]
            for (name, value) in credential { outgoing.setValue(value, forHTTPHeaderField: name) }
            let failure: HermesRESTError
            var retryAfter: Duration?
            do {
                let response = try await perform(outgoing)
                if response.status == 401, !renewed, let auth = configuration.auth,
                   try await auth.renew(rejected: credential, response: response) {
                    renewed = true
                    continue
                }
                guard retry.retryableStatuses.contains(response.status), RESTRetryPolicy.isSafe(request.method),
                      attempt < retry.maxAttempts else {
                    return response
                }
                failure = response.undocumented()
                retryAfter = response.retryAfter
            } catch let error as Attempt {
                switch error {
                case .notConnected(let message) where attempt < retry.maxAttempts:
                    failure = .transport(message)
                case .interrupted(let message) where attempt < retry.maxAttempts && RESTRetryPolicy.isSafe(request.method):
                    failure = .transport(message)
                case .timedOut where attempt < retry.maxAttempts && RESTRetryPolicy.isSafe(request.method):
                    failure = .timeout
                case .notConnected(let message), .interrupted(let message):
                    throw HermesRESTError.transport(message)
                case .timedOut:
                    throw HermesRESTError.timeout
                }
            }
            attempt += 1
            let delay = retry.delay(beforeAttempt: attempt, retryAfter: retryAfter)
            configuration.logger.info(
                "Retrying \(request.method, privacy: .public) \(request.path, privacy: .private) (attempt \(attempt)) after \(String(describing: failure), privacy: .public)")
            try await Task.sleep(for: delay)
        }
    }

    private func urlRequest(for request: RESTRequest) throws -> URLRequest {
        guard var components = DashboardURL.components(base: configuration.baseURL, path: request.path) else {
            throw HermesRESTError.transport("Invalid REST path")
        }
        if !request.query.isEmpty {
            components.percentEncodedQuery = request.query.sorted { $0.key < $1.key }
                .map { "\(RESTPath.segment($0.key))=\(RESTPath.segment($0.value))" }
                .joined(separator: "&")
        }
        guard let endpoint = components.url else { throw HermesRESTError.transport("Invalid REST query") }
        var urlRequest = URLRequest(url: endpoint)
        urlRequest.httpMethod = request.method
        urlRequest.httpBody = request.body
        // The attempt deadline below is authoritative; URLSession's own idle timer must not undercut it.
        urlRequest.timeoutInterval = max(1, Double(configuration.timeout.components.seconds) + 1)
        if let contentType = request.contentType { urlRequest.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        return urlRequest
    }

    /// How one attempt failed, before the retry policy decides.
    private enum Attempt: Error {
        /// No connection was established, so Hermes never saw the request.
        case notConnected(String)
        /// The connection failed after the request may have been sent.
        case interrupted(String)
        case timedOut
    }

    private func perform(_ request: URLRequest) async throws -> RESTResponse {
        let transport = configuration.transport
        let timeout = configuration.timeout
        let (data, response): (Data, HTTPURLResponse)
        do {
            (data, response) = try await withThrowingTaskGroup(of: (Data, HTTPURLResponse)?.self) { group in
                group.addTask { try await transport.send(request) }
                group.addTask {
                    try await Task.sleep(for: timeout)
                    return nil
                }
                defer { group.cancelAll() }
                guard let first = try await group.next(), let result = first else { throw Attempt.timedOut }
                return result
            }
        } catch let error as Attempt {
            throw error
        } catch is CancellationError {
            throw CancellationError()
        } catch let error as URLError {
            if error.code == .cancelled, Task.isCancelled { throw CancellationError() }
            if error.code == .timedOut { throw Attempt.timedOut }
            let unreached: Set<URLError.Code> = [.cannotFindHost, .cannotConnectToHost, .dnsLookupFailed,
                                                 .notConnectedToInternet, .secureConnectionFailed]
            throw unreached.contains(error.code) ? Attempt.notConnected(error.localizedDescription)
                : Attempt.interrupted(error.localizedDescription)
        } catch {
            throw Attempt.interrupted(error.localizedDescription)
        }
        var headers: [String: String] = [:]
        for (name, value) in response.allHeaderFields {
            if let name = name as? String, let value = value as? String { headers[name] = value }
        }
        return RESTResponse(status: response.statusCode, headers: headers, body: data)
    }
}

extension RESTResponse {
    /// `Retry-After` in seconds; HTTP dates are ignored in favour of the policy's backoff.
    var retryAfter: Duration? {
        headers["retry-after"].flatMap { Int($0.trimmingCharacters(in: .whitespaces)) }.map { .seconds(max(0, $0)) }
    }
}
