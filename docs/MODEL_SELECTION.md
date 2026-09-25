# 端侧模型选型调研（2026-09-25）

> 回答用户四问：① 在 Llama-3.2-3B-Instruct / Gemma-2-2B-it / Gemma-2B / Qwen3-Instruct / Qwen3.5-2B-Instruct 里选哪个；
> ② 这些模型的**在线试用网址**；③ 若语义不亚于 Qwen3-1.7B 才考虑换；④ 是否该转 MLC-LLM（TVM）或改用高通原生 QNN Engine。
>
> 口径约定：每条事实标注 `[已验证]`（一手来源或可直接复现）或 `[待验证]`。**本文未改任何代码。**

---

## 0. 结论先行

| 问题 | 结论 |
| --- | --- |
| 选哪个 | **只值得试 `Qwen3.5-2B-Instruct`**。它同族同思考机制，官方 benchmark 在两个模式下几乎全面优于现役 Qwen3-1.7B `[已验证]` |
| Llama-3.2-3B-Instruct | 不推荐。非原生中文，第三方横评同参数档落后 Qwen2.5-3B |
| Gemma-2-2B-it / Gemma-2B | 不推荐 —— **这是 2024 年的老代**，已被 Gemma 4 取代；要评估 Gemma 也该评 **Gemma 4 E2B** |
| Qwen3-Instruct | 就是现役模型（Qwen3-1.7B），无需换 |
| Qwen3.5-0.8B | **不要**。官方同表里全面落后 Qwen3-1.7B，是降级 |
| 换到 Qwen3.5-2B 的工程代价 | **比预期小得多 —— 无需换引擎**。iOS 现役的 llama.cpp `b10809` 已原生支持 `qwen35` 架构且带 Metal 的 gated-delta-net 内核 `[已验证]`；Android 侧 MNN 也支持导出 Qwen3.5-2B |
| MLC-LLM (TVM) | **不做**。换引擎要给两端同时换推理栈 + 换模型格式 + 重测，收益不匹配；未见其支持 Qwen3.5 |
| 高通 QNN Engine | **不采纳为主路线**。只覆盖高通、且 decode 受内存带宽限制使 NPU 收益有限（见 `PERF_AUDIT.md` 9.3）；华为端无效。可作可选 backlog |
| 下一步 | 先按用户要求**测语义**（本文第 4 节给了网址），语义过关再动手；动手时是「换模型」而非「换引擎」 |

---

## 1. 候选模型逐个评估

### 1.1 Qwen3.5-2B-Instruct —— 唯一推荐候选

`[已验证]` 一手来源：Hugging Face 官方模型卡 `Qwen/Qwen3.5-2B`。

| 项 | 值 |
| --- | --- |
| 参数 | 2B Dense（非 MoE） |
| 模态 | **Image-Text-to-Text**（原生多模态） |
| 许可 | Apache 2.0 |
| 隐层 / 层数 | hidden 2048 / 24 层 |
| 结构 | `6 × (3 × (Gated DeltaNet → FFN) → 1 × (Gated Attention → FFN))` |
| 注意力 | Gated DeltaNet 16 heads (head dim 128) + Gated Attention 8Q/2KV (head dim 256, RoPE 64) |
| FFN | 6144 |
| 上下文 | **262,144** |
| 默认模式 | **非思考**（需显式开启 thinking 软开关 `/think`） |

官方同表 benchmark 与现役 Qwen3-1.7B 对比 **（左 Qwen3-1.7B → 右 Qwen3.5-2B）**：

| 基准 | 非思考 | 思考 |
| --- | --- | --- |
| MMLU-Pro | 40.2 → **55.3** | 56.5 → **66.5** |
| MMLU-Redux | 64.4 → **69.2** | — |
| C-Eval（中文） | 61.0 → **65.2** | 68.1 → **73.2** |
| SuperGPQA | 21.0 → **30.4** | — |
| GPQA | — | 40.1 → **51.6** |
| IFEval | 68.2 → 61.2 ⚠️ | 72.5 → **78.6** |
| IFBench | — | 26.7 → **41.3** |
| MMMLU | 46.7 → **56.9** | 57.0 → **63.1** |
| AA-LCR | — | 6.7 → **25.6** |
| LongBench v2 | — | 26.5 → **38.7** |
| WMT24++ | — | 39.3 → **45.8** |

**要点**：除**非思考模式的 IFEval**（68.2 → 61.2，唯一退步项）外，其余全部提升，思考模式提升幅度更大。**注意我们的硬约束是「必须开思考」**，而思考模式下 IFEval 反而是升的（72.5 → 78.6）⇒ 对本项目而言是净收益。

