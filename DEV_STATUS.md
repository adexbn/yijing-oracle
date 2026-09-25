# 开发状态（唯一权威）

> 本文件是「周易小卦」项目的进度与状态台账，**每次有实质改动就追加/更新**。
> 目的：对话上下文被压缩、跨设备、换模型协作时，都能靠这一份文件接着干。
> 规则：只写事实，不写推测；结论必须标注「已验证」或「估计」；数字要带口径。

- 最后更新：2026-09-25
- 最近提交：`1d93d4e`（分支 `main`，已 push；更准确以 `git log` 为准）
- 待提交：无 —— `1d93d4e` 已含「iOS 真正开启思考（1.3.0 / build 8）」那批改动；本轮新增的 `docs/MODEL_SELECTION.md` 随本条提交一并入库
- 文档索引：`docs/PERF_AUDIT.md`（性能审计与端侧速度调研）、`docs/IOS_LOG_CHECKLIST.md`（iOS 真机日志取证清单）、`docs/MODEL_SELECTION.md`（**模型选型调研：为什么只值得试 Qwen3.5-2B、换模型要动哪些代码、在线试用入口**）、`docs/ANDROID_PERF_OPTIONS.md`（安卓加速路线调研）
- 远端：https://github.com/adexbn/yijing-oracle （public）
- 本机仓库路径：工作区下的 `yijing-ios/`（内含 `ios/` 与 `android/`）

---

## 1. 当前状态一览

| 端 | 功能状态 | 编译验证 | 备注 |
| --- | --- | --- | --- |
| iOS | 输入有效性拦截（P3 规则闸）已接入；**已真正开启 Qwen3 思考模式**（对齐 Android 与硬约束） | ✅ 已装机可跑的是 **`version=1.2.0 build=7`**（真机实测通过）；本轮改为 **`1.3.0 / build 8`**，[待验证] 需 CI `iOS Build` 编译 + 真机复测 | 产物 `Yijing-adhoc-ipa`。**v1.2.0 真机实测**：首次解卦 **20.6s**（含首次 Metal 着色器编译 16.9s）、第二次 **2.8s**，生成 **42.8–45.8 tok/s**，是 Android 真机 18.37 tok/s 的 **2.3–2.5 倍**；旧日志实证的 5 条 bug 全部消失。本机是 Windows 无 Xcode，Swift 改动只能靠 CI，**故 1.3.0 尚未编译验证**。**遗留 1 项（详见第 5 节）**：**启动预热未生效**（非思考模式已在 1.3.0 修掉） |
| Android | 输入有效性拦截（P3 规则闸）已接入，与 iOS 同源同表；**本地推理已从 llama.cpp 整体换到 MNN 3.6.1（OpenCL 优先，逐级回退到 CPU）** | ✅ 本机 `gradlew assembleDebug` 通过并出 APK（**25 576 721 B ≈ 24.39 MB**，产物时间 2026-09-25 14:37，两个 ABI 各 10 个 `.so`）；✅ 解包 APK 的 `classes*.dex` 直接核实新词表已入包；✅ 382 条语料等价性 0 差异（本轮复跑） | ⚠️ 真机 `perf-trace.log` 已到，**「旗舰机反而更慢」结案**：端到端 32.45s 里 decode 25.85s（80%），其中**约 68% 是用户看不到的思考段**；`backend=opencl` 未回退、配置与源码预期完全一致 ⇒ **器件无问题，是 think 段占 decode 多数、且无流式所致**。详见 `docs/PERF_AUDIT.md` 第 6 节。**思考保持开启（硬约束）** |

> 2026-09-25 真机反馈（小米18 Pro Max / 高通 2nm）四个问题：阴阳图错、合法提问被判无效、思考过程泄漏、旗舰机更慢。
> 前三个已在 `0f97219` 修掉（详见第 6 节）；**第四个已定位结案**（同上，`docs/PERF_AUDIT.md` 第 6 节），结论是「跨端口径不可比 + 思考段占 decode 多数 + 无流式」，**未改任何性能参数**。

## 2. 进行中 / 待办

1. ~~**（最高优先，等数据）旗舰机反而更慢**~~ **已结案（2026-09-25）**：拿到真机 `perf-trace.log`，端到端 32,452ms = 会话重载 1,962 + prefill 2,149 + decode 25,852（475 token · 18.37 tok/s）+ 采样收尾 2,467。`backend=opencl` 未回退、`MNN 生效配置` 与源码预期完全一致 ⇒ **器件本身没有掉档**；25.85s 的 decode 里约 68% 是思考段（507 字 / 763 字），用户能看到的答案只占约 8.2s。两端**输出上限不同**（iOS 320 token / Android 768，`LlamaCPP.swift:214` / `LocalAiClient.kt`），端到端时长本来就不可比。详见 `docs/PERF_AUDIT.md` 第 6 节。**未改任何性能参数**。
   > ⚠️ **2026-09-25 更正**：本条此前写「iOS 侧是空思考块 = `enable_thinking=false`」「且 iOS 2s 最可能是走云端」，两条推论均被用户否掉（原话「iOS没有启用云，并且启用了思考，别瞎说」），**已撤回**。代码侧事实：`AppSettings.swift:17` 的 `cloudEnabled` 默认为 `false`、`cloudOn` 还需 Keychain 里有 apiKey ⇒ 用户「未启用云」与代码一致；iOS 侧**从来没有过 `enableThinking` 开关**（`git log --all -S'enableThinking' -- ios` 返回空），但 `LlamaCPP.swift:418-424` 至今拼的是空思考块、`c52f4b6` 提交标题也自称「非思考模式预填」—— **「iOS 实际是否在思考」已于 2026-09-25 用真机日志判定：当前为非思考模式**。依据：用户回传的 `yijing_log.txt`（1.2.0 build=7）两次 `STEP 8: 输出预览` 都是纯答案（「你抽到的卦是蹇…」）、全文 grep `think` **零命中**；该行打印的是 `stripThinking` **之前**的原始输出（`LlamaCPP.swift:382` vs `LocalAiClient.swift:58`），且 `applyChatTemplate():422` 末尾拼的正是官方 `enable_thinking=false` 用法的**空思考块**。**iOS 重开思考已实施（2026-09-25，版本 1.3.0 / build 8，待 CI 编译验证）**：按用户拍定「给我一个启用思考的版本」，已删掉 `applyChatTemplate()` 末尾的空思考块，并新增 `LlamaCPP.enableThinking = true` 开关；同时按 Qwen 官方成套推荐把采样参数按模式切换 —— 思考模式 `temp 0.6 / top_p 0.95 / top_k 20`、非思考 `0.7 / 0.8 / 20`；token 预算由 320 提到 **768**（思考段与答案共用），并在 `LocalAiClient` 加「空结果兜底」—— 若思考段吃光预算导致答案为空，自动用 **1536** 重跑一次。代价 `[估计]`：输出由 85/110 token 涨到约 475，生成时长 2.8s → 约 11s（端到端约 11.2s，仍远快于 Android 真机 32.45s）。详见 `docs/PERF_AUDIT.md` 第 9.1 节。
