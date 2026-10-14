<script setup>
import { ref, onErrorCaptured } from 'vue'
import { AlertTriangle, RefreshCw, Home, Bug } from 'lucide-vue-next'
import { useRouter } from 'vue-router'

const props = defineProps({
  fallback: { type: String, default: '' }
})
const emit = defineEmits(['error'])

const router = useRouter()
const error = ref(null)
const errorInfo = ref(null)

onErrorCaptured((err, instance, info) => {
  error.value = err
  errorInfo.value = info
  emit('error', { error: err, instance, info })
  // 返回 true 表示错误已被处理，不继续向上传播
  return true
})

function handleRefresh() {
  window.location.reload()
}

function handleGoHome() {
  router.push('/dashboard')
}

function clearError() {
  error.value = null
  errorInfo.value = null
}
</script>

<template>
  <slot v-if="!error" />
  <div v-else class="error-boundary">
    <div class="error-boundary-content">
      <div class="error-icon">
        <AlertTriangle :size="48" />
      </div>
      <h2 class="error-title">页面出错了</h2>
      <p class="error-message">{{ error?.message || '发生了未知错误' }}</p>
      <p v-if="errorInfo" class="error-info">错误位置：{{ errorInfo }}</p>
      <div class="error-actions">
        <button class="eb-btn eb-btn-primary" @click="handleRefresh">
          <RefreshCw :size="16" /> 刷新页面
        </button>
        <button class="eb-btn" @click="handleGoHome">
          <Home :size="16" /> 返回首页
        </button>
      </div>
      <div class="error-detail" v-if="error?.stack">
        <details>
          <summary>
            <Bug :size="14" /> 查看错误详情
          </summary>
          <pre>{{ error.stack }}</pre>
        </details>
      </div>
    </div>
  </div>
</template>

<style scoped>
.error-boundary {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 400px;
  padding: 40px 20px;
  background: #fef7f5;
}
.error-boundary-content {
  max-width: 500px;
  text-align: center;
}
.error-icon {
  color: var(--c-danger-strong);
  margin-bottom: 16px;
}
.error-title {
  font-size: 20px;
  font-weight: 600;
  color: var(--c-text);
  margin: 0 0 8px;
}
.error-message {
  color: var(--c-brand-deep);
  font-size: 14px;
  margin: 0 0 8px;
}
.error-info {
  color: #8a9b98;
  font-size: 12px;
  margin: 0 0 20px;
}
.error-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
  margin-bottom: 20px;
}
.eb-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 18px;
  border: 1px solid rgba(60,60,67,.10);
  background: var(--c-surface);
  border-radius: var(--r-sm);
  color: var(--c-text);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.15s;
}
.eb-btn:hover {
  background: var(--c-bg);
}
.eb-btn-primary {
  background: var(--c-brand);
  border-color: var(--c-brand);
  color: var(--c-on-brand);
}
.eb-btn-primary:hover {
  background: #27696a;
}
.error-detail {
  text-align: left;
  background: var(--c-surface);
  border: 1px solid var(--c-brand-soft);
  border-radius: var(--r-sm);
  padding: 12px;
}
.error-detail details summary {
  cursor: pointer;
  color: var(--c-brand-deep);
  font-size: 12px;
  display: flex;
  align-items: center;
  gap: 6px;
}
.error-detail pre {
  margin: 10px 0 0;
  font-size: 11px;
  color: #8a5a4a;
  background: #fef7f5;
  padding: 10px;
  border-radius: var(--r-sm);
  overflow-x: auto;
  max-height: 200px;
}
</style>
