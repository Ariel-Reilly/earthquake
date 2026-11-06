# DisasterGuard 设计令牌快速参考

> **一页纸速查**：复制即用的 CSS 变量与常用模式

---

## 🎨 颜色速查

### 品牌色
```css
var(--color-primary)          /* #5F7A6B 莫兰迪绿 */
var(--color-primary-hover)    /* #4A6154 悬停态 */
var(--color-primary-active)   /* #385F49 按下态 */
var(--color-on-primary)       /* #FFFFFF 主色上的文字 */
```

### 文字色
```css
var(--color-foreground)         /* #2C3B34 主文字（对比度 11.2:1）*/
var(--color-muted-foreground)   /* #5F6F68 次级文字（对比度 4.92:1）*/
var(--color-tertiary)           /* #8FA49A 禁用/三级文字 */
```

### 背景色
```css
var(--color-background)   /* #F4F7F5 页面背景 */
var(--color-card)         /* #FFFFFF 卡片背景 */
var(--color-muted)        /* #EEF3F0 静音背景 */
```

### 语义色
```css
var(--color-destructive)        /* #C48B8B 危险/高危预警 */
var(--color-destructive-bg)     /* rgba(..., 0.12) 浅底 */
var(--color-warning)            /* #D4B48B 警告/中危 */
var(--color-success)            /* #6F9E7F 成功 */
var(--color-accent)             /* #3E6F94 强调/链接蓝 */
```

---

## 📏 间距速查（8px 基准）

```css
var(--space-xs)    /*  4px  紧密间隙 */
var(--space-sm)    /*  8px  图标/行内间距 */
var(--space-md)    /* 16px  标准内边距 */
var(--space-lg)    /* 24px  区块内边距 */
var(--space-xl)    /* 32px  大间隙 */
var(--space-2xl)   /* 48px  区块外边距 */
var(--space-3xl)   /* 64px  大区块间距 */
```

**记忆技巧**: xs/sm/md/lg/xl/2xl/3xl 对应 4/8/16/24/32/48/64

---

## 🔘 圆角速查

```css
var(--radius-xs)     /*   4px  细节元素 */
var(--radius-sm)     /*   6px  徽章、标签 */
var(--radius-md)     /*   8px  按钮、输入框 */
var(--radius-lg)     /*  12px  卡片 */
var(--radius-xl)     /*  16px  大卡片、模态框 */
var(--radius-2xl)    /*  24px  特大容器 */
var(--radius-full)   /* 9999px 圆形/胶囊 */
```

---

## 💫 阴影速查

```css
var(--shadow-xs)      /* 微提升（1px 偏移）*/
var(--shadow-sm)      /* 细微边界 */
var(--shadow-md)      /* 卡片默认 */
var(--shadow-lg)      /* 模态框/下拉菜单 */
var(--shadow-xl)      /* 英雄图/大卡片 */
var(--shadow-hover)   /* 悬停提升效果 */
var(--shadow-focus)   /* 焦点环（可访问性必需）*/
```

---

## 🔤 字体速查

### 字号
```css
var(--text-xs)      /* 11px  辅助说明 */
var(--text-sm)      /* 12px  次要文字 */
var(--text-base)    /* 14px  主文字 */
var(--text-lg)      /* 16px  强调文字（移动端输入框推荐）*/
var(--text-xl)      /* 18px  小标题 */
var(--text-2xl)     /* 20px  标题 */
var(--text-3xl)     /* 24px  大标题 */
var(--text-4xl)     /* 30px  页面标题 */
var(--text-5xl)     /* 36px  英雄标题 */
```

### 字重
```css
var(--font-normal)      /* 400 常规 */
var(--font-medium)      /* 500 中等 */
var(--font-semibold)    /* 600 半粗 */
var(--font-bold)        /* 700 粗体 */
var(--font-extrabold)   /* 800 特粗 */
```

---

## ⚡ 动画速查

### 持续时间
```css
var(--duration-fast)    /* 150ms  快速反馈 */
var(--duration-base)    /* 200ms  标准过渡 */
var(--duration-slow)    /* 300ms  慢动画 */
```

### 缓动曲线
```css
var(--ease-in)          /* 减速进入 */
var(--ease-out)         /* 加速离开（推荐）*/
var(--ease-in-out)      /* 两端平滑 */
var(--ease-spring)      /* 弹性曲线（iOS 风格）*/
```

---

## 🧩 常用模式

### 按钮
```css
.btn {
  padding: 12px 24px;
  border-radius: var(--radius-md);
  font-weight: var(--font-semibold);
  font-size: var(--text-base);
  cursor: pointer;
  transition: all var(--duration-base) var(--ease-out);
}

.btn:focus-visible {
  outline: none;
  box-shadow: var(--shadow-focus);
}
```

### 卡片
```css
.card {
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--space-lg);
  box-shadow: var(--shadow-md);
  transition: all var(--duration-base) var(--ease-out);
}

.card:hover {
  box-shadow: var(--shadow-hover);
  transform: translateY(-2px);
}
```

### 输入框
```css
.input {
  padding: 12px 16px;
  font-size: var(--text-lg);  /* 16px 防止 iOS 缩放 */
  border: 1.5px solid var(--color-border);
  border-radius: var(--radius-md);
  transition: all var(--duration-base) var(--ease-out);
}

.input:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: var(--shadow-focus);
}
```

### 徽章
```css
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
```

---

## 🔄 旧变量映射表

| 旧变量 | 新变量 | 说明 |
|-------|--------|------|
| `--c-brand` | `--color-primary` | 主色 |
| `--c-text` | `--color-foreground` | 主文字 |
| `--c-text-2` | `--color-muted-foreground` | 次级文字 |
| `--c-bg` | `--color-background` | 页面背景 |
| `--c-surface` | `--color-card` | 卡片背景 |
| `--c-border` | `--color-border` | 边框 |
| `--c-danger` | `--color-destructive` | 危险色 |
| `--r-md` | `--radius-md` | 中等圆角 |
| `--sp-16` | `--space-md` | 16px 间距 |
| `--sp-24` | `--space-lg` | 24px 间距 |

**注意**: 旧变量仍可用（已映射），但推荐逐步迁移到新变量。

---

## ✅ 可访问性检查

```css
/* ✅ 对比度达标 */
color: var(--color-foreground);          /* 11.2:1 */
color: var(--color-muted-foreground);    /* 4.92:1 */

/* ✅ 焦点环 */
.interactive:focus-visible {
  outline: none;
  box-shadow: var(--shadow-focus);
}

/* ✅ 触摸目标（移动端）*/
.button {
  min-width: 44px;
  min-height: 44px;
}

/* ✅ Reduced Motion */
@media (prefers-reduced-motion: reduce) {
  * {
    animation-duration: 0.01ms !important;
    transition-duration: 0.01ms !important;
  }
}
```

---

## 🚀 快速开始

### 1. 引入设计令牌
```js
// main.js
import './design-system/disasterguard/tokens.css'
```

### 2. 使用 CSS 变量
```vue
<style scoped>
.my-component {
  background: var(--color-card);
  padding: var(--space-lg);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);
}
</style>
```

### 3. 验证可访问性
```bash
npm run check:a11y
```

---

生成时间: 2026-10-27  
完整文档: `design-system/disasterguard/GUIDE.md`
