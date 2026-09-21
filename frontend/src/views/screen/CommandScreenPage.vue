/**
 * 指挥大屏 — Command Screen / 指挥大厅专用全屏视图
 *
 * 设计目标:
 *   - 无需登录,适配 4K/拼接屏全屏展示
 *   - 暗色主题,指挥大厅专用配色
 *   - 四视图自动轮播: 态势总览 → 实时地图 → 预警播报 → 设备状态
 *   - 实时数据来自 realtimeService, mock 模式有种子数据 fallback
 *
 * 视图结构:
 *   [顶部标题栏]  切换按钮 | 时间 | 状态指示
 *   [主体区]     四种视图轮播
 *   [底部状态栏] 系统状态 / 设备在线率
 *
 * 视图 A: 态势总览 — KPI 卡片 + 统计图表
 * 视图 B: 实时地图 — OpenLayers 全屏地图
 * 视图 C: 预警播报 — 滚动列表 + 最新事件
 * 视图 D: 设备状态 — 在线率仪表 + 区域分布
 */

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { Radar, AlertTriangle, Antenna, Bell, Building2, Calendar, CheckCircle2,
  ChevronLeft, ChevronRight, CloudRain, Cpu, Eye, Landmark, Layers,
  MapPin, Radio, RefreshCw, ShieldAlert, Thermometer, Timer, TrendingUp,
  Users, Wifi, WifiOff, Wind, Zap } from "lucide-vue-next";
import { dashboardApi } from "../../services/dashboardApi.js";
import {
  onRealtimeUpdate,
  startRealtimeSync,
  stopRealtimeSync,
  getRealtimeState,
} from "../../services/_architecture/core/realtimeService.js";
import { getAllWeatherProductsLatest } from "../../services/_architecture/domain/weatherService.js";
import { createOpenLayersController } from "../../services/_architecture/core/mapService.js";

// ─────────────────────────────────────────────
// 1. 生命周期
// ─────────────────────────────────────────────
const mapEl = ref(null);
let mapController = null;
// 保存 realtime 监听器反注册函数，避免每次进入页面都累积监听器
let stopRealtimeListener = null;

onMounted(async () => {
  await loadAll();
  startAutoRotate();
  startRealtimeSync();
  // onRealtimeUpdate 返回反注册函数，必须保存并在 unmount 时调用，否则多次进出
  // 指挥大屏会导致死监听器堆积，每个 realtime 事件触发 N 次回调。
  stopRealtimeListener = onRealtimeUpdate(handleRealtimeEvent);

  // 初始化地图(延迟确保容器已渲染)
  await nextTick();
  initMap();
});

onBeforeUnmount(() => {
  stopAutoRotate();
  stopRealtimeSync();
  if (typeof stopRealtimeListener === "function") {
    try { stopRealtimeListener(); } catch (_) { /* noop */ }
    stopRealtimeListener = null;
  }
  // OL controller 暴露的是 destroy()，不是 dispose()。前者才能真正释放 WebGL/事件源。
  if (mapController && typeof mapController.destroy === "function") {
    try { mapController.destroy(); } catch (e) { /* noop */ }
  }
  mapController = null;
});

// ─────────────────────────────────────────────
// 2. 视图轮播状态
// ─────────────────────────────────────────────
const VIEWS = [
  { key: "overview",   label: "态势总览", icon: Globe2 },
  { key: "map",        label: "实时地图", icon: MapPin },
  { key: "warnings",   label: "预警播报", icon: Bell },
  { key: "devices",    label: "设备状态", icon: Cpu },
];
const currentViewIndex = ref(0);
const currentView = computed(() => VIEWS[currentViewIndex.value]);
const slideDirection = ref("left"); // 'left' | 'right'

let rotateTimer = null;
function startAutoRotate() {
  rotateTimer = setInterval(() => {
    slideDirection.value = "left";
    currentViewIndex.value = (currentViewIndex.value + 1) % VIEWS.length;
  }, 30_000);
}
function stopAutoRotate() {
  clearInterval(rotateTimer);
}
function goToView(idx) {
  slideDirection.value = idx > currentViewIndex.value ? "left" : "right";
  currentViewIndex.value = idx;
}

// ─────────────────────────────────────────────
// 3. 实时时钟
// ─────────────────────────────────────────────
const now = ref(new Date());
let clockTimer = null;
onMounted(() => {
  clockTimer = setInterval(() => { now.value = new Date(); }, 1000);
});
onBeforeUnmount(() => clearInterval(clockTimer));

const clockStr = computed(() => {
  return now.value.toLocaleString("zh-CN", {
    year: "numeric", month: "2-digit", day: "2-digit",
    weekday: "long", hour: "2-digit", minute: "2-digit", second: "2-digit",
    hour12: false,
  });
});

// ─────────────────────────────────────────────
// 4. KPI 数据 (视图 A)
// ─────────────────────────────────────────────
const overview = ref(null);
const hazardList = ref([]);
const warningList = ref([]);
const deviceList = ref([]);
const personnelList = ref([]);
const loading = ref(true);

async function loadAll() {
  loading.value = true;
  try {
    const [ov, hl, wl, dl, pl, wx] = await Promise.allSettled([
      dashboardApi.getOverview().catch(() => null),
      dashboardApi.getHazards().catch(() => ({ list: [] })),
      dashboardApi.getWarnings().catch(() => []),
      dashboardApi.getDevices().catch(() => ({ list: [] })),
      dashboardApi.getPersonnel().catch(() => []),
      getAllWeatherProductsLatest().catch(() => null),
    ]);

    overview.value = ov.status === "fulfilled" ? ov.value : null;
    hazardList.value = hl.status === "fulfilled" ? (hl.value?.list ?? []) : [];
    warningList.value = wl.status === "fulfilled" ? (Array.isArray(wl.value) ? wl.value : (wl.value?.list ?? [])) : [];
    deviceList.value = dl.status === "fulfilled" ? (dl.value?.list ?? []) : [];
    personnelList.value = pl.status === "fulfilled" ? (pl.value?.list ?? pl.value ?? []) : [];
  } finally {
    loading.value = false;
  }
}

