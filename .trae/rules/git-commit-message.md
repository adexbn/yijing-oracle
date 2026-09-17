---
scene: git_message
---

# 提交信息规范（本仓库）

- 使用中文书写，格式：`<类型>(<范围>): <一句话说清改了什么>`。
- 类型：`feat` / `fix` / `refactor` / `docs` / `chore` / `ci` / `test`。
- 范围：`ios` / `android` / `both` / `ci` / `docs`。
- 标题控制在 50 字以内；正文说明**为什么改**，以及**怎么验证的**（本地命令或 CI run 号）。
- 例：`fix(ios): 修正 InputGuard 标点字面量未转义导致的编译失败（178:49 expected {）`
