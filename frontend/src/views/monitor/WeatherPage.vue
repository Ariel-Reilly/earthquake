<script setup>
/**
 * 气象数据中心（独立页面 /dashboard/weather）。
 *
 * 与 OverviewPage 的关系：
 *   - OverviewPage 内的 weather overlay 面板是"边角小屏"，适合"态势感知时瞄一眼"
 *   - 本页是"专项工作台"，地图更大、可对比、可单独刷新 / 调透明度 / 切换数据源
 *
 * 数据来源：
 *   - 前端 weatherService 同时支持两种模式：VITE_USE_BACKEND=true 走 /api/weather/* 后端代理；
 *     否则前端直连 NSMC / NMC（带 CORS 代理回退）。本页两种模式都支持，无需用户配置。
 *
 * 性能：
 *   - 一次性预热缓存（getAllProducts），避免 6 张图依次刷新时各自探测
 *   - 切换 overlay 走 OL 已存在的 refreshWeatherOverlay API（mapController）
 */
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  AlertTriangle,
  CheckCircle2,
  CloudRain,
  CloudSun,
  Loader2,
  MapPin,
  Radio,
  RefreshCcw,
  Snowflake,
  Sun,
  Wind,
} from 'lucide-vue-next'
import {
  getAllProducts,
  getSatelliteCloudLatest,
  getRadarLatest,
  getPrecipitation24hLatest,
  getSnow24hLatest,
  getDroughtLatest,
  getSatelliteCloudDiskLatest,
  WEATHER_LEGENDS,
} from '../../services/weatherService'
import { createOpenLayersController } from '../../services/mapService'

const props = defineProps({
  currentTime: { type: String, default: '' },
  searchTerm: { type: String, default: '' },
  userRole: { type: String, default: 'admin' },
})
const emit = defineEmits(['notify'])

const loading = ref(true)
const refreshingAll = ref(false)
const fetchedAt = ref('')
const products = reactive({})
const activeKinds = ref([])
const mainMapContainer = ref(null)
let mainMapController = null

const opacities = reactive({
  satellite: 0.65,
  satelliteDisk: 0.65,
  radar: 0.65,
  precip24h: 0.65,
  snow24h: 0.65,
  drought: 0.65,
})

const KIND_CARDS = [
  { kind: 'satellite', label: '卫星云图', desc: 'FY-4A 中国区域真彩合成，监测云系演变', icon: CloudSun, color: 'blue' },
  { kind: 'satelliteDisk', label: '全圆盘云图', desc: '风云四号 A 全圆盘，覆盖东亚与西太平洋', icon: CloudSun, color: 'teal' },
  { kind: 'radar', label: '雷达回波', desc: '全国基本反射率拼图，6 分钟更新', icon: Radio, color: 'purple' },
  { kind: 'precip24h', label: '24h 降水', desc: '24 小时累计降水实况', icon: CloudRain, color: 'orange' },
  { kind: 'snow24h', label: '24h 积雪', desc: '24 小时积雪深度分布', icon: Snowflake, color: 'cyan' },
  { kind: 'drought', label: '干旱监测', desc: '农业干旱综合监测，每日 12 时发布', icon: Sun, color: 'amber' },
]

const KIND_LOADERS = {
  satellite: getSatelliteCloudLatest,
  satelliteDisk: getSatelliteCloudDiskLatest,
  radar: getRadarLatest,
  precip24h: getPrecipitation24hLatest,
  snow24h: getSnow24hLatest,
  drought: getDroughtLatest,
}

const availableCount = computed(() => Object.values(products).filter((p) => p && p.ok).length)
const totalCount = computed(() => KIND_CARDS.length)

async function bootstrap() {
  loading.value = true
  try {
    // 一次性预热：六个产品的最新帧（缓存友好，失败项标记 ok=false）
    const data = await getAllProducts()
    Object.assign(products, data.products)
    fetchedAt.value = data.fetchedAt
  } catch (e) {
    emit('notify', e?.message || '气象数据加载失败')
  } finally {
    loading.value = false
  }
}

async function createMainMap() {
  if (!mainMapContainer.value) return
  if (mainMapController) {
    mainMapController.map.updateSize()
    return
  }
  mainMapController = createOpenLayersController(mainMapContainer.value, {
    center: [107.21, 30.61],
    zoom: 5,
    baseMap: 'vector',
  })
}

