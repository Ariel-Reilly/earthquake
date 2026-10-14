<script setup>
/**
 * 统一事件中心主页面（EventCenterPage.vue）。
 *
 * 路由：/dashboard/events
 *
 * 页面布局：
 *   ┌──────────────────────────────────────────────────────┐
 *   │  7 状态 KPI 卡片（一排）                              │
 *   ├────────────────────┬─────────────────────────────────┤
 *   │  状态机可视化       │  过滤栏                          │
 *   │  7 节点 + 12 箭头   │  ────────────────────────────   │
 *   │                    │  事件列表（卡片式 / 表格）        │
 *   │  可点击节点过滤     │  - 事件号 / 状态 / 关联           │
 *   │                    │  - 点击打开右侧抽屉              │
 *   └────────────────────┴─────────────────────────────────┘
 *
 * 数据契约：
 *   dashboardApi.listUnifiedEvents({ state, source, keyword, page, size })
 *   dashboardApi.getUnifiedEventStats()
 *   dashboardApi.getUnifiedEventDetail(code)
 *   dashboardApi.transitionUnifiedEvent(code, payload)
 *   dashboardApi.getStateMachineGraph()
 *
 * 设计原则：
 *   - 顶栏 KPI + 状态机 + 列表三者状态联动：点 KPI / 节点都能筛选列表
 *   - 列表卡片突出"事件号 + 当前状态 + 关联实体" 三件套
 *   - 实时刷新：进入页面拉一次 + 每 30 秒拉一次（轻量）
 *   - 离线 mock 兜底：dashboardApi 内部 mock.workflow 已有种子数据
 */
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  Radar,
  Archive,
  AlertTriangle,
  Bell,
  CheckCircle2,
  ClipboardCheck,
  Eye,
  Filter,
  GitBranch,
  Loader2,
  MapPin,
  RefreshCw,
  Search,
  ShieldAlert,
  Truck,
  XCircle,
  X,
} from "lucide-vue-next";
import StateMachineDiagram from "../../components/event/StateMachineDiagram.vue";
import EventDetailDrawer from "../../components/event/EventDetailDrawer.vue";
import { dashboardApi } from "../../services/dashboardApi";

const props = defineProps({
  searchTerm: { type: String, default: "" },
  userRole: { type: String, default: "admin" },
});
const emit = defineEmits(["notify"]);

const route = useRoute();
const router = useRouter();

const STATE_META = {
  DETECTED:         { label: "已发现",      color: "#6f9e7f", icon: Eye,                tone: "blue" },
  CONFIRMING:       { label: "确认中",      color: "#cbaa76", icon: ClipboardCheck,     tone: "yellow" },
  CONFIRMED:        { label: "已确认",      color: "#638fc2", icon: CheckCircle2,       tone: "blue" },
  WARNING_ISSUED:   { label: "预警发布",    color: "#d88561", icon: Bell,                tone: "orange" },
  EMERGENCY_ACTIVE: { label: "应急处置",    color: "#e06060", icon: Truck,               tone: "red" },
  CLOSED:           { label: "已关闭",      color: "#9aab9f", icon: Archive,             tone: "gray" },
  REJECTED:         { label: "已驳回",      color: "#76847a", icon: XCircle,             tone: "gray" },
};

const SOURCE_LABEL = {
  AI_AUTO: "AI 自动",
  RULE_ENGINE: "规则引擎",
  MANUAL: "人工上报",
};
const SOURCE_COLOR = {
  AI_AUTO: "#638fc2",
  RULE_ENGINE: "#9b761e",
  MANUAL: "#4a7a5e",
};

const loading = ref(false);
const refreshing = ref(false);
const items = ref([]);
const total = ref(0);
const stats = ref({
  DETECTED: 0, CONFIRMING: 0, CONFIRMED: 0,
  WARNING_ISSUED: 0, EMERGENCY_ACTIVE: 0,
  CLOSED: 0, REJECTED: 0,
  total: 0, active: 0,
});
const graph = ref({ states: [], transitions: [] });
const selectedEvent = ref(null);
const drawerVisible = ref(false);

