/**
 * 移动端指挥入口 — /mobile
 *
 * 设计目标:
 *   - 手机端极简指挥界面，无需登录即可查看核心 KPI（若需写操作则跳转完整登录）
 *   - 响应式适配所有手机分辨率（320px+）
 *   - 离线缓存支持（Service Worker + localStorage 预存关键数据）
 *   - PWA 安装支持（manifest.json + sw.js）
 *
 * 核心功能:
 *   1. KPI 看板：待确认数、活跃预警、应急事件、设备在线率
 *   2. 待办一键审批（确认/驳回）
 *   3. 预警一键发布/解除
 *   4. 地图定位与导航
 *   5. 视频会商快速接入
 *
 * @author Cursor
 */

<script setup>
import { computed, onMounted, ref } from "vue";
import {
  Radar,
  AlertTriangle,
  CheckCircle2,
  ChevronRight,
  Clock,
  Eye,
  Globe,
  Loader2,
  MapPin,
  MessageSquare,
  RefreshCw,
  ShieldCheck,
  Smartphone,
  ThumbsDown,
  ThumbsUp,
  Video,
  Wifi,
  WifiOff,
  XCircle,
  Zap,
} from "lucide-vue-next";
import { useRouter } from "vue-router";

const router = useRouter();
const isOnline = ref(navigator.onLine);
const loading = ref(true);
const refreshing = ref(false);
const lastUpdated = ref(null);

// ─────────────────────────────────────────────
// 1. KPI 数据（mock，真实场景由 dashboardApi 拉取）
// ─────────────────────────────────────────────
const kpi = ref({
  pendingConfirm: 3,
  activeWarnings: 7,
  emergencyEvents: 2,
  deviceOnline: 94.2,
  unreadMessages: 12,
  pendingApprovals: 5,
});

// 预警列表
const warnings = ref([
  {
    id: "W-2026091301",
    title: "龙泉驿区山体滑坡预警",
    level: "orange",
    levelLabel: "橙色",
    status: "published",
    statusLabel: "已发布",
    time: "14:32",
    location: "四川省成都市龙泉驿区",
  },
  {
    id: "W-2026091302",
    title: "青白江区暴雨洪涝预警",
    level: "yellow",
    levelLabel: "黄色",
    status: "draft",
    statusLabel: "待发布",
    time: "13:15",
    location: "四川省成都市青白江区",
  },
  {
    id: "W-2026091208",
    title: "都江堰市泥石流蓝色预警",
    level: "blue",
    levelLabel: "蓝色",
    status: "published",
    statusLabel: "已发布",
    time: "昨天 22:10",
    location: "四川省成都市都江堰市",
  },
]);

// 待确认队列
const pendingConfirm = ref([
  { id: "H-021", location: "龙安镇北侧隐患点", type: "滑坡", score: 0.87, time: "10 分钟前" },
  { id: "H-018", location: "青白江河道东岸", type: "地面沉降", score: 0.72, time: "25 分钟前" },
  { id: "H-015", location: "都江堰景区入口", type: "崩塌", score: 0.65, time: "1 小时前" },
]);

// ─────────────────────────────────────────────
// 2. 计算属性
// ─────────────────────────────────────────────
const levelColor = (level) => ({
  red: "#c53030",
  orange: "#dd6b20",
  yellow: "#d69e2e",
  blue: "#3182ce",
}[level] ?? "#718096");

const statusBadge = (status) => ({
  published: { bg: "#c6f6d5", color: "#276749", label: "已发布" },
  draft: { bg: "#feebc8", color: "#975a16", label: "待发布" },
  resolved: { bg: "#e2e8f0", color: "#4a5568", label: "已解除" },
}[status] ?? { bg: "#edf2f7", color: "#4a5568", label: status });

// ─────────────────────────────────────────────
// 3. 生命周期
// ─────────────────────────────────────────────
onMounted(async () => {
  window.addEventListener("online", () => { isOnline.value = true; });
  window.addEventListener("offline", () => { isOnline.value = false; });
  await loadData();
});

async function loadData() {
  loading.value = true;
  try {
    // 真实场景：const data = await dashboardApi.getMobileKpi()
    await new Promise((r) => setTimeout(r, 600));
    lastUpdated.value = new Date();
  } catch {
    // ignore
  } finally {
    loading.value = false;
  }
}

async function refresh() {
  refreshing.value = true;
  await loadData();
  refreshing.value = false;
}

// ─────────────────────────────────────────────
// 4. 操作
// ─────────────────────────────────────────────
function quickConfirm(item, approve) {
  // 真实场景：await dashboardApi.confirmHazard(item.id, approve)
  if (approve) {
    pendingConfirm.value = pendingConfirm.value.filter((i) => i.id !== item.id);
    kpi.value.pendingConfirm = Math.max(0, kpi.value.pendingConfirm - 1);
  } else {
    pendingConfirm.value = pendingConfirm.value.filter((i) => i.id !== item.id);
  }
}

