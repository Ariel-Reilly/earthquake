<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Activity, AlertTriangle, CloudRain, Database, Gauge, Globe2, ListChecks, MapPin, MoreHorizontal, Radio, Route, SlidersHorizontal, UsersRound, Waves, ChevronDown, ChevronRight, X } from 'lucide-vue-next'
import { dashboardApi } from '../services/dashboardApi'
import { getRealtimeWeather, getWeatherAlertLevel } from '../services/weatherApi'
import { createOpenLayersController } from '../services/mapService'
import { getRealtimeState, onRealtimeUpdate, startRealtimeSync, stopRealtimeSync } from '../services/realtimeService'
import PersonnelTooltip from '../components/PersonnelTooltip.vue'
import PersonnelRouteDrawer from '../components/PersonnelRouteDrawer.vue'

const props = defineProps({
  currentTime: { type: String, required: true },
  userRole: { type: String, default: 'admin' },
})

const emit = defineEmits(['notify', 'open-task-modal'])
const router = useRouter()

const overviewStats = ref([])
const healthStats = ref([])
const warnings = ref([])
const hazards = ref([])
const devices = ref([])
const personnel = ref([])
const loading = ref(true)
const riskFilter = ref('全部')
const activeWarnings = computed(() => warnings.value.filter((warning) => !['已关闭', '已忽略', '已解除'].includes(warning.status)))
const selectedPoint = ref(null)
const showAllWarnings = ref(false)
const weather = ref(null)
const weatherLoading = ref(true)
const weatherError = ref('')
const mapContainer = ref(null)
const mapController = ref(null)
const mapMode = ref('vector')
const hoveredPersonnel = ref(null)
const activePersonnel = ref(null)
const personnelTooltipVisible = ref(false)
const personnelRouteVisible = ref(false)
const refreshingViewport = ref(false)
const viewportCache = ref({ bbox: '', zoom: null })
const viewportState = ref({ hazards: [], devices: [], warnings: [] })

const filteredPoints = computed(() => hazards.value.filter((point) => riskFilter.value === '全部' || point.level === riskFilter.value))
const visibleWarnings = computed(() => warnings.value.slice(0, showAllWarnings.value ? warnings.value.length : 3))
const weatherAlertLevel = computed(() => getWeatherAlertLevel(weather.value))
const peopleModalOpen = ref(false)
const onlinePersonnel = computed(() => personnel.value.filter((person) => person.state === '在线'))
const offlinePersonnel = computed(() => personnel.value.filter((person) => person.state !== '在线'))
const sortedPersonnel = computed(() => [...onlinePersonnel.value, ...offlinePersonnel.value])
const dutyAvatarList = computed(() => sortedPersonnel.value.slice(0, 3))
const dutyMoreCount = computed(() => Math.max(0, personnel.value.length - dutyAvatarList.value.length))
const dutyTeamStats = computed(() => {
  const groups = {}
  for (const person of personnel.value) {
    const team = person.team || '综合巡查组'
    groups[team] = (groups[team] ?? 0) + 1
  }
  return Object.entries(groups).map(([name, count]) => ({ name, count }))
})

const roleOverview = computed(() => {
  if (props.userRole === 'maintenance') {
    return [
      { label: '在线监测设备', value: String(Math.max(1, healthStats.value.length ? 1486 : 0)).padStart(3, '0'), suffix: '台', change: '↑ 2.8% 较上月', color: 'blue' },
      { label: '离线设备', value: '01', suffix: '台', change: '需巡检', color: 'orange' },
      { label: '设备工单', value: '12', suffix: '单', change: '↑ 3 单 较昨日', color: 'teal' },
      { label: '今日维护完成率', value: '97.2', suffix: '%', change: '↑ 1.8% 较昨日', color: 'purple' },
    ]
  }
  if (props.userRole === 'attendant') {
    return [
      { label: '监测灾害点', value: String(hazards.value.length || 128).padStart(3, '0'), suffix: '处', change: '↑ 4.2% 较上月', color: 'teal' },
      { label: '当前预警事件', value: String(activeWarnings.value.length || 5).padStart(2, '0'), suffix: '起', change: '↑ 1 起 较昨日', color: 'orange' },
      { label: '待处理任务', value: '10', suffix: '项', change: '持续跟进', color: 'blue' },
      { label: '今日巡检完成率', value: '92.4', suffix: '%', change: '↑ 6.3% 较昨日', color: 'purple' },
    ]
  }
  return overviewStats.value
})

