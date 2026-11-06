<script setup>
/**
 * 预警派发/回执 Tab 组件 (/dashboard/warnings/:id?tab=delivery)。
 *
 * - 展示 KPI：应发人数 / 已送达 / 已阅读 / 已确认
 * - 5 个分渠道明细 Tab（短信 / APP 推送 / 邮件 / 声光 LED / Webhook）
 * - 重发失败、重发全量、导出报告
 * - 加载状态、错误态、空态
 *
 * 接收 warningId 作为 prop，emit('refresh') 通知父组件刷新详情。
 */
import { computed, onMounted, ref, watch } from "vue";
import {
  CheckCheck,
  DownloadCloud,
  Eye,
  Inbox,
  Loader2,
  Mail,
  MessageSquare,
  MonitorPlay,
  RefreshCcw,
  Send,
  Smartphone,
  Webhook,
  X,
} from "lucide-vue-next";
import PermissionButton from "./PermissionButton.vue";
import { dashboardApi } from "../services/dashboardApi";

const props = defineProps({
  warningId: { type: String, required: true },
});
const emit = defineEmits(["refresh", "notify"]);

const warningDetail = ref(null);
const summary = ref({
  shouldDeliver: 0,
  delivered: 0,
  read: 0,
  confirmed: 0,
  channels: [],
});
const channelRecords = ref({
  sms: { records: [], loading: true, error: "" },
  app: { records: [], loading: true, error: "" },
  email: { records: [], loading: true, error: "" },
  led: { records: [], loading: true, error: "" },
  webhook: { records: [], loading: true, error: "" },
});
const loading = ref(true);
const loadError = ref("");
const activeTab = ref("sms");
const confirmState = ref({ open: false, channel: "", targetIds: null });

const channelMeta = [
  { key: "sms",     label: "短信",     icon: MessageSquare, color: "#6f9e7f" },
  { key: "app",     label: "APP 推送", icon: Smartphone,    color: "#638fc2" },
  { key: "email",   label: "邮件",     icon: Mail,          color: "#8d79ba" },
  { key: "led",     label: "声光 / LED 大屏", icon: MonitorPlay, color: "#d88561" },
  { key: "webhook", label: "Webhook",  icon: Webhook,       color: "#6f9e7f" },
];

/**
 * 各渠道明细表的列定义（数据驱动）。
 * 用一份配置替代 5 段重复的 <table>，保证列间距/表头样式完全一致。
 * kind: "text"(默认) | "badge" | "thumb"
 */
const channelViews = {
  sms: {
    empty: { title: "暂无短信发送记录", desc: "本次预警尚未产生短信渠道的发送回执" },
    columns: [
      { label: "手机号",   get: (r) => r.phone ?? r.target ?? "—", strong: true },
      { label: "发送时间", get: (r) => r.sentAt ?? r.time ?? "—" },
      { label: "状态",     get: (r) => r.status ?? "—", kind: "badge" },
      { label: "失败原因", get: (r) => r.reason ?? r.failureReason ?? "—", muted: true },
    ],
  },
  app: {
    empty: { title: "暂无 APP 推送记录", desc: "本次预警尚未产生 APP 推送回执" },
    columns: [
      { label: "用户",     get: (r) => r.user ?? r.target ?? "—", strong: true },
      { label: "推送状态", get: (r) => r.status ?? "—", kind: "badge" },
      { label: "是否点击", get: (r) => r.clicked ?? r.click ?? "—" },
      { label: "APP 端回执", get: (r) => r.confirmed ?? r.ack ?? "—" },
    ],
  },
  email: {
    empty: { title: "暂无邮件发送记录", desc: "本次预警尚未产生邮件渠道的发送回执" },
    columns: [
      { label: "邮箱",     get: (r) => r.email ?? r.target ?? "—", strong: true },
      { label: "发送状态", get: (r) => r.status ?? "—", kind: "badge" },
      { label: "退信原因", get: (r) => r.bounce ?? r.reason ?? "—", muted: true },
    ],
  },
  led: {
    empty: { title: "暂无 LED 大屏播放记录", desc: "本次预警未联动声光 / LED 大屏，或后端尚未记录" },
    columns: [
      { label: "点位",       get: (r) => r.endpoint ?? r.point ?? r.target ?? "—", strong: true },
      { label: "状态",       get: (r) => r.status ?? "—", kind: "badge" },
      { label: "截图缩略图", kind: "thumb" },
    ],
  },
  webhook: {
    empty: { title: "暂无 Webhook 回调记录", desc: "外部系统尚未回传该预警的处理结果" },
    columns: [
      { label: "回调地址",  get: (r) => r.url ?? r.endpoint ?? r.target ?? "—", strong: true },
      { label: "HTTP Code", get: (r) => r.httpCode ?? r.code ?? r.status ?? "—", kind: "badge" },
      { label: "耗时",      get: (r) => r.latencyMs ?? r.costMs ?? r.duration ?? "—" },
      { label: "状态",      get: (r) => r.message ?? r.note ?? "—" },
    ],
  },
};

