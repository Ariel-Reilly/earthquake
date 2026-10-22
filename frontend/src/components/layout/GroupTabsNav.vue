<script setup>
/**
 * 一级大类导航条（横向，位于 TopToolbar 下方）。
 *
 * 只负责"切换大类"，大类下的页面列表由左侧 SidebarNav 平铺展示。
 * 高亮完全由父布局传入的 activeGroupId 决定（受控），本组件不读取路由，
 * 这样顶部与侧栏永远指向同一个大类，不会因各自推断而错位。
 *
 * 红点在大类上汇总：侧栏只显示当前大类，切走后其他大类的待办不能看不见。
 *
 * @component
 * @emits {string} select - 点击某个大类，参数为大类 ID
 */
import { computed } from 'vue'
import { BarChart3, Bot, Database, Layers, Radar, Settings, Siren } from 'lucide-vue-next'

const props = defineProps({
  /** 已按角色裁剪的大类列表：[{ id, label, icon, items }] */
  groups: {
    type: Array,
    default: () => [],
  },
  /** 当前激活的大类 ID */
  activeGroupId: {
    type: String,
    default: '',
  },
  /** 动态红点数据，键为菜单路径 */
  badges: {
    type: Object,
    default: () => ({}),
  },
})

const emit = defineEmits(['select'])
// 图标映射：键名与 NAV_GROUPS 的 icon 字段对应，未知名称回退到 Layers
const iconMap = { BarChart3, Bot, Database, Layers, Radar, Settings, Siren }

function getIcon(name) {
  return iconMap[name] || Layers
}

/**
 * 每个大类的红点汇总。
 * 一次性算成 Map，避免模板里对每个 tab 重复遍历 items。
 */
const groupBadges = computed(() => {
  const map = {}
  for (const group of props.groups) {
    map[group.id] = (group.items ?? []).reduce((sum, item) => {
      const n = parseInt(props.badges[item.path], 10)
      return sum + (Number.isFinite(n) && n > 0 ? n : 0)
    }, 0)
  }
  return map
})

function formatBadge(n) {
  return n > 99 ? '99+' : n
}
</script>

<template>
  <nav class="group-tabs" role="navigation" aria-label="一级导航">
    <button
      v-for="group in groups"
      :key="group.id"
      type="button"
      class="group-tab"
      :class="{ 'is-active': group.id === activeGroupId }"
      :aria-current="group.id === activeGroupId ? 'page' : undefined"
      @click="emit('select', group.id)"
    >
      <component :is="getIcon(group.icon)" :size="16" class="group-tab-icon" aria-hidden="true" />
      <span class="group-tab-label">{{ group.label }}</span>
      <span v-if="groupBadges[group.id] > 0" class="group-tab-badge">{{ formatBadge(groupBadges[group.id]) }}</span>
    </button>
  </nav>
</template>
<style scoped>
/* ── 一级导航：嵌在 Header 中部，高度由 Header 的 flex 拉伸决定 ── */
.group-tabs {
  display: flex;
  align-items: stretch;
  align-self: stretch;
  flex: 1 1 0;
  /* flex 子项默认 min-width:auto 会被内容撑开，必须置 0 才能在窄屏下出现横向滚动，
     从而保证右侧操作区不被挤压 */
  min-width: 0;
  overflow-x: auto;
  scrollbar-width: none;
}

.group-tabs::-webkit-scrollbar {
  display: none;
}

/* 用首尾项的 auto margin 实现"空间够就居中，不够就左对齐可滚动"。
   不用 justify-content:center：溢出时它会把左侧内容推到可滚动区域之外，永远滚不到 */
.group-tab:first-child {
  margin-left: auto;
}

.group-tab:last-child {
  margin-right: auto;
}

.group-tab {
  position: relative;
  display: inline-flex;
  align-items: center;
  flex-shrink: 0;
  gap: var(--sp-8, 8px);
  padding: 0 var(--sp-16, 16px);
  border: 0;
  background: transparent;
  color: var(--text-muted, #6B7A73);
  font-size: 14px;
  font-weight: 500;
  white-space: nowrap;
  cursor: pointer;
  transition:
    background 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    color 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

/* 底部 3px 指示线：贴着 Header 底边，用 scaleX 做出现动画，不触发布局 */
.group-tab::after {
  content: '';
  position: absolute;
  left: var(--sp-8, 8px);
  right: var(--sp-8, 8px);
  bottom: 0;
  height: 3px;
  border-radius: 3px 3px 0 0;
  background: var(--primary, #5F7A6B);
  transform: scaleX(0);
  transition: transform 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

.group-tab:hover {
  color: var(--text-main, #2C3B34);
  background: var(--bg-hover, rgba(95, 122, 107, 0.08));
}

/* 文字用 --primary-hover 而非 --primary：主色在白底上对比度仅 4.16，加深一档后约 5.9 */
.group-tab.is-active {
  color: var(--primary-hover, #4A6154);
  font-weight: 700;
}

.group-tab.is-active::after {
  transform: scaleX(1);
}

/* 图标颜色继承文字色，默认灰、激活时随文字变绿 */
.group-tab-icon {
  flex-shrink: 0;
}

.group-tab-badge {
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

.group-tab:focus-visible {
  outline: 2px solid var(--primary, #5F7A6B);
  outline-offset: -2px;
}

@media (prefers-reduced-motion: reduce) {
  .group-tab,
  .group-tab::after {
    transition: none;
  }
}
</style>