const filter = reactive({
  state: "",
  source: "",
  keyword: "",
});

const SOURCE_OPTIONS = [
  { value: "", label: "全部来源" },
  { value: "AI_AUTO", label: "AI 自动" },
  { value: "RULE_ENGINE", label: "规则引擎" },
  { value: "MANUAL", label: "人工上报" },
];

let pollTimer = null;

const highlightedStates = computed(() =>
  Object.entries(stats.value)
    .filter(([k, v]) => /^[A-Z_]+$/.test(k) && Number(v) > 0)
    .map(([k]) => k),
);

const filteredItems = computed(() => items.value);

const sortedItems = computed(() => {
  const order = ["EMERGENCY_ACTIVE", "WARNING_ISSUED", "CONFIRMED", "CONFIRMING", "DETECTED", "REJECTED", "CLOSED"];
  return [...filteredItems.value].sort((a, b) => {
    const oa = order.indexOf(a.state);
    const ob = order.indexOf(b.state);
    if (oa !== ob) return oa - ob;
    return new Date(b.updatedAt).getTime() - new Date(a.updatedAt).getTime();
  });
});

async function loadAll() {
  loading.value = true;
  try {
    const [listData, statsData, graphData] = await Promise.all([
      dashboardApi.listUnifiedEvents({ state: filter.state || undefined, source: filter.source || undefined, keyword: filter.keyword || undefined }),
      dashboardApi.getUnifiedEventStats(),
      Promise.resolve(dashboardApi.getStateMachineGraph()),
    ]);
    items.value = listData.items ?? [];
    total.value = listData.total ?? 0;
    stats.value = {
      DETECTED: statsData.DETECTED ?? 0,
      CONFIRMING: statsData.CONFIRMING ?? 0,
      CONFIRMED: statsData.CONFIRMED ?? 0,
      WARNING_ISSUED: statsData.WARNING_ISSUED ?? 0,
      EMERGENCY_ACTIVE: statsData.EMERGENCY_ACTIVE ?? 0,
      CLOSED: statsData.CLOSED ?? 0,
      REJECTED: statsData.REJECTED ?? 0,
      total: statsData.total ?? 0,
      active: statsData.active ?? 0,
    };
    graph.value = graphData;
  } catch (e) {
    emit("notify", e?.message ?? "加载事件数据失败");
  } finally {
    loading.value = false;
  }
}

async function refresh() {
  refreshing.value = true;
  try { await loadAll(); }
  finally { refreshing.value = false; }
}

function onStateClick(stateId) {
  if (filter.state === stateId) {
    filter.state = "";
  } else {
    filter.state = stateId;
  }
}

function clearFilter() {
  filter.state = "";
  filter.source = "";
  filter.keyword = "";
}

async function onItemClick(item) {
  selectedEvent.value = item;
  drawerVisible.value = true;
  // 拉最新详情（可能比列表更全）
  try {
    const detail = await dashboardApi.getUnifiedEventDetail(item.eventCode);
    if (detail) selectedEvent.value = detail;
  } catch (_e) { /* 离线兜底用列表数据 */ }
}

function onDrawerClosed() {
  drawerVisible.value = false;
  // 触发后重新拉列表（避免本地缓存与后端状态不同步）
  refresh();
}

function closeDrawer() {
  drawerVisible.value = false;
}

async function onTransitioned(payload) {
  emit("notify", `事件 ${payload.code} 已流转：${payload.trigger}`);
  await refresh();
}

const filterActive = computed(() => !!(filter.state || filter.source || filter.keyword));

const headerTitle = "统一事件中心";
const headerDesc = "把 hazard / warning / emergency 三类事件汇聚到同一个状态机视角，7 状态 × 12 转移全链路可视化跟踪。";

function formatTime(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  const diff = Date.now() - d.getTime();
  if (diff < 60_000) return "刚刚";
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`;
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`;
  return `${Math.floor(diff / 86_400_000)} 天前`;
}

