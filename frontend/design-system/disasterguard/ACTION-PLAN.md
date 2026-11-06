# 🎯 DisasterGuard 设计系统应用行动清单

> **立即可执行的步骤，3 个阶段完成设计系统接入**

---

## 阶段 1️⃣: 引入设计令牌（10 分钟）

### Step 1.1: 在 main.js 引入 tokens.css

打开 `src/main.js`，在顶部添加：

```js
import './design-system/disasterguard/tokens.css'
```

**完整示例**：
```js
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'

// ✅ 引入设计系统（新增）
import './design-system/disasterguard/tokens.css'

// ⚠️ 保留原 styles.css（包含全局样式类）
import './styles.css'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.mount('#app')
```

### Step 1.2: 验证引入成功

在浏览器开发者工具 Console 中执行：

```js
getComputedStyle(document.documentElement).getPropertyValue('--color-primary')
// 应返回: "#5F7A6B" 或 " #5F7A6B"（带空格）
```

如果返回空字符串，检查 `main.js` 中的 import 路径。

---

## 阶段 2️⃣: 迁移第一个组件（30 分钟）

### Step 2.1: 选择一个简单组件

推荐从以下组件开始（改动小、风险低）：

- ✅ **按钮组件**（如 `WarningDetailPage.vue` 中的确认/派发/关闭按钮）
- ✅ **卡片组件**（如 `WarningsPage.vue` 中的预警列表项）
- ✅ **输入框组件**（如创建预警弹窗中的表单字段）

**避免先迁移**：
- ❌ 复杂布局组件（如 `MainLayout.vue`）
- ❌ 地图组件（如 `MapView.vue`）
- ❌ 图表组件（如 `StatisticsPage.vue`）

### Step 2.2: 应用设计令牌

**Before**（假设原样式）：
```vue
<style scoped>
.confirm-button {
  background: var(--c-brand);
  color: var(--c-brand-on);
  padding: 10px 20px;
  border-radius: var(--r-md);
}
</style>
```

**After**（新设计系统）：
```vue
<style scoped>
.confirm-button {
  background: var(--color-primary);
  color: var(--color-on-primary);
  padding: 12px 24px;                          /* 调整为标准间距 */
  border-radius: var(--radius-md);
  font-weight: var(--font-semibold);           /* ✨ 新增 */
  box-shadow: var(--shadow-sm);                /* ✨ 新增 */
  transition: all var(--duration-base) var(--ease-out); /* ✨ 新增 */
  cursor: pointer;
}

.confirm-button:hover {
  background: var(--color-primary-hover);      /* ✨ 新增 */
  box-shadow: var(--shadow-hover);             /* ✨ 新增 */
  transform: translateY(-1px);                 /* ✨ 新增 */
}

.confirm-button:focus-visible {
  outline: none;
  box-shadow: var(--shadow-focus);             /* ✨ 可访问性必需 */
}
</style>
```

### Step 2.3: 验证效果

1. **视觉检查**：按钮悬停时有提升动画 + 阴影变化
2. **键盘导航**：按 Tab 键聚焦按钮时，出现绿色焦点环
3. **对比度验证**：运行 `npm run check:a11y`（如果有配置）

---

## 阶段 3️⃣: 建立团队规范（持续）

### Step 3.1: 团队培训

组织 15 分钟分享会，演示：

1. **为什么要用设计系统**：统一视觉、提升可访问性、加速开发
2. **如何查变量**：打开 `QUICK-REFERENCE.md` 速查
3. **如何迁移组件**：参考 `MIGRATION-EXAMPLE.md`

### Step 3.2: Code Review 检查项

在 PR 评审时，确认：

- [ ] 新组件使用 `--color-*` / `--space-*` / `--radius-*` 变量（禁止硬编码）
- [ ] 所有按钮/链接有 `:focus-visible` 样式
- [ ] 所有悬停效果有 `transition`
- [ ] 移动端输入框字号 ≥ 16px
- [ ] 没有使用 emoji 作为图标（使用 lucide-vue-next）

### Step 3.3: 渐进式迁移计划

**Week 1-2**：
- 迁移所有按钮组件（`TopToolbar.vue`、`WarningDetailPage.vue`、弹窗按钮）
- 迁移所有输入框（创建预警表单、搜索框）

**Week 3-4**：
- 迁移卡片组件（预警列表、统计卡片）
- 迁移导航组件（侧边栏、顶栏）

**Week 5-6**：
- 迁移复杂页面（`WarningsPage.vue`、`StatisticsPage.vue`）
- 清理 `styles.css` 中的冗余旧变量

**Week 7+**：
- 删除 `tokens.css` 底部的旧变量兼容映射
- 全局搜索残留的 `--c-*` / `--sp-*` 旧变量并替换

---

## 🛠️ 常见问题

### Q1: 引入 tokens.css 后样式乱了怎么办？

**原因**: 新变量与旧变量命名冲突，或旧样式依赖未定义的变量。

**解决**:
1. 检查 `styles.css` 中是否有与 `tokens.css` 冲突的 `:root` 定义
2. 确保 `tokens.css` 在 `styles.css` **之前**引入（让旧样式覆盖）
3. 临时在 `styles.css` 顶部添加 `@import './design-system/disasterguard/tokens.css';`

### Q2: 旧页面还能用吗？

**答**: ✅ **完全兼容**。`tokens.css` 保留了所有旧变量的映射：

```css
--c-brand     → var(--color-primary)
--c-text      → var(--color-foreground)
--sp-16       → var(--space-md)
```

旧代码无需修改，继续正常运行。

### Q3: 什么时候必须用新变量？

**答**: 只有**新写的组件**强制使用新变量。旧代码可渐进式迁移。

### Q4: 如何快速查变量名？

**答**: 打开 `QUICK-REFERENCE.md`，按 Ctrl+F 搜索关键词（如"间距"、"圆角"、"阴影"）。

### Q5: Reduced Motion 是什么？

**答**: 用户在系统设置中开启"减弱动态效果"时，浏览器会禁用非必需动画。`tokens.css` 已自动处理，无需额外代码。

---

## ✅ 完成标志

当你的项目满足以下条件时，设计系统接入完成：

- [x] `tokens.css` 已引入到 `main.js`
- [x] 至少 3 个组件使用新变量
- [x] 所有新组件有焦点环（`:focus-visible`）
- [x] 团队成员知道如何查阅 `QUICK-REFERENCE.md`
- [x] Code Review 包含设计系统检查项

---

## 📚 资源链接

| 文档 | 用途 | 链接 |
|------|------|------|
| **快速参考** | 日常开发速查 | [QUICK-REFERENCE.md](QUICK-REFERENCE.md) |
| **应用指南** | 系统学习 | [GUIDE.md](GUIDE.md) |
| **迁移示例** | 实战参考 | [MIGRATION-EXAMPLE.md](MIGRATION-EXAMPLE.md) |
| **设计决策** | 技术选型依据 | [MASTER.md](MASTER.md) |
| **CSS 令牌** | 源文件 | [tokens.css](tokens.css) |

---

## 🎯 下一步行动（按优先级）

1. ✅ **立即执行**: 在 `main.js` 引入 `tokens.css`（5 分钟）
2. ✅ **本周完成**: 迁移 `WarningDetailPage.vue` 的三个按钮（30 分钟）
3. ✅ **本月目标**: 迁移所有按钮、输入框、卡片组件
4. 📅 **长期计划**: 2 个月内完成全部页面迁移

---

**生成时间**: 2026-10-27  
**维护者**: DisasterGuard Frontend Team  
**问题反馈**: 提 Issue 到项目仓库