async function toggleMainOverlay(kind) {
  if (!mainMapController) await createMainMap()
  const idx = activeKinds.value.indexOf(kind)
  if (idx >= 0) {
    activeKinds.value.splice(idx, 1)
    mainMapController.setWeatherOverlay(kind, false)
    emit('notify', `${KIND_CARDS.find((c) => c.kind === kind)?.label || kind} 已从主图移除`)
    return
  }
  activeKinds.value.push(kind)
  if (!products[kind]?.ok) {
    // 该产品尚未拉取，先拉
    try {
      products[kind] = { ...products[kind], loading: true, error: '' }
      const payload = await KIND_LOADERS[kind]()
      products[kind] = { ...payload, ok: !!payload?.url, url: payload?.url || '', error: '' }
      fetchedAt.value = new Date().toISOString()
    } catch (e) {
      products[kind] = { ok: false, error: e?.message || '加载失败', loading: false, label: KIND_CARDS.find((c) => c.kind === kind)?.label, description: '' }
      emit('notify', `${kind} 加载失败：${products[kind].error}`)
    }
  }
  await mainMapController.refreshWeatherOverlay(kind, () => products[kind])
  mainMapController.setWeatherOverlay(kind, true)
  mainMapController.setWeatherOverlayOpacity(kind, opacities[kind])
  emit('notify', `${KIND_CARDS.find((c) => c.kind === kind)?.label} 已叠加`)
}

function onOpacity(kind, ev) {
  const v = Number(ev.target.value)
  opacities[kind] = Math.max(0.2, Math.min(1, v))
  mainMapController?.setWeatherOverlayOpacity?.(kind, opacities[kind])
}

async function refreshOne(kind) {
  products[kind] = { ...products[kind], loading: true, error: '' }
  try {
    const payload = await KIND_LOADERS[kind]()
    products[kind] = { ...payload, ok: !!payload?.url, url: payload?.url || '', loading: false, error: '' }
    fetchedAt.value = new Date().toISOString()
    if (activeKinds.value.includes(kind)) {
      await mainMapController.refreshWeatherOverlay(kind, () => products[kind])
      mainMapController.setWeatherOverlay(kind, true)
    }
    emit('notify', `${KIND_CARDS.find((c) => c.kind === kind)?.label} 已刷新`)
  } catch (e) {
    products[kind] = { ...products[kind], ok: false, loading: false, error: e?.message || '刷新失败' }
    emit('notify', `${kind} 刷新失败：${products[kind].error}`)
  }
}

async function refreshAll() {
  if (refreshingAll.value) return
  refreshingAll.value = true
  try {
    await Promise.allSettled(KIND_CARDS.map((c) => refreshOne(c.kind).catch(() => null)))
    emit('notify', '已刷新全部气象产品')
  } finally {
    refreshingAll.value = false
  }
}

function formatTimestamp(ts) {
  if (!ts) return '尚未拉取'
  if (typeof ts === 'string' && ts.includes('T')) {
    const d = new Date(ts)
    if (Number.isNaN(d.getTime())) return ts
    return d.toLocaleString('zh-CN', { hour12: false })
  }
  return ts
}

onMounted(async () => {
  await nextTick()
  await bootstrap()
  await createMainMap()
})
onBeforeUnmount(() => {
  if (mainMapController) {
    mainMapController.destroy()
    mainMapController = null
  }
})
</script>

