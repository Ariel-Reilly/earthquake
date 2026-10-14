import { ref, createApp, h } from 'vue'
import BaseConfirmDialog from '../components/base/BaseConfirmDialog.vue'

/**
 * 全局确认对话框 composable
 * 使用方式：
 *   const { confirm } = useConfirm()
 *   const ok = await confirm({ title: '删除确认', message: '确定删除吗？', type: 'danger' })
 *   if (ok) { ... }
 */

let confirmInstance = null
let containerEl = null

function ensureInstance() {
  if (confirmInstance) {
    return confirmInstance
  }

  containerEl = document.createElement('div')
  containerEl.className = 'confirm-dialog-container'
  document.body.appendChild(containerEl)

  const visible = ref(false)
  const config = ref({})
  const resolver = ref(null)
  const loading = ref(false)

  const app = createApp({
    setup() {
      function handleConfirm() {
        loading.value = true
        // 支持异步确认操作
        if (config.value.onConfirm) {
          Promise.resolve()
            .then(() => config.value.onConfirm())
            .then(() => {
              visible.value = false
              resolver.value?.(true)
              resetAll()
            })
            .catch((err) => {
              loading.value = false
              if (config.value.onError) {
                config.value.onError(err)
              }
            })
        } else {
          visible.value = false
          resolver.value?.(true)
          resetAll()
        }
      }

      function handleCancel() {
        visible.value = false
        resolver.value?.(false)
        resetAll()
      }

      function resetAll() {
        setTimeout(() => {
          loading.value = false
          config.value = {}
          resolver.value = null
        }, 200)
      }

      return () => h(BaseConfirmDialog, {
        modelValue: visible.value,
        'onUpdate:modelValue': (val) => { visible.value = val },
        title: config.value.title,
        message: config.value.message,
        type: config.value.type,
        confirmText: config.value.confirmText,
        cancelText: config.value.cancelText,
        confirmLoading: loading.value,
        requireConfirmText: config.value.requireConfirmText,
        onConfirm: handleConfirm,
        onCancel: handleCancel,
      })
    }
  })

  app.mount(containerEl)

  confirmInstance = {
    open(options = {}) {
      return new Promise((resolve) => {
        config.value = options
        loading.value = false
        resolver.value = resolve
        visible.value = true
      })
    }
  }

  return confirmInstance
}

export function useConfirm() {
  const instance = ensureInstance()

  const confirm = (options) => {
    if (typeof options === 'string') {
      return instance.open({ message: options })
    }
    return instance.open(options)
  }

  return { confirm }
}
