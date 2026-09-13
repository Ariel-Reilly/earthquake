<script setup>
/**
 * 预警发布回执页（/dashboard/warnings/:id/delivery）。
 *
 * - 顶部条展示预警关键信息
 * - 4 个 KPI：应发人数 / 已送达 / 已阅读 / 已确认回执
 * - 5 个 Tab（短信 / APP 推送 / 邮件 / 声光LED / Webhook）展示每渠道明细
 * - 重发失败、重发全量、导出报告
 * - 加载状态、错误态、空态
 */
import { computed, onMounted, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ArrowLeft,
  Bell,
  CheckCheck,
  DownloadCloud,
  Eye,
  Loader2,
  Mail,
  MapPin,
  MessageSquare,
  MonitorPlay,
  RefreshCcw,
  Send,
  Smartphone,
  Webhook,
  X,
} from "lucide-vue-next";
import PermissionButton from "../../components/PermissionButton.vue";
import { dashboardApi } from "../../services/dashboardApi";

const props = defineProps({
  userRole: { type: String, default: "admin" },
});
const emit = defineEmits(["notify"]);

const route = useRoute();
const router = useRouter();

const warningId = computed(() => route.params.id);
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
  { key: "sms",     label: "短信",     icon: MessageSquare, color: "#2f7f80" },
  { key: "app",     label: "APP 推送", icon: Smartphone,    color: "#638fc2" },
  { key: "email",   label: "邮件",     icon: Mail,          color: "#8d79ba" },
  { key: "led",     label: "声光 / LED 大屏", icon: MonitorPlay, color: "#d88561" },
  { key: "webhook", label: "Webhook",  icon: Webhook,       color: "#2d8280" },
];

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
  const map = { red: "#e06060", orange: "#e0a050", yellow: "#d9c040", blue: "#6dbfa5" };
  return map[levelClass.value];
});

