# DisasterGuard 设计系统应用指南

## 📋 概述

本设计系统整合了 **UI/UX Pro Max** 的 B2B 最佳实践与 **莫兰迪绿品牌色板**，提供统一的视觉语言和组件规范。

**生成时间**: 2026-10-27  
**核心原则**: 可访问性优先 | 品牌识别性 | B 端信息密度

---

## 🎨 核心设计原则

### 1. **品牌色 - 莫兰迪绿**
保留现有的 `#5F7A6B` 莫兰迪绿作为主色，传达**安全、镇静、专业**的品牌气质。

```css
/* ✅ 推荐用法 */
.primary-button {
  background: var(--color-primary);      /* #5F7A6B */
  color: var(--color-on-primary);        /* #FFFFFF */
}

.primary-button:hover {
  background: var(--color-primary-hover); /* #4A6154 */
}

/* ❌ 避免 */
.bad-button {
  background: #5F7A6B;  /* 硬编码颜色，无法统一管理 */
}
```

### 2. **可访问性 WCAG AA**
所有文字对比度 ≥ 4.5:1，焦点状态清晰可见。

```css
/* ✅ 主文字对比度 11.2:1 */
.main-text {
  color: var(--color-foreground);  /* #2C3B34 on #F4F7F5 */
}

/* ✅ 次级文字对比度 4.92:1 */
.muted-text {
  color: var(--color-muted-foreground);  /* #5F6F68 */
}

/* ✅ 焦点环（键盘导航必需）*/
.interactive:focus-visible {
  outline: none;
  box-shadow: var(--shadow-focus);  /* 3px 绿色环 */
}
```

### 3. **间距系统（8px 基准）**
使用 8 的倍数确保视觉节奏一致。

```css
.card {
  padding: var(--space-lg);  /* 24px */
  gap: var(--space-md);      /* 16px */
}

.tight-layout {
  gap: var(--space-sm);      /* 8px */
}
```

---

## 📐 快速上手

### 第一步：引入 Token 文件

在 `src/main.js` 或 `src/App.vue` 中引入：

```js
// main.js
import './design-system/disasterguard/tokens.css'
```

或在 `vite.config.js` 中配置全局引入：

```js
export default {
  css: {
    preprocessorOptions: {
      css: {
        additionalData: `@import "./design-system/disasterguard/tokens.css";`
      }
    }
  }
}
```

### 第二步：迁移现有样式

**不需要删除** `src/styles.css`，设计令牌会自动覆盖旧变量。逐步迁移现有组件：

#### 迁移对照表

| 旧变量 | 新变量 | 说明 |
|-------|--------|------|
| `--c-brand` | `--color-primary` | 主色 |
| `--c-text` | `--color-foreground` | 主文字 |
| `--c-text-2` | `--color-muted-foreground` | 次级文字 |
| `--c-bg` | `--color-background` | 页面背景 |
| `--c-surface` | `--color-card` | 卡片背景 |
| `--c-border` | `--color-border` | 边框 |
| `--r-md` | `--radius-md` | 中等圆角 |
| `--sp-16` | `--space-md` | 16px 间距 |

**兼容性保证**: 旧变量已通过 `var()` 映射到新变量，迁移期间两套变量可共存。

---

## 🧩 组件示例

### 按钮系统

