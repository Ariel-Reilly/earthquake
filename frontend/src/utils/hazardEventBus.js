/**
 * 灾害点选择事件总线 + 防抖工具
 * 
 * 用途：在 AiInsightPage 中，防止父组件的灾害点选择触发子组件反向 emit，
 * 导致事件死循环。核心策略是"防抖"：
 * 
 * 1. 父组件的 selectHazard() 仅赋值 selectedCode.value = code，不强推子组件
 * 2. 子组件通过 watch(props.selectedHazardCode, ...) 监听父组件变化
 * 3. 子组件内部对 watch 回调做防抖，检查 newCode ≠ oldCode 才真正加载
 * 4. 子组件一旦加载完成，不再反向 emit，父组件状态已同步
 */

/**
 * 为灾害点选择创建防抖 watch 回调
 * 
 * @param {Function} onSelectCallback - 灾害点被选中时的回调（通常是 loadReport）
 * @param {number} delay - 防抖延迟（毫秒），默认 300ms
 * @returns {Function} 防抖后的选择函数
 * 
 * 示例：
 *   const debouncedLoad = createDebouncedHazardSelector(loadReport)
 *   watch(() => props.selectedHazardCode, debouncedLoad)
 */
export const createDebouncedHazardSelector = (onSelectCallback, delay = 300) => {
  let timeoutId = null
  
  return (newCode, oldCode) => {
    // 清理之前的定时器
    if (timeoutId) clearTimeout(timeoutId)
    
    // 防抖：延迟执行
    timeoutId = setTimeout(() => {
      // 确保只在真正切换时才触发（防止初始化或重复赋值）
      if (newCode && newCode !== oldCode) {
        onSelectCallback(newCode)
      }
    }, delay)
  }
}

/**
 * 用于检查是否是有效的灾害点切换
 * 防止快速连续的选择触发多次加载
 */
export class HazardSelector {
  constructor() {
    this.lastCode = null
    this.isLoading = false
  }

  /**
   * 检查是否应该加载新灾害点
   * @param {string} newCode - 新灾害点编码
   * @returns {boolean} 是否应该加载
   */
  shouldLoad(newCode) {
    const shouldLoad = newCode && newCode !== this.lastCode && !this.isLoading
    if (shouldLoad) {
      this.lastCode = newCode
    }
    return shouldLoad
  }

  /**
   * 标记加载开始
   */
  markLoading() {
    this.isLoading = true
  }

  /**
   * 标记加载结束
   */
  markLoaded() {
    this.isLoading = false
  }

  /**
   * 重置状态
   */
  reset() {
    this.lastCode = null
    this.isLoading = false
  }
}
