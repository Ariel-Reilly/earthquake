<script setup>
/**
 * 全局顶部 Header（单行，高度 64px）。
 *
 * 布局：左侧 Logo + 系统名 / 中间一级大类导航（GroupTabsNav）/
 * 右侧 搜索、新建、通知、用户头像。
 * 一级导航合并进 Header 是为了省掉第二行的 44px 高度；
 * 大类下的子菜单仍由左侧 SidebarNav 平铺展示。
 *
 * 搜索只在当前角色有权限的菜单项里匹配，不请求后端，
 * 这样既不会越权暴露菜单，也不会给顶栏引入额外的接口依赖。
 *
 * @component
 * @emits {string} navigate - 请求跳转到指定路径
 * @emits {string} select-group - 点击某个一级大类，参数为大类 ID
 */
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { MountainSnow, Search, Bell, Plus, ChevronDown, Siren, Menu } from 'lucide-vue-next'
import UserProfileMenu from './UserProfileMenu.vue'
import GroupTabsNav from './GroupTabsNav.vue'
import { getAuthRole, hasPermission, canAccessPath } from '../../stores/auth'

const props = defineProps({
  /** 已按角色过滤的完整分组（每项含 label 与 items），同时作为一级导航和搜索的数据源 */
  groups: {
    type: Array,
    default: () => [],
  },
  /** 当前激活的一级大类 ID，由父布局根据路由推断后传入 */
  activeGroupId: {
    type: String,
    default: '',
  },
  /** 动态红点数据，键为菜单路径，用于一级导航上的大类汇总红点 */
  badges: {
    type: Object,
    default: () => ({}),
  },
  /** 消息中心未读数 */
  unreadCount: {
    type: Number,
    default: 0,
  },
  /** 移动端侧栏抽屉是否展开：仅用于汉堡按钮的 aria-expanded 语义 */
  sidebarOpen: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['navigate', 'select-group', 'open-notification-drawer', 'toggle-sidebar'])

const MESSAGE_CENTER_PATH = '/dashboard/message-center'
const HOME_PATH = '/dashboard'
const MAX_RESULTS = 8

// ── 菜单快速跳转搜索 ──
const keyword = ref('')
const searchOpen = ref(false)
const activeIndex = ref(0)
const searchRef = ref(null)
const inputRef = ref(null)

/** 把分组拍平成可检索列表，并带上所属大类名，方便用户区分同名页面 */
const flatItems = computed(() =>
  props.groups.flatMap((group) =>
    (group.items ?? []).map((item) => ({
      path: item.path,
      label: item.label,
      groupLabel: group.label,
    }))
  )
)

const results = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return []
  return flatItems.value
    .filter((item) =>
      item.label.toLowerCase().includes(kw) ||
      item.groupLabel.toLowerCase().includes(kw) ||
      item.path.toLowerCase().includes(kw)
    )
    .slice(0, MAX_RESULTS)
})

const showDropdown = computed(() => searchOpen.value && keyword.value.trim() !== '')

watch(results, () => { activeIndex.value = 0 })

function focusSearch() {
  searchOpen.value = true
  inputRef.value?.focus()
}

function closeSearch() {
  searchOpen.value = false
  keyword.value = ''
  activeIndex.value = 0
}

function selectItem(path) {
  emit('navigate', path)
  closeSearch()
}

function handleSearchKeydown(e) {
  if (!showDropdown.value) return
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    activeIndex.value = Math.min(activeIndex.value + 1, results.value.length - 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    activeIndex.value = Math.max(activeIndex.value - 1, 0)
  } else if (e.key === 'Enter') {
    e.preventDefault()
    if (results.value[activeIndex.value]) selectItem(results.value[activeIndex.value].path)
  } else if (e.key === 'Escape') {
    e.preventDefault()
    closeSearch()
  }
}

// ── 快速新建下拉 ──
const createOpen = ref(false)
const createRef = ref(null)

const role = computed(() => getAuthRole())

/**
 * 可新建的条目。现在只有"新建预警"，其他待补充。
 *
 * path 只用于权限判定（必须是真实菜单路径；canAccessPath 按 '/' 边界匹配，
 * /dashboard 这个共有根节点不展开子路径，见 stores/auth.js）；
 * target 才是实际跳转地址：列表页用 ?create=1 自行唤起创建弹窗。
 * 历史写法直接跳 /dashboard/warnings/create，那是被重定向回列表的壳路由，
 * 点完只会回到列表、丢掉"我要新建"的意图。
 */
