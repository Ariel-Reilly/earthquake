<script setup>
/**
 * 事件详情侧栏（EventDetailDrawer.vue）。
 *
 * 内容：
 *   1. Header：eventCode + 当前状态徽章 + 关闭按钮
 *   2. Section 1：元信息（来源/创建时间/已停留时长/最后触发器）
 *   3. Section 2：关联实体（hazard/warning/emergency 三 Tab）
 *   4. Section 3：血缘图（事件 → 灾害点 → 预警 → 应急/任务，可点击跳转）
 *   5. Section 4：操作面板（按当前状态动态显示可用 triggers）
 *   6. Footer：终态时显示关闭/驳回原因与关闭人
 */
import { computed, ref } from "vue";
import {
  AlertTriangle,
  ArrowRight,
  Bell,
  CheckCircle2,
  ChevronRight,
  ClipboardCheck,
  Clock,
  FileText,
  GitBranch,
  Loader2,
  MapPin,
  ShieldCheck,
  Truck,
  User,
  X,
  XCircle,
} from "lucide-vue-next";
import { useRouter } from "vue-router";
import { dashboardApi } from "../../services/dashboardApi";
import { useEscapeClose } from "../../composables/useEscapeClose";

const props = defineProps({
  event: { type: Object, default: null },
  visible: { type: Boolean, default: false },
});
const emit = defineEmits(["close", "transitioned", "notify"]);

const router = useRouter();

const activeTab = ref("hazard"); // hazard | warning | emergency
const submitting = ref(false);
const reasonDialog = ref({ open: false, trigger: null, reason: "", error: "" });

// ESC 键关闭抽屉
useEscapeClose(() => emit('close'), () => props.visible)

const STATE_META = {
  DETECTED:         { label: "已发现",      color: "#6f9e7f", icon: GitBranch,  description: "AI 自动识别或人工上报，待人工复核" },
  CONFIRMING:       { label: "确认中",      color: "#cbaa76", icon: ClipboardCheck, description: "已认领，等待现场复核并签认" },
  CONFIRMED:        { label: "已确认",      color: "#638fc2", icon: CheckCircle2, description: "签认通过，准备发起预警或应急响应" },
  WARNING_ISSUED:   { label: "预警发布",    color: "#d88561", icon: Bell, description: "预警已发布，进入公众通知阶段" },
  EMERGENCY_ACTIVE: { label: "应急处置",    color: "#e06060", icon: Truck, description: "应急事件进行中，联动救援资源" },
  CLOSED:           { label: "已关闭",      color: "#9aab9f", icon: ShieldCheck, description: "处置完成 / 自动解除 / 误报归档" },
  REJECTED:         { label: "已驳回",      color: "#76847a", icon: XCircle, description: "复核不予立项" },
};

const SOURCE_META = {
  AI_AUTO:     { label: "AI 自动识别",     color: "#638fc2" },
  RULE_ENGINE: { label: "规则引擎触发",   color: "#9b761e" },
  MANUAL:      { label: "人工现场上报",   color: "#4a7a5e" },
};

const stateMeta = computed(() => STATE_META[props.event?.state] ?? null);
const sourceMeta = computed(() => SOURCE_META[props.event?.source ?? "AI_AUTO"]?.label ?? "AI 自动识别");
const sourceColor = computed(() => SOURCE_META[props.event?.source]?.color ?? "#638fc2");

const dwellMinutes = computed(() => {
  if (!props.event?.lastTransitionAt) return "—";
  const ms = Date.now() - new Date(props.event.lastTransitionAt).getTime();
  if (ms < 60_000) return "刚刚";
  if (ms < 3_600_000) return `${Math.floor(ms / 60_000)} 分钟`;
  if (ms < 86_400_000) return `${Math.floor(ms / 3_600_000)} 小时`;
  return `${Math.floor(ms / 86_400_000)} 天`;
});

const formatTime = (iso) => {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("zh-CN", { hour12: false });
};

const availableTriggers = computed(() => {
  if (!props.event?.state) return [];
  return dashboardApi.computeAvailableTriggers(props.event.state);
});

