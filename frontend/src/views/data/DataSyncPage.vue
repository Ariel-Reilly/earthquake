/**
 * 数据同步监控页面 (/dashboard/data-sync)。
 *
 * 功能：
 *   - 展示所有数据同步任务的运行状态（正常/延迟/失败）
 *   - 每条同步链路的完整性指标（记录数、延迟时间、异常值）
 *   - 异常告警列表（超阈值自动红色高亮）
 *   - 手动触发同步、暂停、重试操作
 *
 * 数据来源：dashboardApi.getDataSyncTasks()（mock 模式走 seed fallback）
 */
<script setup>
import {
  AlertCircle,
  ArrowDownToLine,
  ArrowUpRight,
  Bell,
  CheckCircle2,
  ChevronDown,
  Database,
  FileWarning,
  History,
  Loader2,
  Pause,
  Play,
  RefreshCw,
  Search,
  ShieldAlert,
  XCircle,
} from "lucide-vue-next"
import { dashboardApi } from "../../services/dashboardApi.js";
import { computed, onMounted, ref } from "vue";

const props = defineProps({
  searchTerm: { type: String, default: "" },
  userRole: { type: String, default: "admin" },
});
const emit = defineEmits(["notify"]);

// ─── 状态 ───────────────────────────────────────
const loading = ref(true);
const tasks = ref([]);
const filter = ref({ keyword: "", status: "" });
const selectedTask = ref(null);
const detailDrawer = ref(false);
const refreshing = ref(null);
const toggling = ref(null);

// ─── 生命周期 ───────────────────────────────────
onMounted(loadTasks);

async function loadTasks() {
  loading.value = true;
  try {
    const data = await dashboardApi.getDataSyncTasks?.().catch(() => null);
    tasks.value = Array.isArray(data) ? data : [];
  } catch {
    tasks.value = [];
  } finally {
    loading.value = false;
  }
}

// ─── 过滤 ───────────────────────────────────────
const filteredTasks = computed(() => {
  let list = tasks.value ?? [];
  if (filter.value.status) {
    list = list.filter((t) => t.status === filter.value.status);
  }
  if (filter.value.keyword.trim()) {
    const k = filter.value.keyword.toLowerCase();
    list = list.filter(
      (t) =>
        (t.name ?? "").toLowerCase().includes(k) ||
        (t.source ?? "").toLowerCase().includes(k) ||
        (t.target ?? "").toLowerCase().includes(k),
    );
  }
  return list;
});

// ─── 统计卡片 ───────────────────────────────────
const stats = computed(() => {
  const all = tasks.value;
  return {
    total: all.length,
    ok: all.filter((t) => t.status === "running" || t.status === "idle").length,
    delayed: all.filter((t) => t.status === "delayed").length,
    failed: all.filter((t) => t.status === "failed").length,
    totalRecords: all.reduce((sum, t) => sum + (Number(t.recordCount) || 0), 0),
    totalDelay: all.reduce((sum, t) => sum + (Number(t.delaySeconds) || 0), 0),
  };
});

// ─── 状态辅助 ───────────────────────────────────
function statusMeta(status) {
  return {
    running: { label: "运行中",   color: "cyan",   icon: Loader2 },
    idle:    { label: "空闲",     color: "gray",   icon: Pause },
    delayed: { label: "延迟",     color: "orange",  icon: AlertCircle },
    failed:  { label: "失败",     color: "red",    icon: XCircle },
  }[status ?? "idle"] ?? { label: "未知", color: "gray", icon: AlertCircle };
}

function severityMeta(level) {
  return {
    critical: { label: "严重", color: "red" },
    warning:  { label: "警告", color: "orange" },
    info:     { label: "提示", color: "blue" },
  }[level ?? "info"] ?? { label: "未知", color: "gray" };
}

