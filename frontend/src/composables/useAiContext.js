/**
 * AI 助手上下文管理 Composable
 * 用于在详情页注入当前页面的业务上下文，使 AI 助手能够感知用户所在界面。
 *
 * @example
 * // 在灾害点详情页的 onMounted 中：
 * const { setAiContext, clearAiContext } = useAiContext()
 * setAiContext({
 *   contextType: 'hazard',
 *   contextId: 'GS-001',
 *   title: '青龙坡灾害点',
 *   metadata: { riskLevel: 'high', district: '重庆市渝中区' }
 * })
 *
 * // 在 onUnmounted 中清空：
 * clearAiContext()
 */

import { ref, readonly } from 'vue'

/**
 * AI 上下文数据结构
 * @typedef {Object} AiContext
 * @property {'hazard'|'device'|'warning'|'task'|null} contextType - 上下文类型
 * @property {string|null} contextId - 业务实体 ID（如 GS-001）
 * @property {string|null} title - 业务实体标题（如"青龙坡滑坡隐患点"）
 * @property {Object} [metadata] - 扩展元数据（如风险等级、区域、设备类型等）
 */

// 全局响应式状态（单例模式）
const aiContext = ref({
  contextType: null,
  contextId: null,
  title: null,
  metadata: {}
})

/**
 * 上下文管理 Hook
 */
export function useAiContext() {
  /**
   * 设置 AI 上下文
   * @param {AiContext} context - 上下文对象
   */
  const setAiContext = (context) => {
    aiContext.value = {
      contextType: context.contextType || null,
      contextId: context.contextId || null,
      title: context.title || null,
      metadata: context.metadata || {}
    }
    console.log('[AI Context] 上下文已更新:', aiContext.value)
  }

  /**
   * 清空 AI 上下文（在详情页销毁时调用）
   */
  const clearAiContext = () => {
    aiContext.value = {
      contextType: null,
      contextId: null,
      title: null,
      metadata: {}
    }
    console.log('[AI Context] 上下文已清空')
  }

  /**
   * 获取当前上下文（只读）
   * @returns {Readonly<AiContext>}
   */
  const getAiContext = () => readonly(aiContext)

  /**
   * 判断是否有有效上下文
   * @returns {boolean}
   */
  const hasContext = () => aiContext.value.contextType !== null

  return {
    aiContext: readonly(aiContext),
    setAiContext,
    clearAiContext,
    getAiContext,
    hasContext
  }
}