/** 当前渠道的空状态文案 */
const activeEmpty = computed(
  () => channelViews[activeTab.value]?.empty ?? channelViews.sms.empty,
);

/** 当前渠道的表格列定义 */
const activeColumns = computed(
  () => channelViews[activeTab.value]?.columns ?? [],
);

/** 当前渠道的名称（加载态提示用） */
const activeChannelLabel = computed(
  () => channelMeta.find((m) => m.key === activeTab.value)?.label ?? "渠道",
);

/** 当前渠道的记录集合（模板里多处复用，避免重复索引） */
const activeRecords = computed(
  () => channelRecords.value[activeTab.value]?.records ?? [],
);

/** 当前渠道是否处于加载中 */
const activeLoading = computed(
  () => channelRecords.value[activeTab.value]?.loading ?? false,
);

const failedCount = computed(() => {
  const all = Object.values(channelRecords.value).flatMap((c) => c.records ?? []);
  return all.filter((r) => r.status === "失败").length;
});

const allCount = computed(() => {
  return Object.values(channelRecords.value).reduce(
    (acc, c) => acc + (c.records?.length ?? 0),
    0,
  );
});

const deliveryRate = computed(() => {
  if (!summary.value.shouldDeliver) return 0;
  return Math.round((summary.value.delivered / summary.value.shouldDeliver) * 100);
});

const readRate = computed(() => {
  if (!summary.value.delivered) return 0;
  return Math.round((summary.value.read / summary.value.delivered) * 100);
});

const confirmRate = computed(() => {
  if (!summary.value.delivered) return 0;
  return Math.round((summary.value.confirmed / summary.value.delivered) * 100);
});

const triggerTimeText = computed(() => {
  const ts = warningDetail.value?.item?.updatedAt;
  if (!ts) return warningDetail.value?.item?.time ?? "—";
  try {
    return new Date(ts).toLocaleString("zh-CN", { hour12: false });
  } catch (_) {
    return ts;
  }
});

const triggerPointText = computed(
  () => warningDetail.value?.item?.point ?? "—",
);

const levelClass = computed(() => {
  const level = warningDetail.value?.item?.level ?? "";
  if (level.includes("红")) return "red";
  if (level.includes("橙")) return "orange";
  if (level.includes("黄")) return "yellow";
  if (level.includes("蓝")) return "blue";
  return "blue";
});

const levelColor = computed(() => {
  const map = { red: "#e06060", orange: "#cbaa76", yellow: "#d9c040", blue: "#87b096" };
  return map[levelClass.value];
});

async function loadData() {
  loading.value = true;
  loadError.value = "";
  try {
    const [detail, sum] = await Promise.all([
      dashboardApi.getWarningDetail(props.warningId).catch(() => null),
      dashboardApi.getDeliverySummary(props.warningId).catch((e) => {
        loadError.value = e?.message ?? "加载发布汇总失败";
        return {
          shouldDeliver: 0,
          delivered: 0,
          read: 0,
          confirmed: 0,
          channels: [],
        };
      }),
    ]);
    warningDetail.value = detail;
    summary.value = sum ?? summary.value;

    await Promise.all(channelMeta.map((m) => loadChannel(m.key)));
  } catch (error) {
    loadError.value =
      error instanceof Error ? error.message : "加载失败，请稍后重试";
  } finally {
    loading.value = false;
  }
}

async function loadChannel(channel) {
  channelRecords.value[channel].loading = true;
  channelRecords.value[channel].error = "";
  try {
    const data = await dashboardApi.getDeliveryRecords(props.warningId, {
      channel,
    });
    const records = Array.isArray(data?.records)
      ? data.records
      : Array.isArray(data?.list)
        ? data.list
        : Array.isArray(data)
          ? data
          : [];
    channelRecords.value[channel].records = records;
  } catch (error) {
    channelRecords.value[channel].error =
      error instanceof Error ? error.message : `${channel} 记录加载失败`;
  } finally {
    channelRecords.value[channel].loading = false;
  }
}

