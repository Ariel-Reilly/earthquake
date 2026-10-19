<script setup>
/**
 * 国家气象 overlay 控制面板（卫星云图 / 雷达 / 24h 降水 / 24h 积雪 / 干旱）。
 *
 * 设计要点：
 * - 浮动在地图区域的右上角，可折叠，折叠后只露一个图标按钮（避免遮挡地图）
 * - 5 个 overlay 每个独立开关：未拉取过时显示「打开」按钮；加载中显示 spinner；
 *   成功显示「最新时间」并自动启用；失败显示「重试」
 * - 选中一个 overlay 后展示对应图例（色带）和透明度滑块，透明度立刻生效
 * - 通过 defineExpose 提供 triggerRefresh(kind) 给父组件用，便于在父层代码里强制重拉
 * - 组件内部只做 UI 状态管理；图源加载/缓存/OlImageLayer 控制权在父组件 mapController
 */
import { computed, reactive, ref } from 'vue'
import {
  CloudSun,
  Loader2,
  CloudRain,
  Snowflake,
  Sun,
  Radio,
  RefreshCcw,
  ChevronUp,
  ChevronDown,
  X,
  AlertTriangle,
  CheckCircle2,
} from 'lucide-vue-next'
import { WEATHER_LEGENDS } from '../services/_architecture/domain/weatherService'

const props = defineProps({
  /** 与父组件共享的状态：kind -> { enabled, ok, loading, url, timestamp, error } */
  overlays: { type: Object, required: true },
  /** 当前每个 overlay 的实际透明度（0-1），用于 UI 同步 */
  opacities: { type: Object, default: () => ({}) },
  /** 当前是否正在"刷新全部"（用于按钮 spinner） */
  refreshingAll: { type: Boolean, default: false },
})

const emit = defineEmits([
  /** 用户切换某个 overlay：父组件负责真正去拉数据并刷新 OL 图层 */
  'toggle',
  /** 强制刷新某个 overlay（点击"重试"或卡片自身刷新按钮） */
  'refresh',
  /** 透明度变化 */
  'opacity',
  /** 一键刷新全部 */
  'refresh-all',
])

const collapsed = ref(false)
const selectedKind = ref(null)

const KINDS = [
  { kind: 'satellite', label: '卫星云图', desc: 'FY-4A 中国区域 真彩合成', icon: CloudSun },
  { kind: 'radar', label: '雷达回波', desc: '全国基本反射率拼图', icon: Radio },
  { kind: 'precip24h', label: '24h 降水', desc: '24 小时累计降水量', icon: CloudRain },
  { kind: 'snow24h', label: '24h 积雪', desc: '24 小时积雪深度', icon: Snowflake },
  { kind: 'drought', label: '干旱监测', desc: '农业干旱综合监测', icon: Sun },
]

const legendEntries = computed(() => {
  const out = []
  for (const k of KINDS) {
    const legend = WEATHER_LEGENDS[k.kind]
    if (legend) out.push({ kind: k.kind, legend })
  }
  return out
})

function stateFor(kind) {
  return props.overlays[kind] ?? { enabled: false, loading: false, ok: false, url: '', timestamp: '', error: '' }
}

function onToggle(kind) {
  emit('toggle', kind)
}

function onRefreshOne(kind) {
  emit('refresh', kind)
}

function onOpacity(kind, ev) {
  const value = Number(ev.target.value)
  emit('opacity', kind, Math.max(0, Math.min(1, value)))
}

function onRefreshAll() {
  emit('refresh-all')
}

function pickKind(kind) {
  selectedKind.value = selectedKind.value === kind ? null : kind
}

function formatTimestamp(ts) {
  if (!ts) return '尚未拉取'
  // 兼容后端 "2026-09-07 14:30" 与前端 payload.time（已是 ISO 字符串）
  if (typeof ts === 'string' && ts.includes('T')) {
    const d = new Date(ts)
    if (Number.isNaN(d.getTime())) return ts
    return d.toLocaleString('zh-CN', { hour12: false })
  }
  return ts
}

defineExpose({
  triggerRefresh(kind) {
    onRefreshOne(kind)
  },
  collapse() {
    collapsed.value = true
  },
  expand() {
    collapsed.value = false
  },
})
</script>

