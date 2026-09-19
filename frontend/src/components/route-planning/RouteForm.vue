<script setup>
/**
 * 起终点输入 + 避障范围配置。
 * - 起点 / 终点：地图点选 / 地址搜索 / 资源点
 * - 避障：自动加载当前灾害影响区域，可手动绘制额外区域
 * - 计算：提交后端路径规划接口
 */
import { computed, reactive, ref } from 'vue'
import { Loader2, MapPin, ShieldAlert, Search, Send } from 'lucide-vue-next'

const props = defineProps({
  obstacles: { type: Array, default: () => [] },
  resources: { type: Array, default: () => [] },
  loading: { type: Boolean, default: false },
})
const emit = defineEmits(['update:start', 'update:end', 'add-obstacle', 'calculate', 'pick-mode'])

const start = reactive({ lng: 120.15, lat: 30.28, label: '' })
const end   = reactive({ lng: 120.35, lat: 30.45, label: '' })
const customObstacles = reactive([])
const modes = reactive({ fastest: true, safest: true, shortest: true })
const startSearch = ref('')
const endSearch = ref('')
const startPickerActive = ref(false)
const endPickerActive = ref(false)

function applyPoint(target, point) {
  target.lng = point.lng ?? target.lng
  target.lat = point.lat ?? target.lat
  target.label = point.label ?? ''
}

function pickResource(target, res) {
  applyPoint(target, { lng: res.lng, lat: res.lat, label: res.name })
}

function addCustomObstacle() {
  const center = {
    lng: ((start.lng + end.lng) / 2),
    lat: ((start.lat + end.lat) / 2),
  }
  customObstacles.push({
    id: `CUSTOM-${Date.now()}`,
    type: 'polygon',
    name: `临时避障 ${customObstacles.length + 1}`,
    coordinates: [
      [center.lng - 0.01, center.lat - 0.008],
      [center.lng + 0.01, center.lat - 0.008],
      [center.lng + 0.012, center.lat + 0.008],
      [center.lng - 0.012, center.lat + 0.008],
      [center.lng - 0.01, center.lat - 0.008],
    ],
  })
  emit('add-obstacle', customObstacles[customObstacles.length - 1])
}

function removeCustom(id) {
  const idx = customObstacles.findIndex((o) => o.id === id)
  if (idx >= 0) customObstacles.splice(idx, 1)
}

const canSubmit = computed(() =>
  !!start.lng && !!end.lng && (modes.fastest || modes.safest || modes.shortest)
)

function emitCalculate() {
  if (!canSubmit.value) return
  emit('calculate', {
    start: { lng: start.lng, lat: start.lat },
    end:   { lng: end.lng,   lat: end.lat },
    obstacles: [...props.obstacles, ...customObstacles],
    mode: Object.keys(modes).filter((m) => modes[m]),
  })
}
</script>

<template>
  <form class="route-form" @submit.prevent="emitCalculate">
    <section class="row">
      <label><MapPin :size="14" /> 起点</label>
      <div class="num-row">
        <input v-model.number="start.lng" type="number" step="0.0001" placeholder="经度" />
        <input v-model.number="start.lat" type="number" step="0.0001" placeholder="纬度" />
      </div>
      <input v-model="start.label" placeholder="起点名称（可空）" />
      <details class="picker">
        <summary>从资源点选择</summary>
        <ul>
          <li v-for="r in resources" :key="r.id" @click.prevent="pickResource(start, r)">
            <strong>{{ r.name }}</strong>
            <small>{{ r.lng.toFixed(3) }}, {{ r.lat.toFixed(3) }}</small>
          </li>
        </ul>
      </details>
    </section>

    <section class="row">
      <label><MapPin :size="14" class="end-icon" /> 终点（灾区）</label>
      <div class="num-row">
        <input v-model.number="end.lng" type="number" step="0.0001" placeholder="经度" />
        <input v-model.number="end.lat" type="number" step="0.0001" placeholder="纬度" />
      </div>
      <input v-model="end.label" placeholder="终点名称（可空）" />
      <details class="picker">
        <summary>从受灾点选择</summary>
        <ul>
          <li v-for="r in resources" :key="`d-${r.id}`" @click.prevent="pickResource(end, r)">
            <strong>{{ r.name }}</strong>
            <small>{{ r.lng.toFixed(3) }}, {{ r.lat.toFixed(3) }}</small>
          </li>
        </ul>
      </details>
    </section>

    <section class="row">
      <label><ShieldAlert :size="14" /> 避障范围（自动加载当前灾害影响区域）</label>
      <ul class="obstacle-list">
        <li v-for="o in obstacles" :key="o.id">
          <span class="dot" :style="{ background: levelColor(o.level) }"></span>
          <strong>{{ o.name }}</strong>
          <small>{{ o.level }}</small>
        </li>
        <li v-if="customObstacles.length" class="custom">
          <strong>本会话新增避障</strong>
        </li>
        <li v-for="o in customObstacles" :key="o.id">
          <span class="dot custom"></span>
          <strong>{{ o.name }}</strong>
          <button type="button" class="del" @click="removeCustom(o.id)">×</button>
        </li>
      </ul>
      <button type="button" class="ghost" @click="addCustomObstacle">
        + 新增临时避障（中心点为起终点中点）
      </button>
    </section>

    <section class="row">
      <label>需要计算的方案</label>
      <div class="modes">
        <label :class="{ active: modes.fastest }">
          <input type="checkbox" v-model="modes.fastest" />
          <strong>最快</strong>
          <small>优先通行时间</small>
        </label>
        <label :class="{ active: modes.safest }">
          <input type="checkbox" v-model="modes.safest" />
          <strong>最安全</strong>
          <small>优先规避风险</small>
        </label>
        <label :class="{ active: modes.shortest }">
          <input type="checkbox" v-model="modes.shortest" />
          <strong>最短</strong>
          <small>优先距离</small>
        </label>
      </div>
    </section>

    <button type="submit" class="primary" :disabled="!canSubmit || loading">
      <Loader2 v-if="loading" class="spin" :size="14" />
      <Send v-else :size="14" />
      计算避障路径
    </button>
  </form>
