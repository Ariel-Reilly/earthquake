<script setup>
import { computed, onMounted, onBeforeUnmount, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ArrowLeft,
  Bell,
  FileText,
  GitBranch,
  History,
  Layers,
  MapPin,
  Megaphone,
  MessageSquare,
  PencilLine,
  Save,
  ShieldCheck,
  Paperclip,
  Bot,
  AlertCircle,
  CheckCircle2,
  Send,
  Truck,
  User,
  XCircle,
  Zap,
} from "lucide-vue-next";
import PermissionButton from "../../components/PermissionButton.vue";
import WarningDeliveryTab from "../../components/WarningDeliveryTab.vue";
import { dashboardApi } from "../../services/dashboardApi";
import { aiApi } from "../../services/aiApi";
import { useAiContext } from "../../composables/useAiContext";
import { useEscapeClose } from "../../composables/useEscapeClose";

const emit = defineEmits(["notify"]);

const route = useRoute();
const router = useRouter();
const warning = ref(null);
const loading = ref(true);
const notFound = ref(false);
const editMode = ref(false);
const form = ref({});
const aiRisk = ref(null);
const aiRiskLoading = ref(false);
const activeTab = ref("overview");
const deliveryStats = ref({ delivered: 0, shouldDeliver: 0 });
const isResolved = ref(false);

const { setAiContext, clearAiContext } = useAiContext();

// 计算派发确认的警告状态（送达率未达 100% 时显示红点）
const deliveryWarning = computed(() => {
  if (deliveryStats.value.shouldDeliver === 0) return false;
  const rate = (deliveryStats.value.delivered / deliveryStats.value.shouldDeliver) * 100;
  return rate < 100;
});

/**
 * 处置时间轴的节点类型定义。
 *
 * tone 决定圆点的语义色，与 tokens.css 的语义色板一一对应；
 * icon 则提供颜色之外的第二重识别通道 —— 12px 的圆点仅靠色相区分
 * 「发布(深绿)」与「派发(亮绿)」并不可靠，形状能兜底。
 */
const TIMELINE_NODE_META = {
  publish: { icon: Megaphone, tone: "primary" },
  approve: { icon: CheckCircle2, tone: "info" },
  dispatch: { icon: Truck, tone: "success" },
  feedback: { icon: MessageSquare, tone: "neutral" },
  close: { icon: XCircle, tone: "destructive" },
};

/**
 * 把预警的状态流转拍平成时间轴节点。
 *
 * 收敛到 computed 后，模板只负责渲染，条件判断集中在一处；
 * 同时天然产出「空列表」状态，模板据此落到空状态而不是留白。
 *
 * @returns {Array<{key: string, title: string, time: string, desc: string, operator?: string, tag?: object, icon: object, tone: string}>}
 */
const timelineItems = computed(() => {
  const w = warning.value;
  if (!w) return [];

  const history = w.history || [];
  // 预警没有发布时间、没有历史反馈、状态还停在待确认 —— 说明确实什么都没发生过
  if (!w.time && history.length === 0 && w.status === "待确认") return [];

  const items = [
    {
      key: "publish",
      type: "publish",
      title: "预警发布",
      time: w.time,
      desc: w.title,
      operator: `发布者: ${w.publisher || "系统"}`,
    },
  ];

  if (w.status !== "待确认") {
    items.push({
      key: "approve",
      type: "approve",
      title: "预警已确认",
      time: w.confirmedAt || "已确认",
      desc: "用户已确认该预警",
      operator: `确认人: ${w.confirmedBy || "未知"}`,
    });
  }

  if (["已派发", "处理中", "已解除", "已关闭"].includes(w.status)) {
    items.push({
      key: "dispatch",
      type: "dispatch",
      title: "资源派发",
      time: w.dispatchedAt || "已派发",
      desc: "已派发对应等级的应急资源",
      operator: `派发人: ${w.dispatchedBy || "系统"}`,
      // 橙/红级别会同步触发应急响应。用标签单独标出，而不是塞进正文句子
      tag: /橙|红/.test(String(w.level))
        ? { text: "触发应急响应", icon: Zap, tone: "warning" }
        : null,
    });
  }

  history.forEach((record, index) => {
    items.push({
      key: `history-${index}`,
      type: "feedback",
      title: record.title || "处理反馈",
      time: record.time,
      desc: record.desc,
    });
  });

  if (w.status === "已关闭" || w.status === "已解除") {
    items.push({
      key: "close",
      type: "close",
      title: "预警关闭",
      time: w.closedAt || "已关闭",
      desc: w.closeReason || "预警已关闭",
      operator: `关闭人: ${w.closedBy || "系统"}`,
    });
  }

  return items.map((item) => ({ ...item, ...TIMELINE_NODE_META[item.type] }));
});

