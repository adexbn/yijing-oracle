// swift-tools-version:5.9
import PackageDescription

/// 本地 Swift Package：把官方预编译的 llama.cpp XCFramework（b5092，支持 Qwen3）
/// 包装成可供 SwiftPM 引用的二进制目标，产品名与旧 SPM 源码包保持一致为 `llama`，
/// 因此 `import llama` 及 project.yml 里的 `product: llama` 无需改动。
///
/// 二进制本体 `llama.xcframework` 体积约 72MB，不提交到仓库，由 CI 在构建前
/// 从 GitHub Releases 下载并解压到本目录（见 .github/workflows/ios.yml）。
let package = Package(
    name: "llama-ios",
    platforms: [
        .iOS(.v16)
    ],
    products: [
        .library(name: "llama", targets: ["llama"])
    ],
    targets: [
        .binaryTarget(name: "llama", path: "llama.xcframework")
    ]
)
