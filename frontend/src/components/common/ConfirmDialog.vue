<template>
  <a-modal
    v-model:open="visible"
    :title="config.title"
    :ok-text="config.confirmText"
    :cancel-text="config.cancelText"
    :ok-button-props="{ loading: confirmLoading, danger: config.isDanger }"
    :closable="true"
    @ok="handleConfirm"
    @cancel="handleCancel"
  >
    <p>{{ config.message }}</p>
  </a-modal>
</template>

<script setup>
import { ref, reactive } from 'vue'

const visible = ref(false)
const confirmLoading = ref(false)
let resolveConfirm = null

const config = reactive({
  title: '确认操作',
  message: '',
  confirmText: '确认',
  cancelText: '取消',
  isDanger: false
})

/**
 * 显示确认对话框，返回 Promise<boolean>
 * @param {Object} options - 对话框配置
 * @param {string} options.title - 标题
 * @param {string} options.message - 消息内容
 * @param {string} [options.confirmText='确认'] - 确认按钮文案
 * @param {string} [options.cancelText='取消'] - 取消按钮文案
 * @param {boolean} [options.isDanger=false] - 是否为危险操作（显示红色按钮）
 * @returns {Promise<boolean>} true 表示用户点击确认，false 表示取消
 */
function show(options = {}) {
  return new Promise((resolve) => {
    Object.assign(config, options)
    resolveConfirm = resolve
    visible.value = true
    confirmLoading.value = false
  })
}

function handleConfirm() {
  confirmLoading.value = true
  // 延迟关闭，给 API 调用时间
  setTimeout(() => {
    visible.value = false
    confirmLoading.value = false
    resolveConfirm?.(true)
  }, 300)
}

function handleCancel() {
  visible.value = false
  confirmLoading.value = false
  resolveConfirm?.(false)
}

// 暴露给父组件通过 ref 调用
defineExpose({
  show
})
</script>

<style scoped>
/* Ant Design Vue 的弹窗样式自动应用 */
</style>
