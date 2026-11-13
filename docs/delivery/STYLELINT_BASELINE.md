# Stylelint 存量基线与治理计划（B4）

> 记录时间：2026-11-13 · 命令：`cd frontend; npx stylelint "src/**/*.{css,scss,vue}" --formatter compact`
> 当前策略：**CI 中作为 advisory 报告（不阻断）+ 上传 artifact**；分批修复后再切为阻断门禁。

## 一、基线与自动修复结果（2026-11-13 更新）

| 阶段 | 数量 | 说明 |
|---|---|---|
| 最初基线 | 5,312 | 新增卫星对比页后重测（原 5,264） |
| `stylelint --fix` 第一批 | 2,440 | 修复 2,872 条；86 个文件（格式/颜色写法） |
| Prettier 只格式化 `<style>` 第二批 | **82** | 消除 2,358 条；85 个文件（每个声明独立一行） |
| 剩余 | 82 | 全部为语义项，不再自动修复 |

剩余规则分布（均为需人工确认的语义项）：

| 规则 | 数量 | 说明 | 处理方式 |
|---|---|---|---|
| `no-duplicate-selectors` | 71 | 同文件重复选择器（可能是有意覆盖/媒体查询） | 人工确认合并或保留并说明 |
| `selector-type-no-unknown` | 2 | 未知选择器类型 | 人工核对 |
| `declaration-property-value-keyword-no-deprecated` | 2 | 废弃关键字 | 人工替换 |
| `declaration-property-value-no-unknown` | 1 | 未知属性值 | 人工核对 |

> 第二批实现：`scripts/delivery/format-style-blocks.mjs`（dry-run 默认，`--write` 写入），
> 仅对 Vue `<style>` 块与独立 `.css` 调用 `prettier.format(parser='css')`，不改模板/脚本；
> 随后 `stylelint --fix` 清理格式化产生的空行规则。
>
> **回归证据**：eslint 0 / vitest **69 passed** / `vite build` 成功 / SFC **98/98** /
> 真实浏览器 **37/37 PASS**（截图 `output/browser-verify/`，总览页/橙色弹窗抽查无变化）。

## 二、后续治理计划

1. ~~第一批（低风险自动修复）~~ ✅ 5,312 → 2,440。
2. ~~第二批（单行多声明）~~ ✅ 2,440 → 82（Prettier 样式块格式化）。
3. **第三批（82 条语义项）**：逐条确认重复选择器是否可合并、废弃关键字替换；
   修复后把 CI 的 `continue-on-error` 去掉，改为阻断。
4. 若确认重复选择器为合法分层覆盖，可在 `.stylelintrc.json` 对 `no-duplicate-selectors`
   增加注释与例外（需 code review，不建议直接关闭规则）。

## 三、当前 CI 行为

`.github/workflows/frontend-ci.yml` 新增：
- `Stylelint (advisory baseline)`：执行失败不阻断，输出报告；
- `Upload stylelint report`：artifact `stylelint-report` 可供下载统计。

## 四、风险与注意

- `stylelint --fix` 会大范围改样式文件，必须单独分支、逐页视觉回归（深色模式/移动端）；
- 不要与业务功能改动混在同一提交；
- 修复期间保持 `npm run build` 与 37 项浏览器冒烟通过。