const taskProgress = computed(() => {
  if (props.userRole === 'maintenance') {
    return { done: 18, total: 22, percent: Math.round((18 / 22) * 100), label: '设备维护已完成', pending: 4 }
  }
  return { done: 124, total: 134, percent: Math.round((124 / 134) * 100), label: '任务已完成', pending: 10 }
})

async function loadWeather() {
  weatherLoading.value = true
  weatherError.value = ''
  try {
    const target = hazards.value[0] ?? { lat: 30.61, lon: 107.21, owner: '龙川县' }
    weather.value = await getRealtimeWeather({ lat: target.lat, lon: target.lon, location: target.owner })
  } catch (error) {
    weatherError.value = error instanceof Error ? error.message : '天气加载失败'
  } finally {
    weatherLoading.value = false
  }
}

function syncMapLayers() {
  if (!mapController.value?.map) return
  mapController.value.setHazards(filteredPoints.value)
  mapController.value.setDevices(devices.value)
  mapController.value.setWarnings(activeWarnings.value)
  mapController.value.setPersonnel(personnel.value)
}

function createOrResizeMap() {
  if (!mapContainer.value) return
  if (!mapController.value) {
    mapController.value = createOpenLayersController(mapContainer.value, { center: [107.21, 30.61], zoom: 10, baseMap: 'vector' })
    bindMapEvents()
  }
  mapController.value.map.updateSize()
  syncMapLayers()
}

function bindMapEvents() {
  if (!mapController.value?.map) return
  const map = mapController.value.map
  map.on('moveend', requestViewportData)
  map.on('pointermove', handlePointerMove)
  map.on('click', handleMapClick)
}

function unbindMapEvents() {
  if (!mapController.value?.map) return
  const map = mapController.value.map
  map.un('moveend', requestViewportData)
  map.un('pointermove', handlePointerMove)
  map.un('click', handleMapClick)
}

function buildViewportParams() {
  if (!mapController.value?.map) return null
  const map = mapController.value.map
  const size = map.getSize()
  if (!size) return null
  const extent = map.getView().calculateExtent(size)
  const bbox = `${extent[0].toFixed(6)},${extent[1].toFixed(6)},${extent[2].toFixed(6)},${extent[3].toFixed(6)}`
  return { bbox, zoom: Math.round(map.getView().getZoom() ?? 10) }
}

async function requestViewportData() {
  const params = buildViewportParams()
  if (!params) return
  const nextKey = `${params.bbox}:${params.zoom}:${riskFilter.value}`
  if (viewportCache.value.bbox === nextKey) return
  viewportCache.value = { bbox: nextKey, zoom: params.zoom }
  refreshingViewport.value = true
  try {
    const [hazardData, deviceData, warningData] = await Promise.all([
      dashboardApi.getHazardsByViewport({ bbox: params.bbox, zoom: params.zoom, level: riskFilter.value === '全部' ? '' : riskFilter.value }),
      dashboardApi.getDevicesByViewport({ bbox: params.bbox, zoom: params.zoom }),
      dashboardApi.getWarningsByViewport({ bbox: params.bbox, zoom: params.zoom, status: '' }),
    ])
    viewportState.value = { hazards: hazardData, devices: deviceData, warnings: warningData }
    hazards.value = hazardData.length ? hazardData : hazards.value
    devices.value = deviceData.length ? deviceData : devices.value
    warnings.value = warningData.length ? warningData : warnings.value
    if (!selectedPoint.value || !hazards.value.some((item) => String(item.id) === String(selectedPoint.value.id))) {
      selectedPoint.value = hazards.value[0] ?? null
    }
    syncMapLayers()
  } finally {
    refreshingViewport.value = false
  }
}