function retry() {
  loadData();
}

function openConfirm(channel, targetIds) {
  confirmState.value = { open: true, channel, targetIds: targetIds ?? null };
}

function closeConfirm() {
  confirmState.value = { open: false, channel: "", targetIds: null };
}

async function runRedeliver() {
  const { channel, targetIds } = confirmState.value;
  closeConfirm();
  try {
    const payload = { channel };
    if (targetIds) payload.targetIds = targetIds;
    const result = await dashboardApi.redeliver(props.warningId, payload);
    const success =
      typeof result === "object" && result !== null
        ? result.success !== false
        : Boolean(result);
    if (success) {
      emit("notify", `${channel} 渠道已发起重发`);
      // 重发后通知父组件刷新基本信息（详情细节 1）
      emit("refresh");
      await loadChannel(channel);
    } else {
      emit("notify", "重发请求已提交，但服务返回状态未知");
    }
  } catch (error) {
    emit("notify", error instanceof Error ? error.message : "重发失败");
  }
}

function exportReport(format = "csv") {
  const rows = [];
  rows.push(["渠道", "目标", "发送时间", "状态", "备注"]);
  Object.entries(channelRecords.value).forEach(([key, bag]) => {
    (bag.records ?? []).forEach((r) => {
      rows.push([
        channelMeta.find((m) => m.key === key)?.label ?? key,
        r.target ?? r.phone ?? r.email ?? r.user ?? r.url ?? r.endpoint ?? "—",
        r.sentAt ?? r.time ?? r.pushedAt ?? "—",
        r.status ?? "—",
        r.reason ?? r.failureReason ?? r.error ?? r.bounce ?? "",
      ]);
    });
  });
  const csv = rows
    .map((row) =>
      row
        .map((cell) => {
          const s = String(cell ?? "");
          return /[",\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
        })
        .join(","),
    )
    .join("\n");
  const blob = new Blob(["\uFEFF" + csv], {
    type: format === "xlsx"
      ? "application/vnd.ms-excel"
      : "text/csv;charset=utf-8",
  });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `预警发布回执-${props.warningId}.${format === "xlsx" ? "xls" : "csv"}`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
  emit("notify", `已导出 ${format.toUpperCase()} 报告`);
}

function statusBadgeClass(status) {
  const s = String(status ?? "");
  if (s.includes("成功") || s.includes("已读") || s.includes("已确认") || s === "DELIVERED")
    return "ok";
  if (s.includes("失败") || s.includes("退信") || s === "FAILED") return "fail";
  if (s.includes("待") || s.includes("发送中") || s === "PENDING") return "pending";
  return "neutral";
}

onMounted(loadData);
watch(() => props.warningId, () => loadData());
</script>

<template>
  <div class="delivery-tab">
    <div v-if="loading" class="panel loading-panel">
      <div class="skeleton-row skeleton-bar" style="width: 60%"></div>
      <div class="skeleton-grid">
        <div class="skeleton-card skeleton-card--hero">
          <div class="skeleton-bar small"></div>
          <div class="skeleton-bar big"></div>
          <div class="skeleton-bar tiny"></div>
        </div>
        <div v-for="i in 3" :key="i" class="skeleton-card">
          <div class="skeleton-bar small"></div>
          <div class="skeleton-bar big"></div>
        </div>
      </div>
      <div class="skeleton-row skeleton-bar" style="width: 80%; margin-top: 18px"></div>
      <div class="skeleton-table">
        <div v-for="i in 5" :key="i" class="skeleton-bar" style="width: 100%"></div>
      </div>
    </div>

    <div v-else-if="loadError && !warningDetail" class="panel error-panel">
      <strong>加载失败</strong>
      <p>{{ loadError }}</p>
      <button class="primary-btn" @click="retry">
        <RefreshCcw :size="14" />
        重试
      </button>
    </div>

    <template v-else>
      <!-- ═══════════════════════════════════════════════════════════
           顶部指标区：1 个核心指标（已送达）+ 3 个次要指标
           核心指标用浅绿底强调，次要指标保持白底弱化，避免 4 张卡片同权重
           ═══════════════════════════════════════════════════════════ -->
      <section class="delivery-metrics">
        <!-- 核心指标：已送达 -->
        <article class="metric-card metric-card--hero">
          <header class="metric-head">
            <span class="metric-icon metric-icon--hero"><MessageSquare :size="16" /></span>
            <span class="metric-label">已送达</span>
          </header>
          <div class="metric-figure" :class="{ 'is-empty': !summary.delivered }">
            <strong>{{ summary.delivered }}</strong>
            <small>人</small>
          </div>
          <div class="metric-progress">
            <div class="metric-progress-track">
              <i :style="{ width: deliveryRate + '%' }"></i>
            </div>
            <span class="metric-progress-text">触达率 {{ deliveryRate }}%</span>
          </div>
        </article>

        <!-- 次要指标：应发人数 -->
        <article class="metric-card">
          <header class="metric-head">
            <span class="metric-icon"><Send :size="15" /></span>
            <span class="metric-label">应发人数</span>
          </header>
          <div class="metric-figure metric-figure--sm" :class="{ 'is-empty': !summary.shouldDeliver }">
            <strong>{{ summary.shouldDeliver }}</strong>
            <small>人</small>
          </div>
          <p class="metric-note">
            {{ summary.shouldDeliver ? `${allCount} 条明细 · ${summary.channels?.length || 5} 个渠道` : "暂无下发计划" }}
          </p>
        </article>

        <!-- 次要指标：已阅读 -->
        <article class="metric-card">
          <header class="metric-head">
            <span class="metric-icon"><Eye :size="15" /></span>
            <span class="metric-label">已阅读</span>
          </header>
          <div class="metric-figure metric-figure--sm" :class="{ 'is-empty': !summary.read }">
            <strong>{{ summary.read }}</strong>
            <small>人</small>
          </div>
          <p class="metric-note">{{ summary.read ? `阅读率 ${readRate}%` : "暂无阅读回执" }}</p>
        </article>

        <!-- 次要指标：已确认回执 -->
        <article class="metric-card">
          <header class="metric-head">
            <span class="metric-icon"><CheckCheck :size="15" /></span>
            <span class="metric-label">已确认回执</span>
          </header>
          <div class="metric-figure metric-figure--sm" :class="{ 'is-empty': !summary.confirmed }">
            <strong>{{ summary.confirmed }}</strong>
            <small>人</small>
          </div>
          <p class="metric-note">{{ summary.confirmed ? `回执率 ${confirmRate}%` : "暂无回执确认" }}</p>
        </article>
      </section>

      <!-- ═══════════════════════════════════════════════════════════
           分渠道明细：Segment Control 切换 + 数据驱动表格
           ═══════════════════════════════════════════════════════════ -->
      <section class="panel delivery-panel">
        <header class="delivery-panel-head">
          <div class="delivery-panel-title">
            <h2>分渠道明细</h2>
            <p>查看每个渠道的目标、状态与失败原因</p>
          </div>
          <div class="delivery-panel-actions">
            <button class="ghost-action" @click="exportReport('csv')">
              <DownloadCloud :size="13" />
              导出 CSV
            </button>
            <button class="ghost-action" @click="exportReport('xlsx')">
              <DownloadCloud :size="13" />
              导出 Excel
            </button>
          </div>
        </header>

        <!-- 分段控制器：选中=白底+微阴影，未选中=透明底 -->
        <div class="segment-control" role="tablist" aria-label="通知渠道">
          <button
            v-for="ch in channelMeta"
            :key="ch.key"
            class="segment-item"
            :class="{ active: activeTab === ch.key }"
            role="tab"
            :aria-selected="activeTab === ch.key"
            type="button"
            @click="activeTab = ch.key"
          >
            <component :is="ch.icon" :size="14" />
            <span class="segment-label">{{ ch.label }}</span>
            <em class="segment-count">{{ channelRecords[ch.key].records.length }}</em>
          </button>
        </div>

        <!-- 渠道明细表 -->
        <div class="tab-body">
          <div v-if="activeLoading" class="tab-loading">
            <Loader2 :size="14" class="spin" />{{ activeChannelLabel }}明细加载中
          </div>

          <!-- 空状态 -->
          <div v-else-if="activeRecords.length === 0" class="delivery-empty">
            <span class="delivery-empty-icon">
              <Inbox :size="22" />
            </span>
            <p class="delivery-empty-title">{{ activeEmpty.title }}</p>
            <small class="delivery-empty-desc">{{ activeEmpty.desc }}</small>
            <RouterLink class="delivery-empty-link" to="/dashboard/notification-channels">
              去配置通知渠道
            </RouterLink>
          </div>

          <div v-else class="table-wrap">
            <table class="data-table">
              <thead>
                <tr>
                  <th v-for="col in activeColumns" :key="col.label">{{ col.label }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="(row, index) in activeRecords" :key="row.id ?? index">
                  <template v-for="col in activeColumns">
                    <td v-if="col.kind === 'thumb'" :key="col.label" class="thumb-cell">
                      <div class="thumb-placeholder" :title="row.screenshot ?? '截图占位'">
                        <MonitorPlay :size="14" />
                      </div>
                      <small>{{ row.screenshotAt ?? row.capturedAt ?? "实时" }}</small>
                    </td>
                    <td v-else-if="col.kind === 'badge'" :key="col.label">
                      <span class="status-badge" :class="statusBadgeClass(col.get(row))">
                        {{ col.get(row) }}
                      </span>
                    </td>
                    <td v-else :key="col.label" :class="{ muted: col.muted }">
                      <strong v-if="col.strong">{{ col.get(row) }}</strong>
                      <template v-else>{{ col.get(row) }}</template>
                    </td>
                  </template>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- 底部独立操作栏 -->
        <footer class="delivery-foot">
          <div class="delivery-foot-left">
            <PermissionButton
              permission="warnings:dispatch"
              :icon="RefreshCcw"
              :disabled="!failedCount"
              @click="({ allowed, reason }) => {
                if (!allowed) return emit('notify', reason)
                openConfirm('failed', null)
              }"
            >
              重发失败名单（{{ failedCount }}）
            </PermissionButton>
            <PermissionButton
              permission="warnings:dispatch"
              :icon="Send"
              @click="({ allowed, reason }) => {
                if (!allowed) return emit('notify', reason)
                openConfirm('all', null)
              }"
            >
              重发全量
            </PermissionButton>
          </div>
          <span class="delivery-foot-note">重发操作会写入审计日志</span>
        </footer>
      </section>
    </template>

    <!-- 二次确认弹窗 -->
    <div v-if="confirmState.open" class="modal-backdrop" @click.self="closeConfirm">
      <div class="modal confirm-modal">
        <div class="modal-head">
          <strong>确认重发</strong>
          <button class="close-popover" @click="closeConfirm"><X :size="14" /></button>
        </div>
        <div class="modal-body">
          <p>
            即将对当前预警执行
            <strong>
              {{
                confirmState.targetIds
                  ? `指定 ${confirmState.targetIds.length} 个目标`
                  : confirmState.channel === 'failed'
                    ? '失败名单全量'
                    : '所有渠道全量'
              }}
            </strong>
            重发，是否继续？
          </p>
          <small class="muted">本次操作会调用后端重发接口并写入审计日志</small>
        </div>
        <div class="modal-foot">
          <button class="secondary-btn" @click="closeConfirm">取消</button>
          <button class="primary-btn" @click="runRedeliver">确认重发</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* ═══════════════════════════════════════════════════════════
   通知与送达 Tab — 精修版
   层级策略：核心指标（浅绿底 + 进度条）→ 次要指标（白底弱化）
             → 渠道分段控制器 → 明细表 → 底部独立操作栏
   ═══════════════════════════════════════════════════════════ */

