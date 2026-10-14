<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  ArrowLeft,
  Check,
  ClipboardCheck,
  FileText,
  Gauge,
  Layers3,
  MapPin,
  MountainSnow,
  PencilLine,
  Paperclip,
  GitBranch,
  History,
  Link2,
  Save,
  MapPinned,
  Bot,
  TrendingUp,
  Send,
  ThumbsDown,
} from "lucide-vue-next";
import PermissionButton from "../../components/PermissionButton.vue";
import EventStatusTimeline from "../../components/EventStatusTimeline.vue";
import { dashboardApi } from "../../services/dashboardApi";
import { aiApi } from "../../services/aiApi";
import { hasPermission, getAuthUser } from "../../stores/auth";
import { safeMax } from "../../utils/safeMinMax";

const emit = defineEmits(['notify'])

const route = useRoute();
const router = useRouter();
const point = ref(null);
const loading = ref(true);
const editMode = ref(false);

/**
 * v1.7.0：区分"灾害详情"和"人工确认"两种入口上下文。
 * 人工确认入口: /dashboard/confirmation-workbench/:id (route.name === 'confirmation-detail')
 */
const isConfirmationMode = computed(() => route.name === 'confirmation-detail')

// 人工确认操作状态
const confirmationState = ref({
  status: '待确认',
  assignee: null,
  loadingClaim: false,
  loadingConfirm: false,
  loadingReject: false,
  loadingTransfer: false,
  rejectDialog: false,
  rejectReason: '',
  transferDialog: false,
  transferTo: '',
  transferReason: '',
})

/**
 * 人工确认的操作对象是「统一事件」（unified_event）的数字 ID，
 * 后端四个动作均在 /api/confirmation/queue/{id}/... 下，id 必须是事件主键。
 *
 * <p>确认模式下的路由参数是 hazardCode（工作台跳转时传的是业务码），
 * 详情接口返回的 item 里也没有事件 ID，因此这里在进入确认模式时
 * 拉一次确认队列，按 hazardCode 反查事件 ID，避免用错 ID 或伪造本地状态。
 */
const confirmationEventId = ref(null)

async function resolveEventId() {
  if (!isConfirmationMode.value) return
  // 1) item 若直接带事件 ID 字段，优先使用
  const direct = point.value?.eventId ?? point.value?.unifiedEventId ?? point.value?.confirmationId
  if (direct != null) {
    confirmationEventId.value = direct
    return
  }
  // 2) 否则按 hazardCode 从确认队列反查
  const code = route.params.id ?? point.value?.hazardCode
  if (!code) return
  try {
    const queue = await dashboardApi.getConfirmationQueue({})
    const hit = (queue?.items ?? []).find((it) => it.hazardCode === code || String(it.id) === String(code))
    confirmationEventId.value = hit?.id ?? null
    if (hit?.status) confirmationState.value.status = hit.status
    if (hit?.assignee) confirmationState.value.assignee = hit.assignee
  } catch {
    confirmationEventId.value = null
  }
}

const operatorName = computed(() => getAuthUser()?.name ?? 'system')

async function claimItem() {
  if (!isConfirmationMode.value) return
  const id = confirmationEventId.value
  if (id == null) return emit('notify', '缺少事件 ID，无法认领')
  confirmationState.value.loadingClaim = true
  try {
    const res = await dashboardApi.claimConfirmation(id, operatorName.value)
    confirmationState.value.assignee = res?.assignedTo ?? operatorName.value
    confirmationState.value.status = '已认领'
    emit('notify', '已认领该事件')
  } catch (e) {
    emit('notify', `认领失败：${e.message ?? e}`)
  } finally {
    confirmationState.value.loadingClaim = false
  }
}

async function confirmItem() {
  if (!isConfirmationMode.value) return
  const id = confirmationEventId.value
  if (id == null) return emit('notify', '缺少事件 ID，无法确认')
  if (!window.confirm(`确认将"${point.value?.name}"纳入正式灾害档案？确认后将进入预警创建环节。`)) return
  confirmationState.value.loadingConfirm = true
  try {
    await dashboardApi.confirmHazard(id, { operator: operatorName.value })
    confirmationState.value.status = '已确认'
    emit('notify', `已确认 ${point.value?.name}，可创建预警`)
  } catch (e) {
    emit('notify', `确认失败：${e.message ?? e}`)
  } finally {
    confirmationState.value.loadingConfirm = false
  }
}

async function rejectItem() {
  if (!isConfirmationMode.value) return
  const id = confirmationEventId.value
  if (id == null) return emit('notify', '缺少事件 ID，无法驳回')
  if (!confirmationState.value.rejectReason.trim()) return emit('notify', '请填写驳回原因')
  confirmationState.value.loadingReject = true
  try {
    await dashboardApi.rejectHazard(id, {
      operator: operatorName.value,
      reason: confirmationState.value.rejectReason,
    })
    confirmationState.value.status = '已驳回'
    confirmationState.value.rejectDialog = false
    emit('notify', '已驳回该推断')
  } catch (e) {
    emit('notify', `驳回失败：${e.message ?? e}`)
  } finally {
    confirmationState.value.loadingReject = false
  }
}