`[已验证]` **思考模式官方采样参数与现役 Qwen3 不同**，换模型时必须一起改：

```
temperature=1.0, top_p=0.95, top_k=20, min_p=0.0, presence_penalty=1.5, repetition_penalty=1.0
```

（现役 Qwen3-1.7B 思考用的是 `temp 0.6 / top_p 0.95 / top_k 20`。VL 与精确代码任务官方另建议 `0.6 / 0.95 / 20`。）

**代价 `[估计]`**：2B vs 1.7B，参数 +约 18%；decode 是内存带宽受限（`PERF_AUDIT.md` 9.3），故 token 级耗时大致同比上升，即 iOS 45 tok/s → 约 38 tok/s、Android 18.37 tok/s → 约 15.6 tok/s。端到端因思考段长度变化可能不同，**须真机实测**。

### 1.2 Qwen3.5-0.8B —— 明确不要

`[已验证]` 同一张官方 benchmark 表里，0.8B 在**两个模式下、所有项目**都落后于 Qwen3-1.7B（如非思考 MMLU-Pro 29.7 vs 40.2、思考 GPQA 11.9 vs 40.1）。第三方综合横评也把 Qwen3-1.7B 列为 1B 级最强。**换 0.8B 等于降级。**

### 1.3 Gemma 系列 —— 代际要纠正

`[已验证]` 用户提到的 **Gemma-2-2B-it / Gemma-2B 是 2024 年发布的代际**，当前已是 **Gemma 4**。

| 项 | Gemma 4 E2B |
| --- | --- |
| 参数 | **2.3B effective / 5.1B total**（Per-Layer Embeddings） |
| 许可 | Apache 2.0 |
| 语言 | 预训练 140+ 语言，开箱 35+ |
| 上下文 | 128K（E2B/E4B） |
| 端侧 | Android AICore Developer Preview 已支持 |

`[待验证 / 倾向不选]` 多份第三方横评反映 Gemma 中文「一般至良好、词元结构对中文不够紧凑」（同字数中文占用 token 更多 ⇒ 端侧更费预算）。且 E2B 的 **total 5.1B** 对端侧是明显更重的模型。**若真要评估 Gemma，应评 Gemma 4 E2B，而不是 Gemma-2-2B。**

### 1.4 Llama-3.2-3B-Instruct —— 不推荐

`[已验证]` 多语言非原生；第三方横评显示 Llama 3.2 3B 在同参数档落后 Qwen2.5-3B（工具调用实测 qwen2.5 100% vs llama 92%）。中文占卜文案场景不占优。

### 1.5 一句话对比

| 模型 | 中文 | 相对现役 1.7B | 端侧体积 | 换的代价 | 结论 |
| --- | --- | --- | --- | --- | --- |
| Qwen3.5-2B | 强（C-Eval +4.2） | **更强** | 略大 | 小（引擎已支持） | ✅ 试 |
| Qwen3.5-0.8B | 一般 | **更弱** | 更小 | 小 | ❌ 降级 |
| Qwen3-1.7B（现役） | 强 | — | 基准 | — | 保持 |
| Gemma 4 E2B | 一般 | 未证实 | 更重(5.1B total) | 中（iOS 需换 GGUF 且待验证） | ⚠️ 备选 |
| Gemma-2-2B | 一般 | 未证实 | 中 | 中 | ❌ 老代 |
| Llama-3.2-3B | 弱 | 未证实 | 更大 | 中 | ❌ |

---

## 2. 引擎路线可行性（决定「换模型」还是「换引擎」）

### 2.1 iOS —— 无需换引擎，现役 build 已支持 `[已验证]`

这是本次调研最重要的发现，**推翻了「换 Qwen3.5 在 iOS 上是大工程」的初判**。

用 GitHub API 对**我们 CI 里锁定的那个 tag** 逐项核实（`.github/workflows/ios.yml:22-28` 下载 `llama-b10809-xcframework.zip`）：

| 核实项 | 结果 |
| --- | --- |
| `src/llama-arch.cpp` @ `b10809` | 含 `{ LLM_ARCH_QWEN35, "qwen35" }` 与 `{ LLM_ARCH_QWEN35MOE, "qwen35moe" }`，并有对应 `case LLM_ARCH_QWEN35:` 分支 |
| `ggml/src/ggml-metal/kernels/` @ `b10809` | 含 **`gated_delta_net.metal`（8567 B）**、`ssm.metal`、`solve_tri.metal` |