</template>

<script>
function levelColor(level) {
  if (!level) return '#94a3b8'
  if (level.includes('红')) return '#dc2626'
  if (level.includes('橙')) return '#ea580c'
  if (level.includes('黄')) return '#ca8a04'
  return '#2563eb'
}
export default { name: 'RouteForm' }
</script>

<style scoped>
.route-form { display: flex; flex-direction: column; gap: 14px; }
.row { display: flex; flex-direction: column; gap: 6px; }
.row label { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; color: var(--dashboard-muted, #475569); font-weight: 500; }
.row label .end-icon { color: #ef4444; }
.num-row { display: grid; grid-template-columns: 1fr 1fr; gap: 6px; }
input {
  border: 1px solid var(--dashboard-border, #e2e8f0); border-radius: 8px;
  padding: 7px 10px; font-size: 13px; background: var(--dashboard-input-bg, #fff);
  color: var(--dashboard-text, #1e293b);
}
input:focus { outline: none; border-color: #5b8ef9; box-shadow: 0 0 0 3px rgba(91,142,249,0.15); }
.picker summary {
  cursor: pointer; color: var(--dashboard-muted, #6b7280); font-size: 12px;
  padding: 4px 0;
}
.picker ul { list-style: none; padding: 0; margin: 0; max-height: 120px; overflow: auto; }
.picker li {
  display: flex; justify-content: space-between; align-items: center;
  padding: 6px 8px; border: 1px dashed var(--dashboard-border, #e2e8f0); border-radius: 6px;
  margin-bottom: 4px; cursor: pointer; font-size: 12px;
}
.picker li:hover { border-color: #5b8ef9; background: #eef4ff; }
.obstacle-list { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 4px; }
.obstacle-list li {
  display: inline-flex; align-items: center; gap: 6px;
  border: 1px solid var(--dashboard-border, #eef2f7); border-radius: 6px;
  padding: 5px 10px; font-size: 12px; background: var(--dashboard-muted-bg, #f8fafc);
}
.dot { width: 10px; height: 10px; border-radius: 50%; }
.dot.custom { background: repeating-linear-gradient(45deg, #ef4444, #ef4444 2px, #fee2e2 2px, #fee2e2 4px); }
.obstacle-list .custom { background: #fef3c7; }
.del { background: none; border: none; color: #94a3b8; cursor: pointer; padding: 0 4px; font-size: 14px; }
.del:hover { color: #ef4444; }
.modes { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.modes label {
  display: flex; flex-direction: column; gap: 2px;
  border: 1px solid var(--dashboard-border, #e2e8f0); border-radius: 8px;
  padding: 8px 10px; cursor: pointer; font-size: 12px;
  background: var(--dashboard-muted-bg, #f8fafc);
}
.modes label.active { border-color: #5b8ef9; background: linear-gradient(135deg, #eff6ff, #e0ecff); }
.modes strong { font-size: 13px; color: var(--dashboard-text, #1e293b); }
.modes small { font-size: 11px; color: var(--dashboard-muted, #6b7280); }
.primary {
  background: linear-gradient(135deg, #5b8ef9, #3b82f6); color: white;
  border: none; padding: 9px 18px; border-radius: 8px;
  display: inline-flex; align-items: center; gap: 6px; cursor: pointer; font-size: 14px;
  justify-content: center;
}
.primary:disabled { opacity: 0.5; cursor: not-allowed; }
.ghost {
  background: transparent; color: var(--dashboard-muted, #475569);
  border: 1px dashed var(--dashboard-border, #cbd5e1); padding: 6px 10px;
  border-radius: 8px; cursor: pointer; font-size: 12px;
}
.ghost:hover { border-color: #5b8ef9; color: #2563eb; }
.spin { animation: rotate 1s linear infinite; }
@keyframes rotate { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