const requiresReason = (trigger) => ["REJECT", "DISMISS", "CLOSE"].includes(trigger);

function openReasonDialog(trigger) {
  if (requiresReason(trigger.trigger)) {
    reasonDialog.value = { open: true, trigger: trigger.trigger, reason: "", error: "" };
  } else {
    doTransition(trigger.trigger, "");
  }
}

function closeReasonDialog() {
  if (submitting.value) return;
  reasonDialog.value = { open: false, trigger: null, reason: "", error: "" };
}

async function confirmReasonDialog() {
  const reason = reasonDialog.value.reason.trim();
  if (!reason) {
    reasonDialog.value.error = "请填写理由";
    return;
  }
  await doTransition(reasonDialog.value.trigger, reason);
  closeReasonDialog();
}

async function doTransition(trigger, reason) {
  if (!props.event) return;
  submitting.value = true;
  try {
    const operator = JSON.parse(localStorage.getItem("disasterguard_user") ?? "{}")?.name ?? "system";
    await dashboardApi.transitionUnifiedEvent(props.event.eventCode, {
      trigger,
      operator,
      reason: reason || `${trigger} ${formatTime(new Date().toISOString())}`,
    });
    emit("notify", `事件已流转：${trigger}`);
    emit("transitioned", { code: props.event.eventCode, trigger });
  } catch (e) {
    emit("notify", e?.message ?? "状态流转失败");
  } finally {
    submitting.value = false;
  }
}

function navigateTo(path) {
  router.push(path);
}

/**
 * 血缘节点（用于 Section 3 血缘图）：
 *   event → hazard → warning → emergency → tasks
 *
 * v1.7.0+ P3-4 修复：hazard 节点的 route 改用 event.hazard.id（后端已 join 返回），
 * 而不是 event.id（unified_event 主键，会导致 404）。其它节点若缺 ID 则禁用跳转。
 */
const lineageNodes = computed(() => {
  if (!props.event) return [];
  const hazardId = props.event.hazard?.id;
  const nodes = [
    { id: "event",    label: "统一事件",  code: props.event.eventCode, color: "#6f9e7f", icon: GitBranch, clickable: false },
    {
      id: "hazard",
      label: "灾害点",
      code: props.event.hazardCode,
      color: "#c7553b",
      icon: AlertTriangle,
      clickable: !!(hazardId || props.event.hazardCode),
      route: hazardId
        ? `/dashboard/hazards/${hazardId}`
        : (props.event.hazardCode ? `/dashboard/hazards?code=${encodeURIComponent(props.event.hazardCode)}` : null),
    },
    {
      id: "warning",
      label: "预警",
      code: props.event.warningCode,
      color: "#d88561",
      icon: Bell,
      clickable: !!props.event.warningCode,
      route: props.event.warningCode ? `/dashboard/warnings/${props.event.warningCode}` : null,
    },
    {
      id: "emergency",
      label: "应急事件",
      code: props.event.emergencyCode,
      color: "#e06060",
      icon: Truck,
      clickable: !!props.event.emergencyCode,
      route: props.event.emergencyCode ? `/dashboard/emergency` : null,
    },
  ];
  return nodes;
});

function lineageEdge(from, to) {
  return from && to;
}

const onTabClick = (tab) => {
  activeTab.value = tab;
};
</script>