```vue
<template>
  <!-- 主要按钮 -->
  <button class="btn-primary">
    确认预警
  </button>

  <!-- 次要按钮 -->
  <button class="btn-secondary">
    取消
  </button>

  <!-- 危险按钮 -->
  <button class="btn-destructive">
    删除
  </button>
</template>

<style scoped>
.btn-primary {
  background: var(--color-primary);
  color: var(--color-on-primary);
  padding: 12px 24px;
  border-radius: var(--radius-md);
  font-weight: var(--font-semibold);
  font-size: var(--text-base);
  border: none;
  cursor: pointer;
  transition: all var(--duration-base) var(--ease-out);
}

.btn-primary:hover {
  background: var(--color-primary-hover);
  box-shadow: var(--shadow-hover);
  transform: translateY(-1px);
}

.btn-primary:active {
  background: var(--color-primary-active);
  transform: translateY(0);
}

.btn-primary:focus-visible {
  outline: none;
  box-shadow: var(--shadow-focus);
}

.btn-secondary {
  background: transparent;
  color: var(--color-primary);
  border: 2px solid var(--color-primary);
  padding: 10px 22px;  /* 减 2px 补偿边框 */
  border-radius: var(--radius-md);
  font-weight: var(--font-semibold);
  cursor: pointer;
  transition: all var(--duration-base) var(--ease-out);
}

.btn-secondary:hover {
  background: var(--color-primary-soft);
}

.btn-destructive {
  background: var(--color-destructive-strong);
  color: var(--color-on-destructive);
  padding: 12px 24px;
  border-radius: var(--radius-md);
  font-weight: var(--font-semibold);
  border: none;
  cursor: pointer;
  transition: all var(--duration-base) var(--ease-out);
}
</style>
```

### 卡片组件

```vue
<template>
  <div class="warning-card">
    <div class="card-header">
      <h3 class="card-title">暴雨预警</h3>
      <span class="badge badge-high">高危</span>
    </div>
    <p class="card-body">
      预计未来 3 小时内降雨量达 50mm/h
    </p>
    <div class="card-footer">
      <button class="btn-sm">查看详情</button>
    </div>
  </div>
</template>

<style scoped>
.warning-card {
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--space-lg);
  box-shadow: var(--shadow-card);
  transition: all var(--duration-base) var(--ease-out);
}

.warning-card:hover {
  box-shadow: var(--shadow-hover);
  transform: translateY(-2px);
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: var(--space-md);
}

.card-title {
  font-size: var(--text-xl);
  font-weight: var(--font-semibold);
  color: var(--color-foreground);
  margin: 0;
}

.badge {
  padding: 4px 12px;
  border-radius: var(--radius-full);
  font-size: var(--text-sm);
  font-weight: var(--font-medium);
}

.badge-high {
  background: var(--color-destructive-bg);
  color: var(--color-on-destructive);
}

.card-body {
  font-size: var(--text-base);
  color: var(--color-muted-foreground);
  line-height: 1.6;
  margin-bottom: var(--space-md);
}

.card-footer {
  display: flex;
  justify-content: flex-end;
}

.btn-sm {
  padding: 8px 16px;
  font-size: var(--text-sm);
  background: var(--color-primary);
  color: var(--color-on-primary);
  border: none;
  border-radius: var(--radius-md);
  cursor: pointer;
  transition: all var(--duration-fast) var(--ease-out);
}
</style>
```

### 表单输入

```vue
<template>
  <div class="form-field">
    <label for="location" class="form-label">
      预警地点 <span class="required">*</span>
    </label>
    <input
      id="location"
      type="text"
      class="form-input"
      placeholder="请输入地点名称"
    />
    <p class="form-hint">示例：XX市XX区XX街道</p>
  </div>
</template>

<style scoped>
.form-field {
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
}

.form-label {
  font-size: var(--text-base);
  font-weight: var(--font-medium);
  color: var(--color-foreground);
}

.required {
  color: var(--color-destructive-strong);
}

.form-input {
  padding: 12px 16px;
  font-size: var(--text-lg);  /* 16px 防止 iOS 自动缩放 */
  border: 1.5px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-card);
  color: var(--color-foreground);
  transition: all var(--duration-base) var(--ease-out);
}

.form-input::placeholder {
  color: var(--color-tertiary);
}

.form-input:hover {
  border-color: var(--color-border-hover);
}

.form-input:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: var(--shadow-focus);
}

.form-hint {
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
}
</style>
```

---

## 🎯 应用到现有页面

### 示例：迁移 `WarningsPage.vue`

**Before** (旧样式):
```css
.warning-item {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-md);
  padding: var(--sp-16);
}
```

