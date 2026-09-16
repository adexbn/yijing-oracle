# 周易小卦 · Yijing Oracle

「周易小卦」的跨平台实现：**同一套起卦 / 解卦逻辑，两个原生前端**。iOS 与 Android 各自独立实现，功能严格对齐。

| 平台 | 目录 | 技术栈 | 说明 |
| --- | --- | --- | --- |
| iOS | [`ios/`](ios/) | SwiftUI · SPM（llama.cpp / LunarSwift） | 需 macOS 或云端 CI 编译 |
| Android | [`android/`](android/) | Kotlin · AGP 9.4.0 · Gradle 9.7.1 | JDK 21 + Android SDK 37 |

## 功能

- **起卦**：当前时空一键起卦；长按主章可设定条件（指定时间 / 指定数字 / 指定方位）。
- **真太阳时**：按东经 + 时差方程校正钟表时刻，自动获取所在东经，结合农历四柱确定时辰。
- **结果页**：罗盘、六爻（动爻高亮）、本卦 / 变卦的象义·卦辞·白话，以及动爻爻辞·白话。
- **解读**：无提问 → 四维通用解读（事业 / 感情 / 健康 / 抉择）；有提问 → 联网用云端大模型，或离线用本地小模型（llama.cpp + Qwen3-1.7B）。
- **历史**：起卦记录本地持久化，可一键清空。

## 目录结构

```
yijing-oracle/
├── ios/                        # iOS 端（SwiftUI）
│   ├── project.yml             # XcodeGen 工程描述（含 SPM 依赖锁定）
│   ├── codemagic.yaml          # Codemagic 可选签名 / TestFlight 流程
│   ├── Yijing/                 # 源码：Core / Models / Data / Services / Components / Views / Flow
│   └── README.md               # iOS 端详细说明
├── android/                    # Android 端（Kotlin + 自定义 View）
│   ├── app/src/main/java/com/yijing/app/
│   │   ├── core/               # 起卦算法、真太阳时、农历、卦库、AI 客户端、历史
│   │   └── ui/                 # 罗盘、印章按钮、毛笔动画、爻线绘制
│   ├── settings.gradle.kts
│   └── build.gradle.kts
└── .github/workflows/
    ├── ios.yml                 # iOS 编译验证 + 导出未签名 IPA
    └── android.yml             # Android 编译验证（assembleDebug）
```

## 本地构建

### Android（Windows / macOS / Linux 均可）

```bash
cd android
./gradlew assembleDebug          # Windows: gradlew.bat assembleDebug
```

产物：`android/app/build/outputs/apk/debug/app-debug.apk`

> `android/local.properties` 保存本机 SDK 路径，已被 git 忽略，需各自生成（Android Studio 会自动写入）。

### iOS（需 macOS + Xcode）

```bash
cd ios
brew install xcodegen
xcodegen generate
open Yijing.xcodeproj
```

详见 [`ios/README.md`](ios/README.md)。

## 持续集成

两个工作流都带 `paths` 过滤，改哪端跑哪端，互不触发（iOS 走 macOS runner 按 10 倍计费，尤其需要）。

| 工作流 | 触发条件 | 内容 | 产物 |
| --- | --- | --- | --- |
| `iOS Build` | `ios/**` 变更 | `xcodegen generate` → 不签名编译 → 不签名归档 | `Yijing-unsigned-ipa`（14 天） |
| `Android Build` | `android/**` 变更 | JDK 21 + SDK 37 → `assembleDebug` | `Yijing-debug-apk`（14 天） |

两者也支持在 Actions 页面手动触发（`workflow_dispatch`）。

## iPhone 自用安装（无需 $99 账号）

iOS 的任何安装都必须有 Apple 签名，这是系统硬性要求，与是否走 TestFlight 无关。个人自用可走零成本路径：

1. 从 `iOS Build` 的产物下载 `Yijing-unsigned.ipa`（未签名）。
2. 在 Windows 上用 **Sideloadly**（或 AltStore / 爱思助手）以自己的 Apple ID 重签并安装。
3. 免费 Apple ID 签名有效期 **7 天**，到期需重签（Sideloadly 支持自动续签）；同时最多同时安装 3 个自签 App。

## 备注

- 本地模型不随 App 打包，安装后进入「设置 → 本地小模型」下载（约 1.2GB）或本地导入。
- iOS 端 `llama.cpp` 锁定在仍含 `Package.swift` 的 revision；若需升级，请对照新版本 `llama.h` 校正 `LlamaCPP.swift` 的参数名。
