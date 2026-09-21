<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { useRouter } from "vue-router";
import {
  Radar,
  ShieldAlert,
  ArrowDownRight,
  ArrowUpRight,
  BarChart3,
  Calendar,
  CloudRain,
  Cpu,
  Crosshair,
  Database,
  Download,
  Gauge,
  GitCompareArrows,
  Landmark,
  LineChart,
  ListChecks,
  Loader2,
  MapPin,
  MoreHorizontal,
  Printer,
  Radio,
  RefreshCw,
  Route,
  Search,
  SlidersHorizontal,
  UsersRound,
  Waves,
  ChevronDown,
  ChevronRight,
  X,
} from "lucide-vue-next";
import { dashboardApi } from "../services/dashboardApi";
import {
  getRealtimeWeather,
  getWeatherAlertLevel,
} from "../services/weatherApi";
import {
  getAllWeatherProductsLatest,
  getSatelliteCloudLatest,
  getRadarLatest,
  getPrecipitation24hLatest,
  getSnow24hLatest,
  getDroughtLatest,
  WEATHER_LEGENDS,
} from "../services/weatherService";
import { createOpenLayersController, gradeAccuracy } from "../services/mapService";
import { composeSentinelScene, pollSentinelJob, cancelSentinelJob, describeJobStatus } from "../services/sentinelService";
import { transform } from "ol/proj";
import {
  getRealtimeState,
  onRealtimeUpdate,
  startRealtimeSync,
  stopRealtimeSync,
} from "../services/realtimeService";
import { useOverviewSearch } from "../composables/useOverviewSearch";
import PersonnelTooltip from "../components/PersonnelTooltip.vue";
import PersonnelRouteDrawer from "../components/PersonnelRouteDrawer.vue";
import WeatherOverlayLegend from "../components/WeatherOverlayLegend.vue";

const props = defineProps({
  currentTime: { type: String, required: true },
  userRole: { type: String, default: "admin" },
});

const emit = defineEmits(["notify", "open-task-modal"]);
const router = useRouter();

// 我的位置：localStorage 缓存键
// 关闭浏览器再打开也能复用上次定位结果；只有用户主动"清除定位"或精度严重过期才会触发新的浏览器权限请求。
const USER_LOCATION_KEY = "disasterguard_user_location";
// 缓存有效时长：24 小时。过期后视为过期定位，进入页面会自动重新触发一次 GPS。
const USER_LOCATION_TTL_MS = 24 * 60 * 60 * 1000;

function persistUserLocation(location) {
  if (!location) {
    try {
      window.localStorage.removeItem(USER_LOCATION_KEY);
    } catch (_) {
      /* localStorage 在隐私模式可能被禁 */
    }
    return;
  }
  try {
    window.localStorage.setItem(
      USER_LOCATION_KEY,
      JSON.stringify({
        lon: location.lon,
        lat: location.lat,
        accuracy: location.accuracy,
        timestamp: location.timestamp ?? Date.now(),
      }),
    );
  } catch (_) {
  }
}

function restoreUserLocation() {
  try {
    const raw = window.localStorage.getItem(USER_LOCATION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw);
    if (!Number.isFinite(parsed?.lon) || !Number.isFinite(parsed?.lat)) {
      return null;
    }
    const ts = Number(parsed.timestamp) || 0;
    if (ts && Date.now() - ts > USER_LOCATION_TTL_MS) {
      // 缓存超过 24h，作废
      window.localStorage.removeItem(USER_LOCATION_KEY);
      return null;
    }
    return parsed;
  } catch (_) {
    return null;
  }
}

const overviewStats = ref([]);
const healthStats = ref([]);
const warnings = ref([]);
const hazards = ref([]);
const devices = ref([]);
const personnel = ref([]);
/** hazardCode -> { riskLevel, riskScore, ... }，来自 /api/ai/comprehensive-analysis/all（5 分钟落库一次） */
const aiScoreMap = ref(new Map());
const loading = ref(true);
const riskFilter = ref("全部");
const activeWarnings = computed(() =>
  warnings.value.filter(
    (warning) => !["已关闭", "已忽略", "已解除"].includes(warning.status),
  ),
);
const selectedPoint = ref(null);
const showAllWarnings = ref(false);
const weather = ref(null);
const weatherLoading = ref(true);
const weatherError = ref("");
const mapContainer = ref(null);
const mapController = ref(null);
const mapMode = ref("vector");
/** v1.8.0：Sentinel-1+2 合成任务状态，null=空闲，{jobId, status, progress} = 进行中 */
const sentinelJob = ref(null);
/** v1.8.0：当前合成卫星元数据（合成时间、来源），由 mapController.sentinel.getMeta() 填充 */
const sentinelMeta = ref(null);
/** v1.7.2：图层下拉弹窗的开关状态 */
const layerPanelOpen = ref(false);
/** v1.7.2：业务图层显隐状态（与 mapController.setLayerVisible 双向同步） */
const businessLayers = reactive({
  hazard: true,
  device: true,
  warning: true,
  personnel: true,
  trail: true,
});

/** 业务图层中文标签，用于"图层"弹窗渲染 checkbox。 */
const LAYER_LABELS = {
  hazard: "灾害点",
  device: "监测设备",
  warning: "预警事件",
  personnel: "值守人员",
  trail: "人员轨迹",
};
const locating = ref(false);
const userLocation = ref(null);
const hoveredPersonnel = ref(null);
const activePersonnel = ref(null);
const personnelTooltipVisible = ref(false);
const personnelRouteVisible = ref(false);
const refreshingViewport = ref(false);
const viewportCache = ref({ bbox: "", zoom: null });
const viewportState = ref({ hazards: [], devices: [], warnings: [] });

// 搜索面板状态机、防抖、AbortController 已下沉到 composables/useOverviewSearch.js
const {
  searchTerm,
  searchPanelOpen,
  searchPanelState,
  searchResults,
  searchError,
  searchMarker,
  geocoderKind,
  onSearchInput,
  onSearchSubmit,
  selectSearchResult,
  closeSearchPanel,
  clearSearch,
  dispose: disposeSearch,
} = useOverviewSearch({ mapController, emit });

const filteredPoints = computed(() =>
  hazards.value.filter(
    (point) => riskFilter.value === "全部" || point.level === riskFilter.value,
  ),
);
const visibleWarnings = computed(() =>
  warnings.value.slice(0, showAllWarnings.value ? warnings.value.length : 3),
);
const weatherAlertLevel = computed(() => getWeatherAlertLevel(weather.value));

/** 取灾害点的 AI 综合分（无则 null）。 */
function aiOf(hazardCode) {
  if (!hazardCode) return null;
  return aiScoreMap.value.get(hazardCode) ?? null;
}

/** AI 综合分中文等级。 */
function aiLevelLabel(level) {
  return (
    { CRITICAL: "极高", HIGH: "高", MEDIUM: "中", LOW: "低" }[level] || "—"
  );
}

/** 按 AI 综合分重排灾害点（分高在前），并挂上 aiScore/aiLevel 字段。 */
function applyAiToHazards() {
  hazards.value = hazards.value
    .map((p) => {
      const ai = aiOf(p.hazardCode);
      return {
        ...p,
        aiScore: ai?.riskScore ?? null,
        aiLevel: ai?.riskLevel ?? null,
      };
    })
    .sort((a, b) => (b.aiScore ?? -1) - (a.aiScore ?? -1));
}

// 国家气象 overlay 状态：kind -> { enabled, loading, status, timestamp, error }
const weatherOverlayKinds = ["satellite", "radar", "precip24h", "snow24h", "drought"];
const weatherOverlayLabels = {
  satellite: "卫星云图",
  radar: "雷达回波",
  precip24h: "24h 降水",
  snow24h: "24h 降雪",
  drought: "干旱监测",
};
const weatherOverlayLoaders = {
  satellite: getSatelliteCloudLatest,
  radar: getRadarLatest,
  precip24h: getPrecipitation24hLatest,
  snow24h: getSnow24hLatest,
  drought: getDroughtLatest,
};
const weatherOverlays = reactive(
  weatherOverlayKinds.reduce((acc, kind) => {
    acc[kind] = {
      enabled: false,
      loading: false,
      ok: false,
      timestamp: "",
      error: "",
      url: "",
    };
    return acc;
  }, {}),
);
const peopleModalOpen = ref(false);
const weatherRefreshingAll = ref(false);
const weatherOpacities = reactive({
  satellite: 0.65,
  radar: 0.65,
  precip24h: 0.65,
  snow24h: 0.65,
  drought: 0.65,
});
const onlinePersonnel = computed(() =>
  personnel.value.filter((person) => person.state === "在线"),
);
const offlinePersonnel = computed(() =>
  personnel.value.filter((person) => person.state !== "在线"),
);
const sortedPersonnel = computed(() => [
  ...onlinePersonnel.value,
  ...offlinePersonnel.value,
]);
const dutyAvatarList = computed(() => sortedPersonnel.value.slice(0, 3));
const dutyMoreCount = computed(() =>
  Math.max(0, personnel.value.length - dutyAvatarList.value.length),
);
const dutyTeamStats = computed(() => {
  const groups = {};
  for (const person of personnel.value) {
    const team = person.team || "综合巡查组";
    groups[team] = (groups[team] ?? 0) + 1;
  }
  return Object.entries(groups).map(([name, count]) => ({ name, count }));
});

const roleOverview = computed(() => {
  if (props.userRole === "maintenance") {
    return [
      {
        label: "在线监测设备",
        value: String(
          Math.max(1, healthStats.value.length ? 1486 : 0),
        ).padStart(3, "0"),
        suffix: "台",
        change: "↑ 2.8% 较上月",
        color: "blue",
      },
      {
        label: "离线设备",
        value: "01",
        suffix: "台",
        change: "需巡检",
        color: "orange",
      },
      {
        label: "设备工单",
        value: "12",
        suffix: "单",
        change: "↑ 3 单 较昨日",
        color: "teal",
      },
      {
        label: "今日维护完成率",
        value: "97.2",
        suffix: "%",
        change: "↑ 1.8% 较昨日",
        color: "purple",
      },
    ];
  }
  if (props.userRole === "attendant") {
    return [
      {
        label: "监测灾害点",
        value: String(hazards.value.length || 128).padStart(3, "0"),
        suffix: "处",
        change: "↑ 4.2% 较上月",
        color: "teal",
      },
      {
        label: "当前预警事件",
        value: String(activeWarnings.value.length || 5).padStart(2, "0"),
        suffix: "起",
        change: "↑ 1 起 较昨日",
        color: "orange",
      },
      {
        label: "待处理任务",
        value: "10",
        suffix: "项",
        change: "持续跟进",
        color: "blue",
      },
      {
        label: "今日巡检完成率",
        value: "92.4",
        suffix: "%",
        change: "↑ 6.3% 较昨日",
        color: "purple",
      },
    ];
  }
  return overviewStats.value;
});

const taskProgress = computed(() => {
  if (props.userRole === "maintenance") {
    return {
      done: 18,
      total: 22,
      percent: Math.round((18 / 22) * 100),
      label: "设备维护已完成",
      pending: 4,
    };
  }
  return {
    done: 124,
    total: 134,
    percent: Math.round((124 / 134) * 100),
    label: "任务已完成",
    pending: 10,
  };
});

async function loadWeather() {
  weatherLoading.value = true;
  weatherError.value = "";
  try {
    // 优先使用"我的位置"GPS 坐标；其次落到第一个灾害点；都没有则用默认中心。
    // 这样"区域环境"面板就能反映用户当前所在区域的环境，而不是只看监管区里第一个点。
    const fallback = hazards.value[0] ?? {
      lat: 30.61,
      lon: 107.21,
      owner: "龙川县",
    };
    const target = userLocation.value
      ? {
          lat: userLocation.value.lat,
          lon: userLocation.value.lon,
          owner: `当前位置 (±${userLocation.value.accuracy ?? 0}m)`,
        }
      : fallback;
    weather.value = await getRealtimeWeather({
      lat: target.lat,
      lon: target.lon,
      location: target.owner,
    });
  } catch (error) {
    weatherError.value =
      error instanceof Error ? error.message : "天气加载失败";
  } finally {
    weatherLoading.value = false;
  }
}

function syncMapLayers() {
  if (!mapController.value?.map) return;
  mapController.value.setHazards(filteredPoints.value);
  mapController.value.setDevices(devices.value);
  mapController.value.setWarnings(activeWarnings.value);
  mapController.value.setPersonnel(personnel.value);
}