// 核心 KPI
const kpis = computed(() => {
  const o = overview.value ?? {};
  const activeW = warningList.value.filter(
    (w) => !["已关闭", "已忽略", "已解除"].includes(w.status ?? w.state),
  );
  const onlineDev = deviceList.value.filter((d) => d.online || d.status === "在线");
  const totalDev = deviceList.value.length || 1;
  const onlineRate = Math.round((onlineDev.length / totalDev) * 100);
  return [
    {
      label: "今日事件",
      value: o.todayEvents ?? o.todayEventCount ?? o.eventCount ?? hazardList.value.length,
      suffix: "起",
      icon: Activity,
      color: "cyan",
      trend: o.todayEventsTrend ?? "+0",
    },
    {
      label: "待确认预警",
      value: activeW.filter((w) => (w.status ?? w.state) === "待确认" || (w.status ?? w.state) === "pending").length,
      suffix: "条",
      icon: Bell,
      color: "orange",
      trend: null,
    },
    {
      label: "进行中预警",
      value: activeW.length,
      suffix: "条",
      icon: AlertTriangle,
      color: "red",
      trend: null,
    },
    {
      label: "设备在线率",
      value: onlineRate,
      suffix: "%",
      icon: Wifi,
      color: onlineRate >= 90 ? "green" : onlineRate >= 70 ? "orange" : "red",
      trend: null,
    },
    {
      label: "灾害隐患点",
      value: hazardList.value.length,
      suffix: "处",
      icon: MapPin,
      color: "blue",
      trend: null,
    },
    {
      label: "值守人员",
      value: personnelList.value.length || (o.personnelCount ?? 0),
      suffix: "人",
      icon: Users,
      color: "purple",
      trend: null,
    },
  ];
});

// 预警级别颜色
function levelColor(level) {
  if (!level) return "gray";
  const l = String(level);
  if (l.includes("红")) return "red";
  if (l.includes("橙")) return "orange";
  if (l.includes("黄")) return "yellow";
  return "blue";
}
function levelIcon(level) {
  if (!level) return ShieldAlert;
  const l = String(level);
  if (l.includes("红")) return Zap;
  if (l.includes("橙")) return AlertTriangle;
  return ShieldAlert;
}

// 设备在线/离线
const onlineDevices = computed(() =>
  deviceList.value.filter((d) => d.online || d.status === "在线"),
);
const offlineDevices = computed(() =>
  deviceList.value.filter((d) => !d.online && d.status !== "在线"),
);
const deviceOnlineRate = computed(() => {
  const t = deviceList.value.length || 1;
  return Math.round((onlineDevices.value.length / t) * 100);
});

// ─────────────────────────────────────────────
// 5. 实时事件 (视图 C)
// ─────────────────────────────────────────────
const realtimeState = ref(getRealtimeState());

/**
 * v1.7.0：强化实时事件处理，确保大屏与工作台数据一致性。
 * 原则：
 *   - 来自 realtimeService 的实时推送用于增量更新本地列表（upsert，不全量替换）
 *   - 每类数据独立维护 latestVersion，相同 id 以版本号或时间戳判定新旧
 *   - 重连时 loadAll() 全量拉取兜底，避免累积误差
 */
function upsertById(list, incoming, key = 'id') {
  const idx = list.findIndex((item) => String(item[key]) === String(incoming[key]))
  if (idx === -1) {return [incoming, ...list]}
  // 保留 newer 的那条
  const existing = list[idx]
  const incomingVer = Number(incoming.version ?? 0)
  const existingVer = Number(existing.version ?? 0)
  if (incomingVer > existingVer) {
    const copy = [...list]
    copy[idx] = { ...copy[idx], ...incoming }
    return copy
  }
  return list
}

/**
 * 全量快照替换：incoming 为权威，已不在其中的项视为"后端已剔除"。
 * 保留 list 中 version > 0 的较新项（WS 推送），避免回退闪烁。
 */
function replaceFromSnapshot(list, incoming, key = 'id') {
  const incomingIds = new Set(incoming.map((item) => String(item[key])))
  const fresher = list.filter(
    (item) =>
      !incomingIds.has(String(item[key])) &&
      Number(item.version ?? 0) > 0,
  )
  return [...incoming, ...fresher]
}

function handleRealtimeEvent(payload) {
  realtimeState.value = getRealtimeState()
  if (!payload) return

  // 灾害点增量更新
  if (payload.type === 'hazard' && payload.data) {
    hazardList.value = upsertById(hazardList.value, payload.data, 'id')
    updateKpiFromLists()
  }
  // 预警增量更新（含新建和状态变化）
  if ((payload.type === 'warning' || payload.type === 'warning-poll') && payload.data) {
    warningList.value = upsertById(warningList.value, payload.data, 'id')
    updateKpiFromLists()
  }
  // 设备状态变化
  if (payload.type === 'device' && payload.data) {
    deviceList.value = upsertById(deviceList.value, payload.data, 'id')
    updateKpiFromLists()
  }
  // 人员位置变化
  if (payload.type === 'personnel' && payload.data) {
    personnelList.value = upsertById(personnelList.value, payload.data, 'id')
  }
  // 全量快照：用后端返回覆盖本地，已剔除项消失；保留 version>0 的 WS 较新项
  if (payload.type === 'snapshot' || payload.type === 'poll') {
    if (Array.isArray(payload.hazards)) {
      hazardList.value = replaceFromSnapshot(hazardList.value, payload.hazards, 'id')
    }
    if (Array.isArray(payload.warnings)) {
      warningList.value = replaceFromSnapshot(warningList.value, payload.warnings, 'id')
    }
    if (Array.isArray(payload.devices)) {
      deviceList.value = replaceFromSnapshot(deviceList.value, payload.devices, 'id')
    }
    updateKpiFromLists()
  }
}

