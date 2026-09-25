# 性能对比陷阱清单 · 本仓库逐条核查

> 建档：2026-09-25。起因：用户给出一份「LLM 移动端性能对比陷阱」三层清单 + 排错速查表，要求对照本项目实现核查。
> 口径：只写**读代码得到的事实**。每条标 `[已验证]`（读源码可确认）或 `[待验证]`（需真机/编译产物/本机没有的文件才能确认）。
> 本文不构成对 bug#1（旗舰机 25s 反而更慢）的结论 —— 该 bug 的结案见**第 6 节**（2026-09-25 拿到真机 `perf-trace.log` 后补写）。

## 0. 两端实际配置（核查的基准事实）

| 项 | Android | iOS | 出处 |
| --- | --- | --- | --- |
| 引擎 | MNN 3.6.1（官方预编译 `.so`） | llama.cpp（XCFramework，`llama-ios`） | `cpp/CMakeLists.txt` / `project.yml` |
| 模型 | `Qwen3-1.7B-MNN` 5 文件，权重 1,231,860,194 B | `qwen3-1.7b-q4_k_m.gguf`（约 1223MB） | `ModelManager.kt` / `ModelManager.swift` |
| 权重档位标签 | **App 内不可见**（只校验字节长度） | 文件名即档位（Q4_K_M） | 同上 |
| 上下文 | `CONTEXT_SIZE = 2048` → `max_all_tokens` | `n_ctx = 2048`，`n_batch = 256` | `LocalAiClient.kt:68` / `LlamaCPP.swift:251` |
| 线程 | `coreCount().coerceIn(2, 4)`（**硬上限 4**） | `min(8, activeProcessorCount)`（**用满全核**） | `LocalAiClient.kt:threadCount()` / `LlamaCPP.swift:255` |
| 输出上限 | 768（思考开）/ 1536（重试一次） | 320 | `LocalAiClient.kt` / `LlamaCPP.swift:214` |
| GPU | `backend_type = "opencl"`（全程单一后端） | `n_gpu_layers = 99`（全层 Metal） | 同上 |
| mmap | `use_mmap = true` | **`load_mode = LLAMA_LOAD_MODE_NONE`（显式禁用 mmap）** | `LlamaCPP.swift:180-182` |
| 精度/内存 | `precision="low"`，`memory="low"`，`power="normal"` | llama 默认 | `LocalAiClient.kt:configFor()` |
| 并发 | `mutex.withLock` 串行化 | `NSLock` 串行化 | `LocalAiClient.kt:279` / `LlamaCPP.swift:136` |

## 1. 第一层：对比口径不公

| 清单项 | 我们这边 | 判定 |
| --- | --- | --- |
| 量化档位不同 | 两端**不是同一份权重**：iOS 是 GGUF Q4_K_M（档位写在文件名里），Android 是 MNN 官方预量化包（档位**在 App 内完全不可见**，`ModelManager.kt` 只做长度校验）。`MnnConfig.precision="low"` 是**运行期精度模式**，不是权重档位，两者不能互相解释。权重 1,231,860,194 B ÷ 1.7e9 参数 ≈ **5.8 bit/参数**，明显高于纯 4 bit —— 说明 MNN 包内并非全部 int4。`[已验证]`（字节数与字段）／`[待验证]`（实际比特数） | **不可比成立**：任何「iOS vs Android 谁快」的对比都跨了模型格式与预量化产物，不能归因到引擎 |
| 包里塞了额外模块 | APK 的 `jniLibs/{arm64-v8a,armeabi-v7a}` 各 9 个 `.so`，**含 `libMNNAudio.so`、`libMNNOpenCV.so`**（撑 APK 体积）。但模型 5 文件清单里只有文本权重，无 vision/audio 权重文件；`llm_config.json` 本机没有（未下模型），未逐键核对。`[已验证]`（so 清单）／`[待验证]`（llm_config 是否声明多模态分支） | **半踩**：磁盘/体积成立；「每 token 多跑层」**不成立**（未见视觉/音频权重） |
| 上下文长度不同 | 两端都是 **2048**，且两端都**不复用 KV**（Android `reuse_kv=false`；iOS 每次新建 context 并注释说明「避免复用 KV 带来的位置/状态问题」）。`[已验证]` | **不踩** |
| 线程/机型不同 | 线程策略**两端不同**：Android 上限 4 且不区分大小核；iOS 用满全核。历史真机数据（8 核机）已有「4 线程 11.88 tok/s vs 6 线程 6.88 tok/s」的实测记录，**我们的 4 线程上限正是基于「给多线程反而慢」设的**。但**从未做过线程亲和性绑定**，也从未用 MNN 自带 benchmark 扫过线程拐点。`[已验证]` | **踩**（亲和性）／**部分踩**（口径），详见第 2 节第 5 条 |
| 测量口径混用 | Android JNI 的 `Session` **分开记账**：`prefillUs` / `decodeUs` / `sampleUs` / `ttfaUs` / `loadUs` / `wallUs`，且 `tokensPerSecond = genSeqLen × 1e6 ÷ decodeUs`（**只算 decode，不含 prefill**），加载耗时单列不混入。iOS 也打分开的阶段日志（`⏱ prompt 解码` / `⏱ 生成 … token/s`）。两端都**没有做 30 秒预热循环**（只做了后端 init + 模型 load 的一次性常驻缓存）。`[已验证]` | **不踩**（分阶段）／**未做**（预热） |

**额外发现的口径问题（清单没列，但同样致命）**：Android 输出上限 768 token、iOS 320 token ——「端到端几秒出结果」这个数字两端**天然不可比**，Android 要多生成约 2.4 倍的 token。

## 2. 第二层：后端配置没走对

