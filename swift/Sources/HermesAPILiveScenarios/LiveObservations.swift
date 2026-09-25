import Foundation
import HermesAPI

/// What a live run exercised, measured on the wire and reported to the harness as coverage evidence:
/// methods that returned a result, events that decoded to their typed payload, and server requests
/// that decoded and were answered with a result.
actor LiveObservations {
    private var methods: Set<String> = []
    private var events: Set<String> = []
    private var serverRequests: Set<String> = []

    func method(_ name: String) { methods.insert(name) }
    func event(_ name: String) { events.insert(name) }
    func serverRequest(_ name: String) { serverRequests.insert(name) }

    func report() -> [String: [String]] {
        ["methods": methods.sorted(), "events": events.sorted(), "server_requests": serverRequests.sorted()]
    }
}

/// Wraps a transport so every socket it opens reports to `observations`.
struct ObservingTransport: GatewayTransport {
    let inner: any GatewayTransport
    let observations: LiveObservations

    func connect(url: URL, headers: [String: String], subprotocols: [String]) async throws -> any GatewayConnection {
        let connection = try await inner.connect(url: url, headers: headers, subprotocols: subprotocols)
        return ObservedConnection(inner: connection, observations: observations)
    }
}

private actor ObservedConnection: GatewayConnection {
    let inner: any GatewayConnection
    let observations: LiveObservations
    /// Request ids are scoped to one socket: calls this client sent and server requests it received.
    private var calls: [Int: String] = [:]
    private var requests: [String: String] = [:]

    init(inner: any GatewayConnection, observations: LiveObservations) {
        self.inner = inner
        self.observations = observations
    }

    func send(_ frame: Data) async throws {
        guard case .object(let fields)? = try? JSONDecoder().decode(JSONValue.self, from: frame) else {
            try await inner.send(frame)
            return
        }
        // Record a call before sending it: Hermes can answer before the send returns.
        if case .string(let method)? = fields["method"], case .integer(let id)? = fields["id"] {
            calls[id] = method
        }
        try await inner.send(frame)
        if case .string(let id)? = fields["id"], fields["method"] == nil, fields["result"] != nil,
           let method = requests.removeValue(forKey: id) {
            await observations.serverRequest(method)
        }
    }

    func receive() async throws -> Data {
        let frame = try await inner.receive()
        guard case .object(let fields)? = try? JSONDecoder().decode(JSONValue.self, from: frame) else { return frame }
        switch (fields["method"], fields["id"]) {
        case (.string("event")?, _):
            guard case .object(let params)? = fields["params"], case .string(let type)? = params["type"],
                  let payload = try? GatewayEventPayload.decode(type: type, payload: params["payload"] ?? .object([:]))
            else { break }
            if case .unknown = payload { break }
            await observations.event(type)
        case (.string(let method)?, .string(let id)?):
            guard let request = try? ServerRequest.decode(method: method, params: fields["params"] ?? .object([:]))
            else { break }
            if case .unknown = request { break }
            requests[id] = method
        case (nil, .integer(let id)?):
            if fields["result"] != nil, let method = calls.removeValue(forKey: id) { await observations.method(method) }
        default:
            break
        }
        return frame
    }

    func close() async { await inner.close() }
}