const createItems = computed(() => {
  const candidates = [
    {
      label: '新建预警',
      path: '/dashboard/warnings',
      target: '/dashboard/warnings?create=1',
      icon: Siren,
      permission: 'warnings:create',
    },
  ]
  return candidates.filter((item) =>
    canAccessPath(role.value, item.path) &&
    (!item.permission || hasPermission(role.value, item.permission))
  )
})

const showCreateBtn = computed(() => createItems.value.length > 0)

function toggleCreate() {
  createOpen.value = !createOpen.value
}

function closeCreate() {
  createOpen.value = false
}

/**
 * 跳转到目标地址并收起下拉。
 * 传入的是 item.target（可能带 query），MainLayout 用 route.path 做去重比较，
 * 因此带 query 的地址不会被误判为"当前页"而静默丢弃。
 *
 * @param {string} target - 目标地址（含 query）
 */
function goToCreate(target) {
  emit('navigate', target)
  closeCreate()
}

// ── 通知铃铛：点击打开全局抽屉而不是跳转 ──
const showNotificationBell = computed(() => canAccessPath(role.value, MESSAGE_CENTER_PATH))
function openNotificationDrawer() {
  if (showNotificationBell.value) emit('open-notification-drawer')
}

// ── Logo 跳转 ──
function goHome() {
  emit('navigate', HOME_PATH)
}

// ── 快捷键提示（Ctrl+K / ⌘K）──
// navigator.platform 已废弃，改用 userAgent 判断
const isMac = computed(() => /Mac|iPhone|iPad|iPod/.test(navigator.userAgent))
const searchShortcut = computed(() => (isMac.value ? '⌘K' : 'Ctrl K'))

// ── 点击外部关闭下拉 & 全局快捷键 ──
function handleClickOutside(e) {
  if (searchRef.value && !searchRef.value.contains(e.target)) closeSearch()
  if (createRef.value && !createRef.value.contains(e.target)) closeCreate()
}

function handleGlobalKeydown(e) {
  // Ctrl+K (Windows/Linux) 或 Cmd+K (macOS) 快速聚焦搜索
  if ((e.ctrlKey || e.metaKey) && e.key === 'k') {
    e.preventDefault()
    focusSearch()
    return
  }
  // Escape 关闭所有下拉
  if (e.key === 'Escape') {
    if (searchOpen.value) closeSearch()
    if (createOpen.value) closeCreate()
  }
}

onMounted(() => {
  document.addEventListener('click', handleClickOutside)
  document.addEventListener('keydown', handleGlobalKeydown)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', handleClickOutside)
  document.removeEventListener('keydown', handleGlobalKeydown)
})
</script>