async function transferItem() {
  if (!isConfirmationMode.value) return
  const id = confirmationEventId.value
  if (id == null) return emit('notify', '缺少事件 ID，无法转派')
  if (!confirmationState.value.transferTo.trim()) return emit('notify', '请选择转派对象')
  confirmationState.value.loadingTransfer = true
  try {
    await dashboardApi.transferConfirmation(
      id,
      confirmationState.value.transferTo,
      confirmationState.value.transferReason,
    )
    confirmationState.value.assignee = confirmationState.value.transferTo
    confirmationState.value.status = '已转派'
    confirmationState.value.transferDialog = false
    emit('notify', `已转派给 ${confirmationState.value.transferTo}`)
  } catch (e) {
    emit('notify', `转派失败：${e.message ?? e}`)
  } finally {
    confirmationState.value.loadingTransfer = false
  }
}

const form = ref({});
const cesiumBusy = ref(false);
const cesiumMessage = ref("");
let cesiumCleanup = null;
const aiForecast = ref(null);
const aiForecastLoading = ref(false);
const aiComprehensive = ref(null);
const aiComprehensiveLoading = ref(false);
const aiLevelLabel = (level) => ({ CRITICAL: '极高', HIGH: '高', MEDIUM: '中', LOW: '低' }[level] || '—');
const aiLevelClass = (level) => ({ CRITICAL: 'orange', HIGH: 'yellow', MEDIUM: 'blue', LOW: 'teal' }[level] || 'blue');

const EVENT_STATE_LABELS = {
  DETECTED: '已发现',
  CONFIRMING: '确认中',
  CONFIRMED: '已确认',
  WARNING_ISSUED: '预警发布',
  EMERGENCY_ACTIVE: '应急处置',
  CLOSED: '已关闭',
  REJECTED: '已驳回',
};
const eventStateLabel = (s) => EVENT_STATE_LABELS[s] || '—';

async function loadAiComprehensive() {
  if (!point.value) return;
  aiComprehensiveLoading.value = true;
  try {
    const code = point.value.hazardCode ?? point.value.code;
    if (!code) {
      // 无 hazardCode：退化为读 point.aiLevel/aiScore（DTO 已被 AiRiskEnricher 注入）
      aiComprehensive.value = point.value.aiScore != null
        ? {
            hazardCode: code,
            riskLevel: point.value.aiLevel,
            riskScore: point.value.aiScore,
            confidence: null,
            model: null,
            analyzedAt: point.value.aiAnalyzedAt,
            dimensions: null,
            reasoning: [],
            suggestedActions: [],
          }
        : null;
      return;
    }
    const cached = await dashboardApi.getAiComprehensiveCached(code);
    aiComprehensive.value = cached;
  } catch (_) {
    // 端点 404（未落库）或网络失败：退化为 DTO 自带 aiLevel/aiScore
    if (point.value.aiScore != null) {
      aiComprehensive.value = {
        hazardCode: point.value.hazardCode ?? point.value.code,
        riskLevel: point.value.aiLevel,
        riskScore: point.value.aiScore,
        confidence: null,
        model: null,
        analyzedAt: point.value.aiAnalyzedAt,
        dimensions: null,
        reasoning: [],
        suggestedActions: [],
      };
    } else {
      aiComprehensive.value = null;
    }
  } finally {
    aiComprehensiveLoading.value = false;
  }
}

async function refreshAiComprehensive() {
  if (!point.value) return;
  const code = point.value.hazardCode ?? point.value.code;
  if (!code) return;
  try {
    // 强制实时推理 + 写回落库（让下次点进直接走缓存）
    aiComprehensive.value = await aiApi.comprehensiveAnalysis(code);
  } catch (_) {
    // 实时推理失败：保留当前值
  }
}

async function loadData() {
  loading.value = true;
  const data = await dashboardApi.getHazardDetail(route.params.id);
  point.value = data.item;
  form.value = {
    name: data.item.name,
    type: data.item.type,
    level: data.item.level,
    owner: data.item.owner,
    status: data.item.status,
    lon: data.item.lon,
    lat: data.item.lat,
  };
  loading.value = false;
  if (isConfirmationMode.value) await resolveEventId();
  loadAiForecast();
  loadAiComprehensive();
}

