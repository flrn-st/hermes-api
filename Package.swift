// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "HermesAPI",
    platforms: [
        // iOS covers iPadOS. Only these platforms are built and tested.
        .iOS(.v17),
        .macOS(.v14)
    ],
    products: [
        .library(name: "HermesAPI", targets: ["HermesAPI"]),
        .executable(name: "hermes-api-cli", targets: ["hermes-api-cli"])
    ],
    targets: [
        .target(name: "HermesAPI", path: "swift/Sources/HermesAPI", swiftSettings: [.swiftLanguageMode(.v6)]),
        // Live scenarios shared by the macOS CLI and the iOS simulator test; not a product.
        .target(name: "HermesAPILiveScenarios", dependencies: ["HermesAPI"], path: "swift/Sources/HermesAPILiveScenarios", swiftSettings: [.swiftLanguageMode(.v6)]),
        .executableTarget(name: "hermes-api-cli", dependencies: ["HermesAPI", "HermesAPILiveScenarios"], path: "swift/Sources/hermes-api-cli", swiftSettings: [.swiftLanguageMode(.v6)]),
        .testTarget(name: "HermesAPITests", dependencies: ["HermesAPI", "HermesAPILiveScenarios"], path: "swift/Tests/HermesAPITests", swiftSettings: [.swiftLanguageMode(.v6)])
    ]
)