<template>
  <div class="weather-page">
    <header class="wp-head panel">
      <div class="wp-head-left">
        <h1>
          <CloudSun :size="22" />
          国家气象数据中心
        </h1>
        <p>
          数据源 · 国家卫星气象中心（NSMC）风云四号 + 中央气象台（NMC）预渲染产品 ·
          共 {{ totalCount }} 项产品 · 可用 {{ availableCount }} 项
        </p>
      </div>
      <div class="wp-head-right">
        <div class="wp-fetched">
          <small>最新拉取</small>
          <strong>{{ fetchedAt ? new Date(fetchedAt).toLocaleString('zh-CN', { hour12: false }) : '—' }}</strong>
        </div>
        <button class="wp-refresh" :disabled="refreshingAll" @click="refreshAll">
          <Loader2 v-if="refreshingAll" :size="14" class="spin" />
          <RefreshCcw v-else :size="14" />
          {{ refreshingAll ? '刷新中…' : '刷新全部' }}
        </button>
      </div>
    </header>

    <div v-if="loading" class="wp-loading">
      <Loader2 :size="18" class="spin" />
      正在拉取国家气象数据…
    </div>

    <section v-else class="wp-main">
      <!-- 左侧：主地图 -->
      <div class="wp-map-wrap panel">
        <header class="wp-map-head">
          <div>
            <h2>主图叠加</h2>
            <p>勾选下方卡片，把对应产品的最新一帧叠加到主图</p>
          </div>
          <div class="wp-map-tools">
            <span class="wp-pill">
              <MapPin :size="12" />
              {{ activeKinds.length }} 个 overlay
            </span>
          </div>
        </header>
        <div class="wp-map-canvas">
          <div ref="mainMapContainer" class="wp-map"></div>
          <div class="wp-map-legend">
            <h3>当前叠加图层</h3>
            <p v-if="!activeKinds.length" class="wp-empty">点击下方卡片即可叠加到主图</p>
            <ul v-else>
              <li v-for="kind in activeKinds" :key="kind">
                <strong>{{ KIND_CARDS.find((c) => c.kind === kind)?.label || kind }}</strong>
                <span>{{ formatTimestamp(products[kind]?.timestamp) }}</span>
                <div class="wp-legend-grade">
                  <ul v-if="WEATHER_LEGENDS[kind]">
                    <li v-for="(g, idx) in WEATHER_LEGENDS[kind].grades" :key="idx">
                      <i :style="{ background: g.color }"></i>{{ g.label }}
                    </li>
                  </ul>
                </div>
                <div class="wp-opacity">
                  <small>透明度</small>
                  <input
                    type="range"
                    min="0.2"
                    max="1"
                    step="0.05"
                    :value="opacities[kind]"
                    @input="onOpacity(kind, $event)"
                  />
                  <em>{{ Math.round(opacities[kind] * 100) }}%</em>
                </div>
                <button class="wp-mini" @click="toggleMainOverlay(kind)">移除</button>
              </li>
            </ul>
          </div>
        </div>
      </div>

      <!-- 右侧：六个产品卡片 -->
      <div class="wp-grid">
        <article
          v-for="card in KIND_CARDS"
          :key="card.kind"
          class="wp-card panel"
          :class="{ 'is-on': activeKinds.includes(card.kind) }"
        >
          <header class="wp-card-head">
            <div :class="['wp-card-icon', card.color]">
              <component :is="card.icon" :size="16" />
            </div>
            <div>
              <strong>{{ card.label }}</strong>
              <small>{{ card.desc }}</small>
            </div>
            <button class="wp-card-refresh" :title="`刷新 ${card.label}`" @click="refreshOne(card.kind)">
              <Loader2 v-if="products[card.kind]?.loading" :size="13" class="spin" />
              <RefreshCcw v-else :size="13" />
            </button>
          </header>

          <div class="wp-card-thumb">
            <img
              v-if="products[card.kind]?.ok && products[card.kind]?.url"
              :src="products[card.kind].url"
              :alt="card.label"
              loading="lazy"
              referrerpolicy="no-referrer"
            />
            <div v-else-if="products[card.kind]?.loading" class="wp-card-skeleton">
              <Loader2 :size="20" class="spin" />
              <small>正在探测最新一帧</small>
            </div>
            <div v-else-if="products[card.kind]?.error" class="wp-card-error">
              <AlertTriangle :size="22" />
              <span>{{ products[card.kind].error }}</span>
              <button class="wp-mini" @click="refreshOne(card.kind)">重试</button>
            </div>
            <div v-else class="wp-card-empty">
              <Wind :size="22" />
              <span>尚未拉取</span>
            </div>
          </div>

          <footer class="wp-card-foot">
            <div class="wp-card-meta">
              <span v-if="products[card.kind]?.ok" class="wp-card-time">
                <CheckCircle2 :size="11" />
                {{ formatTimestamp(products[card.kind].timestamp) }}
              </span>
              <span v-else-if="products[card.kind]?.loading" class="wp-card-time muted">
                <Loader2 :size="11" class="spin" />加载中
              </span>
              <span v-else class="wp-card-time muted">等待拉取</span>
              <small class="wp-card-source">源 · {{ products[card.kind]?.source || 'NSMC/NMC' }}</small>
            </div>
            <button
              class="wp-card-toggle"
              :class="{ active: activeKinds.includes(card.kind) }"
              @click="toggleMainOverlay(card.kind)"
            >
              {{ activeKinds.includes(card.kind) ? '已在主图' : '叠加到主图' }}
            </button>
          </footer>
        </article>
      </div>
    </section>
  </div>
