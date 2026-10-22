<script setup>
/**
 * 左侧二级导航（平铺列表，无折叠）。
 *
 * 只渲染父布局传入的"当前一级大类"：大类切换由顶部一级导航负责，
 * 侧栏专注于该大类下的页面，同一时刻只有一层需要扫读。
 *
 * 高亮规则：在当前大类内取"最长前缀匹配"，保证 /dashboard/devices/ota
 * 只高亮「OTA 升级」而不是同时高亮「设备管理」。
 *
 * @component
 */
import { computed } from 'vue'
import {
  Activity, BarChart3, Bot, BellRing, Boxes, Camera, ClipboardCheck, Cloud,
  CloudRain, Cpu, Database, FileBarChart, FileText, FileWarning, Flame,
  GitBranch, HardDrive, HardHat, HelpCircle, History, Inbox, Landmark, Layers,
  LayoutDashboard, LayoutList, ListChecks, Map as MapIcon, MapPin, MessageCircle,
  MountainSnow, Package, Radar, Radio, Route, ScrollText, Settings, ShieldAlert,
  ShieldCheck, Siren, Sparkles, UserCheck, UserCircle, Users,
} from 'lucide-vue-next'

const props = defineProps({
  /** 当前激活的一级大类：{ id, label, icon, items: [{ path, label, icon, danger }] }，为空时只显示空态 */
  group: {
    type: Object,
    default: null,
  },
  /** 允许访问的路径列表，作为组件单独使用时的越权兜底 */
  allowedPaths: {
    type: Array,
    default: () => [],
  },
  /** 动态红点数据，键为菜单路径 */
  badges: {
    type: Object,
    default: () => ({}),
  },
  /** 当前路由路径 */
  currentPath: {
    type: String,
    default: '',
  },
})
const emit = defineEmits(['navigate'])

// 图标映射：键名与 dashboardMenu 里的 icon 字段一一对应
const iconMap = {
  Activity, BarChart3, Bot, BellRing, Boxes, Camera, ClipboardCheck, Cloud,
  CloudRain, Cpu, Database, FileBarChart, FileText, FileWarning, Flame,
  GitBranch, HardDrive, HardHat, HelpCircle, History, Inbox, Landmark, Layers,
  LayoutDashboard, LayoutList, ListChecks, Map: MapIcon, MapPin, MessageCircle,
  MountainSnow, Package, Radar, Radio, Route, ScrollText, Settings, ShieldAlert,
  ShieldCheck, Siren, Sparkles, UserCheck, UserCircle, Users,
}

/**
 * 获取图标组件，未知名称回退到地图图标。
 * @param {string} iconName - 图标名称
 * @returns {Component}
 */
function getIcon(iconName) {
  return iconMap[iconName] || MapIcon
}

/**
 * 按 allowedPaths 兜底过滤后的菜单项。
 * 父布局已按角色过滤过一次，这里再过滤是为了组件单独使用时不越权展示。
 */
const items = computed(() =>
  (props.group?.items ?? []).filter((item) => props.allowedPaths.includes(item.path))
)

/**
 * 判断路径是否命中某个菜单项。
 * /dashboard 是所有页面的前缀，只能精确匹配，否则会吞掉其他菜单。
 */
function matchPath(itemPath, current) {
  if (itemPath === '/dashboard') return current === '/dashboard'
  return current === itemPath || current.startsWith(itemPath + '/')
}

/** 当前高亮的菜单路径：取最长前缀匹配，保证只高亮一项 */
const activePath = computed(() => {
  let best = ''
  for (const item of items.value) {
    if (matchPath(item.path, props.currentPath) && item.path.length > best.length) {
      best = item.path
    }
  }
  return best
})

function isActive(path) {
  return path === activePath.value
}

function getBadgeCount(path) {
  const count = parseInt(props.badges[path], 10)
  return Number.isFinite(count) && count > 0 ? count : 0
}

function formatBadge(n) {
  return n > 99 ? '99+' : n
}

function navigateTo(path) {
  emit('navigate', path)
}
</script>