| 清单项 | 我们这边 | 判定 |
| --- | --- | --- |
| 硬件能力不匹配被静默回退 | Android 侧**没有任何 SoC 能力探测**（不读 `asimddp`/`i8mm`，不看 `SOC_MODEL`）。三级回退 `opencl+mmap → cpu+mmap → cpu 无 mmap` 是**基于异常**（`MnnException`）而非能力检测 —— 「能起来但走了慢 kernel」这种情况我们**测不出来**，只会在 t/s 上表现为慢。目标机型「高通 + 华为」横跨 Adreno/Mali，正是这条的高风险面。`[已验证]`（无探测代码）／`[待验证]`（真机实际 kernel） | **踩** |
| 指令集没编进二进制 | MNN 主体是**官方预编译 `.so`**（`mnn_3.6.1_android_armv7_armv8_cpu_opencl_vulkan`），官方 Android 构建脚本本身带 `MNN_ARM82=true`，故 MNN 主体**不存在「没编 ISA 开关」**。本地自编的只有 425 行 JNI 胶水（`cpp/yijing_llm_jni.cpp`，16 378 B）：`CMakeLists.txt` 里**没有显式 `-O2/-O3`**，`build.gradle.kts` 的 `externalNativeBuild` 里**没有按 buildType 传 `-DCMAKE_BUILD_TYPE`**。`[已验证]`（源码无此配置；2026-09-25 查 `.cxx/Debug/5e5un2a7/arm64-v8a/build.ninja` 第 55 行，AGP 实传 `FLAGS = -g -DANDROID … -fPIC -fvisibility=hidden -Wall -Wextra -Wno-unused-parameter -std=c++17` —— **无 `-O2/-O3`、无 `-march`**） | **主体不踩**（MNN 主体二进制里 dotprod/fp16/NEON 实证齐全，见第 7 节）／**胶水层已查明**（Debug -O0 + 无 dotprod；但胶水仅 425 行纯 JNI marshalling，非热点，收益≈0） |
| 后端选错阶段 | Android **全程单一后端**（`PREFERRED_BACKEND = OPENCL`，prefill 到 decode 全走 OpenCL）；iOS 同样 `n_gpu_layers = 99` 全层卸载。清单实测结论是「prefill 最快在 NPU/GPU、decode 最快在 CPU」—— 我们的负载是**长输出**（上限 768 token，历史实测 decode 329 token 占约 49.9s 中的绝大部分），**decode 是主体**，所以「统一上 GPU」正是清单点名的反模式。`[已验证]` | **踩**（但改动需实测支撑） |
| NPU 路径本身不划算 | **不适用**：我们完全没用 NPU/QNN 路径（预编译包里也没有 QNN 后端）。`[已验证]` | **不适用** |
| 线程亲和性差 | Android `thread_num = 4` 交给 MNN 自己起线程，**没有绑大核、没设优先级、没固定频率**。旗舰机多为「少大核 + 多小核」，4 线程被调度到小核的概率不低。`[已验证]`（无相关代码） | **踩，且是唯一完全没做过的调优项** —— 对 bug#1 最值得先查 |

## 3. 第三层：引擎内核与内存行为

| 清单项 | 我们这边 | 判定 |
| --- | --- | --- |
| 布局转换与拷贝 | 无法本机验证（需 `simpleperf`）。可指出一点：MNN 私有 NC4HW4 布局主要影响**卷积类 CV 算子**，LLM 路径以 MatMul/Attention 为主，转换点远少于 CV 网络。清单这条对 LLM 场景的权重**可能被高估**，不宜先验断言。`[待验证]` | **待验证** |
| 权重与 KV 摆放 | ✅ `use_mmap = true`；✅ `precision="low"` / `memory="low"`；✅ `reuse_kv = false`（每次解卦独立，符合场景不算问题）。❌ `dynamic_option = 0`（关）；❌ **`MnnConfig` 里没有 KV 量化字段**（`kv_quant` / `quant_kv` 之类从未暴露，`llm_config.json` 是官方原样）；❌ 从未核对 **Flash Attention hint**（`MNN::Interpreter.hpp` 有 `FLASH_ATTENTION`：0 不用 / 1 用）是否打开。decode 是 memory-bound，KV 带宽直接决定速度，这三项都是**未利用的杠杆**。`[已验证]`（字段缺失）／`[待验证]`（MNN 3.6.1 是否支持这些键） | **半踩，有明确未开项** |
| 算子覆盖/回退 | 无法本机验证（需 MNN verbose 日志）。参考：Qwen3 是 MNN 官方一等公民（官方直接提供 `Qwen3-1.7B-MNN` 产物），**冷门算子回退概率低**。`[待验证]` | **概率小** |
| 多实例并发 | **不适用**：Android `mutex.withLock` 串行化推理，iOS `NSLock` 串行化，同一时刻只有一次推理，前台解卦不会被后台任务抢。`[已验证]` | **不踩** |

## 4. 排错速查表 6 步的对照

| 步骤 | 我们具备的程度 |
| --- | --- |
| 1. 先对齐口径 | **部分具备**：同 ctx（2048）、都分 prefill/decode。但跨框架（MNN 私有格式 vs GGUF）+ 跨机型 + 输出预算不同（768 vs 320），严格对齐只能**同框架内**做。 |
| 2. 开自动识别的日志 | **部分具备**：`CMakeLists.txt` 已编进 `MNN_USE_LOGCAT=1`，MNN 日志会进 logcat；`LocalAiClient` 还有一条 `MNN 生效配置` 埋点，直接 `dumpConfig()` 打印实际生效键值。**未开 MNN verbose 级别**。 |
| 3. 分阶段测 | **具备**：Android 已分开报 prefill / decode / ttfa / load（`MnnStats` 10 槽）；iOS 日志同样分开。**这是能拿到的第一批真机数据。** |
| 4. 确认硬件门限 | **未做**：无 SoC 能力探测、未显式声明 Release/ISA 开关。 |
| 5. 预热与控温 | **部分具备**：后端 init + 模型 load 已常驻缓存（iOS 侧注释记录 Metal 着色器库编译实测要 15 秒，缓存后省掉）。**没做 30 秒预热循环，没做前后交叉复测。** |
| 6. 最后才做 profile | **未做**（本机无真机、无 `simpleperf`）。 |

