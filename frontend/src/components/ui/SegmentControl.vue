<script setup>
/**
 * SegmentControl - 分段控制组件
 * 
 * 用于在多个互斥选项间切换，类似于 iOS 分段控制或 Web 的选项卡组。
 * 遵循设计系统的 Morandi 配色和交互规范。
 * 
 * @component
 * @example
 * <SegmentControl 
 *   v-model="activeTab"
 *   :options="[
 *     { value: 'all', label: '全部' },
 *     { value: 'active', label: '进行中' },
 *     { value: 'done', label: '已完成' }
 *   ]"
 * />
 */

import { computed } from 'vue'

const props = defineProps({
  /** 当前选中的值 */
  modelValue: {
    type: [String, Number],
    required: true,
  },
  /** 选项列表：{ value, label, icon?, disabled? } */
  options: {
    type: Array,
    default: () => [],
  },
  /** 尺寸：'sm' | 'md' | 'lg' */
  size: {
    type: String,
    default: 'md',
  },
  /** 是否禁用整个控件 */
  disabled: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['update:modelValue'])

/** 计算选项列表，过滤禁用项 */
const activeOptions = computed(() =>
  props.options.map((opt) => ({
    value: opt.value ?? opt,
    label: opt.label ?? opt,
    icon: opt.icon ?? null,
    disabled: opt.disabled ?? false,
  }))
)

/**
 * 处理选项切换
 */
function handleSelect(value) {
  if (!props.disabled) {
    emit('update:modelValue', value)
  }
}

/**
 * 判断选项是否被选中
 */
function isActive(value) {
  return props.modelValue === value
}
</script>

<template>
  <div class="segment-control" :class="[`size-${size}`, { disabled }]" role="tablist">
    <button
      v-for="opt in activeOptions"
      :key="opt.value"
      type="button"
      role="tab"
      :class="['segment-btn', { active: isActive(opt.value), disabled: opt.disabled }]"
      :disabled="opt.disabled || disabled"
      :aria-selected="isActive(opt.value)"
      @click="handleSelect(opt.value)"
    >
      <component v-if="opt.icon" :is="opt.icon" :size="16" aria-hidden="true" />
      {{ opt.label }}
    </button>
  </div>
</template>

<style scoped>
.segment-control {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  background: var(--c-surface-2);
  border-radius: var(--r-pill);
  border: 1px solid var(--c-separator);
  transition: all 0.18s ease-out;
}

.segment-control.disabled {
  opacity: 0.6;
  pointer-events: none;
}

/* 按钮基础样式 */
.segment-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 8px 12px;
  border: 1px solid transparent;
  background: transparent;
  color: var(--c-ink-2);
  border-radius: var(--r-pill);
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.18s ease-out;
}

/* 激活状态 */
.segment-btn.active {
  background: var(--c-surface);
  color: var(--c-brand-deeper);
  border-color: var(--c-separator);
  box-shadow: var(--shadow-xs);
}

/* 悬停状态 */
.segment-btn:hover:not(.disabled):not(.active) {
  color: var(--c-text);
  background: rgba(95, 122, 107, 0.04);
}

.segment-btn:hover.active {
  border-color: var(--c-brand);
}

/* 禁用状态 */
.segment-btn.disabled {
  opacity: 0.5;
  cursor: not-allowed;
  pointer-events: none;
}

/* 尺寸变体 */
.size-sm .segment-btn {
  padding: 4px 8px;
  font-size: 13px;
}

.size-lg .segment-btn {
  padding: 12px 16px;
  font-size: 16px;
}

/* 焦点环（无障碍） */
.segment-btn:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px var(--c-brand);
}
</style>