async function createOrResizeMap() {
  if (!mapContainer.value) return;
  if (!mapController.value) {
    mapController.value = createOpenLayersController(mapContainer.value, {
      center: [107.21, 30.61],
      zoom: 10,
      baseMap: "vector",
    });
    syncSentinelMeta();
  }
  bindMapEvents();
  if (hasPositiveSize(mapContainer.value)) {
    mapController.value.map.updateSize();
    syncMapLayers();
  }
}

/**
 * 监听 map 容器尺寸变化（侧栏折叠 / 浏览器缩放 / 父级 flex 重排都会触发）。
 * 用 ResizeObserver 比监听 window.resize 更精准：仅当真实 DOM 尺寸变化时才 updateSize。
 * 关键修复：第一次 observer 回调时容器可能仍是 0×0（父级 layout 还没完成），
 * 此时调用 updateSize 会让 OL 触发 "No map visible because the map container's
 * width or height are 0" 警告；这里用 hasPositiveSize() 过滤，宽度或高度为 0
 * 时直接跳过，等真正有像素尺寸后再校准。
 */
let _mapResizeObserver = null;
function ensureMapResizeObserver() {
  if (_mapResizeObserver || !mapContainer.value || !window.ResizeObserver) return;
  _mapResizeObserver = new ResizeObserver(() => {
    if (!mapContainer.value || !mapController.value?.map) return;
    if (!hasPositiveSize(mapContainer.value)) return;
    mapController.value.map.updateSize();
    syncMapLayers();
  });
  _mapResizeObserver.observe(mapContainer.value);
}

/**
 * 容器是否已经具有正像素尺寸（父级 layout 完成后）。
 * 用 clientWidth/clientHeight（不含 border）比 offsetWidth 更贴合 OL 的判定逻辑。
 */
function hasPositiveSize(el) {
  return el && el.clientWidth > 0 && el.clientHeight > 0;
}

/**
 * 强制重新校准地图尺寸。
 * 用一个轻量 poll（最多 8 帧 ≈ 130ms）等待父级 layout 真正完成后再 updateSize，
 * 避免 OpenLayers 在容器还是 0×0 时被反复调用 updateSize 而刷出 console warning。
 */
async function forceMapResize() {
  if (!mapController.value?.map) return;
  let attempt = 0;
  while (attempt < 8) {
    await nextTick();
    if (!mapContainer.value) return;
    if (hasPositiveSize(mapContainer.value)) {
      // 强制 reflow 后再 updateSize（OL 内部用 getBoundingClientRect）
      void mapContainer.value.offsetWidth;
      void mapContainer.value.offsetHeight;
      mapController.value.map.updateSize();
      syncMapLayers();
      return;
    }
    attempt += 1;
    await new Promise((r) => requestAnimationFrame(r));
  }
  // 容器仍然 0×0 时不再 updateSize，让后续 ResizeObserver 在有尺寸时自动校准。
  // 这样不会再产生 "No map visible" 的 console warning。
}

function bindMapEvents() {
  if (!mapController.value?.map) return;
  const map = mapController.value.map;
  map.on("moveend", requestViewportData);
  map.on("pointermove", handlePointerMove);
  map.on("click", handleMapClick);
}

function unbindMapEvents() {
  if (!mapController.value?.map) return;
  const map = mapController.value.map;
  map.un("moveend", requestViewportData);
  map.un("pointermove", handlePointerMove);
  map.un("click", handleMapClick);
}

function buildViewportParams() {
  if (!mapController.value?.map) return null;
  const map = mapController.value.map;
  const size = map.getSize();
  if (!size) return null;
  const extent = map.getView().calculateExtent(size);
  const bbox = `${extent[0].toFixed(6)},${extent[1].toFixed(6)},${extent[2].toFixed(6)},${extent[3].toFixed(6)}`;
  return { bbox, zoom: Math.round(map.getView().getZoom() ?? 10) };
}

async function requestViewportData() {
  const params = buildViewportParams();
  if (!params) return;
  const nextKey = `${params.bbox}:${params.zoom}:${riskFilter.value}`;
  if (viewportCache.value.bbox === nextKey) return;
  viewportCache.value = { bbox: nextKey, zoom: params.zoom };
  refreshingViewport.value = true;
  try {
    const [hazardData, deviceData, warningData] = await Promise.all([
      dashboardApi.getHazardsByViewport({
        bbox: params.bbox,
        zoom: params.zoom,
        level: riskFilter.value === "全部" ? "" : riskFilter.value,
      }),
      dashboardApi.getDevicesByViewport({
        bbox: params.bbox,
        zoom: params.zoom,
      }),
      dashboardApi.getWarningsByViewport({
        bbox: params.bbox,
        zoom: params.zoom,
        status: "",
      }),
    ]);
    viewportState.value = {
      hazards: hazardData,
      devices: deviceData,
      warnings: warningData,
    };
    hazards.value = hazardData.length ? hazardData : hazards.value;
    devices.value = deviceData.length ? deviceData : devices.value;
    warnings.value = warningData.length ? warningData : warnings.value;
    applyAiToHazards();
    if (
      !selectedPoint.value ||
      !hazards.value.some(
        (item) => String(item.id) === String(selectedPoint.value.id),
      )
    ) {
      selectedPoint.value = hazards.value[0] ?? null;
    }
    syncMapLayers();
  } finally {
    refreshingViewport.value = false;
  }
}

function locatePersonnel(person) {
  activePersonnel.value = person;
  personnelTooltipVisible.value = true;
  personnelRouteVisible.value = false;
  if (!person || !mapController.value?.map) return;
  mapController.value.setCenter(person.lon, person.lat, 14);
}

function openPersonnelRoute(person) {
  activePersonnel.value = person;
  personnelRouteVisible.value = true;
  personnelTooltipVisible.value = false;
  if (!person || !mapController.value?.map) return;
  mapController.value.setCenter(person.lon, person.lat, 14);
}

let lastHoverPixel = null;

function handlePointerMove(event) {
  if (!mapController.value?.map) return;
  const { pixel } = event;
  // 相同像素不重复做命中检测，减少 OL 画布 readback（getImageData）开销
  if (
    lastHoverPixel &&
    lastHoverPixel[0] === pixel[0] &&
    lastHoverPixel[1] === pixel[1]
  ) {
    return;
  }
  lastHoverPixel = pixel;
  const feature = mapController.value.map.forEachFeatureAtPixel(
    event.pixel,
    (item) => item,
  );
  const person =
    feature?.get?.("name") && feature?.get?.("role")
      ? {
          id: feature.get("id"),
          name: feature.get("name"),
          role: feature.get("role"),
          team: feature.get("team"),
          state: feature.get("state"),
          lon: feature.get("lon"),
          lat: feature.get("lat"),
          speed: feature.get("speed"),
          updatedAt: feature.get("updatedAt"),
          route: feature.get("route") ?? [],
        }
      : null;
  if (person) {
    hoveredPersonnel.value = person;
    personnelTooltipVisible.value = true;
  } else if (!personnelRouteVisible.value) {
    hoveredPersonnel.value = null;
    personnelTooltipVisible.value = false;
  }
}

function handleMapClick(event) {
  if (!mapController.value?.map) return;
  const hit = mapController.value.map.forEachFeatureAtPixel(
    event.pixel,
    (item) => item,
  );
  if (!hit) return;
  // 聚簇命中：取出被聚合的第一个真实点位，作为点击目标
  const clustered = hit.get?.("features");
  const feature = Array.isArray(clustered) && clustered.length ? clustered[0] : hit;
  if (!feature) return;
  // 值守人员：打开轨迹抽屉
  if (feature.get("role")) {
    openPersonnelRoute({
      id: feature.get("id"),
      name: feature.get("name"),
      role: feature.get("role"),
      team: feature.get("team"),
      state: feature.get("state"),
      lon: feature.get("lon"),
      lat: feature.get("lat"),
      speed: feature.get("speed"),
      updatedAt: feature.get("updatedAt"),
      route: feature.get("route") ?? [],
    });
    return;
  }
  // 预警点位：点击跳转预警详情（唯一带 title 的点位）
  if (feature.get("title") && feature.get("status")) {
    const warning = {
      id: feature.get("id"),
      title: feature.get("title"),
      level: feature.get("level"),
      status: feature.get("status"),
    };
    emit("notify", `${warning.level} · ${warning.title}`);
    router.push(`/dashboard/warnings/${warning.id}`);
    return;
  }
  // 灾害点：选中并弹出详情浮层，同时放大定位到该详细点（唯一带 distance/owner 的点位）
  if (feature.get("distance") && feature.get("name")) {
    const point = {
      id: feature.get("id"),
      name: feature.get("name"),
      type: feature.get("type"),
      level: feature.get("level"),
      status: feature.get("status"),
      distance: feature.get("distance"),
      owner: feature.get("owner"),
      lon: feature.get("lon"),
      lat: feature.get("lat"),
    };
    selectedPoint.value = point;
    mapController.value.zoomToPoint?.(point.lon, point.lat, 16);
    return;
  }
  // 设备：定位并通知，跳转设备详情
  if (feature.get("battery") && feature.get("name")) {
    const device = {
      id: feature.get("id"),
      name: feature.get("name"),
      type: feature.get("type"),
      state: feature.get("state"),
      point: feature.get("point"),
      battery: feature.get("battery"),
    };
    emit("notify", `设备 ${device.name}（${device.state}）`);
    router.push(`/dashboard/devices/${device.id}`);
  }
}

async function loadData() {
  loading.value = true;
  const [overview, hazardData, deviceData, warningData] = await Promise.all([
    dashboardApi.getOverview(),
    dashboardApi.getHazards(),
    dashboardApi.getDevices(),
    dashboardApi.getWarnings(),
  ]);
  overviewStats.value = overview.overviewStats ?? overview;
  healthStats.value = overview.healthStats ?? [];
  warnings.value = warningData.list ?? warningData.records ?? [];
  hazards.value = hazardData.list ?? hazardData.records ?? [];
  devices.value = deviceData.list ?? deviceData.records ?? [];
  selectedPoint.value = hazards.value[0] ?? null;
  loading.value = false;
  await nextTick();
  createOrResizeMap();
  // 父级 layout 可能在 nextTick 之后还有一帧延迟（flex/grid 重排），再补一次 force resize
  // 并挂上 ResizeObserver 跟踪后续容器尺寸变化（侧栏折叠 / 浏览器缩放）
  ensureMapResizeObserver();
  await forceMapResize();
  // 再等一帧：父级动画 / sidebar 折叠完成后再校准一次，彻底消除 "width or height are 0" 警告
  requestAnimationFrame(() => forceMapResize());
  await loadWeather();
  await loadAiScores();
}

/**
 * 拉 AI 综合分析落库结果（后端每 5 分钟全量刷新一次）。
 * 概览页的灾害点排序 + 风险徽标都由它驱动 —— AI 模块是整个项目的后台。
 */
async function loadAiScores() {
  try {
    const list = await dashboardApi.getAiComprehensiveAll();
    const map = new Map();
    (Array.isArray(list) ? list : []).forEach((s) => {
      if (s?.hazardCode) map.set(s.hazardCode, s);
    });
    aiScoreMap.value = map;
    applyAiToHazards();
  } catch {
    // AI 模块不可用时静默降级：仍按原有 level 字段展示
  }
}

async function refreshWeather() {
  await loadWeather();
  emit("notify", "实时天气已刷新");
}

/**
 * 切换气象 overlay（卫星云图/雷达/降水/降雪/干旱）：
 *   - 首次开启时拉最新一帧并落到地图；
 *   - 关闭时清除图片源；
 *   - 已开启状态下再次点击会重新拉最新一帧。
 */
async function toggleWeatherOverlay(kind) {
  const ctrl = mapController.value
  if (!ctrl) return
  const state = weatherOverlays[kind]
  if (!state) return
  // 如果开启中且已 ok，再次点击 = 强制刷新
  if (state.enabled && state.ok) {
    await refreshWeatherOverlay(kind)
    return
  }
  if (state.enabled && !state.ok) {
    state.enabled = false
    state.loading = false
    return
  }
  // 首次开启 → 拉数据
  state.loading = true
  state.error = ""
  try {
    await refreshWeatherOverlay(kind)
    state.enabled = true
    emit("notify", `${weatherOverlayLabels[kind]} 已叠加到地图`)
  } catch (err) {
    state.error = err?.message || "拉取失败"
    emit("notify", `${weatherOverlayLabels[kind]} 加载失败：${state.error}`)
  } finally {
    state.loading = false
  }
}