**After** (新样式):
```css
.warning-item {
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  padding: var(--space-md);
  box-shadow: var(--shadow-card);  /* 新增：卡片阴影 */
  transition: all var(--duration-base) var(--ease-out);  /* 新增：过渡动画 */
}

.warning-item:hover {
  box-shadow: var(--shadow-hover);  /* 新增：悬停效果 */
  transform: translateY(-1px);      /* 新增：微提升 */
}
```

### 全局搜索替换（批量迁移）

在 VS Code 中使用正则替换：

1. **替换主色**  
   查找: `--c-brand(?!-)` → 替换: `--color-primary`

2. **替换文字色**  
   查找: `--c-text(?!-)` → 替换: `--color-foreground`

3. **替换间距**  
   查找: `--sp-(\d+)` → 替换: `--space-{映射值}`  
   （需手动映射：16→md, 24→lg, 32→xl）

---

## 🚀 性能优化建议

### 1. 使用 CSS 自定义属性（已实现）
```css
/* ✅ 运行时可主题化 */
.card {
  background: var(--color-card);
}

/* ❌ 编译时硬编码 */
.card {
  background: #FFFFFF;
}
```

### 2. 避免过度动画
```css
/* ✅ 仅在必要时动画 */
.card:hover {
  transform: translateY(-2px);  /* GPU 加速 */
}

/* ❌ 动画 width/height */
.bad-card:hover {
  width: 110%;  /* 触发重排 */
}
```

### 3. Reduced Motion 支持（已内置）
设计系统自动禁用动画当用户开启"减弱动态效果"：

```css
@media (prefers-reduced-motion: reduce) {
  * {
    animation-duration: 0.01ms !important;
    transition-duration: 0.01ms !important;
  }
}
```

---

## ✅ 可访问性检查清单

将以下规则应用到所有新组件：

- [ ] **对比度**: 文字对比度 ≥ 4.5:1（使用 `--color-foreground` / `--color-muted-foreground`）
- [ ] **焦点状态**: 所有可交互元素有 `:focus-visible` 样式（使用 `--shadow-focus`）
- [ ] **触摸目标**: 按钮最小 44×44px（移动端）
- [ ] **键盘导航**: 支持 Tab / Shift+Tab / Enter / Space
- [ ] **ARIA 标签**: 图标按钮补充 `aria-label`
- [ ] **Reduced Motion**: 尊重用户动态效果偏好（已自动处理）

---

## 📚 进阶：深色模式准备

当前版本仅支持浅色模式。若需深色模式，可在 `tokens.css` 中添加：

```css
@media (prefers-color-scheme: dark) {
  :root {
    --color-background: #1A2520;
    --color-foreground: #E8EEE9;
    --color-card: #243229;
    --color-border: #3A4940;
    /* ... 其他深色变量 */
  }
}
```

---

## 🔗 参考资源

- **设计系统主文档**: `design-system/disasterguard/MASTER.md`
- **CSS 令牌文件**: `design-system/disasterguard/tokens.css`
- **UI/UX Pro Max Skill**: 查询组件最佳实践
- **Plus Jakarta Sans 字体**: https://fonts.google.com/specimen/Plus+Jakarta+Sans

---

## 📝 维护说明

### 何时更新设计令牌

1. **新增品牌色**: 在 `tokens.css` 的 `1. 品牌色板` 区块添加
2. **调整间距**: 修改 `4. 间距系统` 的变量值
3. **新增组件变量**: 先在 `tokens.css` 定义全局变量，再在组件中引用

### 兼容性管理

- **不要删除旧变量**: 保留 `--c-*` / `--sp-*` 等旧变量映射，直到全部页面迁移完成
- **渐进式迁移**: 每次重构 1-2 个页面，而非一次性替换所有文件
- **验证对比度**: 修改颜色后使用 `npm run check:a11y` 验证对比度

---

生成时间: 2026-10-27  
维护者: DisasterGuard Frontend Team
