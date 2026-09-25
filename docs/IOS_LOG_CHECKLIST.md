# iOS 真机跑日志清单（v1.2.0 build 7）

用途：用户装上 CI 新版后，按本清单跑一次，把日志回传，用于**定案两件目前无法从旧日志判定的事**。

最后更新：2026-09-25

---

## 0. 这一版是什么

| 项 | 值 | 来源 |
| --- | --- | --- |
| 提交 | `72b8071`（main HEAD） | `git rev-parse HEAD` |
| CI | `iOS Build #27`，run `36104029782`，**success** | GitHub Actions API（2026-09-25T06:41:48Z → 06:45:32Z） |
| 产物 | `Yijing-adhoc-ipa`，3 502 054 B | Actions API |
| 过期 | **2026-10-09T06:45:26Z**（retention 14 天） | Actions API |
| 启动日志应打 | `version=1.2.0 build=7` | `ios/project.yml` 的 `MARKETING_VERSION` / `CURRENT_PROJECT_VERSION` |

`72b8071` 之后 iOS 侧（`ios/**`）无新提交，所以这就是当前能拿到的**最新 iOS 构建**。
Swift 文件工作区干净，不存在「未提交的 iOS 修复」被漏测的情况。

## 1. 安装

步骤同 `README.md`「iPhone 自用安装（无需 $99 账号）」：下载产物 → Sideloadly 用自己 Apple ID 重签 → 免费签名 7 天有效。
注意：Actions 产物页需**登录 GitHub**才能下载（匿名取 zip 返回 401，已实测）。

## 2. 跑之前先确认这行（装错包就白跑）

```
========== App 启动 version=1.2.0 build=7 ==========
```

若仍是 `version=1.0 build=1`，说明装的是旧包，请重装后再跑。

## 3. 取日志

`Documents/yijing_log.txt` ——「文件」App → 我的 iPhone → 周易小卦。
（`UIFileSharingEnabled` / `LSSupportsOpeningDocumentsInPlace` 已开；App 内「设置 → 查看崩溃日志」只能看末尾 8000 字符，取完整日志请走「文件」App。）

## 4. 建议跑 3 个用例

1. 一次**正常解卦**（完整走完，等出结果）
2. 一次**再解一卦**（连跑第二卦，看模型是否保留在内存、加载耗时是否缩短）
3. 一次**偏长的提问**（观察是否撞 `maxTokens=320` 上限）

## 5. 回传时我要看的行（按关键词自查）

| 关键词 | 作用 |
| --- | --- |
| `App 启动 version=` | 确认版本（见第 2 节） |
| `STEP 2: 开始加载模型` | `n_gpu_layers=99` 走 Metal / 是否出现「回退纯 CPU」 |
| `STEP 4: 创建 context` | `threads=` 是否为全核（A19 应 6） |
| `STEP 5: 分词 OK` / `采样参数` | `top_k=20, top_p=0.8, temp=0.7` 是否生效 |
| `⏱ 生成：` | **真实 tok/s**（代码注释：纯 CPU 约 19 token/s，Metal 卸载应明显更高） |
| `STEP 8: 生成结束` | 命中 EOG 正常结束 / 撞 320 上限（异常） |
| `STEP 8: 输出预览 =` | **定案「iOS 到底有没有在思考」的唯一证据** |

## 6. 待定案的两件事（旧日志判不了）

**A. iOS 是否真的在思考**
判据：`STEP 8: 输出预览` 是否以 `<think>` 开头。
背景：当前 `LlamaCPP.swift` 的 `applyChatTemplate` 末尾写的是 **空思考块** `<think>\n\n</think>\n\n`（注释称是 Qwen3 `enable_thinking=false` 官方写法），且 Swift 侧**没有** `enableThinking` 开关（`git log --all -S'enableThinking' -- ios` 为空）。
用户已明确 iOS「启用了思考」，因此需用预览行确认实际行为，不再靠推测。

**B. iOS 当前构建的真实速度**
判据：`⏱ 生成：N ms（N tokens，N tok/s）`。
背景：用户手上那份日志是**纯 CPU**构建（见第 7 节），不能代表当前 Metal 构建；跨框架（MNN vs llama.cpp）也不可比。

## 7. 附：用户手上旧日志实证的老 bug（均已在代码侧修复，需新版复验）

| # | 现象（旧日志原文/实证） | 状态 |
| --- | --- | --- |
| 1 | `App 启动 version=1.0 build=1`（版本号被 XcodeGen 默认值覆盖） | `ec2ce38` 修；新版应为 `1.2.0 build=7`，待复验 |
| 2 | `STEP 2: ... (n_gpu_layers=0, load_mode=NONE)` —— 纯 CPU，无 Metal | `87a8695` 改回 99；待复验 |
| 3 | `MTL0 compute buffer size is 0.0000 MiB` —— Metal 零卸载（与 2 同源） | 同上 |
| 4 | `STEP 8: 生成结束，共 1024 个 token，输出 1138 字节` —— 跑满上限、未命中 EOS（输出退化） | `ec2ce38` 套 ChatML 模板 + 正规采样链修「1024 个『！』」；待复验 |
| 5 | 日志缺 `STEP 8: 输出预览` 行（`git log -S'STEP 8: 输出预览' -- ios` 仅命中 `ec2ce38`）⇒ 旧日志无法判定是否思考 | 新版已有此行，本次回传即为首次取证 |
| 6 | `93a7589`：`n_batch` 越界导致 `GGML_ASSERT` 崩溃（分批喂 prompt 修） | 已修 |
| 7 | `189af24`：`InputGuard.swift` 标点字面量未转义编译失败 | 已修 |

> 口径说明：第 1–5 条为**日志实证**（用户上传的两份 iOS 日志，2026-09-15 / 09-16）；第 6–7 条为**提交历史记录**，非日志实证。
