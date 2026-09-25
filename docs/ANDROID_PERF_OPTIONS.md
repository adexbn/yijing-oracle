# Android 推理加速：现状根因与可选路线

> 背景：用户反馈「Android 版还是太卡」，并提出「换 MNN / 启用 OpenCL」两条候选，且愿意接受本地编译 native 库。
> 本文只记录**调研得到的可核实事实**与**本机实测到的现状**，不含推测。每条结论标注来源或「已验证 / 待验证」。
> 建档日期：2026-09-25

> **后记（2026-09-25 收尾时补，避免把本文当最新结论用）**：路线 A（MNN）已落地并通过本机编译，下面的调研表里有几条事后被实测推翻，以 `DEV_STATUS.md` 第 4 节「MNN 3.6.1 迁移要点」为准。
> 具体更正：① 预编译包**不是**「不含 LLM 模块」——官方 Android 预编译包里 `libllm.so` 等 9 个 `.so` 齐备，实测可直接用，本项目**没有自编 MNN**，只自编了 JNI 桥；② 模型文件是 **5 个**（不是 8 个），合计 1 235 520 567 B；③ 本机**有** NDK `27.2.12479018` 与 CMake `3.22.1`，能本地编译；④ `<uses-native-library>` 实际写的是 `required="false"`（`true` 会让无 OpenCL 驱动的机器装不上），且**必须挂在 `<application>` 下**。

---

## 1. 现状根因（已验证：读本仓库源码 + 依赖元数据）

| 项 | 事实 | 出处 |
| --- | --- | --- |
| 推理引擎 | `dev.ffmpegkit-maintained:llama-android:0.1.1`（Maven Central，封装 llama.cpp b9878，arm64-v8a，API 24+） | `android/app/build.gradle.kts` |
| 后端 | **纯 CPU（NEON）**。厂商 README 的能力勾选表里 "Vulkan GPU acceleration" 为 ✗ | AAR 的 POM / README |
| 代码侧 | `LlamaConfig(... gpuLayers 默认 0)`，注释原文「AAR 里只有 CPU 后端（libggml-cpu.so）」；`describeRuntime()` 直接打印 `gpuLayers=0（AAR 无 GPU 后端）` | `LocalAiClient.kt` |
| 线程 | `threadCount() = coreCount().coerceIn(2, 4)`，**只看总核数，不区分大核/小核** | `LocalAiClient.kt:109,120` |
| 上下文 | `CONTEXT_SIZE = 2048` | `LocalAiClient.kt` |
| 输出预算 | `MAX_TOKENS = 512`、`MAX_TOKENS_THINKING = 768`、`DISABLE_THINKING = false`（默认**开**思考） | `LocalAiClient.kt:86,211` |
| 真机既有基准 | 8 核机 · Qwen3-1.7B Q4_K_M：一次解卦 decode **329 token / 49.90s**；关思考后 decode **12.21s**、端到端 **62s → 25.2s**；4 线程 decode **11.88 tok/s** vs 6 线程 **6.88 tok/s** | `LocalAiClient.kt` 注释（历史真机实测） |

**结论**：这一版**完全没有吃到 GPU**（不是 OpenCL 没开，而是包里根本没有 GPU 后端）。同时可见「思考开关」对端到端时长的杠杆（62s vs 25s）远大于其他任何单点优化。

---

## 2. 路线 A：换成 MNN（CPU 与 GPU 双收益，工作量最大）