function openEventByCode(code) {
  const target = items.value.find((it) => it.eventCode === code);
  if (target) onItemClick(target);
}

// 监听 URL ?code= 自动打开抽屉
watch(
  () => route.query.code,
  (code) => {
    if (code) openEventByCode(code);
  },
  { immediate: true },
);

// 当同一组件被 /events 与 /events/:code 两条路由复用时,
// 路由参数 code 变化不会触发 onMounted,需要主动重拉数据。
watch(
  () => route.params.code,
  () => {
    if (!route.params.code) return; // 列表路由首次加载交给 onMounted
    loadAll();
  },
);

onMounted(async () => {
  await loadAll();
  pollTimer = setInterval(refresh, 30_000);
});

onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer);
  if (filterTimer) clearTimeout(filterTimer);
});

// 监听过滤变化，自动重新加载（避免大量数据本地筛选）
let filterTimer = null;
watch(
  () => [filter.state, filter.source, filter.keyword],
  () => {
    clearTimeout(filterTimer);
    filterTimer = setTimeout(loadAll, 200);
  },
);

// 暴露外部搜索词（顶栏 searchTerm）
watch(
  () => props.searchTerm,
  (v) => {
    if (typeof v === "string" && v !== filter.keyword) {
      filter.keyword = v;
    }
  },
);

function stateMeta(id) {
  return STATE_META[id] ?? { label: id, color: "#6f9e7f", icon: GitBranch, tone: "blue" };
}
</script>

