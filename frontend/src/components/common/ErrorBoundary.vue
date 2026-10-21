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
/**
 * 错误边界样式（UX_Audit_Report P0-1）
 * 
 * 采用深色调 + 品牌色系，符合 DisasterGuard 整体设计语言
 */
.error-boundary {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 40px 20px;
  background: linear-gradient(135deg, #0F172A 0%, #1E293B 100%);
}

.error-boundary-content {
  max-width: 560px;
  padding: 48px;
  text-align: center;
  background: rgba(255, 255, 255, 0.04);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px;
  backdrop-filter: blur(12px);
}

.error-icon {
  color: #F97316;
  margin-bottom: 24px;
  animation: pulse-icon 2s ease-in-out infinite;
}

@keyframes pulse-icon {
  0%, 100% { opacity: 1; transform: scale(1); }
  50% { opacity: 0.8; transform: scale(0.98); }
}

.error-title {
  font-size: 24px;
  font-weight: 600;
  color: #F8FAFC;
  margin: 0 0 12px;
  letter-spacing: -0.02em;
}

.error-message {
  color: #CBD5E1;
  font-size: 15px;
  line-height: 1.6;
  margin: 0 0 8px;
}

.error-info {
  color: #64748B;
  font-size: 13px;
  margin: 0 0 32px;
  font-family: 'SF Mono', Monaco, 'Cascadia Code', monospace;
}

.error-actions {
  display: flex;
  gap: 12px;
  justify-content: center;
  margin-bottom: 24px;
}

.eb-btn {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 10px 20px;
  border: 1px solid rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.06);
  border-radius: 8px;
  color: #F8FAFC;
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
}

.eb-btn:hover {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.2);
  transform: translateY(-1px);
}

.eb-btn:active {
  transform: translateY(0);
}

.eb-btn-primary {
  background: #22D3EE;
  border-color: #22D3EE;
  color: #0F172A;
}

.eb-btn-primary:hover {
  background: #06B6D4;
  border-color: #06B6D4;
}

.error-detail {
  text-align: left;
  background: rgba(0, 0, 0, 0.3);
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 8px;
  padding: 16px;
}

.error-detail details summary {
  cursor: pointer;
  color: #38BDF8;
  font-size: 13px;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 6px;
  user-select: none;
}

.error-detail details summary:hover {
  color: #22D3EE;
}

.error-detail pre {
  margin: 12px 0 0;
  font-size: 11px;
  line-height: 1.5;
  color: #94A3B8;
  background: rgba(0, 0, 0, 0.4);
  padding: 12px;
  border-radius: 6px;
  overflow-x: auto;
  max-height: 240px;
  font-family: 'SF Mono', Monaco, 'Cascadia Code', monospace;
}

.error-detail pre::-webkit-scrollbar {
  height: 8px;
}

.error-detail pre::-webkit-scrollbar-track {
  background: rgba(255, 255, 255, 0.04);
  border-radius: 4px;
}

.error-detail pre::-webkit-scrollbar-thumb {
  background: rgba(255, 255, 255, 0.12);
  border-radius: 4px;
}

.error-detail pre::-webkit-scrollbar-thumb:hover {
  background: rgba(255, 255, 255, 0.2);
}
</style>