2. **Android 真机实测**：装机验证三条路径 —— 危险输入直达安全提示（不调模型）、非有效提问软引导、正常提问照常解读；并顺带验证本轮三个修复（阴阳图、节日词表、思考回显）。
3. ~~**Python 参考实现回写**~~ **已完成（2026-09-25）**：`ANCHOR` / `THING` / `INTENT` 三份已逐词对齐 —— 参考实现的 `INTENT` 补上 `怎样` / `咋办` / `怎么办` / `如何是好` / `能行不` / `行吗` 6 个词（此前只有 Kotlin/Swift 有，逐词比对确认差异仅此一处）。补齐后复跑三端：382 条（**编译真实 `InputGuard.kt`**）判定**不一致 0 条**、各批准确率仍为 A 100/100、B 40/40、C 20/20、D 120/122、E 86/100、E 批名单外多放行 0 条；iOS 侧自动抽取复刻**不一致 0 条**；V 批 12 条三端**互不一致 0 条**。
4. **（待用户确认）`verify_cases.tsv` #12 `中秋出去玩` 的 gold 标注**：gold=invalid，但 Kotlin / Swift / Python 参考**三方一致判 valid**。经核查它走的是 `THING` 弱证据分支（`出去玩` 命中 + 文案 ≥5 个码点），属设计内的「省略主语问自己」场景，逻辑自洽；倾向认为 gold 标注过严（该句本身是可占问的处境）。**未强改代码迁就 gold**，等用户拍定是改 gold 还是收紧判据。
5. **（悬置）第二道闸**：App 侧本地小模型闸（Qwen3-1.7B）尚未接；只在第一道规则闸漏放时才有必要触发。
6. ~~**CI 能否编出 native 桥（未验证）**~~ **已解决**：Android CI 跑在 ubuntu runner 上，`assembleDebug` 需要 NDK `27.2.12479018` + CMake `3.22.1`。实测 AGP 会经 `sdkmanager` 自动拉取，无需改 workflow —— push 后 `Android Build`（run `36097827077`，sha `cd849b2`）**7 步全绿**，含「编译验证（assembleDebug）」与「上传 APK 产物」。
7. **（新增，待真机验证）Android debug log 入口**：日志文件在 `getExternalFilesDir(null)/perf-trace.log`（= `/storage/emulated/0/Android/data/com.yijing.app/files/perf-trace.log`；该目录不存在时退回内部 `filesDir/perf-trace.log`），另有 Logcat 通道 `adb logcat -s YijingPerf`。正确操作顺序：**长按首页「我的」→ 设置页底部「日志与诊断」→ 点「调试日志（点开即开启埋点）」→ 面板展开且埋点已开 → 回去解一卦 → 再回来点「分享日志文件」**。修复已在上表第 6 节首行，**待装机确认**。注意 Android 11+ 文件管理器看不到 `Android/data/`，只能用面板里的分享按钮或 `adb pull`。
8. **（新增，等用户实测）端侧模型是否换成 Qwen3.5-2B**：调研结论见 `docs/MODEL_SELECTION.md`（只值得试 Qwen3.5-2B；iOS 现役 llama.cpp `b10809` 已原生支持 `qwen35` 架构与 Metal `gated_delta_net` 内核 ⇒ **无需换引擎**）。**是否动手取决于用户实测**：若语义「对文字的理解不亚于 Qwen3-1.7B」再换。该文档第 5 节留了 4 条 `[待验证]`：用户实测语义、iOS 真机验证 XCFramework 是否真含该内核、换模型必须同改采样参数、Qwen3.5 的 `/think` 软开关与 Qwen3「空思考块」机制是否等价。

## 3. 输入拦截（P3）方案与验证口径

三道闸设计：① P3 规则闸（~0ms，锚点 ∧ 提问意图同现即放行）→ ② Qwen3-1.7B 本地闸（~733ms，估计只有 14% 输入会触发）→ ③ 输出层槽位化模板吸收残余。

批次成绩（规则闸 / 小模型 / 事后语义）：

| 批次 | 规模 | 规则 | 小模型 | 语义 | 口径 |
| --- | --- | --- | --- | --- | --- |
| A 调参集 | 100 | 100 | — | 100 | 已用于调参 |
| B 留出集 | 40 | 40 | — | 40 | 干净留出 |
| C 全新集 | 20 | 20 | — | 20 | 干净留出 |
| D 真实语料 | 122 | 120 | — | 122 | 部分用于调参 |
| E 新闻/招聘 | 100 | 34 | 86 | 100 | 并集 91% |

> **口径警告**：A/D/E 参与过调参，其数字只能当可行性证据；真正的干净留出集只有 B(40) 与 C(20)。「90% 兜底」是设计目标与估计值，不是已验证结论。

**两端等价性（各自独立验证，目标 0 差异）**：

| 端 | 验证方式 | 结果 |
| --- | --- | --- |
| iOS | 从 Swift 源**自动抽取**种子表与正则（避免手抄漂移），在 Python 里逐句复刻 Swift 流程，与打补丁的参考实现对照 382 条 | 判定不一致 **0** 条 |
| Android | **直接编译真实 `InputGuard.kt`**（gradle 缓存里的 kotlin-compiler-embeddable，本机无 kotlinc）在 JVM 上跑同一批 382 条 | 判定不一致 **0** 条；各批准确率与参考完全相同（A 100/100、B 40/40、C 20/20、D 120/122、E 86/100） |

> Android 侧另有 30 条「判定一致、但原因文案不同」，差异仅在参考实现多打了相似度数值（如参考 `[武器爆炸] 原字命中「炸弹」1.00` vs Kotlin `词表[武器爆炸]「炸弹」`），**不影响拦截结果**。

**真机级 V 批（12 条，2026-09-25 新增）**：真机反馈暴露了词表缺口，因此补了一批「真机会输入」的用例（`verify_cases.tsv`，12 条，8 条 gold=valid / 4 条 gold=invalid，含 `中秋该出去玩吗`、`假期怎么安排`、`周末有什么电影` 等）。核对面扩到三端：**Kotlin 真源编译 / Swift 源自动抽取 / Python 参考，三方判定互不一致 0 条**。本轮词表调整后，`周末有什么电影` 由误放行改为 `invalid`（与 gold 一致）；剩 1 条 `中秋出去玩` 与 gold 不一致，见第 2 节待办第 4 条。**2026-09-25 又补齐了参考实现的 `INTENT` 6 个词，三份表已逐词一致，复跑仍为 0 不一致。**

> **覆盖度警告**：382 条主回归语料对本轮增删的词（`周末` `中秋` `端午` `清明` `七夕` `元宵` `重阳` `假期` `节日` `出去玩` 等）**零覆盖**（全库 grep 无命中）。所以 382 条的「0 差异」**不能**当作新词表的证据，新词只能靠这 12 条 V 批 + 真机验证。补语料时应优先往这些空格上填。

平台差异的替代方案（两端一致）：无 `pypinyin` → 用 `DANGER_VARIANTS` 变体/谐音等价表（26 条）顶替音节比对；无 OpenCC → 同表内含繁体写法。

Java 与 Swift/Python 的语义差异（Kotlin 侧已显式补偿）：Java `\w`/`\b`/`\d` 默认 ASCII 语义，Swift(ICU)/Python 是 Unicode 语义 → 引入 `WB` 字符类常量，`\b` 改写为 `(?<!WB)` / `(?!WB)` 前后瞻；字符计数统一用 `codePointCount`；Unicode 分类用 `Character.getType()` 对应 Swift `generalCategory` / Python `unicodedata.category`。


## 4. 构建与验证怎么做

| 端 | 本地可否编译 | 正式验证路径 |
| --- | --- | --- |
| Android | 可以（Windows 即可） | `cd android; ./gradlew.bat assembleDebug`；CI `Android Build` |
| iOS | **不可以**（本机是 Windows，无 Xcode/swiftc） | 只能 `git push` 触发 CI `iOS Build`（macos-15 + Xcode 16.4） |

CI 细节：`.github/workflows/ios.yml`（workflow `iOS Build`，id `358457133`）流程为 XcodeGen 生成工程 → 下载 llama.cpp XCFramework b10809 → 编译（不签名）→ 归档 → Ad-hoc 签名 → 打包 → 上传产物 `Yijing-adhoc-ipa`。`.github/workflows/android.yml`（`Android Build`，id `358478805`）跑 `assembleDebug`，产物 `Yijing-debug-apk`。两端均带 `paths` 过滤，改哪端跑哪端。

**APK 产物清单（2026-09-25 15:10 核验：`aapt2 dump badging` + 解包逐项核对）**：本机工作区躺着 3 个包，**只有一个是当前有效的**，另两个是历史包。两个 debug 包的 `versionCode` / `versionName` **完全相同**（都是 `4` / `1.3-debug`），光看版本号分不出来，只能看时间或内容。

