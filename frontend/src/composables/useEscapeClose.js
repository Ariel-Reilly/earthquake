import { onMounted, onUnmounted } from 'vue'

/**
 * 监听 ESC 键关闭弹窗/抽屉
 * 
 * @param {Function} onClose - 关闭回调函数
 * @param {import('vue').Ref<boolean>} [isOpen] - 可选的开关状态，用于多层弹窗时只关闭最上层
 * 
 * @example
 * // 基础用法
 * useEscapeClose(() => { visible.value = false })
 * 
 * // 带状态检查（多层弹窗场景）
 * useEscapeClose(() => { visible.value = false }, visible)
 */
export function useEscapeClose(onClose, isOpen = null) {
  function handleEscape(e) {
    // 只在目标元素打开时（或未提供状态时）响应 ESC
    if (e.key === 'Escape' && (!isOpen || isOpen.value)) {
      onClose()
    }
  }

  onMounted(() => {
    document.addEventListener('keydown', handleEscape)
  })

  onUnmounted(() => {
    document.removeEventListener('keydown', handleEscape)
  })
}
