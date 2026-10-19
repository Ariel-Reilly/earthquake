<script setup>
import { computed, ref, watch } from 'vue'
import { AlertTriangle, CheckCircle2, XCircle, Info } from 'lucide-vue-next'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '确认操作' },
  message: { type: String, default: '确定要执行此操作吗？' },
  type: { type: String, default: 'warning' }, // warning | danger | success | info
  confirmText: { type: String, default: '确认' },
  cancelText: { type: String, default: '取消' },
  confirmLoading: { type: Boolean, default: false },
  // 危险操作需要用户输入确认文字
  requireConfirmText: { type: String, default: '' },
})

const emit = defineEmits(['update:modelValue', 'confirm', 'cancel'])

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const iconMap = {
  warning: { icon: AlertTriangle, color: '#d97706' },
  danger: { icon: XCircle, color: '#dc2626' },
  success: { icon: CheckCircle2, color: '#16a34a' },
  info: { icon: Info, color: '#4a7a5e' },
}

const iconInfo = computed(() => iconMap[props.type] || iconMap.warning)

const confirmInput = ref('')

const canConfirm = computed(() => {
  if (!props.requireConfirmText) return true
  return confirmInput.value === props.requireConfirmText
})

function handleConfirm() {
  if (!canConfirm.value) return
  emit('confirm')
}

function handleCancel() {
  emit('cancel')
  visible.value = false
}

// 重置输入
function reset() {
  confirmInput.value = ''
}

watch(() => props.modelValue, (val) => {
  if (val) reset()
})
</script>

<template>
  <Teleport to="body">
    <Transition name="fade">
      <div v-if="visible" class="confirm-dialog-overlay" @click.self="handleCancel">
        <Transition name="scale">
          <div v-if="visible" class="confirm-dialog" role="dialog" aria-modal="true">
            <div class="confirm-dialog-header">
              <div class="confirm-icon" :style="{ color: iconInfo.color }">
                <component :is="iconInfo.icon" :size="24" />
              </div>
              <h3 class="confirm-title">{{ title }}</h3>
            </div>
            <div class="confirm-dialog-body">
              <p class="confirm-message">{{ message }}</p>
              <div v-if="requireConfirmText" class="confirm-input-wrap">
                <label>请输入 <strong>{{ requireConfirmText }}</strong> 以确认：</label>
                <input
                  v-model="confirmInput"
                  type="text"
                  class="confirm-input"
                  :placeholder="`请输入 ${requireConfirmText}`"
                  @keyup.enter="handleConfirm"
                />
              </div>
            </div>
            <div class="confirm-dialog-footer">
              <button class="cd-btn cd-btn-cancel" @click="handleCancel" :disabled="confirmLoading">
                {{ cancelText }}
              </button>
              <button
                class="cd-btn cd-btn-confirm"
                :class="{ 'cd-btn-danger': type === 'danger' }"
                @click="handleConfirm"
                :disabled="confirmLoading || !canConfirm"
              >
                <span v-if="confirmLoading" class="cd-spinner"></span>
                {{ confirmText }}
              </button>
            </div>
          </div>
        </Transition>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.confirm-dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(15, 30, 35, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 20px;
}

.confirm-dialog {
  background: var(--c-surface);
  border-radius: var(--r-lg);
  width: 100%;
  max-width: 420px;
  box-shadow: 0 20px 40px rgba(0, 0, 0, 0.15);
  overflow: hidden;
}

.confirm-dialog-header {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 24px 12px;
}

.confirm-icon {
  flex-shrink: 0;
}

.confirm-title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  color: var(--c-text);
}

.confirm-dialog-body {
  padding: 0 24px 20px;
}

.confirm-message {
  margin: 0;
  font-size: 14px;
  color: var(--c-brand-deep);
  line-height: 1.6;
}

.confirm-input-wrap {
  margin-top: 14px;
}

.confirm-input-wrap label {
  display: block;
  font-size: 13px;
  color: var(--c-brand-deep);
  margin-bottom: 6px;
}

.confirm-input {
  width: 100%;
  padding: 8px 12px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-sm);
  font-size: 13px;
  color: var(--c-text);
  box-sizing: border-box;
  outline: none;
  transition: border-color 0.15s;
}

.confirm-input:focus {
  border-color: var(--c-brand);
}

.confirm-dialog-footer {
  display: flex;
  gap: 10px;
  padding: 14px 24px 20px;
  justify-content: flex-end;
  border-top: 1px solid var(--c-border-2);
}

.cd-btn {
  padding: 8px 18px;
  border-radius: var(--r-sm);
  font-size: 13px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
  border: 1px solid transparent;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.cd-btn-cancel {
  background: var(--c-surface);
  border-color: var(--c-separator);
  color: var(--c-brand-deep);
}

.cd-btn-cancel:hover {
  background: var(--c-bg);
}

.cd-btn-confirm {
  background: var(--c-brand);
  color: var(--c-on-brand);
}

.cd-btn-confirm:hover:not(:disabled) {
  background: var(--c-ink-1);
}

.cd-btn-confirm.cd-btn-danger {
  background: var(--c-danger-strong);
}

.cd-btn-confirm.cd-btn-danger:hover:not(:disabled) {
  background: var(--c-danger-strong);
}

.cd-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.cd-spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: var(--c-surface);
  border-radius: 50%;
  animation: cd-spin 0.7s linear infinite;
}

@keyframes cd-spin {
  to { transform: rotate(360deg); }
}

/* 过渡动画 */
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}

.scale-enter-active,
.scale-leave-active {
  transition: all 0.2s ease;
}
.scale-enter-from,
.scale-leave-to {
  opacity: 0;
  transform: scale(0.95);
}
</style>
