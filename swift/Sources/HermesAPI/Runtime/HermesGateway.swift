import Foundation

private struct OutgoingCall<Params: Encodable>: Encodable {
    let jsonrpc = "2.0"
    let id: Int
    let method: String
    let params: Params
}

private struct OutgoingResult: Encodable {
    let jsonrpc = "2.0"
    let id: String
    let result: JSONValue
}

private struct OutgoingError: Encodable {
    let jsonrpc = "2.0"
    let id: String
    let error: RPCError
}

private struct RPCError: Codable {
    let code: Int
    let message: String
    let data: JSONValue?
}

/// A WebSocket JSON-RPC client for one Hermes dashboard connection.
public actor HermesGateway: GatewayCalling {
    public nonisolated let events: AsyncStream<GatewayEvent>
    public nonisolated let connectionStates: AsyncStream<GatewayConnectionState>

    private let configuration: HermesGatewayConfiguration
    private let eventContinuation: AsyncStream<GatewayEvent>.Continuation
    private let stateContinuation: AsyncStream<GatewayConnectionState>.Continuation
    private var activeSocket: (any GatewayConnection)?
    private var readTask: Task<Void, Never>?
    private var pingTask: Task<Void, Never>?
    private var pending: [Int: AsyncThrowingStream<JSONValue, Error>.Continuation] = [:]
    private var serverTasks: [String: Task<Void, Never>] = [:]
    private var serverRequestHandler: (@Sendable (ServerRequest) async throws -> ServerRequestResult)?
    private var nextID = 1
    private var generation = 0
    private var closing = false
    private var connecting = false
    private var lastSequence: [String: Int] = [:]
    private var replayEpoch: String?

    public init(configuration: HermesGatewayConfiguration) {
        self.configuration = configuration
        let (events, eventContinuation) = AsyncStream.makeStream(
            of: GatewayEvent.self, bufferingPolicy: .unbounded
        )
        let (states, stateContinuation) = AsyncStream.makeStream(
            of: GatewayConnectionState.self, bufferingPolicy: .bufferingNewest(32)
        )
        self.events = events
        self.eventContinuation = eventContinuation
        self.connectionStates = states
        self.stateContinuation = stateContinuation
    }

    /// Generated namespaces are available through `methods` until direct accessors are added.
    public nonisolated var methods: GatewayMethodCatalog { GatewayMethodCatalog(caller: self) }

    public func setServerRequestHandler(
        _ handler: @escaping @Sendable (ServerRequest) async throws -> ServerRequestResult
    ) {
        serverRequestHandler = handler
    }

    public func connect() async throws {
        guard !connecting else { throw HermesGatewayError.transport("Connection already in progress") }
        guard activeSocket == nil else { return }
        closing = false
        connecting = true
        generation += 1
        let current = generation
        stateContinuation.yield(.connecting)
        defer { connecting = false }
        do {
            let credential = try await configuration.auth.credential(
                baseURL: configuration.baseURL, http: configuration.httpTransport
            )
            let (url, headers, protocols) = try Self.socketRequest(
                baseURL: configuration.baseURL, credential: credential
            )
            let socket = try await configuration.transport.connect(
                url: url, headers: headers, subprotocols: protocols
            )
            guard current == generation && !closing else {
                await socket.close()
                throw HermesGatewayError.cancelled
            }
            activeSocket = socket
            readTask = Task { await self.readLoop(socket, generation: current) }
            let capabilities = ClientCapabilitiesParams(serverRequests: true)
            let _: ClientCapabilitiesResult = try await call(
                "client.capabilities", params: capabilities, as: ClientCapabilitiesResult.self
            )
            guard current == generation && activeSocket != nil else {
                throw HermesGatewayError.transport("Connection ended during handshake")
            }
            stateContinuation.yield(.connected)
            pingTask = Task { await self.pingLoop(socket, generation: current) }
        } catch {
            if current == generation { await closeConnection(reason: error.localizedDescription) }
            throw error
        }
    }

    public func disconnect() async {
        closing = true
        generation += 1
        await closeConnection(reason: nil)
    }

    public func call<Params: Encodable & Sendable, Result: Decodable & Sendable>(
        _ method: String, params: Params, as resultType: Result.Type
    ) async throws -> Result {
        try Task.checkCancellation()
        guard let socket = activeSocket else {
            throw HermesGatewayError.transport("Gateway is disconnected")
        }
        let id = nextID
        nextID += 1
        let frame = try JSONEncoder().encode(OutgoingCall(id: id, method: method, params: params))
        let (stream, continuation) = AsyncThrowingStream.makeStream(
            of: JSONValue.self, throwing: Error.self
        )
        pending[id] = continuation
        defer {
            pending[id] = nil
            continuation.finish()
        }
        do {
            try await socket.send(frame)
            let timeout = configuration.requestTimeout
            let result = try await withThrowingTaskGroup(of: JSONValue.self) { group in
                group.addTask {
                    var iterator = stream.makeAsyncIterator()
                    guard let result = try await iterator.next() else {
                        if Task.isCancelled { throw CancellationError() }
                        throw HermesGatewayError.transport("Response stream ended")
                    }
                    return result
                }
                group.addTask {
                    try await Task.sleep(for: timeout)
                    throw HermesGatewayError.timeout
                }
                defer { group.cancelAll() }
                guard let result = try await group.next() else {
                    throw HermesGatewayError.transport("No response")
                }
                return result
            }
            try Task.checkCancellation()
            try validateContract(result)
            do {
                return try JSONDecoder().decode(resultType, from: JSONEncoder().encode(result))
            } catch {
                throw HermesGatewayError.decoding(error.localizedDescription)
            }
        } catch is CancellationError {
            throw HermesGatewayError.cancelled
        } catch let error as HermesGatewayError {
            throw error
        } catch {
            throw HermesGatewayError.transport(error.localizedDescription)
        }
    }

    private static func socketRequest(
        baseURL: URL, credential: GatewayCredential
    ) throws -> (URL, [String: String], [String]) {
        guard var components = URLComponents(url: baseURL, resolvingAgainstBaseURL: false) else {
            throw HermesGatewayError.transport("Invalid dashboard URL")
        }
        switch components.scheme {
        case "http": components.scheme = "ws"
        case "https": components.scheme = "wss"
        default: throw HermesGatewayError.transport("Dashboard URL must use HTTP or HTTPS")
        }
        components.path = "/api/ws"
        switch credential {
        case .ticket(let ticket, let headers):
            guard !ticket.isEmpty else { throw HermesGatewayError.transport("Empty WebSocket ticket") }
            // Hermes accepts a ticket in the protocol header and echoes only the public protocol.
            guard let url = components.url else { throw HermesGatewayError.transport("Invalid WebSocket URL") }
            return (url, headers, ["hermes-gateway-v1", "hermes-gateway-ticket.\(ticket)"])
        case .localToken(let token, let headers):
            guard !token.isEmpty else { throw HermesGatewayError.transport("Empty local token") }
            components.queryItems = [URLQueryItem(name: "token", value: token)]
            guard let url = components.url else { throw HermesGatewayError.transport("Invalid WebSocket URL") }
            return (url, headers, [])
        }
    }

    private func readLoop(_ socket: any GatewayConnection, generation current: Int) async {
        do {
            while !Task.isCancelled {
                let frame = try await socket.receive()
                guard current == generation else { return }
                try await handleFrame(frame, socket: socket, generation: current)
            }
        } catch {
            if current == generation && !closing {
                await closeConnection(reason: error.localizedDescription)
            }
        }
    }

    private func handleFrame(
        _ data: Data, socket: any GatewayConnection, generation current: Int
    ) async throws {
        let frame = try JSONDecoder().decode(JSONValue.self, from: data)
        guard case .object(let fields) = frame else {
            throw HermesGatewayError.decoding("JSON-RPC frame is not an object")
        }
        if case .string(let method) = fields["method"] {
            if method == "event" {
                try handleEvent(fields["params"], replayed: false)
            } else if case .string(let id) = fields["id"] {
                try await handleServerRequest(
                    id: id, method: method, params: fields["params"] ?? .object([:]),
                    socket: socket, generation: current
                )
            }
            return
        }
        guard case .integer(let id) = fields["id"], let continuation = pending.removeValue(forKey: id) else {
            return
        }
        if case .object(let error) = fields["error"] {
            let code = error["code"]?.integerValue ?? -32603
            let message = error["message"]?.stringValue ?? "Unknown RPC error"
            continuation.finish(throwing: HermesGatewayError.rpc(
                code: code, message: message, data: error["data"]
            ))
        } else if let result = fields["result"] {
            continuation.yield(result)
            continuation.finish()
        } else {
            continuation.finish(throwing: HermesGatewayError.decoding("Response has no result or error"))
        }
    }

    private func handleEvent(_ raw: JSONValue?, replayed: Bool) throws {
        guard case .object(let params) = raw,
              case .string(let type) = params["type"] else {
            throw HermesGatewayError.decoding("Invalid event envelope")
        }
        let sessionID = params["session_id"]?.stringValue
        let seq = params["seq"]?.integerValue
        if let sessionID, let seq {
            if seq <= (lastSequence[sessionID] ?? 0) { return }
        }
        let rawPayload = params["payload"] ?? .object([:])
        let payload = try GatewayEventPayload.decode(type: type, payload: rawPayload)
        if case .gatewayReady(let ready) = payload {
            if let epoch = replayEpoch, epoch != ready.replayEpoch { lastSequence.removeAll() }
            replayEpoch = ready.replayEpoch
        }
        if case .requestCancel(let cancel) = payload {
            serverTasks.removeValue(forKey: cancel.id)?.cancel()
        }
        if let sessionID, let seq { lastSequence[sessionID] = seq }
        eventContinuation.yield(GatewayEvent(
            type: type, sessionID: sessionID, seq: seq, payload: payload, replayed: replayed
        ))
    }

    private func handleServerRequest(
        id: String, method: String, params: JSONValue,
        socket: any GatewayConnection, generation current: Int
    ) async throws {
        guard serverTasks[id] == nil else { return }
        let request = try ServerRequest.decode(method: method, params: params)
        guard case .unknown = request else {
            guard let handler = serverRequestHandler else {
                try await sendServerError(id: id, code: -32601, message: "No server request handler", socket: socket)
                return
            }
            let task = Task {
                do {
                    let response = try await handler(request)
                    guard !Task.isCancelled, current == self.generation else { return }
                    guard response.matches(request) else {
                        throw HermesGatewayError.decoding("Server request result kind does not match \(method)")
                    }
                    let payload = try JSONDecoder().decode(JSONValue.self, from: response.encodedJSON())
                    do {
                        try await socket.send(JSONEncoder().encode(OutgoingResult(id: id, result: payload)))
                    } catch {
                        await self.closeConnection(reason: error.localizedDescription)
                    }
                } catch is CancellationError {
                    // The server withdrew the request; it no longer expects an answer.
                } catch {
                    if !Task.isCancelled && current == self.generation {
                        do {
                            try await self.sendServerError(
                                id: id, code: -32603, message: error.localizedDescription, socket: socket
                            )
                        } catch {
                            await self.closeConnection(reason: error.localizedDescription)
                        }
                    }
                }
                self.serverTasks[id] = nil
            }
            serverTasks[id] = task
            return
        }
        try await sendServerError(id: id, code: -32601, message: "Unknown server request", socket: socket)
    }

    private func sendServerError(
        id: String, code: Int, message: String, socket: any GatewayConnection
    ) async throws {
        let frame = OutgoingError(id: id, error: RPCError(code: code, message: message, data: nil))
        try await socket.send(JSONEncoder().encode(frame))
    }

    private func validateContract(_ result: JSONValue) throws {
        guard case .object(let fields) = result else { return }
        let candidate = fields["desktop_contract"] ?? fields["info"]?.objectValue?["desktop_contract"]
        let contract = candidate?.integerValue ?? candidate?.stringValue.flatMap(Int.init)
        if let contract,
           !HermesGatewayContract.supportedContractRange.contains(contract) {
            throw HermesGatewayError.incompatibleServer(contract)
        }
    }

    private func pingLoop(_ socket: any GatewayConnection, generation current: Int) async {
        while !Task.isCancelled && current == generation {
            do {
                try await Task.sleep(for: .seconds(25))
                try Task.checkCancellation()
                try await socket.ping()
            } catch is CancellationError {
                return
            } catch {
                if current == generation && !closing {
                    await closeConnection(reason: error.localizedDescription)
                }
                return
            }
        }
    }

    private func closeConnection(reason: String?) async {
        let socket = activeSocket
        activeSocket = nil
        readTask?.cancel()
        readTask = nil
        pingTask?.cancel()
        pingTask = nil
        for task in serverTasks.values { task.cancel() }
        serverTasks.removeAll()
        for continuation in pending.values {
            continuation.finish(throwing: HermesGatewayError.transport(reason ?? "Gateway disconnected"))
        }
        pending.removeAll()
        if let socket { await socket.close() }
        stateContinuation.yield(.disconnected(reason))
    }
}

private extension JSONValue {
    var stringValue: String? { if case .string(let value) = self { value } else { nil } }
    var integerValue: Int? { if case .integer(let value) = self { value } else { nil } }
    var objectValue: [String: JSONValue]? { if case .object(let value) = self { value } else { nil } }
}