<template>
  <header class="top-toolbar">
    <div class="top-toolbar-inner">
      <!-- 移动端汉堡按钮：≤768px 才显示（样式见 styles.css 的 .mobile-menu-btn），
           桌面端侧栏常驻，不需要入口。点击由父布局打开侧栏抽屉 -->
      <button
        type="button"
        class="mobile-menu-btn"
        aria-label="打开导航菜单"
        aria-controls="app-sidebar"
        :aria-expanded="sidebarOpen"
        @click="emit('toggle-sidebar')"
      >
        <Menu :size="20" aria-hidden="true" />
      </button>

      <!-- 左侧：Logo + 品牌 -->
      <button type="button" class="toolbar-brand" @click="goHome" aria-label="返回首页">
        <div class="brand-mark">
          <MountainSnow :size="20" :stroke-width="2.4" />
        </div>
        <div class="brand-copy">
          <strong>御灾智巡</strong>
          <span class="brand-version">YuZaiGuard v1.8</span>
        </div>
      </button>

      <!-- 中间：一级导航 -->
      <GroupTabsNav
        :groups="groups"
        :active-group-id="activeGroupId"
        :badges="badges"
        @select="(groupId) => emit('select-group', groupId)"
      />

      <!-- 右侧：搜索、新建、通知、头像 -->
      <div class="toolbar-actions">
        <!-- 菜单快速跳转搜索 -->
        <div ref="searchRef" class="toolbar-search">
          <div class="search-input-wrap">
            <Search :size="16" class="search-icon" aria-hidden="true" />
            <input
              ref="inputRef"
              v-model="keyword"
              type="text"
              class="search-input"
              placeholder="搜索菜单…"
              :aria-keyshortcuts="searchShortcut"
              role="combobox"
              aria-autocomplete="list"
              :aria-expanded="showDropdown"
              aria-controls="search-listbox"
              @focus="focusSearch"
              @keydown="handleSearchKeydown"
            />
            <kbd v-if="!searchOpen" class="search-shortcut">{{ searchShortcut }}</kbd>
          </div>

          <!-- 搜索结果下拉 -->
          <Transition name="dropdown-fade">
            <div v-if="showDropdown" id="search-listbox" class="search-dropdown" role="listbox">
              <div v-if="results.length === 0" class="search-empty">无匹配菜单</div>
              <button
                v-for="(item, i) in results"
                :key="item.path"
                type="button"
                class="search-result-item"
                :class="{ 'is-active': i === activeIndex }"
                role="option"
                :aria-selected="i === activeIndex"
                @click="selectItem(item.path)"
                @mouseenter="activeIndex = i"
              >
                <span class="result-label">{{ item.label }}</span>
                <span class="result-group">{{ item.groupLabel }}</span>
              </button>
            </div>
          </Transition>
        </div>

        <!-- 新建按钮 -->
        <div v-if="showCreateBtn" ref="createRef" class="toolbar-create">
          <button
            type="button"
            class="toolbar-create-trigger"
            :class="{ 'is-active': createOpen }"
            aria-haspopup="menu"
            :aria-expanded="createOpen"
            @click="toggleCreate"
          >
            <Plus :size="16" />
            <span>新建</span>
            <ChevronDown :size="14" class="chevron-icon" :class="{ 'is-rotated': createOpen }" />
          </button>

          <Transition name="dropdown-fade">
            <div v-if="createOpen" class="create-dropdown" role="menu">
              <button
                v-for="item in createItems"
                :key="item.path"
                type="button"
                class="create-dropdown-item"
                @click="goToCreate(item.target)"
              >
                <component :is="item.icon" :size="16" />
                <span>{{ item.label }}</span>
              </button>
            </div>
          </Transition>
        </div>

        <!-- 通知铃铛：打开全局抽屉 -->
        <button
          v-if="showNotificationBell"
          type="button"
          class="toolbar-icon-btn"
          :aria-label="unreadCount > 0 ? `${unreadCount} 条未读通知` : '打开通知中心'"
          @click="openNotificationDrawer"
        >
          <Bell :size="18" />
          <span v-if="unreadCount > 0" class="notification-badge">
            {{ unreadCount > 99 ? '99+' : unreadCount }}
          </span>
        </button>

        <!-- 用户头像下拉 -->
        <UserProfileMenu />
      </div>
    </div>
  </header>