| 文件 | 大小 | 时间 | 版本 | 含什么（已解包核对） |
| --- | --- | --- | --- | --- |
| `android/app/build/outputs/apk/debug/app-debug.apk`（工作区根 `yijing-android-debug-logfix.apk` 是它的副本） | 25 576 721 B | 2026-09-25 14:55 | `1.3-debug` / 4，arm64+armv7 | `cd849b2` MNN 迁移 + `0f97219` 三个真机修复 + **尚未提交的 debug log 修复** —— 目前唯一「全都带上」的包 |
| `yijing-android-mnn-debug.apk`（工作区根） | 25 457 415 B | 2026-09-25 13:10 | `1.3-debug` / 4，arm64+armv7 | 只有 `cd849b2`；`0f97219` 的三个修复与 debug log 修复**都不在** |
| `android/app/build/outputs/apk/release/app-release.apk` / `Yijing-android-1.3.apk` | 11 866 822 B | 2026-09-16 19:06 | `1.3` / 4，**native-code 仅 arm64-v8a** | 陈旧 release：native 库只有单 ABI，与 MNN 迁移（两个 ABI 各 9 个 `.so`）不符 → **迁移前**的包；9-25 的任何修复也不在 |

核验产物内容有三种手段（**时间戳不足为凭**，见第 5 节）：① 解 `classes*.dex` 搜特征字符串（验证词表增删、提示词文案）；② 解 `res/layout/*.xml` —— 布局里的字面量**不进 `resources.arsc`**，只查 arsc 会漏；③ `dexdump -d` 数方法体内某条 `invoke-*` 的出现次数（如 `TaijiProgressView.drawTaiji` 里的 `fill:(IF)V`：13:10 旧包 5 次、14:55 新包 6 次），这是代码类改动唯一可离线取证的途径。

**本机 native 编译工具链（2026-09-25 复查，已补齐）**：Android SDK 里 `build-tools` 有 34.0.0 / 35.0.0 / 36.0.0，`platforms` 有 `android-34` 与 `android-37.0`；**NDK `27.2.12479018` 与 CMake `3.22.1` 均已安装**（`app/build.gradle.kts` 里 `ndkVersion` / `externalNativeBuild.cmake.version` 就是钉的这两版），`android/local.properties` 只需 `sdk.dir`，**不必再补 `ndk.dir` / `cmake.dir`**（本次实跑已验证）。因此 Android 侧 native 桥可以直接在本机编译，无需下沉到 CI。

### MNN 3.6.1 迁移要点（已验证事实，勿凭记忆改）

| 项 | 事实 | 依据 |
| --- | --- | --- |
| 引擎来源 | 用 MNN 官方 Android 预编译包，**不自编 MNN**；只需自编 JNI 桥 | `jniLibs/<abi>/` 共 9 个：`libMNN.so` `libllm.so` `libMNN_CL.so` `libMNN_Vulkan.so` `libMNN_Express.so` `libMNNAudio.so` `libMNNOpenCV.so` `libmnncore.so` `libc++_shared.so` |
| 模型文件 | 5 个：`config.json` `llm_config.json` `llm.mnn` `tokenizer.txt` `llm.mnn.weight`，合计 **1 235 520 567 B（≈1.15 GiB）** | `ModelManager.FILES` / `TOTAL_BYTES` |
| 思考开关 | MNN 上唯一的开关是 **`jinja.context.enable_thinking`**；提示词尾巴 `/no_think` 在 MNN 里**已被证明是空操作**（全仓库 grep 命中 0） | 本机 MNN 源码 `nativekit/MNN/` |
| 思考开关的性质 | 它是**建 session 时的参数**，改它必须重建 session，不能运行中切换 | 同上 |
| 硬约束 | 用户明确要求**不能关思考**，故 `LocalAiClient.ENABLE_THINKING = true`，且 `MnnConfig.validate()` 会在被改成 false 时直接报错 | 用户原话「不能关闭思考，否则输出质量太差」 |
| `tmp_path` 必要性 | 非 CPU 后端时引擎会 `setCache(tmpPath + "/mnn_cachefile.bin")`，`tmpPath` 为空会退化成 `"."`，**Android 上不可写**。故必须给可写目录 | 源码 `transformers/llm/engine/src/llm.cpp` `setRuntimeHint()` |
| `tmp_path` 取值 | 用 `context.filesDir/mnn`（MNN 自家 App 用的是 `filesDir/tmps/...`） | `MnnLlmChat` Kotlin 侧 `MmapUtils` |
| `power` 不能设 `low` | MNN 的低功耗探测只认 Adreno；设 `low` 可能把**华为 Mali** 机型顶回 CPU，故用 `"normal"` | MNN 源码 + 用户「主要是高通+华为」 |
| OpenCL 线程位 | OpenCL 走 buffer 模式时引擎会自行把 `numThread` 或上 `64 \| 512`，Kotlin 侧不要重复设 | `llm.cpp` `initRuntime()` |
| 后端回退只能写在 Kotlin | `MNNGetExtraRuntimeCreator` / `Runtime` 不在随包发布的头文件里，C++ 侧无法预探测；且 `Schedule::getAppropriateType` **不会**在「有 OpenCL creator 但设备没 OpenCL 驱动」时自动回退 | 随包 `cpp/include/MNN/*` 逐个核对 + 源码 `Schedule.cpp` |
| 回退阶梯 | 实际实现为三级：`opencl+mmap` → `cpu+mmap` → `cpu+不用 mmap`；靠 `MnnSession.open()` 抛 `MnnException` 触发下一档 | `LocalAiClient.openWithFallback()` |
| 埋点 | `PerfTrace` 原先调 llama 的 `getSystemInfo()`，已换成 MNN 版本号 + 当前 backend | `PerfTrace.enable()` |

**关键落点**：`AndroidManifest.xml` 里的 OpenCL 声明**必须挂在 `<application>` 下**（详见第 5 节，这条是踩出来的）。

工程用 XcodeGen：`ios/project.yml` 里 `sources: - path: Yijing` 是**目录级自动发现**，新增 Swift 文件不需要手工登记。

本机**没有 `kotlinc`**。要单独跑一段 Kotlin 代码（如等价性回归），用 gradle 缓存里的编译器：`java -cp <jar 列表> org.jetbrains.kotlin.cli.jvm.K2JVMCompiler`。`-cp` 里除了 `kotlin-compiler-embeddable` 还要带上 `kotlin-stdlib`、`kotlin-reflect`、`kotlin-script-runtime`、`kotlin-daemon-embeddable`、`kotlinx-coroutines-core-jvm`、`annotations`（缺 coroutines 会直接 `NoClassDefFoundError`，见第 5 节）。好处是 `InputGuard.kt` 只依赖 JDK，**可以脱离 Android SDK 在 JVM 上直接跑真实源码**做验证，比"照着源文件另写一份模拟"更硬。

### 取 CI 报错的办法（踩过的坑）

- `actions/jobs/{job_id}/logs` 匿名访问**拿不到**（返回需 admin 权限），不要浪费时间。
- 可用：`https://api.github.com/repos/adexbn/yijing-oracle/check-runs/{check_run_id}/annotations`，能给 level + 文件 + 行号。`check_run_id` 先从 `/commits/{sha}/check-runs` 取。
- Actions 日志网页是**虚拟滚动**，`#step:N:line` 只换窗口；把页面文本落到本地文件后离线 grep 更可靠。
- PowerShell 调 GitHub API 需要带 `-Headers @{'User-Agent'='...'}`。

### 提改动前的自检（本机无 swiftc，只能静态查）

- 字符串字面量闭合检查（三态扫描 out/str/raw）——**这条是血的教训，见第 5 节事故**。
- 括号配平 + 表引用一致性（变体表的 key 必须都在种子表里、不能重复）。
- 与参考实现做逐条等价性对照（382 条语料应保持 0 差异）。
- 以上脚本放在本机会话级工作目录，未入库；如需长期使用应移入仓库 `tools/`。

## 5. 事故与坑（避免重复踩）

