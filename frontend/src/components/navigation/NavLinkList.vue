/**
 * 链接列表组件
 * 
 * 功能：显示菜单项的链接列表，支持红点徽章和 NEW 标记
 * - 支持悬停高亮效果
 * - 悬停时显示左侧指示条
 * 
 * @component
 */
<script setup>
import {
  LayoutDashboard,
  Map,
  Landmark,
  Camera,
  CloudRain,
  MountainSnow,
  GitBranch,
  Bot,
  Activity,
  ShieldAlert,
  Flame,
  FileWarning,
  MessageCircle,
  ClipboardCheck,
  FileText,
  HardHat,
  Siren,
  BellRing,
  Boxes,
  Route,
  ScrollText,
  Radio,
  MapPin,
  Package,
  Database,
  Layers,
  Cloud,
  History,
  Cpu,
  BarChart3,
  FileBarChart,
  LayoutList,
  ListChecks,
  ShieldCheck,
  Inbox,
  Bell,
  UserCheck,
  Users,
  ScrollText as ScrollTextAlt,
  Radar,
  HelpCircle,
  UserCircle,
} from 'lucide-vue-next'

const props = defineProps({
  /** 菜单项列表 */
  items: {
    type: Array,
    required: true,
  },
  /** 当前路径 */
  currentPath: {
    type: String,
    default: '',
  },
  /** 动态红点数据 */
  badges: {
    type: Object,
    default: () => ({}),
  },
})

const emit = defineEmits(['navigate'])

// 图标映射
const iconMap = {
  LayoutDashboard,
  Map: Map,
  Landmark,
  Camera,
  CloudRain,
  MountainSnow,
  GitBranch,
  Bot,
  Activity,
  ShieldAlert,
  Flame,
  FileWarning,
  MessageCircle,
  ClipboardCheck,
  FileText,
  HardHat,
  Siren,
  BellRing,
  Boxes,
  Route,
  ScrollText,
  Radio,
  MapPin,
  Package,
  Database,
  Layers,
  Cloud,
  History,
  Cpu,
  BarChart3,
  FileBarChart,
  LayoutList,
  ListChecks,
  ShieldCheck,
  Inbox,
  Bell,
  UserCheck,
  Users,
  ScrollTextAlt,
  Radar,
  HelpCircle,
  UserCircle,
}

// 获取图标组件
function getIcon(iconName) {
  return iconMap[iconName] || Map
}

// 判断菜单项是否激活
function isActive(item) {
  const path = item.path
  if (path === '/dashboard') return props.currentPath === '/dashboard'
  return props.currentPath === path || props.currentPath.startsWith(path + '/')
}

// 获取红点数量
function getBadgeCount(item) {
  return props.badges[item.path] ? parseInt(props.badges[item.path], 10) : 0
}

// 判断是否有徽章
function hasBadge(item) {
  return item.badge || (props.badges[item.path] && getBadgeCount(item) > 0)
}

// 判断是否是危险徽章
function isDanger(item) {
  return item.danger && getBadgeCount(item) > 0
}

// 跳转到指定路径
function handleNavigate(path) {
  emit('navigate', path)
}
</script>

<template>
  <div class="nav-link-list" role="list">
    <button
      v-for="item in items"
      :key="item.path"
      type="button"
      class="nav-link-item"
      :class="{
        'is-active': isActive(item),
        'has-badge': hasBadge(item),
        'is-danger': isDanger(item),
      }"
      role="listitem"
      :title="item.label"
      @click="handleNavigate(item.path)"
    >
      <!-- 左侧指示条 -->
      <span class="nav-link-indicator" aria-hidden="true" />
      
      <!-- 图标 -->
      <span class="nav-link-icon">
        <component :is="getIcon(item.icon)" :size="16" />
      </span>
      
      <!-- 文字 -->
      <span class="nav-link-label">{{ item.label }}</span>
      
      <!-- NEW 标记 -->
      <span v-if="item.badge === 'NEW'" class="nav-link-tag tag-new">NEW</span>
      
      <!-- 红点徽章 -->
      <span 
        v-else-if="props.badges[item.path] && getBadgeCount(item) > 0"
        class="nav-link-badge"
        :class="{ 'badge-danger': isDanger(item) }"
      >
        {{ getBadgeCount(item) > 99 ? '99+' : getBadgeCount(item) }}
      </span>
    </button>
  </div>