## 5. 结论与下一步（区分「可改」与「必须等数据」）

**可从代码静态判定、无需等数据的结论**
1. 「iOS vs Android 谁快」在本仓库**本来就不可比**（不同模型格式 + 不同预量化产物 + 不同输出预算）。要谈性能，只能在同框架内比，或接受「跨框架不可归因」。
2. APK 里 `libMNNAudio.so` / `libMNNOpenCV.so` 确实多余（纯文本模型用不到），去掉可瘦身 —— 但**不会提速**。
3. 「每 token 多跑层」在我们这里不成立（未见视觉/音频权重）。
4. 线程亲和性绑定是我们**唯一完全没做过**的调优项。
5. KV 量化、Flash Attention hint、`dynamic_option` 三项**未利用**。

**用户既有硬约束（仍然有效，本轮不得违反）**
- 不关闭思考（`enableThinking` 必须 true）；
- 不再重新调 CPU 线程（用户原话「CPU调优你已经调试过了」）；
- 拿到真机 `PerfTrace` / `PerfPanel` 实测前，**不动任何性能参数**。

## 6. 真机数据已到（2026-09-25）· bug#1 结案

用户提供了 Android 真机 `perf-trace.log`（小米 M154FF / 小米18 Pro Max · 高通 2nm · 8 核 · 物理内存 15062MB · Android 17）。

**第 5 节列的 5 项待补数据全部拿到**：

| 待补项 | 实测值 |
| --- | --- |
| `backend=` 实际值 | `opencl`（**没有掉到 CPU 档**，三级回退未触发） |
| `MNN 生效配置` | `backend_type=opencl · thread_num=4 · precision=low · memory=low · power=normal · use_mmap=true · max_new_tokens=768 · jinja.context.enable_thinking=true` —— 与源码预期**完全一致** |
| `会话加载` / 释放旧会话 | 1,635ms / 324ms（合计 1,962ms，**首页预加载 27s 前已完成，仍又加载了一次**） |
| MNN 版本 | 3.6.1 |
| 机型 | `Xiaomi M154FF · arm64-v8a · 8 核 · Android 17` |

**端到端 32,452ms 的分段（`[已验证]` 真机埋点）**：

| 阶段 | 耗时 | 占比 |
| --- | --- | --- |
| 会话重载（释放 324 + 加载 1635） | 1,962ms | 6% |
| prefill 预填充（333 token） | 2,149ms | 7% |
| decode 解码生成（475 token · 18.37 tok/s） | 25,852ms | 80% |
| 采样 + 收尾（其中采样 2,413ms） | 2,467ms | 8% |

**decode 25,852ms 的 token 去向**：原始输出 763 字 → 去思考后 237 字。思考 507 字占 **约 68%** ⇒ **约 17.6s 用于生成用户看不到的思考段，只有约 8.2s 在生成那 237 字的答案**（按字数比例折算，估算）。

**结论（bug#1 不是「旗舰机更慢」，是口径不同）**
1. **「25s 反而更慢」不成立**：25,852ms 是 decode 段本身，其中约 68% 是思考段；器件本身按 18.37 tok/s 跑，没有掉档、没有回退。
2. **两端输出预算不同（代码事实，`[已验证]`）**：iOS 上限 **320** token（`LlamaCPP.swift:214`），Android 上限 **768**（`LocalAiClient.kt`，重试一次时 1536）。Android 至少要生成约 2.4 倍的 token，**端到端时长本来就不可比**。
   > ⚠️ **2026-09-25 更正**：本条此前写的是「iOS 空思考块 = `enable_thinking=false` ⇒ 只写答案、iOS 不思考」。该推论已被用户否掉（原话「iOS没有启用云，并且启用了思考，别瞎说」），**已撤回**。「iOS 实际是否处于思考模式」现列为**未决项**，见本节末尾「待澄清」。
3. **Android 无流式**：埋点第 26 行明记「当前 AAR 无流式回调，只统计到整体耗时」，`首字 0ms`。用户在那 25.8s 里看不到任何输出 —— 这是「感觉特别久」的放大器，与推理速度无关。
4. **芯片不是主因**：iOS 唯一一份本地实测日志是 **CPU-only 老构建**（`n_gpu_layers=0`、`threads=3`）跑满 1024 token / 53.5s ≈ **19.1 tok/s**，与 Android OpenCL 的 18.37 tok/s **同量级**。iOS 当前构建（`n_gpu_layers=99` 全层 Metal）**没有任何真机耗时日志** —— 所以「iOS 只要 2s」这个数字**目前没有日志支撑**，属已知空白：既给不出确数，也**不能归因给云端**（iOS 走云端的前提是 `cloudEnabled && 已配 apiKey`，`AppSettings.swift:17/72`，默认 `false`；用户已确认未启用云）。

**待澄清：iOS 侧到底开没开思考（只记事实，不下结论）**

