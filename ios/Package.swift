// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "BackroadStatus",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "BackroadStatusCore", targets: ["BackroadStatusCore"]),
        .library(name: "BackroadStatusMapKit", targets: ["BackroadStatusMapKit"]),
        .library(name: "BackroadStatusComposerUI", targets: ["BackroadStatusComposerUI"]),
        .library(name: "BackroadStatusMapLibreUI", targets: ["BackroadStatusMapLibreUI"]),
        .library(name: "BackroadStatusMapboxUI", targets: ["BackroadStatusMapboxUI"])
    ],
    dependencies: [
        .package(url: "https://github.com/openid/AppAuth-iOS.git", from: "1.7.5"),
        .package(url: "https://github.com/maplibre/maplibre-gl-native-distribution.git", from: "7.0.0"),
        .package(url: "https://github.com/mapbox/mapbox-maps-ios.git", from: "11.0.0")
    ],
    targets: [
        .target(
            name: "BackroadStatusCore",
            dependencies: [
                .product(name: "AppAuth", package: "AppAuth-iOS")
            ]
        ),
        .target(
            name: "BackroadStatusMapKit",
            dependencies: [
                "BackroadStatusCore"
            ]
        ),
        .target(
            name: "BackroadStatusComposerUI",
            dependencies: [
                "BackroadStatusCore",
                "BackroadStatusMapKit"
            ],
            resources: [
                .process("Resources/FeatureIcons.xcassets")
            ]
        ),
        .target(
            name: "BackroadStatusMapLibreUI",
            dependencies: [
                "BackroadStatusCore",
                "BackroadStatusMapKit",
                "BackroadStatusComposerUI",
                .product(name: "MapLibre", package: "maplibre-gl-native-distribution")
            ],
            resources: [
                .copy("Resources/Fonts")
            ]
        ),
        .target(
            name: "BackroadStatusMapboxUI",
            dependencies: [
                "BackroadStatusCore",
                "BackroadStatusMapKit",
                "BackroadStatusComposerUI",
                .product(name: "MapboxMaps", package: "mapbox-maps-ios")
            ],
            resources: [
                .copy("Resources/Fonts")
            ]
        )
    ]
)
