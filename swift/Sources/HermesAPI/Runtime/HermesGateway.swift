import Foundation
import os

private struct OutgoingCall<Params: Encodable>: Encodable {
    let jsonrpc = "2.0"
    let id: Int
    let method: String
    let params: Params
}

/// Heartbeat ids are strings so their replies never match a pending call.
private struct OutgoingHeartbeat: Encodable {
    let jsonrpc = "2.0"
    let id: String
    let method: String
    let params: [String: String] = [:]
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

/// A session this client rebinds after a reconnect, with what it needs to resume it from storage.
private struct TrackedSession {
    var storedID: String?
    var profile: String?
    var source: String?
    /// Hermes tears these down as soon as the socket closes; they are never rebound.
    var closeOnDisconnect = false
}

/// A WebSocket JSON-RPC client for one Hermes dashboard.
///
/// Once `connect()` succeeds, the gateway keeps the connection alive until `disconnect()`: it detects
/// dead sockets with a heartbeat, reconnects with jittered backoff when the network allows, rebinds the
/// sessions it created, replays the events it missed, re-delivers open server requests, and resumes
/// sessions Hermes reclaimed meanwhile. Apps call `enterBackground()` and `enterForeground()` from their
/// lifecycle so no socket, heartbeat or retry runs while the app is suspended.
public actor HermesGateway: GatewayCalling {
    private let configuration: HermesGatewayConfiguration
    private let eventBroadcast = Broadcast<GatewayEvent>()
    private let stateBroadcast = Broadcast<GatewayConnectionState>(replaysLatest: true, initial: .idle)
    private let recoveryBroadcast = Broadcast<GatewaySessionRecovery>()
    /// The number of sessions with a turn streaming, so backgrounding can let them finish.
    private let turnActivity = Broadcast<Int>(replaysLatest: true, initial: 0)

    private var activeSocket: (any GatewayConnection)?
    private var readTask: Task<Void, Never>?
    private var heartbeatTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?
    private var reconnectRun = 0
    private var monitorTask: Task<Void, Never>?
    private var generation = 0
    /// Set by `connect()`, cleared by `disconnect()` or a terminal failure.
    private var wantsConnection = false
    private var opening = false
    /// The socket is open, sessions are rebound, and app calls may use it.
    private var ready = false
    private var inBackground = false
    private var networkKnown = false
    private var networkAvailable = true
    private var networkInterface: String?
    private var lastInbound = Duration.zero
    private var heartbeatMethod = "gateway.ping"
    private var heartbeatSequence = 0

    private var pending: [Int: AsyncThrowingStream<JSONValue, Error>.Continuation] = [:]
    private var serverTasks: [String: Task<Void, Never>] = [:]
    private var serverRequestHandler: (@Sendable (ServerRequest) async throws -> ServerRequestResult)?
    private var nextID = 1

    private var sessions: [String: TrackedSession] = [:]
    private var lastSequence: [String: Int] = [:]
    private var activeTurns: Set<String> = []
    private var replayEpoch: String?
    private var hasConnected = false
    private var replayHold: [String: [[String: JSONValue]]] = [:]

    public init(configuration: HermesGatewayConfiguration) {
        self.configuration = configuration
    }

    deinit {
        monitorTask?.cancel()
        reconnectTask?.cancel()
    }

    /// Generated typed method namespaces.
    public nonisolated var methods: GatewayMethodCatalog { GatewayMethodCatalog(caller: self) }

    /// A new stream of gateway notifications. Every subscriber receives every event, buffered independently.
    public nonisolated func events() -> AsyncStream<GatewayEvent> { eventBroadcast.subscribe() }

    /// A new stream of connection states, starting with the current one.
    public nonisolated func connectionStates() -> AsyncStream<GatewayConnectionState> { stateBroadcast.subscribe() }

    public nonisolated var connectionState: GatewayConnectionState { stateBroadcast.latest ?? .idle }

    /// A new stream of session recoveries after reconnects. See `GatewaySessionRecovery`.
    public nonisolated func sessionRecoveries() -> AsyncStream<GatewaySessionRecovery> { recoveryBroadcast.subscribe() }

    public func setServerRequestHandler(
        _ handler: @escaping @Sendable (ServerRequest) async throws -> ServerRequestResult
    ) {
        serverRequestHandler = handler
    }

    // MARK: - Connection lifecycle

    /// Connects and keeps the connection alive until `disconnect()`. Returns once connected; while the
    /// network is down or the server unreachable it keeps retrying. Throws only a failure retrying cannot
    /// fix (`authenticationFailed`, `incompatibleServer`), or `cancelled` if the calling task is cancelled.
    public func connect() async throws {
        if ready { return }
        wantsConnection = true
        startNetworkMonitor()
        if reconnectTask == nil && !opening && !inBackground && networkAvailable {
            // Publish before waiting so a stale `.failed` or `.idle` does not end the wait at once.
            publish(hasConnected ? .reconnecting(attempt: 1) : .connecting)
            startMaintaining(delayFirstAttempt: false)
        } else if !networkAvailable {
            publish(.waitingForNetwork)
        }
        try await awaitConnection(within: nil)
    }

    /// Closes the connection and stops reconnecting. Tracked sessions stay known, so a later `connect()`
    /// rebinds or resumes them.
    public func disconnect() async {
        wantsConnection = false
        cancelReconnect()
        monitorTask?.cancel()
        monitorTask = nil
        networkKnown = false
        generation += 1
        await closeConnection(error: .transport("Gateway disconnected"))
        publish(.idle)
    }

    /// Call when the app moves to the background. A streaming turn gets up to `grace` to finish (with
    /// background execution time on iOS); then the socket closes and nothing runs until
    /// `enterForeground()`. Hermes keeps running turns alive and replays what the client missed.
    public func enterBackground(grace: Duration = .seconds(25)) async {
        guard !inBackground else { return }
        inBackground = true
        cancelReconnect()
        guard wantsConnection else { return }
        if ready && !activeTurns.isEmpty {
            let current = generation
            configuration.logger.info("Background: waiting for \(self.activeTurns.count) running turn(s)")
            await BackgroundActivity.run(reason: "Finish the running Hermes turn") { [turnActivity] in
                let turns = turnActivity.subscribe()
                await withTaskGroup(of: Void.self) { group in
                    group.addTask { for await count in turns where count == 0 { return } }
                    group.addTask { try? await Task.sleep(for: grace) }
                    await group.next()
                    group.cancelAll()
                }
            }
            guard inBackground, current == generation else { return }
        }
        configuration.logger.info("Background: closing the gateway socket")
        generation += 1
        await closeConnection(error: .transport("Gateway suspended in the background"))
        publish(.suspended)
    }

    /// Call when the app returns to the foreground. Reconnects at once and replays what was missed.
    public func enterForeground() async {
        guard inBackground else { return }
        inBackground = false
        guard wantsConnection, activeSocket == nil, reconnectTask == nil, !opening else { return }
        if networkAvailable {
            startMaintaining(delayFirstAttempt: false)
        } else {
            publish(.waitingForNetwork)
        }
    }

    private func startMaintaining(delayFirstAttempt: Bool) {
        cancelReconnect()
        reconnectRun += 1
        let run = reconnectRun
        reconnectTask = Task { await self.maintainConnection(delayFirstAttempt: delayFirstAttempt, run: run) }
    }

    private func cancelReconnect() {
        reconnectTask?.cancel()
        reconnectTask = nil
    }

    private func maintainConnection(delayFirstAttempt: Bool, run: Int) async {
        var attempt = 0
        while wantsConnection && !Task.isCancelled {
            if inBackground { publish(.suspended); break }
            // The network monitor restarts this loop when a path appears; nothing polls meanwhile.
            if !networkAvailable { publish(.waitingForNetwork); break }
            attempt += 1
            publish(hasConnected ? .reconnecting(attempt: attempt) : .connecting)
            if attempt > 1 || delayFirstAttempt {
                do { try await Task.sleep(for: configuration.reconnectDelay(attempt)) } catch { break }
            }
            do {
                try await open()
                break
            } catch let error as HermesGatewayError where error.isTerminal {
                fail(error)
                break
            } catch {
                if Task.isCancelled { break }
                configuration.logger.info("Connection attempt \(attempt) failed: \(String(describing: error), privacy: .public)")
            }
        }
        if run == reconnectRun { reconnectTask = nil }
    }

    /// One connection attempt: socket, capabilities, then session recovery.
    private func open() async throws {
        opening = true
        defer { opening = false }
        generation += 1
        let current = generation
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
            guard current == generation, wantsConnection, !Task.isCancelled else {
                await socket.close()
                throw HermesGatewayError.cancelled
            }
            activeSocket = socket
            lastInbound = configuration.clock.now()
            if hasConnected {
                replayHold = Dictionary(uniqueKeysWithValues: trackedSessionIDs.map { ($0, []) })
            }
            readTask = Task { await self.readLoop(socket, generation: current) }
            // Hermes forgets this per connection; without it every server request fails immediately.
            let _: ClientCapabilitiesResult = try await call(
                "client.capabilities", params: ClientCapabilitiesParams(serverRequests: true),
                as: ClientCapabilitiesResult.self, timeout: configuration.connectTimeout, waitsForConnection: false
            )
            guard current == generation, activeSocket != nil else {
                throw HermesGatewayError.transport("Connection ended during handshake")
            }
            if hasConnected, !(await recoverSessions(socket: socket, generation: current)) {
                throw HermesGatewayError.transport("Session replay failed; reconnecting to replay the gap")
            }
            guard current == generation, activeSocket != nil else {
                throw HermesGatewayError.transport("Connection ended during session recovery")
            }
            hasConnected = true
            ready = true
            configuration.logger.info("Gateway connected")
            publish(.connected)
            heartbeatTask = Task { await self.heartbeatLoop(socket, generation: current) }
        } catch {
            let failure = Self.gatewayError(error)
            if current == generation {
                generation += 1
                await closeConnection(error: failure)
            }
            throw failure
        }
    }

