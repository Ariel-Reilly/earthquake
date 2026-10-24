<script setup>
/**
 * 我的工作台：个人首页占位
 * 待办事项、审批、消息等功能已迁移至全局通知抽屉（顶栏铃铛）
 * 此页面可在未来扩展为个性化仪表板
 */
import { ref } from 'vue'
import { AlertCircle, Bell, CheckCircle2, Clock } from 'lucide-vue-next'

const emit = defineEmits(['notify'])

// 示例统计数据
const stats = ref({
  todayTasks: 0,
  weekTasks: 0,
  completedToday: 0,
})
</script>

<template>
  <div class="workbench-page">
    <!-- 页面头部 -->
    <header class="workbench-header">
      <div class="workbench-title">
        <h2>我的工作台</h2>
        <p>个人工作效率看板</p>
      </div>
    </header>

    <!-- 统计卡片 -->
    <div class="workbench-stats">
      <div class="stat-card">
        <div class="stat-icon primary">
          <Clock :size="20" />
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.todayTasks }}</div>
          <div class="stat-label">今日任务</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon success">
          <CheckCircle2 :size="20" />
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.completedToday }}</div>
          <div class="stat-label">已完成</div>
        </div>
      </div>
      <div class="stat-card">
        <div class="stat-icon warning">
          <AlertCircle :size="20" />
        </div>
        <div class="stat-info">
          <div class="stat-value">{{ stats.weekTasks }}</div>
          <div class="stat-label">本周任务</div>
        </div>
      </div>
    </div>

    <!-- 功能提示 -->
    <div class="workbench-tips">
      <div class="tip-card">
        <div class="tip-icon">
          <Bell :size="20" />
        </div>
        <div class="tip-content">
          <h3>📢 通知中心</h3>
          <p>待办、审批、消息已整合到全局通知抽屉。点击顶栏的铃铛图标即可快速处理所有通知。</p>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.workbench-page {
  display: flex;
  flex-direction: column;
  gap: 24px;
  padding: 24px;
  background: var(--c-bg);
}

/* 页面头部 */
.workbench-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.workbench-title h2 {
  margin: 0;
  font-size: 20px;
  font-weight: 700;
  color: var(--c-text);
}

.workbench-title p {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--c-text-2);
}

/* 统计卡片 */
.workbench-stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
}

.stat-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--c-border);
  border-radius: 8px;
  background: var(--c-surface);
  transition: all 0.2s;
}

.stat-card:hover {
  background: var(--c-surface-2);
  border-color: var(--c-brand);
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.08);
}

.stat-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 6px;
  flex-shrink: 0;
}

.stat-icon.primary {
  background: var(--c-brand-light);
  color: var(--c-brand);
}

.stat-icon.success {
  background: var(--c-success-light);
  color: var(--c-success);
}

.stat-icon.warning {
  background: var(--c-warning-light);
  color: var(--c-warning);
}

.stat-info {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.stat-value {
  font-size: 24px;
  font-weight: 700;
  color: var(--c-text);
}

.stat-label {
  font-size: 12px;
  color: var(--c-text-3);
}

/* 功能提示 */
.workbench-tips {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.tip-card {
  display: flex;
  gap: 12px;
  padding: 16px;
  border: 1px solid var(--c-border);
  border-radius: 8px;
  background: var(--c-surface);
}

.tip-icon {
  display: flex;
  align-items: flex-start;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 6px;
  background: var(--c-brand-light);
  color: var(--c-brand);
  flex-shrink: 0;
  padding-top: 4px;
}

.tip-content h3 {
  margin: 0 0 4px;
  font-size: 14px;
  font-weight: 600;
  color: var(--c-text);
}

.tip-content p {
  margin: 0;
  font-size: 12px;
  color: var(--c-text-2);
  line-height: 1.5;
}

@media (max-width: 768px) {
  .workbench-page {
    padding: 16px;
  }

  .workbench-stats {
    grid-template-columns: 1fr;
  }
}
</style>