.delivery-tab {
  display: flex;
  flex-direction: column;
  gap: var(--space-xl); /* 大区块之间用 space-xl 拉开垂直节奏 */
}

/* ═══════════════════════════════════════════════════════════
   1. 顶部指标区
   ═══════════════════════════════════════════════════════════ */
.delivery-metrics {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr 1fr;
  gap: var(--space-md);
  align-items: stretch;
}

.metric-card {
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--space-md) var(--space-lg);
  box-shadow: var(--shadow-xs);
  display: flex;
  flex-direction: column;
  gap: var(--space-xs);
  min-width: 0;
}

/* 核心指标：浅绿底 + 更粗字体，与次要指标拉开权重 */
.metric-card--hero {
  background: var(--color-primary-soft);
  border-color: color-mix(in srgb, var(--color-primary) 24%, transparent);
  box-shadow: var(--shadow-sm);
}

.metric-head {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  min-width: 0;
}

.metric-icon {
  width: 26px;
  height: 26px;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  border-radius: var(--radius-sm);
  color: var(--color-muted-foreground);
  background: var(--color-surface-2);
}

.metric-icon--hero {
  color: var(--color-primary);
  background: color-mix(in srgb, var(--color-primary) 14%, transparent);
}

.metric-label {
  font-size: var(--text-sm);
  font-weight: var(--font-medium);
  color: var(--color-muted-foreground);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.metric-card--hero .metric-label {
  color: var(--color-primary);
  font-weight: var(--font-semibold);
}

/* 数字排版：大号数字 + 灰色单位 */
.metric-figure {
  display: flex;
  align-items: baseline;
  gap: 3px;
  line-height: 1.05;
}

.metric-figure strong {
  font-size: var(--text-4xl);
  font-weight: var(--font-bold);
  letter-spacing: -0.03em;
  color: var(--color-foreground);
  font-variant-numeric: tabular-nums; /* 数字动态变化时不抖动 */
}

.metric-figure small {
  font-size: var(--text-sm);
  font-weight: var(--font-medium);
  color: var(--color-tertiary);
}

/* 次要指标数字更小，视觉权重低于核心指标 */
.metric-figure--sm strong {
  font-size: var(--text-3xl);
  font-weight: var(--font-semibold);
}

/* 数值为 0：数字降级为 tertiary，避免大号 0 抢戏 */
.metric-figure.is-empty strong {
  color: var(--color-tertiary);
}

.metric-card--hero .metric-figure.is-empty strong {
  color: color-mix(in srgb, var(--color-primary) 45%, var(--color-tertiary));
}

.metric-note {
  margin: 0;
  font-size: var(--text-xs);
  color: var(--color-muted-foreground);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 核心指标底部的触达率进度条 */
.metric-progress {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  margin-top: auto;
  padding-top: var(--space-sm);
}

.metric-progress-track {
  flex: 1;
  height: 4px;
  border-radius: var(--radius-full);
  background: color-mix(in srgb, var(--color-primary) 16%, transparent);
  overflow: hidden;
}

.metric-progress-track i {
  display: block;
  height: 100%;
  border-radius: var(--radius-full);
  background: var(--color-primary);
  transition: width var(--duration-slow) var(--ease-out);
}

.metric-progress-text {
  font-size: var(--text-xs);
  font-weight: var(--font-semibold);
  color: var(--color-primary);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

/* ═══════════════════════════════════════════════════════════
   2. 分渠道明细面板
   ═══════════════════════════════════════════════════════════ */
.delivery-panel {
  padding-bottom: 0;
  overflow: hidden;
}

.delivery-panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--space-md);
  padding: var(--space-lg) var(--space-lg) var(--space-md);
  flex-wrap: wrap;
}

.delivery-panel-title h2 {
  margin: 0;
  font-size: var(--text-lg);
  font-weight: var(--font-semibold);
  letter-spacing: -0.01em;
  color: var(--color-foreground);
}

.delivery-panel-title p {
  margin: 4px 0 0;
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
}

.delivery-panel-actions {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

/* 幽灵按钮：透明底 + 弱化描边，仅在 hover 时显形 */
.ghost-action {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 11px;
  min-height: 30px;
  border-radius: var(--radius-sm);
  border: 1px solid transparent;
  background: transparent;
  color: var(--color-muted-foreground);
  font-size: var(--text-xs);
  font-weight: var(--font-medium);
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out),
    transform var(--duration-fast) var(--ease-out);
}

.ghost-action:hover {
  background: var(--color-surface-1);
  border-color: var(--color-border);
  color: var(--color-foreground);
}

.ghost-action:active {
  transform: scale(0.96);
}

.ghost-action:focus-visible {
  outline: none;
  box-shadow: var(--shadow-focus);
}

/* ═══════════════════════════════════════════════════════════
   3. 分段控制器（Segment Control）
   选中 = 白底 + 微阴影；未选中 = 透明底
   ═══════════════════════════════════════════════════════════ */
.segment-control {
  display: flex;
  align-items: center;
  gap: 2px;
  margin: 0 var(--space-lg) var(--space-md);
  padding: 4px;
  background: var(--color-surface-1);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  overflow-x: auto;
  scrollbar-width: none;
}

.segment-control::-webkit-scrollbar { display: none; }

.segment-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex: 1 1 auto;
  justify-content: center;
  padding: 7px 12px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-muted-foreground);
  font-size: var(--text-sm);
  font-weight: var(--font-medium);
  font-family: inherit;
  white-space: nowrap;
  cursor: pointer;
  transition: background var(--duration-fast) var(--ease-out),
    color var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out),
    box-shadow var(--duration-fast) var(--ease-out),
    transform var(--duration-fast) var(--ease-out);
}