    private func fail(_ error: HermesGatewayError) {
        configuration.logger.error("Gateway failed: \(String(describing: error), privacy: .public)")
        wantsConnection = false
        cancelReconnect()
        publish(.failed(error))
    }

    /// The socket died underneath a working connection. The first retry is jittered unless the cause is
    /// known to be fixed already, such as a new network path.
    private func connectionLost(
        _ error: HermesGatewayError, generation current: Int, retryImmediately: Bool = false
    ) async {
        guard current == generation else { return }
        let wasReady = ready
        generation += 1
        configuration.logger.info("Connection lost: \(String(describing: error), privacy: .public)")
        await closeConnection(error: error)
        // During `open()` the attempt itself reports the failure.
        guard wasReady, wantsConnection else { return }
        if error.isTerminal {
            fail(error)
        } else if !inBackground {
            startMaintaining(delayFirstAttempt: !retryImmediately)
        }
    }

    private func closeConnection(error: HermesGatewayError) async {
        let socket = activeSocket
        activeSocket = nil
        ready = false
        readTask?.cancel()
        readTask = nil
        heartbeatTask?.cancel()
        heartbeatTask = nil
        for task in serverTasks.values { task.cancel() }
        serverTasks.removeAll()
        for continuation in pending.values { continuation.finish(throwing: error) }
        pending.removeAll()
        replayHold.removeAll()
        if let socket { await socket.close() }
    }