function quickPublish(warning) {
  warning.status = "published";
  warning.statusLabel = "已发布";
}

function quickResolve(warning) {
  warning.status = "resolved";
  warning.statusLabel = "已解除";
  kpi.value.activeWarnings = Math.max(0, kpi.value.activeWarnings - 1);
}

function goFullSite() {
  router.push("/dashboard");
}

function goVideo() {
  // 真实场景：打开视频会商 URL 或启动 WebRTC
  window.open("about:blank", "_blank");
}
</script>

<template>
  <div class="mobile-root">
    <!-- 离线提示 -->
    <div v-if="!isOnline" class="offline-banner">
      <WifiOff :size="14" />
      <span>当前离线，部分数据可能过期</span>
    </div>

    <!-- 顶部状态栏 -->
    <header class="mobile-header">
      <div class="header-left">
        <Globe :size="20" class="brand-icon" />
        <div>
          <div class="brand-name">地脉智巡</div>
          <div class="brand-sub">移动指挥终端</div>
        </div>
      </div>
      <div class="header-right">
        <button class="icon-btn" @click="refresh" :disabled="refreshing">
          <RefreshCw :size="18" :class="{ spinning: refreshing }" />
        </button>
        <button class="icon-btn" @click="goFullSite">
          <Eye :size="18" />
        </button>
      </div>
    </header>

    <!-- 加载状态 -->
    <div v-if="loading" class="loading-state">
      <Loader2 :size="36" class="spinning" />
      <span>加载中…</span>
    </div>

    <main v-else class="mobile-main">
      <div class="update-time" v-if="lastUpdated">
        <Clock :size="11" />
        更新于 {{ lastUpdated.toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" }) }}
      </div>

      <!-- KPI 看板 -->
      <section class="kpi-grid">
        <div class="kpi-card kpi-danger" @click="router.push('/dashboard/confirmation-workbench')">
          <div class="kpi-num">{{ kpi.pendingConfirm }}</div>
          <div class="kpi-label">待确认</div>
          <AlertTriangle :size="18" class="kpi-icon" />
        </div>
        <div class="kpi-card kpi-warning" @click="router.push('/dashboard/warnings')">
          <div class="kpi-num">{{ kpi.activeWarnings }}</div>
          <div class="kpi-label">活跃预警</div>
          <Zap :size="18" class="kpi-icon" />
        </div>
        <div class="kpi-card kpi-emergency" @click="router.push('/dashboard/emergency')">
          <div class="kpi-num">{{ kpi.emergencyEvents }}</div>
          <div class="kpi-label">应急事件</div>
          <ShieldCheck :size="18" class="kpi-icon" />
        </div>
        <div class="kpi-card kpi-device" @click="router.push('/dashboard/devices')">
          <div class="kpi-num">{{ kpi.deviceOnline }}%</div>
          <div class="kpi-label">设备在线</div>
          <Wifi :size="18" class="kpi-icon" />
        </div>
      </section>

      <!-- 快捷操作栏 -->
      <section class="quick-actions">
        <button class="action-btn action-video" @click="goVideo">
          <Video :size="20" />
          <span>视频会商</span>
        </button>
        <button class="action-btn action-map" @click="router.push('/dashboard/real-time-map')">
          <MapPin :size="20" />
          <span>实时地图</span>
        </button>
        <button class="action-btn action-messages" @click="router.push('/dashboard/message-center')">
          <MessageSquare :size="20" />
          <span>
            消息
            <span v-if="kpi.unreadMessages" class="badge">{{ kpi.unreadMessages }}</span>
          </span>
        </button>
        <button class="action-btn action-approval" @click="router.push('/dashboard/approvals')">
          <Radar :size="20" />
          <span>
            审批
            <span v-if="kpi.pendingApprovals" class="badge">{{ kpi.pendingApprovals }}</span>
          </span>
        </button>
      </section>

      <!-- 待确认队列 -->
      <section class="section">
        <div class="section-header">
          <span class="section-title">
            <AlertTriangle :size="16" class="text-danger" />
            待确认队列
          </span>
          <button class="link-btn" @click="router.push('/dashboard/confirmation-workbench')">
            查看全部 <ChevronRight :size="14" />
          </button>
        </div>
        <div
          v-for="item in pendingConfirm"
          :key="item.id"
          class="confirm-card"
        >
          <div class="confirm-header">
            <span class="hazard-id">{{ item.id }}</span>
            <span class="confirm-score">{{ Math.round(item.score * 100) }}%</span>
          </div>
          <div class="confirm-location">{{ item.location }}</div>
          <div class="confirm-footer">
            <span class="hazard-type">{{ item.type }}</span>
            <span class="confirm-time">{{ item.time }}</span>
          </div>
          <div class="confirm-actions">
            <button class="btn-confirm btn-approve" @click="quickConfirm(item, true)">
              <ThumbsUp :size="14" /> 确认
            </button>
            <button class="btn-confirm btn-reject" @click="quickConfirm(item, false)">
              <ThumbsDown :size="14" /> 驳回
            </button>
          </div>
        </div>
        <div v-if="!pendingConfirm.length" class="empty-state">
          <CheckCircle2 :size="32" class="text-success" />
          <span>暂无待确认事项</span>
        </div>
      </section>

      <!-- 预警列表 -->
      <section class="section">
        <div class="section-header">
          <span class="section-title">
            <Zap :size="16" class="text-warning" />
            预警中心
          </span>
          <button class="link-btn" @click="router.push('/dashboard/warnings')">
            全部 <ChevronRight :size="14" />
          </button>
        </div>
        <div
          v-for="w in warnings"
          :key="w.id"
          class="warning-card"
          :style="{ borderLeftColor: levelColor(w.level) }"
        >
          <div class="warning-top">
            <span
              class="level-badge"
              :style="{ background: levelColor(w.level), color: '#fff' }"
            >{{ w.levelLabel }}</span>
            <span
              class="status-badge"
              :style="{ background: statusBadge(w.status).bg, color: statusBadge(w.status).color }"
            >{{ w.statusLabel }}</span>
          </div>
          <div class="warning-title">{{ w.title }}</div>
          <div class="warning-meta">
            <MapPin :size="12" />
            <span>{{ w.location }}</span>
            <span class="meta-sep">·</span>
            <Clock :size="12" />
            <span>{{ w.time }}</span>
          </div>
          <div v-if="w.status === 'draft'" class="warning-actions">
            <button class="btn-publish" @click="quickPublish(w)">
              <Zap :size="13" /> 立即发布
            </button>
          </div>
          <div v-else-if="w.status === 'published'" class="warning-actions">
            <button class="btn-resolve" @click="quickResolve(w)">
              <XCircle :size="13" /> 解除预警
            </button>
          </div>
        </div>
      </section>
    </main>

    <!-- 底部安全提示 -->
    <footer class="mobile-footer">
      <span>地脉智巡 v1.7+ · 移动指挥终端</span>
      <span v-if="isOnline" class="online-dot" />
      <span v-else class="offline-dot" />
    </footer>
  </div>
</template>

<style scoped>
/* ── 基础 ── */
.mobile-root {
  min-height: 100vh;
  background: #0f172a;
  color: #e2e8f0;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  display: flex;
  flex-direction: column;
  padding-bottom: env(safe-area-inset-bottom, 0);
}

.offline-banner {
  background: #92400e;
  color: #fef3c7;
  font-size: 12px;
  padding: 6px 16px;
  display: flex;
  align-items: center;
  gap: 6px;
  text-align: center;
  justify-content: center;
}

/* ── 头部 ── */
.mobile-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: #1e293b;
  border-bottom: 1px solid #334155;
  position: sticky;
  top: 0;
  z-index: 10;
  gap: 12px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.brand-icon { color: #38bdf8; }

.brand-name {
  font-size: 16px;
  font-weight: 700;
  color: #f1f5f9;
  line-height: 1.2;
}

.brand-sub {
  font-size: 10px;
  color: #64748b;
}

.header-right {
  display: flex;
  gap: 8px;
}

.icon-btn {
  width: 36px;
  height: 36px;
  border-radius: 8px;
  border: 1px solid #334155;
  background: #1e293b;
  color: #94a3b8;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s;
}

.icon-btn:active {
  background: #334155;
  color: #f1f5f9;
}

/* ── 加载 ── */
.loading-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: #64748b;
  font-size: 14px;
}

/* ── 主内容 ── */
.mobile-main {
  flex: 1;
  overflow-y: auto;
  padding: 12px 12px 80px;
}

.update-time {
  font-size: 11px;
  color: #475569;
  margin-bottom: 10px;
  display: flex;
  align-items: center;
  gap: 4px;
}

/* ── KPI ── */
.kpi-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
  margin-bottom: 16px;
}

.kpi-card {
  background: #1e293b;
  border-radius: 12px;
  padding: 14px;
  position: relative;
  overflow: hidden;
  cursor: pointer;
  transition: transform 0.15s;
  border: 1px solid #334155;
}

.kpi-card:active { transform: scale(0.97); }

.kpi-danger { border-left: 3px solid #ef4444; }
.kpi-warning { border-left: 3px solid #f59e0b; }
.kpi-emergency { border-left: 3px solid #dc2626; }
.kpi-device { border-left: 3px solid #22c55e; }

.kpi-num {
  font-size: 28px;
  font-weight: 800;
  line-height: 1;
  margin-bottom: 4px;
}

.kpi-danger .kpi-num { color: #fca5a5; }
.kpi-warning .kpi-num { color: #fcd34d; }
.kpi-emergency .kpi-num { color: #fca5a5; }
.kpi-device .kpi-num { color: #86efac; }

.kpi-label {
  font-size: 12px;
  color: #94a3b8;
}

.kpi-icon {
  position: absolute;
  top: 12px;
  right: 12px;
  color: #334155;
}

/* ── 快捷操作 ── */
.quick-actions {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  margin-bottom: 20px;
}

.action-btn {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 5px;
  padding: 12px 4px;
  background: #1e293b;
  border: 1px solid #334155;
  border-radius: 10px;
  color: #94a3b8;
  font-size: 11px;
  cursor: pointer;
  transition: all 0.15s;
}

.action-btn:active { background: #334155; color: #f1f5f9; }

.action-video { color: #818cf8; border-color: #3730a3; }
.action-map { color: #34d399; border-color: #065f46; }

.badge {
  background: #ef4444;
  color: #fff;
  border-radius: 10px;
  padding: 1px 5px;
  font-size: 10px;
  font-weight: 700;
}

/* ── 分组 ── */
.section { margin-bottom: 20px; }

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 14px;
  font-weight: 600;
  color: #e2e8f0;
}

.link-btn {
  display: flex;
  align-items: center;
  gap: 2px;
  font-size: 12px;
  color: #38bdf8;
  background: none;
  border: none;
  cursor: pointer;
}

/* ── 确认卡片 ── */
.confirm-card {
  background: #1e293b;
  border: 1px solid #334155;
  border-left: 3px solid #ef4444;
  border-radius: 10px;
  padding: 12px;
  margin-bottom: 8px;
}

.confirm-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 4px;
}

.hazard-id {
  font-size: 13px;
  font-weight: 700;
  color: #f1f5f9;
}

.confirm-score {
  font-size: 12px;
  font-weight: 700;
  color: #fbbf24;
  background: #451a03;
  padding: 1px 6px;
  border-radius: 4px;
}

.confirm-location {
  font-size: 13px;
  color: #94a3b8;
  margin-bottom: 6px;
}

.confirm-footer {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #64748b;
  margin-bottom: 8px;
}

.confirm-actions {
  display: flex;
  gap: 8px;
}

.btn-confirm {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 4px;
  padding: 7px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 600;
  border: none;
  cursor: pointer;
  transition: opacity 0.15s;
}

.btn-confirm:active { opacity: 0.7; }

.btn-approve {
  background: #166534;
  color: #bbf7d0;
}

.btn-reject {
  background: #7f1d1d;
  color: #fecaca;
}

/* ── 预警卡片 ── */
.warning-card {
  background: #1e293b;
  border: 1px solid #334155;
  border-left-width: 3px;
  border-radius: 10px;
  padding: 12px;
  margin-bottom: 8px;
}

.warning-top {
  display: flex;
  gap: 6px;
  margin-bottom: 6px;
}

.level-badge, .status-badge {
  font-size: 11px;
  font-weight: 600;
  padding: 1px 6px;
  border-radius: 4px;
}

.warning-title {
  font-size: 13px;
  font-weight: 600;
  color: #f1f5f9;
  margin-bottom: 5px;
}

.warning-meta {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: #64748b;
}

.meta-sep { color: #334155; }

.warning-actions { margin-top: 8px; }

.btn-publish {
  display: flex;
  align-items: center;
  gap: 4px;
  background: #1d4ed8;
  color: #dbeafe;
  border: none;
  border-radius: 6px;
  padding: 7px 12px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  width: 100%;
  justify-content: center;
}

.btn-resolve {
  display: flex;
  align-items: center;
  gap: 4px;
  background: #374151;
  color: #d1d5db;
  border: none;
  border-radius: 6px;
  padding: 7px 12px;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  width: 100%;
  justify-content: center;
}

/* ── 空状态 ── */
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 24px;
  color: #475569;
  font-size: 13px;
}

/* ── 底部 ── */
.mobile-footer {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  background: #1e293b;
  border-top: 1px solid #334155;
  padding: 8px 16px;
  padding-bottom: calc(8px + env(safe-area-inset-bottom, 0px));
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: #475569;
}

.online-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #22c55e;
}

.offline-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #ef4444;
}

/* ── 动画 ── */
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.spinning {
  animation: spin 1s linear infinite;
}

.text-danger { color: #ef4444; }
.text-warning { color: #f59e0b; }
.text-success { color: #22c55e; }
</style>
