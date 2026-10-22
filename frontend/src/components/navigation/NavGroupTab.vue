/**
 * 一级菜单 Tab 组件 (2026-10-21 重构)
 * 
 * 功能：显示分组标签，带图标、计数器、悬停高亮
 * - 悬停时 Tab 背景亮起
 * - 激活时 Tab 背景更亮 + 加粗字体
 * - 图标自动从字符串映射到组件（lucide-vue-next）
 * 
 * @component
 */
<script setup>
import {
  LayoutDashboard,
  Radar,
  Bot,
  Siren,
  Database,
  BarChart3,
  Settings,
  MountainSnow,
  ShieldAlert,
  Layers,
  BellRing,
  Activity,
} from 'lucide-vue-next'

const props = defineProps({
  /** 分组数据 */
  group: {
    type: Object,
    required: true,
  },
  /** 是否激活 */
  isActive: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['click', 'keydown'])

// ═══════════════════════════════════════════════════════════
// 图标映射表
// ═══════════════════════════════════════════════════════════
const iconMap = {
  LayoutDashboard,
  Radar,
  Bot,
  Siren,
  Database,
  BarChart3,
  Settings,
  MountainSnow,
  ShieldAlert,
  Layers,
  BellRing,
  Activity,
}

/**
 * 根据字符串获取图标组件
 * @param {string} iconName - 图标名称
 * @returns {Component} Vue 图标组件
 */
function getIconComponent(iconName) {
  return iconMap[iconName] || Radar // 默认回退到 Radar
}

// 当前分组的图标组件
const IconComponent = getIconComponent(props.group.icon)

// 处理点击
function handleClick() {
  emit('click')
}

// 处理键盘事件
function handleKeyDown(event) {
  emit('keydown', event)
}
</script>

<template>
  <button
    type="button"
    class="nav-tab"
    :class="{ 'is-active': isActive }"
    :aria-selected="isActive"
    @click="handleClick"
    @keydown="handleKeyDown"
  >
    <!-- 图标 -->
    <component
      :is="IconComponent"
      :size="16"
      class="nav-tab-icon"
    />

    <!-- 分组标签 -->
    <span class="nav-tab-label">{{ group.label }}</span>

    <!-- 功能计数 -->
    <span class="nav-tab-count">{{ group.itemCount || group.items?.length || 0 }}</span>
  </button>
</template>

<style scoped>
.nav-tab {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 36px;
  padding: 0 13px;
  border: 0;
  border-radius: var(--r-md, 8px);
  background: transparent;
  color: rgba(244, 247, 244, 0.78);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition:
    background 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    color 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    transform 0.14s cubic-bezier(0.32, 0.72, 0, 1);
}

/* ── 悬停效果 ──
   导航栏底色是深绿，文字加深到 --text-main 会看不清，所以悬停时文字提亮为白色，
   并在底部出现一条半透明白线作为指示；激活态由 MainNav 的滑动指示器负责，这里不重复画线 */
.nav-tab::after {
  content: '';
  position: absolute;
  left: 13px;
  right: 13px;
  bottom: 0;
  height: 2px;
  border-radius: 2px;
  background: rgba(255, 255, 255, 0.7);
  opacity: 0;
  transform: scaleX(0.4);
  transition:
    opacity 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    transform 0.2s cubic-bezier(0.32, 0.72, 0, 1);
  pointer-events: none;
}

.nav-tab:hover {
  background: rgba(255, 255, 255, 0.12);
  color: var(--c-on-brand, #fff);
}

.nav-tab:hover:not(.is-active)::after {
  opacity: 1;
  transform: scaleX(1);
}

/* ── 点击效果 ── */
.nav-tab:active {
  transform: scale(0.97);
}

/* ── 激活状态 ── */
.nav-tab.is-active {
  background: rgba(255, 255, 255, 0.20);
  color: var(--c-on-brand, #fff);
  font-weight: 650;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.22);
}

/* ── 图标 ── */
.nav-tab-icon {
  flex-shrink: 0;
  transition: transform 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

.nav-tab:hover .nav-tab-icon {
  transform: scale(1.1);
}

/* ── 标签文字 ── */
.nav-tab-label {
  line-height: 1;
}

/* ── 功能计数器 ── */
.nav-tab-count {
  font-style: normal;
  font-size: 10.5px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  min-width: 17px;
  height: 17px;
  padding: 0 5px;
  display: inline-grid;
  place-items: center;
  border-radius: var(--r-pill, 9999px);
  background: rgba(255, 255, 255, 0.16);
  color: rgba(255, 255, 255, 0.88);
  transition:
    background 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    color 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

.nav-tab.is-active .nav-tab-count {
  background: rgba(255, 255, 255, 0.30);
  color: var(--c-on-brand, #fff);
}

/* ── 聚焦样式（无障碍） ── */
.nav-tab:focus-visible {
  outline: 2px solid rgba(255, 255, 255, 0.6);
  outline-offset: 2px;
}

/* ── 响应式：小屏幕隐藏文字 ── */
@media (max-width: 1024px) {
  .nav-tab-label {
    display: none;
  }

  .nav-tab {
    padding: 0 10px;
  }
}
</style>