    private func publish(_ state: GatewayConnectionState) {
        guard stateBroadcast.latest != state else { return }
        stateBroadcast.yield(state)
    }

    /// Waits until the gateway is connected, or throws what prevents it.
    private func awaitConnection(within timeout: Duration?) async throws {
        if ready { return }
        guard wantsConnection else { throw HermesGatewayError.transport("Gateway is disconnected") }
        let states = stateBroadcast.subscribe()
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                for await state in states {
                    switch state {
                    case .connected: if await self.ready { return }
                    case .failed(let error): throw error
                    case .idle: throw HermesGatewayError.transport("Gateway is disconnected")
                    default: continue
                    }
                }
                throw HermesGatewayError.cancelled
            }
            if let timeout {
                group.addTask {
                    try await Task.sleep(for: timeout)
                    throw HermesGatewayError.timeout
                }
            }
            defer { group.cancelAll() }
            do {
                try await group.next()
            } catch is CancellationError {
                throw HermesGatewayError.cancelled
            }
        }
    }

    // MARK: - Network

    private func startNetworkMonitor() {
        guard monitorTask == nil, let monitor = configuration.networkMonitor else { return }
        let paths = monitor.paths()
        monitorTask = Task { [weak self] in
            for await path in paths {
                guard let self else { return }
                await self.networkChanged(path)
            }
        }
    }

    private func networkChanged(_ path: GatewayNetworkPath) async {
        let changed = !networkKnown || path.isAvailable != networkAvailable || path.interface != networkInterface
        let interfaceChanged = networkKnown && path.interface != networkInterface
        networkKnown = true
        networkAvailable = path.isAvailable
        networkInterface = path.interface
        guard changed, wantsConnection, !inBackground else { return }
        if !path.isAvailable {
            configuration.logger.info("Network unavailable")
            cancelReconnect()
            if activeSocket != nil {
                generation += 1
                await closeConnection(error: .transport("Network unavailable"))
            }
            publish(.waitingForNetwork)
        } else if activeSocket == nil {
            // A path appeared or changed while disconnected: retry now instead of after backoff.
            guard !opening || reconnectTask == nil else { return }
            startMaintaining(delayFirstAttempt: false)
        } else if interfaceChanged && ready {
            // Sockets stay bound to the interface they opened on, which may no longer route.
            await connectionLost(.transport("Network path changed"), generation: generation, retryImmediately: true)
        }
    }

    // MARK: - Calls

    public func call<Params: Encodable & Sendable, Result: Decodable & Sendable>(
        _ method: String, params: Params, as resultType: Result.Type
    ) async throws -> Result {
        try await call(method, params: params, as: resultType, timeout: configuration.requestTimeout,
                       waitsForConnection: true)
    }

    /// App calls made while reconnecting wait for the connection, up to their timeout. Calls in flight
    /// when a socket dies fail with `transport`: Hermes may or may not have run them.
    private func call<Params: Encodable & Sendable, Result: Decodable & Sendable>(
        _ method: String, params: Params, as resultType: Result.Type, timeout: Duration, waitsForConnection: Bool
    ) async throws -> Result {
        try Task.checkCancellation()
        if waitsForConnection && !ready {
            try await awaitConnection(within: timeout)
        }
        guard let socket = activeSocket else {
            throw HermesGatewayError.transport("Gateway is disconnected")
        }
        let current = generation
        let id = nextID
        nextID += 1
        let frame = try JSONEncoder().encode(OutgoingCall(id: id, method: method, params: params))
        let (stream, continuation) = AsyncThrowingStream.makeStream(of: JSONValue.self, throwing: Error.self)
        pending[id] = continuation
        defer {
            pending[id] = nil
            continuation.finish()
        }
        do {
            try await socket.send(frame)
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
            let decoded: Result
            do {
                decoded = try JSONDecoder().decode(resultType, from: JSONEncoder().encode(result))
            } catch {
                throw HermesGatewayError.decoding(error.localizedDescription)
            }
            trackSession(method: method, params: params, result: result)
            return decoded
        } catch HermesGatewayError.rpc(HermesGatewayErrorCode.backendRetiring, let message, let data) {
            await connectionLost(.transport("Hermes backend is retiring"), generation: current)
            throw HermesGatewayError.rpc(code: HermesGatewayErrorCode.backendRetiring, message: message, data: data)
        } catch {
            throw Self.gatewayError(error)
        }
    }

    private static func gatewayError(_ error: any Error) -> HermesGatewayError {
        switch error {
        case let error as HermesGatewayError: error
        case is CancellationError: .cancelled
        default: .transport(error.localizedDescription)
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

    // MARK: - Socket reading and liveness

    private func readLoop(_ socket: any GatewayConnection, generation current: Int) async {
        do {
            while !Task.isCancelled {
                let frame = try await socket.receive()
                guard current == generation else { return }
                lastInbound = configuration.clock.now()
                await handleFrame(frame, socket: socket, generation: current)
            }
        } catch {
            guard !Task.isCancelled else { return }
            await connectionLost(Self.gatewayError(error), generation: current)
        }
    }

    /// Any inbound frame proves the socket alive, so a streaming turn needs no pings. After
    /// `heartbeatInterval` of silence the gateway pings; after `heartbeatDeadline` of silence, and only
    /// once a ping has gone unanswered, it reconnects.
    private func heartbeatLoop(_ socket: any GatewayConnection, generation current: Int) async {
        // When the last ping went out while the socket was silent.
        var probedAt: Duration?
        while !Task.isCancelled && current == generation {
            let due = configuration.clock.now() + configuration.heartbeatInterval
            do { try await configuration.clock.sleep(configuration.heartbeatInterval) } catch { return }
            guard current == generation else { return }
            let now = configuration.clock.now()
            let silence = now - lastInbound
            // Waking later than the whole deadline means the app or runtime was paused, and the answer to the
            // last ping may be waiting unread: the check starts over with a fresh ping instead. Ordinary
            // scheduling delays stay well short of the deadline, so a dead socket is still detected.
            let paused = now - due >= configuration.heartbeatDeadline
            // Silence alone proves nothing after the app or runtime was paused: the socket counts as dead
            // only once a ping sent after the last inbound frame has gone unanswered for a full interval.
            if !paused, silence >= configuration.heartbeatDeadline, let probedAt, probedAt > lastInbound,
               now - probedAt >= configuration.heartbeatInterval {
                await connectionLost(.transport("No frame from Hermes for \(silence)"), generation: current)
                return
            }
            guard silence >= configuration.heartbeatInterval else { continue }
            heartbeatSequence += 1
            do {
                let frame = OutgoingHeartbeat(id: "heartbeat-\(heartbeatSequence)", method: heartbeatMethod)
                try await socket.send(JSONEncoder().encode(frame))
                probedAt = now
            } catch {
                await connectionLost(Self.gatewayError(error), generation: current)
                return
            }
        }
    }

    /// A malformed frame is logged and dropped: reconnecting would not change what Hermes sends.
    private func handleFrame(_ data: Data, socket: any GatewayConnection, generation current: Int) async {
        guard case .object(let fields)? = try? JSONDecoder().decode(JSONValue.self, from: data) else {
            configuration.logger.error("Dropped a gateway frame that is not a JSON object")
            return
        }
        if case .string(let method) = fields["method"] {
            if method == "event" {
                handleEvent(fields["params"], replayed: false)
            } else if case .string(let id) = fields["id"] {
                await handleServerRequest(
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
            continuation.finish(throwing: HermesGatewayError.rpc(code: code, message: message, data: error["data"]))
        } else if let result = fields["result"] {
            continuation.yield(result)
            continuation.finish()
        } else {
            continuation.finish(throwing: HermesGatewayError.decoding("Response has no result or error"))
        }
    }

    private func handleEvent(_ raw: JSONValue?, replayed: Bool) {
        guard case .object(let params) = raw, case .string(let type) = params["type"] else {
            configuration.logger.error("Dropped an event without a type")
            return
        }
        let sessionID = params["session_id"]?.stringValue
        let seq = params["seq"]?.integerValue
        if !replayed, let sessionID, seq != nil, replayHold[sessionID] != nil {
            replayHold[sessionID, default: []].append(params)
            return
        }
        if let sessionID, let seq, seq <= (lastSequence[sessionID] ?? 0) { return }
        let rawPayload = params["payload"] ?? .object([:])
        // A payload the generated model rejects must not tear down the socket: reconnect replay
        // would deliver the same frame again. Callers still receive the raw payload.
        let payload = (try? GatewayEventPayload.decode(type: type, payload: rawPayload))
            ?? .unknown(type: type, raw: rawPayload)
        switch payload {
        case .gatewayReady(let ready):
            if let epoch = replayEpoch, epoch != ready.replayEpoch { resetWatermarks() }
            replayEpoch = ready.replayEpoch
            // Older backends answer gateway.ping with an error, which still proves the socket alive.
            heartbeatMethod = ready.heartbeat == true ? "gateway.ping" : "ping"
        case .requestCancel(let cancel):
            serverTasks.removeValue(forKey: cancel.id)?.cancel()
        case .messageStart:
            if let sessionID { setTurn(sessionID, active: true) }
        case .messageComplete, .error:
            if let sessionID { setTurn(sessionID, active: false) }
        case .sessionReclaimed(let reclaimed):
            if sessions[reclaimed.sessionId] != nil || lastSequence[reclaimed.sessionId] != nil {
                forgetSession(reclaimed.sessionId)
                recoveryBroadcast.yield(.reclaimed(
                    sessionID: reclaimed.sessionId, storedSessionID: reclaimed.storedSessionId, reason: reclaimed.reason
                ))
            }
        default:
            break
        }
        if let sessionID, let seq { lastSequence[sessionID] = seq }
        eventBroadcast.yield(GatewayEvent(type: type, sessionID: sessionID, seq: seq, payload: payload, replayed: replayed))
    }

    private func setTurn(_ sessionID: String, active: Bool) {
        let changed = active ? activeTurns.insert(sessionID).inserted : activeTurns.remove(sessionID) != nil
        if changed { turnActivity.yield(activeTurns.count) }
    }

    private func handleServerRequest(
        id: String, method: String, params: JSONValue,
        socket: any GatewayConnection, generation current: Int
    ) async {
        // A reconnect can deliver one request both live and through open_requests.
        guard serverTasks[id] == nil else { return }
        let request: ServerRequest
        do {
            request = try ServerRequest.decode(method: method, params: params)
        } catch {
            await answerWithError(id: id, code: -32602, message: "Invalid \(method) params", socket: socket, generation: current)
            return
        }
        if case .unknown = request {
            await answerWithError(id: id, code: -32601, message: "Unknown server request", socket: socket, generation: current)
            return
        }
        guard let handler = serverRequestHandler else {
            await answerWithError(id: id, code: -32601, message: "No server request handler", socket: socket, generation: current)
            return
        }
        serverTasks[id] = Task {
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
                    await self.connectionLost(Self.gatewayError(error), generation: current)
                }
            } catch {
                // Only a request Hermes withdrew (request.cancel) goes unanswered. Any other failure,
                // including a handler that gives up by throwing CancellationError, is answered so Hermes
                // does not wait out its deadline (an hour for clarify).
                if !Task.isCancelled {
                    await self.answerWithError(id: id, code: -32603, message: String(describing: error),
                                               socket: socket, generation: current)
                }
            }
            self.finishServerRequest(id)
        }
    }

    private func finishServerRequest(_ id: String) { serverTasks[id] = nil }

    private func answerWithError(
        id: String, code: Int, message: String, socket: any GatewayConnection, generation current: Int
    ) async {
        guard current == generation else { return }
        let frame = OutgoingError(id: id, error: RPCError(code: code, message: message, data: nil))
        do {
            try await socket.send(JSONEncoder().encode(frame))
        } catch {
            await connectionLost(Self.gatewayError(error), generation: current)
        }
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

    // MARK: - Session tracking and recovery

    /// Sessions this client must rebind after a reconnect, whether or not they have emitted events yet.
    private var trackedSessionIDs: Set<String> { Set(sessions.keys).union(lastSequence.keys) }

    private func trackSession<Params: Encodable>(method: String, params: Params, result: JSONValue) {
        let arguments = (try? JSONDecoder().decode(JSONValue.self, from: JSONEncoder().encode(params)))?.objectValue ?? [:]
        switch method {
        case "session.create", "session.resume", "session.activate", "session.branch":
            guard let fields = result.objectValue, let sessionID = fields["session_id"]?.stringValue else { return }
            var session = sessions[sessionID] ?? TrackedSession()
            session.storedID = fields["stored_session_id"]?.stringValue ?? fields["session_key"]?.stringValue
                ?? session.storedID
            session.profile = arguments["profile"]?.stringValue ?? session.profile
            session.source = arguments["source"]?.stringValue ?? session.source
            if case .boolean(let close)? = arguments["close_on_disconnect"] { session.closeOnDisconnect = close }
            sessions[sessionID] = session
        case "session.close":
            if let sessionID = arguments["session_id"]?.stringValue { forgetSession(sessionID) }
        default:
            break
        }
    }

    private func forgetSession(_ sessionID: String) {
        sessions[sessionID] = nil
        lastSequence[sessionID] = nil
        setTurn(sessionID, active: false)
    }

    /// A new server process restarts every session's numbering. Keep the sessions so the rebind
    /// resumes the ones that did not survive.
    private func resetWatermarks() {
        for sessionID in lastSequence.keys { lastSequence[sessionID] = 0 }
    }

    private enum Rebind {
        case bound
        case gone(String)
        case retryLater
    }

    /// False when a session's gap could not be replayed: the connection must be retried, because delivering
    /// the live events held meanwhile would move that session's watermark past the gap for good.
    private func recoverSessions(socket: any GatewayConnection, generation current: Int) async -> Bool {
        var replayed = true
        defer {
            let remaining = replayHold.sorted(by: { $0.key < $1.key })
            replayHold.removeAll()
            if replayed {
                for (_, held) in remaining {
                    for event in held { handleEvent(.object(event), replayed: false) }
                }
            }
        }
        for sessionID in trackedSessionIDs.sorted() {
            guard current == generation else { return true }
            let session = sessions[sessionID] ?? TrackedSession()
            if session.closeOnDisconnect {
                forgetSession(sessionID)
                recoveryBroadcast.yield(.unavailable(sessionID: sessionID, reason: "Closed on disconnect"))
            } else {
                switch await rebind(sessionID) {
                case .bound:
                    guard await replay(sessionID, socket: socket, generation: current) else {
                        replayed = false
                        return false
                    }
                case .gone(let reason): await resume(sessionID, session: session, reason: reason)
                case .retryLater: break
                }
            }
            releaseHeldEvents(for: sessionID)
        }
        return true
    }

    /// Rebinding cancels Hermes' orphan reap and routes the session's live events to this socket.
    private func rebind(_ sessionID: String) async -> Rebind {
        for attempt in 1...3 {
            do {
                _ = try await call(
                    "session.activate", params: SessionActivateParams(sessionId: sessionID, omitMessages: true),
                    as: JSONValue.self, timeout: Self.recoveryTimeout, waitsForConnection: false
                )
                return .bound
            } catch HermesGatewayError.rpc(HermesGatewayErrorCode.sessionSettling, _, _) where attempt < 3 {
                // Hermes is still settling the disconnect interrupt.
                try? await Task.sleep(for: .milliseconds(500 * attempt))
            } catch HermesGatewayError.rpc(let code, let message, _)
                where [HermesGatewayErrorCode.sessionNotFound, HermesGatewayErrorCode.sessionNotLive,
                       HermesGatewayErrorCode.sessionUnavailable].contains(code) {
                return .gone(message)
            } catch {
                return .retryLater
            }
        }
        return .retryLater
    }

    /// Hermes reclaims a session 20 s after its socket closes (and every session on restart), but keeps it
    /// in storage. Resuming by the stored id rebuilds it under a new runtime id.
    private func resume(_ sessionID: String, session: TrackedSession, reason: String) async {
        guard configuration.resumesReclaimedSessions, let storedID = session.storedID else {
            forgetSession(sessionID)
            recoveryBroadcast.yield(.unavailable(sessionID: sessionID, reason: reason))
            return
        }
        do {
            let result: SessionResumeResult = try await call(
                "session.resume",
                params: SessionResumeParams(
                    sessionId: storedID, profile: session.profile.map { .value($0) } ?? .absent,
                    source: session.source.map { .value($0) } ?? .absent, omitMessages: true
                ),
                as: SessionResumeResult.self, timeout: configuration.requestTimeout, waitsForConnection: false
            )
            forgetSession(sessionID)
            configuration.logger.info("Resumed a reclaimed session under a new runtime id")
            recoveryBroadcast.yield(.resumed(
                previousSessionID: sessionID, sessionID: result.sessionId, storedSessionID: storedID
            ))
        } catch HermesGatewayError.rpc(_, let message, _) {
            forgetSession(sessionID)
            recoveryBroadcast.yield(.unavailable(sessionID: sessionID, reason: message))
        } catch {
            // Keep the session; the next reconnect tries again.
        }
    }

    private func replay(_ sessionID: String, socket: any GatewayConnection, generation current: Int) async -> Bool {
        do {
            let result: SessionEventsSinceResult = try await call(
                "session.events.since",
                params: SessionEventsSinceParams(sessionId: sessionID, lastSeen: .value(lastSequence[sessionID] ?? 0)),
                as: SessionEventsSinceResult.self, timeout: Self.recoveryTimeout, waitsForConnection: false
            )
            if let epoch = replayEpoch, epoch != result.epoch {
                resetWatermarks()
                replayEpoch = result.epoch
            } else if result.truncated {
                lastSequence[sessionID] = result.latestSeq
                recoveryBroadcast.yield(.replayTruncated(sessionID: sessionID))
            } else {
                for event in result.events { handleEvent(.object(event), replayed: true) }
            }
            for request in result.openRequests {
                await handleServerRequest(
                    id: request.id, method: request.method, params: .object(request.params),
                    socket: socket, generation: current
                )
            }
            return true
        } catch {
            // Keep the watermark; the retried connection replays the gap.
            return false
        }
    }

    private func releaseHeldEvents(for sessionID: String) {
        for event in replayHold.removeValue(forKey: sessionID) ?? [] {
            handleEvent(.object(event), replayed: false)
        }
    }

    /// Recovery calls must not stall a reconnect behind a wedged backend.
    private static let recoveryTimeout: Duration = .seconds(10)
}

private extension JSONValue {
    var stringValue: String? { if case .string(let value) = self { value } else { nil } }
    var integerValue: Int? { if case .integer(let value) = self { value } else { nil } }
    var objectValue: [String: JSONValue]? { if case .object(let value) = self { value } else { nil } }
}