.segment-item:hover {
  color: var(--color-foreground);
  background: var(--color-primary-mist);
}

.segment-item:active {
  transform: scale(0.96);
}

.segment-item:focus-visible {
  outline: none;
  box-shadow: var(--shadow-focus);
}

.segment-item.active {
  background: var(--color-card);
  color: var(--color-foreground);
  font-weight: var(--font-semibold);
  border-color: var(--color-border);
  box-shadow: var(--shadow-xs);
}

.segment-count {
  font-style: normal;
  font-size: var(--text-xs);
  font-weight: var(--font-semibold);
  color: var(--color-tertiary);
  background: var(--color-surface-2);
  border-radius: var(--radius-full);
  padding: 1px 6px;
  font-variant-numeric: tabular-nums;
  min-width: 20px;
  text-align: center;
}

.segment-item.active .segment-count {
  color: var(--color-primary);
  background: var(--color-primary-soft);
}

/* ═══════════════════════════════════════════════════════════
   4. 明细表
   ═══════════════════════════════════════════════════════════ */
.tab-body {
  padding: 0 var(--space-lg);
}

.tab-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: var(--space-sm);
  padding: var(--space-2xl) var(--space-lg);
  color: var(--color-muted-foreground);
  font-size: var(--text-sm);
}

.spin { animation: spin 1s linear infinite; }
@keyframes spin {
  from { transform: rotate(0); }
  to { transform: rotate(360deg); }
}

