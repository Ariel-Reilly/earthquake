/**
 * 右侧主内容区组件（参考 GitHub Issues 页）
 * 
 * 【维度二】排版要求：
 * - 顶部工具栏：64px 高度，Flex 布局，底部 1px 分割线
 * - 数据列表：padding: 24px，行高 ≥50px，1px 行分割
 * - 空状态：居中显示灰色图标 + 主标题 + 次级描述
 * 
 * 【维度四】间距系统：
 * - 工具栏间距：var(--sp-16)
 * - 列表外层：padding: var(--sp-24)
 * - 列表行间距：var(--sp-12)
 * 
 * @component
 */
<script setup>
import { ref, computed } from 'vue'
import { Search, Filter, Plus, Inbox } from 'lucide-vue-next'

const props = defineProps({
  /** 页面标题 */
  title: {
    type: String,
    default: '',
  },
  /** 是否显示搜索框 */
  showSearch: {
    type: Boolean,
    default: true,
  },
  /** 是否显示筛选按钮 */
  showFilter: {
    type: Boolean,
    default: true,
  },
  /** 是否显示新建按钮 */
  showCreate: {
    type: Boolean,
    default: true,
  },
  /** 新建按钮文字 */
  createText: {
    type: String,
    default: '新建',
  },
  /** 是否显示空状态 */
  isEmpty: {
    type: Boolean,
    default: false,
  },
  /** 空状态标题 */
  emptyTitle: {
    type: String,
    default: '暂无数据',
  },
  /** 空状态描述 */
  emptyDescription: {
    type: String,
    default: '当前列表为空，请尝试其他筛选条件',
  },
})

const emit = defineEmits(['search', 'filter', 'create'])

// 搜索关键词
const searchKeyword = ref('')

/** 只要任一工具按钮可见就渲染工具栏；全关闭时不渲染，避免空占 64px */
const showToolbar = computed(() => props.showSearch || props.showFilter || props.showCreate)

/**
 * 处理搜索
 */
function handleSearch() {
  emit('search', searchKeyword.value)
}

/**
 * 处理筛选
 */
function handleFilter() {
  emit('filter')
}

/**
 * 处理新建
 */
function handleCreate() {
  emit('create')
}
</script>

<template>
  <div class="content-area">
    <!-- 【维度二】顶部工具栏：只在需要时渲染 -->
    <header v-if="showToolbar" class="content-toolbar">
      <!-- 左侧：搜索框 -->
      <div v-if="showSearch" class="toolbar-left">
        <div class="search-input-wrap">
          <Search :size="16" class="search-icon" />
          <input
            v-model="searchKeyword"
            type="text"
            class="search-input"
            placeholder="搜索…"
            @keyup.enter="handleSearch"
          />
        </div>
      </div>

      <!-- 右侧：筛选 + 新建按钮 -->
      <div class="toolbar-right">
        <button
          v-if="showFilter"
          type="button"
          class="toolbar-btn toolbar-btn-secondary"
          @click="handleFilter"
        >
          <Filter :size="16" />
          <span>筛选</span>
        </button>

        <button
          v-if="showCreate"
          type="button"
          class="toolbar-btn toolbar-btn-primary"
          @click="handleCreate"
        >
          <Plus :size="16" />
          <span>{{ createText }}</span>
        </button>
      </div>
    </header>

    <!-- 【维度二】数据列表区 -->
    <div class="content-body">
      <!-- 空状态 -->
      <div v-if="isEmpty" class="empty-state">
        <div class="empty-icon-wrap">
          <Inbox :size="48" class="empty-icon" />
        </div>
        <h3 class="empty-title">{{ emptyTitle }}</h3>
        <p class="empty-description">{{ emptyDescription }}</p>
      </div>

      <!-- 插槽：业务页面内容 -->
      <div v-else class="content-slot">
        <slot />
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ── 内容区容器 ── */
.content-area {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  /* 透明以透出 body 的网格背景 */
  background: transparent;
}

