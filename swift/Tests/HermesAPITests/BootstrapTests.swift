import Foundation
import HermesAPI
import Testing

/// The package is generated from, and named after, the Hermes version in spec/current-release.txt.
@Test func releaseIdentity() throws {
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    let current = try String(contentsOf: root.appending(path: "spec/current-release.txt"), encoding: .utf8)
        .trimmingCharacters(in: .whitespacesAndNewlines)
    #expect(HermesAPI.hermesRelease == current)
    #expect(HermesGatewayContract.upstreamVersion == String(current.dropFirst()))
    #expect(HermesGatewayContract.upstreamTag.hasPrefix("v20"))
}