<template>
  <transition name="drawer">
    <div v-if="visible && event" class="event-drawer-backdrop" @click.self="$emit('close')">
      <aside class="event-drawer" role="complementary">
        <!-- Header -->
        <header class="ed-header" :style="{ background: stateMeta?.color ?? '#6f9e7f' }">
          <div class="ed-header-meta">
            <span class="eyebrow">UNIFIED EVENT</span>
            <h2>
              <component :is="stateMeta?.icon ?? GitBranch" :size="22" />
              {{ event.title }}
            </h2>
            <div class="ed-event-code">
              <code>{{ event.eventCode }}</code>
              <span class="state-pill" :style="{ background: 'rgba(255,255,255,.18)', color: '#fff' }">
                {{ stateMeta?.label }}
              </span>
              <span class="source-tag" :style="{ color: sourceColor }">
                <span class="dot" :style="{ background: sourceColor }"></span>
                {{ sourceMeta }}
              </span>
            </div>
          </div>
          <button class="ed-close" @click="$emit('close')" aria-label="关闭"><X :size="18" /></button>
        </header>

        <div class="ed-body">
          <!-- Section 1: 元信息 -->
          <section class="ed-section">
            <h4 class="ed-section-title">
              <Clock :size="14" /> 当前状态
            </h4>
            <div class="meta-grid">
              <div class="meta-cell">
                <span class="k">当前状态</span>
                <strong class="v" :style="{ color: stateMeta?.color }">{{ stateMeta?.label }}</strong>
              </div>
              <div class="meta-cell">
                <span class="k">停留时长</span>
                <strong class="v">{{ dwellMinutes }}</strong>
              </div>
              <div class="meta-cell">
                <span class="k">最近触发器</span>
                <strong class="v code">{{ event.lastTrigger ?? "—" }}</strong>
              </div>
              <div class="meta-cell">
                <span class="k">最近跳转</span>
                <strong class="v">{{ formatTime(event.lastTransitionAt) }}</strong>
              </div>
              <div class="meta-cell">
                <span class="k">创建时间</span>
                <strong class="v">{{ formatTime(event.createdAt) }}</strong>
              </div>
              <div class="meta-cell">
                <span class="k">来源</span>
                <strong class="v" :style="{ color: sourceColor }">{{ sourceMeta }}</strong>
              </div>
            </div>
            <p class="state-desc">{{ stateMeta?.description }}</p>
          </section>

          <!-- Section 2: 关联实体 Tabs -->
          <section class="ed-section">
            <h4 class="ed-section-title">
              <FileText :size="14" /> 关联实体
            </h4>
            <div class="tabs">
              <button :class="{ active: activeTab === 'hazard' }" @click="onTabClick('hazard')">
                灾害点
                <small v-if="event.hazardCode">{{ event.hazardCode }}</small>
              </button>
              <button :class="{ active: activeTab === 'warning' }" @click="onTabClick('warning')" :disabled="!event.warningCode">
                预警
                <small v-if="event.warningCode">{{ event.warningCode }}</small>
                <small v-else class="muted">未关联</small>
              </button>
              <button :class="{ active: activeTab === 'emergency' }" @click="onTabClick('emergency')" :disabled="!event.emergencyCode">
                应急
                <small v-if="event.emergencyCode">{{ event.emergencyCode }}</small>
                <small v-else class="muted">未联动</small>
              </button>
            </div>

            <div v-if="activeTab === 'hazard'" class="tab-content">
              <template v-if="event.hazard">
                <div class="kv-row"><span class="k">名称</span><strong>{{ event.hazard.name }}</strong></div>
                <div class="kv-row"><span class="k">类型</span><strong>{{ event.hazard.type }}</strong></div>
                <div class="kv-row"><span class="k">等级</span><strong :style="{ color: '#c7553b' }">{{ event.hazard.level }}</strong></div>
                <div class="kv-row"><span class="k">坐标</span><strong>{{ event.hazard.lon?.toFixed(4) ?? "—" }}, {{ event.hazard.lat?.toFixed(4) ?? "—" }}</strong></div>
                <button
                  v-if="event.hazard.id"
                  class="link-btn"
                  @click="navigateTo(`/dashboard/hazards/${event.hazard.id}`)"
                >
                  查看完整灾害点 <ChevronRight :size="14" />
                </button>
              </template>
              <div v-else class="empty">未关联灾害点</div>
            </div>

            <div v-if="activeTab === 'warning'" class="tab-content">
              <template v-if="event.warning">
                <div class="kv-row"><span class="k">标题</span><strong>{{ event.warning.title }}</strong></div>
                <div class="kv-row"><span class="k">等级</span><strong :style="{ color: '#d88561' }">{{ event.warning.level }}</strong></div>
                <div class="kv-row"><span class="k">发布时间</span><strong>{{ formatTime(event.warning.issuedAt) }}</strong></div>
                <button class="link-btn" @click="navigateTo(`/dashboard/warnings/${event.warningCode}`)">
                  查看预警详情 <ChevronRight :size="14" />
                </button>
              </template>
              <div v-else class="empty">当前事件未发布预警</div>
            </div>

            <div v-if="activeTab === 'emergency'" class="tab-content">
              <template v-if="event.emergency">
                <div class="kv-row"><span class="k">标题</span><strong>{{ event.emergency.title }}</strong></div>
                <div class="kv-row"><span class="k">状态</span><strong :style="{ color: '#e06060' }">{{ event.emergency.status }}</strong></div>
                <div class="kv-row"><span class="k">救援人数</span><strong>{{ event.emergency.responders ?? "—" }}</strong></div>
                <button class="link-btn" @click="navigateTo('/dashboard/emergency')">
                  跳到应急工作台 <ChevronRight :size="14" />
                </button>
              </template>
              <div v-else class="empty">当前事件未联动应急</div>
            </div>
          </section>

          <!-- Section 3: 血缘图 -->
          <section class="ed-section">
            <h4 class="ed-section-title">
              <GitBranch :size="14" /> 事件血缘
            </h4>
            <!-- eslint-disable-next-line vue/no-v-for-template-key -->
            <!-- Vue 3 官方要求在 <template v-for> 上放置 :key，与该旧版规则冲突 -->
            <div class="lineage">
              <template v-for="(node, idx) in lineageNodes" :key="node.id">
                <div
                  class="lineage-node"
                  :class="{ clickable: node.clickable, dim: !node.code }"
                  :style="{ borderColor: node.color, background: node.clickable ? '#ffffff' : '#f3f6f9' }"
                  @click="node.clickable && navigateTo(node.route)"
                >
                  <span class="lineage-icon" :style="{ color: node.color }">
                    <component :is="node.icon" :size="14" />
                  </span>
                  <span class="lineage-label">{{ node.label }}</span>
                  <span class="lineage-code">{{ node.code ?? "—" }}</span>
                </div>
                <ArrowRight v-if="idx < lineageNodes.length - 1 && lineageEdge(lineageNodes[idx].code, lineageNodes[idx + 1].code)" :key="`arrow-${node.id}`" :size="16" class="lineage-arrow" />
              </template>
            </div>
            <p class="hint">点击实线节点可跳转到对应业务模块的详情页。</p>
          </section>

          <!-- Section 4: 终态原因 -->
          <section v-if="event.closeReason" class="ed-section close-section">
            <h4 class="ed-section-title">
              <XCircle :size="14" /> 关闭 / 驳回原因
            </h4>
            <p class="close-reason">{{ event.closeReason }}</p>
            <div class="kv-row"><span class="k">操作人</span><strong>{{ event.closedBy ?? "—" }}</strong></div>
          </section>
        </div>

        <!-- Footer: 操作面板 -->
        <footer class="ed-footer">
          <div class="ed-footer-title">可执行操作（按当前状态动态显示）</div>
          <div class="ed-actions">
            <template v-if="availableTriggers.length === 0">
              <span class="muted">当前状态无可执行流转</span>
            </template>
            <button
              v-for="t in availableTriggers"
              :key="t.trigger"
              class="action-btn"
              :class="`tone-${t.tone}`"
              :disabled="submitting"
              @click="openReasonDialog(t)"
            >
              <Loader2 v-if="submitting" :size="14" class="spin" />
              {{ t.label }}
            </button>
          </div>
        </footer>

        <!-- Reason 弹窗 -->
        <transition name="modal">
          <div v-if="reasonDialog.open" class="reason-backdrop" @click.self="closeReasonDialog">
            <div class="reason-modal">
              <header>
                <h3>{{ availableTriggers.find((x) => x.trigger === reasonDialog.trigger)?.label }}</h3>
                <button @click="closeReasonDialog"><X :size="16" /></button>
              </header>
              <div class="reason-body">
                <label>原因 / 备注（必填）</label>
                <textarea v-model="reasonDialog.reason" rows="4" placeholder="请填写此次操作的理由（将进入审计日志）" />
                <p v-if="reasonDialog.error" class="error">{{ reasonDialog.error }}</p>
              </div>
              <footer>
                <button class="ghost" @click="closeReasonDialog">取消</button>
                <button class="primary" :disabled="submitting" @click="confirmReasonDialog">
                  <Loader2 v-if="submitting" :size="14" class="spin" />
                  确认
                </button>
              </footer>
            </div>
          </div>
        </transition>
      </aside>
    </div>
  </transition>
