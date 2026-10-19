<script setup>
/**
 * IosAlert  ── iOS 18 居中模态弹窗
 *
 * 用法:
 *   <IosAlert
 *     v-model:open="show"
 *     title="确认删除?"
 *     message="此操作不可撤销"
 *     :actions="[
 *       { label: '取消', cancel: true },
 *       { label: '删除', primary: true, destructive: true, onClick: handleDelete },
 *     ]"
 *   />
 */
import { ref, computed, watch, onBeforeUnmount } from 'vue'
import { useBodyScrollLock } from '../../composables/useBodyScrollLock'

const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, required: true },
  message: { type: String, default: '' },
  actions: { type: Array, default: () => [] }, // [{ label, primary?, destructive?, cancel?, onClick? }]
  closable: { type: Boolean, default: true },
})

const emit = defineEmits(['update:open', 'close', 'action'])

// useBodyScrollLock 需要 ref, 这里把 open 转成 ref 再传入
const openRef = ref(props.open)
watch(() => props.open, (v) => { openRef.value = v })
useBodyScrollLock(openRef)

function close() {
  if (!props.closable) return
  emit('update:open', false)
  emit('close')
}

function handleAction(action) {
  emit('action', action)
  if (typeof action.onClick === 'function') action.onClick()
  if (action.closeOnClick !== false) close()
}

function onMaskClick() {
  if (props.closable) close()
}

function onKey(e) {
  if (e.key === 'Escape' && props.open) close()
}

watch(() => props.open, (v) => {
  if (v) {
    document.addEventListener('keydown', onKey)
  } else {
    document.removeEventListener('keydown', onKey)
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKey)
})
</script>

<template>
  <Teleport to="body">
    <Transition name="ios-alert" appear>
      <div v-if="open" class="ios-alert-mask" @click.self="onMaskClick">
        <div class="ios-alert" role="alertdialog" aria-modal="true" @click.stop>
          <div class="ios-alert-body">
            <div class="ios-alert-title">{{ title }}</div>
            <div v-if="message" class="ios-alert-message">{{ message }}</div>
          </div>
          <div class="ios-alert-actions">
            <button
              v-for="(a, i) in actions"
              :key="i"
              type="button"
              :class="{
                primary: a.primary,
                destructive: a.destructive,
                cancel: a.cancel,
              }"
              @click="handleAction(a)"
            >{{ a.label }}</button>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.ios-alert-actions { border-top: .5px solid var(--ios-hairline); }
.ios-alert-enter-active { transition: opacity .2s ease; }
.ios-alert-leave-active { transition: opacity .2s ease; }
.ios-alert-enter-from, .ios-alert-leave-to { opacity: 0; }
.ios-alert-enter-active .ios-alert,
.ios-alert-leave-active .ios-alert {
  transition: transform .26s cubic-bezier(.32, .72, 0, 1), opacity .2s ease;
}
.ios-alert-enter-from .ios-alert { transform: scale(.92); opacity: 0; }
.ios-alert-leave-to .ios-alert { transform: scale(.96); opacity: 0; }
</style>