.table-wrap {
  width: 100%;
  overflow-x: auto;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--text-sm);
  min-width: 560px;
}

.data-table th,
.data-table td {
  padding: 10px var(--space-md);
  text-align: left;
  border-bottom: 1px solid var(--color-border);
}

.data-table th {
  background: var(--color-surface-1);
  color: var(--color-muted-foreground);
  font-weight: var(--font-semibold);
  font-size: var(--text-xs);
  white-space: nowrap;
}

.data-table td strong {
  color: var(--color-foreground);
  font-weight: var(--font-semibold);
  font-variant-numeric: tabular-nums;
}

.data-table td.muted {
  color: var(--color-muted-foreground);
}

.data-table tbody tr {
  transition: background var(--duration-fast) var(--ease-out);
}

.data-table tbody tr:hover {
  background: var(--color-surface-1);
}

.status-badge {
  display: inline-block;
  padding: 2px 9px;
  border-radius: var(--radius-full);
  font-size: var(--text-xs);
  font-weight: var(--font-semibold);
  white-space: nowrap;
}

.status-badge.ok { color: var(--color-primary); background: var(--color-primary-soft); }
.status-badge.fail { color: var(--color-destructive-strong); background: var(--color-destructive-bg); }
.status-badge.pending { color: var(--color-warning-ink); background: var(--color-warning-bg); }
.status-badge.neutral { color: var(--color-muted-foreground); background: var(--color-surface-2); }