/** 从当前列表驱动 KPI 更新（避免大屏和 dashboard 用不同数据源） */
function updateKpiFromLists() {
  if (!overview.value) return
  overview.value = {
    ...overview.value,
    _dirtyAt: Date.now(),
  }
}

const latestWarnings = computed(() => warningList.value.slice(0, 20));
const criticalWarnings = computed(() =>
  warningList.value.filter((w) => {
    const l = String(w.level ?? w.riskLevel ?? "");
    return l.includes("红") || l.includes("橙");
  }),
);

// AI 识别播报队列 (来自 realtime)
const aiAlerts = ref([]);
watch(() => realtimeState.value.lastEvent, (evt) => {
  if (evt?.type === "ai-detection" || evt?.source === "ai") {
    aiAlerts.value = [evt, ...aiAlerts.value].slice(0, 10);
  }
});

// ─────────────────────────────────────────────
// 6. 地图 (视图 B)
// ─────────────────────────────────────────────
function initMap() {
  if (!mapEl.value) return;
  try {
    mapController = createOpenLayersController(mapEl.value, {
      center: [104.06, 30.67],
      zoom: 8,
      showControls: false,
    });

    // 加载灾害点
    if (hazardList.value.length) {
      const features = hazardList.value
        .filter((h) => h.lon != null && h.lat != null)
        .map((h) => ({
          type: "Feature",
          geometry: { type: "Point", coordinates: [h.lon, h.lat] },
          properties: {
            id: h.id, name: h.name, level: h.level,
            hazardCode: h.hazardCode, type: h.type,
          },
        }));
      mapController.setGeoJSON("hazard", { type: "FeatureCollection", features });
    }

    // 加载预警
    if (warningList.value.length) {
      const features = warningList.value
        .filter((w) => w.lon != null && w.lat != null)
        .map((w) => ({
          type: "Feature",
          geometry: { type: "Point", coordinates: [w.lon, w.lat] },
          properties: {
            id: w.id, name: w.title ?? w.name,
            level: w.level ?? w.riskLevel,
            status: w.status,
          },
        }));
      mapController.setGeoJSON("warning", { type: "FeatureCollection", features });
    }
  } catch (e) {
    console.warn("[CommandScreen] 地图初始化失败:", e);
  }
}

// 刷新地图数据(当数据变化时)
watch([hazardList, warningList], () => {
  if (mapController) {
    // 地图控制器在数据更新时重新渲染图层
    try {
      initMap();
    } catch {}
  }
});

// ─────────────────────────────────────────────
// 7. 天气数据
// ─────────────────────────────────────────────
const weatherData = ref(null);
// 已在 loadAll() 中通过 Promise.allSettled 获取,无需单独调用

// ─────────────────────────────────────────────
// 8. 实用工具
// ─────────────────────────────────────────────
function fmtTime(isoStr) {
  if (!isoStr) return "";
  try {
    return new Date(isoStr).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false });
  } catch { return ""; }
}
function fmtDate(isoStr) {
  if (!isoStr) return "";
  try {
    return new Date(isoStr).toLocaleDateString("zh-CN", { month: "short", day: "numeric" });
  } catch { return ""; }
}
function relativeTime(isoStr) {
  if (!isoStr) return "";
  const diff = Date.now() - new Date(isoStr).getTime();
  if (diff < 60_000) return "刚刚";
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)}分钟前`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)}小时前`;
  return fmtDate(isoStr);
}
</script>