async function refreshWeatherOverlay(kind) {
  const ctrl = mapController.value
  if (!ctrl) return
  const state = weatherOverlays[kind]
  const loader = weatherOverlayLoaders[kind]
  if (!state || !loader) return
  state.loading = true
  state.error = ""
  try {
    const payload = await loader()
    if (!payload || !payload.url) {
      throw new Error("未获取到最新图源")
    }
    state.ok = true
    state.url = payload.url
    state.timestamp = payload.timestamp || payload.time || ""
    state.error = ""
    await ctrl.refreshWeatherOverlay(kind, () => payload)
    ctrl.setWeatherOverlay(kind, true)
    state.enabled = true
  } catch (err) {
    state.ok = false
    state.error = err?.message || "拉取失败"
    throw err
  } finally {
    state.loading = false
  }
}

/** 一次性拉全部 overlay（预热缓存），不强制开启。 */
async function preloadWeatherOverlays() {
  await getAllWeatherProductsLatest().catch(() => null)
}

async function onWeatherRefreshAll() {
  if (weatherRefreshingAll.value) return
  weatherRefreshingAll.value = true
  try {
    const kinds = Object.keys(weatherOverlays)
    await Promise.allSettled(
      kinds.map(async (kind) => {
        try {
          await refreshWeatherOverlay(kind)
        } catch (_) {
          // 单个失败不影响整体
        }
      }),
    )
    emit('notify', '已刷新全部气象 overlay')
  } finally {
    weatherRefreshingAll.value = false
  }
}

function onWeatherOpacity(kind, opacity) {
  weatherOpacities[kind] = opacity
  mapController.value?.setWeatherOverlayOpacity?.(kind, opacity)
}

function selectPoint(point) {
  selectedPoint.value = point;
}

async function refreshMapData() {
  await loadData();
  emit("notify", "地图与点位数据已刷新");
}

function toggleMapMode() {
  if (!mapController.value) return;
  mapMode.value = mapMode.value === "vector" ? "satellite" : "vector";
  mapController.value.setMode(mapMode.value);
  emit(
    "notify",
    mapMode.value === "vector"
      ? "已切换到天地图矢量底图"
      : "已切换到天地图卫星底图",
  );
  nextTick(() => syncMapLayers());
}

/**
 * v1.8.0：合成 Sentinel-1（雷达 SAR）+ Sentinel-2（光学真彩）卫星图层。
 *
 * 流程：composeSentinelScene() 提交 AOI → AI Service /sentinel/compose
 *       → Copernicus Data Space / GEE 拉取 S1 GRD + S2 L2A
 *       → 合成两份 PNG（S2 真彩 + S1 SAR 伪彩色）
 *       → pollSentinelJob() 轮询状态
 *       → setSentinelOverlay() 把结果挂到地图
 *       → 地图切换到卫星模式并聚焦到 AOI
 *
 * 调用方在 UI 上显示进度条（sentinelJob.progress）和文案（describeJobStatus）。
 * 合成过程中地图仍可正常使用（叠加层加载期间底图保持原样）。
 */
async function startSentinelCompose() {
  if (!mapController.value) {
    emit("notify", "地图尚未初始化，请稍后重试");
    return;
  }
  if (sentinelJob.value) {
    emit("notify", "合成任务正在进行中，请等待完成");
    return;
  }
  // 拿当前视图的 AOI bbox
  const view = mapController.value.map.getView();
  const extent = view.calculateExtent(mapController.value.map.getSize());
  const [minX, minY, maxX, maxY] = extent;
  // OL extent 是 EPSG:3857，转 EPSG:4326
  const [minLon, minLat] = toLonLat([minX, minY]);
  const [maxLon, maxLat] = toLonLat([maxX, maxY]);
  const bbox = [minLon, minLat, maxLon, maxLat];
  // 地图切到卫星模式（显示 Sentinel 图层）
  mapMode.value = "satellite";
  mapController.value.setMode("satellite");
  try {
    // 1. 提交合成任务
    const job = await composeSentinelScene({ bbox, maxCloud: 15 });
    sentinelJob.value = job;
    emit("notify", "Sentinel 合成任务已提交，正在排队…");
    // 2. 轮询直到完成
    await pollSentinelJob(
      job.jobId,
      (updatedJob) => {
        sentinelJob.value = updatedJob;
      },
      { timeoutMs: 5 * 60 * 1000 },
    );
    // 3. 拿到结果，挂到地图
    const finalJob = sentinelJob.value;
    if (finalJob.status !== "succeeded") {
      emit("notify", `合成失败：${finalJob.message || finalJob.status}`);
      return;
    }
    const { result } = finalJob;
    if (!result || (!result.s2Url && !result.sarUrl)) {
      emit("notify", "合成完成但未返回有效图源 URL");
      return;
    }
    mapController.value.sentinel.setOverlay({
      s2Url: result.s2Url,
      sarUrl: result.sarUrl,
      bbox,
      meta: { jobId: finalJob.jobId, provider: result.provider || "copernicus", ...result.meta },
    });
    syncSentinelMeta();
    emit("notify", "Sentinel-1+2 合成卫星已加载（数据来源：Copernicus Data Space）");
  } catch (err) {
    if (err.name === "AbortError" || err.message?.includes("被取消")) {
      emit("notify", "合成任务已取消");
    } else {
      emit("notify", `合成出错：${err.message}`);
    }
  } finally {
    sentinelJob.value = null;
  }
}

/** 把 EPSG:3857 坐标转 EPSG:4326（经纬度）。 */
function toLonLat(coord) {
  try {
    return transform(coord, "EPSG:3857", "EPSG:4326");
  } catch {
    // fallback: 大致估算（仅中国区域误差 <1%）
    const x = coord[0] / 20037508.34 * 180;
    const y = coord[1] / 20037508.34 * 180;
    const lat = (Math.atan(Math.exp(y * Math.PI / 180)) * 360 / Math.PI) - 90;
    return [x, lat];
  }
}

/** 从 mapController 拉取当前 Sentinel 合成元数据，同步到 sentinelMeta。 */
function syncSentinelMeta() {
  if (!mapController.value?.sentinel) return;
  sentinelMeta.value = mapController.value.sentinel.getMeta();
}

/**
 * v1.7.2：图层下拉弹窗的开关；同时把当前业务图层的可见性从 mapController 同步到本地状态，
 * 保证再次打开弹窗时 checkbox 反映真实情况。
 */
function toggleLayerPanel() {
  if (!mapController.value) return;
  if (!layerPanelOpen.value) {
    // 打开时同步真实可见性
    Object.keys(businessLayers).forEach((kind) => {
      businessLayers[kind] = mapController.value.getLayerVisible(kind);
    });
  }
  layerPanelOpen.value = !layerPanelOpen.value;
}

function toggleBusinessLayer(kind) {
  if (!mapController.value) return;
  const next = !businessLayers[kind];
  businessLayers[kind] = next;
  mapController.value.setLayerVisible(kind, next);
  emit("notify", `${LAYER_LABELS[kind] || kind} 图层已${next ? "显示" : "隐藏"}`);
}

/**
 * GPS 定位：委托给 mapController.locateUser()，统一处理 loading、toast、结果缓存。
 * - 浏览器必须处于 HTTPS 或 localhost（地理位置 API 的安全策略）
 * - 用户首次定位会被浏览器弹出权限请求，需要他们授权后才能继续
 * - options.silent = true 时不弹 toast（用于页面打开时静默自动定位）
 */
async function locateMe(options = {}) {
  if (!mapController.value) return null;
  if (locating.value) return null;
  locating.value = true;
  try {
    const result = await mapController.value.locateUser({ zoom: 16 });
    const location = {
      lon: result.lon,
      lat: result.lat,
      accuracy: Math.round(result.accuracy),
      timestamp: result.timestamp,
      grade: result.grade,
      zoom: result.zoom,
    };
    userLocation.value = location;
    persistUserLocation(location);
    if (!options.silent) {
      // 用精度档位调整文案：>2km 提醒用户当前不是真实 GPS 而是网络定位
      const acc = location.accuracy;
      const gradeLabel = result.grade?.label || "定位";
      const tip =
        result.grade?.grade >= 2
          ? "（当前为网络/WiFi 定位，建议在开阔地带使用 GPS）"
          : "";
      emit(
        "notify",
        `${gradeLabel}定位（±${acc} 米）：${result.lon.toFixed(4)}, ${result.lat.toFixed(4)}${tip}`,
      );
    }
    return location;
  } catch (error) {
    if (!options.silent) {
      emit("notify", error?.message || "定位失败，请稍后重试");
    }
    return null;
  } finally {
    locating.value = false;
  }
}

/**
 * 用本地缓存的经纬度直接在地图上画定位点（不调用 navigator.geolocation）。
 * 用途：进入页面时从 localStorage 还原上次的定位结果，免去重复弹浏览器权限框。
 * 不会动 toast，全部静默。
 */
function applyUserLocation(cached) {
  if (!mapController.value || !cached) return null;
  const result = mapController.value.paintUserLocation({
    lon: cached.lon,
    lat: cached.lat,
    accuracy: cached.accuracy ?? 0,
    zoom: 16,
    animate: false,
  });
  if (!result) return null;
  const location = {
    lon: result.lon,
    lat: result.lat,
    accuracy: Math.round(result.accuracy ?? cached.accuracy ?? 0),
    timestamp: result.timestamp,
    grade: result.grade ?? gradeAccuracy(cached.accuracy ?? 0),
    zoom: result.zoom,
  };
  userLocation.value = location;
  return location;
}

/** 清除地图上的定位标记 + localStorage 缓存。 */
function clearMyLocation() {
  if (!mapController.value) return;
  mapController.value.clearUserLocation();
  userLocation.value = null;
  persistUserLocation(null);
  emit("notify", "已清除我的位置标记");
}

/**
 * 搜索面板的状态机、防抖、AbortController 已下沉到 composables/useOverviewSearch.js。
 * 这里保留 open3DView 等其他动作。
 */
function open3DView() {
  if (!selectedPoint.value) return;
  router.push(`/dashboard/hazards/${selectedPoint.value.id}`);
}

function bindRealtime() {
  startRealtimeSync({
    wsUrl: import.meta.env.VITE_WS_URL,
    pollingInterval: Number(
      import.meta.env.VITE_REALTIME_POLL_INTERVAL ?? 15000,
    ),
    warningInterval: Number(
      import.meta.env.VITE_WARNING_POLL_INTERVAL ?? 10000,
    ),
    personnelInterval: Number(
      import.meta.env.VITE_PERSONNEL_POLL_INTERVAL ?? 30000,
    ),
    snapshot: {
      warnings: warnings.value,
      devices: devices.value,
      personnel: personnel.value,
    },
  });
  const dispose = onRealtimeUpdate((payload) => {
    if (
      payload.type === "snapshot" ||
      payload.type === "poll" ||
      payload.type === "warning-poll"
    ) {
      // 用 Array.isArray 而非 ?.length：后端返回空数组是合法状态（已全部关闭/无设备），
      // 此时也要清空前端，不能用 truthy 守卫保留陈旧项。
      if (Array.isArray(payload.warnings)) warnings.value = payload.warnings;
      if (Array.isArray(payload.devices)) devices.value = payload.devices;
      if (Array.isArray(payload.personnel)) personnel.value = payload.personnel;
      if (Array.isArray(payload.hazards)) hazards.value = payload.hazards;
      applyAiToHazards();
      syncMapLayers();
      return;
    }
    // personnel 轮询 / 动画事件：之前未处理，导致人员点位不会按 30s/10s 节奏刷新
    if (payload.type === "personnel-poll" || payload.type === "personnel-motion") {
      if (Array.isArray(payload.personnel)) personnel.value = payload.personnel;
      syncMapLayers();
      return;
    }
    // AI 实时监测的灾害增量（WebSocket hazard 事件）
    if (payload.type === "hazard" && payload.data) {
      const incoming = payload.data;
      hazards.value = [
        incoming,
        ...hazards.value.filter(
          (item) => String(item.id) !== String(incoming.id),
        ),
      ];
      applyAiToHazards();
      syncMapLayers();
      return;
    }
    if (payload.type === "warning" && payload.data) {
      const incoming = payload.data;
      warnings.value = [
        incoming,
        ...warnings.value.filter(
          (item) => String(item.id) !== String(incoming.id),
        ),
      ];
      syncMapLayers();
      return;
    }
    if (payload.type === "device" && payload.data) {
      devices.value = [
        payload.data,
        ...devices.value.filter(
          (item) => String(item.id) !== String(payload.data.id),
        ),
      ];
      syncMapLayers();
      return;
    }
    if (payload.type === "personnel" && payload.data) {
      personnel.value = [
        payload.data,
        ...personnel.value.filter(
          (item) => String(item.id) !== String(payload.data.id),
        ),
      ];
      syncMapLayers();
    }
  });
  return () => {
    dispose();
    stopRealtimeSync();
  };
}

