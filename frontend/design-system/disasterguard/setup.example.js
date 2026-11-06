/**
 * DisasterGuard 设计系统一键引入配置
 * 
 * 使用方式：
 * 1. 在 vite.config.js 中引入此文件
 * 2. 或在 src/main.js 直接 import './design-system/disasterguard/tokens.css'
 */

// ═══════════════════════════════════════════════════════════
// 方案 1: Vite 全局引入（推荐）
// ═══════════════════════════════════════════════════════════

/**
 * 在 vite.config.js 中添加：
 * 
 * import { fileURLToPath, URL } from 'node:url'
 * import { defineConfig } from 'vite'
 * import vue from '@vitejs/plugin-vue'
 * 
 * export default defineConfig({
 *   plugins: [vue()],
 *   resolve: {
 *     alias: {
 *       '@': fileURLToPath(new URL('./src', import.meta.url)),
 *       '@design': fileURLToPath(new URL('./design-system/disasterguard', import.meta.url))
 *     }
 *   },
 *   css: {
 *     postcss: {
 *       plugins: []
 *     }
 *   }
 * })
 * 
 * 然后在 src/main.js 中：
 * import '@design/tokens.css'
 */

// ═══════════════════════════════════════════════════════════
// 方案 2: main.js 直接引入（简单）
// ═══════════════════════════════════════════════════════════

/**
 * 在 src/main.js 中添加：
 * 
 * import { createApp } from 'vue'
 * import { createPinia } from 'pinia'
 * import App from './App.vue'
 * import router from './router'
 * 
 * // ✅ 引入设计系统 CSS 变量
 * import './design-system/disasterguard/tokens.css'
 * 
 * // ⚠️ 保留原 styles.css（包含全局样式类）
 * import './styles.css'
 * 
 * const app = createApp(App)
 * app.use(createPinia())
 * app.use(router)
 * app.mount('#app')
 */

// ═══════════════════════════════════════════════════════════
// 方案 3: 按需引入（适合渐进式迁移）
// ═══════════════════════════════════════════════════════════

/**
 * 在单个组件中按需引入：
 * 
 * <script setup>
 * // 组件逻辑
 * </script>
 * 
 * <style>
 * @import '@/design-system/disasterguard/tokens.css';
 * 
 * .my-component {
 *   background: var(--color-card);
 *   padding: var(--space-lg);
 * }
 * </style>
 * 
 * ⚠️ 注意：此方案会重复加载 CSS，推荐使用方案 1 或 2
 */

// ═══════════════════════════════════════════════════════════
// TypeScript 类型提示（可选）
// ═══════════════════════════════════════════════════════════

/**
 * 创建 design-system/disasterguard/tokens.d.ts：
 * 
 * export interface DesignTokens {
 *   // 颜色
 *   '--color-primary': string
 *   '--color-primary-hover': string
 *   '--color-foreground': string
 *   '--color-background': string
 *   '--color-card': string
 *   '--color-border': string
 *   
 *   // 间距
 *   '--space-xs': string
 *   '--space-sm': string
 *   '--space-md': string
 *   '--space-lg': string
 *   
 *   // 圆角
 *   '--radius-md': string
 *   '--radius-lg': string
 *   
 *   // 阴影
 *   '--shadow-md': string
 *   '--shadow-hover': string
 *   '--shadow-focus': string
 *   
 *   // 字体
 *   '--text-base': string
 *   '--text-lg': string
 *   '--font-sans': string
 * }
 * 
 * declare module '*.css' {
 *   const tokens: DesignTokens
 *   export default tokens
 * }
 */

// ═══════════════════════════════════════════════════════════
// Stylelint 配置（避免 CSS 变量警告）
// ═══════════════════════════════════════════════════════════

/**
 * 在 .stylelintrc.json 中添加：
 * 
 * {
 *   "extends": "stylelint-config-standard",
 *   "rules": {
 *     "custom-property-pattern": null,
 *     "selector-class-pattern": null,
 *     "declaration-empty-line-before": null,
 *     "value-keyword-case": null
 *   }
 * }
 */

// ═══════════════════════════════════════════════════════════
// ESLint 忽略设计系统目录（可选）
// ═══════════════════════════════════════════════════════════

/**
 * 在 .eslintignore 中添加：
 * 
 * design-system/
 */

export default {
  message: '请按照上述方案选择一种引入方式',
  recommendation: '推荐使用方案 1（Vite 全局引入）或方案 2（main.js 直接引入）'
}