- `[已验证]` iOS **从来没有过 `enableThinking` 开关**：`git log --all -S'enableThinking' -- ios` 返回**空**。iOS 侧与思考相关的唯一位置是 `LlamaCPP.swift:418-424` 的 `applyChatTemplate`，末尾拼 `<think>\n\n</think>\n\n`。
- `[已验证]` 这段拼接由 `c52f4b6`（2026-09-16）引入，提交标题自称「**非思考模式**预填改为 Qwen3 官方写法 `think\n\n/think`（原来只写开头会留在思考块里）」，代码注释也写「让模型跳过思考直接作答」。**即：自 2026-09-16 起，iOS 代码走的是「空思考块」预填。**
- `[已验证]` 用户口述 iOS「**启用了思考**」，与上一条**冲突**。`LocalAiClient.swift:122` 的注释「真机反馈过思考过程漏到答案里」是 `c52f4b6` 之前那版（只写开头 `<think>`、模型留在思考块里）留下的历史记录，**不能用来证明当前构建在思考**。
- **判定方法 A（无需工具，看结果页标签）**：iOS 结果页会显示 `mode` —— 走云端那支写死 `mode = "大师解卦"`，走本地那支写死 `mode = "解卦"`（`CastFlow.swift:74/94`）。看到「解卦」即本地推理，与用户说法一致。
- **判定方法 B（真机日志，1 分钟）**：iOS 现成日志已经会打 `STEP 8: 输出预览 = …`（`LlamaCPP.swift:382`，取前 200 字）。跑一卦看这一行 —— 以 `<think>` 开头 ⇒ 在思考；直接从正文开始 ⇒ 没思考。**这条日志是唯一能拍板的证据。**
- `[已验证]` iOS 端**同样没有流式**：全仓 grep `流式|onToken|streaming|partial` 在 iOS 侧只命中日志回调/文件读取，无逐 token 上屏；`reply` 是一次性赋值（`CastFlow.swift:106`）。⇒ 两端「等待时屏幕全空」这一点是对称的。
- **若「iOS 也必须开思考」成立**，当前 iOS 预填就属于**违反硬约束的实现**，需要改回，并接受 iOS 耗时会上升。**本次未改任何代码。**

**顺带实测确认的一个真 bug**：首页预加载已完成 27s，`会话加载` 仍花 1,635ms —— 正是 `SessionKey` 非 `data class` 导致缓存永不命中（已在工作区修，未提交）。

**下一步候选动作**（仍只记录，不执行；三条硬约束不变）
1. decode 是 80% 的主战场 → MNN OpenCL 只擅长 prefill，长输出负载下 decode 偏慢（第 2 层第 3 条）；
2. KV 量化 / Flash Attention hint / `dynamic_option` 三项未开（第 3 层第 2 条）；
3. 采样 2,413ms（占 7.4%）偏高，可查 sampler 链；
4. 线程亲和性绑定 —— 用户已声明「调过了」的领域，**需先征得同意**。

## 7. Vulkan / ARM 指令集 逐项核查（2026-09-25 追加）

起因：用户提出「用 Vulkan 后端编译（`cmake -B build -DGGML_VULKAN=ON`）、运行时加 `-ngl 99`；Android / NDK 编译时确保使能 ARMv8.2-A / ARMv9-A 的 Vector 与 Dot Product；确认 `GGML_ARM_NEON` 已激活」，问这三项**做了没有**。

**前置事实（决定怎么答）**：这三个开关全部是 **llama.cpp / ggml 专有**的。而本仓库 Android 侧自 `cd849b2` 起已**整体从 llama.cpp 换成 MNN 3.6.1** —— 全仓 grep `ggml|llama` 在 `android/` 下只剩注释，`jniLibs` 里是 9 个 MNN 官方 `.so`。所以这三项在 Android 上**不能按字面执行**，只能判断「意图在本框架里的等价物有没有做到」。

### 7.1 逐项判定

| 用户要求 | 语境 | 本项目实际状态 | 判定 |
| --- | --- | --- | --- |
| `cmake -B build -DGGML_VULKAN=ON` | llama.cpp 编译期开 Vulkan 后端 | Android 已不用 llama.cpp ⇒ **无此 CMake 开关可加**。但 Vulkan 能力**已随包**：`jniLibs/{arm64-v8a,armeabi-v7a}/libMNN_Vulkan.so` 在；`MnnLlm.BACKEND_VULKAN = "vulkan"` 常量已定义。**当前未被选用** —— `LocalAiClient.kt:110` 的 `PREFERRED_BACKEND = MnnLlm.BACKEND_OPENCL` | **不适用**（开关）／**能力已具备、未启用**（Vulkan 后端） |
| 运行时加 `-ngl 99` | llama.cpp 把 99 层卸载到 GPU | Android 的 MNN **没有「层卸载」概念**，GPU 走会话的 `backend_type`（全量给 GPU 或全量给 CPU）。**iOS 侧（仍用 llama.cpp）等价物已开**：`LlamaCPP.swift:179` `mparams.n_gpu_layers = 99`，日志打 `STEP 2: 开始加载模型 (n_gpu_layers=99 走 Metal …)` | **iOS 已做**（Metal 全层）／**Android 不适用** |
| NDK 编译使能 ARMv8.2-A / ARMv9-A 的 Vector + Dot Product | 指令集 | MNN 主体是**官方预编译包**（官方 Android 脚本自带 `MNN_ARM82=true`），我们本地只编 425 行 JNI 胶水。二进制实测见 7.2 —— Dot Product / FP16 / i8mm / BF16 **全部在** | **已满足**（而且比编译期 `-march` 更好：运行期分发） |
| 确认 `GGML_ARM_NEON` 已激活 | llama.cpp 的 NEON 宏 | 该宏在 Android 不存在（无 ggml）。其**意图**（NEON 生效）已满足，见 7.2 的 armeabi-v7a 属性 | **不适用**（宏）／**意图已满足** |

