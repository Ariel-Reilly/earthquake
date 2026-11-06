# DisasterGuard 设计系统

> **B 端灾害预警管理系统的完整视觉规范**  
> 基于 UI/UX Pro Max 最佳实践 + 莫兰迪绿品牌识别

---

## 📦 包含内容

| 文件 | 说明 | 适用场景 |
|------|------|----------|
| **[tokens.css](tokens.css)** | CSS 变量定义文件（核心）| 直接引入到项目中 |
| **[GUIDE.md](GUIDE.md)** | 完整应用指南（11KB）| 系统学习、团队培训 |
| **[QUICK-REFERENCE.md](QUICK-REFERENCE.md)** | 快速参考卡片（7KB）| 开发时速查 |
| **[MIGRATION-EXAMPLE.md](MIGRATION-EXAMPLE.md)** | 迁移实战示例（10KB）| 重构现有组件 |
| **[MASTER.md](MASTER.md)** | UI/UX Pro Max 生成的设计决策文档 | 设计评审、技术选型 |

---

## 🚀 快速开始（3 步）

### 1️⃣ 引入设计令牌

在 `src/main.js` 中添加：

```js
import './design-system/disasterguard/tokens.css'
```

### 2️⃣ 使用 CSS 变量

在任意 `.vue` 组件中：

```vue
<style scoped>
.my-card {
  background: var(--color-card);
  padding: var(--space-lg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);
}
</style>
```

### 3️⃣ 验证可访问性

```bash
npm run check:a11y
```

---

## 🎨 设计原则

### 品牌色 - 莫兰迪绿
- **主色**: `#5F7A6B` - 传达安全、镇静、专业
- **应用**: 主按钮、导航栏、品牌强调
- **对比度**: 白字对比度 4.68:1（WCAG AA 级）

### 可访问性优先
- ✅ 所有文字对比度 ≥ 4.5:1
- ✅ 焦点环清晰可见（键盘导航）
- ✅ 触摸目标 ≥ 44×44px（移动端）
- ✅ 支持 `prefers-reduced-motion`

### 间距系统（8px 基准）
```
4px → 8px → 16px → 24px → 32px → 48px → 64px
 xs     sm     md     lg     xl    2xl    3xl
```

### 圆角系统
```
4px → 6px → 8px → 12px → 16px → 24px → 9999px
 xs     sm    md     lg     xl    2xl    full
```

---

## 📚 文档导航

### 👉 新手入门
1. 阅读 [GUIDE.md](GUIDE.md) 的"快速上手"章节
2. 查看 [QUICK-REFERENCE.md](QUICK-REFERENCE.md) 了解常用变量
3. 参考 [MIGRATION-EXAMPLE.md](MIGRATION-EXAMPLE.md) 学习实战技巧

### 👉 日常开发
- **速查变量**: [QUICK-REFERENCE.md](QUICK-REFERENCE.md)
- **复制模式**: [GUIDE.md § 组件示例](GUIDE.md#🧩-组件示例)
- **迁移旧代码**: [MIGRATION-EXAMPLE.md](MIGRATION-EXAMPLE.md)

### 👉 设计评审
- **决策依据**: [MASTER.md](MASTER.md)
- **颜色规范**: [tokens.css § 1. 品牌色板](tokens.css)
- **可访问性**: [GUIDE.md § 可访问性检查清单](GUIDE.md#✅-可访问性检查清单)

---

## 🎯 核心 CSS 变量速查

### 颜色
```css
--color-primary           /* #5F7A6B 莫兰迪绿 */
--color-foreground        /* #2C3B34 主文字 */
--color-background        /* #F4F7F5 页面背景 */
--color-card              /* #FFFFFF 卡片背景 */
--color-border            /* #D1DCD5 边框 */
--color-destructive       /* #C48B8B 危险/高危 */
--color-accent            /* #3E6F94 强调蓝 */
```

### 间距
```css
--space-xs / sm / md / lg / xl / 2xl / 3xl
/* 4px / 8px / 16px / 24px / 32px / 48px / 64px */
```

### 圆角
```css
--radius-xs / sm / md / lg / xl / 2xl / full
/* 4px / 6px / 8px / 12px / 16px / 24px / 9999px */
```

### 阴影
```css
--shadow-xs / sm / md / lg / xl / hover / focus
```

### 字体
```css
--text-xs / sm / base / lg / xl / 2xl / 3xl / 4xl / 5xl
/* 11px / 12px / 14px / 16px / 18px / 20px / 24px / 30px / 36px */

--font-sans               /* Plus Jakarta Sans + 系统回退 */
--font-mono               /* 等宽字体栈 */
```

---

## 🔄 兼容性说明

### 旧变量映射
设计令牌保留了所有旧变量的映射，现有代码**无需立即修改**：

```css
--c-brand      → --color-primary
--c-text       → --color-foreground
--c-bg         → --color-background
--sp-16        → --space-md
--r-md         → --radius-md
```

### 渐进式迁移
推荐每次重构 1-2 个组件，而非一次性替换所有文件。迁移完成后可删除旧变量映射（在 `tokens.css` 底部）。

---

## 📋 检查清单

应用设计系统时，确保：

- [ ] 引入 `tokens.css` 到项目入口
- [ ] 所有新组件使用 `--color-*` / `--space-*` / `--radius-*` 变量
- [ ] 所有交互元素有 `:focus-visible` 样式
- [ ] 所有悬停效果添加 `transition`
- [ ] 移动端输入框字号 ≥ 16px
- [ ] 运行 `npm run check:a11y` 验证对比度
- [ ] 添加 `@media (prefers-reduced-motion: reduce)` 支持

---

## 🛠️ 维护

### 新增颜色
在 `tokens.css` 的对应区块添加（带注释说明用途与对比度）

### 新增组件模式
在 `GUIDE.md` 的"组件示例"章节补充代码片段

### 修改现有变量
**谨慎操作**：修改现有变量会影响所有使用处，需全局测试。优先通过新增变量扩展系统。

---

## 📞 支持

- **技术问题**: 提 Issue 到项目仓库
- **设计建议**: 联系 Frontend Team
- **可访问性咨询**: 运行 `npm run check:a11y` 生成报告

---

**生成时间**: 2026-10-27  
**版本**: v1.0  
**基于**: UI/UX Pro Max + 莫兰迪绿品牌色板