.thumb-cell {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
}

.thumb-placeholder {
  width: 38px;
  height: 26px;
  display: grid;
  place-items: center;
  color: var(--color-warning-ink);
  background: repeating-linear-gradient(
    45deg,
    var(--color-warning-bg) 0 4px,
    transparent 4px 8px
  );
  border: 1px solid color-mix(in srgb, var(--color-warning) 40%, transparent);
  border-radius: var(--radius-xs);
}

.thumb-cell small {
  color: var(--color-muted-foreground);
  font-size: var(--text-xs);
}

/* ═══════════════════════════════════════════════════════════
   5. 空状态
   ═══════════════════════════════════════════════════════════ */
.delivery-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-2xl) var(--space-lg);
  margin: var(--space-sm) 0 var(--space-lg);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface-1);
  text-align: center;
}

.delivery-empty-icon {
  width: 46px;
  height: 46px;
  display: grid;
  place-items: center;
  border-radius: var(--radius-lg);
  color: var(--color-primary);
  background: var(--color-primary-soft);
}

.delivery-empty-title {
  margin: var(--space-sm) 0 0;
  font-size: var(--text-base);
  font-weight: var(--font-semibold);
  color: var(--color-muted-foreground);
}

.delivery-empty-desc {
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  max-width: 42ch;
}