</template>

<style scoped>
.event-drawer-backdrop {
  position: fixed;
  inset: 0;
  background: var(--c-overlay)
    /* = rgba 15,23,42/.35 */;
  backdrop-filter: blur(2px);
  z-index: 200;
  display: flex;
  justify-content: flex-end;
}
.event-drawer {
  width: 580px;
  max-width: 92vw;
  height: 100vh;
  background: var(--c-surface);
  display: flex;
  flex-direction: column;
  box-shadow: -16px 0 40px rgba(46, 67, 93, 0.18);
}
.ed-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 20px 22px 18px;
  color: var(--c-on-brand);
  position: relative;
}
.ed-header-meta { flex: 1; min-width: 0; }
.ed-header .eyebrow {
  font-size: 10.5px;
  letter-spacing: 1.5px;
  opacity: 0.85;
  font-weight: 600;
}
.ed-header h2 {
  margin: 4px 0 8px;
  font-size: 18px;
  font-weight: 700;
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--c-on-brand);
  line-height: 1.3;
}
.ed-event-code {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.ed-event-code code {
  background: rgba(255, 255, 255, 0.18);
  padding: 3px 8px;
  border-radius: var(--r-xs);
  font-size: 11px;
  font-family: ui-monospace, monospace;
  letter-spacing: 0.5px;
}
.state-pill {
  padding: 3px 8px;
  border-radius: var(--r-pill);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.5px;
}
.source-tag {
  font-size: 11px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--c-on-brand);
  background: rgba(255, 255, 255, 0.18);
  padding: 3px 8px;
  border-radius: var(--r-pill);
}
.source-tag .dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}
.ed-close {
  background: rgba(255, 255, 255, 0.15);
  border: none;
  width: 32px;
  height: 32px;
  border-radius: var(--r-sm);
  color: var(--c-on-brand);
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.ed-close:hover { background: rgba(255, 255, 255, 0.28); }

.ed-body {
  flex: 1;
  overflow-y: auto;
  padding: 18px 22px;
}
.ed-section {
  margin-bottom: 22px;
  padding-bottom: 16px;
  border-bottom: 1px dashed var(--c-border);
}
.ed-section:last-child { border-bottom: none; padding-bottom: 0; }
.ed-section-title {
  font-size: 12px;
  font-weight: 700;
  color: var(--c-ink-1);
  margin: 0 0 10px;
  display: flex;
  align-items: center;
  gap: 6px;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}
.meta-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}
.meta-cell {
  background: color-mix(in srgb, var(--c-surface-2) 70%, transparent);
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  padding: 8px 10px;
}
.meta-cell .k {
  display: block;
  font-size: 10.5px;
  color: var(--c-text-2);
  margin-bottom: 2px;
}
.meta-cell .v {
  font-size: 13px;
  color: var(--c-ink-1);
}
.meta-cell .v.code {
  font-family: ui-monospace, monospace;
  font-size: 12px;
  color: var(--c-brand);
}
.state-desc {
  margin: 10px 0 0;
  font-size: 12px;
  color: var(--c-ink-2);
  background: rgba(79, 143, 153, 0.06);
  padding: 8px 10px;
  border-radius: var(--r-sm);
  border-left: 3px solid rgba(79, 143, 153, 0.4);
}