let disposeRealtime = null;

onMounted(async () => {
  await loadData();
  disposeRealtime = bindRealtime();
  await requestViewportData();
  // 预热气象 overlay 缓存：第一次切换时不必等待 NSMC/NMC 探测
  preloadWeatherOverlays();
  // 进入界面即定位：
  //   1) 先尝试读 localStorage —— 上次成功的定位会被还原到地图上（无浏览器权限弹窗）；
  //   2) 没有缓存或缓存过期时静默调用 locateMe() 触发一次 GPS（不弹 toast，避免每次进入都刷屏）；
  //   3) 浏览器拒绝了 / 拿不到定位时静默失败，由用户手动点"我的位置"按钮再走一次。
  await tryAutoLocateOnMount();
});

/**
 * 进入页面时尝试还原或触发定位：
 * - localStorage 命中 → 直接 paintUserLocation 到地图，零延迟；
 * - 未命中 → silent locateMe 调一次（带超时兜底，整体不阻塞首屏渲染）；
 * 任何失败都不弹 toast，让用户后续手动点按钮即可。
 *
 * 注意：用户要求"每次打开界面直接GPS定位，不进行手动定位" —— 这里做到：
 *   1) 缓存 < 5 分钟 → 直接复用，不弹任何提示
 *   2) 缓存存在但 > 5 分钟 → 复用 + 静默触发一次新定位（覆盖）
 *   3) 无缓存 → 立即触发定位
 *   4) 浏览器拒绝 → 留个温和提示"需要授权才能定位"，由用户点击"我的位置"再试一次
 */
async function tryAutoLocateOnMount() {
  await nextTick();
  if (!mapController.value) return;
  const cached = restoreUserLocation();
  const CACHE_FRESH_MS = 5 * 60 * 1000;
  const isFresh = cached && cached.timestamp && Date.now() - cached.timestamp < CACHE_FRESH_MS;
  if (cached) {
    const applied = applyUserLocation(cached);
    if (applied && isFresh) {
      await loadWeather();
      return;
    }
  }
  // 缓存过期 / 不存在 → 静默触发一次新定位
  locateMe({ silent: true }).then((location) => {
    if (location) {
      loadWeather();
    }
  });
}

onBeforeUnmount(() => {
  if (disposeRealtime) disposeRealtime();
  unbindMapEvents();
  disposeSearch();
  if (_mapResizeObserver) {
    _mapResizeObserver.disconnect();
    _mapResizeObserver = null;
  }
  if (mapController.value) {
    mapController.value.destroy();
    mapController.value = null;
  }
});

watch([riskFilter, personnel], () => {
  syncMapLayers();
});

// 选中的风险点变化时，同步地图上的高亮标记（点击放大后一眼可辨）
watch(selectedPoint, (point) => {
  mapController.value?.setSelectedHazard?.(point?.id ?? null);
});

// PRD 1.4 多时相对比 Tab
// 取与 OverviewPage 当前口径一致的色板，避免视觉割裂。
const COMPARE_COLOR_BASE = "#4f8f99";
const COMPARE_COLOR_COMPARE = "#e0a050";
const COMPARE_COLOR_RISE = "#e06060";
const COMPARE_COLOR_FALL = "#6dbfa5";
const COMPARE_COLOR_NEUTRAL = "#d9c040";
const COMPARE_COLOR_MUTE = "#7a8d89";
const COMPARE_KPI_DEFS = [
  { key: "incidentTotal", label: "事件总数", unit: "起", higherIsBetter: false, nice: 0 },
  { key: "disposalRate", label: "处置率", unit: "%", higherIsBetter: true, nice: 1 },
  { key: "avgHandleMinutes", label: "平均处置时长", unit: "分钟", higherIsBetter: false, nice: 0 },
  { key: "onlineRate", label: "在线设备率", unit: "%", higherIsBetter: true, nice: 1 },
  { key: "falseAlarmRate", label: "误报率", unit: "%", higherIsBetter: false, nice: 1 },
  { key: "activeUsers", label: "用户活跃", unit: "人", higherIsBetter: true, nice: 0 },
];

const activeCompareTab = ref("overview"); // overview | compare
const compareBasePreset = ref("24h"); // 24h | 7d | 30d | custom
const compareBaseCustomStart = ref("");
const compareBaseCustomEnd = ref("");
const compareTarget = ref("previous"); // previous | lastYear | custom
const compareTargetCustomStart = ref("");
const compareTargetCustomEnd = ref("");
const compareMode = ref("side"); // side | overlay | delta
const compareLoading = ref(false);
const compareError = ref("");
const compareData = ref(null);
const compareExporting = ref(false);
const compareLegendVisible = ref({ base: true, compare: true });

function defaultCompareCustomRange() {
  // 默认给自定义输入一组合理的时间区间，避免首次切换时控件全空。
  const end = new Date();
  const start = new Date(end.getTime() - 24 * 60 * 60 * 1000);
  const toLocalInput = (d) => {
    const pad = (n) => String(n).padStart(2, "0");
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  };
  return { start: toLocalInput(start), end: toLocalInput(end) };
}

function buildComparePayload() {
  // 把 UI 选择归一化为 dashboardApi.getTimeRangeComparison 的入参。
  const baseStr =
    compareBasePreset.value === "custom"
      ? `custom:${compareBaseCustomStart.value || ""},${compareBaseCustomEnd.value || ""}`
      : compareBasePreset.value;
  const targetStr =
    compareTarget.value === "previous"
      ? "previous"
      : compareTarget.value === "lastYear"
        ? "lastYear"
        : `custom:${compareTargetCustomStart.value || ""},${compareTargetCustomEnd.value || ""}`;
  return { base: baseStr, compare: targetStr, mode: compareMode.value };
}

async function applyCompare() {
  // 仅追加：原 Tab 与既有 KPI 卡片不受影响；切换 / 点击"应用对比"才会触发请求。
  if (compareLoading.value) return;
  compareLoading.value = true;
  compareError.value = "";
  try {
    const payload = buildComparePayload();
    const result = await dashboardApi.getTimeRangeComparison(payload);
    compareData.value = result?.data ?? result ?? null;
  } catch (err) {
    compareError.value = err?.message || "对比数据加载失败";
    compareData.value = null;
  } finally {
    compareLoading.value = false;
  }
}

function setCompareTab(tab) {
  activeCompareTab.value = tab;
  if (tab === "compare" && !compareData.value) {
    const seed = defaultCompareCustomRange();
    compareBaseCustomStart.value = compareBaseCustomStart.value || seed.start;
    compareBaseCustomEnd.value = compareBaseCustomEnd.value || seed.end;
    compareTargetCustomStart.value = compareTargetCustomStart.value || seed.start;
    compareTargetCustomEnd.value = compareTargetCustomEnd.value || seed.end;
    applyCompare();
  }
}

function formatCompareNumber(n, nice = 0) {
  if (n === null || n === undefined || Number.isNaN(Number(n))) return "--";
  const num = Number(n);
  if (nice === 0) return Math.round(num).toLocaleString("zh-CN");
  return num.toFixed(nice);
}

function compareKpiByKey(key) {
  // 把 API 返回的 kpis 数组按 key 索引为对象，便于模板取数。
  const list = compareData.value?.kpis ?? [];
  return list.find((k) => k.key === key) || null;
}

const compareKpiCards = computed(() => {
  // 6 张 KPI 卡：基准 / 对比 / 变化 / 颜色 / sparkline。
  return COMPARE_KPI_DEFS.map((def) => {
    const kpi = compareKpiByKey(def.key);
    const base = Number(kpi?.base ?? 0);
    const cmp = Number(kpi?.compare ?? 0);
    const delta = cmp - base;
    const deltaRate = base === 0 ? 0 : (delta / base) * 100;
    const isUp = delta > 0.0001;
    const isDown = delta < -0.0001;
    const good =
      isUp === false && isDown === false
        ? null
        : (isUp && def.higherIsBetter) || (isDown && !def.higherIsBetter);
    const color = good === null ? COMPARE_COLOR_NEUTRAL : good ? COMPARE_COLOR_FALL : COMPARE_COLOR_RISE;
    return {
      ...def,
      base,
      compare: cmp,
      delta,
      deltaRate,
      direction: isUp ? "up" : isDown ? "down" : "flat",
      color,
      sparkBase: compareData.value?.spark?.base ?? [],
      sparkCompare: compareData.value?.spark?.compare ?? [],
    };
  });
});

function buildSparklinePath(values) {
  // 把 16 个点压成 SVG polyline points，丢给 CSS 背景层渲染。
  if (!Array.isArray(values) || values.length < 2) return "";
  const max = Math.max(...values, 1);
  const min = Math.min(...values, 0);
  const range = Math.max(max - min, 1);
  const width = 100;
  const height = 28;
  const step = width / (values.length - 1);
  return values
    .map((v, i) => {
      const x = i * step;
      const y = height - ((v - min) / range) * height;
      return `${x.toFixed(2)},${y.toFixed(2)}`;
    })
    .join(" ");
}

const compareSparkPointsBase = computed(() =>
  buildSparklinePath(compareData.value?.spark?.base ?? []),
);
const compareSparkPointsCompare = computed(() =>
  buildSparklinePath(compareData.value?.spark?.compare ?? []),
);

const compareTrendPoints = computed(() => {
  const base = compareData.value?.series?.base ?? [];
  const cmp = compareData.value?.series?.compare ?? [];
  const max = Math.max(...base, ...cmp, 1);
  const min = Math.min(...base, ...cmp, 0);
  const range = Math.max(max - min, 1);
  const width = 100;
  const height = 60;
  const len = Math.max(base.length, cmp.length, 1);
  const step = width / (len - 1 || 1);
  const toPath = (arr) =>
    arr
      .map((v, i) => {
        const x = i * step;
        const y = height - ((Number(v) - min) / range) * height;
        return `${x.toFixed(2)},${y.toFixed(2)}`;
      })
      .join(" ");
  return {
    basePath: toPath(base),
    comparePath: toPath(cmp),
    xAxisCount: base.length,
  };
});

function buildConicGradient(slices) {
  // 把分类占比转成 CSS conic-gradient，前端纯 CSS 实现"饼图"。
  const total = slices.reduce((sum, s) => sum + (Number(s.value) || 0), 0);
  if (total <= 0) return "conic-gradient(#e3ebe7 0deg 360deg)";
  const palette = ["#4f8f99", "#e0a050", "#d9c040", "#6dbfa5", "#7a8d89", "#b27f9a"];
  let cursor = 0;
  const parts = [];
  slices.forEach((slice, idx) => {
    const value = Number(slice.value) || 0;
    const deg = (value / total) * 360;
    if (deg <= 0) return;
    const color = palette[idx % palette.length];
    parts.push(`${color} ${cursor.toFixed(2)}deg ${(cursor + deg).toFixed(2)}deg`);
    cursor += deg;
  });
  if (cursor < 360) {
    parts.push(`#e3ebe7 ${cursor.toFixed(2)}deg 360deg`);
  }
  return `conic-gradient(${parts.join(", ")})`;
}

const compareCategorySlices = computed(() => {
  const list = compareData.value?.categories ?? [];
  return {
    base: buildConicGradient(list.map((c) => ({ label: c.label, value: c.base }))),
    compare: buildConicGradient(list.map((c) => ({ label: c.label, value: c.compare }))),
    legend: list,
  };
});

function computeMaxBar(items, key) {
  const max = items.reduce((m, it) => Math.max(m, Number(it[key]) || 0), 0);
  return max || 1;
}

