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
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
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
import { dashboardApi } from "../../services/dashboardApi";

defineProps({ searchTerm: { type: String, default: "" }, userRole: { type: String, default: "admin" } });
const emit = defineEmits(["notify"]);

const router = useRouter();
const isOnline = ref(navigator.onLine);
const loading = ref(true);
const refreshing = ref(false);
const lastUpdated = ref(null);
const loadError = ref("");

// ─────────────────────────────────────────────
// 1. KPI 数据（来自后端真实统计）
//    - getOverview(): 监测灾害点 / 在线设备 / 活跃预警 / 巡检完成率
//    - getConfirmationQueueStats(): 待确认数
// ─────────────────────────────────────────────
const kpi = ref({
  pendingConfirm: 0,
  activeWarnings: 0,
  emergencyEvents: 0,
  deviceOnline: null,
  unreadMessages: 0,
  pendingApprovals: 0,
});

// 预警列表（来自 GET /api/warnings）
const warnings = ref([]);

// 待确认队列（来自 GET /api/confirmation/queue）
const pendingConfirm = ref([]);

/** 预警等级中文标签。 */
const LEVEL_LABEL = { red: "红色", orange: "橙色", yellow: "黄色", blue: "蓝色" };

/** 预警状态中文标签。 */
const WARNING_STATUS_LABEL = {
  published: "已发布",
  draft: "待发布",
  resolved: "已解除",
  closed: "已关闭",
  ignored: "已忽略",
};

/** 风险分 → 等级（用于待确认项展示）。 */
function levelFromScore(score) {
  if (score == null) return "blue";
  if (score >= 0.85) return "red";
  if (score >= 0.7) return "orange";
  if (score >= 0.5) return "yellow";
  return "blue";
}

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
  resolved: { bg: "#dde8e0", color: "#4a5568", label: "已解除" },
}[status] ?? { bg: "#edf2f7", color: "#4a5568", label: status });

// ─────────────────────────────────────────────
// 3. 生命周期
// ─────────────────────────────────────────────
function handleOnline() { isOnline.value = true; }
function handleOffline() { isOnline.value = false; }

onMounted(async () => {
  window.addEventListener("online", handleOnline);
  window.addEventListener("offline", handleOffline);
  await loadData();
});

onBeforeUnmount(() => {
  window.removeEventListener("online", handleOnline);
  window.removeEventListener("offline", handleOffline);
});

/** 相对时间格式化（"10 分钟前"）。 */
function relativeTime(iso) {
  if (!iso) return "—";
  const t = new Date(iso).getTime();
  if (!Number.isFinite(t)) return "—";
  const diffMin = Math.floor((Date.now() - t) / 60000);
  if (diffMin < 1) return "刚刚";
  if (diffMin < 60) return `${diffMin} 分钟前`;
  const diffH = Math.floor(diffMin / 60);
  if (diffH < 24) return `${diffH} 小时前`;
  return `${Math.floor(diffH / 24)} 天前`;
}

