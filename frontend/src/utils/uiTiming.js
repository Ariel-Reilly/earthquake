/**
 * 骨架屏最短展示时间。
 *
 * 接口返回过快时，如果立刻撤掉骨架，会出现「闪一下」的视觉抖动；
 * 统一保留约 280ms，让加载态有稳定、可感知的过渡。
 */
const SKELETON_MIN_MS = 280

/**
 * 返回一个在指定时间后 resolve 的 Promise。
 * @param {number} [ms=SKELETON_MIN_MS] 最短展示毫秒数
 * @returns {Promise<void>}
 */
export function waitForSkeleton(ms = SKELETON_MIN_MS) {
  return new Promise((resolve) => {
    window.setTimeout(resolve, ms)
  })
}