.delivery-empty-link {
  margin-top: var(--space-xs);
  font-size: var(--text-sm);
  font-weight: var(--font-medium);
  color: var(--color-primary);
  text-decoration: underline;
  text-underline-offset: 3px;
  text-decoration-thickness: 1px;
  transition: color var(--duration-fast) var(--ease-out);
}

.delivery-empty-link:hover {
  color: var(--color-primary-hover);
}

.delivery-empty-link:focus-visible {
  outline: none;
  box-shadow: var(--shadow-focus);
  border-radius: var(--radius-xs);
}

/* ═══════════════════════════════════════════════════════════
   6. 底部独立操作栏
   ═══════════════════════════════════════════════════════════ */
.delivery-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--space-md);
  flex-wrap: wrap;
  margin-top: var(--space-lg);
  padding: var(--space-md) var(--space-lg);
  border-top: 1px solid var(--color-border);
  background: var(--color-surface-1);
}

.delivery-foot-left {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  flex-wrap: wrap;
}

.delivery-foot-note {
  font-size: var(--text-xs);
  color: var(--color-tertiary);
}

/* ═══════════════════════════════════════════════════════════
   7. 加载 / 错误态
   ═══════════════════════════════════════════════════════════ */
.loading-panel {
  padding: var(--space-lg);
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.skeleton-row { display: flex; gap: var(--space-sm); }

.skeleton-bar {
  height: 14px;
  border-radius: var(--radius-xs);
  background: linear-gradient(
    90deg,
    var(--color-primary-mist),
    var(--color-surface-1),
    var(--color-primary-mist)
  );
  animation: pulse 1.2s ease-in-out infinite;
}

.skeleton-bar.small { width: 30%; height: 10px; }
.skeleton-bar.big { width: 60%; height: 22px; margin-top: 8px; }
.skeleton-bar.tiny { width: 80%; height: 4px; margin-top: 10px; }

.skeleton-grid {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr 1fr;
  gap: var(--space-md);
  margin-top: var(--space-sm);
}

.skeleton-card {
  padding: var(--space-md);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface-1);
}

.skeleton-table {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

@keyframes pulse {
  0%, 100% { opacity: 0.65; }
  50% { opacity: 1; }
}

.error-panel {
  padding: var(--space-2xl);
  text-align: center;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-destructive-bg);
  color: var(--color-destructive-strong);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-sm);
}

.error-panel strong { font-size: var(--text-lg); }
.error-panel p { color: var(--color-destructive-strong); font-size: var(--text-sm); }
.error-panel .primary-btn { margin-top: var(--space-xs); }

/* ═══════════════════════════════════════════════════════════
   8. 确认弹窗
   ═══════════════════════════════════════════════════════════ */
.confirm-modal { width: min(440px, calc(100vw - 32px)); }

.modal-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: var(--space-md) var(--space-lg);
  border-bottom: 1px solid var(--color-border);
  color: var(--color-foreground);
}

.modal-body { padding: var(--space-md) var(--space-lg); }

.modal-body p {
  color: var(--color-foreground);
  font-size: var(--text-sm);
  line-height: 1.7;
}

.modal-body strong { color: var(--color-warning-ink); }

.modal-body .muted {
  color: var(--color-muted-foreground);
  font-size: var(--text-xs);
  display: block;
  margin-top: var(--space-sm);
}

.modal-foot {
  display: flex;
  gap: var(--space-sm);
  justify-content: flex-end;
  padding: var(--space-md) var(--space-lg);
  border-top: 1px solid var(--color-border);
}

/* ═══════════════════════════════════════════════════════════
   9. 响应式
   ═══════════════════════════════════════════════════════════ */
@media (max-width: 1180px) {
  .delivery-metrics { grid-template-columns: 1fr 1fr; }
  .skeleton-grid { grid-template-columns: 1fr 1fr; }
}

@media (max-width: 760px) {
  .delivery-metrics { grid-template-columns: 1fr; }
  .skeleton-grid { grid-template-columns: 1fr; }
  .segment-item { flex: 0 0 auto; }
  .delivery-panel-head { flex-direction: column; align-items: stretch; }
  .delivery-foot { flex-direction: column; align-items: stretch; }
  .delivery-foot-left { width: 100%; }
  .delivery-foot-left > * { flex: 1; justify-content: center; }
}
</style>