.tabs {
  display: flex;
  gap: 4px;
  border-bottom: 1px solid var(--c-border);
  margin-bottom: 12px;
}
.tabs button {
  background: transparent;
  border: none;
  padding: 8px 14px;
  font-size: 13px;
  color: var(--c-ink-2);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: all 0.15s;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.tabs button:hover:not(:disabled) { color: var(--c-brand); }
.tabs button.active {
  color: var(--c-brand);
  border-bottom-color: var(--c-brand);
  font-weight: 600;
}
.tabs button:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.tabs small {
  font-size: 10.5px;
  color: var(--c-text-3);
  font-family: ui-monospace, monospace;
}
.tabs small.muted { color: var(--c-border); }
.tabs button.active small { color: var(--sys-blue); }

.tab-content { padding: 4px 0; }
.kv-row {
  display: flex;
  justify-content: space-between;
  padding: 6px 0;
  border-bottom: 1px dotted var(--c-border);
  font-size: 13px;
}
.kv-row .k { color: var(--c-text-2); }
.kv-row strong { color: var(--c-ink-1); }
.link-btn {
  margin-top: 12px;
  background: transparent;
  border: 1px solid color-mix(in srgb, var(--c-brand) 40%, transparent)
    /* = rgba 111,158,127/.4 */;
  color: var(--c-brand);
  padding: 6px 12px;
  border-radius: var(--r-sm);
  cursor: pointer;
  font-size: 12px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  transition: all 0.15s;
}
.link-btn:hover { background: color-mix(in srgb, var(--c-brand) 8%, transparent)
    /* = rgba 111,158,127/.08 */; }
.empty {
  padding: 18px 0;
  text-align: center;
  color: var(--c-text-3);
  font-size: 12px;
}

.lineage {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  padding: 4px 0;
}
.lineage-node {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 1px;
  padding: 6px 10px;
  border: 1.5px solid;
  border-radius: var(--r-sm);
  min-width: 92px;
  text-align: center;
  transition: all 0.15s;
}
.lineage-node.clickable {
  cursor: pointer;
}
.lineage-node.clickable:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 8px rgba(46, 67, 93, 0.1);
}
.lineage-node.dim { opacity: 0.45; }
.lineage-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
}
.lineage-label {
  font-size: 11px;
  font-weight: 700;
  color: var(--c-ink-1);
}
.lineage-code {
  font-size: 9.5px;
  font-family: ui-monospace, monospace;
  color: var(--c-text-2);
}
.lineage-arrow {
  color: var(--c-border);
  flex-shrink: 0;
}
.hint {
  font-size: 11px;
  color: var(--c-text-3);
  margin: 6px 0 0;
  font-style: italic;
}