async function loadAiForecast() {
  if (!point.value) return;
  aiForecastLoading.value = true;
  try {
    // 从后端直接拉取该设备的真实历史指标，交由 AI 完成预测；
    // 不合成/伪造历史数据，AI 不可用时按 null 处理，界面显示不可用提示。
    aiForecast.value = await aiApi.forecastAuto(
      point.value.deviceCode || "DEV-001",
      "displacement",
      7,
      30,
    );
  } catch (_e) {
    aiForecast.value = null;
  } finally {
    aiForecastLoading.value = false;
  }
}

const latestForecast = computed(() => {
  const predictions = aiForecast.value?.predictions;
  if (!Array.isArray(predictions) || !predictions.length) return null;
  return predictions[predictions.length - 1];
});
const forecastMax = computed(() => {
  const predictions = aiForecast.value?.predictions;
  if (!Array.isArray(predictions) || !predictions.length) return 1;
  // ⚠️ 不写 Math.max(...predictions)：预测序列长度无上限。
  // 用 safeMax 替代，O(1) 栈深度。
  return safeMax(predictions) || 1;
});
const forecastPercent = computed(() => {
  if (latestForecast.value == null) return 0;
  return Math.max(3, (latestForecast.value / forecastMax.value) * 100);
});

async function saveDetail() {
  if (!point.value) return;
  const result = await dashboardApi.updateHazard(point.value.id, form.value);
  // 后端 PUT /hazards/{id} 返回 HazardDto（无 item 包装），直接合并
  point.value = { ...point.value, ...result };
  editMode.value = false;
}

async function refreshDetail() {
  await loadData();
}

/**
 * v1.7.0+ P3-4：跳转到统一事件中心并定位该 hazard 对应的 eventCode。
 * HazardService.toDto 已经反查 unified_event，把 eventCode 放进 HazardDto。
 */
function openEventOverview() {
  if (!point.value) return;
  const code = point.value.eventCode;
  if (!code) {
    emit('notify', '该灾害点尚未关联统一事件');
    return;
  }
  router.push({ path: '/dashboard/events', query: { code } });
}

async function open3DView() {
  if (!point.value) return;
  // 防止快速重复点击导致多个 viewer 并存；async import 期间用户可能再次点击
  if (cesiumBusy.value) return;
  cesiumBusy.value = true;
  cesiumMessage.value = "";
  try {
    if (cesiumCleanup) {
      // 上一个 viewer 先释放，再开新的；确保 destroy 真正完成（destroyCesiumViewer 通常是同步）
      const prev = cesiumCleanup;
      cesiumCleanup = null;
      try { prev(); } catch (_) { /* noop */ }
    }
    const { createCesiumViewer, addCesiumPoint, destroyCesiumViewer } =
      await import("../../services/mapService");
    // 优先用组件内的 <Teleport> 目标，避免挂在 document.body 上导致切页时无法释放
    const host = document.getElementById("cesium-host") || document.body;
    const viewerBundle = await createCesiumViewer(host, {
      center: [Number(point.value.lon), Number(point.value.lat)],
      point: point.value,
    });
    addCesiumPoint(viewerBundle, point.value);
    cesiumCleanup = () => {
      try { destroyCesiumViewer(viewerBundle); } catch (_) { /* noop */ }
    };
    cesiumMessage.value = "三维视角已加载，关闭页面即可退出。";
  } catch (error) {
    cesiumMessage.value =
      error instanceof Error ? error.message : "3D 视角加载失败";
  } finally {
    cesiumBusy.value = false;
  }
}

onMounted(loadData);

// 关键：切页时若仍有 Cesium viewer 挂在 document.body / #cesium-host 上，必须释放，
// 否则浏览器 WebGL context 槽位会逐渐耗尽（每个 viewer ≈ 64MB + 1 context）。
onBeforeUnmount(() => {
  if (cesiumCleanup) {
    try { cesiumCleanup(); } catch (_) { /* noop */ }
    cesiumCleanup = null;
  }
});
</script>

