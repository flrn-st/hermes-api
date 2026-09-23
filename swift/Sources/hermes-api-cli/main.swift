import HermesAPI

@main
struct HermesAPICLI {
    static func main() {
        // The command surface is added with the gateway runtime.
        _ = HermesAPI.hermesRelease
    }
}
