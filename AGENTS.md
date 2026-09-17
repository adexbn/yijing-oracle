# AGENTS.md

本文件是给 AI 智能体（TraeCode、以及其他支持 `AGENTS.md` 的 IDE）的项目级行为指引。
通用写作规范见全局规则；本文件只写**这个仓库特有**的约束。

## 项目是什么

「周易小卦」：一款**完全离线**的周易解卦 App。iOS（SwiftUI）与 Android（Kotlin）两端各自内置
Qwen3-1.7B Q4_K_M GGUF + llama.cpp，不联网也能出卦与解读。两侧代码同仓，共用同一套卦象与语料数据。

## 进度必须落文件（硬性要求）

**对话上下文会被压缩，只有文件里的内容能活下来。** 因此：

- 项目状态与进展的唯一权威是 `DEV_STATUS.md`（仓库根）。每次有实质改动，必须同步更新它：
  1. 更新「1. 当前状态一览」表；
  2. 在「6. 进展日志」表尾追加一行（日期 / 提交号 / 做了什么 / 怎么验证的）；
  3. 踩到新坑，补进「5. 事故与坑」表。
- 不要只在对话里汇报「已完成」，而不落文件 —— 那等于没做。
- 结论要区分**已验证**与**估计**；数字必须带口径（哪批数据、多少条、是否参与过调参）。

## 本机环境约束（务必先认清再动手）

- 开发机是 **Windows**，没有 macOS：**iOS 端本地无法编译**（无 Xcode / swiftc / xcodebuild）。
- iOS 的真实编译、打包、IPA 产出**只能靠 GitHub Actions**：改完 `git push` 触发 `iOS Build`，
  产物为 `Yijing-adhoc-ipa`（路径 `ios/build/ipa/Yijing-adhoc.ipa`）。
- Android 端可以在本机编译：`cd android; ./gradlew.bat assembleDebug`，CI 另有 `Android Build`。
- 工程用 XcodeGen 生成：`ios/project.yml` 里 `sources: - path: Yijing` 是**目录级自动发现**，
  新增 Swift 文件不需要手工登记到工程文件。

## 动手前 / 提交前

- 改动 iOS Swift 代码前，先自查**字符串字面量闭合**与括号配平 —— 曾经因为把全角引号写成
  ASCII `"` 导致字符串提前闭合，报出 `expected '{' to start the body of for-each loop`，
  本地又编不了，只能靠 CI 兜一圈才发现。
- 涉及规则/词表类改动，必须与 Python 参考实现做**等价性回归**（从 Swift 源自动抽取种子表与正则，
  不要手抄），不一致必须为 0。
- 提交信息用中文，形如 `fix(ios): …` / `feat(android): …`，说清「改了什么 + 为什么」。

## 红线

- 不编造数据、不伪造验证结果。跑不通就说跑不通，没数据就说没数据。
- 不删改用户已有的未提交改动；不擅自扩需求。
- 本地模型（约 1.2GB）不随包发布，装机后在设置里下载；这是有意为之，不要「顺手」塞进产物。
