import Foundation
import HermesAPI
import Testing

/// The package is generated from, and named after, the Hermes version in spec/current-release.txt.
@Test func releaseIdentity() throws {
    let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    let current = try String(contentsOf: root.appending(path: "spec/current-release.txt"), encoding: .utf8)
        .trimmingCharacters(in: .whitespacesAndNewlines)
    #expect(HermesGatewayContract.release == current)
    #expect(HermesGatewayContract.upstreamVersion == String(current.dropFirst()))
    // Hermes tagged releases by date up to v0.21.5 (v2026.9.24) and by version from v0.22.1.
    #expect(HermesGatewayContract.upstreamTag.hasPrefix("v20") || HermesGatewayContract.upstreamTag == current)
}

/// Apps with types of the same names qualify generated ones by module, so nothing may be named `HermesAPI`.
@Test func generatedTypesCanBeQualifiedByModule() {
    let value: HermesAPI.JSONValue = .null
    #expect(value == HermesAPI.JSONValue.null)
}
