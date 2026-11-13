# Stylelint 存量基线与治理计划（B4）

> 记录时间：2026-11-13 · 命令：`cd frontend; npx stylelint "src/**/*.{css,scss,vue}" --formatter compact`
> 当前策略：**CI 中作为 advisory 报告（不阻断）+ 上传 artifact**；分批修复后再切为阻断门禁。

## 一、基线

- 总报错：**5,264 条**（全量 src/，含 Vue SFC `<style>`）
- 主要规则分布：

| 规则 | 数量 | 说明 | 自动修复 |
|---|---|---|---|
| `declaration-block-single-line-max-declarations` | 2,273 | 单行写了多个声明 | 否（需格式化） |
| `rule-empty-line-before` | 1,139 | 规则前缺少空行 | 是 |
| `alpha-value-notation` | 680 | `0.6` → `60%` | 是 |
| `color-function-notation` | 680 | `rgb()` → `rgb()` 现代写法 | 是 |
| `at-rule-empty-line-before` | 102 | @ 规则前空行 | 是 |
| `media-feature-range-notation` | 98 | `max-width` → 范围写法 | 是 |
| `comment-empty-line-before` | 77 | 注释前空行 | 是 |
| `no-duplicate-selectors` | 72 | 重复选择器 | 否 |
| `property-no-vendor-prefix` | 39 | 冗余前缀 | 否 |
| `color-hex-length` | 28 | `#ffffff` → `#fff` | 是 |
| 其他（value-keyword-case / custom-property-empty-line 等） | ~200 | 格式类 | 部分 |

## 二、分批治理计划（建议）

1. **第一批（低风险、自动修复为主）**：
   ```powershell
   cd frontend
   npx stylelint "src/**/*.{css,scss,vue}" --fix
   git diff --stat   # 人工 spot check + npm run build + vitest
   ```
   预计消除 2,500+ 条（空行/颜色写法/十六进制等）。
2. **第二批（单行多声明 2,273 条）**：按文件分批手工格式化（组件样式块），每批跑 build/视觉抽查。
3. **第三批（重复选择器/前缀等语义项 111 条）**：逐个确认是否合并/删除。
4. 清零后把 CI 的 `continue-on-error` 去掉，改为阻断；并在 `frontend/package.json` 加 `lint:css` 门禁说明。

## 三、当前 CI 行为

`.github/workflows/frontend-ci.yml` 新增：
- `Stylelint (advisory baseline)`：执行失败不阻断，输出报告；
- `Upload stylelint report`：artifact `stylelint-report` 可供下载统计。

## 四、风险与注意

- `stylelint --fix` 会大范围改样式文件，必须单独分支、逐页视觉回归（深色模式/移动端）；
- 不要与业务功能改动混在同一提交；
- 修复期间保持 `npm run build` 与 37 项浏览器冒烟通过。
