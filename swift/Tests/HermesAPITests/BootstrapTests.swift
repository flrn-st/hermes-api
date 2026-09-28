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
    #expect(HermesGatewayContract.upstreamTag.hasPrefix("v20"))
}

/// Apps with types of the same names qualify generated ones by module, so nothing may be named `HermesAPI`.
@Test func generatedTypesCanBeQualifiedByModule() {
    let value: HermesAPI.JSONValue = .null
    #expect(value == HermesAPI.JSONValue.null)
}
