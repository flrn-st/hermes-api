import Foundation
import Network

/// The device's current route to the network.
public struct GatewayNetworkPath: Sendable, Equatable {
    public let isAvailable: Bool
    /// Identifies the interface carrying traffic. A change while available (Wi-Fi to cellular, or a new
    /// Wi-Fi network) strands sockets bound to the old interface, so the gateway reconnects at once.
    public let interface: String?

    public init(isAvailable: Bool, interface: String?) {
        self.isAvailable = isAvailable
        self.interface = interface
    }
}

/// Reports network path changes. The first value is the current path.
public protocol GatewayNetworkMonitor: Sendable {
    func paths() -> AsyncStream<GatewayNetworkPath>
}

/// `NWPathMonitor`, the system's own view of connectivity. It costs nothing while the path is stable.
public struct SystemNetworkMonitor: GatewayNetworkMonitor {
    public init() {}

    public func paths() -> AsyncStream<GatewayNetworkPath> {
        AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let monitor = NWPathMonitor()
            monitor.pathUpdateHandler = { path in
                let preferred = path.availableInterfaces.first
                continuation.yield(GatewayNetworkPath(
                    isAvailable: path.status == .satisfied,
                    interface: preferred.map { "\($0.type):\($0.name)" }
                ))
            }
            continuation.onTermination = { _ in monitor.cancel() }
            monitor.start(queue: DispatchQueue(label: "st.flrn.hermes.api.network-path"))
        }
    }
}
