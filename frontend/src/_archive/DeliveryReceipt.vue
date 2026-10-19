<script setup>
/**
 * 回执详情卡片组件
 * 展示通知发送的回执状态
 */
import { computed, onMounted, ref } from "vue"
import {
  CheckCircle,
  Clock,
  Loader2,
  RefreshCw,
  XCircle,
} from "lucide-vue-next"

const props = defineProps({
  recordId: { type: Number, required: true },
  stats: { type: Object, default: () => ({}) },
})

const emit = defineEmits(["retry", "notify"])

const loading = ref(false)
const receipts = ref([])

const channelMeta = {
  SMS: { label: "短信", color: "#6f9e7f" },
  APP: { label: "APP 推送", color: "#6f9e7f" },
  SCREEN: { label: "大屏", color: "#87b096" },
  SOUND_LIGHT: { label: "声光", color: "#cbaa76" },
  EMAIL: { label: "邮件", color: "#805ad5" },
  WEBHOOK: { label: "Webhook", color: "#718096" },
}

const statusMeta = {
  PENDING: { label: "待发送", icon: Clock, color: "#76847a" },
  SENT: { label: "已发送", icon: CheckCircle, color: "#38a169" },
  DELIVERED: { label: "已送达", icon: CheckCircle, color: "#6f9e7f" },
  FAILED: { label: "失败", icon: XCircle, color: "#e53e3e" },
  RATE_LIMITED: { label: "限流", icon: XCircle, color: "var(--c-warning-ink)" },
}

async function load() {
  loading.value = true
  try {
    const { getNotificationReceipts } = await import("../../services/_architecture/domain/notificationService.js")
    receipts.value = await getNotificationReceipts(props.recordId)
  } catch (e) {
    emit("notify", "加载回执失败: " + e.message)
  } finally {
    loading.value = false
  }
}

async function retry() {
  emit("retry", props.recordId)
}

// 接收外部更新
function updateReceipts(newReceipts) {
  receipts.value = newReceipts
}

defineExpose({ load, updateReceipts })
</script>

<template>
  <div class="receipt-card">
    <header class="card-header">
      <span>发送回执</span>
      <div class="stats" v-if="props.stats">
        <span class="stat sent">
          <CheckCircle :size="12" />
          {{ props.stats.sent || 0 }} 成功
        </span>
        <span class="stat failed" v-if="(props.stats.failed || 0) > 0">
          <XCircle :size="12" />
          {{ props.stats.failed || 0 }} 失败
        </span>
        <span class="stat pending" v-if="(props.stats.pending || 0) > 0">
          <Clock :size="12" />
          {{ props.stats.pending || 0 }} 待发
        </span>
      </div>
    </header>

    <div v-if="loading" class="loading-state">
      <Loader2 :size="16" class="spin" />
      <span>加载回执...</span>
    </div>

    <div v-else-if="receipts.length === 0" class="empty-state">
      暂无回执数据
    </div>

    <div v-else class="receipt-list">
      <div
        v-for="receipt in receipts"
        :key="receipt.id"
        class="receipt-item"
        :class="receipt.status?.toLowerCase()"
      >
        <div
          class="channel-badge"
          :style="{
            background: (channelMeta[receipt.channel]?.color || '#76847a') + '22',
            color: channelMeta[receipt.channel]?.color || '#76847a'
          }"
        >
          {{ channelMeta[receipt.channel]?.label || receipt.channel }}
        </div>

        <div class="receipt-target">{{ receipt.target }}</div>

        <div class="receipt-status">
          <component
            :is="statusMeta[receipt.status]?.icon || Clock"
            :size="14"
            :style="{ color: statusMeta[receipt.status]?.color || '#76847a' }"
          />
          <span :style="{ color: statusMeta[receipt.status]?.color || '#76847a' }">
            {{ statusMeta[receipt.status]?.label || receipt.status }}
          </span>
        </div>

        <div v-if="receipt.retryCount > 0" class="retry-count">
          重试 {{ receipt.retryCount }} 次
        </div>
      </div>
    </div>

    <footer class="card-footer" v-if="receipts.some(r => r.status === 'FAILED')">
      <button class="retry-btn" @click="retry">
        <RefreshCw :size="14" />
        重试失败项
      </button>
    </footer>
  </div>
</template>

<style scoped>
.receipt-card {
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-md);
  overflow: hidden;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 14px;
  border-bottom: 1px solid var(--c-brand-soft);
  background: var(--c-surface-2);
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
}

.stats {
  display: flex;
  gap: 12px;
}

.stat {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  font-weight: 500;
}

.stat.sent { color: var(--c-success); }
.stat.failed { color: var(--c-danger); }
.stat.pending { color: var(--c-text-2); }

.loading-state,
.empty-state {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 30px;
  color: var(--c-text-2);
  font-size: 13px;
}

.receipt-list {
  max-height: 240px;
  overflow-y: auto;
}

.receipt-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--c-brand-mist);
}

.receipt-item:last-child {
  border-bottom: none;
}

.receipt-item.failed {
  background: var(--c-danger-bg);
}

.channel-badge {
  padding: 3px 8px;
  border-radius: var(--r-xs);
  font-size: 11px;
  font-weight: 600;
  flex-shrink: 0;
}

.receipt-target {
  flex: 1;
  font-size: 12px;
  color: var(--c-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.receipt-status {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  flex-shrink: 0;
}

.retry-count {
  font-size: 10px;
  color: var(--c-warning-ink);
  flex-shrink: 0;
}

.card-footer {
  padding: 10px 14px;
  border-top: 1px solid var(--c-brand-soft);
  background: var(--c-surface-2);
}

.retry-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  border: 1px solid var(--c-brand);
  background: var(--c-surface);
  border-radius: var(--r-sm);
  color: var(--c-brand);
  font-size: 12px;
  cursor: pointer;
}

.retry-btn:hover {
  background: var(--c-brand-mist);
}

.spin {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
