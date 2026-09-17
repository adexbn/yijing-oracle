# 开发状态（唯一权威）

> 本文件是「周易小卦」项目的进度与状态台账，**每次有实质改动就追加/更新**。
> 目的：对话上下文被压缩、跨设备、换模型协作时，都能靠这一份文件接着干。
> 规则：只写事实，不写推测；结论必须标注「已验证」或「估计」；数字要带口径。

- 最后更新：2026-09-17
- 最近提交：`28ef289`（分支 `main`；更准确以 `git log` 为准）
- 远端：https://github.com/adexbn/yijing-oracle （public）
- 本机仓库路径：工作区下的 `yijing-ios/`（内含 `ios/` 与 `android/`）

---

## 1. 当前状态一览

| 端 | 功能状态 | 编译验证 | 备注 |
| --- | --- | --- | --- |
| iOS | 输入有效性拦截（P3 规则闸）已接入 | ✅ CI 全绿（iOS Build #26）；**真机实测通过** | 产物 `Yijing-adhoc-ipa`，已装机运行正常 |
| Android | 输入有效性拦截（P3 规则闸）已接入，与 iOS 同源同表 | ✅ 本机 `assembleDebug` 通过；382 条语料等价性 0 差异 | 待真机实测 |

## 2. 进行中 / 待办

1. **Android 真机实测**：装机验证三条路径 —— 危险输入直达安全提示（不调模型）、非有效提问软引导、正常提问照常解读。
2. **Python 参考实现回写**：把已验证的 P3 补丁写回本机参考实现 `input_guard_sim.py`，保证原型与两端代码同源。
3. **（悬置）第二道闸**：App 侧本地小模型闸（Qwen3-1.7B）尚未接；只在第一道规则闸漏放时才有必要触发。

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

平台差异的替代方案（两端一致）：无 `pypinyin` → 用 `DANGER_VARIANTS` 变体/谐音等价表（26 条）顶替音节比对；无 OpenCC → 同表内含繁体写法。

Java 与 Swift/Python 的语义差异（Kotlin 侧已显式补偿）：Java `\w`/`\b`/`\d` 默认 ASCII 语义，Swift(ICU)/Python 是 Unicode 语义 → 引入 `WB` 字符类常量，`\b` 改写为 `(?<!WB)` / `(?!WB)` 前后瞻；字符计数统一用 `codePointCount`；Unicode 分类用 `Character.getType()` 对应 Swift `generalCategory` / Python `unicodedata.category`。


## 4. 构建与验证怎么做

| 端 | 本地可否编译 | 正式验证路径 |
| --- | --- | --- |
| Android | 可以（Windows 即可） | `cd android; ./gradlew.bat assembleDebug`；CI `Android Build` |
| iOS | **不可以**（本机是 Windows，无 Xcode/swiftc） | 只能 `git push` 触发 CI `iOS Build`（macos-15 + Xcode 16.4） |

CI 细节：`.github/workflows/ios.yml`（workflow `iOS Build`，id `358457133`）流程为 XcodeGen 生成工程 → 下载 llama.cpp XCFramework b10809 → 编译（不签名）→ 归档 → Ad-hoc 签名 → 打包 → 上传产物 `Yijing-adhoc-ipa`。`.github/workflows/android.yml`（`Android Build`，id `358478805`）跑 `assembleDebug`，产物 `Yijing-debug-apk`。两端均带 `paths` 过滤，改哪端跑哪端。

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
| 2026-09-17 | 从 Actions 取不到原始编译日志 | `actions/jobs/{id}/logs` 需 admin | 改走 check-runs 注解 + 网页日志落盘离线 grep |

其他已知约束：本地小模型（约 1.2GB GGUF）不随 App 打包，装机后由用户在「设置 → 本地小模型」下载或导入；iOS 端 llama.cpp 锁定在仍含 `Package.swift` 的 revision，升级需对照新 `llama.h` 校正 `LlamaCPP.swift` 参数名。

## 6. 进展日志（新→旧）

| 日期 | 提交 | 内容 | 验证 |
| --- | --- | --- | --- |
| 2026-09-17 | （本次提交） | **Android 同步 P3 输入拦截**：新增 `android/app/src/main/java/com/yijing/app/core/InputGuard.kt`（与 Swift 同源同表，另补偿 Java 正则 ASCII 语义差异）；`MainActivity.openResult()` 改三支路由（危险→结果页直出安全提示不调模型 / 非有效→等待页带软引导 / 其余照常）；`LoadingActivity` 增 `guardHint` 并在云端、本地两条 AI 路径前置系统提示词 | ① 本机 `gradlew --offline assembleDebug` **BUILD SUCCESSFUL**（1m49s）；② 编译真实 `InputGuard.kt` 在 JVM 跑 382 条语料，与参考实现**判定 0 差异** |
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
