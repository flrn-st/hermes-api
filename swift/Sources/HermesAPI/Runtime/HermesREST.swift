import Foundation

/// The narrow HTTP boundary used by generated REST methods.
public protocol RESTCalling: Sendable {
    func request<Result: Decodable & Sendable>(
        _ method: String, path: String, as resultType: Result.Type
    ) async throws -> Result
}

public enum HermesRESTError: Error, Sendable, Equatable {
    case transport(String)
    case http(status: Int, body: String)
    case decoding(String)
}

public struct HermesRESTConfiguration: Sendable {
    public let baseURL: URL
    public let headers: @Sendable () async throws -> [String: String]
    public let transport: any HTTPTransport

    public init(
        baseURL: URL,
        headers: @escaping @Sendable () async throws -> [String: String] = { [:] },
        transport: any HTTPTransport = URLSessionHTTPTransport()
    ) {
        self.baseURL = baseURL
        self.headers = headers
        self.transport = transport
    }
}

/// Typed REST requests for responses reviewed against a tagged Hermes handler.
public struct HermesREST: RESTCalling {
    private let configuration: HermesRESTConfiguration

    public init(configuration: HermesRESTConfiguration) { self.configuration = configuration }

    public var methods: RESTMethodCatalog { RESTMethodCatalog(caller: self) }

    public func request<Result: Decodable & Sendable>(
        _ method: String, path: String, as resultType: Result.Type
    ) async throws -> Result {
        guard path.hasPrefix("/api/"),
              let url = URL(string: path, relativeTo: configuration.baseURL)?.absoluteURL else {
            throw HermesRESTError.transport("Invalid REST path")
        }
        var request = URLRequest(url: url)
        request.httpMethod = method
        let headers = try await configuration.headers()
        for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
        let data: Data
        let response: HTTPURLResponse
        do {
            (data, response) = try await configuration.transport.send(request)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            throw HermesRESTError.transport(error.localizedDescription)
        }
        guard (200..<300).contains(response.statusCode) else {
            throw HermesRESTError.http(
                status: response.statusCode,
                body: String(decoding: data.prefix(1024), as: UTF8.self)
            )
        }
        do {
            return try JSONDecoder().decode(resultType, from: data)
        } catch {
            throw HermesRESTError.decoding(error.localizedDescription)
        }
    }
}