| 日期 | 现象 | 根因 | 处置 |
| --- | --- | --- | --- |
| 2026-09-17 | `NoClassDefFoundError: kotlinx/coroutines/CoroutineScope`（调 kotlin-compiler-embeddable 编译时） | `kotlin-compiler-embeddable` 是 shaded 包，但**不含 coroutines**，编译器自身启动就需要它 | `-cp` 里补 `kotlinx-coroutines-core-jvm`（+ `annotations`、`kotlin-daemon-embeddable` 等），见第 4 节 |
| 2026-09-17 | CI 编译失败：`InputGuard.swift:178:49: error: expected '{' to start the body of for-each loop` | 标点字面量里本想写全角引号 `“ ” ‘ ’`，实际落进文件的是 ASCII `"` `'`，字符串提前闭合 | 该段全部改用 `\u{201C}\u{201D}\u{2018}\u{2019}` 显式转义 + 分段拼接；此后提交前必跑闭合检查。**Kotlin 侧同样沿用 `\uXXXX` 转义写好，已规避** |
| 2026-09-25 | `:app:processDebugResources FAILED`，`AAPT: error: unexpected element <uses-native-library> found in <manifest>.`（AndroidManifest.xml:16） | `<uses-native-library>` 被写在了 `<manifest>` 下。**实测 aapt2 只接受它挂在 `<application>` 下**（与 `<uses-library>` 同级），与 AGP 版本无关（本项目 AGP 9.4.0，网上「AGP 4.1 太老」的说法不适用） | 移到 `<application>` 内；用本机 build-tools 的 aapt2 单独跑过两种最小清单做对照（放 `<manifest>` 下 exit=1 报同一句错，放 `<application>` 下 exit=0），与 MNN 官方 `MnnLlmChat` 清单写法一致。随后 `assembleDebug` 通过 |
| 2026-09-17 | 从 Actions 取不到原始编译日志 | `actions/jobs/{id}/logs` 需 admin | 改走 check-runs 注解 + 网页日志落盘离线 grep |
| 2026-09-25 | 加载页阴阳图在真机上画错（退化成「墨球 + 白点 + 小墨点」，看不出 S 形） | `TaijiProgressView.drawTaiji()` 在 `drawPath(halfPath, paint)` 前**漏了一句 `fill(inkColor, alpha)`**，画笔还停在上面「纸色底盘」的状态，右半边被填成纸色 | 在 `drawPath` 前补 `fill(inkColor, alpha)`，并加注释写明失效形态；随 `0f97219` 提交 |
| 2026-09-25 | Kotlin 侧想「剥掉思考过程」时发现引擎帮不上忙 | MNN 的 `stripThinkBlocks` **只清洗提示词缓存**（`prompt_cache_utils.hpp:13`，仅被 `llm.cpp:1235` / `llm.cpp:1418` 调用），**完全不处理 `response()` 的生成文本** | 生成侧必须自己兜底：`LocalAiClient.stripThinking()` 做 5 步清洗 + 指令回显剥离（`INSTRUCTION_ECHO_SEEDS` / `dropInstructionEcho`，阈值 60 字）；随 `0f97219` 提交 |
| 2026-09-25 | 在会话里 `import input_guard_sim` 会**顺手往工作区根目录写 6 个 `输入拦截模拟结果_*.tsv`** | 该脚本把「跑全部批次并落盘」写在了模块顶层，导入即执行（没有 `if __name__ == "__main__"` 保护） | 本次只是核对三端一致性时误触发（文件被按新词表重写，内容实质不变，无损坏）。**已修**：`v_tri_check.py` 改为只 `exec` 规则部分（截到 `DIR = OUT.rsplit` 之前），复跑后那 6 个文件 mtime 仍停在 `2026/9/25 14:36:05`，确认不再落盘。**该脚本顶层缺 `if __name__ == "__main__"` 保护这一点仍在，其余脚本勿退化成整模块 import** |
| 2026-09-25 | Kotlin 等价性回归里「本轮词表改动是否进了 APK」无法只看时间戳判断（gradle 报了 `packageDebug UP-TO-DATE`） | 时间戳/构建日志都不足以证明产物内容 | 写 `apk_dex_probe.py`：解包 APK 的 `classes*.dex` 直接搜特征字符串 —— 新增词（端午/清明/七夕/元宵/重阳）命中、已删词（周末/机票/民宿）不命中，才算证据 |
| 2026-09-25 | 改了 `LocalAiClient.kt` 后跑 `gradlew :app:compileDebugKotlin`，任务报 **`UP-TO-DATE`**，看着像「编译通过」其实**根本没编**（文件 mtime 15:17:07、`git diff` 明确有 +5 行，源码确实落盘了） | Gradle 的 up-to-date 判定这次没被源码变更失效（VFS/快照层面的问题，与本项目改动无关） | **验证编译必须用 `--no-build-cache --rerun-tasks` 强制真跑**：加了这两个参数后 `compileDebugKotlin` 实际执行、**BUILD SUCCESSFUL in 36s**。看到 `UP-TO-DATE` 一律不算验证过 |

| 2026-09-25 | 真机反馈「Android debug log 功能坏了、打不开，按了按钮之后没有 log」 | 两层错位，**都不是接线 bug**：① 埋点 `PerfTrace.enabled` **默认关闭**，而设置页那颗「Debug 日志」按钮**只展开/收起面板、不点亮埋点** —— 用户按了按钮以为已在记录，实际一条不写；② 日志落在 `getExternalFilesDir(null)/perf-trace.log`，即 `Android/data/com.yijing.app/files/`，**Android 11+ 起该目录对文件管理器不可见**，即使写了也「打不开」 | 已改 `SettingsActivity.setupDebugSection()`：点开面板时若埋点未开则一并 `PerfTrace.setEnabled(true)`，并 Toast 出日志绝对路径；面板改为**每次打开都重建**（旧写法只在首次构建，报告会陈旧）；按钮文案改「调试日志（点开即开启埋点）」。**核实过没有接线问题**：`YijingApp` 已在 `AndroidManifest.xml` 注册（`android:name=".YijingApp"`）并调 `attach()`；`setupDebugSection()` 在 `onCreate` 被调；`res/xml/file_paths.xml` 已含 `external-files-path`；`describeRuntime()` 有 `runCatching` 兜底不会抛 |
| 2026-09-25 | 想取结果页的性能报告，发现 `aiPerf` 这个 extra 没人读 | `d1b59c6` 把 `PerfPanel` 从结果页撤到设置页，但 `MainActivity` / `LoadingActivity` 仍在 `putExtra("aiPerf", ...)`，全仓库**没有任何 `getStringExtra("aiPerf")`** | **未清理**（无害死代码，只多头一次读启动时的 MNN 引擎探测）。清理时注意：`PerfTrace.report(scope)` 在埋点关闭时返回空串，删掉不影响功能 |

| 2026-09-25 | 真机 `perf-trace.log` 显示「首页预加载已于 27s 前完成」，但解卦时仍 `释放旧会话`(324ms) + `会话加载`(1635ms) 重建 —— 预加载白做 | `LocalAiClient.SessionKey` 写成 `private class`（**不是 `data class`**），类体里只有 `describe()`，全文件无 `equals`/`hashCode` 覆写；而 `sessionFor` 用 `cachedKey == key` 判命中 —— **普通 class 的 `==` 是引用比较**，`key` 又是每次调用 `new` 出来的 ⇒ 判定恒为 false，命中分支「命中常驻缓存，未重新读盘」是**死代码**。由 `cd849b2`（MNN 迁移）引入 | 改 `private data class SessionKey` 并加注释写明「必须是 data class」。**注意：命中分支此前从未在生产中执行过**，改动后需真机复验「连续两次解卦结果正常、第二次要打印『命中常驻缓存』」 |
| 2026-09-25 | 真机 `perf-trace.log` 无法解释旗舰机为何慢 | 埋点里**分段计时与累计计时混用**：`释放旧会话` / `等待推理锁` 是分段（本地 t 差值），`定位模型文件` / `等待会话就绪` 是从 t0 累计 —— 不读代码会把 325ms、1962ms 直接相加而重复计算 | 已交叉验证无重复：`定位模型文件` 325ms ⊃ `释放旧会话` 324ms；`等待会话就绪` 1962ms = 定位 325 + 会话加载 1635 + 2ms 舍入。**读这类日志前先确认每个 mark 是分段还是累计**（`LocalAiClient.kt:271-318 / 352-402`） |