.close-section {
  background: var(--c-danger-bg);
  border-left: 3px solid var(--c-danger);
  padding-left: 10px;
  border-radius: var(--r-xs);
}
.close-reason {
  font-size: 13px;
  color: var(--c-ink-1);
  margin: 0 0 10px;
  line-height: 1.5;
  background: rgba(255, 255, 255, 0.7);
  padding: 8px 10px;
  border-radius: var(--r-xs);
}

.ed-footer {
  border-top: 1px solid var(--c-border);
  padding: 14px 22px 16px;
  background: color-mix(in srgb, var(--c-surface-2) 60%, transparent);
}
.ed-footer-title {
  font-size: 11px;
  color: var(--c-text-2);
  margin-bottom: 8px;
  font-weight: 600;
}
.ed-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.action-btn {
  border: 1px solid;
  padding: 6px 14px;
  border-radius: var(--r-sm);
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
  transition: all 0.15s;
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.action-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.action-btn.tone-green { background: var(--c-brand-mist); border-color: var(--c-brand-hover); color: var(--c-brand-deep); }
.action-btn.tone-green:hover:not(:disabled) { background: var(--c-brand-hover); color: var(--c-on-brand); }
.action-btn.tone-red { background: var(--c-danger-bg); border-color: var(--c-danger); color: var(--c-danger-strong); }
.action-btn.tone-red:hover:not(:disabled) { background: var(--c-danger); color: var(--c-on-brand); }
.action-btn.tone-orange { background: rgba(224, 160, 80, 0.1); border-color: var(--c-warning); color: var(--c-warning-ink); }
.action-btn.tone-orange:hover:not(:disabled) { background: var(--c-warning); color: var(--c-on-brand); }
.action-btn.tone-blue { background: rgba(99, 143, 194, 0.1); border-color: var(--sys-blue); color: var(--c-brand); }
.action-btn.tone-blue:hover:not(:disabled) { background: var(--sys-blue); color: var(--c-on-brand); }
.action-btn.tone-gray { background: var(--c-brand-mist); border-color: var(--c-border); color: var(--c-ink-2); }
.action-btn.tone-gray:hover:not(:disabled) { background: var(--c-border); color: var(--c-ink-1); }
.muted { color: var(--c-text-3); font-size: 12px; font-style: italic; }

.reason-backdrop {
  position: fixed;
  inset: 0;
  background: var(--c-overlay)
    /* = rgba 15,23,42/.45 */;
  z-index: 300;
  display: flex;
  align-items: center;
  justify-content: center;
}
.reason-modal {
  background: var(--c-surface);
  width: 420px;
  border-radius: var(--r-md);
  overflow: hidden;
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.25);
}
.reason-modal header {
  padding: 14px 18px;
  border-bottom: 1px solid var(--c-brand-soft);
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.reason-modal header h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 700;
}
.reason-modal header button {
  background: transparent;
  border: none;
  cursor: pointer;
  color: var(--c-text-2);
}
.reason-body {
  padding: 16px 18px;
}
.reason-body label {
  display: block;
  font-size: 12px;
  color: var(--c-ink-2);
  margin-bottom: 6px;
}
.reason-body textarea {
  width: 100%;
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  padding: 8px 10px;
  font-family: inherit;
  font-size: 13px;
  resize: vertical;
}
.reason-body textarea:focus {
  outline: none;
  border-color: var(--c-brand);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--c-brand) 10%, transparent)
    /* = rgba 111,158,127/.1 */;
}
.reason-body .error {
  margin: 6px 0 0;
  color: var(--c-danger-strong);
  font-size: 12px;
}
.reason-modal footer {
  padding: 12px 18px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  border-top: 1px solid var(--c-brand-soft);
  background: color-mix(in srgb, var(--c-surface-2) 60%, transparent);
}
.reason-modal footer button {
  border: 1px solid;
  padding: 6px 14px;
  border-radius: var(--r-sm);
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
}
.reason-modal footer button.ghost {
  background: transparent;
  border-color: var(--c-border);
  color: var(--c-ink-2);
}
.reason-modal footer button.ghost:hover { background: rgba(203, 213, 224, 0.2); }
.reason-modal footer button.primary {
  background: var(--c-brand);
  border-color: var(--c-brand);
  color: var(--c-on-brand);
  display: inline-flex;
  align-items: center;
  gap: 4px;
}
.reason-modal footer button.primary:hover:not(:disabled) { background: var(--sys-blue-deep); }
.reason-modal footer button.primary:disabled { opacity: 0.6; cursor: not-allowed; }

.spin { animation: spin 1s linear infinite; }
@keyframes spin { from { transform: rotate(0); } to { transform: rotate(360deg); } }

.drawer-enter-active, .drawer-leave-active {
  transition: opacity 0.18s ease;
}
.drawer-enter-active .event-drawer, .drawer-leave-active .event-drawer {
  transition: transform 0.24s cubic-bezier(0.22, 0.61, 0.36, 1);
}
.drawer-enter-from, .drawer-leave-to { opacity: 0; }
.drawer-enter-from .event-drawer, .drawer-leave-to .event-drawer { transform: translateX(100%); }

.modal-enter-active, .modal-leave-active { transition: opacity 0.16s ease; }
.modal-enter-from, .modal-leave-to { opacity: 0; }
</style>
