/**
 * Smoke test for safeMin / safeMax / safeMinMax.
 *
 * 验证：
 *   1. 普通数组取最值正确
 *   2. 空数组 / null / undefined 返回 fallback
 *   3. 大数组（10k+）不抛 RangeError（这是修复的核心目标）
 *   4. NaN / 负数 / 浮点边界正确
 *
 * 跑法：node tests/smoke_safe_min_max.mjs
 */
import { safeMin, safeMax, safeMinMax } from '../src/utils/safeMinMax.js'

const cases = []

function expect(label, actual, expected) {
  const ok = Object.is(actual, expected) ||
    (Number.isNaN(actual) && Number.isNaN(expected))
  cases.push({ label, ok, actual, expected })
  console.log(`${ok ? '✓' : '✗'} ${label}  →  ${String(actual)}${ok ? '' : '   ❗expected=' + String(expected)}`)
}

// 1. 基本正确性
expect('safeMin 基本', safeMin([3, 1, 4, 1, 5, 9, 2, 6]), 1)
expect('safeMax 基本', safeMax([3, 1, 4, 1, 5, 9, 2, 6]), 9)
expect('safeMin 单元素', safeMin([42]), 42)
expect('safeMax 单元素', safeMax([42]), 42)

// 2. fallback 行为
expect('safeMin 空数组返回 Infinity', safeMin([]), Infinity)
expect('safeMax 空数组返回 -Infinity', safeMax([]), -Infinity)
expect('safeMin 自定义 fallback', safeMin([], 0), 0)
expect('safeMax 自定义 fallback', safeMax([], 100), 100)
expect('safeMin null 返回 fallback', safeMin(null, 0), 0)
expect('safeMax undefined 返回 fallback', safeMax(undefined, -1), -1)

// 3. 大数组（10k+）不抛 RangeError —— 核心回归测试
{
  const big = new Array(50000).fill(0).map((_, i) => i)
  expect('safeMin 50k 元素不抛错', safeMin(big), 0)
  expect('safeMax 50k 元素不抛错', safeMax(big), 49999)
}
{
  // 模拟轨迹场景：30s 采样 24h = 2880 点 + 7 天 = 20160 点
  const trajectory = new Array(20160).fill(0).map((_, i) => 118.5 + Math.sin(i / 100) * 0.01)
  expect('safeMin 轨迹场景 (20k 点)', Math.abs(safeMin(trajectory) - 118.49) < 0.0001, true)
}

// 4. safeMinMax 一次遍历
{
  const [min, max] = safeMinMax([3, 1, 4, 1, 5, 9, 2, 6])
  expect('safeMinMax 返回 tuple', JSON.stringify([min, max]), JSON.stringify([1, 9]))
}
{
  const [min, max] = safeMinMax([], { min: 0, max: 100 })
  expect('safeMinMax 空数组带自定义 fallback', JSON.stringify([min, max]), JSON.stringify([0, 100]))
}

// 5. 边界值
expect('safeMin 负数', safeMin([-1, -5, -3]), -5)
expect('safeMax 负数', safeMax([-1, -5, -3]), -1)
expect('safeMin 浮点', Math.abs(safeMin([0.1, 0.2, 0.05]) - 0.05) < 1e-10, true)
expect('safeMax 浮点', Math.abs(safeMax([0.1, 0.2, 0.05]) - 0.2) < 1e-10, true)

// 汇总
const passed = cases.filter((c) => c.ok).length
const failed = cases.length - passed
console.log(`\n--- ${passed}/${cases.length} passed, ${failed} failed ---`)
if (failed > 0) {
  process.exit(1)
}