| 2026-09-25 | iOS 新版真机日志里，两次解卦的**原始输出都是纯答案**（`STEP 8: 输出预览 = 你抽到的卦是蹇…`），全文 grep `think` **零命中** —— 而此前一直以为 iOS「启用了思考」 | `applyChatTemplate()`（`LlamaCPP.swift:418-424`）在 assistant 段末尾**固定拼了 `"<think>\n\n</think>\n\n"` 空思考块**，这正是 Qwen3 `tokenizer_config.json` 里 `enable_thinking=false` 的官方写法（源码注释 `:410-417` 已写明）⇒ **模型一开头就被判定「思考已结束」，直接作答**。iOS 侧从未有过 `enableThinking` 开关 | **新增待办**：iOS 开思考后需真机复测「原始输出里确实出现 `<think>` 段」+ 端到端时长是否落在 11s 量级。**现状认定（v1.2.0 时期）：iOS 是非思考模式**（有原始输出为证）。**已处置（2026-09-25，1.3.0 / build 8）**：删掉 `applyChatTemplate` 末尾空思考块（改为 `if !enableThinking` 条件拼接），新增 `enableThinking = true`；采样参数成套切成思考模式（`temp 0.6` / `top_p 0.95` / `top_k 20`）；`maxTokens` 320 → **768**，并在 `LocalAiClient` 加空结果兜底（答案为空则用 **1536** 重跑）；新增两条日志 `STEP 5: 思考模式` 与 `STEP 5: 采样参数` 便于真机取证。代码改动 [已验证落盘]，编译与真机表现 [待验证]（本机无 Xcode，只能靠 CI） |
| 2026-09-25 | iOS 启动预热「没起作用」：`YijingApp.init()` 里 `+2s` 的 `warmUp()` 本应在启动 2 秒后编译 Metal 库，但日志从 15:51:03.537（App 启动）**直接跳到** 15:51:47.140（用户第一次点解卦），中间 44 秒**一行预热日志都没有**；16.883s 的 Metal 着色器编译最终仍落在用户首次解卦的等待里 | **原因待验证**。已排除的：`warmUp()`（`LlamaCPP.swift:390-392`）→ `ensureBackend()`（`:143-154`）**必定**打印 `STEP 1: llama_backend_init 前…`，日志无此行 ⇒ 预热根本没执行到后端初始化；`init()` 本身确已执行（`App 启动` 行即出自它）。**唯一静默出口**是 `YijingApp.swift:14` 的 `guard ModelManager.isDownloaded() else { return }`；但 `isDownloaded()`（`ModelManager.swift:45`）= 文件大小 > `minModelBytes`，而该文件在 15:51:47 明确存在且为 1 282 439 328 B ⇒ 逻辑上不该返回 false。**注**：第二次解卦 `STEP 1: 后端已初始化，跳过` 证明后端只在首次解卦里才被初始化 | 定位手段：在 `warmUp()` 调用点与 `guard` 两支各补一行日志，再跑一次冷启动即可区分「预热被 guard 拦掉」还是「预热已执行但 `ensureBackend()` 内部提前返回」。**在拿到该日志前，不得断言原因** |

其他已知约束：本地小模型不随 App 打包，装机后由用户在「设置 → 本地小模型」下载或导入。**两端用的不再是同一套模型格式**：iOS 仍走 llama.cpp + GGUF（约 1.2GB，锁定在仍含 `Package.swift` 的 revision，升级需对照新 `llama.h` 校正 `LlamaCPP.swift` 参数名）；Android 已换 MNN 3.6.1，模型是该框架自己的 5 文件组合（`config.json` / `llm_config.json` / `llm.mnn` / `tokenizer.txt` / `llm.mnn.weight`，合计 1 235 520 567 B），由 `ModelManager` 双源下载（hf-mirror 优先，ModelScope 兜底）。

## 6. 进展日志（新→旧）

