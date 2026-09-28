import Foundation
import HermesAPI

/// A gateway call as a scripted caller received it.
public struct RecordedCall: Sendable, Hashable {
    public let method: String
    public let params: JSONValue

    public init(method: String, params: JSONValue) {
        self.method = method
        self.params = params
    }
}

/// Answers gateway calls from a script, for code written against `GatewayMethodCatalog` or `GatewayCalling`:
/// `GatewayMethodCatalog(caller: scripted).session.list(...)`. Params and results pass through JSON exactly as
/// on the wire, so the generated models are encoded and decoded for real.
public actor ScriptedGatewayCaller: GatewayCalling {
    public typealias Responder = @Sendable (_ method: String, _ params: JSONValue) async throws -> JSONValue

    private let respond: Responder
    public private(set) var calls: [RecordedCall] = []

    /// Answers every call with `respond`. Throw `HermesGatewayError.rpc` to answer with an error.
    public init(_ respond: @escaping Responder) { self.respond = respond }

    /// Answers each method with a fixed result; any other method fails with Hermes' "method not found".
    public init(results: [String: JSONValue]) {
        self.respond = { method, _ in
            guard let result = results[method] else {
                throw HermesGatewayError.rpc(code: -32601, message: "Method not found: \(method)", data: nil)
            }
            return result
        }
    }

    public nonisolated var methods: GatewayMethodCatalog { GatewayMethodCatalog(caller: self) }

    public func call<Params: Encodable & Sendable, Result: Decodable & Sendable>(
        _ method: String, params: Params, as resultType: Result.Type
    ) async throws -> Result {
        let encoded = try JSONValue(jsonData: JSONEncoder().encode(params))
        calls.append(RecordedCall(method: method, params: encoded))
        let result = try await respond(method, encoded)
        do {
            return try JSONDecoder().decode(resultType, from: JSONEncoder().encode(result))
        } catch {
            throw HermesGatewayError.decoding(error.localizedDescription)
        }
    }
}

/// Answers REST calls from a script, for code written against `RESTMethodCatalog` or `RESTCalling`:
/// `RESTMethodCatalog(caller: scripted).sessions.get(limit: 20)`. The generated methods build real requests
/// and decode the scripted bodies, so status handling and decoding behave as against Hermes.
public actor ScriptedRESTCaller: RESTCalling {
    public typealias Responder = @Sendable (RESTRequest) async throws -> RESTResponse

    private let respond: Responder
    public private(set) var requests: [RESTRequest] = []

    public init(_ respond: @escaping Responder) { self.respond = respond }

    /// Answers `"GET /api/status"`-style keys (method, space, path without query) with a status and JSON body;
    /// anything else gets 404.
    public init(routes: [String: (status: Int, body: String)], decoding: RESTDecoding = .strict) {
        self.respond = { request in
            guard let route = routes["\(request.method) \(request.path)"] else {
                return .json(404, #"{"detail":"Not Found"}"#, decoding: decoding)
            }
            return .json(route.status, route.body, decoding: decoding)
        }
    }

    public nonisolated var methods: RESTMethodCatalog { RESTMethodCatalog(caller: self) }

    public func send(_ request: RESTRequest) async throws -> RESTResponse {
        requests.append(request)
        return try await respond(request)
    }
}

public extension RESTResponse {
    /// A JSON response as Hermes would send it.
    static func json(_ status: Int, _ body: String, decoding: RESTDecoding = .strict) -> RESTResponse {
        RESTResponse(status: status, headers: ["Content-Type": "application/json"], body: Data(body.utf8),
                     decoding: decoding)
    }
}

/// Answers HTTP requests from a script and records what was sent, for driving `HermesREST` (with its retries,
/// deadlines and authentication) or the gateway's ticket request without a server.
public actor ScriptedHTTPTransport: HTTPTransport {
    public enum Step: Sendable {
        case respond(Int, String, [String: String] = [:])
        case fail(URLError.Code)
        /// Never answers; the caller's deadline or cancellation ends the request.
        case hang
    }

    private var steps: [Step]
    public private(set) var requests: [URLRequest] = []

    public init(_ steps: [Step]) { self.steps = steps }

    public func send(_ request: URLRequest) async throws -> (Data, HTTPURLResponse) {
        requests.append(request)
        guard !steps.isEmpty else { throw HermesRESTError.transport("No scripted response left") }
        switch steps.removeFirst() {
        case .respond(let status, let body, let headers):
            guard let url = request.url,
                  let response = HTTPURLResponse(url: url, statusCode: status, httpVersion: nil, headerFields: headers)
            else { throw HermesRESTError.transport("Invalid scripted response") }
            return (Data(body.utf8), response)
        case .fail(let code):
            throw URLError(code)
        case .hang:
            try await Task.sleep(for: .seconds(3600))
            throw HermesRESTError.transport("Hung request was not cancelled")
        }
    }
}