</template>

<style scoped>
.weather-page {
  display: flex;
  flex-direction: column;
  gap: 18px;
}
.wp-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 22px;
  gap: 16px;
}
.wp-head h1 {
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 0;
  color: var(--c-text);
  font-size: 19px;
  font-weight: 700;
  letter-spacing: -.02em;
}
.wp-head h1 svg { color: var(--c-brand); }
.wp-head p {
  margin: 6px 0 0;
  color: var(--c-text-3);
  font-size: 11px;
}
.wp-head-right {
  display: flex;
  align-items: center;
  gap: 14px;
}
.wp-fetched {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  color: var(--c-ink-2);
  font-size: 10px;
}
.wp-fetched strong {
  color: var(--c-text);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}
.wp-refresh {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-md);
  background: var(--c-surface);
  color: var(--c-ink-1);
  font-size: 12px;
  cursor: pointer;
  transition: all .18s;
}
.wp-refresh:hover:not(:disabled) {
  border-color: var(--c-brand);
  color: var(--c-brand);
  background: var(--c-brand-light);
}
.wp-refresh:disabled { cursor: not-allowed; opacity: .6; }
.wp-loading {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 38px;
  border: 1px dashed var(--c-separator);
  border-radius: var(--r-lg);
  background: var(--glass-thin);
  color: var(--c-ink-2);
  font-size: 13px;
  justify-content: center;
}
.wp-loading .spin { animation: spin 1.1s linear infinite; color: var(--c-brand); }

.wp-main {
  display: grid;
  grid-template-columns: 1.4fr 1fr;
  gap: 18px;
  align-items: stretch;
}
.wp-map-wrap {
  display: flex;
  flex-direction: column;
  min-height: 560px;
}
.wp-map-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
  border-bottom: 1px solid var(--c-brand-mist);
}
.wp-map-head h2 {
  margin: 0;
  color: var(--c-text);
  font-size: 14px;
  font-weight: 700;
  letter-spacing: -.01em;
}
.wp-map-head p {
  margin: 4px 0 0;
  color: var(--c-text-3);
  font-size: 10px;
}
.wp-map-tools {
  display: flex;
  gap: 6px;
}
.wp-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px 9px;
  border-radius: var(--r-pill);
  background: var(--c-success-bg);
  color: var(--c-brand-ink);
  font-size: 11px;
  font-weight: 600;
}
.wp-map-canvas {
  position: relative;
  flex: 1;
  min-height: 480px;
  display: flex;
}
.wp-map {
  flex: 1;
  min-height: 480px;
}
.wp-map-legend {
  width: 240px;
  padding: 14px 16px;
  border-left: 1px solid var(--c-brand-mist);
  background: var(--c-surface-2);
  overflow-y: auto;
}
.wp-map-legend h3 {
  margin: 0 0 6px;
  color: var(--c-text);
  font-size: 12px;
  font-weight: 700;
}
.wp-map-legend p.wp-empty {
  margin: 12px 0;
  color: var(--c-text-3);
  font-size: 11px;
}
.wp-map-legend ul {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.wp-map-legend li {
  padding: 10px 12px;
  border: 1px solid var(--c-brand-mist);
  border-radius: var(--r-md);
  background: var(--c-surface);
}
.wp-map-legend strong {
  color: var(--c-text);
  font-size: 12px;
  font-weight: 600;
}
.wp-map-legend span {
  display: block;
  color: var(--c-text-3);
  font-size: 10px;
  margin-top: 2px;
}
.wp-legend-grade ul {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;
}
.wp-legend-grade li {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--c-ink-2);
  font-size: 10px;
}
.wp-legend-grade i {
  width: 12px;
  height: 12px;
  display: inline-block;
  border: 1px solid var(--c-border-2);
  border-radius: 3px;
}
.wp-opacity {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  color: var(--c-text-3);
  font-size: 10px;
}
.wp-opacity input[type="range"] { width: 100%; accent-color: var(--c-brand); }
.wp-opacity em { font-style: normal; font-variant-numeric: tabular-nums; color: var(--c-ink-1); }
.wp-mini {
  margin-top: 8px;
  padding: 3px 9px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-ink-2);
  font-size: 10px;
  cursor: pointer;
}
.wp-mini:hover { color: var(--c-brand); border-color: var(--c-brand); }

