/**
 * 顶部一级导航栏 - 主组件
 *
 * 业务菜单的完整层级由左侧 SidebarNav 承载，顶部只保留一级分组入口，
 * 因此这里不再渲染二级 Mega Menu 下拉面板（它会与侧边栏层级重叠，
 * 且悬停时会遮挡地图）。
 *
 * 交互逻辑：
 * - 悬停：仅有 CSS 视觉反馈，不触发任何面板
 * - 点击：跳转到该分组下第一个有权限访问的页面
 * - 滑动指示器：通过 getBoundingClientRect() 跟随激活分组移动
 *
 * @component
 * @emits {string} navigate - 路由跳转事件
 */
<script setup>
import { computed, ref, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import NavGroupTab from './NavGroupTab.vue'
import UserProfileMenu from '../layout/UserProfileMenu.vue'

const props = defineProps({
  /** 菜单分组数据 */
  groups: {
    type: Array,
    required: true,
  },
  /** 允许访问的路径列表 */
  allowedPaths: {
    type: Array,
    default: () => [],
  },
  /** 动态红点数据 */
  badges: {
    type: Object,
    default: () => ({}),
  },
  /**
   * 受控模式下的激活分组 ID。
   * 传入（含空字符串）时由父布局决定高亮，保证顶部与左侧栏始终指向同一分组；
   * 不传（null）时沿用组件内部根据路由推断的逻辑，兼容 DashboardLayout / NavigationDemo。
   */
  activeGroupId: {
    type: String,
    default: null,
  },
})

const emit = defineEmits(['navigate', 'group-change'])

// 当前路由
const route = useRoute()

// ═══════════════════════════════════════════════════════════
// 状态管理
// ═══════════════════════════════════════════════════════════
const localGroupId = ref('')                  // 非受控模式下的激活分组
const navContainerRef = ref(null)

/** 是否由父组件控制激活分组 */
const isControlled = computed(() => props.activeGroupId !== null)

/** 实际生效的激活分组 ID */
const currentGroupId = computed(() => (isControlled.value ? props.activeGroupId : localGroupId.value))

// 滑动指示器状态
const indicatorStyle = ref({
  width: '0px',
  left: '0px',
  opacity: 0,
})
const tabRefs = {}                            // 存储每个 tab 的引用

// ═══════════════════════════════════════════════════════════
// 计算属性
// ═══════════════════════════════════════════════════════════

/** 当前路径 */
const currentPath = computed(() => route.path)

/** 过滤可见的菜单（基于权限） */
const visibleMenu = computed(() => {
  if (!Array.isArray(props.allowedPaths) || props.allowedPaths.length === 0) return []
  if (!Array.isArray(props.groups)) return []
  return props.groups.flatMap(group =>
    group.items?.filter(item => props.allowedPaths.includes(item.path)) ?? []
  )
})

// ═══════════════════════════════════════════════════════════
// 核心交互函数
// ═══════════════════════════════════════════════════════════

/**
 * 判断菜单项是否激活
 * @param {string} path - 菜单路径
 * @returns {boolean}
 */
function isItemActive(path) {
  if (path === '/dashboard') return currentPath.value === '/dashboard'
  return currentPath.value === path || currentPath.value.startsWith(path + '/')
}

/**
 * 判断分组是否激活
 * @param {string} groupId - 分组 ID
 * @returns {boolean}
 */
function isGroupActive(groupId) {
  return currentGroupId.value === groupId
}

/**
 * 点击一级菜单：跳转到该分组下第一个有权限访问的页面。
 *
 * 去掉下拉面板后，点击是进入分组的唯一入口，因此必须直接路由跳转；
 * 若分组下没有任何可访问页面（权限被全部裁剪）则不做任何事，避免跳到 403。
 * 当前页面本就属于该分组时不重复跳转，防止把用户从子页面拽回分组首页；
 * 但仅"分组高亮"而页面不在分组内（如 403、详情页兜底高亮）时仍需跳转，
 * 所以判断依据是当前路由是否命中该分组的菜单项，而不是高亮状态本身。
 *
 * @param {object} group - 分组数据
 * @param {number} tabIndex - Tab 索引
 */
function onGroupClick(group, tabIndex) {
  const firstAllowed = group.items?.find(item => props.allowedPaths.includes(item.path))
  if (!firstAllowed) return

  if (!isControlled.value) localGroupId.value = group.id
  emit('group-change', group.id)
  updateIndicator(tabIndex)

  const alreadyInGroup = group.items.some(item => isItemActive(item.path))
  if (alreadyInGroup) return
  // 跳转交给父布局统一处理，避免这里再 push 一次造成重复导航
  emit('navigate', firstAllowed.path)
}

/**
 * 更新下划线指示器位置
 *
 * 通过 getBoundingClientRect() 取得 Tab 相对容器的位置和宽度，
 * 由 CSS transition 完成平滑移动。
 *
 * @param {number} tabIndex - Tab 索引
 */
function updateIndicator(tabIndex) {
  nextTick(() => {
    const tabRef = tabRefs[tabIndex]
    if (!tabRef || !tabRef.$el) return

    const tabElement = tabRef.$el
    const containerElement = navContainerRef.value?.querySelector('.nav-tabs')
    if (!containerElement) return

    const tabRect = tabElement.getBoundingClientRect()
    const containerRect = containerElement.getBoundingClientRect()

    // 计算相对于容器的位置
    const relativeLeft = tabRect.left - containerRect.left
    const width = tabRect.width

    indicatorStyle.value = {
      width: `${width}px`,
      left: `${relativeLeft}px`,
      opacity: 1,
    }
  })
}

/**
 * 为每个 Tab 组件注册 ref 回调
 * 
 * @param {number} index - Tab 索引
 * @param {VNode} el - Vue 组件实例
 */
function setTabRef(index, el) {
  if (el) {
    tabRefs[index] = el
  }
}

// ═══════════════════════════════════════════════════════════
// 监听器：路由变化时同步激活状态
// ═══════════════════════════════════════════════════════════

// 非受控模式：自己根据路由推断激活分组
watch(
  () => route.path,
  () => {
    if (isControlled.value) return
    // 查找当前路由激活的菜单项
    if (!Array.isArray(visibleMenu.value) || visibleMenu.value.length === 0) return
    const activeItem = visibleMenu.value.find(item => isItemActive(item.path))
    if (activeItem) {
      const groupIndex = props.groups.findIndex(g => g.id === activeItem.group)
      if (groupIndex !== -1) {
        localGroupId.value = activeItem.group
        // 路由变化时自动同步指示器位置
        updateIndicator(groupIndex)
      }
    }
  },
  { immediate: true }
)

// 受控模式：父组件改变激活分组（路由切换 / 刷新 / 左侧点击）时，同步滑动指示器。
// 分组列表变化（权限刷新）时也要重算位置，因为 Tab 宽度可能变了。
watch(
  () => [props.activeGroupId, props.groups],
  () => {
    if (!isControlled.value) return
    const index = props.groups.findIndex(g => g.id === props.activeGroupId)
    if (index === -1) {
      indicatorStyle.value = { ...indicatorStyle.value, opacity: 0 }
      return
    }
    updateIndicator(index)
  },
  { immediate: true, flush: 'post' }
)
</script>

<template>
  <nav
    ref="navContainerRef"
    class="main-nav"
    role="navigation"
    aria-label="主导航"
  >
    <!-- 第一行：一级菜单 Tab（带滑动指示器） -->
    <div class="nav-primary">
      <div class="nav-tabs" role="tablist">
        <NavGroupTab
          v-for="(group, index) in groups"
          :key="group.id"
          :ref="el => setTabRef(index, el)"
          :group="group"
          :is-active="isGroupActive(group.id)"
          role="tab"
          :aria-selected="isGroupActive(group.id)"
          :tabindex="index === 0 ? 0 : -1"
          @click="onGroupClick(group, index)"
        />

        <!-- 共享滑动指示器（激活分组的下划线） -->
        <span
          class="nav-indicator"
          :style="indicatorStyle"
          aria-hidden="true"
        />
      </div>

      <!-- 右侧用户菜单 -->
      <div class="nav-actions">
        <UserProfileMenu />
      </div>
    </div>
  </nav>
</template>

<style scoped>
.main-nav {
  position: relative;
  z-index: var(--z-top, 40);
  background: linear-gradient(180deg, var(--c-brand-deep) 0%, var(--nav-bg-flat) 58%, var(--c-brand-deeper) 100%);
  box-shadow: 0 6px 22px rgba(44, 61, 51, 0.16), inset 0 -1px 0 rgba(255, 255, 255, 0.08);
}

/* ── 第一行：一级菜单 Tab ── */
.nav-primary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 62px;
  padding: 0 22px;
  gap: 14px;
}