function locatePersonnel(person) {
  activePersonnel.value = person
  personnelTooltipVisible.value = true
  personnelRouteVisible.value = false
  if (!person || !mapController.value?.map) return
  mapController.value.setCenter(person.lon, person.lat, 14)
}

function openPersonnelRoute(person) {
  activePersonnel.value = person
  personnelRouteVisible.value = true
  personnelTooltipVisible.value = false
  if (!person || !mapController.value?.map) return
  mapController.value.setCenter(person.lon, person.lat, 14)
}

function handlePointerMove(event) {
  if (!mapController.value?.map) return
  const feature = mapController.value.map.forEachFeatureAtPixel(event.pixel, (item) => item)
  const person = feature?.get?.('name') && feature?.get?.('role') ? {
    id: feature.get('id'),
    name: feature.get('name'),
    role: feature.get('role'),
    team: feature.get('team'),
    state: feature.get('state'),
    lon: feature.get('lon'),
    lat: feature.get('lat'),
    speed: feature.get('speed'),
    updatedAt: feature.get('updatedAt'),
    route: feature.get('route') ?? [],
  } : null
  if (person) {
    hoveredPersonnel.value = person
    personnelTooltipVisible.value = true
  } else if (!personnelRouteVisible.value) {
    hoveredPersonnel.value = null
    personnelTooltipVisible.value = false
  }
}

function handleMapClick(event) {
  if (!mapController.value?.map) return
  const feature = mapController.value.map.forEachFeatureAtPixel(event.pixel, (item) => item)
  if (!feature) return
  // 值守人员：打开轨迹抽屉
  if (feature.get('role')) {
    openPersonnelRoute({
      id: feature.get('id'),
      name: feature.get('name'),
      role: feature.get('role'),
      team: feature.get('team'),
      state: feature.get('state'),
      lon: feature.get('lon'),
      lat: feature.get('lat'),
      speed: feature.get('speed'),
      updatedAt: feature.get('updatedAt'),
      route: feature.get('route') ?? [],
    })
    return
  }
  // 预警点位：点击跳转预警详情（唯一带 title 的点位）
  if (feature.get('title') && feature.get('status')) {
    const warning = {
      id: feature.get('id'),
      title: feature.get('title'),
      level: feature.get('level'),
      status: feature.get('status'),
    }
    emit('notify', `${warning.level} · ${warning.title}`)
    router.push(`/warnings/${warning.id}`)
    return
  }
  // 灾害点：选中并弹出详情浮层（唯一带 distance/owner 的点位）
  if (feature.get('distance') && feature.get('name')) {
    const point = {
      id: feature.get('id'),
      name: feature.get('name'),
      type: feature.get('type'),
      level: feature.get('level'),
      status: feature.get('status'),
      distance: feature.get('distance'),
      owner: feature.get('owner'),
      lon: feature.get('lon'),
      lat: feature.get('lat'),
    }
    selectedPoint.value = point
    return
  }
  // 设备：定位并通知，跳转设备详情
  if (feature.get('battery') && feature.get('name')) {
    const device = {
      id: feature.get('id'),
      name: feature.get('name'),
      type: feature.get('type'),
      state: feature.get('state'),
      point: feature.get('point'),
      battery: feature.get('battery'),
    }
    emit('notify', `设备 ${device.name}（${device.state}）`)
    router.push(`/devices/${device.id}`)
  }
}