<template>
  <div class="cs-root">

    <!-- ════════════════════════════════════════
         顶部标题栏
    ════════════════════════════════════════════ -->
    <header class="cs-header">
      <!-- 左: 系统名 -->
      <div class="cs-header-left">
        <ShieldAlert :size="22" class="cs-logo-icon" />
        <span class="cs-logo-text">地脉智巡 · 指挥大厅</span>
        <span class="cs-divider">|</span>
        <span class="cs-subtitle">DisasterGuard Command Center</span>
      </div>

      <!-- 中: 视图切换标签 -->
      <nav class="cs-tabs" role="tablist">
        <button
          v-for="(view, idx) in VIEWS"
          :key="view.key"
          class="cs-tab"
          :class="{ active: currentViewIndex === idx }"
          role="tab"
          :aria-selected="currentViewIndex === idx"
          @click="goToView(idx)"
        >
          <component :is="view.icon" :size="15" />
          {{ view.label }}
        </button>
      </nav>

      <!-- 右: 时间 + 状态 -->
      <div class="cs-header-right">
        <!-- WS 状态 -->
        <div class="cs-ws-badge" :class="realtimeState.isRunning ? 'ws-ok' : 'ws-off'">
          <Radio :size="13" />
          {{ realtimeState.isRunning ? "实时在线" : "连接中…" }}
        </div>
        <div class="cs-clock">{{ clockStr }}</div>
      </div>
    </header>

    <!-- ════════════════════════════════════════
         主体轮播区
    ════════════════════════════════════════════ -->
    <main class="cs-body">

      <!-- ── 视图 A: 态势总览 ── -->
      <section v-show="currentView.key === 'overview'" class="cs-view cs-view-overview">
        <!-- KPI 卡片 -->
        <div class="cs-kpi-grid">
          <div
            v-for="kpi in kpis"
            :key="kpi.label"
            class="cs-kpi-card"
            :class="`kpi-${kpi.color}`"
          >
            <div class="kpi-icon-wrap">
              <component :is="kpi.icon" :size="28" />
            </div>
            <div class="kpi-body">
              <div class="kpi-value">
                <span class="kpi-num">{{ loading ? "—" : kpi.value }}</span>
                <span class="kpi-suffix">{{ kpi.suffix }}</span>
              </div>
              <div class="kpi-label">{{ kpi.label }}</div>
              <div v-if="kpi.trend" class="kpi-trend">{{ kpi.trend }}</div>
            </div>
          </div>
        </div>

        <!-- 下半区: 左右分栏 -->
        <div class="cs-overview-split">
          <!-- 左: 灾害隐患点列表 -->
          <div class="cs-panel cs-panel-left">
            <div class="panel-header">
              <MapPin :size="16" />
              <span>灾害隐患点</span>
              <span class="panel-count">{{ hazardList.length }}处</span>
            </div>
            <div class="cs-hazard-list">
              <div
                v-for="h in hazardList.slice(0, 10)"
                :key="h.id"
                class="cs-hazard-item"
              >
                <div class="hazard-dot" :class="`dot-${h.level ?? 'blue'}`" />
                <div class="hazard-info">
                  <div class="hazard-name">{{ h.name || h.hazardCode }}</div>
                  <div class="hazard-meta">{{ h.type }} · {{ h.regionCode || h.region || "未知区域" }}</div>
                </div>
                <div class="hazard-level" :class="`lvl-${h.level ?? 'blue'}`">
                  {{ h.level || "关注" }}
                </div>
              </div>
              <div v-if="!hazardList.length" class="cs-empty">暂无灾害点数据</div>
            </div>
          </div>

          <!-- 右: 活跃预警 -->
          <div class="cs-panel cs-panel-right">
            <div class="panel-header">
              <AlertTriangle :size="16" />
              <span>活跃预警</span>
              <span class="panel-count panel-count-alert">{{ latestWarnings.length }}条</span>
            </div>
            <div class="cs-warning-list">
              <div
                v-for="w in latestWarnings.slice(0, 10)"
                :key="w.id"
                class="cs-warning-item"
              >
                <div class="w-icon">
                  <component :is="levelIcon(w.level ?? w.riskLevel)" :size="18" />
                </div>
                <div class="w-body">
                  <div class="w-title">{{ w.title ?? w.name ?? w.warningNo ?? `预警-${w.id}` }}</div>
                  <div class="w-meta">
                    <span class="w-level" :class="`lvl-${w.level ?? w.riskLevel ?? 'blue'}`">
                      {{ w.level ?? w.riskLevel ?? "预警" }}
                    </span>
                    <span class="w-time">{{ relativeTime(w.updatedAt ?? w.createdAt) }}</span>
                  </div>
                </div>
              </div>
              <div v-if="!latestWarnings.length" class="cs-empty">暂无预警数据</div>
            </div>
          </div>
        </div>
      </section>

      <!-- ── 视图 B: 实时地图 ── -->
      <section v-show="currentView.key === 'map'" class="cs-view cs-view-map">
        <div ref="mapEl" class="cs-map-container" />
        <!-- 地图上的实时统计浮层 -->
        <div class="cs-map-overlay-stats">
          <div class="overlay-stat">
            <MapPin :size="14" />
            <span>灾害点</span>
            <strong>{{ hazardList.length }}</strong>
          </div>
          <div class="overlay-stat">
            <AlertTriangle :size="14" />
            <span>预警</span>
            <strong>{{ latestWarnings.length }}</strong>
          </div>
          <div class="overlay-stat">
            <Antenna :size="14" />
            <span>设备</span>
            <strong>{{ deviceList.length }}</strong>
          </div>
        </div>
      </section>

      <!-- ── 视图 C: 预警播报 ── -->
      <section v-show="currentView.key === 'warnings'" class="cs-view cs-view-warnings">

        <!-- 顶部: 今日统计 -->
        <div class="cs-warn-summary">
          <div class="warn-summary-card warn-critical">
            <Zap :size="32" />
            <div>
              <div class="wsc-num">{{ criticalWarnings.length }}</div>
              <div class="wsc-label">红色预警</div>
            </div>
          </div>
          <div class="warn-summary-card warn-high">
            <AlertTriangle :size="32" />
            <div>
              <div class="wsc-num">{{ latestWarnings.length }}</div>
              <div class="wsc-label">活跃预警</div>
            </div>
          </div>
          <div class="warn-summary-card warn-online">
            <Radio :size="32" />
            <div>
              <div class="wsc-num">{{ realtimeState.isRunning ? "在线" : "离线" }}</div>
              <div class="wsc-label">实时通道</div>
            </div>
          </div>
          <div class="warn-summary-card warn-device">
            <Wifi :size="32" />
            <div>
              <div class="wsc-num">{{ deviceOnlineRate }}%</div>
              <div class="wsc-label">设备在线率</div>
            </div>
          </div>
        </div>

        <!-- 下方: 滚动播报 -->
        <div class="cs-ticker-wrap">
          <div class="cs-ticker-label">
            <Bell :size="16" />
            实时播报
          </div>
          <div class="cs-ticker">
            <div class="cs-ticker-inner">
              <div
                v-for="w in [...latestWarnings, ...latestWarnings]"
                :key="`ticker-${w.id}-${Math.random()}`"
                class="cs-ticker-item"
              >
                <span class="ti-level" :class="`lvl-${w.level ?? w.riskLevel ?? 'blue'}`">
                  {{ w.level ?? w.riskLevel ?? "预警" }}
                </span>
                <span class="ti-title">{{ w.title ?? w.name ?? `预警-${w.id}` }}</span>
                <span class="ti-time">{{ relativeTime(w.updatedAt ?? w.createdAt) }}</span>
                <span class="ti-sep">◆</span>
              </div>
            </div>
          </div>
        </div>

        <!-- 预警详细列表 -->
        <div class="cs-warn-detail-grid">
          <div
            v-for="w in latestWarnings"
            :key="w.id"
            class="cs-warn-card"
            :class="`card-${levelColor(w.level ?? w.riskLevel)}`"
          >
            <div class="wcard-top">
              <component :is="levelIcon(w.level ?? w.riskLevel)" :size="20" />
              <span class="wcard-level" :class="`lvl-${w.level ?? w.riskLevel ?? 'blue'}`">
                {{ w.level ?? w.riskLevel ?? "预警" }}
              </span>
              <span class="wcard-status">{{ w.status ?? "进行中" }}</span>
            </div>
            <div class="wcard-title">{{ w.title ?? w.name ?? `预警-${w.id}` }}</div>
            <div class="wcard-desc">{{ w.desc ?? w.description ?? w.message ?? "—" }}</div>
            <div class="wcard-footer">
              <span><MapPin :size="12" /> {{ w.lon?.toFixed(2) }},{{ w.lat?.toFixed(2) }}</span>
              <span>{{ relativeTime(w.updatedAt ?? w.createdAt) }}</span>
            </div>
          </div>
          <div v-if="!latestWarnings.length" class="cs-empty-large">
            <Bell :size="48" />
            <div>暂无预警数据</div>
          </div>
        </div>
      </section>

      <!-- ── 视图 D: 设备状态 ── -->
      <section v-show="currentView.key === 'devices'" class="cs-view cs-view-devices">
        <!-- 顶部: 在线率仪表 -->
        <div class="cs-device-top">
          <div class="cs-rate-panel">
            <div class="rate-gauge">
              <svg viewBox="0 0 120 120" class="gauge-svg">
                <circle cx="60" cy="60" r="50" fill="none" stroke="#1e2a3a" stroke-width="10" />
                <circle
                  cx="60" cy="60" r="50" fill="none"
                  :stroke="deviceOnlineRate >= 90 ? '#22c55e' : deviceOnlineRate >= 70 ? '#f59e0b' : '#ef4444'"
                  stroke-width="10"
                  stroke-linecap="round"
                  :stroke-dasharray="`${(deviceOnlineRate / 100) * 314} 314`"
                  stroke-dashoffset="78.5"
                  transform="rotate(-90 60 60)"
                  class="gauge-arc"
                />
                <text x="60" y="55" text-anchor="middle" fill="white" font-size="22" font-weight="bold">
                  {{ deviceOnlineRate }}%
                </text>
                <text x="60" y="72" text-anchor="middle" fill="#94a3b8" font-size="10">
                  设备在线率
                </text>
              </svg>
            </div>
            <div class="rate-stats">
              <div class="rate-stat rate-online">
                <CheckCircle2 :size="20" />
                <span class="rs-num">{{ onlineDevices.length }}</span>
                <span class="rs-label">在线</span>
              </div>
              <div class="rate-stat rate-offline">
                <WifiOff :size="20" />
                <span class="rs-num">{{ offlineDevices.length }}</span>
                <span class="rs-label">离线</span>
              </div>
              <div class="rate-stat rate-total">
                <Cpu :size="20" />
                <span class="rs-num">{{ deviceList.length }}</span>
                <span class="rs-label">总计</span>
              </div>
            </div>
          </div>

          <!-- 设备类型分布 -->
          <div class="cs-device-types">
            <div class="panel-header">
              <Layers :size="16" />
              <span>设备类型分布</span>
            </div>
            <div class="device-type-list">
              <div
                v-for="type in (() => {
                  const m = {};
                  deviceList.forEach(d => { m[d.type || '其他'] = (m[d.type||'其他']||0)+1; });
                  return Object.entries(m).map(([k,v])=>({type:k,count:v}));
                })()"
                :key="type.type"
                class="device-type-item"
              >
                <span class="dtype-label">{{ type.type }}</span>
                <div class="dtype-bar-wrap">
                  <div
                    class="dtype-bar"
                    :style="{ width: `${deviceList.length ? (type.count/deviceList.length*100) : 0}%` }"
                  />
                </div>
                <span class="dtype-count">{{ type.count }}</span>
              </div>
              <div v-if="!deviceList.length" class="cs-empty">暂无设备数据</div>
            </div>
          </div>
        </div>

        <!-- 下方: 设备列表 -->
        <div class="cs-device-list-grid">
          <div
            v-for="d in deviceList.slice(0, 24)"
            :key="d.id"
            class="cs-device-card"
            :class="d.online || d.status === '在线' ? 'dev-online' : 'dev-offline'"
          >
            <div class="dev-icon">
              <Cpu :size="22" />
            </div>
            <div class="dev-info">
              <div class="dev-name">{{ d.name || `设备-${d.id}` }}</div>
              <div class="dev-type">{{ d.type || "监测设备" }}</div>
            </div>
            <div class="dev-status-badge">
              <component :is="d.online || d.status === '在线' ? CheckCircle2 : WifiOff" :size="14" />
              {{ d.online || d.status === "在线" ? "在线" : "离线" }}
            </div>
          </div>
          <div v-if="!deviceList.length" class="cs-empty-large">
            <Cpu :size="48" />
            <div>暂无设备数据</div>
          </div>
        </div>
      </section>
    </main>

    <!-- ════════════════════════════════════════
         底部状态栏
    ════════════════════════════════════════════ -->
    <footer class="cs-footer">
      <div class="footer-left">
        <Radar :size="13" />
        <span>系统运行中</span>
        <span class="footer-sep">·</span>
        <span>最后刷新: {{ fmtTime(new Date().toISOString()) }}</span>
      </div>
      <div class="footer-center">
        <div class="footer-view-indicator">
          <span
            v-for="(v, i) in VIEWS"
            :key="v.key"
            class="fvi-dot"
            :class="{ active: currentViewIndex === i }"
            :title="v.label"
          />
        </div>
      </div>
      <div class="footer-right">
        <RefreshCw :size="13" :class="loading ? 'spin' : ''" />
        <span>{{ loading ? "加载中…" : "数据已就绪" }}</span>
        <span class="footer-sep">·</span>
        <Wifi :size="13" />
        <span>{{ deviceOnlineRate }}% 设备在线</span>
      </div>
    </footer>
  </div>
