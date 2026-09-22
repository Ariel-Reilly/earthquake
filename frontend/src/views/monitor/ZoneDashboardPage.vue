<script setup>
/**
 * 灾种 / 区域专项大屏（/dashboard/zone/:kind?[/region?]）。
 *
 * 视觉与 OverviewPage 同源：4 KPI + 灾害点列表 + 14 天折线 + Top 5 风险表 + 关联预警 / 应急小列表。
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  Activity,
  AlertTriangle,
  ChevronLeft,
  ChevronRight,
  Crosshair,
  Filter,
  Layers3,
  ListChecks,
  Loader2,
  MapPin,
  MountainSnow,
  Pause,
  Play,
  Radio,
  RefreshCcw,
  Send,
  TrendingUp,
} from 'lucide-vue-next';
import { dashboardApi } from "../../services/dashboardApi";

const props = defineProps({
  currentTime: { type: String, default: "" },
  userRole: { type: String, default: "admin" },
});
const emit = defineEmits(["notify"]);

const route = useRoute();
const router = useRouter();

const KIND_OPTIONS = [
  { key: "all",      label: "全部",   icon: Filter },
  { key: "滑坡",     label: "滑坡",   icon: MountainSnow },
  { key: "崩塌",     label: "崩塌",   icon: Layers3 },
  { key: "泥石流",   label: "泥石流", icon: Activity },
  { key: "地裂缝",   label: "地裂缝", icon: Crosshair },
  { key: "沉降",     label: "沉降",   icon: TrendingUp },
];

const REGION_OPTIONS = [
  { key: "all",     label: "全部" },
  { key: "龙安镇", label: "龙安镇" },
  { key: "石桥镇", label: "石桥镇" },
  { key: "清河镇", label: "清河镇" },
];

const activeKind = ref("all");
const activeRegion = ref("all");
const activeRange = ref("7d");

const RANGE_OPTIONS = [
  { value: "24h", label: "近 24 小时" },
  { value: "7d",  label: "近 7 天" },
  { value: "30d", label: "近 30 天" },
  { value: "60d", label: "近 60 天" },
];

const autoPlay = ref(false);
const carouselIndex = ref(0);
const carouselItems = computed(() => {
  const items = [];
  KIND_OPTIONS.forEach((k) => {
    REGION_OPTIONS.filter((r) => r.key !== "all").forEach((r) => {
      items.push({ kind: k.key, region: r.key, label: `${k.label} · ${r.label}` });
    });
  });
  return items.length ? items : [{ kind: "all", region: "all", label: "全部 · 全部" }];
});
let carouselTimer = null;

function toggleCarousel() {
  autoPlay.value = !autoPlay.value;
  if (autoPlay.value) {
    carouselTimer = window.setInterval(() => {
      carouselIndex.value = (carouselIndex.value + 1) % carouselItems.value.length;
      const cur = carouselItems.value[carouselIndex.value];
      activeKind.value = cur.kind;
      activeRegion.value = cur.region;
      syncRoute();
    }, 8000);
  } else if (carouselTimer) {
    window.clearInterval(carouselTimer);
    carouselTimer = null;
  }
}

onBeforeUnmount(() => {
  if (carouselTimer) window.clearInterval(carouselTimer);
});

const summary = ref(null);
const trend = ref({ points: [] });
const hazards = ref([]);
const warnings = ref([]);
const incidents = ref([]);
const loading = ref(true);
const loadError = ref("");

const kindLabel = computed(() => {
  return KIND_OPTIONS.find((k) => k.key === activeKind.value)?.label ?? "全部";
});
const regionLabel = computed(() => {
  return REGION_OPTIONS.find((r) => r.key === activeRegion.value)?.label ?? "全部";
});

const stats = computed(() => {
  const s = summary.value;
  if (!s) {
    return [
      { label: "灾害点数", value: "—", color: "teal",   icon: Mountain },
      { label: "在线设备", value: "—", color: "blue",   icon: Radio },
      { label: "预警数",   value: "—", color: "orange", icon: AlertTriangle },
      { label: "待处置",   value: "—", color: "purple", icon: ListChecks },
    ];
  }
  return [
    {
      label: "灾害点数",
      value: String(s.hazardCount ?? 0),
      suffix: "处",
      change: `区域 ${regionLabel.value} · 灾种 ${kindLabel.value}`,
      color: "teal",
      icon: Mountain,
    },
    {
      label: "在线设备",
      value: String(s.onlineDeviceCount ?? 0),
      suffix: "台",
      change: "本专项关联监测设备",
      color: "blue",
      icon: Radio,
    },
    {
      label: "预警数",
      value: String(s.warningCount ?? 0),
      suffix: "起",
      change: "本专项关联活跃预警",
      color: "orange",
      icon: AlertTriangle,
    },
    {
      label: "待处置",
      value: String(s.pendingCount ?? 0),
      suffix: "项",
      change: "待办巡检 / 应急任务",
      color: "purple",
      icon: ListChecks,
    },
  ];
});

const topHazards = computed(() => {
  const list = summary.value?.topHazards ?? hazards.value;
  return list.slice(0, 5);
});

const trendPath = computed(() => {
  const points = trend.value?.points ?? [];
  if (!points.length) return { line: "", area: "", values: [], dates: [] };
  const max = 100;
  const min = 0;
  const width = 540;
  const height = 180;
  const stepX = points.length > 1 ? width / (points.length - 1) : width;
  const coords = points.map((p, i) => {
    const x = i * stepX;
    const y = height - ((p.value - min) / (max - min)) * height;
    return { x, y, value: p.value, date: p.date };
  });
  const line = coords
    .map((c, i) => `${i === 0 ? "M" : "L"} ${c.x.toFixed(1)} ${c.y.toFixed(1)}`)
    .join(" ");
  const area = `${line} L ${width.toFixed(1)} ${height} L 0 ${height} Z`;
  return {
    line,
    area,
    coords,
    values: points.map((p) => p.value),
    dates: points.map((p) => p.date),
  };
});

const trendLatest = computed(() => {
  const pts = trend.value?.points ?? [];
  return pts[pts.length - 1]?.value ?? 0;
});

const trendPrev = computed(() => {
  const pts = trend.value?.points ?? [];
  return pts[pts.length - 2]?.value ?? 0;
});

const trendDelta = computed(() => {
  const a = trendLatest.value;
  const b = trendPrev.value;
  if (!a || !b) return 0;
  return Math.round(((a - b) / b) * 100);
});

function selectKind(kind) {
  if (kind === activeKind.value) return;
  activeKind.value = kind;
  syncRoute();
}

function selectRegion(event) {
  activeRegion.value = event.target.value;
  syncRoute();
}

function syncRoute() {
  const next = { kind: activeKind.value, range: activeRange.value };
  if (activeRegion.value && activeRegion.value !== "all") {
    next.region = activeRegion.value;
  } else {
    delete next.region;
  }
  router.replace({ path: `/dashboard/zone`, query: next }).catch(() => {});
}

function backToOverview() {
  router.push("/dashboard/overview");
}

async function loadAll() {
  loading.value = true;
  loadError.value = "";
  try {
    const filter = { kind: activeKind.value, region: activeRegion.value, range: activeRange.value };
    const [sum, tr, haz] = await Promise.all([
      dashboardApi.getZoneSummary(filter).catch((e) => {
        loadError.value = e?.message ?? "加载专项汇总失败";
        return null;
      }),
      dashboardApi.getZoneRiskTrend(filter).catch(() => ({ points: [] })),
      dashboardApi
        .getHazards({ kind: activeKind.value, region: activeRegion.value })
        .catch(() => ({ list: [] })),
    ]);

    summary.value = sum;
    trend.value = tr ?? { points: [] };
    hazards.value = Array.isArray(haz?.list) ? haz.list : [];

    // 关联预警与应急：优先使用 summary 返回的关联列表；否则尝试预览接口
    warnings.value = sum?.relatedWarnings ?? [];
    incidents.value = sum?.relatedIncidents ?? [];

    if (!warnings.value.length) {
      try {
        const preview = await dashboardApi.getWarningsPreview();
        const all = preview?.list ?? [];
        warnings.value = all.filter((w) => {
          const hazard = hazards.value.find(
            (h) => String(h.id) === String(w.hazardId) || h.code === w.hazardCode,
          );
          return Boolean(hazard);
        });
      } catch (_) {
        warnings.value = [];
      }
    }
    if (!incidents.value.length) {
      try {
        const em = await dashboardApi.getEmergency();
        incidents.value = (em?.incidents ?? []).filter((i) =>
          warnings.value.some(
            (w) => String(w.id) === String(i.warningId),
          ),
        );
      } catch (_) {
        incidents.value = [];
      }
    }
  } catch (error) {
    loadError.value =
      error instanceof Error ? error.message : "加载专项大屏失败";
  } finally {
    loading.value = false;
  }
}

function reload() {
  loadAll();
}

function levelClass(level) {
  const l = String(level ?? "");
  if (l.includes("极高") || l.includes("红")) return "red";
  if (l.includes("高") || l.includes("橙")) return "orange";
  if (l.includes("中") || l.includes("黄")) return "yellow";
  if (l.includes("蓝") || l.includes("低")) return "blue";
  return "neutral";
}

function viewHazard(item) {
  if (!item?.id) return;
  router.push(`/dashboard/hazards/${item.id}`);
}

function viewWarning(item) {
  if (!item?.id) return;
  router.push(`/dashboard/warnings/${item.id}`);
}

onMounted(() => {
  const kindParam = String(route.params.kind ?? "all");
  activeKind.value =
    KIND_OPTIONS.some((k) => k.key === kindParam) ? kindParam : "all";
  const regionParam = String(route.params.region ?? route.query.region ?? "all");
  activeRegion.value = REGION_OPTIONS.some((r) => r.key === regionParam)
    ? regionParam
    : "all";
  loadAll();
});

watch(
  () => [route.params.kind, route.params.region, route.query.region],
  () => {
    const kindParam = String(route.params.kind ?? "all");
    activeKind.value =
      KIND_OPTIONS.some((k) => k.key === kindParam) ? kindParam : "all";
    const regionParam = String(route.params.region ?? route.query.region ?? "all");
    activeRegion.value = REGION_OPTIONS.some((r) => r.key === regionParam)
      ? regionParam
      : "all";
    loadAll();
  },
);
</script>

<template>
  <div class="zone-page">
    <!-- 顶部固定过滤条 -->
    <div class="zone-toolbar panel">
      <div class="toolbar-left">
        <div class="kind-tabs">
          <button
            v-for="opt in KIND_OPTIONS"
            :key="opt.key"
            class="kind-tab"
            :class="{ active: activeKind === opt.key }"
            @click="selectKind(opt.key)"
          >
            <component :is="opt.icon" :size="13" />
            {{ opt.label }}
          </button>
        </div>
        <label class="region-select">
          <MapPin :size="13" />
          <span>区域</span>
          <select :value="activeRegion" @change="selectRegion">
            <option v-for="opt in REGION_OPTIONS" :key="opt.key" :value="opt.key">
              {{ opt.label }}
            </option>
          </select>
        </label>
        <label class="region-select">
          <span>时间</span>
          <select :value="activeRange" @change="(e) => { activeRange = e.target.value; syncRoute(); loadAll(); }">
            <option v-for="opt in RANGE_OPTIONS" :key="opt.value" :value="opt.value">
              {{ opt.label }}
            </option>
          </select>
        </label>
      </div>
      <div class="toolbar-right">
        <span class="current-tag">
          当前：{{ kindLabel }} · {{ regionLabel }} · {{ RANGE_OPTIONS.find(r => r.value === activeRange)?.label ?? activeRange }}
        </span>
        <button class="secondary-btn" @click="toggleCarousel">
          <Pause v-if="autoPlay" :size="13" /> 
          <Play v-else :size="13" />
          {{ autoPlay ? '暂停轮播' : '开始轮播' }}
        </button>
        <button class="secondary-btn" @click="reload">
          <RefreshCcw :size="13" />刷新
        </button>
        <button class="primary-btn" @click="backToOverview">
          <Crosshair :size="13" />返回总览大屏
        </button>
      </div>
    </div>

    <div v-if="loading" class="panel loading-panel">
      <div class="loading-row">
        <Loader2 :size="14" class="spin" />
        正在加载专项大屏数据…
      </div>
    </div>

    <div v-else-if="loadError && !summary" class="panel error-panel">
      <strong>加载失败</strong>
      <p>{{ loadError }}</p>
      <button class="primary-btn" @click="reload">
        <RefreshCcw :size="13" />重试
      </button>
    </div>

    <template v-else>
      <!-- 4 KPI -->
      <section class="stats-grid">
        <div v-for="stat in stats" :key="stat.label" class="stat-card">
          <div class="stat-icon" :class="stat.color">
            <component :is="stat.icon" :size="19" />
          </div>
          <div class="stat-copy">
            <span>{{ stat.label }}</span>
            <strong>{{ stat.value }} <small>{{ stat.suffix }}</small></strong>
            <em class="positive">{{ stat.change }}</em>
          </div>
        </div>
      </section>

      <!-- 主体内容 -->
      <section class="content-grid zone-grid">
        <!-- 折线趋势图 -->
        <section class="panel system-panel chart-panel">
          <div class="panel-header compact">
            <div>
              <h2>{{ kindLabel }}专项 · 近 14 天风险趋势</h2>
              <p>每日综合风险评分均值（区域：{{ regionLabel }}）</p>
            </div>
            <div class="trend-stat">
              <strong>{{ trendLatest }}</strong>
              <span :class="trendDelta >= 0 ? 'trend-up' : 'trend-down'">
                {{ trendDelta >= 0 ? '+' : '' }}{{ trendDelta }}%
              </span>
            </div>
          </div>
          <div class="chart-body">
            <svg viewBox="0 0 560 200" preserveAspectRatio="none" class="trend-svg">
              <defs>
                <linearGradient id="trend-area" x1="0" y1="0" x2="0" y2="1">
                  <stop offset="0%" stop-color="#2f7f80" stop-opacity="0.32" />
                  <stop offset="100%" stop-color="#2f7f80" stop-opacity="0" />
                </linearGradient>
              </defs>
              <g v-if="trendPath.line">
                <path :d="trendPath.area" fill="url(#trend-area)" />
                <path
                  :d="trendPath.line"
                  stroke="#2f7f80"
                  stroke-width="2"
                  fill="none"
                  stroke-linecap="round"
                />
                <g v-for="(c, i) in trendPath.coords" :key="i">
                  <circle :cx="c.x" :cy="c.y" r="3" fill="#fff" stroke="#2f7f80" stroke-width="1.6" />
                </g>
              </g>
              <g v-else>
                <text x="280" y="100" text-anchor="middle" fill="#99a7a1" font-size="12">
                  暂无趋势数据
                </text>
              </g>
            </svg>
            <div class="trend-axis">
              <span v-for="(c, i) in trendPath.coords" :key="i" :title="c.date">
                {{ i === 0 || i === trendPath.coords.length - 1 || i % 3 === 0 ? c.date : '' }}
              </span>
            </div>
          </div>
        </section>

        <!-- 区域内灾害点列表 -->
        <section class="panel warning-panel hazard-panel">
          <div class="panel-header compact">
            <div>
              <h2>区域内灾害点</h2>
              <p>{{ kindLabel }} · {{ regionLabel }}（{{ hazards.length }} 处）</p>
            </div>
          </div>
          <div v-if="!hazards.length" class="panel-empty">
            <MountainSnow :size="20" />
            <p>当前过滤条件下暂无灾害点</p>
            <small>尝试切换灾种或区域，或在「总览大屏」新增灾害点</small>
          </div>
          <ul v-else class="hazard-list">
            <li v-for="item in hazards" :key="item.id" @click="viewHazard(item)">
              <span class="dot" :class="levelClass(item.level)"></span>
              <div class="hazard-info">
                <strong>{{ item.name }}</strong>
                <small>
                  <span>{{ item.type }}</span>
                  <span><MapPin :size="11" />{{ item.owner }}</span>
                  <span>{{ item.status }}</span>
                </small>
              </div>
              <span class="hazard-level" :class="levelClass(item.level)">
                {{ item.level }}
              </span>
            </li>
          </ul>
        </section>
      </section>

      <!-- Top 5 风险灾害点表 -->
      <section class="panel system-panel top-panel">
        <div class="panel-header compact">
          <div>
            <h2>{{ kindLabel }}专项 · Top 5 风险灾害点</h2>
            <p>综合风险评分较高的前 5 个灾害点</p>
          </div>
        </div>
        <div v-if="!topHazards.length" class="panel-empty">
          <ListChecks :size="20" />
          <p>暂无风险较高的灾害点</p>
        </div>
        <div v-else class="table-wrap">
          <table class="data-table">
            <thead>
              <tr>
                <th style="width: 60px">排名</th>
                <th>灾害点</th>
                <th>类型</th>
                <th>区域</th>
                <th>风险等级</th>
                <th>当前状态</th>
                <th>处置</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(item, idx) in topHazards" :key="item.id ?? item.code">
                <td><strong>NO.{{ idx + 1 }}</strong></td>
                <td><strong>{{ item.name }}</strong></td>
                <td>{{ item.type }}</td>
                <td>{{ item.owner }}</td>
                <td>
                  <span class="status-badge" :class="levelClass(item.level)">
                    {{ item.level }}
                  </span>
                </td>
                <td>{{ item.status }}</td>
                <td>
                  <button class="text-btn" @click="viewHazard(item)">查看</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- 关联预警 + 应急事件小列表 -->
      <section class="content-grid bottom-zone-grid">
        <section class="panel warning-panel">
          <div class="panel-header compact">
            <div>
              <h2>关联预警</h2>
              <p>{{ kindLabel }}专项触发的活跃预警（{{ warnings.length }} 起）</p>
            </div>
          </div>
          <div v-if="!warnings.length" class="panel-empty">
            <AlertTriangle :size="20" />
            <p>暂无关联预警</p>
          </div>
          <ul v-else class="warning-list">
            <li v-for="w in warnings" :key="w.id" class="warning-item" @click="viewWarning(w)">
              <span class="warning-symbol" :class="w.cssClass ?? w.class ?? 'orange'">!</span>
              <div class="warning-body">
                <div class="warning-title">
                  <strong>{{ w.title }}</strong>
                  <span :class="w.cssClass ?? w.class ?? 'orange'">{{ w.level }}</span>
                </div>
                <p>{{ w.desc }}</p>
                <small><MapPin :size="11" />{{ w.point }}<time>{{ w.time }}</time></small>
              </div>
            </li>
          </ul>
        </section>

        <section class="panel system-panel">
          <div class="panel-header compact">
            <div>
              <h2>关联应急事件</h2>
              <p>联动应急值守与处置（{{ incidents.length }} 项）</p>
            </div>
          </div>
          <div v-if="!incidents.length" class="panel-empty">
            <Send :size="20" />
            <p>暂无应急联动事件</p>
          </div>
          <div v-else class="system-list">
            <div v-for="i in incidents" :key="i.id">
              <span><AlertTriangle :size="14" />{{ i.title }}</span>
              <strong>{{ i.level ?? '应急响应' }}</strong>
            </div>
            <div v-for="i in incidents" :key="`step-${i.id}`">
              <span><ListChecks :size="14" />当前步骤</span>
              <strong>{{ i.step ?? '待派发' }}</strong>
            </div>
            <div v-for="i in incidents" :key="`owner-${i.id}`">
              <span><Layers3 :size="14" />负责组</span>
              <strong>{{ i.owner ?? '—' }}</strong>
            </div>
          </div>
        </section>
      </section>
    </template>
  </div>
</template>

<style scoped>
.zone-page { display: flex; flex-direction: column; gap: 14px; }

.zone-toolbar {
  position: sticky;
  top: 0;
  z-index: 5;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 18px;
  background: rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(8px);
  flex-wrap: wrap;
}
.toolbar-left {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
  min-width: 0;
}
.kind-tabs {
  display: flex;
  gap: 6px;
  overflow-x: auto;
  flex-wrap: nowrap;
}
.kind-tab {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 7px 11px;
  border: 1px solid #e2ebe8;
  border-radius: 999px;
  background: #fff;
  color: #5b7769;
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
  transition: color .2s, border-color .2s, background .2s;
}
.kind-tab:hover { color: #2f7f80; border-color: #cfe3dd; }
.kind-tab.active {
  color: #2f7f80;
  border-color: #2f7f80;
  background: #eff9f6;
  font-weight: 600;
}

.region-select {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 10px;
  border: 1px solid #e2ebe8;
  border-radius: 10px;
  background: #f8fcfb;
  color: #5b7769;
  font-size: 12px;
}
.region-select select {
  border: 0;
  outline: 0;
  background: transparent;
  color: #24464b;
  font-size: 12px;
  padding: 4px 6px;
  border-radius: 6px;
  cursor: pointer;
}

.toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.current-tag {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 999px;
  background: #ecf8f2;
  color: #2d8280;
  font-size: 11px;
}

.loading-panel { padding: 28px; text-align: center; color: #99a7a1; }
.loading-row { display: inline-flex; align-items: center; gap: 8px; font-size: 12px; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0); } to { transform: rotate(360deg); } }

.error-panel {
  padding: 28px;
  text-align: center;
  display: flex;
  flex-direction: column;
  gap: 10px;
  align-items: center;
  border: 1px dashed #ecc4c4;
  background: #fbecec;
  color: #9c3d3d;
}

.zone-grid { grid-template-columns: minmax(0, 1.45fr) minmax(0, 1fr); }
.bottom-zone-grid {
  margin-top: 15px;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr);
}

.chart-panel { display: flex; flex-direction: column; }
.chart-body {
  padding: 6px 19px 14px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.trend-svg {
  width: 100%;
  height: 200px;
  display: block;
}
.trend-axis {
  display: flex;
  justify-content: space-between;
  color: #99a7a1;
  font-size: 10px;
  padding: 0 4px;
}
.trend-axis span { flex: 1; text-align: center; }
.trend-stat { display: flex; align-items: center; gap: 8px; }
.trend-stat strong { color: #2f7f80; font-size: 16px; }
.trend-up { color: #d88561; font-size: 11px; }
.trend-down { color: #3b9a72; font-size: 11px; }

.hazard-panel { display: flex; flex-direction: column; min-height: 0; }
.hazard-list {
  list-style: none;
  margin: 0;
  padding: 0 19px 18px;
  max-height: 360px;
  overflow-y: auto;
}
.hazard-list li {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 0;
  border-top: 1px solid #f1f6f4;
  cursor: pointer;
}
.hazard-list li:first-child { border-top: 0; }
.hazard-list li:hover { background: #f8fbfa; }
.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  display: inline-block;
  flex: 0 0 auto;
}
.dot.red { background: #e06060; }
.dot.orange { background: #e0a050; }
.dot.yellow { background: #d9c040; }
.dot.blue { background: #6dbfa5; }
.dot.neutral { background: #b0bdb8; }
.hazard-info { flex: 1; min-width: 0; }
.hazard-info strong { color: #365c60; font-size: 12px; }
.hazard-info small {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  color: #99a7a1;
  font-size: 10px;
  margin-top: 4px;
}
.hazard-info small span { display: inline-flex; align-items: center; gap: 3px; }
.hazard-level {
  padding: 3px 9px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 600;
  flex: 0 0 auto;
}
.hazard-level.red { color: #b04848; background: #fbecec; }
.hazard-level.orange { color: #d18f2f; background: #fdf6e9; }
.hazard-level.yellow { color: #a37a18; background: #fbf4dd; }
.hazard-level.blue { color: #2d8280; background: #e4f4ef; }
.hazard-level.neutral { color: #6f8482; background: #f1f6f4; }

.top-panel { padding-bottom: 6px; }
.table-wrap { width: 100%; overflow-x: auto; }
.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
  min-width: 600px;
}
.data-table th,
.data-table td {
  padding: 10px 14px;
  text-align: left;
  border-bottom: 1px solid #f1f6f4;
}
.data-table th {
  background: #f8fbfa;
  color: #6f8482;
  font-weight: 600;
  font-size: 11px;
}
.data-table td strong { color: #365c60; }
.text-btn {
  padding: 4px 10px;
  color: #2f7f80;
  border: 1px solid #dbe6e2;
  border-radius: 8px;
  background: #fff;
  font-size: 11px;
  cursor: pointer;
}
.text-btn:hover { color: #fff; background: #2f7f80; border-color: #2f7f80; }

.status-badge {
  display: inline-block;
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
}
.status-badge.red { color: #b04848; background: #fbecec; }
.status-badge.orange { color: #d18f2f; background: #fdf6e9; }
.status-badge.yellow { color: #a37a18; background: #fbf4dd; }
.status-badge.blue { color: #2d8280; background: #e4f4ef; }
.status-badge.neutral { color: #6f8482; background: #f1f6f4; }

.warning-list { padding: 6px 19px 16px; flex: 1; }
.warning-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-top: 1px solid #f1f6f4;
  cursor: pointer;
}
.warning-item:first-child { border-top: 0; }
.warning-item:hover { background: #f8fbfa; }
.warning-symbol {
  width: 30px;
  height: 30px;
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  color: #fff;
  border-radius: 50%;
  font-weight: 700;
  font-size: 14px;
}
.warning-symbol.red { background: #e06060; }
.warning-symbol.orange { background: #e0a050; }
.warning-symbol.yellow { background: #d9c040; }
.warning-symbol.blue { background: #6dbfa5; }
.warning-body { flex: 1; min-width: 0; }
.warning-title { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.warning-title strong { color: #24494d; font-size: 13px; }
.warning-title span {
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 10px;
  background: #fdf0e9;
  color: #d88561;
}
.warning-title span.blue { background: #eaf1fa; color: #638fc2; }
.warning-title span.red { background: #fbecec; color: #b04848; }
.warning-title span.yellow { background: #fbf4dd; color: #a37a18; }
.warning-body p { margin: 4px 0 0; color: #72908f; font-size: 11px; }
.warning-body small {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  color: #99a7a1;
  font-size: 10px;
  margin-top: 5px;
}
.warning-body small span,
.warning-body small time { display: inline-flex; align-items: center; gap: 3px; }

.panel-empty {
  text-align: center;
  padding: 30px 18px;
  color: #99a7a1;
  font-size: 12px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.panel-empty p { color: #5e7675; font-size: 13px; margin: 4px 0 0; }
.panel-empty small { color: #99a7a1; font-size: 11px; }

@media (max-width: 1100px) {
  .zone-grid, .bottom-zone-grid { grid-template-columns: 1fr; }
}
@media (max-width: 720px) {
  .zone-toolbar { flex-direction: column; align-items: stretch; }
  .toolbar-right { justify-content: flex-end; }
  .stats-grid { grid-template-columns: 1fr 1fr; }
  .chart-body { padding: 0 12px 12px; }
  .trend-svg { height: 160px; }
}
@media (max-width: 480px) {
  .stats-grid { grid-template-columns: 1fr; }
  .toolbar-left { width: 100%; }
}
</style>