### 7.2 二进制级实证（方法：NDK 27.2.12479018 自带 `llvm-objdump` / `llvm-readelf` / `llvm-strings`）

| 二进制 | 方法 | 结果 |
| --- | --- | --- |
| `arm64-v8a/libMNN.so` | `llvm-objdump -d` + 正则计数 | `sdot/udot`（Dot Product，ARMv8.2-A）**756** 条；`.8h`（FP16）**17 166** 条；`smmla/bfdot/bfmmla`（i8mm/BF16，ARMv8.6-A）**954** 条 |
| `arm64-v8a/libllm.so` | 同上 | `sdot/udot` **0**、`.8h` **54**、`smmla` **0** ⇒ 它是调度/图执行层，热点 GEMM 内核在 `libMNN.so`，**不能只看这一个文件就判「没编 dotprod」** |
| `arm64-v8a/libyijingllm.so` | 同上 | **全 0** ⇒ 我们自编的胶水确实没有任何 SIMD 路径（它本来就是纯 JNI marshalling） |
| `armeabi-v7a/libMNN.so` | `llvm-readelf -A` | `TagName: CPU_arch` = ARM v7 · `FP_arch` = **VFPv3** · `Advanced_SIMD_arch` = **NEONv1** ⇒ 32 位 ABI 的 NEON 已激活 |
| `arm64-v8a/libMNN.so` | `llvm-strings` | 含 KleidiAI 内核符号 `kai_kernel_matmul_*_neon_dotprod` / `*_neon_i8mm`，以及**运行期探测字符串** `The device supports: i8sdot:%d, fp16:%d, i8mm: %d, sve2: %d, sme2: %d` |

**7.2 的结论**：MNN 主体**同时**编进了 dotprod 与 i8mm 两套内核，并在运行期按 CPU 特性（`i8sdot` = dotprod 标志位）挑选 ⇒ 「指令集没使能」这个问题在本项目**不存在**，且这套机制比手工加 `-march=armv8.2-a+dotprod` 更稳（单包兼容老 ARMv8.0 机型，不会 `SIGILL`）。

### 7.3 剩下真正没做的（与用户三问相关的缺口）

1. **Vulkan 后端从未被启用过**：包里有 `libMNN_Vulkan.so`、常量也有，但首选是 OpenCL；debug 基准套件 `benchSuite()` 的对照表只有两项 `A OpenCL（首选）` / `B CPU（对照）`（`LocalAiClient.kt:520-523`），**没有 Vulkan 组**。要实测只需改 `PREFERRED_BACKEND` 一行 + 加一组对照。
2. **切 Vulkan 必须保留回退**：`docs/ANDROID_PERF_OPTIONS.md` 已记移动端 Vulkan 的已知问题（Adreno 730 `SIGSEGV`、Mali-G715 挂死），而本 App 目标机型正是「高通 + 华为」横跨 Adreno / Mali ⇒ 不能直接把 Vulkan 设成首选而不留 opencl/cpu 退路。
3. **App 层仍无 SoC 能力探测**：不读 `asimddp`/`i8mm`，后端选择是「起不来才退（异常驱动）」，不是「按能力选」。MNN 内核层面已自动分发，但**后端层面**（OpenCL vs Vulkan vs CPU）仍靠试错。
4. 自编胶水 `libyijingllm.so` 是 **Debug `-O0` 且无 `-march`**（`build.ninja` 第 55 行实证）—— 但它只有 425 行纯参数转换、无 SIMD 路径，**收益≈0，不构成本次问题的答案**，仅作记录。

> 本节所有数字均为 2026-09-25 在本机对 `jniLibs` 内实际随包二进制取反汇编/属性所得（`[已验证]`），非估算。**本次未改任何一行推理代码或性能参数。**

---

## 8. iOS 真机实测已到（2026-09-25）· 旧 bug 全清、两端首次可比

数据源：用户回传 `yijing_log.txt`（118 247 B / 1 347 行）—— App `version=1.2.0 build=7`，Metal 设备 `MTL0 (Apple A19 GPU)`，共两次解卦。

### 8.1 iOS 实测分段

| 阶段 | 第一次（冷） | 第二次（热） |
| --- | --- | --- |
| 后端初始化 | **16 883 ms**（`loaded 20 libraries from embedded data in 16.484 sec` = 首次 Metal 着色器库编译） | **0 ms**（`STEP 1: 后端已初始化，跳过`） |
| 模型加载 | 1 185 ms（`offloaded 29/29 layers to GPU`；`MTL0 model buffer 1050.43 MiB` + `CPU model buffer 166.92 MiB`） | **0 ms**（`复用已加载的模型与 vocab`） |
| 创建 context | 80 ms（`n_ctx=2048, n_batch=256, threads=6`，KV cache 全部 `dev = MTL0`） | 40 ms |
| 分词 | 5 ms（333 token） | ~20 ms（341 token） |
| prompt 解码 | 595 ms | 194 ms |
| **生成** | **1 856 ms**（85 token，**45.8 tok/s**） | **2 571 ms**（110 token，**42.8 tok/s**） |
| 端到端 | **20.6 s**（其中 16.9 s 是首次着色器编译） | **2.8 s** |

**旧日志（2026-09-15/16）实证的 5 条 bug 现已全部消失**，逐条对照：① `version=1.0 build=1` → `1.2.0 build=7`；② `n_gpu_layers=0`（纯 CPU）→ `n_gpu_layers=99` + `offloaded 29/29 layers to GPU`；③ `MTL0 compute buffer size = 0.0000 MiB`（Metal 空转）→ `MTL0 compute buffer size is 152.3750 MiB`；④ 跑满 1024 token 不命中 EOG → 两次均 `命中结束符（EOG）正常结束`；⑤ 缺 `STEP 8: 输出预览` 行 → 已存在。（`[已验证]`，逐行 grep 日志）

