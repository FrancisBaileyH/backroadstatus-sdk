// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "BackroadStatus",
    platforms: [.iOS(.v15)],
    products: [
        .library(name: "BackroadStatus", targets: ["BackroadStatus"])
    ],
    dependencies: [
        .package(url: "https://github.com/openid/AppAuth-iOS.git", from: "1.7.5"),
        .package(url: "https://github.com/yonat/MultiSlider.git", from: "1.14.0")
    ],
    targets: [
        .target(
            name: "BackroadStatus",
            dependencies: [
                .product(name: "AppAuth", package: "AppAuth-iOS"),
                .product(name: "MultiSlider", package: "MultiSlider")
            ]
        )
    ]
)
