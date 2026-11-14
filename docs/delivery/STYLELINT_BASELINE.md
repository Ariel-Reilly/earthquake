# Stylelint 存量基线与治理计划（B4）

> 记录时间：2026-11-14 · 命令：`cd frontend; npx stylelint "src/**/*.{css,scss,vue}" --formatter compact`
> 当前状态：**0 error，CI 已切为阻断门禁**（例外仅 2 个有意覆盖层文件，见 §三）。

## 一、治理结果

| 阶段 | 数量 | 说明 |
|---|---|---|
| 最初基线 | 5,312 | 新增卫星对比页后重测（原 5,264） |
| 第一批 `stylelint --fix` | 2,440 | 修复 2,872 条；86 个文件（格式/颜色写法） |
| 第二批 Prettier 样式块格式化 | 82 | 消除 2,358 条；85 个文件（每个声明独立一行） |
| 第三批语义项人工修复 | **0** | 修复 5 处真实问题 + 合并 6 处重复块 + 2 处覆盖层豁免 |

> 第二批实现：`scripts/delivery/format-style-blocks.mjs`（默认 dry-run，`--write` 写入），
> 仅对 Vue `<style>` 块与独立 `.css` 调用 `prettier.format(parser='css')`，不改模板/脚本。

## 二、第三批修复明细

| 问题 | 修复 |
|---|---|
| `ModelManagePage.vue` 选择器 `\3c style scoped > .model-manage-page`（CSS 转义残留，规则实际失效） | 改为 `.model-manage-page`，页面框架样式恢复生效 |
| `word-break: break-word` ×2（`AiAssistant.vue`、`WelcomePage.vue`） | 改为标准 `overflow-wrap: break-word` |
| `mask-composite: xor`（`WelcomePage.vue`，非标准值） | 删除无效值，保留标准 `exclude` |
| 重复选择器（组件内可安全合并的 6 处） | 删除 `DamageAssessmentPage` / `ModelManagePage` / `HazardsListPage` / `WarningDetailPage` / `NotificationDrawer` 中重复且等价的块 |

## 三、有意保留的覆盖层例外（已评审）

以下 2 个文件存在"同选择器后置覆盖"的设计层，合并会改变层叠顺序；
在文件内以 `/* stylelint-disable no-duplicate-selectors */` + 说明注释显式豁免，不全局关闭规则：

| 文件 | 原因 |
|---|---|
| `src/styles.css` | 末尾「SKELETON IOS-IFICATION」覆盖层：不改上方原规则，只在末尾以同优先级后置覆盖 30+ 页面骨架 |
| `src/views/WelcomePage.vue` | 末尾「视觉升级」覆盖层：对按钮/预览框/KPI 做后置精修 |

> 如需彻底消除例外，应把覆盖层拆到独立文件并用 `@layer` 管理优先级，属独立重构，不在本次交付范围。

## 四、CI 门禁

`.github/workflows/frontend-ci.yml`：
- `Stylelint (blocking)`：`npx stylelint "src/**/*.{css,scss,vue}"` **失败即阻断**；
- `Upload stylelint report`：artifact `stylelint-report` 仍保留，便于失败时下载定位。

## 五、回归证据

- `npx eslint src/` **0 error**；
- `npx vitest run` **69 passed / 7 文件**；
- `npm run build` 成功；SFC 完整性 **98/98**；
- 真实浏览器冒烟 **37/37 PASS**（截图 `output/browser-verify/`）；
- `npx stylelint "src/**/*.{css,scss,vue}"` → **0 error**。
