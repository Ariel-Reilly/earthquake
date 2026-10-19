/**
 * 安全的最值查找工具。
 *
 * 背景：Math.min(...arr) / Math.max(...arr) 在数组元素数超过 V8 调用栈限制
 * （一般 ~10 万参数）时会抛 RangeError（Maximum call stack size exceeded）。
 * 此外，对长数组频繁展开也会带来不必要的 GC 压力。
 *
 * 本工具使用 O(n) 的单次遍历实现，避免栈展开，O(1) 栈深度。
 * 空数组 / 非数组 / NaN 输入统一返回 fallback。
 *
 * 使用场景：
 *   - 预测序列（horizon 可配，理论无上限）
 *   - 设备轨迹（24h × 30s 采样 = 8640 点）
 *   - 训练历史、时序数据等
 */

const DEFAULT_MIN_FALLBACK = Infinity
const DEFAULT_MAX_FALLBACK = -Infinity

/**
 * 返回数组最小值。
 * @param {Array<number>} arr - 数值数组
 * @param {number} [fallback=Infinity] - 数组为空或无效时的返回值
 * @returns {number}
 */
export function safeMin(arr, fallback = DEFAULT_MIN_FALLBACK) {
  if (!Array.isArray(arr) || arr.length === 0) {
    return fallback
  }
  let min = arr[0]
  // 从 index 1 开始，避免重复比较 arr[0]
  for (let i = 1; i < arr.length; i++) {
    if (arr[i] < min) {
      min = arr[i]
    }
  }
  return min
}

/**
 * 返回数组最大值。
 * @param {Array<number>} arr - 数值数组
 * @param {number} [fallback=-Infinity] - 数组为空或无效时的返回值
 * @returns {number}
 */
export function safeMax(arr, fallback = DEFAULT_MAX_FALLBACK) {
  if (!Array.isArray(arr) || arr.length === 0) {
    return fallback
  }
  let max = arr[0]
  for (let i = 1; i < arr.length; i++) {
    if (arr[i] > max) {
      max = arr[i]
    }
  }
  return max
}

/**
 * 一次遍历同时返回 [min, max]。
 * @param {Array<number>} arr - 数值数组
 * @param {{ min?: number, max?: number }} [fallbacks] - 空数组时的回退值
 * @returns {[number, number]}
 */
export function safeMinMax(arr, fallbacks = {}) {
  const minFallback = fallbacks.min ?? DEFAULT_MIN_FALLBACK
  const maxFallback = fallbacks.max ?? DEFAULT_MAX_FALLBACK
  if (!Array.isArray(arr) || arr.length === 0) {
    return [minFallback, maxFallback]
  }
  let min = arr[0]
  let max = arr[0]
  for (let i = 1; i < arr.length; i++) {
    const v = arr[i]
    if (v < min) {
      min = v
    }
    if (v > max) {
      max = v
    }
  }
  return [min, max]
}