### 8.2 两端 decode 速度（首次可比）

| 端 | 引擎 / 后端 | 设备 | decode 速度 | 输出 token |
| --- | --- | --- | --- | --- |
| iOS | llama.cpp + **Metal**（全层卸载） | Apple A19 GPU | **42.8 – 45.8 tok/s** | 85 / 110（纯答案） |
| Android | **MNN 3.6.1 + OpenCL** | Xiaomi M154FF（骁龙） | **18.37 tok/s** | 475（含 ≈507 字思考段） |

⇒ **iOS 每 token 快 2.3 – 2.5 倍**。**但端到端时长不可直接比**（iOS 20.6 s / 2.8 s vs Android 32.45 s）：iOS 当前跑的是非思考模式（见 8.3），输出 token 只有 Android 的约 1/4。若在 iOS 打开思考，按同机速度外推约需 **11 s**，**仍显著快于 Android 的 32.45 s** ⇒ 思考开关在 iOS 侧有充足余量，不影响「保质量」这条硬约束的可行性。

### 8.3 必须点出的问题：iOS 当前实际是「非思考模式」

两次 `STEP 8: 输出预览` 都是**纯答案**（以「你抽到的卦是蹇…」开头），全日志 grep `think` **零命中**。该行打印的是 `stripThinking()` **之前**的原始输出（`LlamaCPP.swift:382` vs `LocalAiClient.swift:58`）⇒ **不是被后处理删掉的，而是模型本来就没思考**。根因：`applyChatTemplate()`（`LlamaCPP.swift:418-424`）在 assistant 段末尾固定拼了 `"<think>\n\n</think>\n\n"` **空思考块** —— 这正是 Qwen3 `tokenizer_config.json` 里 `enable_thinking=false` 的官方写法（源码 `:410-417` 注释已写明）。**与「不能关闭思考，否则输出质量太差」这条硬约束冲突，是否删除该空块需用户拍定。**

### 8.4 唯一值得修的工程项：启动预热没生效

`YijingApp.init()` 里 `.now() + 2s` 的 `warmUp()` 本应在启动 2 秒后编译 Metal 库，但日志从 `15:51:03.537`（App 启动）**直接跳到** `15:51:47.140`（用户第一次点解卦），中间 44 秒**一行预热日志都没有** ⇒ 16.883 s 的着色器编译仍落在用户首次解卦的等待里。`warmUp()` → `ensureBackend()` **必定**打印 `STEP 1`（`LlamaCPP.swift:150`），唯一静默出口是调用方 `YijingApp.swift:14` 的 `guard ModelManager.isDownloaded() else { return }`；而 `isDownloaded()`（`ModelManager.swift:45`）= 文件大小 > `minModelBytes`，该文件当时确实存在（1 282 439 328 B）⇒ **逻辑上不该被拦，原因待定位**（补两行日志复跑冷启动即可区分）。修好后首次解卦可省掉约 16.9 s 中的绝大部分。**在拿到该日志前不下结论。**

---

## 9. iOS 开启思考 + 高通专项可行性 + 端侧速度调研（2026-09-25 追加）

起因（用户原话）：「如果这是没思考的结果，输出的质量比Android开思考还要好，这也不对吧？给我一个启用思考的版本？另外，针对高通有特别优化可能吗？比如MNN 的 OpenCL 后端，MNN_ARM82=ON？第三，帮我调研端侧小模型实际的运行速度（搜索网上的评论和案例）」

### 9.1 已落地：iOS 真正开启思考（代码侧 `[已验证]`／真机 `[待验证]`）

第 8.3 节认定的「iOS 实为非思考模式」已按用户要求修复，共 3 个文件：

| 文件 | 改动 | 作用 |
| --- | --- | --- |
| `ios/Yijing/Services/LlamaCPP.swift` | 新增 `static let enableThinking = true`（`:217`）；`applyChatTemplate()` 仅在 `!enableThinking` 时补空思考块（`:439`–`:446`） | **开思考的实质动作**：模板不再预填 `"<think>\n\n</think>\n\n"`，模型会自己开 `<think>` 段 |
| 同上 | `complete(..., maxTokens: Int32 = 768)`（`:224`，原 320）；采样链按模式成套取值（`:294`–`:295`） | 思考段与正文**共用**预算，768 对齐 Android `MAX_TOKENS_THINKING`；思考模式取 Qwen 官方推荐的 `temp=0.6 / top_p=0.95 / top_k=20` |
| 同上 | 新增 `STEP 5: 思考模式 = …`（`:285`）与 `STEP 5: 采样参数 …`（`:307`）两条日志 | 下次真机日志可**一眼证实**「到底思考没思考」，不再靠推断 |
| `ios/Yijing/Services/LocalAiClient.swift` | 新增 `maxTokensThinking = 768`（`:41`）、`maxTokensRetry = 1536`（`:46`）、`logThinkingOf()`（`:98`）、空结果兜底重跑（`:74`–`:83`） | 思考段吃光预算、去标签后空白时，自动把预算提到 1536 重跑一轮，而不是给用户一张白纸 |
| `ios/project.yml` | `MARKETING_VERSION 1.2.0 → 1.3.0`、`CURRENT_PROJECT_VERSION 7 → 8` | 真机日志据此可区分新老版本 |

**为什么必须成套换成 0.6 / 0.95**：Qwen 官方对 Qwen3 给的是**成套**参数 —— 思考模式 `Temperature=0.6, TopP=0.95, TopK=20, MinP=0`；非思考模式 `Temperature=0.7, TopP=0.8, TopK=20, MinP=0`，并明确**不建议贪心解码**（小模型会复读退化，旧日志里连续 1024 个「！」即此因）。改动前 iOS 用的是非思考那套（0.7/0.8），若只删空思考块而不换采样参数，思考段会啰嗦发散。