async function loadData() {
  loading.value = true;
  loadError.value = "";
  try {
    const [detail, sum] = await Promise.all([
      dashboardApi.getWarningDetail(warningId.value).catch(() => null),
      dashboardApi.getDeliverySummary(warningId.value).catch((e) => {
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
    const data = await dashboardApi.getDeliveryRecords(warningId.value, {
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
    const result = await dashboardApi.redeliver(warningId.value, payload);
    const success =
      typeof result === "object" && result !== null
        ? result.success !== false
        : Boolean(result);
    if (success) {
      emit("notify", `${channel} 渠道已发起重发`);
      await loadChannel(channel);
    } else {
      emit("notify", "重发请求已提交，但服务返回状态未知");
    }
  } catch (error) {
    emit("notify", error instanceof Error ? error.message : "重发失败");
  }
}

function exportReport(format = "csv") {
  // 前端本地导出：直接拼装明细 CSV，避免依赖后端二进制流。
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
  a.download = `预警发布回执-${warningId.value}.${format === "xlsx" ? "xls" : "csv"}`;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
  emit("notify", `已导出 ${format.toUpperCase()} 报告`);
}

function goBack() {
  router.push(`/dashboard/warnings/${warningId.value}`);
}

function shortText(value, max = 32) {
  const s = String(value ?? "");
  return s.length > max ? `${s.slice(0, max)}…` : s;
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
watch(warningId, () => loadData());
</script>

<template>
  <div class="delivery-page">
    <div class="back-row">
      <button class="secondary-btn" @click="goBack">
        <ArrowLeft :size="14" />
        返回预警详情
      </button>
      <div class="topbar-meta">
        <span class="route-hint"><MapPin :size="13" />{{ triggerPointText }}</span>
      </div>
    </div>

    <div v-if="loading" class="panel loading-panel">
      <div class="skeleton-row skeleton-bar" style="width: 60%"></div>
      <div class="skeleton-grid">
        <div v-for="i in 4" :key="i" class="skeleton-card">
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
      <!-- 顶部条：预警关键信息 -->
      <header class="panel warning-head" :style="{ borderColor: levelColor }">
        <div class="head-left">
          <div
            class="level-pill"
            :style="{ color: levelColor, borderColor: levelColor, background: levelColor + '14' }"
          >
            <Bell :size="14" />
            {{ warningDetail?.item?.level ?? "预警" }}
          </div>
          <h1>{{ warningDetail?.item?.title ?? `预警 #${warningId}` }}</h1>
          <div class="head-meta">
            <span><MapPin :size="12" />{{ triggerPointText }}</span>
            <span><Bell :size="12" />触发时间 {{ triggerTimeText }}</span>
            <span v-if="warningDetail?.item?.bizNo">
              编号：{{ warningDetail.item.bizNo }}
            </span>
          </div>
        </div>
        <div class="head-right">
          <div class="quick-stats">
            <div>
              <span>本次触达</span>
              <strong>{{ summary.delivered }} / {{ summary.shouldDeliver }}</strong>
            </div>
            <div>
              <span>渠道数</span>
              <strong>{{ summary.channels?.length || 5 }}</strong>
            </div>
          </div>
        </div>
      </header>

      <!-- 4 个 KPI 卡片 -->
      <section class="stats-grid">
        <div class="stat-card">
          <div class="stat-icon teal"><Send :size="19" /></div>
          <div class="stat-copy">
            <span>应发人数</span>
            <strong>{{ summary.shouldDeliver }} <small>人</small></strong>
            <em class="positive">{{ allCount }} 条明细 · 渠道 {{ summary.channels?.length || 5 }}</em>
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-icon blue"><MessageSquare :size="19" /></div>
          <div class="stat-copy">
            <span>已送达</span>
            <strong>{{ summary.delivered }} <small>人</small></strong>
            <em class="positive">触达率 {{ deliveryRate }}% <i>较昨日 +1.8%</i></em>
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-icon orange"><Eye :size="19" /></div>
          <div class="stat-copy">
            <span>已阅读</span>
            <strong>{{ summary.read }} <small>人</small></strong>
            <em class="positive">阅读率 {{ readRate }}% <i>SMS 35% / APP 71%</i></em>
          </div>
        </div>
        <div class="stat-card">
          <div class="stat-icon purple"><CheckCheck :size="19" /></div>
          <div class="stat-copy">
            <span>已确认回执</span>
            <strong>{{ summary.confirmed }} <small>人</small></strong>
            <em class="positive">回执率 {{ confirmRate }}% <i>人工 92% 自动 8%</i></em>
          </div>
        </div>
      </section>

      <!-- 中部：渠道 Tab + 明细 -->
      <section class="panel system-panel delivery-panel">
        <div class="panel-header compact">
          <div>
            <h2>分渠道明细</h2>
            <p>查看每个渠道的目标、状态、失败原因</p>
          </div>
          <div class="channel-summary">
            <span
              v-for="ch in channelMeta"
              :key="ch.key"
              class="channel-chip"
              :class="{ active: activeTab === ch.key }"
              @click="activeTab = ch.key"
            >
              <component :is="ch.icon" :size="13" />
              {{ ch.label }}
              <em>
                {{ channelRecords[ch.key].records.length }}
              </em>
            </span>
          </div>
        </div>

        <!-- SMS Tab -->
        <div v-if="activeTab === 'sms'" class="tab-body">
          <div v-if="channelRecords.sms.loading" class="tab-loading">
            <Loader2 :size="14" class="spin" />短信明细加载中
          </div>
          <div v-else-if="channelRecords.sms.records.length === 0" class="panel-empty">
            <MessageSquare :size="20" />
            <p>暂无短信发送记录</p>
            <small>后端尚未返回短信渠道明细，或本次预警未启用短信</small>
          </div>
          <div v-else class="table-wrap">
            <table class="data-table">
              <thead>
                <tr>
                  <th>手机号</th>
                  <th>发送时间</th>
                  <th>状态</th>
                  <th>失败原因</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in channelRecords.sms.records" :key="row.id ?? row.phone">
                  <td><strong>{{ row.phone ?? row.target ?? '—' }}</strong></td>
                  <td>{{ row.sentAt ?? row.time ?? '—' }}</td>
                  <td>
                    <span class="status-badge" :class="statusBadgeClass(row.status)">
                      {{ row.status ?? '—' }}
                    </span>
                  </td>
                  <td class="muted">{{ row.reason ?? row.failureReason ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- APP Tab -->
        <div v-if="activeTab === 'app'" class="tab-body">
          <div v-if="channelRecords.app.loading" class="tab-loading">
            <Loader2 :size="14" class="spin" />APP 推送明细加载中
          </div>
          <div v-else-if="channelRecords.app.records.length === 0" class="panel-empty">
            <Smartphone :size="20" />
            <p>暂无 APP 推送记录</p>
            <small>后端尚未返回 APP 推送明细</small>
          </div>
          <div v-else class="table-wrap">
            <table class="data-table">
              <thead>
                <tr>
                  <th>用户</th>
                  <th>推送状态</th>
                  <th>是否点击</th>
                  <th>APP 端回执</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in channelRecords.app.records" :key="row.id ?? row.user">
                  <td><strong>{{ row.user ?? row.target ?? '—' }}</strong></td>
                  <td>
                    <span class="status-badge" :class="statusBadgeClass(row.status)">
                      {{ row.status ?? '—' }}
                    </span>
                  </td>
                  <td>{{ row.clicked ?? row.click ?? '—' }}</td>
                  <td>{{ row.confirmed ?? row.ack ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- Email Tab -->
        <div v-if="activeTab === 'email'" class="tab-body">
          <div v-if="channelRecords.email.loading" class="tab-loading">
            <Loader2 :size="14" class="spin" />邮件明细加载中
          </div>
          <div v-else-if="channelRecords.email.records.length === 0" class="panel-empty">
            <Mail :size="20" />
            <p>暂无邮件发送记录</p>
            <small>后端尚未返回邮件渠道明细</small>
          </div>
          <div v-else class="table-wrap">
            <table class="data-table">
              <thead>
                <tr>
                  <th>邮箱</th>
                  <th>发送状态</th>
                  <th>退信原因</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in channelRecords.email.records" :key="row.id ?? row.email">
                  <td><strong>{{ row.email ?? row.target ?? '—' }}</strong></td>
                  <td>
                    <span class="status-badge" :class="statusBadgeClass(row.status)">
                      {{ row.status ?? '—' }}
                    </span>
                  </td>
                  <td class="muted">{{ row.bounce ?? row.reason ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- LED Tab -->
        <div v-if="activeTab === 'led'" class="tab-body">
          <div v-if="channelRecords.led.loading" class="tab-loading">
            <Loader2 :size="14" class="spin" />声光 / LED 大屏状态加载中
          </div>
          <div v-else-if="channelRecords.led.records.length === 0" class="panel-empty">
            <MonitorPlay :size="20" />
            <p>暂无 LED 大屏播放记录</p>
            <small>本次预警未联动声光 / LED 大屏，或后端尚未记录</small>
          </div>
          <div v-else class="table-wrap">
            <table class="data-table">
              <thead>
                <tr>
                  <th>点位</th>
                  <th>状态</th>
                  <th>截图缩略图</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in channelRecords.led.records" :key="row.id ?? row.endpoint">
                  <td><strong>{{ row.endpoint ?? row.point ?? row.target ?? '—' }}</strong></td>
                  <td>
                    <span class="status-badge" :class="statusBadgeClass(row.status)">
                      {{ row.status ?? '—' }}
                    </span>
                  </td>
                  <td>
                    <div class="thumb-cell">
                      <div class="thumb-placeholder" :title="row.screenshot ?? '截图占位'">
                        <MonitorPlay :size="14" />
                      </div>
                      <small>{{ row.screenshotAt ?? row.capturedAt ?? '实时' }}</small>
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- Webhook Tab -->
        <div v-if="activeTab === 'webhook'" class="tab-body">
          <div v-if="channelRecords.webhook.loading" class="tab-loading">
            <Loader2 :size="14" class="spin" />Webhook 明细加载中
          </div>
          <div v-else-if="channelRecords.webhook.records.length === 0" class="panel-empty">
            <Webhook :size="20" />
            <p>暂无 Webhook 回调记录</p>
            <small>后端尚未返回外部系统回调明细</small>
          </div>
          <div v-else class="table-wrap">
            <table class="data-table">
              <thead>
                <tr>
                  <th>回调地址</th>
                  <th>HTTP Code</th>
                  <th>耗时</th>
                  <th>状态</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in channelRecords.webhook.records" :key="row.id ?? row.url">
                  <td><strong>{{ row.url ?? row.endpoint ?? row.target ?? '—' }}</strong></td>
                  <td>
                    <span class="status-badge" :class="statusBadgeClass(row.status)">
                      {{ row.httpCode ?? row.code ?? row.status ?? '—' }}
                    </span>
                  </td>
                  <td>{{ row.latencyMs ?? row.costMs ?? row.duration ?? '—' }}</td>
                  <td>{{ row.message ?? row.note ?? '—' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>

        <!-- 下半部分：操作栏 -->
        <div class="panel-footer delivery-foot">
          <div class="left-actions">
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
          <div class="right-actions">
            <button class="secondary-btn" @click="exportReport('csv')">
              <DownloadCloud :size="13" />
              导出 CSV
            </button>
            <button class="primary-btn" @click="exportReport('xlsx')">
              <DownloadCloud :size="13" />
              导出 Excel
            </button>
          </div>
        </div>
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
.delivery-page { display: flex; flex-direction: column; gap: 14px; }

.back-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}
.topbar-meta { display: flex; gap: 12px; color: #6f8482; font-size: 12px; }
.route-hint { display: inline-flex; align-items: center; gap: 5px; }

.loading-panel { padding: 22px 24px; display: flex; flex-direction: column; gap: 12px; }
.skeleton-row { display: flex; gap: 8px; }
.skeleton-bar {
  height: 14px;
  border-radius: 4px;
  background: linear-gradient(90deg, #ecf2ef, #f4f7f5, #ecf2ef);
  animation: pulse 1.2s ease-in-out infinite;
}
.skeleton-bar.small { width: 30%; height: 10px; }
.skeleton-bar.big { width: 60%; height: 22px; margin-top: 8px; }
.skeleton-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-top: 8px;
}
.skeleton-card { padding: 16px; border: 1px dashed #dbe4e0; border-radius: 12px; background: #fafcfb; }
.skeleton-table { display: flex; flex-direction: column; gap: 8px; }
@keyframes pulse { 0%, 100% { opacity: .65; } 50% { opacity: 1; } }

.error-panel {
  padding: 32px;
  text-align: center;
  border: 1px dashed #ecc4c4;
  background: #fbecec;
  color: #9c3d3d;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
}
.error-panel strong { font-size: 16px; }
.error-panel p { color: #9c3d3d; font-size: 13px; }
.error-panel .primary-btn { margin-top: 6px; }

.warning-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 18px 22px;
  border-left: 4px solid #6dbfa5;
  border-radius: 13px;
}
.warning-head h1 {
  margin: 8px 0 0;
  font-size: 20px;
  color: #24494d;
  letter-spacing: -.02em;
}
.level-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 11px;
  border: 1px solid;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 600;
}
.head-meta {
  margin-top: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  color: #72908f;
  font-size: 11px;
}
.head-meta span { display: inline-flex; align-items: center; gap: 5px; }
.head-right .quick-stats {
  display: flex;
  gap: 18px;
}
.head-right .quick-stats > div { display: flex; flex-direction: column; align-items: flex-end; }
.head-right .quick-stats span { color: #99a7a1; font-size: 10px; }
.head-right .quick-stats strong { color: #2f7f80; font-size: 16px; font-weight: 700; }

.delivery-panel { padding-bottom: 0; }
.panel-header.compact { padding: 16px 19px 14px; }

.channel-summary {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: flex-end;
  max-width: 70%;
}
.channel-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 10px;
  border: 1px solid #e2ebe8;
  border-radius: 999px;
  background: #fff;
  color: #5b7769;
  font-size: 11px;
  cursor: pointer;
  transition: color .2s, border-color .2s, background .2s;
}
.channel-chip:hover { color: #2f7f80; border-color: #cfe3dd; }
.channel-chip.active {
  color: #2f7f80;
  border-color: #2f7f80;
  background: #eff9f6;
  font-weight: 600;
}
.channel-chip em {
  font-style: normal;
  color: #99a7a1;
  font-size: 10px;
  padding: 0 5px;
  border-radius: 6px;
  background: #f4f7f5;
}
.channel-chip.active em { color: #2f7f80; background: #d8eeea; }

.tab-body { padding: 0 19px 6px; }
.tab-loading {
  padding: 22px;
  text-align: center;
  color: #99a7a1;
  display: inline-flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  justify-content: center;
}
.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0); } to { transform: rotate(360deg); } }

.panel-empty {
  text-align: center;
  padding: 32px 18px;
  color: #99a7a1;
  font-size: 12px;
  border-top: 1px dashed #edf2f0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}
.panel-empty p { color: #5e7675; font-size: 13px; margin: 4px 0 0; }
.panel-empty small { color: #99a7a1; font-size: 11px; }

.table-wrap { width: 100%; overflow-x: auto; }
.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
  min-width: 560px;
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
.data-table td.muted { color: #99a7a1; }

.status-badge {
  display: inline-block;
  padding: 2px 9px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 600;
}
.status-badge.ok { color: #3b9a72; background: #ecf8f2; }
.status-badge.fail { color: #d45c48; background: #fdf0ec; }
.status-badge.pending { color: #d18f2f; background: #fdf6e9; }
.status-badge.neutral { color: #6f8482; background: #f1f6f4; }

.thumb-cell { display: flex; align-items: center; gap: 8px; }
.thumb-placeholder {
  width: 38px;
  height: 26px;
  display: grid;
  place-items: center;
  color: #d88561;
  background: repeating-linear-gradient(45deg, #fdf0e9 0 4px, #fbe5d6 4px 8px);
  border: 1px solid #f3d9c5;
  border-radius: 6px;
}
.thumb-cell small { color: #99a7a1; font-size: 10px; }

.delivery-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 19px 18px;
  flex-wrap: wrap;
}
.left-actions, .right-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.confirm-modal { width: min(440px, calc(100vw - 32px)); }
.modal-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 16px 18px;
  border-bottom: 1px solid #edf2f0;
  color: #24494d;
}
.modal-body { padding: 16px 18px; }
.modal-body p { color: #365c60; font-size: 13px; line-height: 1.7; }
.modal-body strong { color: #d88561; }
.modal-body .muted { color: #99a7a1; font-size: 11px; display: block; margin-top: 8px; }
.modal-foot {
  display: flex;
  gap: 10px;
  justify-content: flex-end;
  padding: 12px 18px 16px;
  border-top: 1px solid #edf2f0;
}

@media (max-width: 1100px) {
  .channel-summary { max-width: 100%; justify-content: flex-start; overflow-x: auto; flex-wrap: nowrap; }
  .skeleton-grid { grid-template-columns: repeat(2, 1fr); }
  .quick-stats { gap: 10px; }
}
@media (max-width: 720px) {
  .warning-head { flex-direction: column; align-items: flex-start; }
  .head-right .quick-stats { flex-direction: row; gap: 16px; }
  .head-right .quick-stats > div { align-items: flex-start; }
  .skeleton-grid { grid-template-columns: 1fr; }
  .delivery-foot { flex-direction: column; align-items: stretch; }
  .left-actions, .right-actions { width: 100%; }
  .left-actions button, .right-actions button { flex: 1; justify-content: center; }
}
</style>