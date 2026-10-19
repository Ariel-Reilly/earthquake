<script setup>
/**
 * IosSheet  ── iOS 18 底部动作菜单 (Action Sheet)
 *
 * 用法:
 *   <IosSheet v-model:open="show" title="选择操作" :actions="[
 *     { label: '编辑', onClick: handleEdit, icon: Pencil },
 *     { label: '分享', onClick: handleShare, icon: Share2 },
 *     { label: '删除', destructive: true, onClick: handleDelete, icon: Trash2 },
 *   ]" />
 */
import { ref, computed, watch } from 'vue'
import { useBodyScrollLock } from '../../composables/useBodyScrollLock'

const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '' },
  message: { type: String, default: '' },
  actions: { type: Array, default: () => [] },
  cancelText: { type: String, default: '取消' },
  showCancel: { type: Boolean, default: true },
})

const emit = defineEmits(['update:open', 'close', 'action'])

const openRef = ref(props.open)
watch(() => props.open, (v) => { openRef.value = v })
useBodyScrollLock(openRef)

const hasHeader = computed(() => Boolean(props.title || props.message))

function close() {
  emit('update:open', false)
  emit('close')
}

function pick(a) {
  emit('action', a)
  if (typeof a.onClick === 'function') a.onClick()
  if (a.closeOnClick !== false) close()
}

function onMaskClick() { close() }
</script>

<template>
  <Teleport to="body">
    <Transition name="ios-sheet" appear>
      <div v-if="open" class="ios-sheet-mask" @click.self="onMaskClick">
        <div class="ios-sheet" role="dialog" aria-modal="true" @click.stop>
          <div v-if="hasHeader" class="ios-sheet-section">
            <div class="ios-sheet-title">
              <div v-if="title" style="font-weight: 600; color: var(--ios-label);">{{ title }}</div>
              <div v-if="message" style="margin-top: 2px; line-height: 1.4;">{{ message }}</div>
            </div>
          </div>
          <div class="ios-sheet-section">
            <ul class="ios-sheet-actions">
              <li v-for="(a, i) in actions" :key="i">
                <button
                  type="button"
                  :class="{ destructive: a.destructive }"
                  @click="pick(a)"
                >
                  <span style="display: inline-flex; align-items: center; gap: 6px; justify-content: center;">
                    <component :is="a.icon" v-if="a.icon" :size="16" />
                    <span>{{ a.label }}</span>
                  </span>
                </button>
              </li>
            </ul>
          </div>
          <div v-if="showCancel" class="ios-sheet-section">
            <ul class="ios-sheet-actions">
              <li>
                <button type="button" @click="close">{{ cancelText }}</button>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.ios-sheet-enter-active { transition: opacity .22s ease; }
.ios-sheet-leave-active { transition: opacity .22s ease; }
.ios-sheet-enter-from, .ios-sheet-leave-to { opacity: 0; }
.ios-sheet-enter-active .ios-sheet,
.ios-sheet-leave-active .ios-sheet {
  transition: transform .34s cubic-bezier(.32, .72, 0, 1), opacity .22s ease;
}
.ios-sheet-enter-from .ios-sheet { transform: translateY(100%); opacity: .6; }
.ios-sheet-leave-to .ios-sheet { transform: translateY(60%); opacity: 0; }
</style>