<template>
  <nav class="sidebar-nav" role="navigation" aria-label="侧边栏导航">
    <!-- 当前大类标题：只做上下文提示，不可点击、不可折叠 -->
    <h2 v-if="group" class="sidebar-title">
      <component :is="getIcon(group.icon)" :size="16" class="sidebar-title-icon" aria-hidden="true" />
      <span>{{ group.label }}</span>
    </h2>

    <ul v-if="items.length > 0" class="sidebar-list">
      <li v-for="item in items" :key="item.path">
        <button
          type="button"
          class="sidebar-item"
          :class="{ 'is-active': isActive(item.path) }"
          :aria-current="isActive(item.path) ? 'page' : undefined"
          @click="navigateTo(item.path)"
        >
          <span class="sidebar-item-indicator" aria-hidden="true" />
          <component :is="getIcon(item.icon)" :size="16" class="sidebar-item-icon" />
          <span class="sidebar-item-label">{{ item.label }}</span>
          <span
            v-if="getBadgeCount(item.path) > 0"
            class="sidebar-item-badge"
            :class="{ 'badge-danger': item.danger }"
          >{{ formatBadge(getBadgeCount(item.path)) }}</span>
        </button>
      </li>
    </ul>

    <p v-else class="sidebar-empty">暂无可访问的菜单</p>
  </nav>
</template>

<style scoped>
/* ── 侧边栏容器 ── */
.sidebar-nav {
  display: flex;
  flex-direction: column;
  gap: var(--sp-8, 8px);
  width: 100%;
  height: 100%;
  padding: var(--sp-12, 12px) var(--sp-8, 8px);
  background: var(--bg-card, #FFFFFF);
  overflow-y: auto;
  overflow-x: hidden;
}

/* ── 大类标题 ── */
.sidebar-title {
  display: flex;
  align-items: center;
  gap: var(--sp-8, 8px);
  margin: 0;
  padding: var(--sp-4, 4px) var(--sp-12, 12px) var(--sp-8, 8px);
  border-bottom: 1px solid var(--border-color, #D1DCD5);
  color: var(--text-main, #2C3B34);
  font-size: 14px;
  font-weight: 700;
}

.sidebar-title-icon {
  flex-shrink: 0;
  color: var(--primary, #5F7A6B);
}

.sidebar-list {
  display: flex;
  flex-direction: column;
  gap: var(--sp-4, 4px);
  margin: 0;
  padding: 0;
  list-style: none;
}

/* ── 菜单项 ── */
.sidebar-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--sp-12, 12px);
  width: 100%;
  padding: var(--sp-8, 8px) var(--sp-16, 16px);
  border: 0;
  border-radius: var(--r-md, 8px);
  background: transparent;
  color: var(--text-main, #2C3B34);
  font-size: 13px;
  font-weight: 500;
  text-align: left;
  cursor: pointer;
  overflow: hidden;
  transition:
    background 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    color 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

.sidebar-item-indicator {
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%) scaleY(0);
  width: 3px;
  height: 60%;
  background: var(--primary, #5F7A6B);
  border-radius: 0 2px 2px 0;
  transition: transform 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

.sidebar-item.is-active .sidebar-item-indicator {
  transform: translateY(-50%) scaleY(1);
}

/* 文字用 --primary-hover 而非 --primary：主色在浅底上对比度仅 4.16，加深一档后约 5.9 */
.sidebar-item:hover {
  background: var(--bg-hover, rgba(95, 122, 107, 0.08));
  color: var(--primary-hover, #4A6154);
}

.sidebar-item.is-active {
  background: var(--bg-active, rgba(95, 122, 107, 0.1));
  color: var(--primary-hover, #4A6154);
  font-weight: 650;
}

.sidebar-item.is-active:hover {
  background: var(--bg-active-hover, rgba(95, 122, 107, 0.14));
}

.sidebar-item-icon {
  flex-shrink: 0;
}

.sidebar-item-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── 红点徽章 ── */
.sidebar-item-badge {
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: var(--r-pill, 9999px);
  background: var(--primary, #5F7A6B);
  color: var(--primary-on, #FFFFFF);
  font-size: 10px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.sidebar-item-badge.badge-danger {
  background: var(--alert-high, #C48B8B);
  /* 白字在 #C48B8B 上对比度仅 2.83，深字 5.18 */
  color: var(--alert-on, #1F2B25);
}

.sidebar-empty {
  margin: var(--sp-24, 24px) var(--sp-16, 16px);
  color: var(--text-muted, #6B7A73);
  font-size: 13px;
  text-align: center;
}

/* ── 键盘聚焦 ── */
.sidebar-item:focus-visible {
  outline: 2px solid var(--primary, #5F7A6B);
  outline-offset: 2px;
}

@media (prefers-reduced-motion: reduce) {
  .sidebar-item,
  .sidebar-item-indicator {
    transition: none;
  }
}
</style>
