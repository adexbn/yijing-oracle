# 周易小卦 · iOS

「周易小卦」的 iOS 版（SwiftUI），与 Android 版功能对齐：梅花易数起卦、真太阳时/农历校正、64 卦离线数据库、联网（云端大模型）或本地小模型离线解读、历史记录。

## 功能

- **起卦**：当前时空一键起卦；长按主章可设定条件（指定时间 / 指定数字 / 指定方位）。
- **真太阳时**：按东经 + 时差方程校正钟表时刻，自动获取所在东经（默认东经 120°），结合农历四柱确定时辰。
- **结果页**：罗盘、六爻（动爻高亮）、本卦/变卦的象义·卦辞·白话，以及动爻爻辞·白话。
- **解读**：
  - 无提问 → 四维通用解读（事业 / 感情 / 健康 / 抉择）。
  - 有提问 → 联网用云端大模型，或离线用本地小模型（llama.cpp + Qwen3-1.7B）。
- **历史**：起卦记录本地持久化，可一键清空。

## 本版两处体验优化

1. 首页问题输入框放大（约 160pt 高，可滚动），主印章按钮整体下移、与输入区拉开距离。
2. 点按起卦后，**先进入等待页**（毛笔写「爻」动画），等本地/云端解读完整跑完，**再一次性进入结果页**，避免「半成品先展示、再慢慢填满」的割裂感。

## 目录结构

本端位于 monorepo 的 `ios/` 子目录，仓库根目录同时存放 `android/`。

```
yijing-oracle/
├── .github/workflows/
│   ├── ios.yml          # 本端：编译验证 + 导出未签名 IPA
│   └── android.yml      # Android 端
└── ios/
    ├── README.md        # 本文件
    ├── project.yml      # XcodeGen 工程描述（含 SPM 依赖锁定）
    ├── codemagic.yaml   # Codemagic 构建 + TestFlight 发布（可选）
    └── Yijing/
        ├── YijingApp.swift  # @main 入口
        ├── RootView.swift   # NavigationStack + 路由
        ├── Flow/            # 起卦流程协调器（等待→结果）
        ├── Core/            # 起卦算法、真太阳时、农历、通用解读
        ├── Models/          # 八卦 / 六十四卦
        ├── Data/            # 64 卦卦辞、象义、384 爻辞库
        ├── Services/        # AI、本地模型、历史、钥匙串、定位
        ├── Components/      # 印章、罗盘、爻线、毛笔动画
        └── Views/           # 首页 / 等待 / 结果 / 历史 / 设置
```

## 依赖（SPM）

| 包 | 用途 | 说明 |
| --- | --- | --- |
| `ggml-org/llama.cpp` | 本地小模型推理 | 产品名 `llama`，`import llama`；锁定 2024-12-07 的 revision（`master` 已于 2025-03 移除 `Package.swift`，改发 XCFramework） |
| `6tail/lunar-swift` | 农历 / 四柱 | 产品名 `LunarSwift`，`import LunarSwift` |

## 本地构建（有 Mac / Xcode）

```bash
# 1. 安装 XcodeGen
brew install xcodegen

# 2. 生成 Xcode 工程（首次会拉取并编译 SPM 依赖，llama.cpp 体积较大，耗时较长）
xcodegen generate

# 3. 用 Xcode 打开并运行
open Yijing.xcodeproj
```

> 需在 Xcode 中选择自己的开发团队（Signing & Capabilities → Team）后即可真机 / TestFlight 运行。

## 云端构建（无 Mac / 无 Xcode）

没有 Mac 也能在云端免费编译 iOS。共享一个无法绕过的前提，其余额度均免费。

### 前提（一次性成本）

- **Apple Developer Program 付费账号（$99/年）**：签名、描述文件、提交 TestFlight 都依赖它。
  - 免费 Apple ID 只能用 Xcode 临时自签名（约 7 天过期），且**不能**发布 TestFlight——对「无 Mac」场景不可用。