</template>

<style scoped>
.nav-link-list {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  width: 100%;
}

.nav-link-item {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  padding: 0 14px;
  border: 0;
  border-radius: var(--r-md, 8px);
  background: rgba(255, 255, 255, 0.05);
  color: rgba(244, 247, 244, 0.82);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  white-space: nowrap;
  transition: 
    background 0.18s cubic-bezier(0.32, 0.72, 0, 1),
    color 0.18s cubic-bezier(0.32, 0.72, 0, 1),
    transform 0.12s cubic-bezier(0.32, 0.72, 0, 1),
    box-shadow 0.18s cubic-bezier(0.32, 0.72, 0, 1);
  overflow: hidden;
}

/* 左侧指示条 */
.nav-link-indicator {
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%) scaleY(0);
  width: 3px;
  height: 60%;
  background: var(--c-brand, #6f9e7f);
  border-radius: 0 2px 2px 0;
  transition: transform 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

.nav-link-item:hover .nav-link-indicator {
  transform: translateY(-50%) scaleY(1);
}

/* 悬停效果 */
.nav-link-item:hover {
  background: rgba(255, 255, 255, 0.10);
  color: var(--c-on-brand, #fff);
}

.nav-link-item:hover .nav-link-icon {
  transform: scale(1.08);
}

/* 点击效果 */
.nav-link-item:active {
  transform: scale(0.97);
}

/* 激活状态 */
.nav-link-item.is-active {
  background: rgba(255, 255, 255, 0.16);
  color: var(--c-on-brand, #fff);
  font-weight: 600;
  box-shadow: 
    inset 0 1px 0 rgba(255, 255, 255, 0.12),
    0 2px 8px rgba(0, 0, 0, 0.15);
}

.nav-link-item.is-active .nav-link-indicator {
  background: var(--c-on-brand, #fff);
  transform: translateY(-50%) scaleY(1);
}

/* 图标 */
.nav-link-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: transform 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

/* 文字 */
.nav-link-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* NEW 标签 */
.tag-new {
  flex-shrink: 0;
  padding: 2px 6px;
  border-radius: var(--r-sm, 4px);
  background: linear-gradient(135deg, var(--c-brand, #6f9e7f), var(--c-brand-hover, #5f8e70));
  color: var(--c-on-brand, #fff);
  font-size: 9px;
  font-weight: 700;
  letter-spacing: 0.04em;
  box-shadow: 0 2px 6px rgba(111, 158, 127, 0.4);
}

/* 红点徽章 */
.nav-link-badge {
  flex-shrink: 0;
  min-width: 18px;
  height: 18px;
  padding: 0 5px;
  border-radius: var(--r-pill, 9999px);
  background: var(--c-brand, #6f9e7f);
  color: var(--c-on-brand, #fff);
  font-size: 10px;
  font-weight: 700;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-variant-numeric: tabular-nums;
  box-shadow: 0 2px 6px rgba(111, 158, 127, 0.35);
}

.nav-link-badge.badge-danger {
  background: #ff453a;
  box-shadow: 0 2px 6px rgba(255, 69, 58, 0.4);
}

/* 危险状态（未激活但有危险标记） */
.nav-link-item.is-danger:not(.is-active) {
  color: #ffd9d6;
}

.nav-link-item.is-danger:not(.is-active) .nav-link-icon {
  color: #ff6b60;
}

/* 聚焦样式 */
.nav-link-item:focus-visible {
  outline: 2px solid rgba(255, 255, 255, 0.5);
  outline-offset: 2px;
}

/* 响应式 */
@media (max-width: 768px) {
  .nav-link-item {
    height: 34px;
    padding: 0 12px;
    font-size: 12.5px;
  }
}
</style>