<template>
  <div class="event-center">
    <!-- 7 状态 KPI -->
    <section class="kpi-row">
      <button
        v-for="stateId in ['DETECTED', 'CONFIRMING', 'CONFIRMED', 'WARNING_ISSUED', 'EMERGENCY_ACTIVE', 'CLOSED', 'REJECTED']"
        :key="stateId"
        class="kpi-card"
        :class="[`tone-${stateMeta(stateId).tone}`, { active: filter.state === stateId }]"
        :style="{ '--tone-color': stateMeta(stateId).color }"
        @click="onStateClick(stateId)"
      >
        <span class="kpi-icon">
          <component :is="stateMeta(stateId).icon" :size="18" />
        </span>
        <span class="kpi-body">
          <span class="kpi-label">{{ stateMeta(stateId).label }}</span>
          <strong class="kpi-value">{{ stats[stateId] ?? 0 }}</strong>
        </span>
      </button>
      <div class="kpi-card summary">
        <span class="kpi-icon"><Radar :size="18" /></span>
        <span class="kpi-body">
          <span class="kpi-label">事件总数</span>
          <strong class="kpi-value">{{ stats.total }}</strong>
          <small class="kpi-sub">活动 {{ stats.active }} · 终态 {{ (stats.total ?? 0) - (stats.active ?? 0) }}</small>
        </span>
      </div>
    </section>

    <!-- 状态机图 + 列表 -->
    <div class="ec-grid">
      <!-- 左侧：状态机可视化 -->
      <aside class="ec-aside">
        <StateMachineDiagram
          :states="graph.states"
          :transitions="graph.transitions"
          :active-state="filter.state"
          :counts="stats"
          :highlighted-states="highlightedStates"
          @select-state="onStateClick"
        />

        <!-- 当前过滤状态提示 -->
        <div v-if="filterActive" class="active-filter">
          <Filter :size="13" />
          <span>
            <template v-if="filter.state">状态: {{ stateMeta(filter.state).label }}</template>
            <template v-if="filter.source"> · 来源: {{ SOURCE_LABEL[filter.source] }}</template>
            <template v-if="filter.keyword"> · 关键字: "{{ filter.keyword }}"</template>
          </span>
          <button class="clear-filter" @click="clearFilter">
            <X :size="12" /> 清除
          </button>
        </div>

        <!-- 图例 / 帮助 -->
        <section class="help-card">
          <h4><ShieldAlert :size="14" /> 使用说明</h4>
          <ul>
            <li>点击 <strong>状态卡片</strong> 或 <strong>状态机节点</strong> 即可筛选该状态事件</li>
            <li>事件卡片显示完整 <strong>事件号 / 状态 / 关联实体</strong> 三件套</li>
            <li>点击卡片打开 <strong>右侧抽屉</strong>，可执行状态流转、跳转关联模块</li>
            <li>驳回 / 关闭等操作会进入 <strong>审计日志</strong>，不可篡改</li>
          </ul>
        </section>
      </aside>

      <!-- 右侧：过滤 + 列表 -->
      <section class="ec-list">
        <header class="list-toolbar">
          <div class="filter-group">
            <select v-model="filter.source">
              <option v-for="opt in SOURCE_OPTIONS" :key="opt.value" :value="opt.value">
                {{ opt.label }}
              </option>
            </select>
            <div class="search-input">
              <Search :size="14" />
              <input v-model="filter.keyword" placeholder="搜索事件号或标题" />
              <button v-if="filter.keyword" class="clear-input" @click="filter.keyword = ''">
                <X :size="12" />
              </button>
            </div>
          </div>
          <div class="toolbar-actions">
            <span class="total-label">
              共 <strong>{{ total }}</strong> 条事件
              <small v-if="filterActive">（已过滤）</small>
            </span>
            <button class="refresh-btn" :disabled="refreshing" @click="refresh">
              <RefreshCw :size="14" :class="{ spin: refreshing }" />
              {{ refreshing ? "刷新中" : "刷新" }}
            </button>
          </div>
        </header>

        <div v-if="loading && items.length === 0" class="loading-block">
          <Loader2 :size="20" class="spin" /> 加载事件数据…
        </div>

        <div v-else-if="!sortedItems.length" class="empty-block">
          <GitBranch :size="36" />
          <p>{{ filterActive ? "没有符合条件的事件" : "暂无事件" }}</p>
          <button v-if="filterActive" class="link" @click="clearFilter">清除筛选</button>
        </div>

        <div v-else class="event-cards">
          <article
            v-for="item in sortedItems"
            :key="item.eventCode"
            class="event-card"
            :class="[`tone-${stateMeta(item.state).tone}`]"
            @click="onItemClick(item)"
          >
            <header class="ec-head">
              <div class="ec-state">
                <span class="state-dot" :style="{ background: stateMeta(item.state).color }"></span>
                <strong>{{ stateMeta(item.state).label }}</strong>
              </div>
              <code class="ec-code">{{ item.eventCode }}</code>
            </header>

            <h3 class="ec-title">{{ item.title }}</h3>

            <div class="ec-meta">
              <span v-if="item.hazardCode" class="meta-tag">
                <MapPin :size="12" /> {{ item.hazardCode }}
              </span>
              <span v-if="item.warningCode" class="meta-tag warning">
                <Bell :size="12" /> {{ item.warningCode }}
              </span>
              <span v-if="item.emergencyCode" class="meta-tag emergency">
                <Truck :size="12" /> {{ item.emergencyCode }}
              </span>
            </div>

            <footer class="ec-foot">
              <span class="ec-source" :style="{ color: SOURCE_COLOR[item.source] ?? 'var(--c-info)' }">
                <span class="src-dot" :style="{ background: SOURCE_COLOR[item.source] ?? 'var(--c-info)' }"></span>
                {{ SOURCE_LABEL[item.source] ?? item.source }}
              </span>
              <span class="ec-time">
                {{ formatTime(item.updatedAt) }}
              </span>
            </footer>
          </article>
        </div>
      </section>
    </div>

    <!-- 右侧抽屉 -->
    <EventDetailDrawer
      :event="selectedEvent"
      :visible="drawerVisible"
      @close="closeDrawer"
      @transitioned="onTransitioned"
      @notify="(msg) => emit('notify', msg)"
    />
  </div>
</template>

<style scoped>
.event-center { padding-bottom: 12px; }

