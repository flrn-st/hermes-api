// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "HermesAPI",
    platforms: [
        .iOS(.v17),
        .macOS(.v14),
        .watchOS(.v10),
        .tvOS(.v17),
        .visionOS(.v1)
    ],
    products: [
        .library(name: "HermesAPI", targets: ["HermesAPI"]),
        .executable(name: "hermes-api-cli", targets: ["hermes-api-cli"])
    ],
    targets: [
        .target(name: "HermesAPI", path: "swift/Sources/HermesAPI", swiftSettings: [.swiftLanguageMode(.v6)]),
        .executableTarget(name: "hermes-api-cli", dependencies: ["HermesAPI"], path: "swift/Sources/hermes-api-cli", swiftSettings: [.swiftLanguageMode(.v6)]),
        .testTarget(name: "HermesAPITests", dependencies: ["HermesAPI"], path: "swift/Tests/HermesAPITests", swiftSettings: [.swiftLanguageMode(.v6)])
    ]
)