async function loadData() {
  loading.value = true;
  notFound.value = false;
  try {
    const data = await dashboardApi.getWarningDetail(route.params.id);
    warning.value = data.item ?? data;
    isResolved.value = warning.value?.status === "已解除" || warning.value?.status === "已关闭";
    
    form.value = {
      title: warning.value.title,
      level: warning.value.level,
      status: warning.value.status,
    };

    setAiContext({
      contextType: 'warning',
      contextId: warning.value.warningId || route.params.id,
      title: warning.value.title || `预警 ${warning.value.warningId}`,
      metadata: {
        level: warning.value.level,
        status: warning.value.status,
        hazardCode: warning.value.hazardCode,
      },
    });

    try {
      const stats = await dashboardApi.getDeliverySummary(route.params.id);
      deliveryStats.value = {
        delivered: stats?.delivered ?? 0,
        shouldDeliver: stats?.shouldDeliver ?? 0,
      };
    } catch (_) {
      deliveryStats.value = { delivered: 0, shouldDeliver: 0 };
    }

    loading.value = false;
    loadAiRisk();
  } catch (error) {
    notFound.value = true;
    loading.value = false;
  }
}

async function loadAiRisk() {
  const hazardCode = warning.value?.hazardCode;
  if (!hazardCode) return;
  aiRiskLoading.value = true;
  try {
    aiRisk.value = await aiApi.fusionRiskAuto(hazardCode);
  } catch (_e) {
    aiRisk.value = null;
  } finally {
    aiRiskLoading.value = false;
  }
}

function riskLevelColor(level) {
  const map = {
    CRITICAL: "#e06060",
    HIGH: "#cbaa76",
    MEDIUM: "#d9c040",
    LOW: "#87b096",
  };
  return map[String(level || "").toUpperCase()] || "#76847a";
}

function riskLevelLabel(level) {
  const map = {
    CRITICAL: "极端",
    HIGH: "高风险",
    MEDIUM: "中风险",
    LOW: "低风险",
  };
  return map[String(level || "").toUpperCase()] || level || "未知";
}

async function saveDetail() {
  if (!warning.value) return;
  const result = await dashboardApi.updateWarning(warning.value.id, {
    ...form.value,
  });
  warning.value = { ...warning.value, ...result };
  editMode.value = false;
}

async function refreshDetail() {
  await loadData();
}

async function handleDeliveryRefresh() {
  await loadData();
}

/* ===================== 处置动作：确认 / 派发 / 关闭 =====================
 * 详情页此前只有「编辑详情」，确认与派发必须在列表页做，用户要处置就得退回列表；
 * 而退回列表会重新 loadData 并把选中项重置为第一条，操作上下文直接丢失。
 * 这里把列表页的三个动作原样搬到详情页：接口、权限码、按钮样式全部复用，
 * 结果就地合并进当前 warning，不跳转、不刷新页面。
 */

/** 正在进行中的动作，用于防重复点击（null 表示空闲） */
const actionLoading = ref(null);

/** 关闭预警的原因弹窗（关闭必须记录原因，与列表页口径一致） */
const closeDialog = ref({ open: false, reason: "", error: "", saving: false });

const CLOSE_REASON_PRESETS = [
  "现场处置完成，险情解除",
  "风险已降至关注级以下",
  "已完成应急闭环，事件终止",
  "其他（请手动输入原因）",
];

/**
 * 把动作接口的返回值合并进当前详情，并同步派生状态。
 *
 * 不能直接 loadData()：那会把 activeTab、编辑态、AI 评分一起重置，
 * 用户视线被强行拽回顶部。这里只就地打补丁。
 *
 * @param {object} result - 接口返回的 WarningDto
 * @returns {void}
 */
function applyUpdated(result) {
  if (!result || typeof result !== "object") return;
  warning.value = { ...warning.value, ...result };
  isResolved.value =
    warning.value?.status === "已解除" || warning.value?.status === "已关闭";
  form.value = {
    ...form.value,
    title: warning.value.title,
    level: warning.value.level,
    status: warning.value.status,
  };
}

async function handleConfirm() {
  if (!warning.value || actionLoading.value) return;
  actionLoading.value = "confirm";
  try {
    const result = await dashboardApi.confirmWarning(warning.value.id);
    applyUpdated(result);
    emit("notify", `已确认：${warning.value.title}`);
  } catch (error) {
    emit("notify", error instanceof Error ? error.message : "确认失败");
  } finally {
    actionLoading.value = null;
  }
}

async function handleDispatch() {
  if (!warning.value || actionLoading.value) return;
  actionLoading.value = "dispatch";
  try {
    const result = await dashboardApi.dispatchWarning(warning.value.id);
    applyUpdated(result);
    // 与列表页保持同一话术：橙色及以上派发后按等级启动应急响应
    const escalated = /橙|红/.test(String(result?.level ?? warning.value.level ?? ""));
    emit(
      "notify",
      escalated
        ? `已派发：${warning.value.title}，并按等级自动启动应急响应`
        : `已派发：${warning.value.title}，按等级采取加密巡查`,
    );
  } catch (error) {
    emit("notify", error instanceof Error ? error.message : "派发失败");
  } finally {
    actionLoading.value = null;
  }
}

function openCloseDialog() {
  closeDialog.value = { open: true, reason: "", error: "", saving: false };
}

function cancelCloseDialog() {
  if (closeDialog.value.saving) return;
  closeDialog.value.open = false;
}

function applyClosePreset(preset) {
  closeDialog.value.reason = preset === "其他（请手动输入原因）" ? "" : preset;
  closeDialog.value.error = "";
}