function fmtDelay(seconds) {
  if (!seconds && seconds !== 0) return "—";
  if (seconds < 60) return `${seconds}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
  return `${Math.floor(seconds / 3600)}h ${Math.floor((seconds % 3600) / 60)}m`;
}

function fmtRecords(n) {
  if (!n && n !== 0) return "—";
  if (n < 1000) return String(n);
  if (n < 1_0000) return `${(n / 1000).toFixed(1)}K`;
  return `${(n / 10000).toFixed(1)}W`;
}

function fmtTime(isoStr) {
  if (!isoStr) return "—";
  try {
    return new Date(isoStr).toLocaleTimeString("zh-CN", { hour12: false });
  } catch { return "—"; }
}

// ─── 操作 ───────────────────────────────────────
function openDetail(task) {
  selectedTask.value = task;
  detailDrawer.value = true;
}
function closeDetail() { detailDrawer.value = false; selectedTask.value = null; }

async function refreshTask(taskId) {
  refreshing.value = taskId;
  try {
    await dashboardApi.triggerDataSync?.(taskId).catch(() => null);
    emit("notify", "同步任务已触发");
    await loadTasks();
  } finally {
    refreshing.value = null;
  }
}

async function toggleTask(taskId, currentStatus) {
  toggling.value = taskId;
  try {
    const newStatus = currentStatus === "running" ? "pause" : "resume";
    await dashboardApi.toggleDataSync?.(taskId, newStatus).catch(() => null);
    emit("notify", newStatus === "pause" ? "同步已暂停" : "同步已恢复");
    await loadTasks();
  } finally {
    toggling.value = null;
  }
}
</script>

<template>
  <div class="ds-page">
    <!-- ── 统计卡片 ── -->
    <div class="ds-stats">
      <div class="ds-stat-card">
        <Database :size="22" class="stat-icon" />
        <div class="stat-body">
          <div class="stat-num">{{ stats.total }}</div>
          <div class="stat-label">同步链路</div>
        </div>
      </div>
      <div class="ds-stat-card ds-ok">
        <CheckCircle2 :size="22" class="stat-icon" />
        <div class="stat-body">
          <div class="stat-num">{{ stats.ok }}</div>
          <div class="stat-label">正常</div>
        </div>
      </div>
      <div class="ds-stat-card ds-delayed">
        <AlertCircle :size="22" class="stat-icon" />
        <div class="stat-body">
          <div class="stat-num">{{ stats.delayed }}</div>
          <div class="stat-label">延迟</div>
        </div>
      </div>
      <div class="ds-stat-card ds-failed">
        <XCircle :size="22" class="stat-icon" />
        <div class="stat-body">
          <div class="stat-num">{{ stats.failed }}</div>
          <div class="stat-label">失败</div>
        </div>
      </div>
      <div class="ds-stat-card ds-records">
        <ArrowDownToLine :size="22" class="stat-icon" />
        <div class="stat-body">
          <div class="stat-num">{{ fmtRecords(stats.totalRecords) }}</div>
          <div class="stat-label">总记录数</div>
        </div>
      </div>
      <div class="ds-stat-card ds-delay">
        <History :size="22" class="stat-icon" />
        <div class="stat-body">
          <div class="stat-num">{{ fmtDelay(stats.totalDelay) }}</div>
          <div class="stat-label">总延迟</div>
        </div>
      </div>
    </div>

    <!-- ── 工具栏 ── -->
    <div class="ds-toolbar">
      <div class="ds-search">
        <Search :size="16" />
        <input
          v-model="filter.keyword"
          placeholder="搜索同步任务名称、数据源或目标..."
          class="ds-input"
        />
      </div>
      <select v-model="filter.status" class="ds-select">
        <option value="">全部状态</option>
        <option value="running">运行中</option>
        <option value="delayed">延迟</option>
        <option value="failed">失败</option>
        <option value="idle">空闲</option>
      </select>
      <button class="ds-btn ds-btn-refresh" @click="loadTasks">
        <RefreshCw :size="15" :class="loading ? 'spin' : ''" />
        刷新
      </button>
    </div>

    <!-- ── 加载中 ── -->
    <div v-if="loading" class="ds-loading">
      <Loader2 :size="32" class="spin" />
      <span>加载同步状态…</span>
    </div>

    <!-- ── 空状态 ── -->
    <div v-else-if="!filteredTasks.length" class="ds-empty">
      <Database :size="48" />
      <p>暂无同步任务</p>
      <small>请在数据源接入页面配置同步链路</small>
    </div>

    <!-- ── 任务列表 ── -->
    <div v-else class="ds-table-wrap">
      <table class="ds-table">
        <thead>
          <tr>
            <th>任务名称</th>
            <th>数据源</th>
            <th>目标</th>
            <th>状态</th>
            <th>记录数</th>
            <th>延迟</th>
            <th>最后同步</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="task in filteredTasks"
            :key="task.id"
            :class="{ 'row-delayed': task.status === 'delayed', 'row-failed': task.status === 'failed' }"
            @click="openDetail(task)"
          >
            <td class="td-name">
              <span class="task-name">{{ task.name || `同步任务-${task.id}` }}</span>
            </td>
            <td class="td-source">{{ task.source || "—" }}</td>
            <td class="td-target">{{ task.target || "—" }}</td>
            <td>
              <span class="ds-badge" :class="`badge-${statusMeta(task.status).color}`">
                <component :is="statusMeta(task.status).icon" :size="12" />
                {{ statusMeta(task.status).label }}
              </span>
            </td>
            <td class="td-num">{{ fmtRecords(task.recordCount) }}</td>
            <td class="td-num" :class="{ 'delay-warn': task.delaySeconds > 300 }">
              {{ fmtDelay(task.delaySeconds) }}
            </td>
            <td class="td-time">{{ fmtTime(task.lastSyncAt) }}</td>
            <td class="td-actions" @click.stop>
              <button
                class="ds-icon-btn"
                title="触发同步"
                :disabled="refreshing === task.id"
                @click="refreshTask(task.id)"
              >
                <RefreshCw :size="14" :class="refreshing === task.id ? 'spin' : ''" />
              </button>
              <button
                class="ds-icon-btn"
                :title="task.status === 'running' ? '暂停' : '恢复'"
                :disabled="toggling === task.id"
                @click="toggleTask(task.id, task.status)"
              >
                <component :is="task.status === 'running' ? Pause : Play" :size="14" />
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- ── 详情抽屉 ── -->
    <Transition name="slide-right">
      <div v-if="detailDrawer && selectedTask" class="ds-drawer">
        <div class="drawer-header">
          <div>
            <strong>{{ selectedTask.name || `同步任务-${selectedTask.id}` }}</strong>
            <span class="ds-badge ml-2" :class="`badge-${statusMeta(selectedTask.status).color}`">
              {{ statusMeta(selectedTask.status).label }}
            </span>
          </div>
          <button class="ds-icon-btn" @click="closeDetail"><XCircle :size="18" /></button>
        </div>
        <div class="drawer-body">
          <div class="detail-section">
            <h4>基本信息</h4>
            <div class="detail-grid">
              <div class="detail-row"><span class="detail-key">任务ID</span><span>{{ selectedTask.id }}</span></div>
              <div class="detail-row"><span class="detail-key">数据源</span><span>{{ selectedTask.source || "—" }}</span></div>
              <div class="detail-row"><span class="detail-key">目标库</span><span>{{ selectedTask.target || "—" }}</span></div>
              <div class="detail-row"><span class="detail-key">记录数</span><span>{{ fmtRecords(selectedTask.recordCount) }}</span></div>
              <div class="detail-row"><span class="detail-key">延迟</span><span>{{ fmtDelay(selectedTask.delaySeconds) }}</span></div>
              <div class="detail-row"><span class="detail-key">最后同步</span><span>{{ selectedTask.lastSyncAt ? new Date(selectedTask.lastSyncAt).toLocaleString("zh-CN") : "—" }}</span></div>
              <div class="detail-row"><span class="detail-key">同步周期</span><span>{{ selectedTask.interval || "实时" }}</span></div>
            </div>
          </div>

          <div v-if="selectedTask.alerts?.length" class="detail-section">
            <h4><ShieldAlert :size="15" /> 告警记录</h4>
            <div v-for="alert in selectedTask.alerts" :key="alert.id" class="alert-item">
              <span class="ds-badge" :class="`badge-${severityMeta(alert.level).color}`">
                {{ severityMeta(alert.level).label }}
              </span>
              <span class="alert-msg">{{ alert.message }}</span>
              <span class="alert-time">{{ fmtTime(alert.createdAt) }}</span>
            </div>
          </div>
          <div v-else class="detail-section">
            <h4>告警记录</h4>
            <p class="no-data">暂无告警</p>
          </div>
        </div>
      </div>
    </Transition>

    <!-- 抽屉遮罩 -->
    <Transition name="fade">
      <div v-if="detailDrawer" class="ds-overlay" @click="closeDetail" />
    </Transition>
  </div>
</template>

<style scoped>
.ds-page {
  display: flex;
  flex-direction: column;
  gap: 16px;
  height: 100%;
  position: relative;
}

/* 统计卡片 */
.ds-stats {
  display: grid;
  grid-template-columns: repeat(6, 1fr);
  gap: 12px;
}
.ds-stat-card {
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  padding: 14px 16px;
  display: flex;
  align-items: center;
  gap: 12px;
}
.ds-stat-card.ds-ok { border-color: rgba(34,197,94,0.3); background: rgba(34,197,94,0.05); }
.ds-stat-card.ds-delayed { border-color: rgba(249,115,22,0.3); background: rgba(249,115,22,0.05); }
.ds-stat-card.ds-failed { border-color: rgba(239,68,68,0.3); background: rgba(239,68,68,0.05); }
.ds-stat-card.ds-records { border-color: rgba(59,130,246,0.3); background: rgba(59,130,246,0.05); }
.ds-stat-card.ds-delay { border-color: rgba(168,85,247,0.3); background: rgba(168,85,247,0.05); }
.stat-icon { color: #64748b; }
.ds-stat-card.ds-ok .stat-icon { color: #22c55e; }
.ds-stat-card.ds-delayed .stat-icon { color: #f97316; }
.ds-stat-card.ds-failed .stat-icon { color: #ef4444; }
.ds-stat-card.ds-records .stat-icon { color: #3b82f6; }
.ds-stat-card.ds-delay .stat-icon { color: #a855f7; }
.stat-body { flex: 1; }
.stat-num { font-size: 24px; font-weight: 700; color: #1e293b; line-height: 1; }
.stat-label { font-size: 12px; color: #64748b; margin-top: 3px; }

/* 工具栏 */
.ds-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
}
.ds-search {
  display: flex;
  align-items: center;
  gap: 8px;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 8px 12px;
  flex: 1;
  color: #64748b;
}
.ds-input {
  border: none;
  outline: none;
  background: transparent;
  flex: 1;
  font-size: 14px;
  color: #1e293b;
}
.ds-input::placeholder { color: #94a3b8; }
.ds-select {
  padding: 8px 12px;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  background: #fff;
  font-size: 14px;
  color: #1e293b;
  cursor: pointer;
}
.ds-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: 8px;
  border: 1px solid #e2e8f0;
  background: #fff;
  font-size: 14px;
  color: #475569;
  cursor: pointer;
  transition: all 0.15s;
  white-space: nowrap;
}
.ds-btn:hover { border-color: #3b82f6; color: #3b82f6; }
.ds-btn-refresh { border-color: #3b82f6; color: #3b82f6; }
.ds-btn-refresh:hover { background: #eff6ff; }

/* 加载/空 */
.ds-loading, .ds-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 60px;
  color: #94a3b8;
  font-size: 14px;
}
.ds-empty small { font-size: 12px; color: #cbd5e1; }

/* 表格 */
.ds-table-wrap { background: #fff; border-radius: 10px; border: 1px solid #e2e8f0; overflow: auto; }
.ds-table { width: 100%; border-collapse: collapse; font-size: 14px; }
.ds-table th {
  padding: 10px 14px;
  text-align: left;
  font-size: 12px;
  font-weight: 600;
  color: #64748b;
  background: #f8fafc;
  border-bottom: 1px solid #e2e8f0;
  white-space: nowrap;
  position: sticky;
  top: 0;
  z-index: 2;
}
.ds-table td {
  padding: 11px 14px;
  border-bottom: 1px solid #f1f5f9;
  color: #334155;
  vertical-align: middle;
}
.ds-table tbody tr { cursor: pointer; transition: background 0.1s; }
.ds-table tbody tr:hover { background: #f8fafc; }
.ds-table tbody tr:last-child td { border-bottom: none; }
.row-delayed { background: rgba(249,115,22,0.04); }
.row-failed { background: rgba(239,68,68,0.04); }
.task-name { font-weight: 500; color: #1e293b; }
.td-source, .td-target { color: #64748b; font-size: 13px; }
.td-num { text-align: right; font-variant-numeric: tabular-nums; }
.td-time { color: #94a3b8; font-size: 12px; white-space: nowrap; }
.td-actions { display: flex; gap: 4px; }
.delay-warn { color: #f97316; font-weight: 600; }

/* 状态徽章 */
.ds-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 12px;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}
.badge-cyan   { background: rgba(6,182,212,0.1); color: #0891b2; }
.badge-gray   { background: rgba(100,116,139,0.1); color: #64748b; }
.badge-orange { background: rgba(249,115,22,0.1); color: #ea580c; }
.badge-red    { background: rgba(239,68,68,0.1); color: #dc2626; }
.badge-blue   { background: rgba(59,130,246,0.1); color: #2563eb; }

/* 图标按钮 */
.ds-icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 28px; height: 28px;
  border-radius: 6px;
  border: 1px solid #e2e8f0;
  background: #fff;
  color: #64748b;
  cursor: pointer;
  transition: all 0.15s;
}
.ds-icon-btn:hover { border-color: #3b82f6; color: #3b82f6; }
.ds-icon-btn:disabled { opacity: 0.5; cursor: not-allowed; }

/* 详情抽屉 */
.ds-drawer {
  position: fixed;
  right: 0; top: 0; bottom: 0;
  width: 380px;
  background: #fff;
  border-left: 1px solid #e2e8f0;
  z-index: 200;
  display: flex;
  flex-direction: column;
  box-shadow: -4px 0 24px rgba(0,0,0,0.08);
}
.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 18px;
  border-bottom: 1px solid #e2e8f0;
  font-size: 15px;
  font-weight: 600;
  color: #1e293b;
  flex-shrink: 0;
}
.drawer-body { flex: 1; overflow-y: auto; padding: 16px 18px; }
.detail-section { margin-bottom: 24px; }
.detail-section h4 {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: #64748b;
  margin-bottom: 10px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.detail-grid { display: flex; flex-direction: column; gap: 8px; }
.detail-row { display: flex; gap: 8px; font-size: 13px; }
.detail-key { color: #94a3b8; min-width: 70px; flex-shrink: 0; }
.detail-row span:last-child { color: #1e293b; }
.no-data { font-size: 13px; color: #cbd5e1; text-align: center; padding: 12px 0; }

/* 告警 */
.alert-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 0;
  border-bottom: 1px solid #f1f5f9;
  font-size: 13px;
}
.alert-msg { flex: 1; color: #334155; }
.alert-time { color: #94a3b8; font-size: 12px; white-space: nowrap; }

/* 遮罩 */
.ds-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.2);
  z-index: 199;
}

/* 过渡 */
.slide-right-enter-active, .slide-right-leave-active { transition: transform 0.25s ease; }
.slide-right-enter-from, .slide-right-leave-to { transform: translateX(100%); }
.fade-enter-active, .fade-leave-active { transition: opacity 0.25s; }
.fade-enter-from, .fade-leave-to { opacity: 0; }
.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
.ml-2 { margin-left: 8px; }
</style>