const compareRegionBaseMax = computed(() =>
  computeMaxBar(compareData.value?.regions ?? [], "base"),
);
const compareRegionCompareMax = computed(() =>
  computeMaxBar(compareData.value?.regions ?? [], "compare"),
);

const compareDiffRates = computed(() => {
  const list = compareData.value?.kpis ?? [];
  return list
    .map((kpi) => {
      const base = Number(kpi.base) || 0;
      const cmp = Number(kpi.compare) || 0;
      const diff = base === 0 ? 0 : ((cmp - base) / base) * 100;
      const def = COMPARE_KPI_DEFS.find((d) => d.key === kpi.key);
      const good =
        Math.abs(diff) < 0.05
          ? null
          : (diff > 0 && def?.higherIsBetter) || (diff < 0 && !def?.higherIsBetter);
      const color = good === null ? COMPARE_COLOR_NEUTRAL : good ? COMPARE_COLOR_FALL : COMPARE_COLOR_RISE;
      return {
        key: kpi.key,
        label: kpi.label,
        base,
        compare: cmp,
        diff,
        color,
        nice: def?.nice ?? 1,
        unit: kpi.unit,
      };
    })
    .sort((a, b) => Math.abs(b.diff) - Math.abs(a.diff));
});

const compareTopDiffs = computed(() => compareDiffRates.value.slice(0, 5));

function buildTrendSentence(card) {
  // 差异 Top 卡片：自动拼一段中文描述（基准→对比的差异方向 + 数值）。
  const directionLabel =
    Math.abs(card.diff) < 0.5
      ? "基本持平"
      : card.diff > 0
        ? "上升"
        : "下降";
  const rateText = `${Math.abs(card.diff).toFixed(1)}%`;
  if (card.key === "disposalRate") {
    return card.diff >= 0
      ? `处置率${directionLabel} ${rateText}，整体处置效率有所${card.diff >= 0 ? "提升" : "下滑"}`
      : `处置率${directionLabel} ${rateText}，需关注闭环环节`;
  }
  if (card.key === "avgHandleMinutes") {
    const minutes = Math.round(Math.abs(card.compare - card.base));
    return card.diff >= 0
      ? `平均处置时长${directionLabel} ${rateText}（约延长 ${minutes} 分钟），建议排查拥堵节点`
      : `平均处置时长${directionLabel} ${rateText}（约缩短 ${minutes} 分钟），效率改善`;
  }
  if (card.key === "incidentTotal") {
    return card.diff >= 0
      ? `事件总数${directionLabel} ${rateText}，关注新增风险点`
      : `事件总数${directionLabel} ${rateText}，风险面收窄`;
  }
  if (card.key === "onlineRate") {
    return card.diff >= 0
      ? `在线设备率${directionLabel} ${rateText}，设备健康度改善`
      : `在线设备率${directionLabel} ${rateText}，排查离线设备`;
  }
  if (card.key === "falseAlarmRate") {
    return card.diff >= 0
      ? `误报率${directionLabel} ${rateText}，复核识别规则`
      : `误报率${directionLabel} ${rateText}，识别更精准`;
  }
  return card.diff >= 0
    ? `${card.label}${directionLabel} ${rateText}`
    : `${card.label}${directionLabel} ${rateText}`;
}

function openCompareDeepAnalysis() {
  // 占位：保持与现有"打开任务/通知"一致的提示风格；不实际跳转路由。
  emit("notify", "已记录：深入分析模块尚在规划中，敬请期待。");
}

function buildCompareCsv() {
  // 拼接 CSV：KPI 明细 + 类别 + 区域，便于运维侧留档。
  const lines = [];
  lines.push("维度,基准值,对比值,差异率(%),单位,描述");
  compareDiffRates.value.forEach((row) => {
    lines.push(
      [
        row.label,
        formatCompareNumber(row.base, row.nice),
        formatCompareNumber(row.compare, row.nice),
        row.diff.toFixed(2),
        row.unit,
        buildTrendSentence(row).replace(/,/g, "，"),
      ].join(","),
    );
  });
  lines.push("");
  lines.push("类别,基准,对比");
  (compareData.value?.categories ?? []).forEach((c) => {
    lines.push(`${c.label},${c.base},${c.compare}`);
  });
  lines.push("");
  lines.push("区域,基准,对比");
  (compareData.value?.regions ?? []).forEach((r) => {
    lines.push(`${r.name},${r.base},${r.compare}`);
  });
  return "\uFEFF" + lines.join("\n");
}

async function exportCompareReport(format) {
  // 离线/在线统一入口：PDF 走 window.print；CSV 走 Blob 下载。
  if (compareExporting.value) return;
  compareExporting.value = true;
  try {
    if (!compareData.value) await applyCompare();
    if (format === "csv") {
      const csv = buildCompareCsv();
      const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
      const url = URL.createObjectURL(blob);
      const a = document.createElement("a");
      const stamp = new Date().toISOString().replace(/[:.]/g, "-");
      a.href = url;
      a.download = `compare-report-${stamp}.csv`;
      document.body.appendChild(a);
      a.click();
      a.remove();
      URL.revokeObjectURL(url);
    } else {
      // PDF 走打印：用户可在打印对话框中"另存为 PDF"。
      window.print();
    }
  } finally {
    compareExporting.value = false;
  }
}

function toggleCompareLegend(which) {
  compareLegendVisible.value = { ...compareLegendVisible.value, [which]: !compareLegendVisible.value[which] };
}
</script>

