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
  PencilLine,
  Save,
  ShieldCheck,
  Paperclip,
  Bot,
  AlertCircle,
} from "lucide-vue-next";
import PermissionButton from "../../components/PermissionButton.vue";
import WarningDeliveryTab from "../../components/WarningDeliveryTab.vue";
import WarningResolveTab from "../../components/WarningResolveTab.vue";
import { dashboardApi } from "../../services/dashboardApi";
import { aiApi } from "../../services/aiApi";
import { useAiContext } from "../../composables/useAiContext";

const route = useRoute();
const router = useRouter();
const warning = ref(null);
const loading = ref(true);
const notFound = ref(false);
const editMode = ref(false);
const form = ref({});
const aiRisk = ref(null);
const aiRiskLoading = ref(false);
const activeTab = ref("info");
const deliveryStats = ref({ delivered: 0, shouldDeliver: 0 });
const isResolved = ref(false);

const { setAiContext, clearAiContext } = useAiContext();

// 计算派发确认的警告状态（送达率未达 100% 时显示红点）
const deliveryWarning = computed(() => {
  if (deliveryStats.value.shouldDeliver === 0) return false;
  const rate = (deliveryStats.value.delivered / deliveryStats.value.shouldDeliver) * 100;
  return rate < 100;
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

async function handleResolveSuccess() {
  await loadData();
}

watch(() => route.query.tab, (newTab) => {
  if (!newTab) {
    activeTab.value = "info";
    return;
  }
  if (!warning.value) {
    activeTab.value = "info";
    return;
  }
  if (["info", "delivery", "resolve"].includes(newTab)) {
    activeTab.value = newTab;
  }
}, { immediate: true });

watch(() => warning.value, (newWarning) => {
  if (newWarning && route.query.tab && ["info", "delivery", "resolve"].includes(route.query.tab)) {
    activeTab.value = route.query.tab;
  }
});

onMounted(loadData);

onBeforeUnmount(() => {
  clearAiContext();
});
</script>

<style scoped>
.error-block {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  padding: 40px;
  background: var(--c-danger-bg);
  border: 1px dashed var(--c-separator);
  border-radius: var(--r-lg);
  color: var(--c-danger-strong);
  text-align: center;
}
.error-block strong { font-size: 16px; }
.error-block p { margin: 0; color: var(--c-danger-strong); font-size: 13px; }

.tab-nav {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 0 0 14px;
  border-bottom: 1px solid var(--c-border-2);
  margin-bottom: 16px;
  flex-wrap: wrap;
}

.tab-button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border: none;
  background: transparent;
  color: var(--c-text-2);
  font-size: 13px;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  transition: color .2s, border-color .2s;
  position: relative;
}

.tab-button:hover {
  color: var(--c-text);
}

.tab-button.active {
  color: var(--c-brand);
  border-bottom-color: var(--c-brand);
  font-weight: 600;
}

.tab-button.disabled {
  opacity: 0.5;
  cursor: not-allowed;
  color: var(--c-text-3);
}

.tab-button.disabled:hover {
  color: var(--c-text-3);
}

.tab-warning {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  background: var(--c-danger);
  color: white;
  border-radius: 50%;
  font-size: 10px;
  font-weight: 700;
  margin-left: 2px;
}

.tab-spacer {
  flex: 1;
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
        <div class="tab-nav">
          <button
            class="tab-button"
            :class="{ active: activeTab === 'info' }"
            @click="activeTab = 'info'"
          >
            <FileText :size="14" />基本信息
          </button>
          <button
            class="tab-button"
            :class="{ active: activeTab === 'delivery' }"
            @click="activeTab = 'delivery'"
          >
            <Bell :size="14" />派发确认
            <span v-if="deliveryWarning" class="tab-warning" title="送达率未达 100%">!</span>
          </button>
          <button
            class="tab-button"
            :class="{ active: activeTab === 'resolve', disabled: isResolved }"
            :disabled="isResolved"
            @click="activeTab = 'resolve'"
            :title="isResolved ? '该预警已解除，无法再次解除' : ''"
          >
            <ShieldCheck :size="14" />解除审批
          </button>
          <div class="tab-spacer"></div>
          <button class="text-btn" @click="router.back()">
            <ArrowLeft :size="15" />返回
          </button>
          <button class="text-btn" @click="refreshDetail">
            <History :size="15" />刷新
          </button>
        </div>

        <template v-if="activeTab === 'info'">
          <section class="panel system-panel">
            <div class="panel-header compact">
              <div>
                <h2>预警详情</h2>
                <p>预警等级、编辑与处置状态</p>
              </div>
              <div class="heading-actions">
                <button class="primary-btn" @click="editMode = !editMode">
                  <PencilLine :size="15" />{{ editMode ? "取消编辑" : "编辑详情" }}
                </button>
              </div>
            </div>

            <div class="system-list">
              <div>
                <span><Bell :size="16" />预警等级</span
                ><strong v-if="!editMode">{{ warning.level }}</strong
                ><select v-else v-model="form.level">
                  <option>橙色预警</option>
                  <option>黄色预警</option>
                  <option>蓝色提醒</option>
                </select>
              </div>
              <div>
                <span><Layers :size="16" />触发点位</span
                ><strong>{{ warning.point }}</strong>
              </div>
              <div>
                <span><MapPin :size="16" />触发时间</span
                ><strong>{{ warning.time }}</strong>
              </div>
              <div>
                <span><ShieldCheck :size="16" />当前状态</span
                ><strong v-if="!editMode">{{ warning.status }}</strong
                ><select v-else v-model="form.status">
                  <option>待确认</option>
                  <option>已确认</option>
                  <option>处理中</option>
                </select>
              </div>
              <div v-if="warning.closeReason && !editMode">
                <span><GitBranch :size="16" />关闭原因</span
                ><strong>{{ warning.closeReason }}</strong>
              </div>
            </div>

            <div class="chart-card" style="margin: 0 20px 18px">
              <div class="mini-chart-header">
                <strong><Bot :size="16" />AI 融合风险评分</strong>
                <span v-if="aiRisk?.mock" class="u-text-warn">启发式降级</span>
                <span v-else-if="aiRisk" class="u-text-brand">{{ aiRisk.model }}</span>
              </div>
              <div v-if="aiRiskLoading" class="u-hint-box">
                AI 分析中...
              </div>
              <div v-else-if="aiRisk" style="padding: 8px 12px">
                <div style="display: flex; align-items: center; gap: 16px">
                  <div class="progress-ring" style="width: 54px; height: 54px; font-size: 18px">
                    <span
                      :style="{
                        color: aiRisk.risk_score >= 60 ? '#e06060' : aiRisk.risk_score >= 35 ? '#cbaa76' : '#87b096',
                      }"
                    >
                      {{ aiRisk.risk_score }}<small>%</small>
                    </span>
                  </div>
                  <div style="flex: 1">
                    <div style="display: flex; justify-content: space-between; margin-bottom: 4px">
                      <strong :style="{ color: riskLevelColor(aiRisk.risk_level) }">
                        {{ riskLevelLabel(aiRisk.risk_level) }}
                      </strong>
                      <span class="u-hint-12">置信度 {{ (aiRisk.confidence * 100).toFixed(0) }}%</span>
                    </div>
                    <div class="task-bars">
                      <i
                        :style="{
                          width: aiRisk.risk_score + '%',
                          background: riskLevelColor(aiRisk.risk_level),
                        }"
                      ></i>
                    </div>
                  </div>
                </div>
              </div>
              <div v-else class="u-hint-box">
                AI 服务不可用，已跳过评分
              </div>
            </div>

            <div class="task-summary">
              <div class="progress-ring">
                <span :style="aiRisk ? { color: riskLevelColor(aiRisk.risk_level) } : {}">
                  {{ aiRisk ? aiRisk.risk_score : "--" }}<small>%</small>
                </span>
              </div>
              <div class="task-overview">
                <strong v-if="!editMode">{{ warning.title }}</strong>
                <input v-else v-model="form.title" />
                <span v-if="!editMode">{{ warning.desc }}</span>
                <span v-else class="u-text-2">{{ warning.desc }}</span>
                <div class="task-bars">
                  <i v-if="aiRisk" :style="{ width: aiRisk.risk_score + '%', background: riskLevelColor(aiRisk.risk_level) }"></i>
                  <i v-else style="width: 0%"></i>
                </div>
                <p v-if="aiRisk" class="u-text-2">
                  AI 融合评分：{{ riskLevelLabel(aiRisk.risk_level) }}（置信度 {{ (aiRisk.confidence * 100).toFixed(0) }}%）
                </p>
                <p v-else class="u-text-clay">
                  AI 识别服务不可用，未生成风险评分（无虚拟数据）
                </p>
              </div>
            </div>
            <div class="heading-actions" style="padding: 0 20px 18px; justify-content: flex-end">
              <PermissionButton
                permission="warnings:confirm"
                :icon="Save"
                @click="async ({ allowed }) => { if (!allowed) return; await saveDetail(); }"
              >
                保存
              </PermissionButton>
            </div>
          </section>

          <section class="panel warning-panel">
            <div class="panel-header compact">
              <div>
                <h2>处置记录</h2>
                <p>预警闭环处理轨迹、附件与联动</p>
              </div>
            </div>
            <div class="warning-list">
              <article v-for="record in warning.history" :key="`${record.time}-${record.title}`" class="warning-item">
                <div class="warning-symbol orange">W</div>
                <div class="warning-body">
                  <div class="warning-title">
                    <strong>{{ record.title }}</strong
                    ><span class="orange">{{ record.time }}</span>
                  </div>
                  <p>{{ record.desc }}</p>
                </div>
              </article>
            </div>
            <div class="chart-card">
              <div class="mini-chart-header">
                <strong><Paperclip :size="16" />附件</strong>
                <span>预警材料</span>
              </div>
              <div class="pie-list">
                <div v-for="file in warning.attachments" :key="file.name" class="pie-item">
                  <span>{{ file.name }}</span>
                  <div class="pie-track">
                    <i class="u-bar-brand"></i>
                  </div>
                  <strong>{{ file.size }}</strong>
                </div>
              </div>
            </div>
            <div class="chart-card">
              <div class="mini-chart-header">
                <strong><GitBranch :size="16" />关联联动</strong>
                <span>点击跳转 · 灾害点 / 设备 / 任务 / 应急</span>
              </div>
              <div v-if="warning.linked?.length" class="pie-list">
                <RouterLink v-for="item in warning.linked" :key="`${item.type}-${item.label}`" :to="item.path" class="pie-item link-item">
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
        </template>

        <template v-if="activeTab === 'delivery'">
          <WarningDeliveryTab
            :warning-id="route.params.id"
            @refresh="handleDeliveryRefresh"
            @notify="() => {}"
          />
        </template>

        <template v-if="activeTab === 'resolve'">
          <WarningResolveTab
            :warning-id="route.params.id"
            @success="handleResolveSuccess"
            @notify="() => {}"
          />
        </template>
      </div>
    </template>
  </div>
</template>