async function submitClose() {
  if (!warning.value || closeDialog.value.saving) return;
  const reason = closeDialog.value.reason.trim();
  if (!reason) {
    closeDialog.value.error = "请输入关闭原因";
    return;
  }
  closeDialog.value.saving = true;
  closeDialog.value.error = "";
  try {
    const result = await dashboardApi.closeWarning(warning.value.id, { reason });
    applyUpdated(result);
    // 后端未回传 closeReason 时用输入值兜底回显
    if (!warning.value.closeReason) {
      warning.value = { ...warning.value, closeReason: reason };
    }
    closeDialog.value.open = false;
    emit("notify", `已关闭：${warning.value.title}`);
  } catch (error) {
    closeDialog.value.error =
      error instanceof Error ? error.message : "关闭失败";
  } finally {
    closeDialog.value.saving = false;
  }
}

useEscapeClose(cancelCloseDialog, () => closeDialog.value.open);

watch(() => route.query.tab, (newTab) => {
  if (!newTab) {
    activeTab.value = "overview";
    return;
  }
  if (!warning.value) {
    activeTab.value = "overview";
    return;
  }
  if (["overview", "delivery", "timeline"].includes(newTab)) {
    activeTab.value = newTab;
  }
}, { immediate: true });

watch(() => warning.value, (newWarning) => {
  if (newWarning && route.query.tab && ["overview", "delivery", "timeline"].includes(route.query.tab)) {
    activeTab.value = route.query.tab;
  }
});

onMounted(loadData);

onBeforeUnmount(() => {
  clearAiContext();
});
</script>

<style scoped>
/* ═══════════════════════════════════════════════════════════
   ERROR STATE
   ═══════════════════════════════════════════════════════════ */
.error-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-md);
  padding: var(--space-2xl);
  background: var(--color-destructive-bg);
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-lg);
  color: var(--color-destructive-strong);
  text-align: center;
}
.error-block strong { 
  font-size: var(--text-lg); 
}
.error-block p { 
  margin: 0; 
  color: var(--color-destructive-strong); 
  font-size: var(--text-sm); 
}

/* ═══════════════════════════════════════════════════════════
   TAB NAVIGATION (新设计: 3个Tab)
   ═══════════════════════════════════════════════════════════ */
.tab-nav {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: 0 0 var(--space-md);
  border-bottom: 1px solid var(--color-border);
  margin-bottom: var(--space-lg);
  flex-wrap: wrap;
}

.tab-button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: var(--space-sm) var(--space-md);
  border: none;
  background: transparent;
  color: var(--color-muted-foreground);
  font-size: var(--text-sm);
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: color var(--duration-fast) var(--ease-out), 
              border-color var(--duration-fast) var(--ease-out);
  position: relative;
}

.tab-button:hover {
  color: var(--color-foreground);
}

.tab-button.active {
  color: var(--color-primary);
  border-bottom-color: var(--color-primary);
  font-weight: var(--font-semibold);
}

.tab-button.disabled {
  opacity: 0.5;
  cursor: not-allowed;
  color: var(--color-tertiary);
}

.tab-button.disabled:hover {
  color: var(--color-tertiary);
}

.tab-warning {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  background: var(--color-destructive);
  color: white;
  border-radius: var(--radius-full);
  font-size: 10px;
  font-weight: var(--font-bold);
  margin-left: 2px;
}

/* ═══════════════════════════════════════════════════════════
   TAB NAVIGATION (新设计: 3个Tab)
   ═══════════════════════════════════════════════════════════ */
.tab-nav {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: 0 0 var(--space-md);
  border-bottom: 1px solid var(--color-border);
  margin-bottom: var(--space-lg);
  flex-wrap: wrap;
}

/* Tab 内容包装器: 逃离 .content-grid 的 Grid 控制 */
.tab-content-wrapper {
  display: block;
  width: 100%;
}

.detail-overview-grid {
  display: grid;
  grid-template-columns: 1.5fr 1fr; /* 左60% / 右40% */
  gap: var(--space-lg);
  align-items: start;
}

/* 左栏: 信息展示区 */
.detail-left {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
}

/* 右栏: 操作区 */
.detail-right {
  display: flex;
  flex-direction: column;
  gap: var(--space-lg);
  position: sticky;
  top: var(--space-lg);
}

/* ═══════════════════════════════════════════════════════════
   INFO CARDS (使用新变量重构)
   ═══════════════════════════════════════════════════════════ */
.info-card,
.ai-card,
.linked-card,
.action-card,
.suggestion-card,
.delivery-summary-card {
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--space-lg);
  box-shadow: var(--shadow-sm);
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: var(--space-md);
  padding-bottom: var(--space-md);
  border-bottom: 1px solid var(--color-border);
}

.card-header h3 {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  font-size: var(--text-lg);
  font-weight: var(--font-semibold);
  color: var(--color-foreground);
  margin: 0;
}

.card-header .hint {
  font-size: var(--text-xs);
  color: var(--color-muted-foreground);
}

/* 信息列表 */
.info-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.info-item {
  display: grid;
  grid-template-columns: 140px 1fr;
  gap: var(--space-md);
  align-items: center;
}

.info-label {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
}

