/// Entry point for the generated Hermes gateway and dashboard clients.
public enum HermesAPI {
    /// The upstream Hermes release used to generate this package.
    public static let hermesRelease = HermesGatewayContract.release
    /// The desktop gateway protocol version this package supports.
    public static let contractVersion = HermesGatewayContract.desktopContract
}
