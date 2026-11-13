# Stylelint 存量基线与治理计划（B4）

> 记录时间：2026-11-13 · 命令：`cd frontend; npx stylelint "src/**/*.{css,scss,vue}" --formatter compact`
> 当前策略：**CI 中作为 advisory 报告（不阻断）+ 上传 artifact**；分批修复后再切为阻断门禁。

## 一、基线与自动修复结果（2026-11-13 更新）

| 阶段 | 数量 | 说明 |
|---|---|---|
| 修复前 | **5,312** | 新增卫星对比页后重测（原 5,264） |
| `stylelint --fix` 第一批 | **2,440** | 修复 2,872 条；涉及 **86 个文件**（仅格式/颜色写法） |
| 剩余 | 2,440 | 均为不可自动修复项，需人工/格式化器处理 |

剩余规则分布：

| 规则 | 数量 | 说明 | 处理方式 |
|---|---|---|---|
| `declaration-block-single-line-max-declarations` | 2,320 | 单行写多个声明 | 人工格式化/Prettier 分批 |
| `no-duplicate-selectors` | 72 | 重复选择器 | 人工合并/删除 |
| `declaration-block-no-duplicate-properties` | 38 | 重复属性 | 人工确认保留值 |
| `selector-type-no-unknown` | 2 | 未知选择器类型 | 人工核对 |
| `declaration-property-value-keyword-no-deprecated` | 2 | 废弃关键字 | 人工替换 |
| `declaration-property-value-no-unknown` | 1 | 未知属性值 | 人工核对 |

> 已修复的规则：`rule-empty-line-before`、`alpha-value-notation`、`color-function-notation`、
> `at-rule-empty-line-before`、`media-feature-range-notation`、`comment-empty-line-before`、
> `color-hex-length`、`property-no-vendor-prefix` 等（详见 git diff）。
>
> **回归证据**：eslint 0 / vitest **69 passed** / `vite build` 成功 / SFC **98/98** /
> 真实浏览器 **37/37 PASS**（截图 `output/browser-verify/`，橙色弹窗等颜色抽查无变化）。

## 二、分批治理计划（更新）

1. ~~第一批（低风险、自动修复）~~ ✅ 已完成：5,312 → 2,440，86 文件，回归全绿。
2. **第二批（单行多声明 2,320 条）**：按文件分批格式化（组件样式块），每批跑 build/视觉抽查；
   可评估引入 Prettier 的 CSS/SFC 格式化（需单独分支）。
3. **第三批（重复选择器/重复属性/未知值 115 条）**：逐个确认语义后修复。
4. 清零后把 CI 的 `continue-on-error` 去掉，改为阻断；并在 `frontend/package.json` 加 `lint:css` 门禁说明。

## 三、当前 CI 行为

`.github/workflows/frontend-ci.yml` 新增：
- `Stylelint (advisory baseline)`：执行失败不阻断，输出报告；
- `Upload stylelint report`：artifact `stylelint-report` 可供下载统计。

## 四、风险与注意

- `stylelint --fix` 会大范围改样式文件，必须单独分支、逐页视觉回归（深色模式/移动端）；
- 不要与业务功能改动混在同一提交；
- 修复期间保持 `npm run build` 与 37 项浏览器冒烟通过。
