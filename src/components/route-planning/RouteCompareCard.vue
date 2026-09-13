<script setup>
/**
 * 多方案对比卡片。
 */
import { CheckCircle2, Clock, Download, Share2, Shield } from 'lucide-vue-next'

const props = defineProps({
  routes: { type: Array, default: () => [] },
  activeId: { type: String, default: '' },
})
const emit = defineEmits(['select', 'export', 'share'])

const fmtDur = (s) => {
  const m = parseInt(String(s ?? '0').match(/(\d+)/)?.[1] ?? 0, 10)
  return s
}

function riskColor(r) {
  if (r === 'low') return '#10b981'
  if (r === 'medium') return '#f59e0b'
  return '#ef4444'
}

function riskLabel(r) {
  return { low: '低风险', medium: '中风险', high: '高风险' }[r] ?? '未知'
}

function modeLabel(m) {
  return { fastest: '最快', safest: '最安全', shortest: '最短' }[m] ?? m
}
</script>

<template>
  <div class="compare-list">
    <div v-if="!routes.length" class="empty">未生成路径，请点击右上角"计算避障路径"。</div>
    <article
      v-for="r in routes"
      :key="r.id"
      class="card"
      :class="{ active: activeId === r.id, [r.riskLevel]: true }"
      @click="emit('select', r)"
    >
      <header>
        <strong>{{ modeLabel(r.mode) }}方案</strong>
        <span class="risk" :style="{ '--risk': riskColor(r.riskLevel) }">
          <Shield :size="12" />
          {{ riskLabel(r.riskLevel) }}
        </span>
        <CheckCircle2 v-if="activeId === r.id" :size="18" class="check" />
      </header>
      <dl>
        <div><dt>距离</dt><dd>{{ Math.round(r.distance / 100) / 10 }} km</dd></div>
        <div><dt>预计时间</dt><dd><Clock :size="12" /> {{ r.duration }}</dd></div>
        <div><dt>途经</dt><dd>{{ r.waypoints?.length ?? 0 }} 个关键点</dd></div>
        <div v-if="r.obstacleCount !== undefined"><dt>避障区</dt><dd>{{ r.obstacleCount }} 处</dd></div>
      </dl>
      <footer>
        <button class="ghost" @click.stop="emit('export', r)">
          <Download :size="13" /> GeoJSON
        </button>
        <button class="ghost" @click.stop="emit('share', r)">
          <Share2 :size="13" /> 分享
        </button>
      </footer>
    </article>
  </div>
</template>

<style scoped>
.compare-list { display: flex; flex-direction: column; gap: 10px; }
.empty { color: var(--dashboard-muted, #94a3b8); font-size: 13px; padding: 24px; text-align: center; }
.card {
  border: 1px solid var(--dashboard-border, #e2e8f0); border-radius: 12px;
  padding: 12px 14px; cursor: pointer; background: var(--dashboard-card-bg, #fff);
  transition: all 0.18s; display: flex; flex-direction: column; gap: 8px;
}
.card:hover { border-color: #5b8ef9; box-shadow: 0 4px 12px rgba(91,142,249,0.1); }
.card.active { border-color: #2563eb; background: linear-gradient(135deg, #eff6ff, #e0ecff); }
header { display: flex; align-items: center; gap: 8px; }
header strong { font-size: 14px; color: var(--dashboard-text, #1e293b); }
.risk {
  display: inline-flex; align-items: center; gap: 4px;
  font-size: 11px; padding: 2px 8px; border-radius: 999px;
  background: color-mix(in srgb, var(--risk) 12%, white);
  color: var(--risk);
}
.check { margin-left: auto; color: #2563eb; }
dl { display: grid; grid-template-columns: 1fr 1fr; gap: 4px 8px; margin: 0; font-size: 12px; }
dl > div { display: flex; align-items: center; gap: 4px; }
dt { color: var(--dashboard-muted, #6b7280); }
dd { margin: 0; color: var(--dashboard-text, #1e293b); font-weight: 500; }
footer { display: flex; gap: 6px; justify-content: flex-end; }
.ghost {
  background: transparent; color: var(--dashboard-muted, #475569);
  border: 1px solid var(--dashboard-border, #cbd5e1); padding: 4px 10px;
  border-radius: 6px; cursor: pointer; font-size: 12px;
  display: inline-flex; align-items: center; gap: 4px;
}
.ghost:hover { border-color: #5b8ef9; color: #2563eb; }
</style>