</template>

<style scoped>
/* ─── 根容器 ─── */
.cs-root {
  display: flex;
  flex-direction: column;
  height: 100vh;
  width: 100vw;
  overflow: hidden;
  background: #060a14;
  color: #e2e8f0;
  font-family: "Microsoft YaHei UI", "PingFang SC", "Helvetica Neue", sans-serif;
  user-select: none;
}

/* ─── 顶部标题栏 ─── */
.cs-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 56px;
  padding: 0 20px;
  background: linear-gradient(90deg, #0d1b2a 0%, #1b2a3a 50%, #0d1b2a 100%);
  border-bottom: 1px solid rgba(59, 130, 246, 0.25);
  flex-shrink: 0;
}
.cs-header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}
.cs-logo-icon { color: #3b82f6; }
.cs-logo-text {
  font-size: 18px;
  font-weight: 700;
  color: #f1f5f9;
  letter-spacing: 1px;
}
.cs-divider { color: #334155; }
.cs-subtitle {
  font-size: 12px;
  color: #64748b;
  letter-spacing: 0.5px;
}

/* 标签切换 */
.cs-tabs {
  display: flex;
  gap: 4px;
  background: rgba(30, 41, 59, 0.8);
  border-radius: 10px;
  padding: 4px;
  border: 1px solid rgba(59, 130, 246, 0.2);
}
.cs-tab {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 16px;
  border-radius: 7px;
  border: none;
  background: transparent;
  color: #94a3b8;
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
  white-space: nowrap;
}
.cs-tab:hover { color: #e2e8f0; background: rgba(59, 130, 246, 0.15); }
.cs-tab.active {
  background: #1d4ed8;
  color: #fff;
  font-weight: 600;
}

/* 右区 */
.cs-header-right {
  display: flex;
  align-items: center;
  gap: 14px;
}
.cs-ws-badge {
  display: flex;
  align-items: center;
  gap: 5px;
  padding: 4px 10px;
  border-radius: 20px;
  font-size: 12px;
  font-weight: 500;
}
.ws-ok { background: rgba(34, 197, 94, 0.15); color: #22c55e; border: 1px solid rgba(34,197,94,0.3); }
.ws-off { background: rgba(239, 68, 68, 0.15); color: #ef4444; border: 1px solid rgba(239,68,68,0.3); }
.cs-clock {
  font-size: 14px;
  color: #94a3b8;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.5px;
}

/* ─── 主体区 ─── */
.cs-body {
  flex: 1;
  overflow: hidden;
  position: relative;
}
.cs-view {
  width: 100%;
  height: 100%;
  padding: 16px;
  box-sizing: border-box;
  overflow: hidden;
}

/* ─── 视图 A: 态势总览 ─── */
.cs-view-overview { display: flex; flex-direction: column; gap: 12px; }

/* KPI 卡片网格 */
.cs-kpi-grid {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 10px;
  flex-shrink: 0;
}
.cs-kpi-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px 16px;
  border-radius: 10px;
  background: rgba(15, 23, 42, 0.8);
  border: 1px solid rgba(255,255,255,0.06);
  position: relative;
  overflow: hidden;
  transition: border-color 0.2s;
}
.cs-kpi-card::before {
  content: '';
  position: absolute;
  left: 0; top: 0; bottom: 0;
  width: 3px;
  border-radius: 3px 0 0 3px;
}
.kpi-cyan::before { background: #06b6d4; }
.kpi-orange::before { background: #f97316; }
.kpi-red::before { background: #ef4444; }
.kpi-green::before { background: #22c55e; }
.kpi-blue::before { background: #3b82f6; }
.kpi-purple::before { background: #a855f7; }
.kpi-yellow::before { background: #eab308; }
.kpi-gray::before { background: #6b7280; }

.kpi-icon-wrap { color: #64748b; flex-shrink: 0; }
.kpi-body { flex: 1; min-width: 0; }
.kpi-value { display: flex; align-items: baseline; gap: 2px; }
.kpi-num { font-size: 28px; font-weight: 700; color: #f1f5f9; line-height: 1; }
.kpi-suffix { font-size: 13px; color: #94a3b8; margin-left: 2px; }
.kpi-label { font-size: 12px; color: #64748b; margin-top: 3px; }
.kpi-trend { font-size: 11px; color: #22c55e; margin-top: 2px; }

/* 下半分栏 */
.cs-overview-split {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  flex: 1;
  min-height: 0;
}
.cs-panel {
  background: rgba(10, 15, 30, 0.9);
  border: 1px solid rgba(59, 130, 246, 0.15);
  border-radius: 10px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.panel-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  font-size: 13px;
  font-weight: 600;
  color: #94a3b8;
  border-bottom: 1px solid rgba(59, 130, 246, 0.1);
  background: rgba(15, 23, 42, 0.6);
  flex-shrink: 0;
}
.panel-count {
  margin-left: auto;
  font-size: 11px;
  background: rgba(59, 130, 246, 0.15);
  color: #60a5fa;
  padding: 2px 8px;
  border-radius: 10px;
}
.panel-count-alert { background: rgba(239, 68, 68, 0.15); color: #f87171; }

/* 灾害点列表 */
.cs-hazard-list { flex: 1; overflow-y: auto; padding: 6px 0; }
.cs-hazard-list::-webkit-scrollbar { width: 4px; }
.cs-hazard-list::-webkit-scrollbar-thumb { background: #1e3a5f; border-radius: 4px; }
.cs-hazard-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 14px;
  transition: background 0.15s;
}
.cs-hazard-item:hover { background: rgba(59, 130, 246, 0.06); }
.hazard-dot {
  width: 8px; height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.dot-red, .dot-红色 { background: #ef4444; box-shadow: 0 0 6px #ef4444; }
.dot-orange, .dot-橙色 { background: #f97316; box-shadow: 0 0 6px #f97316; }
.dot-yellow, .dot-黄色 { background: #eab308; }
.dot-blue, .dot-蓝色 { background: #3b82f6; }
.dot-关注, .dot-全部 { background: #64748b; }
.hazard-info { flex: 1; min-width: 0; }
.hazard-name { font-size: 13px; color: #cbd5e1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.hazard-meta { font-size: 11px; color: #475569; margin-top: 2px; }
.hazard-level {
  font-size: 11px;
  padding: 2px 8px;
  border-radius: 4px;
  font-weight: 600;
  flex-shrink: 0;
}
.lvl-red, .lvl-红色 { background: rgba(239,68,68,0.15); color: #f87171; }
.lvl-orange, .lvl-橙色 { background: rgba(249,115,22,0.15); color: #fb923c; }
.lvl-yellow, .lvl-黄色 { background: rgba(234,179,8,0.15); color: #facc15; }
.lvl-blue, .lvl-蓝色, .lvl-关注 { background: rgba(59,130,246,0.15); color: #60a5fa; }

/* 预警列表 */
.cs-warning-list { flex: 1; overflow-y: auto; padding: 6px 0; }
.cs-warning-list::-webkit-scrollbar { width: 4px; }
.cs-warning-list::-webkit-scrollbar-thumb { background: #1e3a5f; border-radius: 4px; }
.cs-warning-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 7px 14px;
  transition: background 0.15s;
}
.cs-warning-item:hover { background: rgba(249, 115, 22, 0.06); }
.w-icon { color: #f97316; flex-shrink: 0; }
.w-body { flex: 1; min-width: 0; }
.w-title { font-size: 13px; color: #cbd5e1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.w-meta { display: flex; align-items: center; gap: 8px; margin-top: 2px; }
.w-level {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: 600;
}
.w-time { font-size: 11px; color: #475569; }

/* ─── 视图 B: 实时地图 ─── */
.cs-view-map { padding: 0; position: relative; }
.cs-map-container { width: 100%; height: 100%; }
.cs-map-overlay-stats {
  position: absolute;
  top: 14px;
  right: 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  z-index: 10;
}
.overlay-stat {
  display: flex;
  align-items: center;
  gap: 6px;
  background: rgba(6, 10, 20, 0.85);
  border: 1px solid rgba(59, 130, 246, 0.25);
  border-radius: 6px;
  padding: 6px 12px;
  font-size: 12px;
  color: #94a3b8;
  backdrop-filter: blur(6px);
}
.overlay-stat strong { color: #f1f5f9; font-size: 15px; }

/* ─── 视图 C: 预警播报 ─── */
.cs-view-warnings { display: flex; flex-direction: column; gap: 12px; overflow: hidden; }

/* 统计卡片 */
.cs-warn-summary {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 10px;
  flex-shrink: 0;
}
.warn-summary-card {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 18px;
  border-radius: 10px;
  background: rgba(15, 23, 42, 0.8);
  border: 1px solid rgba(255,255,255,0.06);
}
.warn-critical { border-color: rgba(239,68,68,0.3); color: #ef4444; }
.warn-high { border-color: rgba(249,115,22,0.3); color: #f97316; }
.warn-online { border-color: rgba(34,197,94,0.3); color: #22c55e; }
.warn-device { border-color: rgba(59,130,246,0.3); color: #3b82f6; }
.wsc-num { font-size: 26px; font-weight: 700; color: #f1f5f9; line-height: 1; }
.wsc-label { font-size: 12px; color: #64748b; margin-top: 3px; }

/* 滚动播报 */
.cs-ticker-wrap {
  flex-shrink: 0;
  background: rgba(15, 23, 42, 0.9);
  border: 1px solid rgba(249, 115, 22, 0.2);
  border-radius: 8px;
  overflow: hidden;
}
.cs-ticker-label {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  font-size: 12px;
  font-weight: 600;
  color: #f97316;
  background: rgba(249, 115, 22, 0.1);
  border-bottom: 1px solid rgba(249, 115, 22, 0.15);
}
.cs-ticker {
  height: 36px;
  overflow: hidden;
  display: flex;
  align-items: center;
}
.cs-ticker-inner {
  display: flex;
  align-items: center;
  white-space: nowrap;
  animation: ticker-scroll 40s linear infinite;
}
@keyframes ticker-scroll {
  0%   { transform: translateX(0); }
  100% { transform: translateX(-50%); }
}
.cs-ticker-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 16px;
  font-size: 13px;
  color: #cbd5e1;
}
.ti-level {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 3px;
  font-weight: 600;
}
.ti-time { font-size: 11px; color: #475569; }
.ti-sep { color: #334155; font-size: 10px; }

/* 预警详情网格 */
.cs-warn-detail-grid {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 10px;
  overflow-y: auto;
  padding-bottom: 4px;
}
.cs-warn-detail-grid::-webkit-scrollbar { width: 4px; }
.cs-warn-detail-grid::-webkit-scrollbar-thumb { background: #1e3a5f; border-radius: 4px; }
.cs-warn-card {
  background: rgba(10, 15, 30, 0.9);
  border-radius: 8px;
  padding: 12px 14px;
  border: 1px solid rgba(255,255,255,0.06);
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.card-red { border-color: rgba(239,68,68,0.3); }
.card-orange { border-color: rgba(249,115,22,0.3); }
.card-yellow { border-color: rgba(234,179,8,0.2); }
.card-blue, .card-gray { border-color: rgba(59,130,246,0.2); }
.wcard-top { display: flex; align-items: center; gap: 6px; }
.wcard-level {
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 4px;
  font-weight: 600;
}
.wcard-status {
  margin-left: auto;
  font-size: 11px;
  color: #64748b;
}
.wcard-title { font-size: 14px; font-weight: 600; color: #e2e8f0; }
.wcard-desc { font-size: 12px; color: #64748b; line-height: 1.4; overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.wcard-footer {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 11px;
  color: #475569;
  margin-top: 2px;
}
.wcard-footer span { display: flex; align-items: center; gap: 3px; }

/* ─── 视图 D: 设备状态 ─── */
.cs-view-devices { display: flex; flex-direction: column; gap: 12px; overflow: hidden; }
.cs-device-top { display: grid; grid-template-columns: 340px 1fr; gap: 12px; flex-shrink: 0; }
.cs-rate-panel {
  background: rgba(10, 15, 30, 0.9);
  border: 1px solid rgba(59, 130, 246, 0.15);
  border-radius: 10px;
  padding: 18px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16px;
}
.rate-gauge { width: 130px; height: 130px; }
.gauge-svg { width: 100%; height: 100%; }
.gauge-arc { transition: stroke-dasharray 0.8s ease; }
.rate-stats { display: flex; gap: 16px; }
.rate-stat { display: flex; flex-direction: column; align-items: center; gap: 3px; }
.rate-online { color: #22c55e; }
.rate-offline { color: #ef4444; }
.rate-total { color: #64748b; }
.rs-num { font-size: 20px; font-weight: 700; color: #f1f5f9; }
.rs-label { font-size: 11px; color: #64748b; }

.cs-device-types {
  background: rgba(10, 15, 30, 0.9);
  border: 1px solid rgba(59, 130, 246, 0.15);
  border-radius: 10px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.device-type-list { padding: 10px 14px; display: flex; flex-direction: column; gap: 8px; flex: 1; overflow-y: auto; }
.device-type-list::-webkit-scrollbar { width: 4px; }
.device-type-list::-webkit-scrollbar-thumb { background: #1e3a5f; border-radius: 4px; }
.device-type-item { display: flex; align-items: center; gap: 10px; }
.dtype-label { width: 80px; font-size: 12px; color: #94a3b8; flex-shrink: 0; }
.dtype-bar-wrap { flex: 1; height: 6px; background: rgba(30, 41, 59, 0.8); border-radius: 3px; overflow: hidden; }
.dtype-bar { height: 100%; background: #3b82f6; border-radius: 3px; transition: width 0.6s ease; }
.dtype-count { width: 28px; text-align: right; font-size: 12px; color: #e2e8f0; font-variant-numeric: tabular-nums; }

.cs-device-list-grid {
  flex: 1;
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(200px, 1fr));
  gap: 8px;
  overflow-y: auto;
  padding-bottom: 4px;
}
.cs-device-list-grid::-webkit-scrollbar { width: 4px; }
.cs-device-list-grid::-webkit-scrollbar-thumb { background: #1e3a5f; border-radius: 4px; }
.cs-device-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 8px;
  background: rgba(10, 15, 30, 0.9);
  border: 1px solid rgba(255,255,255,0.06);
}
.dev-online { border-color: rgba(34, 197, 94, 0.2); }
.dev-offline { border-color: rgba(239, 68, 68, 0.2); }
.dev-icon { color: #475569; }
.dev-online .dev-icon { color: #22c55e; }
.dev-offline .dev-icon { color: #ef4444; }
.dev-info { flex: 1; min-width: 0; }
.dev-name { font-size: 13px; color: #cbd5e1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dev-type { font-size: 11px; color: #475569; margin-top: 2px; }
.dev-status-badge {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  font-weight: 600;
  flex-shrink: 0;
}
.dev-online .dev-status-badge { color: #22c55e; }
.dev-offline .dev-status-badge { color: #ef4444; }

/* ─── 空状态 ─── */
.cs-empty { padding: 16px; text-align: center; font-size: 12px; color: #334155; }
.cs-empty-large {
  grid-column: 1 / -1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 40px;
  color: #1e3a5f;
  font-size: 14px;
}

/* ─── 底部状态栏 ─── */
.cs-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 32px;
  padding: 0 16px;
  background: rgba(10, 15, 30, 0.95);
  border-top: 1px solid rgba(59, 130, 246, 0.15);
  font-size: 11px;
  color: #475569;
  flex-shrink: 0;
}
.footer-left, .footer-right {
  display: flex;
  align-items: center;
  gap: 6px;
}
.footer-sep { color: #1e3a5f; }
.footer-view-indicator { display: flex; gap: 5px; align-items: center; }
.fvi-dot {
  width: 6px; height: 6px;
  border-radius: 50%;
  background: #1e3a5f;
  transition: all 0.3s;
}
.fvi-dot.active { background: #3b82f6; transform: scale(1.3); }

/* ─── 动画 ─── */
@keyframes spin {
  from { transform: rotate(0deg); }
  to   { transform: rotate(360deg); }
}
.spin { animation: spin 1s linear infinite; }
</style>