async function loadData() {
  loading.value = true
  const [overview, hazardData, deviceData, warningData] = await Promise.all([
    dashboardApi.getOverview(),
    dashboardApi.getHazards(),
    dashboardApi.getDevices(),
    dashboardApi.getWarnings(),
  ])
  overviewStats.value = overview.overviewStats ?? overview
  healthStats.value = overview.healthStats ?? []
  warnings.value = warningData.list ?? warningData.records ?? []
  hazards.value = hazardData.list ?? hazardData.records ?? []
  devices.value = deviceData.list ?? deviceData.records ?? []
  selectedPoint.value = hazards.value[0] ?? null
  loading.value = false
  await nextTick()
  createOrResizeMap()
  await loadWeather()
}

async function refreshWeather() {
  await loadWeather()
  emit('notify', '实时天气已刷新')
}

function selectPoint(point) {
  selectedPoint.value = point
}

async function refreshMapData() {
  await loadData()
  emit('notify', '地图与点位数据已刷新')
}

function toggleMapMode() {
  if (!mapController.value) return
  mapMode.value = mapMode.value === 'vector' ? 'satellite' : 'vector'
  mapController.value.setMode(mapMode.value)
  emit('notify', mapMode.value === 'vector' ? '已切换到天地图矢量底图' : '已切换到天地图卫星底图')
  nextTick(() => syncMapLayers())
}

function open3DView() {
  if (!selectedPoint.value) return
  router.push(`/hazards/${selectedPoint.value.id}`)
}

function bindRealtime() {
  startRealtimeSync({
    wsUrl: import.meta.env.VITE_WS_URL,
    pollingInterval: Number(import.meta.env.VITE_REALTIME_POLL_INTERVAL ?? 15000),
    warningInterval: Number(import.meta.env.VITE_WARNING_POLL_INTERVAL ?? 10000),
    personnelInterval: Number(import.meta.env.VITE_PERSONNEL_POLL_INTERVAL ?? 30000),
    snapshot: {
      warnings: warnings.value,
      devices: devices.value,
      personnel: personnel.value,
    },
  })
  const dispose = onRealtimeUpdate((payload) => {
    if (payload.type === 'snapshot' || payload.type === 'poll' || payload.type === 'warning-poll') {
      if (payload.warnings?.length) warnings.value = payload.warnings
      if (payload.devices?.length) devices.value = payload.devices
      if (payload.personnel?.length) personnel.value = payload.personnel
      syncMapLayers()
      return
    }
    if (payload.type === 'warning' && payload.data) {
      const incoming = payload.data
      warnings.value = [
        incoming,
        ...warnings.value.filter((item) => String(item.id) !== String(incoming.id)),
      ]
      syncMapLayers()
      return
    }
    if (payload.type === 'device' && payload.data) {
      devices.value = [payload.data, ...devices.value.filter((item) => String(item.id) !== String(payload.data.id))]
      syncMapLayers()
      return
    }
    if (payload.type === 'personnel' && payload.data) {
      personnel.value = [payload.data, ...personnel.value.filter((item) => String(item.id) !== String(payload.data.id))]
      syncMapLayers()
    }
  })
  return () => {
    dispose()
    stopRealtimeSync()
  }
}

let disposeRealtime = null

onMounted(async () => {
  await loadData()
  disposeRealtime = bindRealtime()
  await requestViewportData()
})

onBeforeUnmount(() => {
  if (disposeRealtime) disposeRealtime()
  unbindMapEvents()
  if (mapController.value) {
    mapController.value.destroy()
    mapController.value = null
  }
})

watch([riskFilter, personnel], () => {
  syncMapLayers()
})
</script>

