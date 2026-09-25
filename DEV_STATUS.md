# 开发状态（唯一权威）

> 本文件是「周易小卦」项目的进度与状态台账，**每次有实质改动就追加/更新**。
> 目的：对话上下文被压缩、跨设备、换模型协作时，都能靠这一份文件接着干。
> 规则：只写事实，不写推测；结论必须标注「已验证」或「估计」；数字要带口径。

- 最后更新：2026-09-25
- 最近提交：`0f97219`（分支 `main`；更准确以 `git log` 为准）
- 远端：https://github.com/adexbn/yijing-oracle （public）
- 本机仓库路径：工作区下的 `yijing-ios/`（内含 `ios/` 与 `android/`）

---

## 1. 当前状态一览

| 端 | 功能状态 | 编译验证 | 备注 |
| --- | --- | --- | --- |
| iOS | 输入有效性拦截（P3 规则闸）已接入 | ⚠️ 本轮改了 `InputGuard.swift` / `LocalAiClient.swift`（词表收紧 + SYSTEM 重写 + 回显剥离），**待 CI `iOS Build` 验证**；上一版为 CI 全绿（iOS Build #26） | 产物 `Yijing-adhoc-ipa`，已装机运行正常；本机是 Windows 无 Xcode，Swift 改动只能靠 CI |
| Android | 输入有效性拦截（P3 规则闸）已接入，与 iOS 同源同表；**本地推理已从 llama.cpp 整体换到 MNN 3.6.1（OpenCL 优先，逐级回退到 CPU）** | ✅ 本机 `gradlew assembleDebug` 通过并出 APK（**25 576 721 B ≈ 24.39 MB**，产物时间 2026-09-25 14:37，两个 ABI 各 10 个 `.so`）；✅ 解包 APK 的 `classes*.dex` 直接核实新词表已入包；✅ 382 条语料等价性 0 差异（本轮复跑） | ⚠️ **真机反馈「旗舰机反而更慢（25s）」尚未定位，等真机 `PerfTrace`/`PerfPanel` 数据**；**OpenCL 有没有真的被启用、快多少，本机无法验证**；**思考保持开启（硬约束）** |

> 2026-09-25 真机反馈（小米18 Pro Max / 高通 2nm）四个问题：阴阳图错、合法提问被判无效、思考过程泄漏、旗舰机更慢。
> 前三个已在 `0f97219` 修掉（详见第 6 节），**第四个未动** —— 本机 Windows 测不出推理性能，必须拿真机埋点数据。

## 2. 进行中 / 待办

1. **（最高优先，等数据）旗舰机反而更慢：用户反馈小米18 Pro Max（高通 2nm）出结果要 25s，比小手机还慢**。本机 Windows **无法复现推理性能**，必须先拿到真机埋点：`PerfTrace` / `PerfPanel`（入口 `SettingsActivity`）里的 `backend=` 实际值、三级回退（`opencl+mmap` → `cpu+mmap` → `cpu` 无 mmap）是否被打到 CPU、OpenCL kernel 是否首跑冷编译、25s 里有多少是**模型冷加载**而非推理。**拿到实测前不动任何性能参数**（用户已明确「CPU 调优你已经调试过了」）。
2. **Android 真机实测**：装机验证三条路径 —— 危险输入直达安全提示（不调模型）、非有效提问软引导、正常提问照常解读；并顺带验证本轮三个修复（阴阳图、节日词表、思考回显）。
3. ~~**Python 参考实现回写**~~ **已完成（2026-09-25）**：`ANCHOR` / `THING` / `INTENT` 三份已逐词对齐 —— 参考实现的 `INTENT` 补上 `怎样` / `咋办` / `怎么办` / `如何是好` / `能行不` / `行吗` 6 个词（此前只有 Kotlin/Swift 有，逐词比对确认差异仅此一处）。补齐后复跑三端：382 条（**编译真实 `InputGuard.kt`**）判定**不一致 0 条**、各批准确率仍为 A 100/100、B 40/40、C 20/20、D 120/122、E 86/100、E 批名单外多放行 0 条；iOS 侧自动抽取复刻**不一致 0 条**；V 批 12 条三端**互不一致 0 条**。
4. **（待用户确认）`verify_cases.tsv` #12 `中秋出去玩` 的 gold 标注**：gold=invalid，但 Kotlin / Swift / Python 参考**三方一致判 valid**。经核查它走的是 `THING` 弱证据分支（`出去玩` 命中 + 文案 ≥5 个码点），属设计内的「省略主语问自己」场景，逻辑自洽；倾向认为 gold 标注过严（该句本身是可占问的处境）。**未强改代码迁就 gold**，等用户拍定是改 gold 还是收紧判据。
5. **（悬置）第二道闸**：App 侧本地小模型闸（Qwen3-1.7B）尚未接；只在第一道规则闸漏放时才有必要触发。
6. ~~**CI 能否编出 native 桥（未验证）**~~ **已解决**：Android CI 跑在 ubuntu runner 上，`assembleDebug` 需要 NDK `27.2.12479018` + CMake `3.22.1`。实测 AGP 会经 `sdkmanager` 自动拉取，无需改 workflow —— push 后 `Android Build`（run `36097827077`，sha `cd849b2`）**7 步全绿**，含「编译验证（assembleDebug）」与「上传 APK 产物」。

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

其他已知约束：本地小模型不随 App 打包，装机后由用户在「设置 → 本地小模型」下载或导入。**两端用的不再是同一套模型格式**：iOS 仍走 llama.cpp + GGUF（约 1.2GB，锁定在仍含 `Package.swift` 的 revision，升级需对照新 `llama.h` 校正 `LlamaCPP.swift` 参数名）；Android 已换 MNN 3.6.1，模型是该框架自己的 5 文件组合（`config.json` / `llm_config.json` / `llm.mnn` / `tokenizer.txt` / `llm.mnn.weight`，合计 1 235 520 567 B），由 `ModelManager` 双源下载（hf-mirror 优先，ModelScope 兜底）。

## 6. 进展日志（新→旧）

| 日期 | 提交 | 内容 | 验证 |
| --- | --- | --- | --- |
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
