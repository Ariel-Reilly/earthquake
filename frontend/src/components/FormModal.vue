<script setup>
/**
 * FormModal  ── iOS 18 风格表单弹窗
 *
 * 用法 (API 完全兼容旧版):
 *   <FormModal
 *     :open="visible"
 *     eyebrow="NEW INSPECTION"
 *     title="创建巡检任务"
 *     subtitle="统一表单已接入后端创建接口"
 *     confirm-text="确认创建"
 *     :loading="busy"
 *     :wide="true"
 *     @close="visible = false"
 *     @confirm="handleSubmit"
 *   >
 *     ...表单内容 (slot)...
 *   </FormModal>
 *
 * 视觉:
 *   - iOS 18 sheet 上滑动画 + glass 背景
 *   - 标题字号 17px (iOS nav title)
 *   - 操作按钮 capsule pill (filled primary + tinted cancel)
 *   - ESC 关闭 + 遮罩点击关闭
 */
import { ref, watch, onBeforeUnmount } from 'vue'
import { X } from 'lucide-vue-next'
import { useBodyScrollLock } from '../composables/useBodyScrollLock'

const props = defineProps({
  open: { type: Boolean, default: false },
  eyebrow: { type: String, default: '' },
  title: { type: String, required: true },
  subtitle: { type: String, default: '' },
  confirmText: { type: String, default: '确认' },
  cancelText: { type: String, default: '取消' },
  loading: { type: Boolean, default: false },
  destructive: { type: Boolean, default: false },
  wide: { type: Boolean, default: false },     // 居中宽版 (表单)
  sheet: { type: Boolean, default: true },     // 底部 sheet; false = 居中
  closable: { type: Boolean, default: true },
  size: { type: String, default: 'md' },        // sm | md | lg
})

const emit = defineEmits(['close', 'confirm'])

const openRef = ref(props.open)
watch(() => props.open, (v) => { openRef.value = v })
useBodyScrollLock(openRef)

function close() {
  if (props.loading) return
  if (!props.closable) return
  emit('close')
}

function confirm() {
  if (props.loading) return
  emit('confirm')
}

function onKey(e) {
  if (e.key === 'Escape' && props.open) close()
}

watch(() => props.open, (v) => {
  if (v) document.addEventListener('keydown', onKey)
  else document.removeEventListener('keydown', onKey)
})

onBeforeUnmount(() => document.removeEventListener('keydown', onKey))
</script>

<template>
  <Teleport to="body">
    <Transition :name="sheet ? 'ios-form-modal-sheet' : 'ios-form-modal-alert'" appear>
      <div
        v-if="open"
        :class="['form-modal-mask', sheet ? 'is-sheet' : 'is-alert']"
        @click.self="close"
      >
        <div
          :class="[
            'form-modal-panel',
            sheet ? 'is-sheet' : 'is-alert',
            wide ? 'is-wide' : '',
            `is-${size}`,
          ]"
          role="dialog"
          aria-modal="true"
          @click.stop
        >
          <!-- 顶部拖把 / 关闭 -->
          <div v-if="sheet" class="form-modal-grabber"><span /></div>
          <button
            v-if="closable && !loading"
            class="form-modal-close"
            aria-label="关闭"
            @click="close"
          >
            <X :size="16" />
          </button>

          <header class="form-modal-head">
            <span v-if="eyebrow" class="form-modal-eyebrow">{{ eyebrow }}</span>
            <h2 class="form-modal-title">{{ title }}</h2>
            <p v-if="subtitle" class="form-modal-subtitle">{{ subtitle }}</p>
          </header>

          <div class="form-modal-body">
            <slot />
          </div>

          <footer v-if="$slots.footer || !sheet" class="form-modal-foot">
            <slot name="footer">
              <button
                type="button"
                class="ios-btn ghost"
                :disabled="loading"
                @click="close"
              >{{ cancelText }}</button>
              <button
                type="button"
                :class="['ios-btn', destructive ? 'destructive' : '']"
                :disabled="loading"
                @click="confirm"
              >
                <span v-if="loading" class="ios-btn-spin" />
                {{ loading ? '处理中...' : confirmText }}
              </button>
            </slot>
          </footer>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
/* ── 遮罩 ── */
.form-modal-mask {
  position: fixed;
  inset: 0;
  z-index: 60;
  display: flex;
  padding: 16px;
  background: rgba(0, 0, 0, .42);
  -webkit-backdrop-filter: blur(8px);
  backdrop-filter: blur(8px);
}
.form-modal-mask.is-sheet {
  align-items: flex-end;
  justify-content: center;
  padding: 0;
}
.form-modal-mask.is-alert {
  align-items: center;
  justify-content: center;
}

/* ── 面板 ── */
.form-modal-panel {
  position: relative;
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 24px);
  background: var(--glass-thick, rgba(255, 255, 255, .92));
  -webkit-backdrop-filter: blur(40px) saturate(200%);
  backdrop-filter: blur(40px) saturate(200%);
  border: .5px solid var(--ios-hairline);
  box-shadow: var(--shadow-glass);
  color: var(--c-text);
}
.form-modal-panel.is-sheet {
  width: min(560px, 100%);
  margin: 0 8px 8px;
  border-radius: var(--r-2xl);
  overflow: hidden;
}
.form-modal-panel.is-alert {
  width: min(440px, 100%);
  border-radius: var(--r-xl);
  overflow: hidden;
}
.form-modal-panel.is-wide { width: min(680px, 100%); }
.form-modal-panel.is-lg { width: min(820px, 100%); }
.form-modal-panel.is-sm { width: min(360px, 100%); }