- 在 App Store Connect 注册好 App，Bundle ID 与工程里一致（本项目为 `com.yijing.app`）。

### 方案 A：Codemagic（推荐）

- 免费档 **500 分钟/月**，Apple Silicon M2 机器，月底重置；个人开发完全够用。
- 内置 **自动代码签名** + 一条命令直接提交 TestFlight，无需手动管理证书/描述文件。
- 已配好 `codemagic.yaml`。流程：

  1. 把仓库推到 GitHub / GitLab / Bitbucket，并在 Codemagic 中把构建目录指向本仓库的 `ios/` 子目录。
  2. Codemagic 后台：**Connect App Store Connect**（用 API Key），并给 App 打开 **Automatic code signing**。
  3. 新建 Build，选择本源 `codemagic.yaml`，运行即可 —— 自动执行 `xcodegen generate → use-profiles 签名 → 归档 → 提交 TestFlight`。
  4. 在「Environment variables」建变量组 `app_store_credentials`，含：
     - `APP_STORE_CONNECT_ISSUER_ID`
     - `APP_STORE_CONNECT_KEY_IDENTIFIER`
     - `APP_STORE_CONNECT_PRIVATE_KEY`

### 方案 B：GitHub Actions（公开仓库免费）

- 公开仓库免费提供 Apple silicon macOS runner；免费账户约 **200 macOS 分钟/月**（macOS 按 10 倍计费）。
- 已配好仓库根目录的 `.github/workflows/ios.yml`：`ios/**` 变更即触发，先 `xcodegen generate`，再做 **不签名的编译验证**——零 secrets 即可确认代码能否编译通过；同时归档并导出 **未签名 IPA** 作为 Actions 产物，用于本机自签安装。
- 若要在 GitHub Actions 上签名 + 上 TestFlight，需自配证书（`.p12`）+ 描述文件 + App Store Connect key 到仓库 Secrets，再解开 `ios.yml` 里注释的步骤；比 Codemagic 繁琐，不推荐首选用。

### 编译耗时提醒

- `llama.cpp` 走 SPM 从源码编译，单次云端构建约 15–40 分钟，注意别超免费额度。
- 首次建议先用「方案 B 的编译验证」确认能编译，再上 Codemagic 跑正式签名构建，省额度。

## iPhone 自用安装（零成本，无需 $99 账号）

iOS 的**任何**安装都必须带 Apple 签名，这是系统硬性要求，与走不走 TestFlight 无关。个人自用不必买开发者账号，走「未签名 IPA + 本机自签」即可：

1. 打开 GitHub 仓库的 **Actions → iOS Build**，下载产物 `Yijing-unsigned-ipa`。
2. 在 Windows 上安装 **Sideloadly**（需配套安装非商店版 iTunes + iCloud，用于驱动与配对）。
3. 用数据线连上 iPhone，把 `.ipa` 拖进 Sideloadly，填入自己的 Apple ID，点 Start 完成重签与安装。
4. iPhone 上到「设置 → 通用 → VPN 与设备管理」信任该开发者证书后即可打开。

| 账号类型 | 签名有效期 | 同时可装 App 数 | 费用 |
| --- | --- | --- | --- |
| 免费 Apple ID | 7 天（到期重签） | 3 个 | 免费 |
| 付费开发者账号 | 1 年 | 100 台设备 | $99/年 |

> 免费账号 7 天过期只是需要重新跑一次 Sideloadly，App 内的历史记录不会被清空。

## 本地小模型

- 模型不随 App 打包，安装后进入「设置 → 本地小模型」下载（约 1.2GB，国内镜像优先）或「本地导入」。
- 未下载模型时走云端；无 API Key 或未开联网时自动回退本地，若也未下载会给出明确提示。

## 备注

- 若 `llama.cpp` 的 `master` 分支 API 有变动导致 `LlamaCPP.swift` 编译报错，请对照所安装版本 `llama.h` 校正参数名（集中在 model/context 参数与采样器）。