<template>
  <div class="wx-overlay-panel" :class="{ collapsed }">
    <header class="wx-overlay-head" @click="collapsed = !collapsed">
      <div class="wx-overlay-title">
        <CloudSun :size="16" />
        <strong>国家气象 overlay</strong>
        <span class="wx-overlay-hint">{{ KINDS.filter(k => stateFor(k.kind).enabled).length || 0 }} 项叠加中</span>
      </div>
      <button class="wx-icon-btn" :title="collapsed ? '展开' : '收起'" @click.stop="collapsed = !collapsed">
        <ChevronDown v-if="!collapsed" :size="15" />
        <ChevronUp v-else :size="15" />
      </button>
    </header>

    <div v-show="!collapsed" class="wx-overlay-body">
      <ul class="wx-overlay-list">
        <li v-for="item in KINDS" :key="item.kind" :class="{ active: selectedKind === item.kind }">
          <div class="wx-overlay-row">
            <button
              class="wx-overlay-icon"
              :class="{ 'is-on': stateFor(item.kind).enabled && stateFor(item.kind).ok }"
              :title="stateFor(item.kind).enabled ? '关闭此 overlay' : '叠加到地图'"
              @click="onToggle(item.kind)"
            >
              <Loader2 v-if="stateFor(item.kind).loading" :size="15" class="spin" />
              <component v-else :is="item.icon" :size="15" />
            </button>
            <div class="wx-overlay-meta" @click="pickKind(item.kind)">
              <strong>{{ item.label }}</strong>
              <small>{{ formatTimestamp(stateFor(item.kind).timestamp) }}</small>
            </div>
            <div class="wx-overlay-state">
              <span v-if="stateFor(item.kind).loading" class="badge loading">
                <Loader2 :size="11" class="spin" />加载中
              </span>
              <span v-else-if="stateFor(item.kind).ok && stateFor(item.kind).enabled" class="badge ok">
                <CheckCircle2 :size="11" />已叠加
              </span>
              <span v-else-if="stateFor(item.kind).error" class="badge error" :title="stateFor(item.kind).error">
                <AlertTriangle :size="11" />失败
              </span>
              <button
                v-if="stateFor(item.kind).error"
                class="wx-mini-btn"
                title="重新加载"
                @click.stop="onRefreshOne(item.kind)"
              >
                <RefreshCcw :size="11" />
              </button>
            </div>
          </div>

          <!-- 展开：图例 + 透明度 -->
          <transition name="wx-legend">
            <div v-if="selectedKind === item.kind && stateFor(item.kind).ok" class="wx-overlay-legend">
              <div class="wx-legend-title">
                <span>{{ WEATHER_LEGENDS[item.kind]?.title }} · {{ WEATHER_LEGENDS[item.kind]?.unit }}</span>
                <button class="wx-mini-btn" title="收起" @click="selectedKind = null"><X :size="12" /></button>
              </div>
              <ul class="wx-legend-grades">
                <li v-for="(g, idx) in WEATHER_LEGENDS[item.kind]?.grades || []" :key="idx">
                  <i :style="{ background: g.color }"></i>
                  <span>{{ g.label }}</span>
                </li>
              </ul>
              <div class="wx-opacity-row">
                <label>透明度</label>
                <input
                  type="range"
                  min="0.2"
                  max="1"
                  step="0.05"
                  :value="opacities[item.kind] ?? 0.65"
                  @input="onOpacity(item.kind, $event)"
                />
                <em>{{ Math.round((opacities[item.kind] ?? 0.65) * 100) }}%</em>
              </div>
            </div>
          </transition>
        </li>
      </ul>

      <footer class="wx-overlay-foot">
        <span>数据源 · NSMC / NMC</span>
        <button
          class="wx-refresh-all"
          :disabled="refreshingAll"
          title="刷新全部最新一帧"
          @click="onRefreshAll"
        >
          <Loader2 v-if="refreshingAll" :size="13" class="spin" />
          <RefreshCcw v-else :size="13" />
          <em>{{ refreshingAll ? '刷新中' : '刷新全部' }}</em>
        </button>
      </footer>
    </div>
  </div>
</template>