/* KPI 行 */
.kpi-row {
  display: grid;
  grid-template-columns: repeat(8, 1fr);
  gap: 10px;
  margin-bottom: 16px;
}
.kpi-card {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 14px;
  background: var(--c-surface);
  border: 1.5px solid var(--c-border);
  border-radius: var(--r-md);
  cursor: pointer;
  transition: all 0.16s;
  text-align: left;
  font-family: inherit;
  color: inherit;
  position: relative;
  overflow: hidden;
}
.kpi-card::before {
  content: "";
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 3px;
  background: var(--tone-color, var(--c-brand));
  opacity: 0;
  transition: opacity 0.18s;
}
.kpi-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 6px 16px rgba(46, 67, 93, 0.1);
  border-color: var(--tone-color, var(--c-brand));
}
.kpi-card:hover::before { opacity: 1; }
.kpi-card.active {
  border-color: var(--tone-color, var(--c-brand));
  background: var(--c-surface);
  box-shadow: 0 6px 16px rgba(111, 158, 127, 0.15);
}
.kpi-card.active::before { opacity: 1; }
.kpi-card.summary {
  grid-column: span 1;
  background: linear-gradient(135deg, var(--c-brand-mist), var(--c-info-bg));
}
.kpi-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  background: var(--glass-regular);
  border-radius: var(--r-sm);
  color: var(--tone-color, var(--c-brand));
  flex-shrink: 0;
  border: 1px solid var(--c-border);
}
.kpi-body {
  display: flex;
  flex-direction: column;
  flex: 1;
  min-width: 0;
}
.kpi-label {
  font-size: 11px;
  color: var(--c-text-2);
  letter-spacing: 0.3px;
}
.kpi-value {
  font-size: 18px;
  font-weight: 800;
  color: var(--c-ink-1);
  font-feature-settings: "tnum";
}
.kpi-sub {
  font-size: 10.5px;
  color: var(--c-text-3);
  margin-top: 1px;
}

/* 双栏布局 */
.ec-grid {
  display: grid;
  grid-template-columns: minmax(340px, 28%) 1fr;
  gap: 16px;
  align-items: flex-start;
}
.ec-aside {
  display: flex;
  flex-direction: column;
  gap: 12px;
  position: sticky;
  top: 12px;
}
.active-filter {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--c-ink-2);
  background: var(--c-brand-mist);
  border: 1px dashed var(--c-brand-tint);
  padding: 8px 10px;
  border-radius: var(--r-sm);
}
.active-filter span { flex: 1; }
.clear-filter {
  background: transparent;
  border: none;
  cursor: pointer;
  color: var(--c-brand);
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  gap: 2px;
  padding: 2px 4px;
}
.clear-filter:hover { color: var(--c-danger-strong); }

.help-card {
  background: var(--c-info-bg);
  border: 1px solid var(--c-info-bg);
  border-radius: var(--r-sm);
  padding: 12px 14px;
  font-size: 12px;
  color: var(--c-ink-2);
}
.help-card h4 {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--c-brand);
  display: flex;
  align-items: center;
  gap: 4px;
  font-weight: 700;
  letter-spacing: 0.3px;
  text-transform: uppercase;
}
.help-card ul {
  margin: 0;
  padding-left: 18px;
  line-height: 1.7;
}
.help-card strong { color: var(--c-ink-1); font-weight: 600; }