<template>
  <div class="overview-page">
    <div v-if="loading" class="panel" style="padding: 24px">
      正在加载态势数据...
    </div>
    <template v-else>
      <!-- PRD 1.4：多时相对比 Tab 导航（仅追加，原 Tab 与 KPI 卡片不受影响） -->
      <nav class="overview-tab-bar" role="tablist">
        <button
          type="button"
          class="overview-tab-btn"
          :class="{ active: activeCompareTab === 'overview' }"
          role="tab"
          :aria-selected="activeCompareTab === 'overview'"
          @click="setCompareTab('overview')"
        >
          <BarChart3 :size="14" />
          <span>总览</span>
        </button>
        <button
          type="button"
          class="overview-tab-btn"
          :class="{ active: activeCompareTab === 'compare' }"
          role="tab"
          :aria-selected="activeCompareTab === 'compare'"
          @click="setCompareTab('compare')"
        >
          <GitCompareArrows :size="14" />
          <span>多时相对比</span>
        </button>
      </nav>

      <div v-show="activeCompareTab === 'overview'">
        <div class="stats-grid">
        <div v-for="stat in roleOverview" :key="stat.label" class="stat-card">
          <div class="stat-icon" :class="stat.color">
            <component
              :is="
                stat.color === 'teal'
                  ? Radar
                  : stat.color === 'blue'
                    ? Radio
                    : stat.color === 'orange'
                      ? ShieldAlert
                      : ListChecks
              "
              :size="19"
            />
          </div>
          <div class="stat-copy">
            <span>{{ stat.label }}</span>
            <strong
              >{{ stat.value }} <small>{{ stat.suffix }}</small></strong
            >
            <em class="positive"
              >{{ stat.change.split(" ")[0] }}
              <i>{{ stat.change.split(" ").slice(1).join(" ") }}</i></em
            >
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
              <button class="filter-btn" @click="refreshMapData">
                <ChevronDown :size="14" />刷新
              </button>
              <button
                class="filter-btn"
                :class="{
                  'is-locating': locating,
                  'is-active': userLocation && userLocation.grade?.color === 'teal',
                  'is-warning': userLocation && userLocation.grade?.color === 'amber',
                  'is-danger': userLocation && userLocation.grade?.color === 'red',
                }"
                :disabled="locating"
                :title="userLocation
                  ? `已定位（${userLocation.grade?.label || '精度未知'} ±${userLocation.accuracy} 米），点击重新定位`
                  : '定位到我的位置'"
                @click="locateMe"
              >
                <Loader2 v-if="locating" :size="14" class="spin" />
                <Crosshair v-else :size="14" />
                <span v-if="userLocation && !locating" class="loc-grade-pill" :class="`grade-${userLocation.grade?.color || 'amber'}`">
                  {{ userLocation.grade?.label || '定位' }}
                </span>
                <span v-else>{{ locating ? "定位中" : "我的位置" }}</span>
              </button>
              <button
                v-if="userLocation"
                class="filter-btn"
                title="关闭定位标记"
                @click="clearMyLocation"
              >
                <X :size="14" />清除定位
              </button>
              <button class="filter-btn" @click="toggleMapMode">
                <Landmark :size="14" />{{
                  mapMode === "vector" ? "切换卫星" : "切换矢量"
                }}
              </button>
              <button
                class="filter-btn"
                :class="{ 'is-sentinel-active': sentinelMeta?.s2 || sentinelMeta?.sar }"
                :disabled="!!sentinelJob"
                title="合成 Sentinel-1（雷达）+ Sentinel-2（光学）卫星图层"
                @click="startSentinelCompose"
              >
                <Loader2 v-if="sentinelJob" :size="14" class="spin" />
                <Layers v-else :size="14" />
                <span v-if="sentinelJob">
                  {{ describeJobStatus(sentinelJob).label }}
                </span>
                <span v-else-if="sentinelMeta?.s2">合成卫星（已加载）</span>
                <span v-else>合成卫星</span>
              </button>
              <div v-if="sentinelJob" class="sentinel-progress-wrap">
                <div
                  class="sentinel-progress-bar"
                  :class="`tone-${describeJobStatus(sentinelJob).tone}`"
                  :style="{ width: `${describeJobStatus(sentinelJob).percent}%` }"
                ></div>
              </div>
              <button class="square-btn" @click="refreshWeather">
                <CloudRain :size="17" />
              </button>
              <button class="square-btn"><MoreHorizontal :size="17" /></button>
            </div>
          </div>

          <div class="map-toolbar">
            <div class="map-legend">
              <button
                v-for="item in ['全部', '高风险', '关注', '正常']"
                :key="item"
                :class="{ selected: riskFilter === item }"
                @click="
                  riskFilter = item;
                  requestViewportData();
                "
              >
                <span
                  :class="
                    item === '全部'
                      ? 'all-dot'
                      : item === '高风险'
                        ? 'danger-dot'
                        : item === '关注'
                          ? 'warn-dot'
                          : 'safe-dot'
                  "
                ></span
                >{{ item }}
              </button>
            </div>
            <div class="map-tools">
              <!-- 经纬度 / 地址搜索 -->
              <div
                class="map-search"
                :class="{ 'is-open': searchPanelOpen, 'is-loading': searchPanelState === 'loading' }"
              >
                <div class="map-search-input">
                  <Search :size="14" />
                  <input
                    v-model="searchTerm"
                    type="text"
                    placeholder="搜索经纬度（如 108.95,34.27）或地址"
                    spellcheck="false"
                    autocomplete="off"
                    @input="onSearchInput"
                    @keydown.enter.prevent="onSearchSubmit"
                    @keydown.esc="closeSearchPanel"
                    @focus="searchPanelOpen = !!searchTerm.trim()"
                  />
                  <button
                    v-if="searchTerm"
                    class="search-clear"
                    title="清空"
                    @click="clearSearch"
                  >
                    <X :size="12" />
                  </button>
                  <button
                    class="search-submit"
                    title="搜索"
                    @click="onSearchSubmit"
                  >
                    <ChevronRight :size="14" />
                  </button>
                </div>
                <div v-if="searchPanelOpen" class="map-search-panel">
                  <div v-if="searchPanelState === 'loading'" class="search-hint">
                    <Loader2 :size="13" class="spin" />搜索中…
                  </div>
                  <div v-else-if="searchPanelState === 'empty'" class="search-hint">
                    未找到匹配结果
                    <small>提示：可输入"经度,纬度"直接定位</small>
                  </div>
                  <div v-else-if="searchPanelState === 'results'" class="search-results">
                    <button
                      v-for="(item, idx) in searchResults"
                      :key="`${item.lon}-${item.lat}-${idx}`"
                      class="search-result-item"
                      @click="selectSearchResult(item)"
                    >
                      <span class="result-icon">
                        <MapPin :size="13" />
                      </span>
                      <div class="result-body">
                        <strong>{{ item.name }}</strong>
                        <span v-if="item.address">{{ item.address }}</span>
                        <small>{{ item.lon.toFixed(4) }}, {{ item.lat.toFixed(4) }}{{ item.source === 'coord' ? ' · 经纬度' : '' }}{{ item.source === 'osm' ? ' · OSM 兜底' : '' }}</small>
                      </div>
                      <ChevronRight :size="13" class="result-arrow" />
                    </button>
                  </div>
                  <div class="search-footer">
                    <span>由 {{ geocoderKind === 'amap' ? '高德地图' : 'OSM Nominatim' }} 提供搜索</span>
                  </div>
                </div>
              </div>
              <button @click="emit('notify', '已定位到当前监管区域')">
                <MapPin :size="15" />定位
              </button>
              <button
                class="layer-toggle-btn"
                :class="{ 'is-open': layerPanelOpen }"
                :title="layerPanelOpen ? '关闭图层弹窗' : '打开图层弹窗'"
                @click="toggleLayerPanel"
              >
                <SlidersHorizontal :size="15" />图层
              </button>
            </div>
          </div>

          <div class="map-canvas map-real-container">
            <div ref="mapContainer" class="real-map-view"></div>
            <transition name="layer-panel-fade">
              <div v-if="layerPanelOpen" class="layer-popup-panel" @click.stop>
                <header class="layer-popup-head">
                  <strong><SlidersHorizontal :size="14" />图层管理</strong>
                  <button class="layer-popup-close" title="关闭" @click="toggleLayerPanel"><X :size="14" /></button>
                </header>

                <section class="layer-popup-section">
                  <h6>底图</h6>
                  <div class="basemap-row">
                    <button
                      class="basemap-pill"
                      :class="{ active: mapMode === 'vector' }"
                      @click="mapMode === 'vector' || toggleMapMode()"
                    >
                      矢量
                    </button>
                    <button
                      class="basemap-pill"
                      :class="{ active: mapMode === 'satellite' }"
                      @click="mapMode === 'satellite' || toggleMapMode()"
                    >
                      卫星
                    </button>
                  </div>
                  <div class="sentinel-layer-info">
                    <p class="sentinel-info-text">
                      <Layers :size="12" />Sentinel-1（雷达 SAR） + Sentinel-2（光学真彩）合成卫星
                    </p>
                    <p class="sentinel-info-sub">
                      数据来源：Copernicus Data Space / Google Earth Engine
                    </p>
                    <p v-if="sentinelMeta?.s2" class="sentinel-loaded-badge">
                      <span class="badge-dot success"></span>
                      已合成 · {{ sentinelMeta.s2.meta?.provider || "Copernicus" }}
                    </p>
                    <p v-else class="sentinel-idle-badge">
                      <span class="badge-dot idle"></span>
                      未合成 · 点击工具栏「合成卫星」触发
                    </p>
                    <button
                      class="basemap-pill sentinel-compose-btn"
                      :disabled="!!sentinelJob"
                      @click="startSentinelCompose"
                    >
                      <Loader2 v-if="sentinelJob" :size="11" class="spin" />
                      <Layers v-else :size="11" />
                      {{ sentinelJob ? describeJobStatus(sentinelJob).label : "开始合成" }}
                    </button>
                  </div>
                </section>

                <section class="layer-popup-section">
                  <h6>业务图层</h6>
                  <ul class="layer-toggle-list">
                    <li v-for="(label, kind) in LAYER_LABELS" :key="kind">
                      <label class="layer-toggle-row">
                        <input
                          type="checkbox"
                          :checked="businessLayers[kind]"
                          @change="toggleBusinessLayer(kind)"
                        />
                        <span>{{ label }}</span>
                        <small v-if="kind === 'trail'">展示人员活动轨迹</small>
                        <small v-else-if="kind === 'personnel'">实时点位 + 轨迹</small>
                        <small v-else>显示在地图上的{{ label }}点位</small>
                      </label>
                    </li>
                  </ul>
                </section>

                <section class="layer-popup-section weather-section">
                  <h6>国家气象 overlay</h6>
                  <WeatherOverlayLegend
                    :overlays="weatherOverlays"
                    :opacities="weatherOpacities"
                    :refreshing-all="weatherRefreshingAll"
                    @toggle="toggleWeatherOverlay"
                    @refresh="refreshWeatherOverlay"
                    @opacity="onWeatherOpacity"
                    @refresh-all="onWeatherRefreshAll"
                  />
                </section>
              </div>
            </transition>
            <div class="map-overlay-hint">
              系统默认采用 OpenLayers 二维地图；3D 视角仅在灾害点详情页按需加载
            </div>
            <div v-if="selectedPoint" class="point-popover real-map-popover">
              <button class="close-popover" @click="selectedPoint = null">
                <X :size="15" />
              </button>
              <span class="point-type"
                >{{ selectedPoint.type }} · {{ selectedPoint.level }}</span
              >
              <h3>{{ selectedPoint.name }}</h3>
              <p>
                <Gauge :size="14" />{{ selectedPoint.status }}
                <span>实时数据</span>
              </p>
              <p v-if="selectedPoint.aiScore != null" class="ai-popover-score">
                <ShieldAlert :size="14" />AI 综合分
                <strong :class="`ai-tag-${(selectedPoint.aiLevel || '').toLowerCase()}`"
                  >{{ Number(selectedPoint.aiScore).toFixed(1) }} ·
                  {{ aiLevelLabel(selectedPoint.aiLevel) }}</strong
                >
              </p>
              <button @click="open3DView">
                打开 3D 详情 <ChevronRight :size="14" />
              </button>
            </div>
            <PersonnelTooltip
              :person="
                personnelTooltipVisible
                  ? hoveredPersonnel || activePersonnel
                  : null
              "
              :visible="personnelTooltipVisible"
              @close="personnelTooltipVisible = false"
            />
          </div>
        </section>

        <section class="side-column">
          <div class="panel warning-panel">
            <div class="panel-header compact">
              <div>
                <h2>实时预警</h2>
                <p>需重点关注的风险变化</p>
              </div>
              <button
                class="text-btn"
                @click="showAllWarnings = !showAllWarnings"
              >
                {{ showAllWarnings ? "收起" : "查看全部"
                }}<ChevronRight :size="14" />
              </button>
            </div>
            <div class="warning-list">
              <article
                v-for="warning in visibleWarnings"
                :key="warning.id"
                class="warning-item"
                @click="router.push(`/dashboard/warnings/${warning.id}`)"
              >
                <div class="warning-symbol" :class="warning.class">!</div>
                <div class="warning-body">
                  <div class="warning-title">
                    <strong>{{ warning.title }}</strong
                    ><span :class="warning.class">{{ warning.level }}</span>
                  </div>
                  <p>{{ warning.desc }}</p>
                  <small
                    ><MapPin :size="12" />{{ warning.point
                    }}<time>{{ warning.time }}</time></small
                  >
                </div>
              </article>
            </div>
          </div>

          <div class="panel weather-panel">
            <div class="panel-header compact">
              <div>
                <h2>区域环境</h2>
                <p v-if="userLocation">
                  已定位 ·
                  {{ userLocation.lon.toFixed(4) }},
                  {{ userLocation.lat.toFixed(4) }}
                  · ±{{ userLocation.accuracy ?? "--" }}m
                </p>
                <p v-else>
                  {{
                    weather?.location ||
                    (locating ? "正在定位…" : "龙川县")
                  }}
                  · 综合监测
                </p>
              </div>
              <div class="weather-panel-tools">
                <button
                  class="filter-btn loc-mini"
                  :class="{
                    'is-locating': locating,
                    'is-active':
                      userLocation && userLocation.grade?.color === 'teal',
                    'is-warning':
                      userLocation && userLocation.grade?.color === 'amber',
                    'is-danger':
                      userLocation && userLocation.grade?.color === 'red',
                  }"
                  :disabled="locating"
                  :title="
                    locating
                      ? '正在定位…'
                      : userLocation
                        ? `已定位（${userLocation.grade?.label || '精度未知'} ±${userLocation.accuracy} 米），点击重新定位`
                        : '点击获取我的位置'
                  "
                  @click="locateMe()"
                >
                  <Loader2 v-if="locating" :size="13" class="spin" />
                  <Crosshair v-else :size="13" />
                  <span
                    v-if="userLocation && !locating"
                    class="loc-grade-pill"
                    :class="`grade-${userLocation.grade?.color || 'amber'}`"
                  >
                    {{ userLocation.grade?.label || "定位" }}
                  </span>
                  <span v-else>{{ locating ? "定位中" : "我的位置" }}</span>
                </button>
                <CloudRain :size="22" class="weather-cloud" />
              </div>
            </div>
            <div class="weather-main">
              <div>
                <strong v-if="weatherLoading">--<small>°C</small></strong>
                <strong v-else
                  >{{ weather?.temperature ?? "--" }}<small>°C</small></strong
                >
                <span v-if="weatherLoading">正在获取实时天气...</span>
                <span v-else>{{ weather?.description }}</span>
              </div>
              <div class="weather-detail">
                <p><Waves :size="14" />湿度 {{ weather?.humidity ?? "--" }}%</p>
                <p>
                  <CloudRain :size="14" />降雨概率
                  {{ weather?.precipitationProbability ?? "--" }}%
                </p>
                <p>
                  <Gauge :size="14" />气压 {{ weather?.pressure ?? "--" }} hPa
                </p>
              </div>
            </div>
            <div class="weather-alert" :class="`alert-${weatherAlertLevel}`">
              <b>实时天气预警等级：{{ weatherAlertLevel }}</b>
              <button @click="refreshWeather">刷新天气</button>
            </div>
            <div class="forecast">
              <span
                ><small>风速</small
                ><strong>{{
                  weather?.windSpeed != null ? `${weather.windSpeed} m/s` : "--"
                }}</strong></span
              >
              <span
                ><small>更新时间</small
                ><strong>{{
                  weather?.updatedAt
                    ? new Date(weather.updatedAt).toLocaleTimeString("zh-CN", {
                        hour: "2-digit",
                        minute: "2-digit",
                      })
                    : "--"
                }}</strong></span
              >
              <span
                ><small>站点</small
                ><strong>{{ weather?.location || "龙川县" }}</strong></span
              >
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
            <button class="text-btn" @click="router.push('/dashboard/tasks')">
              进入任务中心 <ChevronRight :size="14" />
            </button>
          </div>
          <div class="task-summary">
            <div
              class="progress-ring"
              :style="{
                background: `conic-gradient(#56b992 0 ${taskProgress.percent}%, #e8f2ee ${taskProgress.percent}% 100%)`,
              }"
            >
              <span>{{ taskProgress.percent }}<small>%</small></span>
            </div>
            <div class="task-overview">
              <strong
                >{{ taskProgress.done }}
                <small>/ {{ taskProgress.total }}</small></strong
              >
              <span>{{ taskProgress.label }}</span>
              <div class="task-bars">
                <i :style="{ width: `${taskProgress.percent}%` }"></i>
              </div>
              <p>
                <b>{{ taskProgress.pending }}</b> 项任务待处理
              </p>
            </div>
            <div class="task-stats">
              <div>
                <strong>{{
                  props.userRole === "maintenance" ? "14" : "86"
                }}</strong
                ><span>{{
                  props.userRole === "maintenance" ? "设备巡检" : "常规巡检"
                }}</span>
              </div>
              <div>
                <strong>{{
                  props.userRole === "attendant" ? "18" : "28"
                }}</strong
                ><span>{{
                  props.userRole === "maintenance" ? "故障工单" : "雨后复查"
                }}</span>
              </div>
              <div>
                <strong>{{ props.userRole === "admin" ? "10" : "6" }}</strong
                ><span>{{
                  props.userRole === "maintenance" ? "待处理告警" : "应急巡检"
                }}</span>
              </div>
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
              <i class="healthy-bar"
                ><b :style="{ width: `${item.progress}%` }"></b
              ></i>
            </div>
          </div>
        </section>

        <section class="panel people-panel">
          <div class="panel-header compact">
            <div>
              <h2>值班与响应</h2>
              <p>
                {{ onlinePersonnel.length }} 人在线 ·
                {{ offlinePersonnel.length }} 人离线
              </p>
            </div>
            <UsersRound :size="20" class="people-icon" />
          </div>
          <div class="people-main">
            <div class="avatars">
              <span
                v-for="person in dutyAvatarList"
                :key="person.id"
                :class="{ offline: person.state !== '在线' }"
                :title="person.name"
                >{{ person.name.charAt(0) }}</span
              >
              <b v-if="dutyMoreCount > 0">+{{ dutyMoreCount }}</b>
              <b v-else-if="!personnel.length" title="暂无值守人员">--</b>
            </div>
            <div>
              <strong>{{ onlinePersonnel.length }}<small> 人</small></strong>
              <span>在线值守 · 共 {{ personnel.length || 0 }} 人</span>
            </div>
            <button
              @click="emit('notify', '已打开值班人员通讯录')"
              @dblclick="peopleModalOpen = true"
            >
              通讯录 <ChevronRight :size="14" />
            </button>
          </div>
          <div class="people-list">
            <div
              v-for="person in sortedPersonnel.slice(0, 3)"
              :key="person.id"
              class="people-item"
              @click="locatePersonnel(person)"
            >
              <span
                class="p-status"
                :class="`${person.state === '在线' ? 'online' : 'offline'}`"
                ><i></i>{{ person.state }}</span
              >
              <span class="p-name">{{ person.name }}</span>
              <span class="p-role"
                >{{ person.role }} · {{ person.team
                }}<template v-if="person.region">
                  · {{ person.region }}</template
                ></span
              >
              <div class="p-actions">
                <button
                  :title="`定位 ${person.name}`"
                  @click.stop="locatePersonnel(person)"
                >
                  <MapPin :size="12" />
                </button>
                <button
                  :title="`查看 ${person.name} 轨迹`"
                  @click.stop="openPersonnelRoute(person)"
                >
                  <Route :size="12" />
                </button>
              </div>
            </div>
            <p v-if="!sortedPersonnel.length" class="people-empty">
              暂无值守人员在线
            </p>
          </div>
          <div class="people-hint">
            <button
              v-if="sortedPersonnel[0]"
              @click="locatePersonnel(sortedPersonnel[0])"
            >
              定位值守人员
            </button>
            <button
              v-for="item in dutyTeamStats"
              :key="item.name"
              :title="`${item.name} ${item.count} 人`"
            >
              {{ item.name }}·{{ item.count }}
            </button>
          </div>
        </section>
      </div>
      </div>

      <!-- PRD 1.4：多时相对比 Tab 内容（顶部控制条 + KPI 矩阵 + 双图对比 + 差异分析） -->
      <div v-show="activeCompareTab === 'compare'" class="compare-tab-pane">
        <!-- 顶部控制条 -->
        <section class="chart-card compare-toolbar">
          <div class="compare-toolbar-grid">
            <div class="compare-toolbar-group">
              <label class="compare-toolbar-label">基准时段</label>
              <div class="compare-toolbar-row">
                <select v-model="compareBasePreset" class="compare-select">
                  <option value="24h">最近 24 小时</option>
                  <option value="7d">最近 7 天</option>
                  <option value="30d">最近 30 天</option>
                  <option value="custom">自定义</option>
                </select>
                <template v-if="compareBasePreset === 'custom'">
                  <input
                    v-model="compareBaseCustomStart"
                    type="datetime-local"
                    class="compare-input"
                  />
                  <span class="compare-input-sep">至</span>
                  <input
                    v-model="compareBaseCustomEnd"
                    type="datetime-local"
                    class="compare-input"
                  />
                </template>
              </div>
            </div>

            <div class="compare-toolbar-group">
              <label class="compare-toolbar-label">对比时段</label>
              <div class="compare-toolbar-row">
                <select v-model="compareTarget" class="compare-select">
                  <option value="previous">上一同时段</option>
                  <option value="lastYear">去年同时段</option>
                  <option value="custom">自定义</option>
                </select>
                <template v-if="compareTarget === 'custom'">
                  <input
                    v-model="compareTargetCustomStart"
                    type="datetime-local"
                    class="compare-input"
                  />
                  <span class="compare-input-sep">至</span>
                  <input
                    v-model="compareTargetCustomEnd"
                    type="datetime-local"
                    class="compare-input"
                  />
                </template>
              </div>
            </div>

            <div class="compare-toolbar-group">
              <label class="compare-toolbar-label">对比模式</label>
              <div class="compare-toolbar-row">
                <select v-model="compareMode" class="compare-select">
                  <option value="side">并排</option>
                  <option value="overlay">叠加</option>
                  <option value="delta">差异率</option>
                </select>
              </div>
            </div>

            <div class="compare-toolbar-actions">
              <button
                class="compare-btn compare-btn-primary"
                :disabled="compareLoading"
                @click="applyCompare"
              >
                <RefreshCw v-if="compareLoading" :size="14" class="spin" />
                <GitCompareArrows v-else :size="14" />
                <span>{{ compareLoading ? "加载中" : "应用对比" }}</span>
              </button>
              <button
                class="compare-btn"
                :disabled="compareExporting || !compareData"
                @click="exportCompareReport('csv')"
              >
                <Download :size="14" />
                <span>导出 CSV</span>
              </button>
              <button
                class="compare-btn"
                :disabled="compareExporting || !compareData"
                @click="exportCompareReport('pdf')"
              >
                <Printer :size="14" />
                <span>导出对比报告 (PDF)</span>
              </button>
            </div>
          </div>
        </section>

        <p v-if="compareError" class="compare-error">{{ compareError }}</p>

        <!-- KPI 矩阵（并排模式） -->
        <section v-if="compareData" class="compare-kpi-grid">
          <article
            v-for="card in compareKpiCards"
            :key="card.key"
            class="compare-kpi-card"
          >
            <header class="compare-kpi-head">
              <span class="compare-kpi-label">{{ card.label }}</span>
              <span class="compare-kpi-unit">{{ card.unit }}</span>
            </header>
            <div class="compare-kpi-body">
              <div class="compare-kpi-base">
                <strong>{{ formatCompareNumber(card.base, card.nice) }}</strong>
                <em>基准</em>
              </div>
              <div class="compare-kpi-compare">
                <div class="compare-kpi-compare-line">
                  <ArrowUpRight v-if="card.direction === 'up'" :size="13" :color="card.color" />
                  <ArrowDownRight v-else-if="card.direction === 'down'" :size="13" :color="card.color" />
                  <span v-else class="compare-kpi-flat">—</span>
                  <strong :style="{ color: card.color }">{{ formatCompareNumber(card.compare, card.nice) }}</strong>
                </div>
                <em :style="{ color: card.color }">
                  {{ card.direction === 'flat' ? '持平' : (card.direction === 'up' ? '+' : '') + card.deltaRate.toFixed(1) + '%' }}
                </em>
              </div>
            </div>
            <div class="compare-kpi-spark">
              <svg
                class="compare-kpi-spark-line"
                viewBox="0 0 100 28"
                preserveAspectRatio="none"
                aria-hidden="true"
              >
                <polyline
                  v-if="compareSparkPointsBase"
                  :points="compareSparkPointsBase"
                  fill="none"
                  :stroke="COMPARE_COLOR_BASE"
                  stroke-width="1.5"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
                <polyline
                  v-if="compareSparkPointsCompare"
                  :points="compareSparkPointsCompare"
                  fill="none"
                  :stroke="COMPARE_COLOR_COMPARE"
                  stroke-width="1.5"
                  stroke-dasharray="3 3"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
              <div class="compare-kpi-spark-legend">
                <span class="dot dot-base"></span>基准
                <span class="dot dot-compare"></span>对比
              </div>
            </div>
          </article>
        </section>

        <!-- 双图对比 -->
        <section v-if="compareData" class="compare-charts-grid">
          <!-- 事件趋势 -->
          <article class="chart-card compare-chart-card">
            <header class="panel-header compact">
              <div>
                <h2>事件趋势对比</h2>
                <p>基准时段 vs 对比时段 · 折线 / 虚线切换</p>
              </div>
              <div class="compare-chart-legend">
                <button
                  type="button"
                  class="compare-legend-btn"
                  :class="{ off: !compareLegendVisible.base }"
                  @click="toggleCompareLegend('base')"
                >
                  <span class="line line-base"></span>基准
                </button>
                <button
                  type="button"
                  class="compare-legend-btn"
                  :class="{ off: !compareLegendVisible.compare }"
                  @click="toggleCompareLegend('compare')"
                >
                  <span class="line line-compare"></span>对比
                </button>
              </div>
            </header>
            <div class="compare-trend-chart">
              <svg
                viewBox="0 0 100 60"
                preserveAspectRatio="none"
                aria-label="事件趋势对比折线"
              >
                <line x1="0" y1="60" x2="100" y2="60" stroke="#e3ebe7" stroke-width="0.5" />
                <line x1="0" y1="45" x2="100" y2="45" stroke="#f1f6f4" stroke-width="0.4" stroke-dasharray="2 2" />
                <line x1="0" y1="30" x2="100" y2="30" stroke="#f1f6f4" stroke-width="0.4" stroke-dasharray="2 2" />
                <line x1="0" y1="15" x2="100" y2="15" stroke="#f1f6f4" stroke-width="0.4" stroke-dasharray="2 2" />
                <polyline
                  v-if="compareTrendPoints.comparePath && compareLegendVisible.compare"
                  :points="compareTrendPoints.comparePath"
                  fill="none"
                  :stroke="COMPARE_COLOR_COMPARE"
                  stroke-width="1.4"
                  stroke-dasharray="3 3"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
                <polyline
                  v-if="compareTrendPoints.basePath && compareLegendVisible.base"
                  :points="compareTrendPoints.basePath"
                  fill="none"
                  :stroke="COMPARE_COLOR_BASE"
                  stroke-width="1.6"
                  stroke-linecap="round"
                  stroke-linejoin="round"
                />
              </svg>
              <div class="compare-trend-axis">
                <span>起始</span>
                <span>中段</span>
                <span>结束</span>
              </div>
            </div>
          </article>

          <!-- 灾种分布 -->
          <article class="chart-card compare-chart-card">
            <header class="panel-header compact">
              <div>
                <h2>灾种分布对比</h2>
                <p>左：基准时段 · 右：对比时段</p>
              </div>
            </header>
            <div class="compare-pie-row">
              <div class="compare-pie-block">
                <div
                  class="compare-pie"
                  :style="{ background: compareCategorySlices.base }"
                  aria-label="基准时段灾种分布"
                ></div>
                <ul class="compare-pie-legend">
                  <li v-for="(c, idx) in compareCategorySlices.legend" :key="`b-${c.key}`">
                    <span class="dot" :style="{ background: ['#4f8f99','#e0a050','#d9c040','#6dbfa5','#7a8d89','#b27f9a'][idx % 6] }"></span>
                    <em>{{ c.label }}</em>
                    <strong>{{ c.base }}</strong>
                  </li>
                </ul>
              </div>
              <div class="compare-pie-block">
                <div
                  class="compare-pie"
                  :style="{ background: compareCategorySlices.compare }"
                  aria-label="对比时段灾种分布"
                ></div>
                <ul class="compare-pie-legend">
                  <li v-for="(c, idx) in compareCategorySlices.legend" :key="`c-${c.key}`">
                    <span class="dot" :style="{ background: ['#4f8f99','#e0a050','#d9c040','#6dbfa5','#7a8d89','#b27f9a'][idx % 6] }"></span>
                    <em>{{ c.label }}</em>
                    <strong>{{ c.compare }}</strong>
                  </li>
                </ul>
              </div>
            </div>
          </article>

          <!-- 区域分布 -->
          <article class="chart-card compare-chart-card">
            <header class="panel-header compact">
              <div>
                <h2>区域分布对比</h2>
                <p>横向柱图，左基准 / 右对比</p>
              </div>
            </header>
            <div class="compare-region-grid">
              <div class="compare-region-col">
                <h4>基准时段</h4>
                <ul class="compare-region-bars">
                  <li v-for="r in compareData.regions" :key="`rb-${r.name}`">
                    <span class="compare-region-label">{{ r.name }}</span>
                    <div class="compare-region-track">
                      <i
                        :style="{
                          width: ((r.base / compareRegionBaseMax) * 100).toFixed(1) + '%',
                          background: COMPARE_COLOR_BASE
                        }"
                      ></i>
                    </div>
                    <strong>{{ r.base }}</strong>
                  </li>
                </ul>
              </div>
              <div class="compare-region-col">
                <h4>对比时段</h4>
                <ul class="compare-region-bars">
                  <li v-for="r in compareData.regions" :key="`rc-${r.name}`">
                    <span class="compare-region-label">{{ r.name }}</span>
                    <div class="compare-region-track">
                      <i
                        :style="{
                          width: ((r.compare / compareRegionCompareMax) * 100).toFixed(1) + '%',
                          background: COMPARE_COLOR_COMPARE
                        }"
                      ></i>
                    </div>
                    <strong>{{ r.compare }}</strong>
                  </li>
                </ul>
              </div>
            </div>
          </article>

          <!-- KPI 差异率 -->
          <article class="chart-card compare-chart-card">
            <header class="panel-header compact">
              <div>
                <h2>KPI 差异率</h2>
                <p>对比时段相对基准的偏差幅度（按绝对值排序）</p>
              </div>
            </header>
            <ul class="compare-diff-list">
              <li v-for="row in compareDiffRates" :key="row.key">
                <span class="compare-diff-label">{{ row.label }}</span>
                <div class="compare-diff-track">
                  <i
                    :style="{
                      width: Math.min(100, Math.abs(row.diff) * 2).toFixed(1) + '%',
                      background: row.color
                    }"
                  ></i>
                </div>
                <strong :style="{ color: row.color }">
                  {{ row.diff >= 0 ? '+' : '' }}{{ row.diff.toFixed(1) }}%
                </strong>
              </li>
            </ul>
          </article>
        </section>

        <!-- 差异分析 -->
        <section v-if="compareData" class="compare-diff-section">
          <header class="panel-header compact compare-diff-header">
            <div>
              <h2>差异 Top 5 卡片</h2>
              <p>自动汇总维度差异，附"深入分析"占位入口</p>
            </div>
          </header>
          <div class="compare-diff-cards">
            <article v-for="card in compareTopDiffs" :key="card.key" class="compare-diff-card">
              <header>
                <strong>{{ card.label }}</strong>
                <em :style="{ color: card.color }">
                  {{ card.diff >= 0 ? '+' : '' }}{{ card.diff.toFixed(1) }}%
                </em>
              </header>
              <p class="compare-diff-numbers">
                <span>基准 {{ formatCompareNumber(card.base, card.nice) }} {{ card.unit }}</span>
                <span class="arrow">→</span>
                <span>对比 {{ formatCompareNumber(card.compare, card.nice) }} {{ card.unit }}</span>
              </p>
              <p class="compare-diff-sentence">{{ buildTrendSentence(card) }}</p>
              <button class="compare-diff-link" type="button" @click="openCompareDeepAnalysis">
                <span>深入分析</span>
                <ArrowUpRight :size="13" />
              </button>
            </article>
          </div>
        </section>

        <p v-if="!compareLoading && !compareData" class="compare-empty">
          尚未应用对比，点击右上角"应用对比"开始分析。
        </p>
      </div>

      <PersonnelRouteDrawer
        :person="activePersonnel"
        :visible="personnelRouteVisible"
        @close="personnelRouteVisible = false"
        @focus="locatePersonnel"
      />

      <transition name="modal">
        <div
          v-if="peopleModalOpen"
          class="modal-backdrop"
          @click.self="peopleModalOpen = false"
        >
          <div class="modal modal-wide people-directory">
            <div class="modal-head">
              <div>
                <span class="eyebrow">DUTY DIRECTORY</span>
                <h2>值班人员通讯录</h2>
                <p class="modal-subtitle">
                  在岗人员 {{ personnel.length || 0 }} 人 · 在线
                  {{ onlinePersonnel.length }} 人
                </p>
              </div>
              <button
                class="icon-btn"
                title="关闭"
                @click="peopleModalOpen = false"
              >
                <X :size="17" />
              </button>
            </div>
            <div class="people-dir-list">
              <div
                v-for="person in sortedPersonnel"
                :key="person.id"
                class="people-dir-item"
              >
                <span
                  class="dir-avatar"
                  :class="{ offline: person.state !== '在线' }"
                  >{{ person.name.charAt(0) }}</span
                >
                <div class="dir-info">
                  <strong
                    >{{ person.name }}
                    <small v-if="person.state === '在线'">在线</small
                    ><small v-else class="offline-tag">离线</small></strong
                  >
                  <p>
                    {{ person.role }} · {{ person.team
                    }}<template v-if="person.region">
                      · {{ person.region }}</template
                    ><template v-if="person.shift">
                      · {{ person.shift }}</template
                    ><template v-if="person.phone">
                      · {{ person.phone }}</template
                    >
                  </p>
                </div>
                <div class="dir-actions">
                  <button
                    @click="
                      locatePersonnel(person);
                      peopleModalOpen = false;
                    "
                  >
                    <MapPin :size="13" />定位
                  </button>
                  <button
                    @click="
                      openPersonnelRoute(person);
                      peopleModalOpen = false;
                    "
                  >
                    <Route :size="13" />轨迹
                  </button>
                </div>
              </div>
              <p v-if="!sortedPersonnel.length" class="people-empty">
                暂无值班人员
              </p>
            </div>
          </div>
        </div>
      </transition>
    </template>
    </div>