<template>
  <div>
    <div v-if="loading" class="panel" style="padding: 24px;">正在加载态势数据...</div>
    <template v-else>
      <div class="stats-grid">
        <div v-for="stat in roleOverview" :key="stat.label" class="stat-card">
          <div class="stat-icon" :class="stat.color"><component :is="stat.color === 'teal' ? Activity : stat.color === 'blue' ? Radio : stat.color === 'orange' ? AlertTriangle : ListChecks" :size="19" /></div>
          <div class="stat-copy">
            <span>{{ stat.label }}</span>
            <strong>{{ stat.value }} <small>{{ stat.suffix }}</small></strong>
            <em class="positive">{{ stat.change.split(' ')[0] }} <i>{{ stat.change.split(' ').slice(1).join(' ') }}</i></em>
          </div>
          <div class="sparkline" :class="`${stat.color}-line`"></div>
        </div>
      </div>

      <div class="content-grid">
        <section class="panel map-panel">
          <div class="panel-header">
            <div>
              <h2>风险态势总览</h2>
              <p>实时监测 · 更新于 {{ props.currentTime }}</p>
            </div>
            <div class="panel-tools">
              <button class="filter-btn" @click="refreshMapData"><ChevronDown :size="14" />刷新</button>
              <button class="filter-btn" @click="toggleMapMode"><Globe2 :size="14" />{{ mapMode === 'vector' ? '切换卫星' : '切换矢量' }}</button>
              <button class="square-btn" @click="refreshWeather"><CloudRain :size="17" /></button>
              <button class="square-btn"><MoreHorizontal :size="17" /></button>
            </div>
          </div>

          <div class="map-toolbar">
            <div class="map-legend">
              <button v-for="item in ['全部', '高风险', '关注', '正常']" :key="item" :class="{ selected: riskFilter === item }" @click="riskFilter = item; requestViewportData()">
                <span :class="item === '全部' ? 'all-dot' : item === '高风险' ? 'danger-dot' : item === '关注' ? 'warn-dot' : 'safe-dot'"></span>{{ item }}
              </button>
            </div>
            <div class="map-tools">
              <button @click="emit('notify', '已定位到当前监管区域')"><MapPin :size="15" />定位</button>
              <button @click="requestViewportData"><SlidersHorizontal :size="15" />图层</button>
            </div>
          </div>

          <div class="map-canvas map-real-container">
            <div ref="mapContainer" class="real-map-view"></div>
            <div class="map-overlay-hint">系统默认采用 OpenLayers 二维地图；3D 视角仅在灾害点详情页按需加载</div>
            <div v-if="selectedPoint" class="point-popover real-map-popover">
              <button class="close-popover" @click="selectedPoint = null"><X :size="15" /></button>
              <span class="point-type">{{ selectedPoint.type }} · {{ selectedPoint.level }}</span>
              <h3>{{ selectedPoint.name }}</h3>
              <p><Gauge :size="14" />{{ selectedPoint.status }} <span>实时数据</span></p>
              <button @click="open3DView">打开 3D 详情 <ChevronRight :size="14" /></button>
            </div>
            <PersonnelTooltip :person="personnelTooltipVisible ? (hoveredPersonnel || activePersonnel) : null" :visible="personnelTooltipVisible" @close="personnelTooltipVisible = false" />
          </div>
        </section>

        <section class="side-column">
          <div class="panel warning-panel">
            <div class="panel-header compact">
              <div>
                <h2>实时预警</h2>
                <p>需重点关注的风险变化</p>
              </div>
              <button class="text-btn" @click="showAllWarnings = !showAllWarnings">{{ showAllWarnings ? '收起' : '查看全部' }}<ChevronRight :size="14" /></button>
            </div>
            <div class="warning-list">
              <article v-for="warning in visibleWarnings" :key="warning.id" class="warning-item" @click="router.push(`/warnings/${warning.id}`)">
                <div class="warning-symbol" :class="warning.class">!</div>
                <div class="warning-body">
                  <div class="warning-title"><strong>{{ warning.title }}</strong><span :class="warning.class">{{ warning.level }}</span></div>
                  <p>{{ warning.desc }}</p>
                  <small><MapPin :size="12" />{{ warning.point }}<time>{{ warning.time }}</time></small>
                </div>
              </article>
            </div>
          </div>

          <div class="panel weather-panel">
            <div class="panel-header compact">
              <div>
                <h2>区域环境</h2>
                <p>{{ weather?.location || '龙川县' }} · 综合监测</p>
              </div>
              <CloudRain :size="22" class="weather-cloud" />
            </div>
            <div class="weather-main">
              <div>
                <strong v-if="weatherLoading">--<small>°C</small></strong>
                <strong v-else>{{ weather?.temperature ?? '--' }}<small>°C</small></strong>
                <span v-if="weatherLoading">正在获取实时天气...</span>
                <span v-else>{{ weather?.description }}</span>
              </div>
              <div class="weather-detail">
                <p><Waves :size="14" />湿度 {{ weather?.humidity ?? '--' }}%</p>
                <p><CloudRain :size="14" />降雨概率 {{ weather?.precipitationProbability ?? '--' }}%</p>
                <p><Gauge :size="14" />气压 {{ weather?.pressure ?? '--' }} hPa</p>
              </div>
            </div>
            <div class="weather-alert" :class="`alert-${weatherAlertLevel}`">
              <b>实时天气预警等级：{{ weatherAlertLevel }}</b>
              <button @click="refreshWeather">刷新天气</button>
            </div>
            <div class="forecast">
              <span><small>风速</small><strong>{{ weather?.windSpeed != null ? `${weather.windSpeed} m/s` : '--' }}</strong></span>
              <span><small>更新时间</small><strong>{{ weather?.updatedAt ? new Date(weather.updatedAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : '--' }}</strong></span>
              <span><small>站点</small><strong>{{ weather?.location || '龙川县' }}</strong></span>
            </div>
            <p v-if="weatherError" class="weather-error">{{ weatherError }}</p>
          </div>
        </section>
      </div>

      <div class="bottom-grid">
        <section class="panel tasks-panel">
          <div class="panel-header compact">
            <div>
              <h2>巡检任务进度</h2>
              <p>今日任务执行情况</p>
            </div>
            <button class="text-btn" @click="router.push('/tasks')">进入任务中心 <ChevronRight :size="14" /></button>
          </div>
          <div class="task-summary">
            <div class="progress-ring" :style="{ background: `conic-gradient(#56b992 0 ${taskProgress.percent}%, #e8f2ee ${taskProgress.percent}% 100%)` }"><span>{{ taskProgress.percent }}<small>%</small></span></div>
            <div class="task-overview">
              <strong>{{ taskProgress.done }} <small>/ {{ taskProgress.total }}</small></strong>
              <span>{{ taskProgress.label }}</span>
              <div class="task-bars"><i :style="{ width: `${taskProgress.percent}%` }"></i></div>
              <p><b>{{ taskProgress.pending }}</b> 项任务待处理</p>
            </div>
            <div class="task-stats">
              <div><strong>{{ props.userRole === 'maintenance' ? '14' : '86' }}</strong><span>{{ props.userRole === 'maintenance' ? '设备巡检' : '常规巡检' }}</span></div>
              <div><strong>{{ props.userRole === 'attendant' ? '18' : '28' }}</strong><span>{{ props.userRole === 'maintenance' ? '故障工单' : '雨后复查' }}</span></div>
              <div><strong>{{ props.userRole === 'admin' ? '10' : '6' }}</strong><span>{{ props.userRole === 'maintenance' ? '待处理告警' : '应急巡检' }}</span></div>
            </div>
          </div>
        </section>

        <section class="panel system-panel">
          <div class="panel-header compact">
            <div>
              <h2>系统运行状态</h2>
              <p>平台及数据服务健康度</p>
            </div>
            <span class="health-label"><i></i>运行良好</span>
          </div>
          <div class="system-list">
            <div v-for="item in healthStats" :key="item.label">
              <span><Database :size="16" />{{ item.label }}</span>
              <strong>{{ item.value }}</strong>
              <i class="healthy-bar"><b :style="{ width: `${item.progress}%` }"></b></i>
            </div>
          </div>
        </section>

        <section class="panel people-panel">
          <div class="panel-header compact">
            <div>
              <h2>值班与响应</h2>
              <p>{{ onlinePersonnel.length }} 人在线 · {{ offlinePersonnel.length }} 人离线</p>
            </div>
            <UsersRound :size="20" class="people-icon" />
          </div>
          <div class="people-main">
            <div class="avatars">
              <span v-for="person in dutyAvatarList" :key="person.id" :class="{ offline: person.state !== '在线' }" :title="person.name">{{ person.name.charAt(0) }}</span>
              <b v-if="dutyMoreCount > 0">+{{ dutyMoreCount }}</b>
              <b v-else-if="!personnel.length" title="暂无值守人员">--</b>
            </div>
            <div>
              <strong>{{ onlinePersonnel.length }}<small> 人</small></strong>
              <span>在线值守 · 共 {{ personnel.length || 0 }} 人</span>
            </div>
            <button @click="emit('notify', '已打开值班人员通讯录')" @dblclick="peopleModalOpen = true">通讯录 <ChevronRight :size="14" /></button>
          </div>
          <div class="people-list">
            <div v-for="person in sortedPersonnel.slice(0, 3)" :key="person.id" class="people-item" @click="locatePersonnel(person)">
              <span class="p-status" :class="`${person.state === '在线' ? 'online' : 'offline'}`"><i></i>{{ person.state }}</span>
              <span class="p-name">{{ person.name }}</span>
              <span class="p-role">{{ person.role }} · {{ person.team }}<template v-if="person.region"> · {{ person.region }}</template></span>
              <div class="p-actions">
                <button :title="`定位 ${person.name}`" @click.stop="locatePersonnel(person)"><MapPin :size="12" /></button>
                <button :title="`查看 ${person.name} 轨迹`" @click.stop="openPersonnelRoute(person)"><Route :size="12" /></button>
              </div>
            </div>
            <p v-if="!sortedPersonnel.length" class="people-empty">暂无值守人员在线</p>
          </div>
          <div class="people-hint">
            <button v-if="sortedPersonnel[0]" @click="locatePersonnel(sortedPersonnel[0])">定位值守人员</button>
            <button v-for="item in dutyTeamStats" :key="item.name" :title="`${item.name} ${item.count} 人`">{{ item.name }}·{{ item.count }}</button>
          </div>
        </section>
      </div>

      <PersonnelRouteDrawer :person="activePersonnel" :visible="personnelRouteVisible" @close="personnelRouteVisible = false" @focus="locatePersonnel" />

      <transition name="modal">
        <div v-if="peopleModalOpen" class="modal-backdrop" @click.self="peopleModalOpen = false">
          <div class="modal modal-wide people-directory">
            <div class="modal-head">
              <div>
                <span class="eyebrow">DUTY DIRECTORY</span>
                <h2>值班人员通讯录</h2>
                <p class="modal-subtitle">在岗人员 {{ personnel.length || 0 }} 人 · 在线 {{ onlinePersonnel.length }} 人</p>
              </div>
              <button class="icon-btn" title="关闭" @click="peopleModalOpen = false"><X :size="17" /></button>
            </div>
            <div class="people-dir-list">
              <div v-for="person in sortedPersonnel" :key="person.id" class="people-dir-item">
                <span class="dir-avatar" :class="{ offline: person.state !== '在线' }">{{ person.name.charAt(0) }}</span>
                <div class="dir-info">
                  <strong>{{ person.name }} <small v-if="person.state === '在线'">在线</small><small v-else class="offline-tag">离线</small></strong>
                  <p>{{ person.role }} · {{ person.team }}<template v-if="person.region"> · {{ person.region }}</template><template v-if="person.shift"> · {{ person.shift }}</template><template v-if="person.phone"> · {{ person.phone }}</template></p>
                </div>
                <div class="dir-actions">
                  <button @click="locatePersonnel(person); peopleModalOpen = false"><MapPin :size="13" />定位</button>
                  <button @click="openPersonnelRoute(person); peopleModalOpen = false"><Route :size="13" />轨迹</button>
                </div>
              </div>
              <p v-if="!sortedPersonnel.length" class="people-empty">暂无值班人员</p>
            </div>
          </div>
        </div>
      </transition>
    </template>
  </div>
</template>