/* ── 【维度二】顶部工具栏：64px 高度 ── */
.content-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 64px;
  padding: 0 var(--sp-24, 24px);
  background: var(--bg-card, #FFFFFF);
  /* 底部分割线 */
  border-bottom: 1px solid var(--border-color, #D1DCD5);
  flex-shrink: 0;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: var(--sp-16, 16px);
  flex: 1;
  min-width: 0;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: var(--sp-12, 12px);
  flex-shrink: 0;
}

/* ── 搜索框 ── */
.search-input-wrap {
  position: relative;
  display: flex;
  align-items: center;
  width: 100%;
  max-width: 400px;
}

.search-icon {
  position: absolute;
  left: var(--sp-12, 12px);
  color: var(--text-muted, #6B7A73);
  pointer-events: none;
}

.search-input {
  width: 100%;
  height: 38px;
  padding: 0 var(--sp-12, 12px) 0 calc(var(--sp-12, 12px) + 16px + var(--sp-8, 8px));
  border: 1px solid var(--border-color, #D1DCD5);
  border-radius: var(--r-lg, 12px);
  background: var(--bg-page, #F4F7F5);
  color: var(--text-main, #2C3B34);
  font-size: 13px;
  transition:
    border-color 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    background 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    box-shadow 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

.search-input::placeholder {
  color: var(--text-muted, #6B7A73);
  opacity: 0.6;
}

.search-input:focus {
  outline: none;
  border-color: var(--primary, #5F7A6B);
  background: var(--bg-card, #FFFFFF);
  box-shadow: var(--focus-ring, 0 0 0 3px rgba(95, 122, 107, 0.16));
}

/* ── 工具栏按钮 ── */
.toolbar-btn {
  display: inline-flex;
  align-items: center;
  gap: var(--sp-8, 8px);
  height: 38px;
  padding: 0 var(--sp-16, 16px);
  border: 0;
  border-radius: var(--r-lg, 12px);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition:
    background 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    color 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    transform 0.12s cubic-bezier(0.32, 0.72, 0, 1),
    box-shadow 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

.toolbar-btn:active {
  transform: scale(0.97);
}

/* 次按钮 */
.toolbar-btn-secondary {
  background: var(--bg-page, #F4F7F5);
  color: var(--text-main, #2C3B34);
  border: 1px solid var(--border-color, #D1DCD5);
}

.toolbar-btn-secondary:hover {
  background: var(--bg-hover, rgba(95, 122, 107, 0.08));
  border-color: var(--border-hover, #B8C7BE);
  color: var(--primary, #5F7A6B);
}

/* 主按钮 */
.toolbar-btn-primary {
  background: var(--primary, #5F7A6B);
  color: var(--primary-on, #FFFFFF);
  box-shadow: var(--shadow-card, 0 2px 8px rgba(44, 59, 52, 0.04));
}

.toolbar-btn-primary:hover {
  background: var(--primary-hover, #4A6154);
  box-shadow: var(--shadow-hover, 0 4px 16px rgba(44, 59, 52, 0.08));
}

/* ── 【维度二·维度四】数据列表区：padding: 24px ── */
.content-body {
  flex: 1;
  padding: var(--sp-24, 24px);
  overflow-y: auto;
  overflow-x: hidden;
}

/* ── 【维度二】空状态：居中显示 ── */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  text-align: center;
  padding: var(--sp-32, 32px);
}

.empty-icon-wrap {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 96px;
  height: 96px;
  margin-bottom: var(--sp-24, 24px);
  border-radius: var(--r-2xl, 24px);
  background: var(--bg-page, #F4F7F5);
}

.empty-icon {
  color: var(--text-muted, #6B7A73);
  opacity: 0.4;
}

.empty-title {
  margin: 0 0 var(--sp-12, 12px) 0;
  color: var(--text-main, #2C3B34);
  font-size: 18px;
  font-weight: 700;
  line-height: 1.4;
}

.empty-description {
  margin: 0;
  color: var(--text-muted, #6B7A73);
  font-size: 14px;
  line-height: 1.6;
  max-width: 400px;
}

/* ── 内容插槽 ── */
.content-slot {
  width: 100%;
}

/* ── 【维度二】数据列表行样式（供业务页面使用） ── */
:deep(.data-list) {
  display: flex;
  flex-direction: column;
  gap: 0;
  background: var(--bg-card, #FFFFFF);
  border-radius: var(--r-lg, 12px);
  box-shadow: var(--shadow-card, 0 2px 8px rgba(44, 59, 52, 0.04));
  overflow: hidden;
}

:deep(.data-list-item) {
  display: flex;
  align-items: center;
  gap: var(--sp-16, 16px);
  min-height: 50px;
  padding: var(--sp-12, 12px) var(--sp-16, 16px);
  border-bottom: 1px solid var(--border-color, #D1DCD5);
  transition: background 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

:deep(.data-list-item:last-child) {
  border-bottom: none;
}

:deep(.data-list-item:hover) {
  background: var(--bg-hover, rgba(95, 122, 107, 0.04));
}

/* ── 响应式 ── */
@media (max-width: 1024px) {
  .content-toolbar {
    padding: 0 var(--sp-16, 16px);
  }

  .content-body {
    padding: var(--sp-16, 16px);
  }

  .search-input-wrap {
    max-width: 300px;
  }
}

@media (max-width: 768px) {
  .content-toolbar {
    flex-direction: column;
    height: auto;
    padding: var(--sp-12, 12px);
    gap: var(--sp-12, 12px);
  }

  .toolbar-left,
  .toolbar-right {
    width: 100%;
  }

  .search-input-wrap {
    max-width: none;
  }

  .toolbar-right {
    justify-content: flex-end;
  }
}
</style>