.info-value {
  font-size: var(--text-base);
  font-weight: var(--font-medium);
  color: var(--color-foreground);
}

.info-value.editable {
  width: 100%;
  padding: var(--space-sm) var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  font-size: var(--text-base);
  background: var(--color-background);
  transition: border-color var(--duration-fast) var(--ease-out);
}

.info-value.editable:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: var(--shadow-focus);
}

/* AI 评分卡片 */
.ai-score-content {
  display: flex;
  align-items: center;
  gap: var(--space-lg);
}

.ai-score-ring {
  flex-shrink: 0;
  width: 80px;
  height: 80px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-full);
  border: 4px solid var(--color-border);
  font-size: var(--text-3xl);
  font-weight: var(--font-bold);
}

.ai-score-details {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.ai-score-level {
  font-size: var(--text-xl);
  font-weight: var(--font-semibold);
}

.ai-score-bar {
  height: 8px;
  background: var(--color-muted);
  border-radius: var(--radius-full);
  overflow: hidden;
}

.ai-score-bar-fill {
  height: 100%;
  background: var(--color-primary);
  transition: width var(--duration-slow) var(--ease-out);
}

/* 关联资源链接 */
.linked-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.linked-item {
  display: flex;
  align-items: center;
  gap: var(--space-sm);
  padding: var(--space-md);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  text-decoration: none;
  color: var(--color-foreground);
  transition:
    border-color var(--duration-fast) var(--ease-out),
    background-color var(--duration-fast) var(--ease-out);
}

.linked-item:hover {
  border-color: var(--color-primary);
  background: var(--color-primary-mist);
  transform: translateX(4px);
}

.linked-item .link-icon {
  color: var(--color-primary);
}

.linked-item .link-text {
  flex: 1;
  font-size: var(--text-sm);
}

.linked-item .link-arrow {
  color: var(--color-muted-foreground);
}

/* ═══════════════════════════════════════════════════════════
   ACTION BUTTONS (右栏顶部操作按钮组)
   ═══════════════════════════════════════════════════════════ */
.action-buttons {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
  padding: var(--space-lg);
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-md);
}

.action-buttons-title {
  font-size: var(--text-base);
  font-weight: var(--font-semibold);
  color: var(--color-foreground);
  margin-bottom: var(--space-xs);
}

.action-buttons-group {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

/* AI 建议卡片 */
.suggestion-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
}

.suggestion-item {
  display: flex;
  align-items: flex-start;
  gap: var(--space-sm);
  padding: var(--space-md);
  background: var(--color-primary-mist);
  border-left: 3px solid var(--color-primary);
  border-radius: var(--radius-sm);
  font-size: var(--text-sm);
  color: var(--color-foreground);
}

.suggestion-item::before {
  content: '•';
  color: var(--color-primary);
  font-weight: var(--font-bold);
  font-size: var(--text-lg);
}

/* 送达统计概览 */
.delivery-stats {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
}

.delivery-rate {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: var(--space-md);
  background: var(--color-success-bg);
  border-radius: var(--radius-md);
}

.delivery-rate-text {
  font-size: var(--text-base);
  font-weight: var(--font-medium);
  color: var(--color-foreground);
}

.delivery-rate-percent {
  font-size: var(--text-2xl);
  font-weight: var(--font-bold);
  color: var(--color-success-strong);
}

.delivery-channels {
  display: flex;
  flex-direction: column;
  gap: var(--space-sm);
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
}

/* ═══════════════════════════════════════════════════════════
   TIMELINE TAB (处置时间轴 - 合并审批记录)
   轨道几何：圆点直径 26px，中心落在 x=13px；
   连接线宽 2px，需 left:12px 才能与圆点严格同心。
   ═══════════════════════════════════════════════════════════ */
.timeline-container {
  background: var(--color-card);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--space-lg);
}

.timeline-list {
  display: flex;
  flex-direction: column;
  gap: var(--space-md);
  list-style: none;
  margin: 0;
  padding: 0;
}

.timeline-item {
  position: relative;
  display: grid;
  grid-template-columns: 26px minmax(0, 1fr);
  gap: var(--space-md);
  align-items: start;
}

/* 连接线：从本节点圆点下沿延伸到下一节点圆点上沿 */
.timeline-item::before {
  content: '';
  position: absolute;
  left: 12px;
  top: 26px;
  bottom: calc(var(--space-md) * -1);
  width: 2px;
  background: var(--color-border);
}

.timeline-item:last-child::before {
  display: none;
}

/* 圆点：语义色浅底 + 同色描边 + 同色图标。
   12px 图标属于非文本图形，需要 ≥3:1 对比度，
   因此取 -strong / accent 等加深变体而非浅莫兰迪原色。 */
.timeline-marker {
  position: relative;
  z-index: 1;
  width: 26px;
  height: 26px;
  border-radius: var(--radius-full);
  display: grid;
  place-items: center;
  background: var(--color-card);
  border: 1px solid var(--color-border);
  color: var(--color-muted-foreground);
  /* 白色描边环：圆点底色是半透明的，不加这圈会把连接线透出来 */
  box-shadow: 0 0 0 3px var(--color-card);
}

.timeline-marker.tone-primary {
  background: var(--color-primary-soft);
  border-color: var(--color-primary);
  color: var(--color-primary);
}