| 项 | 事实 | 出处 |
| --- | --- | --- |
| 官方预编译 Android 包 | 有，但是 **zip 不是 AAR**：`mnn_3.6.1_android_armv7_armv8_cpu_opencl_vulkan.zip`，**6,197,903 B ≈ 6.2MB**（含 CPU/OpenCL/Vulkan，armv7+armv8） | GitHub Releases `alibaba/MNN` 3.6.1 |
| 该包能否直接跑 LLM | **不能**。预编译包是通用主库，**不含 LLM 模块**；跑 LLM 必须自编 | 同上 + LLM 部署文档 |
| 自编入口 | `project/android/build_64.sh`，官方 MnnLlmChat App 用的完整开关：`-DMNN_BUILD_LLM=true -DMNN_OPENCL=true -DMNN_SEP_BUILD=OFF -DMNN_ARM82=true -DMNN_LOW_MEMORY=true -DMNN_SUPPORT_TRANSFORMER_FUSE=true -DLLM_SUPPORT_VISION=true -DMNN_BUILD_OPENCV=true -DCMAKE_SHARED_LINKER_FLAGS='-Wl,-z,max-page-size=16384'` | MNN `apps/Android/MnnLlmChat/README.md` |
| 宏默认值 | `MNN_OPENCL` 默认 **OFF**；`MNN_BUILD_LLM` 默认 **OFF**（打开会自动带 `MNN_LOW_MEMORY`、`MNN_SUPPORT_TRANSFORMER_FUSE`）；`MNN_SEP_BUILD` 默认 **ON，必须显式设 OFF**，否则 App 内 OpenCL/Vulkan 后端静态注册失败，报 `Can't Find type=3 backend` | MNN 编译宏文档 / FAQ |
| NDK | 官方文档只说「建议最新稳定版」；官方 App README 写「NDK 21 recommended」 | MNN 文档 / App README |
| Android 15+ 16KB 页 | 官方 CMakeLists 已强制加 `-Wl,-z,max-page-size=16384` | MNN App CMakeLists |
| Manifest 必要条件 | `<uses-native-library android:name="libOpenCL.so" android:required="true"/>`，否则新版 Android 加载 OpenCL 驱动失败 | MNN FAQ |
| 模型（开箱可用） | ModelScope `MNN/Qwen3-1.7B-MNN`，8 个文件合计 **≈1.235 GB**：`llm.mnn` 461,520 B、`llm.mnn.weight` **1,231,860,194 B**、`llm_config.json` 4,881 B、`config.json` 403 B、`tokenizer.txt` 3,193,569 B、`configuration.json`、`README.md`、`.gitattributes` | ModelScope 模型页 |
| 切 GPU 的方式 | `config.json` 里的字符串字段 `backend_type`，默认 `"cpu"`，改成 `"opencl"` 即走 GPU —— **是运行时配置，不是编译宏**，因此可以做 App 内 CPU/OpenCL 切换对比 | MNN LLM 文档 + 模型页 |
| Java/Kotlin 集成 | **没有现成 SDK 类**。官方做法是自己写 JNI，Java 侧类为 `com.alibaba.mnnllm.android.llm.LlmSession`，**必须 `System.loadLibrary("mnnllmapp")`** | MnnLlmChat 源码 |
| GPU 能力评级 | 官方能力表：GPU-OpenCL 在 **FP16 为 S 级**（最高），Normal 为 A | MNN 介绍文档 |
| OpenCL 型号门槛 | 官方**未给出** Adreno/Mali 具体型号门槛；可核实的硬性约束只有「能 `dlopen` 到驱动 + Manifest 声明」 | MNN FAQ（型号门槛为文档缺口） |

**代价**：① 需自编 `libMNN.so`（本机缺 NDK/CMake，或在 CI 编）；② 需自写 JNI 桥 + 重写 `LocalAiClient` 的推理层；③ 模型格式从 GGUF 换成 MNN 私有格式，**已下载的 1.2GB GGUF 作废**，需换下载源（ModelScope）；④ 与 iOS 不再同源模型。

**收益**：OpenCL 后端**同时覆盖 Adreno 与 Mali**（llama.cpp 对 Mali 基本不可用）；且官方口径 MNN 的 **CPU 路径本身**就比 llama.cpp 快（prefill 8.6×、decode 2.3×）—— 对本例「decode 占绝大部分时间」的场景，CPU 侧提速比 GPU 更对口。**（厂商口径，未经本机复现，标记为待验证）**

---

## 3. 路线 B：保留 GGUF，自编 llama.cpp 打开 OpenCL/Vulkan

| 项 | 事实 | 出处 |
| --- | --- | --- |
| Maven 上有没有「免费 + 预编译 + 自带 GPU」的 llama.cpp Android AAR | **没有**。免费 AAR 全是 CPU | 各仓库 POM/README 核查 |
| 官方 Android Release | **不含 Vulkan**；Android 的 GPU 路径是 `GGML_OPENCL=ON` 自编（Snapdragon 后端文档） | llama.cpp docs/backend/snapdragon、docs/release.md |
| `com.llamatik:library:1.7.0` | Maven Central 真实存在，但 README 明确「除 macOS 外默认产物都是 CPU-only」，Vulkan 需 `-Pllamatik.cmake.args="-DGGML_VULKAN=ON"` 自编 | Llamatik README |
| `dev.ffmpegkit-maintained:llama-android-pro` | 厂商页面声称含 Vulkan（付费），**该页无法抓取正文，仅搜索片段证据 —— 未独立验证** | 厂商渠道页 |
| `llama.rn` | 支持 OpenCL，但**仅限高通 Adreno，且仅 Q4_0 / Q6_K 数据类型**；且是 React Native 的 npm 包，非 Maven 依赖 | npm llama.rn |
| Vulkan 移动端稳定性 | 已知问题：Adreno 730 SIGSEGV、Mali-G715 切后台挂死 | llama.cpp issue 追踪 |

