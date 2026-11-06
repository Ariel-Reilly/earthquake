<script setup>
/**
 * StatCard - 统计卡片组件
 * 
 * 用于展示关键指标（KPI），支持图标、数值、单位、状态标记。
 * 遵循设计系统的 Morandi 配色和间距规范。
 * 
 * @component
 * @example
 * <StatCard 
 *   :icon="ShieldCheck"
 *   label="处置中的事件"
 *   count="3"
 *   suffix="起"
 *   status-class="positive"
 *   status-text="联动响应"
 *   status-subtext="已启动"
 * />
 */

defineProps({
  /** Lucide 图标组件 */
  icon: {
    type: Object,
    required: true,
  },
  /** 卡片标签文本 */
  label: {
    type: String,
    required: true,
  },
  /** 数值（可以是字符串或数字） */
  count: {
    type: [String, Number],
    required: true,
  },
  /** 单位（如"起"、"人"、"份"） */
  suffix: {
    type: String,
    default: '',
  },
  /** 状态样式类：'positive' | 'negative' | 'neutral' */
  statusClass: {
    type: String,
    default: 'positive',
  },
  /** 状态主文本 */
  statusText: {
    type: String,
    required: true,
  },
  /** 状态副文本 */
  statusSubtext: {
    type: String,
    required: true,
  },
  /** 图标颜色类型：'orange' | 'teal' | 'blue' | 'purple' */
  iconVariant: {
    type: String,
    default: 'orange',
  },
})
</script>

<template>
  <div class="stat-card">
    <div class="stat-icon" :class="iconVariant">
      <component :is="icon" :size="19" aria-hidden="true" />
    </div>
    <div class="stat-copy">
      <span class="stat-label">{{ label }}</span>
      <strong class="stat-count">
        {{ count }}
        <small v-if="suffix" class="stat-suffix">{{ suffix }}</small>
      </strong>
      <em :class="['stat-status', statusClass]">
        {{ statusText }}
        <i class="stat-status-sub">{{ statusSubtext }}</i>
      </em>
    </div>
  </div>
</template>

<style scoped>
.stat-card {
  display: flex;
  align-items: stretch;
  gap: var(--space-md);
  padding: var(--space-lg);
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-xs);
  transition: all var(--duration-fast) var(--ease-out);
}

.stat-card:hover {
  border-color: var(--color-border-hover);
  box-shadow: var(--shadow-sm);
}

/* 图标容器 */
.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: var(--radius-md);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  color: var(--color-on-primary);
  font-weight: 500;
  transition: all var(--duration-fast) var(--ease-out);
}

.stat-icon.orange {
  background: #D4B48B;
}

.stat-icon.teal {
  background: #6F9E7F;
}

.stat-icon.blue {
  background: #3E6F94;
}

.stat-icon.purple {
  background: #7A5FA8;
}

/* 文本容器 */
.stat-copy {
  display: flex;
  flex-direction: column;
  justify-content: center;
  flex: 1;
  min-width: 0;
}

/* 标签 */
.stat-label {
  display: block;
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  font-weight: var(--font-normal);
  margin-bottom: var(--space-xs);
  letter-spacing: 0.3px;
}

/* 数值 */
.stat-count {
  display: block;
  font-size: var(--text-2xl);
  font-weight: var(--font-bold);
  color: var(--color-foreground);
  line-height: 1.2;
}

.stat-suffix {
  display: inline;
  font-size: var(--text-sm);
  font-weight: var(--font-normal);
  color: var(--color-muted-foreground);
  margin-left: var(--space-xs);
}

/* 状态标记 */
.stat-status {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  font-size: var(--text-xs);
  font-style: normal;
  margin-top: var(--space-xs);
  font-weight: var(--font-medium);
}

.stat-status.positive {
  color: var(--color-success-strong);
}

.stat-status.negative {
  color: var(--color-destructive-strong);
}

.stat-status.neutral {
  color: var(--color-muted-foreground);
}

.stat-status-sub {
  display: inline;
  font-style: normal;
  opacity: 0.85;
  font-weight: var(--font-normal);
}
</style>