<template>
  <div>
    <!-- P1 统一事件中心：在详情页顶部嵌入全生命周期时间线 -->
    <EventStatusTimeline
      v-if="point"
      :hazard-id="point.id"
      :hazard-code="point.hazardCode ?? point.code"
      @notify="(msg) => $emit('notify', msg)"
    />

    <div v-if="isConfirmationMode" class="confirmation-ops">
      <div class="confirmation-ops-left">
        <ClipboardCheck :size="16" />
        <strong>人工确认工作台</strong>
        <span class="confirmation-badge" :class="confirmationState.status">
          {{ confirmationState.status }}
        </span>
        <span v-if="confirmationState.assignee" class="assignee-tag">
          当前认领人：{{ confirmationState.assignee }}
        </span>
        <span class="ops-hint">← 请先认领再处理，避免多人同时操作</span>
      </div>
      <div class="confirmation-ops-right">
        <button
          v-if="confirmationState.status === '待确认' && !confirmationState.assignee"
          class="ops-btn primary"
          :disabled="confirmationState.loadingClaim"
          @click="claimItem"
        >
          <Check :size="14" />{{ confirmationState.loadingClaim ? '认领中…' : '认领' }}
        </button>
        <button
          v-if="confirmationState.status === '已认领' && confirmationState.assignee === '当前用户'"
          class="ops-btn success"
          :disabled="confirmationState.loadingConfirm"
          @click="confirmItem"
        >
          <Check :size="14" />{{ confirmationState.loadingConfirm ? '确认中…' : '确认为正式灾害' }}
        </button>
        <!-- 创建预警(确认后) -->
        <button
          v-if="confirmationState.status === '已确认'"
          class="ops-btn primary"
          @click="router.push('/dashboard/warnings/create')"
        >
          <Bot :size="14" />创建预警
        </button>
        <button
          v-if="['待确认','已认领'].includes(confirmationState.status)"
          class="ops-btn danger"
          @click="confirmationState.rejectDialog = true"
        >
          <ThumbsDown :size="14" />驳回
        </button>
        <button
          v-if="confirmationState.status === '待确认' || confirmationState.status === '已认领'"
          class="ops-btn"
          @click="confirmationState.transferDialog = true"
        >
          <Send :size="14" />转派
        </button>
      </div>
    </div>

    <!-- 驳回弹窗 -->
    <div v-if="confirmationState.rejectDialog" class="mask" @click.self="confirmationState.rejectDialog = false">
      <div class="modal">
        <h3>驳回 AI 推断</h3>
        <p class="modal-hint">驳回后该事件进入"已驳回"档案，AI 推理结果将反馈到模型用于下次校准。</p>
        <label>驳回原因
          <textarea v-model="confirmationState.rejectReason" placeholder="例如：与现场复核一致、无新增迹象" rows="3"></textarea>
        </label>
        <div class="modal-actions">
          <button class="secondary-btn" @click="confirmationState.rejectDialog = false">取消</button>
          <button class="primary-btn danger" :disabled="confirmationState.loadingReject" @click="rejectItem">
            {{ confirmationState.loadingReject ? '处理中…' : '确认驳回' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 转派弹窗 -->
    <div v-if="confirmationState.transferDialog" class="mask" @click.self="confirmationState.transferDialog = false">
      <div class="modal">
        <h3>转派给专家</h3>
        <label>接收人
          <input v-model="confirmationState.transferTo" placeholder="输入专家姓名或账号" />
        </label>
        <label>转派说明
          <textarea v-model="confirmationState.transferReason" placeholder="例如：需要地质专家判读岩层结构" rows="2"></textarea>
        </label>
        <div class="modal-actions">
          <button class="secondary-btn" @click="confirmationState.transferDialog = false">取消</button>
          <button class="primary-btn" :disabled="confirmationState.loadingTransfer" @click="transferItem">
            {{ confirmationState.loadingTransfer ? '转派中…' : '确认转派' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="point?.eventCode" class="event-overview-banner">
      <div class="event-overview-left">
        <GitBranch :size="16" />
        <span class="event-overview-label">统一事件号</span>
        <code class="event-overview-code">{{ point.eventCode }}</code>
        <span class="event-overview-state" :data-state="point.eventState">
          {{ eventStateLabel(point.eventState) }}
        </span>
      </div>
      <button class="event-overview-btn" @click="openEventOverview" title="在统一事件中心查看血缘、状态机和时间线">
        <GitBranch :size="13" />查看事件全貌
      </button>
    </div>

    <div class="content-grid">
      <section class="panel system-panel">
        <div class="panel-header compact">
          <div>
            <h2>灾害点详情</h2>
            <p>基础档案、编辑与空间信息</p>
          </div>
          <div class="heading-actions">
            <button class="text-btn" @click="router.back()">
              <ArrowLeft :size="15" />返回
            </button>
            <button class="text-btn" @click="refreshDetail">
              <History :size="15" />刷新
            </button>
            <button class="primary-btn" @click="editMode = !editMode">
              <PencilLine :size="15" />{{ editMode ? "取消编辑" : "编辑详情" }}
            </button>
            <button
              class="secondary-btn"
              :disabled="cesiumBusy"
              @click="open3DView"
            >
              <MapPinned :size="15" />{{ cesiumBusy ? "加载中..." : "打开 3D" }}
            </button>
          </div>
        </div>
        <div v-if="loading" class="u-hint-block">
          加载中...
        </div>
        <template v-else-if="point">
          <div class="system-list">
            <div>
              <span><MountainSnow :size="16" />点位名称</span
              ><strong v-if="!editMode">{{ point.name }}</strong
              ><input v-else v-model="form.name" />
            </div>
            <div>
              <span><Layers3 :size="16" />灾害类型</span
              ><strong v-if="!editMode">{{ point.type }}</strong
              ><input v-else v-model="form.type" />
            </div>
            <div>
              <span><MapPin :size="16" />所属辖区</span
              ><strong v-if="!editMode">{{ point.owner }}</strong
              ><input v-else v-model="form.owner" />
            </div>
            <div>
              <span><Gauge :size="16" />当前状态</span
              ><strong v-if="!editMode">{{ point.status }}</strong
              ><input v-else v-model="form.status" />
            </div>
            <div>
              <span><Link2 :size="16" />空间坐标</span
              ><strong v-if="!editMode"
                >{{ point.lon }}, {{ point.lat }}</strong
              >
              <div v-else class="modal-row">
                <input v-model="form.lon" /><input v-model="form.lat" />
              </div>
            </div>
          </div>
          <div class="task-summary">
            <div class="progress-ring">
              <span v-if="latestForecast != null"
                >{{ latestForecast.toFixed(1) }}<small>mm</small></span
              >
              <span v-else class="u-text-3">--</span>
            </div>
            <div class="task-overview">
              <strong v-if="!editMode">{{ point.level }}</strong>
              <select v-else v-model="form.level">
                <option value="高风险">高风险</option>
                <option value="关注">关注</option>
                <option value="正常">正常</option>
              </select>
              <span>空间位置：{{ point.lon }}, {{ point.lat }}</span>
              <div class="task-bars">
                <i
                  v-if="latestForecast != null"
                  :style="{
                    width: forecastPercent + '%',
                    background:
                      latestForecast > 15
                        ? '#e06060'
                        : latestForecast > 12
                          ? '#cbaa76'
                          : '#87b096',
                  }"
                ></i>
                <i v-else style="width: 0%"></i>
              </div>
              <p v-if="latestForecast != null" class="u-text-2">
                AI 预测未来 {{ aiForecast.horizon }} 天位移
                {{ latestForecast.toFixed(1) }} mm
              </p>
              <p v-else class="u-text-clay">
                AI 识别服务不可用，不提供虚拟监测数据
              </p>
            </div>
          </div>

          <!-- AI 综合研判（多维度） -->
          <div v-if="aiComprehensive" class="ai-hazard-card">
            <div class="ai-card-head">
              <div>
                <small><Bot :size="13" />AI 综合风险研判</small>
                <strong :class="`ai-tag ai-tag-${(aiComprehensive.riskLevel || '').toLowerCase()}`">
                  {{ aiLevelLabel(aiComprehensive.riskLevel) }} · {{ Number(aiComprehensive.riskScore || 0).toFixed(1) }} / 100
                </strong>
              </div>
              <span v-if="aiComprehensive.analyzedAt" class="ai-card-time">{{ new Date(aiComprehensive.analyzedAt).toLocaleString('zh-CN', { hour12: false }) }}</span>
            </div>
            <div v-if="aiComprehensive.dimensions" class="ai-hazard-dims">
              <div class="ai-dim-cell">
                <small>图像现况</small>
                <strong :style="{ color: aiLevelClass(aiComprehensive.dimensions.image?.level) === 'orange' ? 'var(--c-warning-strong)' : aiLevelClass(aiComprehensive.dimensions.image?.level) === 'yellow' ? '#bda06a' : '#1c1c1e' }">
                  {{ aiLevelLabel(aiComprehensive.dimensions.image?.level) }} {{ Number(aiComprehensive.dimensions.image?.score ?? 0).toFixed(0) }}
                </strong>
              </div>
              <div class="ai-dim-cell">
                <small>当地降水量</small>
                <strong :style="{ color: aiLevelClass(aiComprehensive.dimensions.precipitation?.level) === 'orange' ? 'var(--c-warning-strong)' : aiLevelClass(aiComprehensive.dimensions.precipitation?.level) === 'yellow' ? '#bda06a' : '#1c1c1e' }">
                  {{ aiLevelLabel(aiComprehensive.dimensions.precipitation?.level) }} {{ Number(aiComprehensive.dimensions.precipitation?.score ?? 0).toFixed(0) }}
                </strong>
              </div>
              <div class="ai-dim-cell">
                <small>当地地理情况</small>
                <strong :style="{ color: aiLevelClass(aiComprehensive.dimensions.geographic?.level) === 'orange' ? 'var(--c-warning-strong)' : aiLevelClass(aiComprehensive.dimensions.geographic?.level) === 'yellow' ? '#bda06a' : '#1c1c1e' }">
                  {{ aiLevelLabel(aiComprehensive.dimensions.geographic?.level) }} {{ Number(aiComprehensive.dimensions.geographic?.score ?? 0).toFixed(0) }}
                </strong>
              </div>
              <div class="ai-dim-cell">
                <small>设备指标</small>
                <strong :style="{ color: aiLevelClass(aiComprehensive.dimensions.device?.level) === 'orange' ? 'var(--c-warning-strong)' : aiLevelClass(aiComprehensive.dimensions.device?.level) === 'yellow' ? '#bda06a' : '#1c1c1e' }">
                  {{ aiLevelLabel(aiComprehensive.dimensions.device?.level) }} {{ Number(aiComprehensive.dimensions.device?.score ?? 0).toFixed(0) }}
                </strong>
              </div>
            </div>
            <div v-if="aiComprehensive.reasoning?.length" class="ai-hazard-reasons">
              <small>关键归因：</small>
              <ul>
                <li v-for="(r, i) in aiComprehensive.reasoning.slice(0, 4)" :key="i">{{ r }}</li>
              </ul>
            </div>
            <div v-if="aiComprehensive.suggestedActions?.length" class="ai-hazard-actions">
              <small>建议动作：</small>
              <span v-for="(a, i) in aiComprehensive.suggestedActions.slice(0, 4)" :key="i" class="ai-action-pill">{{ a }}</span>
            </div>
            <div class="heading-actions" style="padding: 6px 0 0; justify-content: flex-end; gap: 6px;">
              <span v-if="aiComprehensive.model" class="u-meta">模型：{{ aiComprehensive.model }} · 置信度 {{ ((aiComprehensive.confidence ?? 0) * 100).toFixed(0) }}%</span>
              <PermissionButton permission="ai:insight:refresh" :icon="BrainCircuit" @click="async ({ allowed, reason }) => {
                if (!allowed) return
                await refreshAiComprehensive()
              }">立即重算</PermissionButton>
            </div>
          </div>
          <div v-else-if="aiComprehensiveLoading" class="ai-hazard-card ai-hazard-card--hint">AI 综合研判加载中...</div>
          <div v-else class="ai-hazard-card ai-hazard-card--hint-3">
            等待 AI 综合研判（5 分钟内自动更新，<a href="javascript:void(0)" @click="refreshAiComprehensive" class="u-link-inline">立即重算</a>）
          </div>

          <!-- AI 预测趋势 -->
          <div class="chart-card" style="margin: 0 20px 18px">
            <div class="mini-chart-header">
              <strong
                ><TrendingUp :size="16" />AI 位移趋势预测（未来 7 天）</strong
              >
              <span v-if="aiForecast?.mock" class="u-text-warn"
                >启发式降级</span
              >
              <span v-else-if="aiForecast" class="u-text-brand">{{
                aiForecast.model
              }}</span>
            </div>
            <div v-if="aiForecastLoading" class="u-hint-box">
              AI 预测中...
            </div>
            <div v-else-if="aiForecast" style="padding: 8px 12px">
              <div
                style="
                  display: flex;
                  align-items: flex-end;
                  gap: 8px;
                  height: 80px;
                "
              >
                <div
                  v-for="(val, i) in aiForecast.predictions"
                  :key="i"
                  style="
                    flex: 1;
                    display: flex;
                    flex-direction: column;
                    align-items: center;
                    gap: 2px;
                  "
                >
                  <span class="u-hint-10">{{
                    val.toFixed(1)
                  }}</span>
                  <div
                    style="
                      width: 100%;
                      background: #87b096;
                      border-radius: 3px 3px 0 0;
                      min-height: 3px;
                    "
                    :style="{
                      height:
                        (val / forecastMax) * 70 + 'px',
                      background:
                        val > 15 ? '#e06060' : val > 12 ? '#cbaa76' : '#87b096',
                    }"
                  ></div>
                  <span class="u-hint-9"
                    >D{{ i + 1 }}</span
                  >
                </div>
              </div>
              <div
                style="
                  display: flex;
                  justify-content: space-between;
                  margin-top: 6px;
                  font-size: 11px;
                  color: #76847a;
                "
              >
                <span
                  >置信度 {{ (aiForecast.confidence * 100).toFixed(0) }}%</span
                >
                <span
                  >metric={{ aiForecast.metric }}, horizon={{
                    aiForecast.horizon
                  }}</span
                >
              </div>
            </div>
            <div v-else class="u-hint-box">
              AI 服务不可用，已跳过预测
            </div>
          </div>

          <p v-if="cesiumMessage" class="weather-error" style="padding: 0 20px">
            {{ cesiumMessage }}
          </p>
          <div
            class="heading-actions"
            style="padding: 0 20px 18px; justify-content: flex-end"
          >
            <PermissionButton
              permission="hazards:edit"
              :icon="Save"
              @click="
                async ({ allowed, reason }) => {
                  if (!allowed) return;
                  try {
                    await saveDetail();
                  } catch (error) {
                    emit('notify', error instanceof Error ? error.message : (reason ?? '保存失败'));
                  }
                }
              "
              >保存</PermissionButton
            >
          </div>
        </template>
      </section>

      <section class="panel warning-panel">
        <div class="panel-header compact">
          <div>
            <h2>历史记录与附件</h2>
            <p>查看完整历史轨迹、附件与联动信息</p>
          </div>
        </div>
        <div v-if="point" class="warning-list">
          <article
            v-for="record in point.history"
            :key="`${record.time}-${record.title}`"
            class="warning-item"
          >
            <div class="warning-symbol orange">H</div>
            <div class="warning-body">
              <div class="warning-title">
                <strong>{{ record.title }}</strong
                ><span class="orange">{{ record.time }}</span>
              </div>
              <p>{{ record.desc }}</p>
            </div>
          </article>
        </div>
        <div v-if="point" class="chart-card">
          <div class="mini-chart-header">
            <strong><Paperclip :size="16" />附件</strong>
            <span>{{ point.attachments.length }} 个文件</span>
          </div>
          <div class="pie-list">
            <div
              v-for="file in point.attachments"
              :key="file.name"
              class="pie-item"
            >
              <span>{{ file.name }}</span>
              <div class="pie-track">
                <i class="u-bar-brand"></i>
              </div>
              <strong>{{ file.size }}</strong>
            </div>
          </div>
        </div>
        <div v-if="point" class="chart-card">
          <div class="mini-chart-header">
            <strong><GitBranch :size="16" />关联联动</strong>
            <span>点击跳转 · 设备 / 任务 / 预警</span>
          </div>
          <div v-if="point.linked?.length" class="pie-list">
            <RouterLink
              v-for="item in point.linked"
              :key="`${item.type}-${item.label}`"
              :to="item.path"
              class="pie-item link-item"
            >
              <span class="link-type">{{ item.type }}</span>
              <span class="link-label">{{ item.label }}</span>
              <div class="pie-track">
                <i class="u-bar-brand-hover"></i>
              </div>
              <strong class="link-go">查看 ›</strong>
            </RouterLink>
          </div>
          <p v-else class="people-empty" style="padding: 12px">暂无关联记录</p>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
/* v1.7.0 人工确认模式操作栏 */
.confirmation-ops {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 10px;
  padding: 10px 16px;
  background: linear-gradient(135deg, var(--c-success-bg), var(--c-success-bg));
  border: 1px solid color-mix(in srgb, var(--c-success) 45%, transparent);
  border-radius: var(--r-sm);
  margin-bottom: 12px;
  font-size: 13px;
}
.confirmation-ops-left {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.confirmation-ops-left strong { color: var(--c-success-strong); font-size: 14px; }
.assignment-badge { color: var(--c-success-strong); }
.assignee-tag { font-size: 12px; color: var(--c-success); background: color-mix(in srgb, var(--c-success-strong) 10%, transparent); padding: 2px 8px; border-radius: var(--r-xs); }
.ops-hint { font-size: 11.5px; color: var(--c-text-2); }

.confirmation-badge {
  padding: 2px 10px;
  border-radius: var(--r-lg);
  font-size: 11.5px;
  font-weight: 700;
  border: 1px solid;
}
.confirmation-badge.待确认 { color: var(--c-warning-ink); border-color: color-mix(in srgb, var(--c-warning) 40%, transparent); background: var(--c-warning-bg); }
.confirmation-badge.已认领   { color: var(--c-brand-deeper); border-color: color-mix(in srgb, var(--sys-blue) 35%, transparent); background: var(--sys-blue-soft); }
.confirmation-badge.已确认   { color: var(--c-success-strong); border-color: color-mix(in srgb, var(--c-success) 40%, transparent); background: var(--c-success-bg); }
.confirmation-badge.已驳回   { color: var(--c-text-2); border-color: var(--c-separator); background: var(--c-surface-2); }
.confirmation-badge.已转派   { color: #7c3aed; border-color: #ddd6fe; background: #f5f3ff; }

.confirmation-ops-right {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.ops-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 12px;
  border-radius: var(--r-sm);
  border: 1px solid var(--c-border);
  background: var(--c-surface);
  color: var(--c-ink-1);
  font-size: 12.5px;
  font-weight: 500;
  cursor: pointer;
  transition: all 0.15s;
}
.ops-btn:hover { background: var(--c-surface-2); border-color: var(--c-text-3); }
.ops-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.ops-btn.primary { background: var(--c-brand-deeper); color: var(--c-on-brand); border-color: var(--c-brand-deeper); }
.ops-btn.primary:hover { background: var(--sys-blue-deep); }
.ops-btn.success { background: var(--c-success-strong); color: var(--c-on-brand); border-color: var(--c-success-strong); }
.ops-btn.success:hover { background: var(--c-success-strong); }
.ops-btn.danger { background: var(--c-danger-strong); color: var(--c-on-brand); border-color: var(--c-danger-strong); }
.ops-btn.danger:hover { background: var(--c-danger-strong); }

/* 弹窗遮罩 */
.mask {
  position: fixed; inset: 0;
  background: var(--c-scrim); /* 原 rgba(0,0,0,.4) 遮罩 */
  display: flex; align-items: center; justify-content: center;
  z-index: 9999;
}
.modal {
  background: var(--c-surface);
  border-radius: var(--r-lg);
  padding: 24px;
  width: min(480px, 92vw);
  box-shadow: 0 20px 60px rgba(0,0,0,0.2);
}
.modal h3 { margin: 0 0 8px; font-size: 16px; color: var(--c-ink-1); }
.modal-hint { font-size: 12.5px; color: var(--c-text-2); margin-bottom: 12px; }
.modal label {
  display: block;
  font-size: 12.5px;
  color: var(--c-ink-1);
  font-weight: 500;
  margin-bottom: 10px;
}
.modal label textarea, .modal label input {
  width: 100%;
  margin-top: 4px;
  padding: 7px 10px;
  border: 1px solid var(--c-border);
  border-radius: var(--r-sm);
  font-size: 13px;
  box-sizing: border-box;
}
.modal-actions {
  display: flex; gap: 8px; justify-content: flex-end;
  margin-top: 16px;
}

/* v1.7.0+ P3-4：统一事件号快速跳转条 */
.event-overview-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 12px 0 0;
  padding: 10px 16px;
  border-radius: var(--r-md);
  background: linear-gradient(135deg, rgba(79, 143, 153, 0.08), rgba(99, 143, 194, 0.05));
  border: 1px solid rgba(79, 143, 153, 0.25);
  gap: 12px;
  flex-wrap: wrap;
}
.event-overview-left {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.event-overview-label {
  font-size: 12px;
  color: var(--c-ink-2);
  font-weight: 600;
}
.event-overview-code {
  font-family: ui-monospace, monospace;
  background: rgba(111, 158, 127, 0.1);
  color: var(--c-brand);
  padding: 3px 8px;
  border-radius: var(--r-xs);
  font-size: 12px;
  font-weight: 700;
}
.event-overview-state {
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: var(--r-pill);
  background: rgba(155, 174, 173, 0.18);
  color: var(--c-ink-2);
  letter-spacing: 0.3px;
}
.event-overview-state[data-state="DETECTED"]          { background: rgba(79, 143, 153, 0.18); color: var(--c-brand); }
.event-overview-state[data-state="CONFIRMING"]        { background: rgba(224, 160, 80, 0.20); color: var(--c-warning-ink); }
.event-overview-state[data-state="CONFIRMED"]         { background: rgba(99, 143, 194, 0.18); color: var(--c-brand); }
.event-overview-state[data-state="WARNING_ISSUED"]    { background: rgba(216, 133, 97, 0.20); color: var(--c-danger-strong); }
.event-overview-state[data-state="EMERGENCY_ACTIVE"]  { background: rgba(224, 96, 96, 0.18); color: var(--c-danger); }
.event-overview-state[data-state="CLOSED"]            { background: rgba(155, 174, 173, 0.25); color: var(--c-ink-2); }
.event-overview-state[data-state="REJECTED"]          { background: rgba(122, 141, 137, 0.22); color: var(--c-text-2); }
.event-overview-btn {
  background: var(--c-surface);
  border: 1px solid rgba(111, 158, 127, 0.4);
  color: var(--c-brand);
  padding: 6px 14px;
  border-radius: var(--r-sm);
  cursor: pointer;
  font-size: 12px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  transition: all 0.15s;
}
.event-overview-btn:hover {
  background: var(--c-brand);
  color: var(--c-on-brand);
  border-color: var(--c-brand);
}
/* ── 静态内联色收敛后的工具类（等价于原先的 inline style） ── */
.u-hint-block { padding: 0 20px 18px; color: var(--c-text-2); }
.u-hint-box { padding: 12px; color: var(--c-text-2); }
.u-hint-10 { font-size: 10px; color: var(--c-text-2); }
.u-hint-9 { font-size: 9px; color: var(--c-text-2); }
.u-meta { font-size: 10px; color: var(--c-text-3); }
.u-text-2 { color: var(--c-text-2); }
.u-text-3 { color: var(--c-text-3); }
.u-text-brand { color: var(--c-brand-hover); }
.u-text-warn { color: var(--c-warning); }
.u-text-clay { color: var(--c-warning-strong); }
.u-link-inline { color: var(--c-brand-deep); text-decoration: underline; }
.u-bar-brand { width: 100%; background: var(--c-brand); }
.u-bar-brand-hover { width: 100%; background: var(--c-brand-hover); }
.u-flex-hint { display: flex; justify-content: space-between; margin-top: 6px; font-size: 11px; color: var(--c-text-2); }
.ai-hazard-card--hint { text-align: center; color: var(--c-text-2); font-size: 12px; }
.ai-hazard-card--hint-3 { text-align: center; color: var(--c-text-3); font-size: 11px; }

</style>