**代价**：需自编 llama.cpp + 自写 JNI（现用 AAR 是成品 JNI，无法只替换 .so 复用）；但**模型不用重下、与 iOS 保持 GGUF 同源**。
**收益**：只对**高通 Adreno** 有效；且按量级参考，1.7B 这种小模型的 decode 未必比 CPU 快（GPU 主要赢在 prefill/首字延迟）。

---

## 4. 路线 C：零编译能吃到 GPU，但必须换模型格式

| 方案 | Gradle 坐标 | GPU | 模型格式 | 有无 Qwen3-1.7B 现成产物 |
| --- | --- | --- | --- | --- |
| LiteRT-LM（Google，主推） | `com.google.ai.edge.litertlm:litertlm-android` | `EngineConfig(backend = Backend.GPU())` | `.litertlm`（**不吃 GGUF**） | 未见 |
| MediaPipe LLM Inference | `com.google.mediapipe:tasks-genai:0.10.27` | 由转换配置决定 | `.task` | 未见，且官方标注维护模式 |
| ExecuTorch | `org.pytorch:executorch-android`（有 Vulkan / QNN 分后端预编译 AAR） | ✅ 预编译含 Vulkan | `.pte`（需 PC 端导出） | 未见 |
| llama.rn | npm（非 Maven） | OpenCL（仅 Adreno） | GGUF ✅ | 可跑 Qwen3 GGUF |

**结论**：想要「免费 + 零编译 + GPU + GGUF」，当前**不存在**满足全部条件的公开产物。

---

## 5. 量级参考（用于设定预期，来自早前一轮调研，标注为参考值）

- 1.7B Q4 在骁龙 8 系：CPU decode 约 **20–40 tok/s**；GPU prefill 可达 **800–1900 tok/s**，但 **decode 不占优**；NPU prefill 最强而 decode 反而落后。
- 「卡」的成因排序（影响量级）：**热节流（−15%~40%）> 线程与大小核亲和性（±35%）> 上下文过长（−20%~24%）> prefill chunk 过小（推高首字延迟）**。
- 线程数取「**大核数**」最优，把线程加到小核上反而变慢 —— 与本项目代码注释里「6 线程比 4 线程慢」的真机观测一致。

> 推论（**估计，非已验证**）：对本例「提示词不长、生成 300+ token」的负载，GPU 只能省掉 prefill 的几秒，**decode 段才是「卡」的主体**。所以单纯打开 OpenCL 未必解决问题；而 MNN 的 CPU 侧提速 + 缩短输出 + 大核线程，可能收益更大。

---

## 6. 本机工具链现状（已验证：2026-09-25 实查）

| 组件 | 状态 |
| --- | --- |
| Android SDK | ✅ `C:\Users\adex\AppData\Local\Android\Sdk`，含 `build-tools` / `platforms` / `platform-tools` / `emulator` / `sources` |
| **NDK** | ❌ **不存在**（`ndk`、`ndk-bundle` 均无） |
| **CMake** | ❌ **不存在**（SDK 下 `cmake` 目录无） |
| JDK | ✅ Java 21 |
| kotlinc | ❌ 无（可用 gradle 缓存里的 `kotlin-compiler-embeddable` 顶替，见 `DEV_STATUS.md` 第 4 节） |

**含义**：路线 A 与路线 B 都必须先补 NDK + CMake（约 1GB 量级下载），或把 native 编译放到 GitHub Actions 里做（本仓库已有 `Android Build` workflow，改为在 CI 编 .so 并作为产物/入库更省本地成本）。

---

## 7. 待确认项（未验证，需实测/用户输入）

1. 用户测试机的 SoC（Adreno vs Mali）—— 决定路线 B 是否可行、以及路线 A 的 OpenCL 预期收益。
2. 自编 `libMNN.so`（LLM 版）的实际体积 —— 官方无公开数字，需编译后实测。
3. MNN 在 Qwen3-**1.7B** 上的实际 tok/s —— 官方基准用的是 Qwen3-0.6B / Qwen3-4B，未列 1.7B。
4. 「MNN CPU decode 比 llama.cpp 快 2.3×」为厂商口径，需本机同机对比复现。
5. 付费 Pro AAR 是否真含 Vulkan —— 厂商页无法抓取正文，未独立验证。