**代价（`[估计]`，按 iOS 实测 42.8–45.8 tok/s 外推）**：输出 token 由 85 / 110（纯答案）涨到约 475（对齐 Android 真机口径），生成时长 2.57 s → **约 11 s**（端到端 2.8 s → 约 11.2 s）。**仍显著快于 Android 真机的 32.45 s** ⇒ 思考开关在 iOS 侧余量充足，与「不能关闭思考」这条硬约束不冲突。

**注**：iOS 改动**无法在本机编译验证**（Windows 无 Xcode/swiftc），只能靠 CI `iOS Build`；改动前已按第 4 节自检清单做过静态检查（字符串闭合 / 括号配平）。`[待验证]`

### 9.2 高通专项优化：`MNN_ARM82=ON` 不管 GPU，真正的高通路径是 Hexagon

**先纠正误解**：`MNN_ARM82` **与 OpenCL / Adreno 完全无关**。MNN 官方编译宏文档原文是「编译ARM架构时，是否构建`Armv8.2`后端，以支持FP16计算，默认为`ON`」⇒ 它是 **CPU 侧**的 ARMv8.2 FP16 计算后端（armv7 上另由 `MNN_SUPPORT_FP16_ARMV7` 控制，默认 OFF），而且**默认就是 ON**，官方 Android 预编译包必然已含 ⇒ **打开它不会给高通 GPU 带来任何收益**。（来源：[MNN 编译宏介绍](https://mnn-docs.readthedocs.io/en/latest/compile/cmake.html)）

| 用户猜测 | 实际语义（官方文档） | 对本项目（高通 Adreno）的意义 |
| --- | --- | --- |
| `MNN_ARM82=ON` | CPU 侧 ARMv8.2 FP16 后端，**默认已 ON** | **无效**（不改 GPU 路径） |
| `MNN_OPENCL=ON` | 构建 OpenCL 后端，**默认 OFF**；官方 LLM 文档明确「Android：可添加 `-DMNN_OPENCL=ON` 以利用 GPU 加速」 | **已经在用**：宏虽默认 OFF，但随包的官方预编译包里 `libMNN_CL.so` 在、真机日志 `backend=opencl` ⇒ 这条**已吃到** |
| `MNN_HEXAGON=ON` | 高通 Hexagon DSP / HTP 后端，默认 OFF | **唯一真正「高通专属」的加速路径**，但**当前不可用**，见下 |

**Hexagon（Qualcomm HTP）为什么走不通（`[已验证]` 解包 + 官方文档）**：官方 LLM 文档写明该后端用于「在支持 Qualcomm HTP/cDSP 的 Android 设备上运行 LLM」，但**只支持 4-bit 权重量化且必须对称量化**（导出需加 `--quant_bit 4 --sym`），运行前必须把 `libMNN_htpops.so` 与 `libMNN_htpops_skel.so` 推到设备同目录。而本项目 `jniLibs/<abi>/` 的 9 个 `.so` 名单（第 4 节）**不含这两个** ⇒ 要上 Hexagon 得**自编 MNN + 重新导出模型**，属换引擎量级的工作，且只覆盖高通、对「华为 Mali」目标机型完全无效（与第 4 节 `power` 不能设 `low` 同源）。**不建议当前阶段做。**

**真正还能抠的高通侧旋钮（都不需改编译宏）**：

| 旋钮 | 现状 | 说明 |
| --- | --- | --- |
| `config.json: backend_type` | `opencl`（真机已验证） | 可对照 Vulkan（包里 `libMNN_Vulkan.so` 与常量都在，但第 7.3 节已列其 Adreno 730 `SIGSEGV` 风险） |
| `config.json: thread_num` | 4 | 官方 benchmark 示例用 `-t 4,8`；`llm_bench -a cpu,opencl -t 4,8` 可一次扫出最优值 |
| `precision` | `low`（fp16） | 已是最省的常规值 |
| `power` / `memory` | `normal` / `low` | `power=low` 会把华为 Mali 顶回 CPU，不能动（第 4 节） |
| 逐 Kernel GPU 耗时 | **未开** | `MNN_GPU_TIME_PROFILE`（默认 OFF）+ `MNN_GPU_PROFILE_SILENT` 是唯一能看清「OpenCL 慢在哪个 kernel」的手段，但需自编 MNN |
| AutoTuning 缓存 | 未知 | MNN 的 GPU 自调优若缓存未命中，会在首次运行付代价 |

> 结论：**`MNN_ARM82=ON` 不是答案**（CPU 侧、且默认已开）；`MNN_OPENCL` 已经在用。真正的高通专项只剩 Hexagon 一条，代价是自编引擎 + 换模型导出，并牺牲华为机型 ⇒ **不建议现在做**。**本次未改任何 MNN 编译宏或性能参数。**

### 9.3 端侧小模型实际速度调研（网上案例与评论）

#### 9.3.1 本项目两端在公开数据里的位置

| 端 | 本项目实测 | 同类公开数据 | 判定 |
| --- | --- | --- | --- |
| iOS（A19 Pro，llama.cpp + Metal） | **42.8–45.8 tok/s**（Qwen3-1.7B，输出 85/110 token，短时） | iPhone 17 Pro 上 Qwen3-1.7B Q4_K_M **约 34 tok/s**；iPhone 16 Pro 上 SmolLM2 1.7B **42 tok/s**、Llama-3.2-3B 25 tok/s；iPhone 14 Pro 上 Qwen3-1.7B **约 15–20 tok/s** | **正常偏好**（高于同代公开口径约 30%）；口径（base/instruct、prompt 长度、量化法）未对齐，只看量级 |
| Android（骁龙 8 Gen 3，MNN 3.6.1 + OpenCL） | **18.37 tok/s**（输出 475 token，含思考段） | 同为骁龙旗舰（S26 Ultra）走 LiteRT-LM：同一模型 **CPU decode 46.9 / GPU decode 52.1 tok/s** | **明显偏低**（约为同代公开数据的 1/3）；但**口径不同框**（框架/模型/量化都不同），只能说「有量级空间」，不能说「MNN 不行」 |

来源：机型对照（[llmrun.dev](https://llmrun.dev/model/qwen-qwen3-1-7b-base)）、iPhone 端模型对照（[aimagicx](https://www.aimagicx.com/blog/on-device-ai-models-local-llm-guide-2026)）、老机型对照（[promptquorum](https://www.promptquorum.com/power-local-llm/best-local-llm-apps-iphone-2026)）、LiteRT-LM 官方表（[Google Edge 文档](https://developers.google.com/edge/litert-lm/models/gemma-4) / [第三方镜像](https://bytegoose.com/models/1826)）。

#### 9.3.2 「GPU 未必快过 CPU」是常态（多条独立证据）

| 证据 | 结论 |
| --- | --- |
| LiteRT-LM 官方表（S26 Ultra） | GPU 的 **prefill 是 CPU 的 6.8 倍**（3808 vs 557），但 **decode 只快 11%**（52.1 vs 46.9）⇒ GPU 收益几乎全在 prefill |
| [arXiv 2607.05475](https://arxiv.org/html/2607.05475v1)《Is Your NPU Ready for LLMs?》（5 框架 × 3 后端） | 存在明确的「**阶段分裂**」：NPU/GPU 强在 compute-bound 的 **prefill**，而 CPU 在 memory-bound 的 **decode** 上**超过所有其它后端** |
| [arXiv 2605.27435](https://arxiv.org/html/2605.27435v1)《When NPUs Are Not Always Faster》 | Qwen3-4B / llama.cpp Q4_0：prefill **CPU 7 181 s 胜 NPU 10 352 s**，decode 反向 NPU 胜 1.55× ⇒ 由阶段决定谁快 |
| [arXiv 2505.06461](https://arxiv.org/html/2505.06461)《Challenging GPU Dominance》 | 线程数调优后 **CPU 可以超过 GPU**（Llama 3.2-1B 等小模型上尤其明显） |
| [arXiv 2506.10443](https://arxiv.org/html/2506.10443v1)（MNN-LLM 论文） | MNN 自家 CPU 路径 prefill 比 llama.cpp 快 **8.6×**、decode 快 **2.3×**；但 **GPU 路径略逊于 MLC-LLM** ⇒ OpenCL 并非 MNN 最强路径 |

**对本项目的直接推论**：本项目 decode 占端到端约 80%（第 6 节 `[已验证]`），而 decode 恰是 CPU 不输 GPU 的阶段 ⇒ **「换更强 GPU 后端」不是首选杠杆**，与第 7.3 节「Vulkan 有风险、收益不明」一致。

#### 9.3.3 带宽墙：decode 的物理上限

decode 每生成 1 token 必须把全部权重读一遍 ⇒ 上限 ≈ 内存带宽 ÷ 模型体积。Qwen3-1.7B 4bit 权重约 **1.0–1.1 GB**。

| 平台 | 内存带宽 | 理论上限（`[估计]`） | 本项目实测 | 达带宽比例 |
| --- | --- | --- | --- | --- |
| A19 Pro（12 GB LPDDR5X-9600） | 最高 **75.8 GB/s** | ≈ 72 tok/s | 42.8–45.8 tok/s | **约 60%** ⇒ 接近墙，空间有限 |
| 骁龙 8 Gen 3 | 理论峰值 **68 GB/s**、实测最大可达 **61.9 GB/s**，且**单个处理器通常只到 40–45 GB/s** | ≈ 59 tok/s（按 61.9）／≈ 38–43 tok/s（按单处理器实得） | 18.37 tok/s（≈ **19 GB/s** 有效带宽） | **约 31%**（按 61.9）／约 **45%**（按 40–45 GB/s） ⇒ **未打满，仍有约 2 倍空间** |

来源：A19 Pro 带宽（[notebookcheck](https://www.notebookcheck.net/M2-Max-vs-A18-vs-A19-Pro_14975_18007_19962.247596.0.html)、[cpuscores](https://cpuscores.com/soc/apple-a19-pro)）；骁龙 8 Gen 3 带宽（[arXiv 2501.14794](https://arxiv.org/html/2501.14794v2)）。

**重要限定**：上表把 decode 简化成「纯权重流式读取」，实际还有 KV cache 读写、采样、kernel 效率等损耗 ⇒ 「理论上限」是**上限估计，不是可达值**。

#### 9.3.4 必须记下的现实：热节流会吃掉三到四成

[arXiv 2603.23640](https://arxiv.org/html/2603.23640v2) 的持续负载实验：**iPhone 16 Pro 连续 20 次迭代后掉 41.5%，稳定在 23.7 tok/s 的平台**；S24 Ultra 温和些，掉约 15% 到约 10 tok/s 平台。

⇒ 本项目 iOS 的 42.8–45.8 tok/s 是**短时（两次解卦、每次两三秒）** 的数字；连打十几卦后大概率掉到 25–30 tok/s 量级。**评估体验应按热态数字，不要按这个峰值。**

> 本节「网上案例」均为**外部引用**，已标注来源链接与口径；本项目两端数字为本机日志实测（第 6 / 8 节）。**本次调研未改任何代码或参数。**
