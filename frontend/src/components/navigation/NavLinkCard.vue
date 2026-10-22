/**
 * 图文预览卡片组件
 * 
 * 功能：显示菜单项的图文预览卡片，适合功能预览
 * - 支持大图标和描述文字
 * - 支持悬停放大效果
 * 
 * @component
 */
<script setup>
import { computed } from 'vue'

const props = defineProps({
  /** 卡片数据 */
  item: {
    type: Object,
    required: true,
  },
  /** 是否激活 */
  isActive: {
    type: Boolean,
    default: false,
  },
  /** 是否悬停 */
  isHovered: {
    type: Boolean,
    default: false,
  },
})

const emit = defineEmits(['click'])

// 点击卡片
function handleClick() {
  emit('click')
}

// 悬停进入
function handleMouseEnter() {
  emit('mouseenter')
}

// 悬停离开
function handleMouseLeave() {
  emit('mouseleave')
}
</script>

<template>
  <button
    type="button"
    class="nav-card"
    :class="{
      'is-active': isActive,
      'is-hovered': isHovered,
    }"
    @click="handleClick"
    @mouseenter="handleMouseEnter"
    @mouseleave="handleMouseLeave"
  >
    <!-- 图标区域 -->
    <div class="nav-card-icon-wrap">
      <div class="nav-card-icon-bg" />
      <slot name="icon" />
    </div>
    
    <!-- 文字区域 -->
    <div class="nav-card-content">
      <span class="nav-card-title">{{ item.label }}</span>
      <span v-if="item.description" class="nav-card-desc">{{ item.description }}</span>
    </div>
    
    <!-- NEW 标记 -->
    <span v-if="item.badge === 'NEW'" class="nav-card-badge">NEW</span>
  </button>
</template>

<style scoped>
.nav-card {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  width: 120px;
  padding: 16px 12px;
  border: 0;
  border-radius: var(--r-lg, 12px);
  background: rgba(255, 255, 255, 0.04);
  color: rgba(244, 247, 244, 0.85);
  cursor: pointer;
  transition: 
    background 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    color 0.2s cubic-bezier(0.32, 0.72, 0, 1),
    transform 0.25s cubic-bezier(0.32, 0.72, 0, 1),
    box-shadow 0.2s cubic-bezier(0.32, 0.72, 0, 1);
  overflow: hidden;
}

/* 悬停效果 */
.nav-card:hover {
  background: rgba(255, 255, 255, 0.08);
  color: var(--c-on-brand, #fff);
  transform: translateY(-2px);
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.2);
}

.nav-card:hover .nav-card-icon-wrap {
  transform: scale(1.1);
}

/* 点击效果 */
.nav-card:active {
  transform: translateY(0) scale(0.98);
}

/* 激活状态 */
.nav-card.is-active {
  background: rgba(255, 255, 255, 0.12);
  color: var(--c-on-brand, #fff);
  box-shadow: 
    inset 0 1px 0 rgba(255, 255, 255, 0.15),
    0 4px 12px rgba(0, 0, 0, 0.2);
}

/* 图标区域 */
.nav-card-icon-wrap {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  transition: transform 0.25s cubic-bezier(0.32, 0.72, 0, 1);
}

.nav-card-icon-bg {
  position: absolute;
  inset: 0;
  background: linear-gradient(135deg, rgba(111, 158, 127, 0.3), rgba(111, 158, 127, 0.1));
  border-radius: var(--r-md, 8px);
  transition: background 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

.nav-card:hover .nav-card-icon-bg,
.nav-card.is-active .nav-card-icon-bg {
  background: linear-gradient(135deg, rgba(111, 158, 127, 0.5), rgba(111, 158, 127, 0.2));
}

.nav-card-icon-wrap :deep(svg) {
  position: relative;
  z-index: 1;
  color: var(--c-brand, #6f9e7f);
  filter: drop-shadow(0 2px 4px rgba(111, 158, 127, 0.3));
  transition: color 0.2s cubic-bezier(0.32, 0.72, 0, 1);
}

.nav-card:hover .nav-card-icon-wrap :deep(svg),
.nav-card.is-active .nav-card-icon-wrap :deep(svg) {
  color: var(--c-on-brand, #fff);
  filter: drop-shadow(0 2px 6px rgba(255, 255, 255, 0.2));
}

/* 文字区域 */
.nav-card-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  text-align: center;
}

.nav-card-title {
  font-size: 12.5px;
  font-weight: 600;
  line-height: 1.3;
}

.nav-card-desc {
  font-size: 10px;
  color: rgba(244, 247, 244, 0.55);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

/* NEW 标记 */
.nav-card-badge {
  position: absolute;
  top: 6px;
  right: 6px;
  padding: 2px 5px;
  border-radius: var(--r-sm, 4px);
  background: linear-gradient(135deg, var(--c-brand, #6f9e7f), var(--c-brand-hover, #5f8e70));
  color: var(--c-on-brand, #fff);
  font-size: 8px;
  font-weight: 700;
  letter-spacing: 0.04em;
  box-shadow: 0 2px 6px rgba(111, 158, 127, 0.4);
}

/* 聚焦样式 */
.nav-card:focus-visible {
  outline: 2px solid rgba(255, 255, 255, 0.5);
  outline-offset: 2px;
}
</style>