.timeline-marker.tone-info {
  background: var(--color-accent-soft);
  border-color: var(--color-accent);
  color: var(--color-accent);
}

.timeline-marker.tone-success {
  background: var(--color-success-bg);
  border-color: var(--color-success-strong);
  color: var(--color-success-strong);
}

.timeline-marker.tone-neutral {
  background: var(--color-muted);
  border-color: var(--color-border-hover);
  color: var(--color-muted-foreground);
}

.timeline-marker.tone-destructive {
  background: var(--color-destructive-bg);
  border-color: var(--color-destructive-strong);
  color: var(--color-destructive-strong);
}

/* 内容卡片：极浅底 + 细边框，避免文字直接浮在白板上 */
.timeline-card {
  position: relative;
  background: var(--color-surface-1);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  padding: var(--space-md) var(--space-lg);
  transition:
    box-shadow var(--duration-fast) var(--ease-out),
    border-color var(--duration-fast) var(--ease-out),
    transform var(--duration-fast) var(--ease-out);
}

/* 悬停只做极轻微抬升：静态的边框变化同时给出反馈，不依赖动画单通道 */
.timeline-card:hover {
  border-color: var(--color-border-hover);
  box-shadow: var(--shadow-sm);
  transform: translateY(-1px);
}

.timeline-header {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: var(--space-md);
  margin-bottom: var(--space-xs);
}

.timeline-title {
  font-size: var(--text-base);
  font-weight: var(--font-semibold);
  color: var(--color-foreground);
}

.timeline-time {
  font-size: var(--text-xs);
  color: var(--color-muted-foreground);
  /* 时间戳等宽，避免数值跳动导致行宽抖动 */
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.timeline-desc {
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
  margin: 0;
}

/* 操作人：与描述同色但更小，用字号而不是低对比度灰色区分层级 */
.timeline-operator {
  display: flex;
  align-items: center;
  gap: var(--space-xs);
  font-size: var(--text-xs);
  color: var(--color-muted-foreground);
  margin: var(--space-xs) 0 0;
}

.timeline-operator svg {
  flex-shrink: 0;
  color: var(--color-tertiary);
}

/* 应急响应标签：米色底 + 深色文字，保证小字号也满足 AA 对比度 */
.timeline-tag {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  margin-left: var(--space-sm);
  padding: 0 var(--space-sm);
  border-radius: var(--radius-sm);
  background: var(--color-warning-bg);
  border: 1px solid var(--color-warning-strong);
  color: var(--color-foreground);
  font-size: var(--text-xs);
  font-weight: var(--font-medium);
  white-space: nowrap;
  vertical-align: 1px;
}

/* ═══════════════════════════════════════════════════════════
   TIMELINE 空状态
   ═══════════════════════════════════════════════════════════ */
.timeline-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-xs);
  padding: var(--space-2xl) var(--space-lg);
  text-align: center;
}

.timeline-empty-icon {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  margin-bottom: var(--space-sm);
  border-radius: var(--radius-full);
  background: var(--color-primary-soft);
  color: var(--color-primary);
}

.timeline-empty-title {
  margin: 0;
  font-size: var(--text-base);
  font-weight: var(--font-semibold);
  color: var(--color-foreground);
}

.timeline-empty-desc {
  max-width: 34ch;
  font-size: var(--text-sm);
  color: var(--color-muted-foreground);
}

/* ═══════════════════════════════════════════════════════════
   RESPONSIVE - MOBILE (补充要求6: 移动端适配)
   ═══════════════════════════════════════════════════════════ */
@media (max-width: 768px) {
  /* Grid 变单列 */
  .detail-overview-grid {
    grid-template-columns: 1fr;
  }

  /* 右栏取消 sticky */
  .detail-right {
    position: static;
  }

  /* 操作按钮固定在底部 (补充要求6) */
  .action-buttons {
    position: fixed;
    bottom: 0;
    left: 0;
    right: 0;
    z-index: var(--z-sticky);
    border-radius: var(--radius-lg) var(--radius-lg) 0 0;
    box-shadow: 0 -4px 12px rgba(44, 59, 52, 0.15);
    background: var(--color-card);
    padding: var(--space-md);
  }

  /* 底部留出空间避免被按钮遮挡 */
  .detail-left {
    padding-bottom: 120px;
  }

  /* Tab 按钮更紧凑 */
  .tab-button {
    font-size: var(--text-xs);
    padding: var(--space-sm) var(--space-md);
  }

  /* 信息列表改为单列 */
  .info-item {
    grid-template-columns: 1fr;
    gap: var(--space-xs);
  }

  /* AI 评分环缩小 */
  .ai-score-ring {
    width: 60px;
    height: 60px;
    font-size: var(--text-2xl);
  }

  /* 时间轴在窄屏收紧留白，圆点与连接线几何保持不变 */
  .timeline-container {
    padding: var(--space-md);
  }

  .timeline-item {
    gap: var(--space-sm);
  }

  .timeline-card {
    padding: var(--space-md);
  }
}

/* 平板适配 */
@media (min-width: 769px) and (max-width: 1439px) {
  .detail-overview-grid {
    grid-template-columns: 1.2fr 1fr; /* 55% / 45% */
  }
}
</style>