</template>

<style scoped>
/* =====================================================================
 * 多时相对比 Tab 样式 (PRD 1.4)
 * 颜色基调与 .chart-card / .panel 一致,采用 #2f7f80 / #1f4f4a 作为强调色
 * ===================================================================== */

/* Tab 内容容器 */
.compare-tab-pane {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

/* 顶部控制条:继承自 .chart-card,这里只补内部布局 */
.compare-toolbar-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)) auto;
  gap: 14px 18px;
  align-items: end;
}
.compare-toolbar-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
  min-width: 0;
}
.compare-toolbar-label {
  font-size: 12px;
  color: #4f6769;
  font-weight: 600;
  letter-spacing: 0.02em;
}
.compare-toolbar-row {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
}
.compare-toolbar-actions {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
}
.compare-select,
.compare-input {
  border: 1px solid #d8e3df;
  border-radius: 8px;
  padding: 7px 10px;
  font-size: 13px;
  background: #fff;
  color: #1f3e44;
  min-width: 120px;
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}
.compare-select:focus,
.compare-input:focus {
  outline: none;
  border-color: #2f7f80;
  box-shadow: 0 0 0 3px rgba(47, 127, 128, 0.15);
}
.compare-input-sep {
  color: #6e7f7c;
  font-size: 12px;
}
.compare-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  border: 1px solid #d8e3df;
  border-radius: 8px;
  background: #fff;
  color: #1f3e44;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.18s ease;
}
.compare-btn:hover:not(:disabled) {
  border-color: #2f7f80;
  color: #2f7f80;
  background: #eff9f6;
}
.compare-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.compare-btn-primary {
  background: linear-gradient(135deg, #2f7f80, #1f4f4a);
  color: #fff;
  border-color: transparent;
  font-weight: 600;
}
.compare-btn-primary:hover:not(:disabled) {
  background: linear-gradient(135deg, #379093, #225a55);
  color: #fff;
  border-color: transparent;
}

/* 错误提示 */
.compare-error {
  background: #fbe9e4;
  color: #b04a3a;
  border: 1px solid #f5c8c0;
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 13px;
  margin: 0;
}

/* 空状态 */
.compare-empty {
  margin: 0;
  padding: 48px 20px;
  text-align: center;
  color: #6e7f7c;
  font-size: 14px;
  border: 2px dashed #d8e3df;
  border-radius: 14px;
  background: linear-gradient(180deg, #ffffff, #f8fbfa);
}

/* KPI 矩阵 (并排模式) */
.compare-kpi-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
}
.compare-kpi-card {
  border: 1px solid #e3ebe7;
  background: #fff;
  border-radius: 13px;
  padding: 14px 16px;
  box-shadow: 0 5px 18px rgba(30, 75, 69, 0.025);
  display: flex;
  flex-direction: column;
  gap: 8px;
  transition: box-shadow 0.28s ease, transform 0.28s cubic-bezier(0.25, 0.9, 0.35, 1);
}
.compare-kpi-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 24px rgba(30, 75, 69, 0.08);
}
.compare-kpi-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.compare-kpi-label {
  font-size: 12px;
  color: #4f6769;
  font-weight: 600;
}
.compare-kpi-unit {
  font-size: 10px;
  color: #93a39e;
  background: #f1f5f3;
  padding: 1px 6px;
  border-radius: 999px;
}
.compare-kpi-body {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.compare-kpi-base,
.compare-kpi-compare {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.compare-kpi-base strong,
.compare-kpi-compare strong {
  font-size: 20px;
  color: #1f3e44;
  font-weight: 700;
  line-height: 1.1;
}
.compare-kpi-base em,
.compare-kpi-compare em {
  font-style: normal;
  font-size: 11px;
  color: #93a39e;
}
.compare-kpi-compare-line {
  display: flex;
  align-items: center;
  gap: 4px;
}
.compare-kpi-flat {
  color: #93a39e;
  font-size: 14px;
}
.compare-kpi-spark {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 4px;
}
.compare-kpi-spark-line {
  width: 100%;
  height: 28px;
}
.compare-kpi-spark-legend {
  display: flex;
  gap: 12px;
  font-size: 10px;
  color: #6e7f7c;
  align-items: center;
}
.compare-kpi-spark-legend .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
  margin-right: 3px;
  vertical-align: middle;
}
.compare-kpi-spark-legend .dot-base {
  background: #2f7f80;
}
.compare-kpi-spark-legend .dot-compare {
  background: #b04a3a;
}

/* 图表区域 (并排 / 叠加 / 差异率) */
.compare-charts-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(380px, 1fr));
  gap: 14px;
}
.compare-pie-row {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
}
.compare-pie-block {
  flex: 1 1 240px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.compare-pie {
  width: 100%;
  height: 220px;
}
.compare-pie-legend {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
  color: #4f6769;
}
.compare-trend-chart {
  width: 100%;
  height: 240px;
}
.compare-trend-axis {
  stroke: #d8e3df;
  stroke-width: 1;
}
.compare-chart-legend {
  display: flex;
  gap: 12px;
  font-size: 12px;
  color: #4f6769;
  align-items: center;
  flex-wrap: wrap;
}
.compare-legend-btn {
  background: transparent;
  border: 1px dashed #cbd5e1;
  border-radius: 6px;
  padding: 3px 8px;
  cursor: pointer;
  font-size: 11px;
  color: #475569;
}
.compare-legend-btn:hover {
  border-color: #2f7f80;
  color: #2f7f80;
}

/* 区域柱图 */
.compare-region-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
  gap: 12px;
}
.compare-region-col {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.compare-region-bars {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.compare-region-label {
  font-size: 12px;
  color: #4f6769;
  display: flex;
  justify-content: space-between;
}
.compare-region-track {
  height: 8px;
  background: #f1f5f3;
  border-radius: 4px;
  overflow: hidden;
  position: relative;
}

/* 差异 Top 5 卡片 */
.compare-diff-section {
  /* 复用 .panel / .chart-card 的卡片外观 */
}
.compare-diff-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 12px;
  margin-top: 10px;
}
.compare-diff-card {
  border: 1px solid #e3ebe7;
  background: #fff;
  border-radius: 12px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  box-shadow: 0 4px 14px rgba(30, 75, 69, 0.025);
}
.compare-diff-card header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.compare-diff-card header strong {
  color: #1f3e44;
  font-size: 13px;
  font-weight: 600;
}
.compare-diff-card header em {
  font-style: normal;
  font-size: 12px;
  font-weight: 600;
}
.compare-diff-numbers {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #4f6769;
  margin: 0;
}
.compare-diff-numbers .arrow {
  color: #2f7f80;
  font-weight: 700;
}
.compare-diff-sentence {
  font-size: 12px;
  color: #6e7f7c;
  margin: 0;
}
.compare-diff-link {
  background: transparent;
  border: 0;
  color: #2f7f80;
  font-size: 12px;
  padding: 0;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  align-self: flex-start;
}
.compare-diff-link:hover {
  color: #1f4f4a;
  text-decoration: underline;
}

/* 差异维度列表 (Top N) */
.compare-diff-list {
  list-style: none;
  padding: 0;
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.compare-diff-list li {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 0;
  border-bottom: 1px dashed #ecf2ef;
}
.compare-diff-list li:last-child {
  border-bottom: 0;
}
.compare-diff-label {
  font-size: 12px;
  color: #4f6769;
}
.compare-diff-track {
  height: 6px;
  background: #f1f5f3;
  border-radius: 3px;
  overflow: hidden;
  position: relative;
}
</style>