.nav-tabs {
  position: relative;
  display: flex;
  align-items: center;
  gap: 4px;
  flex: 1;
  min-width: 0;
}

/* ── 右侧操作区 ── */
.nav-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-12, 12px);
  flex-shrink: 0;
}

/* 导航栏底色是深绿，用户菜单触发器默认的深色文字在此处对比度不足，
   这里只覆盖触发器配色，下拉卡片本身仍是白底 */
.nav-actions :deep(.user-profile-trigger) {
  color: var(--nav-text, #f4f7f4);
}
.nav-actions :deep(.user-profile-trigger:hover),
.nav-actions :deep(.user-profile-trigger.is-active) {
  background: var(--nav-hover, rgba(255, 255, 255, 0.1));
}
.nav-actions :deep(.chevron-icon) {
  color: var(--nav-text-2, rgba(244, 247, 244, 0.76));
}
.nav-actions :deep(.user-avatar) {
  box-shadow: 0 0 0 1.5px rgba(255, 255, 255, 0.55);
}

/* ── 共享滑动指示器 ── */
.nav-indicator {
  position: absolute;
  bottom: 0;
  height: 3px;
  background: linear-gradient(
    90deg,
    transparent 0%,
    rgba(255, 255, 255, 0.9) 10%,
    rgba(255, 255, 255, 0.9) 90%,
    transparent 100%
  );
  border-radius: 2px 2px 0 0;
  /* ★ 指示器平滑动画：cubic-bezier(0.87, 0, 0.13, 1) —— iOS 18 标准 */
  transition:
    left 0.3s cubic-bezier(0.87, 0, 0.13, 1),
    width 0.3s cubic-bezier(0.87, 0, 0.13, 1),
    opacity 0.25s cubic-bezier(0.87, 0, 0.13, 1);
  pointer-events: none;
  will-change: transform, width, opacity;
}

/* ── 响应式 ── */
@media (max-width: 1024px) {
  .nav-primary {
    padding: 0 16px;
  }

  .nav-tabs {
    gap: 2px;
  }
}

@media (max-width: 768px) {
  .main-nav {
    display: none;
  }
}
</style>