| 日期 | 提交 | 内容 | 验证 |
| --- | --- | --- | --- |
| 2026-09-25 | （本轮提交） | **端侧模型选型调研 + 引擎路线定案，新增 `docs/MODEL_SELECTION.md`**（回答用户四问：换哪个模型 / 有没有在线试用地址 / 换模型门槛 / 是否转 MLC-LLM 或高通 QNN）。结论：① **候选里只值得试 `Qwen3.5-2B-Instruct`** —— 官方 benchmark 在**思考模式**下几乎全面优于现役 `Qwen3-1.7B`（MMLU-Pro 56.5→**66.5**、C-Eval 68.1→**73.2**、GPQA 40.1→**51.6**、IFEval 72.5→**78.6**、IFBench 26.7→**41.3**、LongBench v2 26.5→**38.7**），而本项目硬约束是「必须开思考」⇒ 净收益成立；`Qwen3.5-0.8B` 是**降级**（非思考 MMLU-Pro 29.7 vs 40.2）；`Gemma-2-2B-it / Gemma-2B` 是 **2024 老代**（要评 Gemma 应评 **Gemma 4 E2B**，2.3B effective / 5.1B total，且多方横评称其中文「一般至良好」）；`Llama-3.2-3B-Instruct` 中文非原生、同档落后 Qwen2.5-3B ⇒ 不推荐。② **iOS 换 Qwen3.5-2B 不需要换引擎** —— CI 锁定的 llama.cpp **`b10809`** 已原生支持：`src/llama-arch.cpp?ref=b10809` 含 `{ LLM_ARCH_QWEN35, "qwen35" }` 与 `case LLM_ARCH_QWEN35:`，`ggml/src/ggml-metal/kernels?ref=b10809` 含 **`gated_delta_net.metal`（8567 B）**（master 上已更新到 9402 B）；Android 侧 MNN 官方亦支持 Qwen3.5-2B 导出。③ **MLC-LLM（TVM）不做**（换它 = 两端同时换推理栈 + 换模型格式 + 全部重测，且未证实支持 Qwen3.5）；**高通 QNN 不采纳为主路线**（目标含华为需两套路径；decode 是内存带宽受限而非算力受限，已有 5 条独立证据表明安卓 GPU/NPU decode 未必快过 CPU；`MNN_HEXAGON` 只支持 4-bit 对称量化 + 需 `libMNN_htpops*.so` + 对华为无效）。**本文档未改任何代码**；换模型须同改采样参数（Qwen3.5-2B 官方思考档 `temp 1.0 / top_p 0.95 / top_k 20 / presence_penalty 1.5`，与现役 0.6/0.95/20 不同），改动清单见该文档第 3 节 | ① **一手证据（GitHub API 逐 tag 实查，非二手转述）**：`contents` 接口列 `src/llama-arch.cpp`（`ref=b10809`，base64 解码 81 892 B）确认 `qwen35` / `qwen35moe` 架构表项与 `case LLM_ARCH_QWEN35:` 分支；列 `ggml/src/ggml-metal/kernels?ref=b10809` 确认 `gated_delta_net.metal` / `ssm.metal` / `solve_tri.metal` 三个内核在该 tag 已存在（tag sha `5266f24da75dc449bd56cbed7addb9c8e4a6a73e`）；② 上游时间线：`b7976` 加入 Qwen3.5 dense+MoE（#19435）、`b8233` 加入 `GATED_DELTA_NET` 算子（#19504，CPU/CUDA）、`b9667` Vulkan 支持 gated_delta_net；③ 模型事实取自 HF 官方模型卡（Qwen3.5-2B 为 Dense、Image-Text-to-Text、Apache 2.0、Context 262 144、默认 non-thinking）与官方 benchmark 同表对照；④ 试用入口可用性实测：`openrouter.ai/api/v1/models` 返回 460 条（含 `qwen/qwen3.5-plus-*`、`google/gemma-4-26b-a4b-it`、`google/gemma-4-31b-it`，**无 qwen3-1.7b / qwen3.5-2b / gemma-2-2b**）、`chat.qwen.ai/api/v2/models` 实测只有 3 个（`qwen3.7-plus` / `qwen3.8-max` / `qwen3.8-omni-flash`）⇒ **免费官方 Chat 拿不到小模型，小模型只能本地 Ollama/GGUF 或 HF Spaces**；`curl.exe -w "%{http_code}"` 实测 `chat.qwen.ai` 200、`openrouter.ai/chat` 200；⑤ Ollama 库页实查 `qwen3.5:2b`（2.7 GB / 256K / Text+Image）与 `qwen3:1.7b` 可直接对照 |
| 2026-09-25 | （本轮提交） | **iOS 真正开启思考模式（1.3.0 / build 8）+ 高通专项可行性核查 + 端侧小模型速度调研**。① **iOS 开思考**：删掉 `applyChatTemplate()` 末尾那个等于 `enable_thinking=false` 的空思考块（改 `if !enableThinking` 条件拼接），新增 `LlamaCPP.enableThinking = true`；采样参数按 Qwen 官方成套切换（思考 `temp 0.6 / top_p 0.95 / top_k 20`，非思考 `0.7 / 0.8 / 20`）；`maxTokens` 320 → **768**，`LocalAiClient` 加「答案为空则用 1536 重跑」兜底；新增日志 `STEP 5: 思考模式`、`STEP 5: 采样参数`、思考段字数。**同时解释用户疑问**：iOS 此前输出质量不输 Android 是「两端输出上限不同（iOS 320 / Android 768）⇒ 跨端口径不可比」，而非「非思考模式更强」。② **高通专项**：`MNN_ARM82` 经官方文档核实是**纯 CPU 侧 ARMv8.2 FP16 后端、默认已 ON**，与 OpenCL/Adreno 无关（用户猜测不成立）；`MNN_OPENCL` 已在用（官方预编译包含，真机日志 `backend=opencl` 实证）；真正的高通专属路径是 `MNN_HEXAGON`，但只支持 4-bit 对称量化且需 `libMNN_htpops*.so` + 自编引擎，且对华为无效 ⇒ **不建议现在做**。**本次未改任何编译宏或性能参数**。③ **端侧速度调研**：iOS 45 tok/s ≈ A19 Pro 带宽峰值（75.8 GB/s）的 60%；Android 18.37 tok/s ≈ SD 8 Gen 3 带宽（61.9 GB/s）的 31%（有效带宽约 19 GB/s，未打满）；另有 5 条独立证据表明**安卓 GPU/OpenCL 的 decode 未必快过 CPU**，且热节流会吃掉三到四成 | ① 三处 iOS 改动 **grep 逐行核实已落盘**（`LlamaCPP.swift` :217/:224/:285/:294-295/:303-304/:307/:439/:444；`LocalAiClient.swift` :41/:46/:65-83/:98）；② **编译与真机 [待验证]** —— 本机 Windows 无 Xcode，须靠 push 后 CI `iOS Build`；③ `MNN_ARM82` / `MNN_OPENCL` / `MNN_HEXAGON` 语义取自 MNN 官方 cmake 宏表与 LLM 文档原文（抓取一手页面，非二手转述）；④ 带宽数字：A19 Pro 75.8 GB/s（notebookcheck / cpuscores）、SD 8 Gen 3 理论 68 GB/s / 实测最大 61.9 GB/s / 单处理器 40–45 GB/s（arXiv 2501.14794）；⑤ 「GPU 未必快过 CPU」5 条证据：LiteRT-LM 官表（GPU decode 仅 1.11×）、arXiv 2607.05475、arXiv 2605.27435、arXiv 2505.06461、arXiv 2506.10443；⑥ 热节流 arXiv 2603.23640（iPhone 16 Pro 掉 41.5%、稳定 23.7 tok/s）。结论明细见 `docs/PERF_AUDIT.md` 第 9 节 |
| 2026-09-25 | （未提交） | **iOS 新版真机日志核对**（用户回传 `yijing_log.txt`，118 247 B / 1347 行 / 2 次解卦）。结论：① 版本已是 `1.2.0 build=7`；② 旧日志实证的 **5 条 bug 全部消失**（`n_gpu_layers=99` 全层 Metal、`offloaded 29/29 layers to GPU`、`MTL0 model buffer 1050.43 MiB` / `CPU 166.92 MiB`、`MTL0 compute buffer 152.3750 MiB` 非零、两次均 `命中结束符（EOG）正常结束`、`STEP 8: 输出预览` 行存在）；③ 实测性能：首次解卦 **20.6s**（Metal 着色器编译 16.883s + 模型加载 1.185s + ctx 0.08s + 分词 5ms + prompt 解码 595ms/333tok + 生成 1856ms/85tok/**45.8 tok/s**），第二次 **2.8s**（复用模型与 vocab，生成 2571ms/110tok/**42.8 tok/s**）⇒ **iOS 约 43–46 tok/s，为 Android OpenCL 真机 18.37 tok/s 的 2.3–2.5 倍**；④ **发现 2 个真问题**（已记入第 5 节）：iOS 当前**实际处于非思考模式**（原始输出无 `<think>`，全文 grep `think` 零命中）；**启动预热未生效**，16.9s 的 Metal 编译仍落在用户首次解卦的等待里。**本次未改任何代码** | ① 1347 行逐段 grep + read：`App 启动`、`STEP 1/2/4/5/8`、`⏱`、`load_tensors`、`offloaded`、`llama_kv_cache: layer N: dev = MTL0`、`~llama_context` 缓冲统计；② 与旧日志（09-15 / 09-16）逐条对照 5 条 bug；③ 源码对照 `LlamaCPP.swift:390-401`（`warmUp()`→`ensureBackend()` 必打 `STEP 1`，唯一静默出口是调用方的 guard）、`:418-424`（末尾拼空思考块）、`:212-214`（`maxTokens=320`）、`YijingApp.swift:13-16`（`+2s` 触发预热）、`ModelManager.swift:45`（`isDownloaded()` = 文件大小 > `minModelBytes`） |
| 2026-09-25 | （未提交） | **逐项核查用户三问：Vulkan 后端（`-DGGML_VULKAN=ON`）/ `-ngl 99` / ARMv8.2-A·ARMv9-A Vector+Dot Product / `GGML_ARM_NEON`**，结论追加进 `docs/PERF_AUDIT.md` **第 7 节**。要点：① 这三个开关全部是 **llama.cpp / ggml 专有**，而 Android 自 `cd849b2` 起已整体换 MNN 3.6.1（`android/` 下 grep 不到 ggml 代码），**字面上不适用**；② Vulkan **能力已在包内但从未启用** —— `libMNN_Vulkan.so` 与 `MnnLlm.BACKEND_VULKAN` 俱在，但 `LocalAiClient.kt:110` 首选 `OPENCL`，基准套件也只有 OpenCL/CPU 两组（`:520-523`）；③ `-ngl 99` 的等价物在 **iOS 侧已开**（`LlamaCPP.swift:179` `n_gpu_layers = 99` 全层 Metal），Android 的 MNN 无「层卸载」概念；④ 指令集要求 **已满足、且优于编译期 `-march`**（MNN 预编译主体内含 dotprod + i8mm 双套内核，运行期按 CPU 特性分发）；⑤ NEON 已激活（armeabi-v7a 属性实证）。**本次未改任何推理代码或性能参数**（遵守三条硬约束） | ① NDK 自带 `llvm-objdump -d` + 正则计数实测 arm64 `libMNN.so`：`sdot/udot` **756** 条、`.8h` **17 166** 条、`smmla/bfdot` **954** 条；`libllm.so` sdot 0（`.8h` 54）；自编 `libyijingllm.so` 全 0；② `llvm-readelf -A` 实测 armeabi-v7a `libMNN.so`：`CPU_arch` ARM v7 · `FP_arch` VFPv3 · `Advanced_SIMD_arch` **NEONv1**；③ `llvm-strings` 实测命中 KleidiAI 内核符号 `kai_kernel_matmul_*_neon_dotprod` / `_neon_i8mm` 与运行期探测串 `The device supports: i8sdot:%d, fp16:%d, i8mm: %d, sve2: %d, sme2: %d`；④ 源码逐处核对 `LocalAiClient.kt:110/520-523`、`MnnLlm.kt` 后端常量、`LlamaCPP.swift:179`、`cpp/CMakeLists.txt`、`build.gradle.kts`（无 `-march`、无 `-DCMAKE_BUILD_TYPE`） |
| 2026-09-25 | （未提交） | **核实 iOS 侧「有没有新版 / 有哪些 log 实证的 bug」，并新增 `docs/IOS_LOG_CHECKLIST.md`**（用户将在真机上跑新版日志回传）。结论：① 最新 iOS 产物 = CI `iOS Build #27`（run `36104029782`，sha `72b8071`，success，06:41:48Z→06:45:32Z）的 artifact `Yijing-adhoc-ipa`（3 502 054 B，**2026-10-09 过期**）；`72b8071` 之后 `ios/**` 无新提交，故这就是最新；`ios.yml` 带 `workflow_dispatch`，过期后可手动手动重跑刷新。② **该 IPA 相对用户手上那版不含任何新的 iOS 性能改动**（工作区 Swift 文件全干净，「iOS 是否在思考」仍待真机取证）。③ 旧日志实证的老 bug 共 5 条（版本号恒 1、纯 CPU `n_gpu_layers=0`、Metal 零卸载、跑满 1024 token 未命中 EOG、缺「输出预览」行），详见清单第 7 节 | ① 读 `.github/workflows/ios.yml`（触发条件 / artifact 名 / retention 14 天）与 `ios/project.yml`（`MARKETING_VERSION=1.2.0`、`CURRENT_PROJECT_VERSION=7` ⇒ 启动日志应为 `version=1.2.0 build=7`）；② Actions API 取 run 与 artifact 明细，`expired: false`、`expires_at 2026-10-09T06:45:26Z`；③ **匿名下载 artifact zip 实测返回 401** ⇒ 下载须登录 GitHub；④ grep 用户两份 iOS 日志（2026-09-15 / 09-16）：均 `version=1.0 build=1`、`n_gpu_layers=0`、无 `STEP 8: 输出预览`（`git log -S'STEP 8: 输出预览' -- ios` 仅命中 `ec2ce38`）⇒ 旧日志无法判定是否思考，也无法代表当前 Metal 构建速度 |
| 2026-09-25 | （未提交） | **用真机 `perf-trace.log` 定位 b1（旗舰机 32.4s）**：Xiaomi M154FF · arm64-v8a · 8 核 · Android 17，端到端 **32 452ms**。拆解 —— decode 25 852ms（475 token · **18.37 tok/s**）占 **79.7%**、prefill 2 149ms（333 token）、采样 2 413ms、会话等待 1 962ms、其余 ~20ms。**OpenCL 确实被选中**（`会话加载 backend=opencl`、生效配置 `backend_type:"opencl"`；`power:"normal"` 对华为 Mali 安全）。decode 18.37 tok/s 对比 `LocalAiClient` 注释里的 CPU 基线（329 token / 49.90s ≈ 6.6 tok/s）**约 2.8×**（不同题目，只看量级），MNN 迁移方向正确。**锁定 1 个真 bug**：`SessionKey` 非 data class ⇒ 会话缓存永不命中（详见第 5 节）。**本次仍未改任何调优参数** | ① 日志 27 行逐行读；② 埋点口径交叉验证后确认 32.45s **无重复计算**：「定位模型文件」325ms ⊃「释放旧会话」324ms；「等待会话就绪」1962ms = 定位 325 + 会话加载 1635 + 2ms；③ 读 `LocalAiClient.kt:271-318 / 352-402` 确认预加载与解卦**传参完全相同**（`threadCount()`=4、`backend=null`、同一 `configPath`），差异只可能来自引用比较；④ `git log -S "private class SessionKey"` 确认由 `cd849b2` 引入；⑤ 日志中「命中常驻缓存」出现 **0 次**（自证未命中）；⑥ 「非有效提问 → 仍跑满 32s」经查是**设计行为**（`MainActivity.kt:144` 注释：非有效提问带软引导进等待页），非 bug；⑦ 改 `data class` 后本机 `gradlew --offline --no-build-cache --rerun-tasks :app:compileDebugKotlin` **BUILD SUCCESSFUL in 36s**（`compileDebugKotlin` 实际执行，只剩 `MainActivity.kt:218` 一条既有 deprecation 警告） |
| 2026-09-25 | （未提交） | **核验「最新那个 APK 里到底有什么」**（回答「有没有性能优化 / 各 bug 修复 / 太极图 / 词表是否都进了」）：拿 14:55 的 `app-debug.apk` 解包逐项核对，**不靠构建日志与时间戳**；产物清单见第 4 节 | ① 词表：`中秋/端午/清明/七夕/元宵/重阳/国庆/春节/暑假/寒假` 与 THING 弱证据词（`出去玩/旅行/旅游/度假/回老家/走亲戚/团建/露营/爬山`）在 `classes4.dex` 命中；已删词 `周末` `机票` `民宿` 全条目不命中；② 提示词：新 SYSTEM 的「只输出解读本身」「像聊天一样自然」命中，旧 SYSTEM 的「按这个顺序用大白话讲」「字数控制在150字以内」不命中；③ 太极图：`dexdump -d` 数出 `drawTaiji` 里 `fill:(IF)V` **新包 6 次 / 13:10 旧包 5 次**，多出的一次落在偏移 `0x0012`（纸色底盘之后、`drawPath` 之前），反汇编行数 659→662；④ debug log：`res/layout/activity_settings.xml` 有「调试日志（点开即开启埋点）」，`classes5.dex` 有 Toast「埋点已开启 · 现在去解一卦」，旧包两者皆无；⑤ MNN 引擎在包内：20 个 `.so`／解压 16.7 MB，含 `libMNN.so` `libMNN_CL.so`（OpenCL）`libMNN_Vulkan.so` `libllm.so` `libyijingllm.so`，arm64-v8a + armeabi-v7a 两套；⑥ `aapt2 dump badging`：`1.3-debug` / versionCode 4。**注意：旗舰机 25s 的性能项本次仍无进展，该包不含任何新调优** |
| 2026-09-25 | （未提交） | **修 Android「debug log 按了按钮没反应」+ 归档性能核查**：① `SettingsActivity.setupDebugSection()` 改为点开面板时自动点亮埋点、每次打开重建面板并 Toast 日志绝对路径；`activity_settings.xml` 按钮文案改「调试日志（点开即开启埋点）」（详见第 5 节）；② 新增 `docs/PERF_AUDIT.md` —— 逐条核查用户给的三层性能陷阱清单（15 条）与 6 步排错速查表，踩中 5 条 / 未利用杠杆 3 项 / 不踩 4 项。**两份改动都未动任何性能参数**（遵守「CPU 调优已调试过」） | ① 本机 `gradlew :app:compileDebugKotlin` **BUILD SUCCESSFUL in 30s**（仅 `MainActivity.kt:218` 一条既有 deprecation 警告，无关）；② `PERF_AUDIT.md` 内每条已标注 `[已验证]` / `[待验证]`；③ **真机待验证**：日志路径与操作顺序 |
| 2026-09-25 | `0f97219` | **修真机反馈的三个问题（小米18 Pro Max）**：① 加载页阴阳图错 —— `TaijiProgressView.drawTaiji()` 补 `fill(inkColor, alpha)`；② 合法提问被判「非有效提问」—— `InputGuard` 的 ANCHOR 补「节日/假期/中秋/端午/清明/七夕/元宵/重阳」等有具体事由的处境词、剔除纯循环时间词「周末」，THING 剔除可订物品「机票/车票/酒店/民宿」，**Kotlin / Swift / Python 参考三端同源同表同步**；③ 思考过程连系统提示词一起泄漏进答案 —— `LocalAiClient` 的 SYSTEM 重写为 255 字自然叙述版并显式要求「只输出解读本身」，`stripThinking` 增加指令回显剥离（`INSTRUCTION_ECHO_SEEDS` 11 个种子 + `dropInstructionEcho`，阈值 60 字），兜住 MNN 只清提示词缓存的缺口。**性能项（旗舰机 25s）刻意未动，等真机埋点** | ① 编译**真实 `InputGuard.kt`** 在 JVM 跑 382 条：判定不一致 **0** 条，各批准确率 A 100/100、B 40/40、C 20/20、D 120/122、E 86/100 与参考完全一致，E 批名单外多放行 0 条；② 12 条 V 批**三端互不一致 0 条**，`周末有什么电影` 改为 invalid；③ `strip_check` 13/13 OK，两端 SYSTEM 各 255 字逐字一致；④ `cd android; ./gradlew.bat --offline assembleDebug` **BUILD SUCCESSFUL**，产物 APK 25 576 721 B，解包 dex 核实新词入包、旧词已删；⑤ iOS 待 CI `iOS Build` |
| 2026-09-25 | `cd849b2` | **Android 本地推理从 llama.cpp 整体切到 MNN 3.6.1**（OpenCL 优先 → CPU+mmap → CPU 无 mmap 三级回退，**思考保持开启**）：新增 JNI 桥 `cpp/yijing_llm_jni.cpp` + `CMakeLists.txt`，接入 MNN 官方预编译 9 个 `.so`（两个 ABI），重写 `core/LocalAiClient.kt`（对外接口不变），`ModelManager.kt` 换成 MNN 5 文件清单（合计 1 235 520 567 B）双源下载，清掉 `SettingsActivity.kt` / `PerfTrace.kt` 的 llama 依赖，修 `AndroidManifest.xml` 的 OpenCL 声明位置 | ① 本机 `gradlew assembleDebug` **BUILD SUCCESSFUL in 27s**，NDK 27.2.12479018 + CMake 3.22.1 实跑通（两个 ABI 均编出）；② 产物 `app-debug.apk` **24.28 MB**，解包核对每 ABI 10 个 `.so`（9 MNN + `libyijingllm.so`）共 20 个；③ 打包后清单里 `<uses-native-library>` 落在 `<application>` 内；④ CI `Android Build`（run `36097827077`）**success**，7 步全绿。**OpenCL 是否真的启用、提速多少，Windows 上测不出来，待真机（高通 Adreno + 华为 Kirin/Mali）** |
| 2026-09-25 | `65181f8` | **Android 推理加速调研**：新增 `docs/ANDROID_PERF_OPTIONS.md`，记录「现状是纯 CPU 包」的根因、MNN / 自编 llama.cpp OpenCL / LiteRT-LM 等三条路线的可核实事实与链接、本机缺 NDK+CMake 的事实、以及 5 项待实测项。**未改任何代码** | 事实来源为 Maven POM/README、MNN 官方文档与 Releases、ModelScope 模型页；本机 SDK 目录实查。文末已补后记，标注其中被后续实测推翻的 4 条（预编译包含 LLM 模块、模型文件数、本机工具链、`required` 取值） |
| 2026-09-17 | `050032f` | **Android 同步 P3 输入拦截**：新增 `android/app/src/main/java/com/yijing/app/core/InputGuard.kt`（与 Swift 同源同表，另补偿 Java 正则 ASCII 语义差异）；`MainActivity.openResult()` 改三支路由（危险→结果页直出安全提示不调模型 / 非有效→等待页带软引导 / 其余照常）；`LoadingActivity` 增 `guardHint` 并在云端、本地两条 AI 路径前置系统提示词 | ① 本机 `gradlew --offline assembleDebug` **BUILD SUCCESSFUL**（1m49s）；② 编译真实 `InputGuard.kt` 在 JVM 跑 382 条语料，与参考实现**判定 0 差异**；③ CI `Android Build` **success**（1m17s） |
| 2026-09-17 | `28ef289`、`9f7ff52` | 建立文档体系：新增本文件、`AGENTS.md`、`.trae/rules/progress-log.md`、`.trae/rules/git-commit-message.md`；README 订正产物名并补「文档」一节 | 纯文档改动，未触发 CI；`git status` 确认改动范围 |
| 2026-09-17 | `189af24` | 修 `InputGuard.swift` 第 178 行标点字面量未转义导致的编译失败 | CI `iOS Build #26`（run `35187727041`）11 步全绿，产出 `Yijing-adhoc-ipa` 3.34MB |
| 2026-09-17 | `3c0b980` | iOS 接入输入有效性拦截：新增 `ios/Yijing/Services/InputGuard.swift`（P3 规则闸 + 变体/谐音等价表），`ios/Yijing/Flow/CastFlow.swift` 接上路由与提示 | 静态自检 + 两端等价性 0 差异；首次 CI 因上述字面量问题失败 |
| 2026-09-16 | `d1b59c6` | Android 修取消下载死循环、推演页动画对齐 iOS、收回调试入口 | CI |
| 2026-09-16 | `503f70b` | Android 埋点默认关闭、回滚默认思考、线程上限 4、重做推文页毛笔动画 | CI |
| 2026-09-16 | `4d0f08d` | 首次下载可随时取消、下载改真流式 | CI |
| 2026-09-16 | `87a8695` | iOS 开启 Metal GPU 卸载加速、日志改缓冲批量写盘、新增 App 图标 | CI |
| 2026-09-16 | `c52f4b6` | iOS 非思考模式预填改官方写法、改用 `is_eog` 判终止符 | CI |
| 2026-09-16 | `ec2ce38` | iOS 套用 Qwen3 对话模板 + 正规采样链，修输出退化；修版本号被 XcodeGen 覆盖 | CI |
| 2026-09-15 | `93a7589` | iOS 分批喂 prompt 修 `n_batch` 越界导致的 `GGML_ASSERT` 崩溃 | CI |
| 2026-09-15 | `84ba607` | iOS 增加文件共享导出与应用内崩溃日志查看 | CI |

## 7. 维护约定

1. 任何实质改动完成后：更新第 1、2 节的状态，并在第 6 节表格**加一行**（日期 / 提交号 / 内容 / 验证方式）。
2. 出现新坑或新事故：补进第 5 节。
3. iPhone 自用安装的完整步骤见 `README.md`。

## 8. 状态记录挂在哪（Trae 机制）

上下文压缩是必然会发生的（对话窗口超限后，早期工具输出会被丢弃并压缩成摘要），**唯一可靠的状态载体是文件**。本项目挂了三层：

| 位置 | 作用范围 | 说明 |
| --- | --- | --- |
| `DEV_STATUS.md`（本文件） | 本仓库 | 状态与进展的唯一权威，人手/智能体都读它 |
| `AGENTS.md`（仓库根） | 本仓库 | 跨 IDE 通用的智能体指引（`CLAUDE.md` 亦兼容）。**需在 设置 → 规则 → 导入设置 打开「将 AGENTS.md 包含在上下文中」才进上下文** |
| `.trae/rules/progress-log.md` | 本仓库 | Trae 项目规则，`alwaysApply: true` 始终生效；`.trae/rules/git-commit-message.md` 用 `scene: git_message` 管提交信息 |
| `%userprofile%/.trae-cn/user_rules/` | **所有项目** | Trae 全局规则（本机已放入 `progress-log.md`：每步留痕、重大进展归档、结论分「已验证/估计」）；全局记忆另有 `%userprofile%/.trae-cn/memory/user_profile.md` |

会话级记忆由 Trae 自动维护在 `%userprofile%/.trae-cn/memory/projects/{项目路径}/` 下（按日 `topics.md` + `session_memory_*.jsonl`），属自动产物，不作为权威来源。

> 提示：新建或修改规则后，建议**开新对话**再用，避免旧上下文与新规则打架。

### 为什么压缩后「没了」、怎么避免

- **原因**：模型上下文窗口有限。对话变长后，系统会把早期内容压成摘要，**当时的工具原始输出（编译日志、
  CI 返回、文件内容）被丢弃**；切换模型/新会话更是完全从零开始。压缩本身是正常机制，丢失的是「只在对话里说过、没写进文件」的信息。
- **避免**：① 关键结论当场写进 `DEV_STATUS.md`；② 长输出先落文件再读摘要；③ 会话开头先读 `DEV_STATUS.md` 再干活；
  ④ 一次任务收尾前自查该文件是否已同步（见 `.trae/rules/progress-log.md`）。