</template>
<style scoped>
/* ── 顶部 Header 容器：64px 高度，纯白底 ── */
.top-toolbar {
  position: sticky;
  top: 0;
  z-index: var(--z-top, 40);
  width: 100%;
  height: 64px;
  background: var(--bg-card, #fff);
  border-bottom: 1px solid var(--border-color, #d1dcd5);
  flex-shrink: 0;
  box-sizing: border-box;
}

.top-toolbar-inner {
  display: flex;
  align-items: center;
  height: 100%;
  padding: 0 var(--sp-16, 16px);
  gap: var(--sp-12, 12px);
}

/* ── 左侧：Logo + 品牌 ── */
.toolbar-brand {
  display: flex;
  align-items: center;
  gap: var(--sp-12, 12px);
  padding: var(--sp-8, 8px) var(--sp-12, 12px);
  border: 0;
  border-radius: var(--r-lg, 12px);
  background: transparent;
  cursor: pointer;
  transition: background 0.2s cubic-bezier(0.32, 0.72, 0, 1);
  flex-shrink: 0;
}

.toolbar-brand:hover {
  background: var(--bg-hover, rgb(95 122 107 / 8%));
}

.brand-mark {
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--r-md, 8px);
  background: linear-gradient(150deg, var(--primary, #5f7a6b), var(--primary-hover, #4a6154));
  color: var(--primary-on, #fff);
  flex-shrink: 0;
}

.brand-copy {
  display: flex;
  flex-direction: column;
  line-height: 1.2;
}

.brand-copy strong {
  font-size: 14.5px;
  font-weight: 700;
  letter-spacing: 0.01em;
  color: var(--text-main, #2c3b34);
}

.brand-version {
  /* 10px 版本号在 1x 屏基本看不清，提到 12px */
  font-size: 12px;
  color: var(--text-muted, #6b7a73);
}

/* ── 中间：菜单搜索 ── */
.toolbar-search {
  position: relative;
  flex: 0 0 auto;
  width: 200px;
}

.search-input-wrap {
  position: relative;
  display: flex;
  align-items: center;
  width: 100%;
}

.search-icon {
  position: absolute;
  left: var(--sp-12, 12px);
  color: var(--text-muted, #6b7a73);
  pointer-events: none;
}

.search-input {
  width: 100%;
  height: 38px;
  padding: 0 50px 0 calc(16px + var(--sp-12, 12px) + var(--sp-8, 8px));
  border: 1px solid var(--border-color, #d1dcd5);
  border-radius: var(--r-lg, 12px);
  background: var(--bg-page, #f4f7f5);
  color: var(--text-main, #2c3b34);
  font-size: 13px;
  transition:
    border-color 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    background 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    box-shadow 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

/* 占位符不再降透明度：0.6 透明度下对比度只有约 2.2，低于可读下限 */
.search-input::placeholder {
  color: var(--text-muted, #5f6f68);
  opacity: 1;
}

/* ── 快捷键提示 ── */
.search-shortcut {
  position: absolute;
  right: var(--sp-8, 8px);
  top: 50%;
  transform: translateY(-50%);
  font-family: monospace;
  font-size: 12px;
  color: var(--text-muted, #6b7a73);
  pointer-events: none;
}

.search-input:focus {
  outline: none;
  border-color: var(--primary, #5f7a6b);
  background: var(--bg-card, #fff);
  box-shadow: var(--focus-ring, 0 0 0 3px rgb(95 122 107 / 16%));
}

/* ── 搜索下拉：右对齐，不超出 Header 边界 ── */
.search-dropdown {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  left: auto;
  z-index: var(--z-dropdown, 10);
  width: 320px;
  max-height: 400px;
  padding: var(--sp-8, 8px);
  border-radius: var(--r-lg, 12px);
  background: var(--bg-card, #fff);
  border: 1px solid var(--border-color, #d1dcd5);
  box-shadow: var(--shadow-hover, 0 4px 16px rgb(44 59 52 / 8%));
  overflow: hidden auto;
}

.search-empty {
  padding: var(--sp-16, 16px);
  color: var(--text-muted, #6b7a73);
  font-size: 13px;
  text-align: center;
}

.search-result-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--sp-12, 12px);
  width: 100%;
  padding: var(--sp-12, 12px);
  border: 0;
  border-radius: var(--r-md, 8px);
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

.search-result-item:hover,
.search-result-item.is-active {
  background: var(--bg-hover, rgb(95 122 107 / 8%));
}

.result-label {
  flex: 1;
  font-size: 13px;
  font-weight: 500;
  color: var(--text-main, #2c3b34);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-group {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--text-muted, #6b7a73);
  padding: 2px 8px;
  border-radius: var(--r-sm, 4px);
  background: var(--bg-page, #f4f7f5);
}

/* ── 右侧：搜索、新建、通知、头像 ── */
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: var(--sp-12, 12px);
  flex-shrink: 0;
}

/* ── 通知铃铛 ── */
.toolbar-icon-btn {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: var(--r-lg, 12px);
  background: transparent;
  color: var(--text-main, #2c3b34);
  cursor: pointer;
  transition:
    background 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    transform 0.12s cubic-bezier(0.32, 0.72, 0, 1);
}

.toolbar-icon-btn:hover {
  background: var(--bg-hover, rgb(95 122 107 / 8%));
}

.toolbar-icon-btn:active {
  transform: scale(0.95);
}

.notification-badge {
  position: absolute;
  top: 2px;
  right: 2px;
  min-width: 18px;
  height: 18px;
  padding: 0 4px;
  border-radius: var(--r-pill, 9999px);
  background: var(--alert-high, #c48b8b);

  /* 白字在 #C48B8B 上对比度仅 2.83，深字 5.18 */
  color: var(--alert-on, #1f2b25);

  /* 10px 未读数在 1x 屏不可读，提到 12px 并同步放大胶囊 */
  font-size: 12px;
  font-weight: 700;
  line-height: 18px;
  text-align: center;
  box-shadow: 0 0 0 2px var(--bg-card, #fff);
}

/* ── 新建按钮 ── */
.toolbar-create {
  position: relative;
}

.toolbar-create-trigger {
  display: flex;
  align-items: center;
  gap: var(--sp-8, 8px);
  height: 38px;
  padding: 0 var(--sp-12, 12px);
  border: 0;
  border-radius: var(--r-lg, 12px);
  background: var(--primary, #5f7a6b);
  color: var(--primary-on, #fff);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition:
    background 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    transform 0.12s cubic-bezier(0.32, 0.72, 0, 1);
  box-shadow: var(--shadow-card, 0 2px 8px rgb(44 59 52 / 4%));
}

.toolbar-create-trigger:hover {
  background: var(--primary-hover, #4a6154);
  box-shadow: var(--shadow-hover, 0 4px 16px rgb(44 59 52 / 8%));
}

.toolbar-create-trigger:active {
  transform: scale(0.97);
}

.toolbar-create-trigger.is-active {
  background: var(--primary-hover, #4a6154);
}

.chevron-icon {
  transition: transform 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

.chevron-icon.is-rotated {
  transform: rotate(180deg);
}

.create-dropdown {
  position: absolute;
  top: calc(100% + 8px);
  right: 0;
  z-index: var(--z-dropdown, 10);
  min-width: 180px;
  padding: var(--sp-8, 8px);
  border-radius: var(--r-lg, 12px);
  background: var(--bg-card, #fff);
  border: 1px solid var(--border-color, #d1dcd5);
  box-shadow: var(--shadow-hover, 0 4px 16px rgb(44 59 52 / 8%));
  overflow: hidden;
}

.create-dropdown-item {
  display: flex;
  align-items: center;
  gap: var(--sp-12, 12px);
  width: 100%;
  padding: var(--sp-12, 12px);
  border: 0;
  border-radius: var(--r-md, 8px);
  background: transparent;
  color: var(--text-main, #2c3b34);
  font-size: 13px;
  font-weight: 500;
  text-align: left;
  cursor: pointer;
  transition: background 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

.create-dropdown-item:hover {
  background: var(--bg-hover, rgb(95 122 107 / 8%));
  color: var(--primary, #5f7a6b);
}

/* ── 下拉淡入动画 ── */
.dropdown-fade-enter-active,
.dropdown-fade-leave-active {
  transition:
    opacity 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    transform 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

.dropdown-fade-enter-from {
  opacity: 0;
  transform: translateY(-10px);
}

.dropdown-fade-leave-to {
  opacity: 0;
  transform: translateY(-5px);
}

/* ── 响应式：优先保证右侧操作区不被挤压，空间不足时中部一级导航自行横向滚动 ── */
@media (width <= 1180px) {
  .brand-version,
  .search-shortcut {
    display: none;
  }

  .toolbar-search {
    width: 160px;
  }

  .search-input {
    padding-right: var(--sp-12, 12px);
  }
}

/* 1280 以下优先保证一级导航完整可见；菜单搜索让位。
   1045~1180 下导航只剩约 570px，再收紧 Tab 内边距，避免第五组被裁切。 */
@media (width <= 1280px) {
  .toolbar-search {
    display: none;
  }
}

@media (width <= 1180px) {
  :deep(.group-tab) {
    gap: 4px;
    padding: 0 7px;
    font-size: 13px;
  }
}

@media (width <= 900px) {
  .brand-copy {
    display: none;
  }
}

/* ── 移动端（≤768px）──
   侧栏改为抽屉后，顶栏要多放一个汉堡按钮，宽度非常紧张：
   · 菜单搜索是桌面快捷入口，窄屏隐藏（导航交给抽屉 + 一级 Tab）
   · 新建按钮只留图标，文案与下拉箭头隐藏
   · 品牌只留图标（.brand-copy 在 900px 已隐藏） */
@media (width <= 768px) {
  /* 保留一级导航的现有样式：窄屏改成第二行横向可滚动，而不是取消导航。 */
  .top-toolbar {
    height: auto;
    min-height: 64px;
  }

  .top-toolbar-inner {
    height: auto;
    min-height: 64px;
    flex-wrap: wrap;
    align-items: center;
    padding: 0 var(--sp-8, 8px);
    gap: var(--sp-8, 8px);
    row-gap: 0;
  }

  :deep(.group-tabs) {
    order: 3;
    flex: 1 0 100%;
    width: 100%;
    height: 44px;
    align-self: stretch;
    border-top: 1px solid var(--border-color, #d1dcd5);
    padding: 0 var(--sp-8, 8px);
    scroll-behavior: smooth;
    scroll-snap-type: x proximity;
    overscroll-behavior-x: contain;
    mask-image: linear-gradient(
      to right,
      transparent 0,
      #000 12px,
      #000 calc(100% - 12px),
      transparent 100%
    );
  }

  :deep(.group-tab) {
    scroll-snap-align: center;
  }

  .toolbar-actions {
    margin-left: auto;
    gap: var(--sp-8, 8px);
  }

  .toolbar-search {
    display: none;
  }

  .toolbar-brand {
    padding: var(--sp-8, 8px) var(--sp-4, 4px);
  }

  .toolbar-create-trigger {
    padding: 0 var(--sp-8, 8px);
    gap: 0;
  }

  .toolbar-create-trigger span,
  .toolbar-create-trigger .chevron-icon {
    display: none;
  }
}
</style>