/* 列表 */
.ec-list {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-md);
  overflow: hidden;
}
.list-toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  border-bottom: 1px solid var(--c-border);
  flex-wrap: wrap;
  gap: 10px;
  background: var(--glass-thin);
}
.filter-group {
  display: flex;
  gap: 8px;
  flex: 1;
  align-items: center;
}
.filter-group select {
  padding: 6px 10px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  font-size: 12px;
  background: var(--c-surface);
  color: var(--c-ink-1);
  cursor: pointer;
}
.search-input {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 4px 10px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  background: var(--c-surface);
  flex: 1;
  max-width: 320px;
  color: var(--c-text-3);
}
.search-input:focus-within {
  border-color: var(--c-brand);
  box-shadow: 0 0 0 3px rgba(111, 158, 127, 0.08);
}
.search-input input {
  border: none;
  outline: none;
  background: transparent;
  font-size: 12px;
  color: var(--c-ink-1);
  flex: 1;
  font-family: inherit;
}
.clear-input {
  background: transparent;
  border: none;
  cursor: pointer;
  color: var(--c-text-3);
  padding: 0;
  display: inline-flex;
}
.toolbar-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.total-label {
  font-size: 12px;
  color: var(--c-text-2);
}
.total-label strong {
  color: var(--c-ink-1);
  font-size: 14px;
}
.refresh-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border: 1px solid var(--c-border);
  background: var(--c-surface);
  border-radius: var(--r-sm);
  cursor: pointer;
  font-size: 12px;
  color: var(--c-ink-2);
  font-family: inherit;
  transition: all 0.15s;
}
.refresh-btn:hover:not(:disabled) {
  border-color: var(--c-brand);
  color: var(--c-brand);
}
.refresh-btn:disabled { opacity: 0.6; cursor: not-allowed; }

/* 事件卡片 */
.event-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(280px, 1fr));
  gap: 10px;
  padding: 12px 14px;
}
.event-card {
  background: var(--c-surface);
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  padding: 12px 14px;
  cursor: pointer;
  transition: all 0.16s;
  display: flex;
  flex-direction: column;
  gap: 8px;
  position: relative;
}
.event-card::before {
  content: "";
  position: absolute;
  left: 0;
  top: 10px;
  bottom: 10px;
  width: 3px;
  border-radius: 0 2px 2px 0;
}
.event-card.tone-blue::before { background: var(--sys-blue); }
.event-card.tone-yellow::before { background: var(--c-warning); }
.event-card.tone-orange::before { background: var(--c-warning-strong); }
.event-card.tone-red::before { background: var(--c-danger); }
.event-card.tone-gray::before { background: var(--c-text-4); }
.event-card:hover {
  border-color: var(--c-brand);
  transform: translateY(-2px);
  box-shadow: 0 6px 14px rgba(111, 158, 127, 0.12);
}
.ec-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.ec-state {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--c-ink-1);
}
.state-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}
.ec-code {
  font-size: 10.5px;
  color: var(--c-text-2);
  font-family: ui-monospace, monospace;
  background: var(--c-border-2);
  padding: 2px 6px;
  border-radius: 3px;
  letter-spacing: 0.3px;
}
.ec-title {
  margin: 0;
  font-size: 13.5px;
  font-weight: 700;
  color: var(--c-ink-1);
  line-height: 1.4;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.ec-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
.meta-tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  padding: 2px 7px;
  font-size: 11px;
  background: var(--c-border-2);
  color: var(--c-ink-2);
  border-radius: var(--r-xs);
  font-family: ui-monospace, monospace;
}
.meta-tag.warning {
  background: var(--c-warning-bg);
  color: var(--c-warning-ink);
}
.meta-tag.emergency {
  background: var(--c-danger-bg);
  color: var(--c-danger-strong);
}
.ec-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 6px;
  border-top: 1px dashed var(--c-border);
}
.ec-source {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  font-weight: 600;
}
.src-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.ec-time {
  font-size: 11px;
  color: var(--c-text-3);
}

/* Loading / Empty */
.loading-block,
.empty-block {
  padding: 48px 16px;
  text-align: center;
  color: var(--c-text-3);
  font-size: 13px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}
.empty-block svg {
  color: var(--c-border);
}
.empty-block .link {
  background: transparent;
  border: 1px solid var(--c-brand);
  color: var(--c-brand);
  padding: 4px 12px;
  border-radius: var(--r-xs);
  cursor: pointer;
  font-size: 12px;
}

.spin { animation: spin 1s linear infinite; }
@keyframes spin {
  from { transform: rotate(0); }
  to { transform: rotate(360deg); }
}

/* 响应式 */
@media (max-width: 1280px) {
  .kpi-row {
    grid-template-columns: repeat(4, 1fr);
  }
  .ec-grid {
    grid-template-columns: 1fr;
  }
  .ec-aside { position: static; }
}
</style>