.wp-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
  align-content: start;
}
.wp-card {
  display: flex;
  flex-direction: column;
  padding: 14px 16px 16px;
  gap: 10px;
  transition: border-color .2s, box-shadow .2s;
}
.wp-card.is-on {
  border-color: var(--c-brand);
  box-shadow: 0 10px 24px rgba(111, 158, 127, .12);
}
.wp-card-head {
  display: grid;
  grid-template-columns: 30px 1fr auto;
  align-items: center;
  gap: 10px;
}
.wp-card-icon {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  border-radius: var(--r-md);
  background: var(--c-brand-mist);
  color: var(--c-brand);
}
.wp-card-icon.blue { background: var(--sys-blue-soft); color: var(--sys-blue-deep); }
.wp-card-icon.teal { background: var(--c-success-bg); color: var(--c-brand-ink); }
.wp-card-icon.purple { background: var(--sys-purple-soft); color: var(--sys-indigo); }
.wp-card-icon.orange { background: var(--c-warning-bg); color: var(--c-warning-strong); }
.wp-card-icon.cyan { background: var(--c-info-bg); color: var(--sys-blue-deep); }
.wp-card-icon.amber { background: var(--c-warning-bg); color: var(--c-warning-ink); }
.wp-card-head strong {
  color: var(--c-text);
  font-size: 13px;
  font-weight: 600;
}
.wp-card-head small {
  display: block;
  color: var(--c-text-3);
  font-size: 10px;
  margin-top: 1px;
}
.wp-card-refresh {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-ink-2);
  cursor: pointer;
}
.wp-card-refresh:hover { color: var(--c-brand); border-color: var(--c-brand); }
.wp-card-thumb {
  position: relative;
  aspect-ratio: 16 / 9;
  border: 1px solid var(--c-brand-mist);
  border-radius: var(--r-md);
  overflow: hidden;
  background: var(--c-brand-mist);
  display: grid;
  place-items: center;
}
.wp-card-thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.wp-card-skeleton,
.wp-card-error,
.wp-card-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: var(--c-text-3);
  font-size: 11px;
}
.wp-card-error { color: var(--c-danger-strong); }
.wp-card-error .wp-mini { margin-top: 4px; }
.wp-card-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
}
.wp-card-meta {
  display: flex;
  flex-direction: column;
  gap: 1px;
  color: var(--c-ink-2);
}
.wp-card-time {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--c-brand-ink);
}
.wp-card-time.muted { color: var(--c-text-3); }
.wp-card-source {
  color: var(--c-text-3);
  font-size: 10px;
}
.wp-card-toggle {
  padding: 6px 12px;
  border: 1px solid var(--c-separator);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  color: var(--c-ink-2);
  font-size: 11px;
  cursor: pointer;
}
.wp-card-toggle:hover { color: var(--c-brand); border-color: var(--c-brand); }
.wp-card-toggle.active {
  background: var(--c-brand-light);
  color: var(--c-brand-hover);
  border-color: var(--c-brand);
  box-shadow: inset 0 0 0 1px rgba(111,158,127,.25);
}

.spin { animation: spin 1.1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

@media (max-width: 1100px) {
  .wp-main { grid-template-columns: 1fr; }
  .wp-map-canvas { flex-direction: column; }
  .wp-map { min-height: 380px; }
  .wp-map-legend { width: 100%; border-left: 0; border-top: 1px solid var(--c-brand-mist); max-height: 240px; }
}
@media (max-width: 720px) {
  .wp-grid { grid-template-columns: 1fr; }
}
</style>