/* 顶部拖把 (仅 sheet) */
.form-modal-grabber {
  display: flex;
  justify-content: center;
  padding: 8px 0 2px;
}
.form-modal-grabber span {
  display: block;
  width: 36px;
  height: 5px;
  border-radius: 3px;
  background: var(--c-border-2);
}

/* 关闭按钮 (悬浮) */
.form-modal-close {
  position: absolute;
  top: 12px;
  right: 12px;
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border: 0;
  border-radius: 50%;
  background: rgba(120, 120, 127, .16);
  color: var(--c-text-2);
  cursor: pointer;
  transition: background .15s ease, color .15s ease, transform .12s ease;
  z-index: 2;
}
.form-modal-close:hover { background: rgba(120, 120, 127, .28); color: var(--c-text); }
.form-modal-close:active { transform: scale(.94); }

/* 头部 */
.form-modal-head {
  padding: 18px 22px 10px;
  text-align: center;
  border-bottom: .5px solid var(--c-separator);
}
.form-modal-eyebrow {
  display: block;
  color: var(--c-text-3);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: .12em;
  text-transform: uppercase;
  margin-bottom: 6px;
}
.form-modal-title {
  margin: 0;
  color: var(--c-text);
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.012em;
}
.form-modal-subtitle {
  margin: 4px 0 0;
  color: var(--c-text-2);
  font-size: 13px;
  line-height: 1.5;
}

/* body */
.form-modal-body {
  flex: 1 1 auto;
  overflow-y: auto;
  padding: 18px 22px 4px;
}
.form-modal-body :deep(.form-group) { gap: 6px; margin-bottom: 14px; }
.form-modal-body :deep(.form-label) {
  color: var(--c-text-2);
  font-size: 12px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: .04em;
}
.form-modal-body :deep(.form-input) {
  width: 100%;
  padding: 10px 14px;
  border: .5px solid var(--c-border-2);
  border-radius: var(--r-lg);
  background: var(--c-surface);
  color: var(--c-text);
  font-size: 15px;
  transition: border-color .18s, box-shadow .18s;
  outline: none;
}
.form-modal-body :deep(.form-input:focus) {
  border-color: var(--c-brand);
  box-shadow: 0 0 0 3px var(--c-brand-muted);
}
.form-modal-body :deep(label) { display: block; margin-bottom: 12px; }
.form-modal-body :deep(label > input),
.form-modal-body :deep(label > select) {
  display: block;
  margin-top: 6px;
  width: 100%;
}
.form-modal-body :deep(.modal-row) {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

/* 底部 */
.form-modal-foot {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  padding: 14px 22px 18px;
  border-top: .5px solid var(--c-separator);
  background: transparent;
}
.form-modal-foot .ios-btn { min-width: 88px; }
.form-modal-foot .ios-btn.ghost { background: rgba(120, 120, 127, .16); }
.form-modal-foot .ios-btn.ghost:hover { background: rgba(120, 120, 127, .24); }

.ios-btn-spin {
  display: inline-block;
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, .35);
  border-top-color: var(--c-surface);
  border-radius: 50%;
  margin-right: 6px;
  vertical-align: -2px;
  animation: ios-btn-spin-anim .8s linear infinite;
}
@keyframes ios-btn-spin-anim { to { transform: rotate(360deg); } }

/* ── 过渡动画 ── */
.ios-form-modal-sheet-enter-active { transition: opacity .22s ease; }
.ios-form-modal-sheet-leave-active { transition: opacity .22s ease; }
.ios-form-modal-sheet-enter-from,
.ios-form-modal-sheet-leave-to { opacity: 0; }
.ios-form-modal-sheet-enter-active .form-modal-panel,
.ios-form-modal-sheet-leave-active .form-modal-panel {
  transition: transform .36s cubic-bezier(.32, .72, 0, 1), opacity .22s ease;
}
.ios-form-modal-sheet-enter-from .form-modal-panel { transform: translateY(100%); opacity: .6; }
.ios-form-modal-sheet-leave-to .form-modal-panel { transform: translateY(50%); opacity: 0; }

.ios-form-modal-alert-enter-active { transition: opacity .22s ease; }
.ios-form-modal-alert-leave-active { transition: opacity .22s ease; }
.ios-form-modal-alert-enter-from,
.ios-form-modal-alert-leave-to { opacity: 0; }
.ios-form-modal-alert-enter-active .form-modal-panel,
.ios-form-modal-alert-leave-active .form-modal-panel {
  transition: transform .26s cubic-bezier(.32, .72, 0, 1), opacity .22s ease;
}
.ios-form-modal-alert-enter-from .form-modal-panel { transform: scale(.92); opacity: 0; }
.ios-form-modal-alert-leave-to .form-modal-panel { transform: scale(.96); opacity: 0; }

@media (max-width: 540px) {
  .form-modal-panel.is-wide,
  .form-modal-panel.is-lg { width: 100%; }
  .form-modal-mask { padding: 0; }
  .form-modal-panel.is-sheet { margin: 0 0 6px; border-radius: var(--r-xl) 18px 0 0; }
}
</style>