⇒ Qwen3.5 的 Gated DeltaNet 线性注意力层**在 iOS 的 Metal 后端有原生内核**，且架构识别已就位。**换到 Qwen3.5-2B 不需要换引擎、不需要第三方 fork。**

`[已验证]` 补充时间线：`b7976` 加入 Qwen3.5 dense/MoE 模型支持（#19435）；`b8233` 加入 `GATED_DELTA_NET` 算子（#19504，当时仅 CPU/CUDA）；`b9667` Vulkan 支持 gated_delta_net。我们的 `b10809` 晚于上述全部提交。

**仍需实测确认的**：XCFramework 是预编译产物，内核是否被编进 `llama.framework` 内、以及真机 tok/s，都必须装机才知道 `[待验证]`。

### 2.2 Android —— MNN 支持导出，成本低 `[已验证]`

`[已验证]` MNN 自己的文档给出 Qwen3.5-2B 的 QNN/NPU 导出命令：

```
python3 npu/generate_llm_qnn.py --model /path/to/Qwen3.5-2B-MNN/ --soc_id=57 --dsp_arch=v75
```

说明 MNN 已支持 Qwen3.5-2B 的导出通道（通用导出路径仍是 `llmexport.py --path ... --export mnn --hqq`，默认 4-bit；预转换模型在 ModelScope `MNN/*-MNN`）。换模型对 Android 来说 = 换一份 MNN 模型文件 + 更新 `ModelManager` 的 5 文件清单与体积阈值。

### 2.3 MLC-LLM (TVM) —— 不做

`[已验证]` 高通官方确有 Adreno GPU 上跑 MLC-LLM 的文档；但 MLC 走 TVM 自有编译格式，换它意味着 **iOS 与 Android 同时更换推理栈 + 更换模型格式 + 全部基准重测**，是本项目能选的路线里代价最大的一条。`[待验证]` 未见 MLC-LLM 支持 Qwen3.5。**结论：无必要，不做。**

### 2.4 高通原生 QNN Engine —— 不采纳为主路线

三条独立理由：

1. `[已验证]` 目标机型是**高通 + 华为**，QNN 对华为（Kirin/Mali）完全无效 ⇒ 至少两套路径并行维护。
2. `[已验证]` decode 是内存带宽受限而非算力受限（`PERF_AUDIT.md` 9.3）。已有 5 条独立证据表明安卓 GPU/NPU 的 decode 未必快过 CPU（LiteRT-LM 官表 GPU decode 仅 **1.11×** 等）。
3. `[已验证]` 项目侧：MNN 的高通专属路径 `MNN_HEXAGON` 只支持 4-bit 对称量化且需 `libMNN_htpops*.so` 与自编引擎（`PERF_AUDIT.md` 9.2 已评估，判定不建议现在做）。

`[已验证]` 另有高通 **GenieX**（2026-06 developer preview，开源 runtime），可跑 HF GGUF 与 AI Hub 预编译模型，`compute_unit` 可选 NPU/GPU/CPU。**记录备查**，不作为主路线。

`[已验证]` 高通 AI Hub 已上架 `Qwen3.5-2B` / `Qwen3.5-0.8B` / `Qwen3-VL-2B` / `Qwen3-VL-4B` / `Gemma-4-E2B` / `Qwen3-4B-Instruct-2507` 等，可作为日后需要 NPU 时的现成入口。

---

## 3. 换模型要动的地方（清单，动手时才做）

| 端 | 要改 | 位置 |
| --- | --- | --- |
| iOS | 模型下载 URL + 体积阈值 | `ModelManager.swift:45`（`isDownloaded()` = 文件大小 > `minModelBytes`） |
| iOS | 思考采样参数换成 Qwen3.5 官方值 | `LlamaCPP.swift`（现为思考 `0.6 / 0.95 / 20`，需改 `1.0 / 0.95 / 20` + `presence_penalty 1.5`） |
| iOS | chat template 的思考开关写法复核 | `LlamaCPP.applyChatTemplate()`（Qwen3.5 默认非思考，需确认软开关行为与 Qwen3 是否一致）`[待验证]` |
| iOS | 版本号 | `ios/project.yml` |
| Android | MNN 5 文件清单 + 体积阈值 | `ModelManager.kt` |
| Android | 思考采样参数 | `LocalAiClient.kt`（现 `MAX_TOKENS_THINKING = 768`） |
| 两端 | 上下文 2048 是否放宽 | `LocalAiClient.kt` `CONTEXT_SIZE = 2048`（Qwen3.5-2B 支持 262K，但端侧不必放宽） |

---

## 4. 在线试用入口（用户要的网址）