/** 时刻格式化（HH:mm）。 */
function clockOf(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "—";
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

async function loadData() {
  loading.value = true;
  loadError.value = "";
  try {
    const [overview, confirmStats, warningPage, queue] = await Promise.allSettled([
      dashboardApi.getOverview(),
      dashboardApi.getConfirmationQueueStats(),
      dashboardApi.getWarnings({ page: 1, size: 5 }),
      dashboardApi.getConfirmationQueue({ page: 1, size: 5 }),
    ]);

    // KPI：overview 为 [{title, value, unit, desc, color}]
    if (overview.status === "fulfilled" && Array.isArray(overview.value)) {
      const cards = overview.value;
      const findCard = (kw) => cards.find((c) => (c.title ?? "").includes(kw));
      const onlineCard = findCard("在线监测设备");
      if (onlineCard) {
        // value 形如 "12/15"（在线/总数）
        const [online, total] = String(onlineCard.value ?? "").split("/").map(Number);
        kpi.value.deviceOnline =
          Number.isFinite(online) && Number.isFinite(total) && total > 0
            ? Math.round((online / total) * 1000) / 10
            : null;
      }
      const warnCard = findCard("预警");
      if (warnCard) kpi.value.activeWarnings = Number(warnCard.value) || 0;
    }

    // 待确认数
    if (confirmStats.status === "fulfilled" && confirmStats.value) {
      const s = confirmStats.value;
      kpi.value.pendingConfirm = Number(s.pending ?? s.detected ?? s.total ?? 0) || 0;
    }

    // 应急事件数：取自 /dashboard/summary 的模块计数
    try {
      const meta = await dashboardApi.getModuleMeta();
      kpi.value.emergencyEvents = Number(meta?.modules?.emergencyEvents ?? 0) || 0;
    } catch {
      kpi.value.emergencyEvents = 0;
    }

    // 预警列表
    if (warningPage.status === "fulfilled") {
      const list = warningPage.value?.records ?? warningPage.value?.list ?? warningPage.value?.items ?? [];
      warnings.value = list.map((w) => ({
        id: String(w.id ?? w.warningCode ?? w.code ?? ""),
        title: w.title ?? w.content ?? w.warningTitle ?? "未命名预警",
        level: w.level ?? w.warningLevel ?? "blue",
        levelLabel: LEVEL_LABEL[w.level ?? w.warningLevel] ?? "未知",
        status: w.status ?? "draft",
        statusLabel:
          w.statusLabel ?? WARNING_STATUS_LABEL[w.status] ?? w.status ?? "未知",
        time: clockOf(w.publishedAt ?? w.createdAt ?? w.triggeredAt),
        location: w.regionName ?? w.location ?? w.regionCode ?? "",
      }));
    }

    // 待确认队列
    if (queue.status === "fulfilled") {
      const list = queue.value?.items ?? queue.value?.records ?? [];
      pendingConfirm.value = list.slice(0, 5).map((h) => ({
        id: String(h.id ?? h.hazardCode ?? h.code ?? ""),
        location: h.location ?? h.name ?? h.regionName ?? h.hazardCode ?? "未知位置",
        type: h.hazardType ?? h.type ?? "—",
        score: h.score ?? h.riskScore ?? h.confidence ?? null,
        time: relativeTime(h.detectedAt ?? h.createdAt ?? h.triggeredAt),
      }));
      if (pendingConfirm.value.length && !kpi.value.pendingConfirm) {
        kpi.value.pendingConfirm = pendingConfirm.value.length;
      }
    }

    lastUpdated.value = new Date();
  } catch (e) {
    loadError.value = e?.message ?? "加载失败";
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
async function quickConfirm(item, approve) {
  const eventId = item.eventId ?? item.id;
  try {
    if (approve) {
      await dashboardApi.confirmHazard(eventId);
    } else {
      await dashboardApi.rejectHazard(eventId, "移动端快捷驳回");
    }
    pendingConfirm.value = pendingConfirm.value.filter((i) => i.id !== item.id);
    kpi.value.pendingConfirm = Math.max(0, kpi.value.pendingConfirm - 1);
    emit("notify", approve ? "已确认" : "已驳回");
  } catch (e) {
    emit("notify", `${approve ? "确认" : "驳回"}失败：${e?.message ?? e}`);
  }
}

async function quickPublish(warning) {
  try {
    await dashboardApi.dispatchWarning(warning.id);
    warning.status = "published";
    warning.statusLabel = WARNING_STATUS_LABEL.published;
    emit("notify", "预警已发布");
  } catch (e) {
    emit("notify", `发布失败：${e?.message ?? e}`);
  }
}

async function quickResolve(warning) {
  try {
    await dashboardApi.resolveWarning(warning.id);
    warning.status = "resolved";
    warning.statusLabel = WARNING_STATUS_LABEL.resolved;
    emit("notify", "预警已解除");
  } catch (e) {
    emit("notify", `解除失败：${e?.message ?? e}`);
  }
}

function goFullSite() {
  router.push("/dashboard");
}

function goVideo() {
  // 视频会商需要接入 WebRTC / 第三方会商服务；当前系统没有该能力，
  // 明确告知而不是打开空白页冒充成功。
  emit("notify", "视频会商尚未接入（需部署 WebRTC 信令或第三方会商服务）");
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
          <div class="kpi-num">{{ kpi.deviceOnline != null ? kpi.deviceOnline + '%' : '—' }}</div>
          <div class="kpi-label">设备在线</div>
          <Wifi :size="18" class="kpi-icon" />
        </div>
      </section>

      <!-- 快捷操作栏 -->
      <section class="quick-actions">
        <button
          class="action-btn action-video is-unavailable"
          title="视频会商需接入 WebRTC 信令或第三方会商服务后启用"
          @click="goVideo"
        >
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
  background: var(--c-text);
  color: var(--c-brand-soft);
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  display: flex;
  flex-direction: column;
  padding-bottom: env(safe-area-inset-bottom, 0);
}

.offline-banner {
  background: var(--c-warning-ink);
  color: var(--c-warning-bg);
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
  background: var(--c-scrim);
  border-bottom: 1px solid var(--c-ink-1);
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

.brand-icon { color: var(--sys-cyan); }

.brand-name {
  font-size: 16px;
  font-weight: 700;
  color: var(--c-brand-mist);
  line-height: 1.2;
}

.brand-sub {
  font-size: 10px;
  color: var(--c-text-3);
}

.header-right {
  display: flex;
  gap: 8px;
}

.icon-btn {
  width: 36px;
  height: 36px;
  border-radius: var(--r-sm);
  border: 1px solid var(--c-ink-1);
  background: var(--c-scrim);
  color: var(--c-text-3);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: all 0.15s;
}

.icon-btn:active {
  background: var(--c-ink-1);
  color: var(--c-brand-mist);
}

/* ── 加载 ── */
.loading-state {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: var(--c-text-3);
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
  color: var(--c-ink-1);
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
  background: var(--c-scrim);
  border-radius: var(--r-lg);
  padding: 14px;
  position: relative;
  overflow: hidden;
  cursor: pointer;
  transition: transform 0.15s;
  border: 1px solid var(--c-ink-1);
}

.kpi-card:active { transform: scale(0.97); }

.kpi-danger { border-left: 3px solid var(--c-danger); }
.kpi-warning { border-left: 3px solid var(--c-warning); }
.kpi-emergency { border-left: 3px solid var(--c-danger-strong); }
.kpi-device { border-left: 3px solid var(--c-success); }

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
  color: var(--c-text-3);
}

.kpi-icon {
  position: absolute;
  top: 12px;
  right: 12px;
  color: var(--c-ink-1);
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
  background: var(--c-scrim);
  border: 1px solid var(--c-ink-1);
  border-radius: var(--r-md);
  color: var(--c-text-3);
  font-size: 11px;
  cursor: pointer;
  transition: all 0.15s;
}

.action-btn:active { background: var(--c-ink-1); color: var(--c-brand-mist); }

.action-video { color: #818cf8; border-color: #3730a3; }
.action-btn.is-unavailable { opacity: .55; }
.action-map { color: #34d399; border-color: #065f46; }

.badge {
  background: var(--c-danger);
  color: var(--c-on-brand);
  border-radius: var(--r-md);
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
  color: var(--c-brand-soft);
}

.link-btn {
  display: flex;
  align-items: center;
  gap: 2px;
  font-size: 12px;
  color: var(--sys-cyan);
  background: none;
  border: none;
  cursor: pointer;
}

/* ── 确认卡片 ── */
.confirm-card {
  background: var(--c-scrim);
  border: 1px solid var(--c-ink-1);
  border-left: 3px solid var(--c-danger);
  border-radius: var(--r-md);
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
  color: var(--c-brand-mist);
}

.confirm-score {
  font-size: 12px;
  font-weight: 700;
  color: #fbbf24;
  background: #451a03;
  padding: 1px 6px;
  border-radius: var(--r-xs);
}

.confirm-location {
  font-size: 13px;
  color: var(--c-text-3);
  margin-bottom: 6px;
}

.confirm-footer {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: var(--c-text-3);
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
  border-radius: var(--r-sm);
  font-size: 12px;
  font-weight: 600;
  border: none;
  cursor: pointer;
  transition: opacity 0.15s;
}

.btn-confirm:active { opacity: 0.7; }

.btn-approve {
  background: var(--c-success-strong);
  color: #bbf7d0;
}

.btn-reject {
  background: #7f1d1d;
  color: #fecaca;
}

/* ── 预警卡片 ── */
.warning-card {
  background: var(--c-scrim);
  border: 1px solid var(--c-ink-1);
  border-left-width: 3px;
  border-radius: var(--r-md);
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
  border-radius: var(--r-xs);
}

.warning-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--c-brand-mist);
  margin-bottom: 5px;
}

.warning-meta {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
  color: var(--c-text-3);
}

.meta-sep { color: var(--c-ink-1); }

.warning-actions { margin-top: 8px; }

.btn-publish {
  display: flex;
  align-items: center;
  gap: 4px;
  background: var(--c-brand-deeper);
  color: var(--sys-blue-soft);
  border: none;
  border-radius: var(--r-sm);
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
  background: var(--c-ink-1);
  color: var(--c-border);
  border: none;
  border-radius: var(--r-sm);
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
  color: var(--c-ink-1);
  font-size: 13px;
}

/* ── 底部 ── */
.mobile-footer {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  background: var(--c-scrim);
  border-top: 1px solid var(--c-ink-1);
  padding: 8px 16px;
  padding-bottom: calc(8px + env(safe-area-inset-bottom, 0px));
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 11px;
  color: var(--c-ink-1);
}

.online-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--c-success);
}

.offline-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--c-danger);
}

/* ── 动画 ── */
@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

.spinning {
  animation: spin 1s linear infinite;
}

.text-danger { color: var(--c-danger); }
.text-warning { color: var(--c-warning); }
.text-success { color: var(--c-success); }
</style>