<style scoped>
.wx-overlay-panel {
  position: absolute;
  top: 18px;
  right: 18px;
  z-index: 50;
  width: 320px;
  max-height: calc(100% - 36px);
  display: flex;
  flex-direction: column;
  color: var(--c-text);
  background: rgba(255, 255, 255, 0.96);
  border: 1px solid var(--c-separator);
  border-radius: var(--r-lg);
  box-shadow: 0 14px 36px rgba(31, 75, 69, 0.16);
  backdrop-filter: blur(12px);
  overflow: hidden;
  transition: width 0.3s cubic-bezier(.3,.8,.3,1);
}
.wx-overlay-panel.collapsed {
  width: 156px;
}
.wx-overlay-panel.collapsed .wx-overlay-hint {
  display: none;
}
.wx-overlay-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 11px 14px;
  color: var(--c-text);
  border-bottom: 1px solid var(--c-brand-mist);
  cursor: pointer;
  user-select: none;
}
.wx-overlay-panel.collapsed .wx-overlay-head {
  border-bottom: 0;
}
.wx-overlay-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 700;
}
.wx-overlay-title svg {
  color: var(--c-brand);
}
.wx-overlay-hint {
  margin-left: 6px;
  padding: 2px 7px;
  color: var(--c-ink-2);
  background: var(--c-success-bg);
  border-radius: var(--r-pill);
  font-size: 10px;
  font-weight: 500;
}
.wx-icon-btn {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 0;
  border-radius: var(--r-sm);
  background: #f3f7f5;
  color: var(--c-ink-2);
  cursor: pointer;
}
.wx-icon-btn:hover {
  background: var(--c-brand-mist);
  color: var(--c-brand);
}
.wx-overlay-body {
  display: flex;
  flex-direction: column;
  min-height: 0;
}
.wx-overlay-list {
  list-style: none;
  margin: 0;
  padding: 6px 8px;
  overflow-y: auto;
  flex: 1;
}
.wx-overlay-list li {
  border-radius: var(--r-md);
  margin: 3px 0;
  transition: background 0.18s;
}
.wx-overlay-list li.active {
  background: #f4f9f7;
}
.wx-overlay-row {
  display: grid;
  grid-template-columns: 30px 1fr auto;
  align-items: center;
  gap: 10px;
  padding: 6px 4px;
}
.wx-overlay-icon {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-md);
  background: var(--c-surface);
  color: var(--c-ink-2);
  cursor: pointer;
  transition: all 0.18s;
}
.wx-overlay-icon:hover {
  border-color: var(--c-brand);
  color: var(--c-brand);
}
.wx-overlay-icon.is-on {
  border-color: var(--c-brand);
  background: rgba(111,158,127,.08);
  color: var(--c-brand-hover);
  box-shadow: inset 0 0 0 1px rgba(111,158,127,.32);
}
.wx-overlay-icon .spin {
  animation: spin 1.1s linear infinite;
}
.wx-overlay-meta {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  cursor: pointer;
}
.wx-overlay-meta strong {
  color: var(--c-text);
  font-size: 12px;
  font-weight: 600;
}
.wx-overlay-meta small {
  color: var(--c-text-3);
  font-size: 10px;
}
.wx-overlay-state {
  display: flex;
  align-items: center;
  gap: 4px;
}
.badge {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 6px;
  border-radius: var(--r-pill);
  font-size: 9px;
  font-weight: 600;
  letter-spacing: 0.02em;
}
.badge.ok {
  color: var(--c-brand-ink);
  background: var(--c-success-bg);
}
.badge.loading {
  color: #6c4ad6;
  background: #efe9fb;
}
.badge.error {
  color: var(--c-danger-strong);
  background: var(--c-warning-bg);
}
.wx-mini-btn {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  padding: 0;
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--c-text-2);
  cursor: pointer;
}
.wx-mini-btn:hover {
  background: var(--c-brand-mist);
  color: var(--c-brand);
}
.wx-overlay-legend {
  margin: 0 6px 6px;
  padding: 8px 10px 10px;
  background: var(--c-surface);
  border: 1px solid var(--c-brand-mist);
  border-radius: var(--r-md);
}
.wx-legend-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  color: var(--c-ink-1);
  font-size: 10px;
  font-weight: 700;
  margin-bottom: 6px;
}
.wx-legend-grades {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.wx-legend-grades li {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--c-ink-2);
  font-size: 10px;
}
.wx-legend-grades i {
  width: 12px;
  height: 12px;
  display: inline-block;
  border: 1px solid rgba(0,0,0,0.08);
  border-radius: 3px;
}
.wx-opacity-row {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 8px;
  margin-top: 10px;
  color: var(--c-text-3);
  font-size: 10px;
}
.wx-opacity-row label {
  color: var(--c-ink-2);
  font-weight: 600;
}
.wx-opacity-row input[type="range"] {
  width: 100%;
  accent-color: var(--c-brand);
}
.wx-opacity-row em {
  font-style: normal;
  font-variant-numeric: tabular-nums;
  color: var(--c-ink-1);
}
.wx-overlay-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 8px 14px;
  border-top: 1px solid var(--c-brand-mist);
  color: var(--c-text-3);
  font-size: 10px;
}
.wx-refresh-all {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-ink-1);
  font-size: 11px;
  cursor: pointer;
  transition: all 0.18s;
}
.wx-refresh-all:hover:not(:disabled) {
  border-color: var(--c-brand);
  color: var(--c-brand);
  background: rgba(111,158,127,.08);
}
.wx-refresh-all:disabled {
  cursor: not-allowed;
  opacity: 0.6;
}
.wx-refresh-all .spin {
  animation: spin 1.1s linear infinite;
}
.spin {
  animation: spin 1.1s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.wx-legend-enter-active, .wx-legend-leave-active {
  transition: opacity 0.18s ease, max-height 0.24s ease, padding 0.24s ease;
  overflow: hidden;
}
.wx-legend-enter-from, .wx-legend-leave-to {
  opacity: 0;
  max-height: 0;
  padding-top: 0;
  padding-bottom: 0;
}
.wx-legend-enter-to, .wx-legend-leave-from {
  opacity: 1;
  max-height: 240px;
}
@media (max-width: 720px) {
  .wx-overlay-panel { width: calc(100% - 24px); right: 12px; top: 12px; }
  .wx-overlay-panel.collapsed { width: 152px; }
}
</style>