> 说明：**免费官方 Qwen Chat 拿不到小模型**。`[已验证]` 实测 `chat.qwen.ai` 的模型接口只返回旗舰（`qwen3.7-plus`、`qwen3.8-max`、`qwen3.8-omni-flash`），历史上也只放过 235B/30B/32B。所以「用官方网页测 2B」这条路不存在，需走下面三种。

### 4.1 最推荐：本地跑（最接近真实，可与现役 1.7B 直接对照）

`[已验证]` Ollama 官方库已有 `qwen3.5`，tags 覆盖 `0.8b / 2b / 4b / 9b / 27b / 35b / 122b`，其中 `2b` 为 **2.7 GB / 256K 上下文 / Text+Image**。

- Ollama 模型页：https://ollama.com/library/qwen3.5
- 命令：`ollama run qwen3.5:2b`（对照现役：`ollama run qwen3:1.7b`）

GGUF 直下（用于本地 GUI / llama.cpp 手测）：

- `unsloth/Qwen3.5-2B-GGUF` https://huggingface.co/unsloth/Qwen3.5-2B-GGUF （下载量最高，约 32 万）
- `bartowski/Qwen_Qwen3.5-2B-GGUF` https://huggingface.co/bartowski/Qwen_Qwen3.5-2B-GGUF
- `lmstudio-community/Qwen3.5-2B-GGUF` https://huggingface.co/lmstudio-community/Qwen3.5-2B-GGUF
- 现役对照：`Qwen/Qwen3-1.7B-GGUF` https://huggingface.co/Qwen/Qwen3-1.7B-GGUF

### 4.2 免安装网页试（浏览器直接开）

使用前请自行确认各 Space 实际挂的是哪个尺寸（同名 Space 可能默认大模型）：

- Qwen3.5（浏览器内跑，WebGPU）https://huggingface.co/spaces/webml-community/Qwen3.5-WebGPU
- Qwen3.5-0.8B（WebGPU，仅作下限参考）https://huggingface.co/spaces/webml-community/Qwen3.5-0.8B-WebGPU
- Qwen3.5 官方 Demo https://huggingface.co/spaces/Qwen/Qwen3.5-Omni-Online-Demo
- 社区 Qwen3.5 Demo https://huggingface.co/spaces/prithivMLmods/Qwen-3.5-HF-Demo
- Gemma 4（WebGPU）https://huggingface.co/spaces/webml-community/Gemma-4-WebGPU
- Gemma 4 E4B https://huggingface.co/spaces/huggingface-projects/gemma-4-e4b-it

### 4.3 多模型对照 / 竞技场

- **OpenRouter Chat**（一个页面切模型，可对照）https://openrouter.ai/chat
  `[已验证]` 库内可用：`meta-llama/llama-3.2-3b-instruct`、`google/gemma-4-31b-it:free`、`google/gemma-4-26b-a4b-it:free`
  ⚠️ 库里**没有** `qwen3-1.7b` / `qwen3.5-2b` / `gemma-2-2b`
- **Google AI Studio**（Gemma 4 31B/26B MoE 免费试）https://aistudio.google.com/
- **盲测竞技场**（两个模型匿名对照，最适合「语义是否不亚于 1.7B」这种成对判断）https://lmarena.ai/
- 官方 Qwen Chat（仅旗舰，作「天花板参考」）https://chat.qwen.ai/

### 4.4 建议的测法

占卜 App 的语义需求集中在**中文长文指令跟随 + 语气 + 不越界**。建议用现役 1.7B 与 Qwen3.5-2B 跑**同一批固定题目**（可用仓库里 382 条语料中的正常提问子集 + 若干条真实占卜问句），逐条对齐看：① 是否稳定输出完整解读；② 思考段是否真的产生内容；③ 语气与「不泄漏系统提示词」是否守住。**同题同参对比**才有意义。

---

## 5. 待办

1. `[待验证]` 用户按第 4 节实测语义；若不亚于 Qwen3-1.7B 再进入第 3 节清单。
2. `[待验证]` iOS 真机验证 `b10809` 的 XCFramework 是否真把 `gated_delta_net` 编进去（跑一个 Qwen3.5-2B GGUF，看是否报未知架构 / 是否回落 CPU）。
3. `[待验证]` 换模型后采样参数必须一并改成 Qwen3.5 官方值（`temp 1.0 / top_p 0.95 / top_k 20 / presence_penalty 1.5`），否则质量会异常。
4. `[待验证]` Qwen3.5 的思考软开关（`/think`）与 Qwen3 的「模板补空思考块」机制是否等价 —— 直接决定 `applyChatTemplate()` 怎么改。
