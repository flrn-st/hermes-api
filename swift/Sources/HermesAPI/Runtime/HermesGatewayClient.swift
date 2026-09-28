/// Everything an app uses of a gateway: typed calls, the event, state and recovery streams, the server request
/// handler, and the lifecycle. `HermesGateway` is the implementation; depend on this protocol where tests need
/// to substitute `FakeGateway` from `HermesAPITesting`.
public protocol HermesGatewayClient: GatewayCalling, AnyObject {
    /// A new stream of gateway notifications. Every subscriber receives every event, buffered independently,
    /// so consume or cancel each stream.
    func events() -> AsyncStream<GatewayEvent>
    /// A new stream of connection states, starting with the current one.
    func connectionStates() -> AsyncStream<GatewayConnectionState>
    var connectionState: GatewayConnectionState { get }
    /// A new stream of session recoveries after reconnects.
    func sessionRecoveries() -> AsyncStream<GatewaySessionRecovery>
    /// The desktop contract Hermes last reported, or `nil` before the first session result.
    var backendContract: Int? { get async }

    func setServerRequestHandler(_ handler: @escaping @Sendable (ServerRequest) async throws -> ServerRequestResult) async
    func connect() async throws
    func disconnect() async
    func enterBackground(grace: Duration) async
    func enterForeground() async
}

public extension HermesGatewayClient {
    /// Generated typed method namespaces over this client.
    var methods: GatewayMethodCatalog { GatewayMethodCatalog(caller: self) }

    func enterBackground() async { await enterBackground(grace: .seconds(25)) }
}