<template>
  <div>
    <div v-if="loading" class="u-hint-block">
      加载中...
    </div>
    <div v-else-if="notFound" class="error-block">
      <AlertCircle :size="20" />
      <strong>预警不存在</strong>
      <p>该预警可能已删除或您没有访问权限。</p>
      <button class="secondary-btn" @click="router.back()">
        <ArrowLeft :size="14" />返回
      </button>
    </div>
    <template v-else-if="warning">
      <div class="content-grid">
        <!-- ═══════════════════════════════════════════════════════════
             TAB NAVIGATION (新设计: 3个Tab)
             ═══════════════════════════════════════════════════════════ -->
        <div class="tab-nav" style="grid-column: 1 / -1;">
          <button
            class="tab-button"
            :class="{ active: activeTab === 'overview' }"
            @click="activeTab = 'overview'"
          >
            <FileText :size="14" />概览
          </button>
          <button
            class="tab-button"
            :class="{ active: activeTab === 'delivery' }"
            @click="activeTab = 'delivery'"
          >
            <Bell :size="14" />通知与送达
            <span v-if="deliveryWarning" class="tab-warning" title="送达率未达 100%">!</span>
          </button>
          <button
            class="tab-button"
            :class="{ active: activeTab === 'timeline' }"
            @click="activeTab = 'timeline'"
          >
            <History :size="14" />处置时间轴
          </button>
          <div class="tab-spacer"></div>
          <button class="text-btn" @click="router.back()">
            <ArrowLeft :size="15" />返回
          </button>
          <button class="text-btn" @click="refreshDetail">
            <History :size="15" />刷新
          </button>
        </div>

        <!-- Tab 内容容器：不受 content-grid Grid 影响 -->
        <div class="tab-content-wrapper" style="grid-column: 1 / -1;">
          
          <!-- ═══════════════════════════════════════════════════════════
               OVERVIEW TAB (概览 - Grid 2栏布局)
               ═══════════════════════════════════════════════════════════ -->
          <template v-if="activeTab === 'overview'">
            <div class="detail-overview-grid">
            
            <!-- 左栏: 信息展示区 (60%) -->
            <div class="detail-left">
              
              <!-- 基础信息卡片 -->
              <section class="info-card">
                <div class="card-header">
                  <h3><FileText :size="18" />基础信息</h3>
                  <button 
                    v-if="!editMode"
                    class="text-btn" 
                    @click="editMode = true"
                  >
                    <PencilLine :size="14" />编辑
                  </button>
                  <div v-else style="display: flex; gap: 8px;">
                    <button class="text-btn" @click="editMode = false">
                      取消
                    </button>
                    <PermissionButton
                      permission="warnings:confirm"
                      :icon="Save"
                      @click="async ({ allowed }) => { if (allowed) await saveDetail(); }"
                    >
                      保存
                    </PermissionButton>
                  </div>
                </div>
                <div class="info-list">
                  <div class="info-item">
                    <span class="info-label">
                      <Bell :size="16" />预警等级
                    </span>
                    <span v-if="!editMode" class="info-value">{{ warning.level }}</span>
                    <select v-else v-model="form.level" class="info-value editable">
                      <option>红色预警</option>
                      <option>橙色预警</option>
                      <option>黄色预警</option>
                      <option>蓝色提醒</option>
                    </select>
                  </div>
                  <div class="info-item">
                    <span class="info-label">
                      <Layers :size="16" />触发点位
                    </span>
                    <span class="info-value">{{ warning.point }}</span>
                  </div>
                  <div class="info-item">
                    <span class="info-label">
                      <MapPin :size="16" />触发时间
                    </span>
                    <span class="info-value">{{ warning.time }}</span>
                  </div>
                  <div class="info-item">
                    <span class="info-label">
                      <ShieldCheck :size="16" />当前状态
                    </span>
                    <span v-if="!editMode" class="info-value">{{ warning.status }}</span>
                    <select v-else v-model="form.status" class="info-value editable">
                      <option>待确认</option>
                      <option>已确认</option>
                      <option>处理中</option>
                      <option>已解除</option>
                      <option>已关闭</option>
                    </select>
                  </div>
                  <div v-if="warning.closeReason" class="info-item">
                    <span class="info-label">
                      <GitBranch :size="16" />关闭原因
                    </span>
                    <span class="info-value">{{ warning.closeReason }}</span>
                  </div>
                  <div class="info-item" style="grid-template-columns: 1fr;">
                    <span class="info-label" style="margin-bottom: 8px;">
                      <FileText :size="16" />预警描述
                    </span>
                    <span v-if="!editMode" class="info-value">{{ warning.desc || warning.title }}</span>
                    <textarea 
                      v-else 
                      v-model="form.title" 
                      class="info-value editable" 
                      rows="3"
                      placeholder="请输入预警描述"
                    />
                  </div>
                </div>
              </section>

              <!-- AI 融合评分卡片 -->
              <section class="ai-card">
                <div class="card-header">
                  <h3><Bot :size="18" />AI 融合风险评分</h3>
                  <span v-if="aiRisk?.mock" class="hint" style="color: var(--color-warning);">
                    启发式降级
                  </span>
                  <span v-else-if="aiRisk" class="hint" style="color: var(--color-primary);">
                    {{ aiRisk.model }}
                  </span>
                </div>
                
                <div v-if="aiRiskLoading" class="u-hint-box">
                  AI 分析中...
                </div>
                <div v-else-if="aiRisk" class="ai-score-content">
                  <div 
                    class="ai-score-ring" 
                    :style="{ 
                      borderColor: riskLevelColor(aiRisk.risk_level),
                      color: riskLevelColor(aiRisk.risk_level)
                    }"
                  >
                    <span>{{ aiRisk.risk_score }}<small style="font-size: 0.5em;">%</small></span>
                  </div>
                  <div class="ai-score-details">
                    <div style="display: flex; justify-content: space-between; align-items: center;">
                      <strong 
                        class="ai-score-level" 
                        :style="{ color: riskLevelColor(aiRisk.risk_level) }"
                      >
                        {{ riskLevelLabel(aiRisk.risk_level) }}
                      </strong>
                      <span style="font-size: var(--text-xs); color: var(--color-muted-foreground);">
                        置信度 {{ (aiRisk.confidence * 100).toFixed(0) }}%
                      </span>
                    </div>
                    <div class="ai-score-bar">
                      <div 
                        class="ai-score-bar-fill" 
                        :style="{ 
                          width: aiRisk.risk_score + '%',
                          background: riskLevelColor(aiRisk.risk_level)
                        }"
                      />
                    </div>
                  </div>
                </div>
                <div v-else class="u-hint-box">
                  AI 服务不可用，已跳过评分
                </div>
              </section>

              <!-- 关联资源卡片 -->
              <section class="linked-card">
                <div class="card-header">
                  <h3><GitBranch :size="18" />关联资源</h3>
                </div>
                <div v-if="warning.linked?.length" class="linked-list">
                  <RouterLink 
                    v-for="item in warning.linked" 
                    :key="`${item.type}-${item.label}`" 
                    :to="item.path" 
                    class="linked-item"
                  >
                    <span class="link-icon">
                      <GitBranch :size="16" />
                    </span>
                    <span class="link-text">
                      <strong style="margin-right: 8px;">{{ item.type }}</strong>
                      {{ item.label }}
                    </span>
                    <span class="link-arrow">›</span>
                  </RouterLink>
                </div>
                <p v-else style="color: var(--color-muted-foreground); font-size: var(--text-sm); margin: 0;">
                  暂无关联记录
                </p>
              </section>

              <!-- 附件卡片 (如果有附件) -->
              <section v-if="warning.attachments?.length" class="info-card">
                <div class="card-header">
                  <h3><Paperclip :size="18" />附件</h3>
                </div>
                <div class="linked-list">
                  <div 
                    v-for="file in warning.attachments" 
                    :key="file.name" 
                    class="linked-item"
                    style="cursor: default;"
                  >
                    <Paperclip :size="16" style="color: var(--color-muted-foreground);" />
                    <span class="link-text">{{ file.name }}</span>
                    <span style="font-size: var(--text-xs); color: var(--color-muted-foreground);">
                      {{ file.size }}
                    </span>
                  </div>
                </div>
              </section>

            </div>

            <!-- 右栏: 操作区 (40%) -->
            <div class="detail-right">
              
              <!-- 处置操作按钮组 (权限控制保持原样) -->
              <div class="action-buttons">
                <div class="action-buttons-title">处置操作</div>
                <div class="action-buttons-group">
                  <!-- 确认按钮 (补充要求7: 完全保留权限控制) -->
                  <PermissionButton
                    v-if="warning.status === '待确认' || warning.status === '已确认'"
                    permission="warnings:confirm"
                    :icon="CheckCircle2"
                    :disabled="actionLoading === 'confirm'"
                    @click="async ({ allowed }) => { if (allowed) await handleConfirm() }"
                  >
                    {{ actionLoading === 'confirm' ? '确认中...' : '确认预警' }}
                  </PermissionButton>
                  
                  <!-- 派发按钮 -->
                  <PermissionButton
                    v-if="!isResolved"
                    permission="warnings:dispatch"
                    :icon="Send"
                    :disabled="actionLoading === 'dispatch'"
                    @click="async ({ allowed }) => { if (allowed) await handleDispatch() }"
                  >
                    {{ actionLoading === 'dispatch' ? '派发中...' : '派发资源' }}
                  </PermissionButton>
                  
                  <!-- 关闭按钮 -->
                  <PermissionButton
                    v-if="!isResolved"
                    permission="warnings:close"
                    :icon="XCircle"
                    :disabled="actionLoading !== null"
                    @click="({ allowed }) => { if (allowed) openCloseDialog() }"
                  >
                    关闭预警
                  </PermissionButton>
                </div>
              </div>

              <!-- AI 建议卡片 -->
              <section v-if="aiRisk" class="suggestion-card">
                <div class="card-header">
                  <h3><Bot :size="18" />AI 建议</h3>
                </div>
                <div class="suggestion-list">
                  <div class="suggestion-item">
                    建议疏散低洼地区居民
                  </div>
                  <div class="suggestion-item">
                    关闭 3 处下穿通道
                  </div>
                  <div class="suggestion-item">
                    调度应急物资: 沙袋 500 个、水泵 5 台
                  </div>
                  <div class="suggestion-item">
                    预计影响人口: {{ deliveryStats.shouldDeliver || '未知' }} 人
                  </div>
                </div>
              </section>

              <!-- 送达统计概览 -->
              <section class="delivery-summary-card">
                <div class="card-header">
                  <h3><Bell :size="18" />送达统计</h3>
                </div>
                <div class="delivery-stats">
                  <div class="delivery-rate">
                    <span class="delivery-rate-text">已送达</span>
                    <span class="delivery-rate-percent">
                      {{ deliveryStats.shouldDeliver > 0 
                        ? Math.round((deliveryStats.delivered / deliveryStats.shouldDeliver) * 100) 
                        : 0 
                      }}%
                    </span>
                  </div>
                  <div class="delivery-channels">
                    <div>已送达: {{ deliveryStats.delivered }} / {{ deliveryStats.shouldDeliver }} 人</div>
                    <div style="font-size: var(--text-xs); color: var(--color-tertiary); margin-top: 4px;">
                      点击"通知与送达"Tab 查看详细数据
                    </div>
                  </div>
                </div>
              </section>

            </div>

          </div>
        </template>

        <!-- ═══════════════════════════════════════════════════════════
             DELIVERY TAB (通知与送达)
             ═══════════════════════════════════════════════════════════ -->
        <template v-if="activeTab === 'delivery'">
          <WarningDeliveryTab
            :warning-id="route.params.id"
            @refresh="handleDeliveryRefresh"
            @notify="() => {}"
          />
        </template>

        <!-- ═══════════════════════════════════════════════════════════
             TIMELINE TAB (处置时间轴 - 合并审批记录 + 历史)
             节点颜色取自 tokens.css 语义色板，并用图标提供第二重识别通道
             ═══════════════════════════════════════════════════════════ -->
        <template v-if="activeTab === 'timeline'">
          <div class="timeline-container">
            <!-- 空状态：没有任何处置动作时给出图标 + 说明，而不是留白 -->
            <div v-if="!timelineItems.length" class="timeline-empty">
              <span class="timeline-empty-icon"><History :size="24" /></span>
              <p class="timeline-empty-title">暂无处置记录</p>
              <small class="timeline-empty-desc">
                该预警尚未产生发布、确认或派发动作，记录生成后会按时间顺序在此展示。
              </small>
            </div>

            <ol v-else class="timeline-list">
              <li v-for="item in timelineItems" :key="item.key" class="timeline-item">
                <span class="timeline-marker" :class="`tone-${item.tone}`">
                  <component :is="item.icon" :size="13" />
                </span>

                <div class="timeline-card">
                  <div class="timeline-header">
                    <span class="timeline-title">{{ item.title }}</span>
                    <span class="timeline-time">{{ item.time }}</span>
                  </div>

                  <p class="timeline-desc">
                    {{ item.desc }}
                    <span v-if="item.tag" class="timeline-tag" :class="`tone-${item.tag.tone}`">
                      <component :is="item.tag.icon" :size="11" />
                      {{ item.tag.text }}
                    </span>
                  </p>

                  <p v-if="item.operator" class="timeline-operator">
                    <User :size="11" />
                    <span>{{ item.operator }}</span>
                  </p>
                </div>
              </li>
            </ol>
          </div>
        </template>

        </div>
        <!-- 结束: tab-content-wrapper -->

      </div>
    </template>

    <!-- 关闭预警原因弹窗：复用列表页同一套全局弹窗样式，未新增任何 CSS -->
    <div v-if="closeDialog.open" class="close-dialog-mask" @click.self="cancelCloseDialog">
      <div class="close-dialog" role="dialog" aria-modal="true">
        <div class="close-dialog-head">
          <strong>关闭预警</strong>
          <button class="close-dialog-x" @click="cancelCloseDialog" aria-label="关闭">×</button>
        </div>
        <div class="close-dialog-body">
          <p class="close-dialog-target">
            <AlertCircle :size="14" />
            <span>{{ warning?.title }}</span>
          </p>
          <p class="close-dialog-hint">关闭此预警后，事件流转结束，请填写关闭原因。</p>

          <div class="close-dialog-presets">
            <button
              v-for="preset in CLOSE_REASON_PRESETS"
              :key="preset"
              type="button"
              class="preset-chip"
              :class="{ active: closeDialog.reason === preset }"
              @click="applyClosePreset(preset)"
            >
              {{ preset }}
            </button>
          </div>

          <label class="close-dialog-field">
            <span>关闭原因<em>*</em></span>
            <textarea
              v-model="closeDialog.reason"
              rows="3"
              placeholder="现场处置完成，险情解除"
            ></textarea>
          </label>

          <p v-if="closeDialog.error" class="close-dialog-error">{{ closeDialog.error }}</p>
        </div>
        <div class="close-dialog-foot">
          <button class="secondary-btn" :disabled="closeDialog.saving" @click="cancelCloseDialog">取消</button>
          <button class="primary-btn" :disabled="closeDialog.saving" @click="submitClose">
            {{ closeDialog.saving ? "提交中..." : "确认关闭" }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
