<script setup>
/**
 * AI 灾害评估页面（/dashboard/ai-insight）- Tab 容器版本
 *
 * v2.0.0 起改为 Tab 架构：
 * - Tab 1: AiComprehensiveTab（综合研判）
 * - Tab 2: AiForecastTab（趋势预测）
 * 
 * 本页面职责：
 * - 全局灾害点选择 & 加载
 * - Tab 切换管理
 * - 状态同步（防止死循环）
 */
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import ConfirmDialog from '../../components/common/ConfirmDialog.vue'
import AiComprehensiveTab from '../../components/ai/AiComprehensiveTab.vue'
import AiForecastTab from '../../components/ai/AiForecastTab.vue'
import AiHealthBadge from '../../components/AiHealthBadge.vue'
import {
  ChevronRight,
  Loader2,
  MapPin,
  RefreshCcw,
  XCircle,
  Brain,
  TrendingUp,
} from 'lucide-vue-next'
import { aiApi } from '../../services/aiApi'
import { dashboardApi } from '../../services/dashboardApi'

const props = defineProps({
  currentTime: { type: String, default: '' },
  userRole: { type: String, default: 'admin' },
})

const emit = defineEmits(['notify'])
const confirmDialogRef = ref(null)

// 灾害点列表
const hazards = ref([])
const selectedCode = ref('')
const hazardsLoading = ref(false)
const hazardsError = ref('')
const batchLoading = ref(false)

// Tab 管理
const activeTab = ref('comprehensive') // comprehensive | forecast
const tabOptions = [
  { value: 'comprehensive', label: '综合研判', icon: Brain },
  { value: 'forecast', label: '趋势预测', icon: TrendingUp },
]

// 加载灾害点列表
async function loadHazards() {
  hazardsLoading.value = true
  hazardsError.value = ''
  try {
    const resp = await dashboardApi.getHazards({ pageSize: 200 })
    hazards.value = resp.list ?? []
    
    // 如果还未选择灾害点，自动选择第一个
    if (hazards.value.length > 0 && !selectedCode.value) {
      selectedCode.value = hazards.value[0].hazardCode
    }
  } catch (e) {
    hazardsError.value = '灾害点加载失败：' + (e?.message || '后端不可达')
    hazards.value = []
  } finally {
    hazardsLoading.value = false
  }
}

// 灾害点选择（防抖，避免死循环）
let lastSelectedCode = null
function selectHazard(code) {
  // 防止快速重复选择同一灾害点
  if (code === lastSelectedCode) return
  lastSelectedCode = code
  selectedCode.value = code
}

// 批量分析所有灾害点
async function loadAllReports() {
  batchLoading.value = true
  try {
    for (const h of hazards.value) {
      selectedCode.value = h.hazardCode
      try {
        await aiApi.disasterAssessment(h.hazardCode)
      } catch (_e) {
        // 继续处理下一个
      }
    }
    emit('notify', '所有灾害点已分析完成')
  } finally {
    batchLoading.value = false
  }
}

// 获取选中的灾害点信息
const selectedHazard = computed(() =>
  hazards.value.find((h) => h.hazardCode === selectedCode.value)
)

// 页面初始化
onMounted(() => {
  loadHazards()
})

onBeforeUnmount(() => {
  // 清理资源（如需要）
})

// 处理子组件的发布预警事件
function handlePublishWarning(warningData) {
  emit('notify', '预警已发布')
}

// 处理子组件的通知事件
function handleNotify(message) {
  emit('notify', message)
}

function fmtTime(iso) {
  if (!iso) return '--'
  try {
    return new Date(iso).toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return '--'
  }
}
</script>

<template>
  <div class="ai-insight-page">
    <!-- 页面顶部：标题 + 一键分析 -->
    <div class="page-head">
      <div class="page-head-info">
        <h1>AI 灾害评估</h1>
        <p>综合地理、气象、设备数据进行多维度灾害风险评估</p>
      </div>
      <div class="page-head-actions">
        <AiHealthBadge />
        <button class="refresh-btn" :disabled="batchLoading || hazardsLoading" @click="loadAllReports">
          <Loader2 v-if="batchLoading" size="14" class="spin-icon" />
          <RefreshCcw v-else size="14" />
          {{ batchLoading ? '正在逐点分析…' : '一键分析全部灾害点' }}
        </button>
      </div>
    </div>

    <!-- 主布局：左侧灾害点列表 + 右侧 Tab 面板 -->
    <div class="ai-layout">
      <!-- 左侧：灾害点列表 -->
      <aside class="hazard-list-panel">
        <div class="panel-header">
          <div>
            <h2>灾害点清单</h2>
            <p>共 {{ hazards.length }} 个监测点</p>
          </div>
        </div>

        <!-- 加载中 -->
        <div v-if="hazardsLoading" class="list-loading">
          <Loader2 class="spin-icon" size="20" />
          <span>加载中...</span>
        </div>

        <!-- 加载失败 -->
        <div v-else-if="hazardsError" class="list-error">
          <XCircle size="16" />
          <p>{{ hazardsError }}</p>
          <button class="retry-btn" @click="loadHazards">重试</button>
        </div>

        <!-- 灾害点列表 -->
        <div v-else class="hazard-list">
          <button
            v-for="h in hazards"
            :key="h.hazardCode"
            class="hazard-row"
            :class="{ active: h.hazardCode === selectedCode }"
            @click="selectHazard(h.hazardCode)"
          >
            <div class="hazard-row-main">
              <strong>{{ h.name }}</strong>
              <span class="hazard-type">{{ h.type }} · {{ h.level }}</span>
              <small>
                <MapPin size="11" />{{ h.owner }} ·
                <span class="hazard-status">{{ h.status }}</span>
              </small>
            </div>
            <ChevronRight size="14" class="hazard-row-arrow" />
          </button>
        </div>
      </aside>

      <!-- 右侧：Tab 面板 -->
      <section class="report-panel">
        <!-- 页面头部：灾害点标题 + Tab 切换 -->
        <div class="report-head">
          <div class="report-head-info">
            <h2>{{ selectedHazard?.name || '请选择灾害点' }}</h2>
            <p>
              编码 <code>{{ selectedCode }}</code>
              <span v-if="selectedHazard?.updatedAt">
                · 更新时间 {{ fmtTime(selectedHazard.updatedAt) }}
              </span>
            </p>
          </div>

          <!-- Tab 切换器 -->
          <div class="tabs">
            <button
              v-for="tab in tabOptions"
              :key="tab.value"
              class="tab-btn"
              :class="{ active: activeTab === tab.value }"
              @click="activeTab = tab.value"
            >
              <component :is="tab.icon" size="16" />
              {{ tab.label }}
            </button>
          </div>
        </div>

        <!-- Tab 内容区域 -->
        <div class="tabs-content">
          <!-- 综合研判 Tab -->
          <AiComprehensiveTab
            v-show="activeTab === 'comprehensive'"
            :selected-hazard-code="selectedCode"
            :selected-hazard-info="selectedHazard"
            @publish-warning="handlePublishWarning"
            @notify="handleNotify"
          />

          <!-- 趋势预测 Tab -->
          <AiForecastTab
            v-show="activeTab === 'forecast'"
            :selected-hazard-code="selectedCode"
            @notify="handleNotify"
          />
        </div>
      </section>
    </div>

    <ConfirmDialog ref="confirmDialogRef" />
  </div>
</template>

<style scoped>
.ai-insight-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: var(--c-bg);
}

/* 页面顶部 */
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  border-bottom: 1px solid var(--c-separator);
  background: var(--c-surface);
}

.page-head-info h1 {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 700;
  color: var(--c-text);
}

.page-head-info p {
  margin: 0;
  font-size: 12px;
  color: var(--c-text-3);
}

.page-head-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}

.refresh-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  border: 1px solid var(--c-border);
  background: var(--c-surface);
  border-radius: var(--r-sm);
  font-size: 12px;
  font-weight: 500;
  color: var(--c-text);
  cursor: pointer;
  transition: all 0.2s;
}

.refresh-btn:hover:not(:disabled) {
  background: var(--c-surface-2);
  border-color: var(--c-border);
}

.refresh-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.spin-icon {
  animation: spin 1s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* 主布局 */
.ai-layout {
  display: grid;
  grid-template-columns: 240px 1fr;
  gap: 0;
  flex: 1;
  overflow: hidden;
}

/* 左侧：灾害点列表 */
.hazard-list-panel {
  display: flex;
  flex-direction: column;
  border-right: 1px solid var(--c-separator);
  background: var(--c-surface);
  overflow: hidden;
}

.panel-header {
  padding: 12px 14px;
  border-bottom: 1px solid var(--c-separator);
  flex-shrink: 0;
}

.panel-header h2 {
  margin: 0 0 2px;
  font-size: 13px;
  font-weight: 600;
  color: var(--c-text);
}

.panel-header p {
  margin: 0;
  font-size: 11px;
  color: var(--c-text-3);
}

.list-loading,
.list-error {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 40px 20px;
  color: var(--c-text-2);
  text-align: center;
  font-size: 12px;
}

.list-error {
  color: var(--c-danger-strong);
}

.retry-btn {
  margin-top: 8px;
  padding: 4px 10px;
  border: 1px solid var(--c-danger-soft);
  background: var(--c-surface);
  border-radius: var(--r-xs);
  font-size: 11px;
  color: var(--c-danger-strong);
  cursor: pointer;
}

.retry-btn:hover {
  background: var(--c-danger-bg);
}

.hazard-list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px;
}

.hazard-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px;
  border: 1px solid transparent;
  background: transparent;
  border-radius: var(--r-sm);
  cursor: pointer;
  transition: all 0.2s;
  text-align: left;
  color: var(--c-text);
  font-size: 12px;
  flex: 1;
  min-width: 0;
}

.hazard-row:hover {
  background: var(--c-surface-2);
}

.hazard-row.active {
  background: var(--c-brand-light);
  border-color: var(--c-brand);
  color: var(--c-text);
}

.hazard-row-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.hazard-row-main strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-weight: 600;
  font-size: 12px;
}

.hazard-type {
  font-size: 10px;
  color: var(--c-text-3);
}

.hazard-row-main small {
  font-size: 9px;
  color: var(--c-text-3);
  display: flex;
  align-items: center;
  gap: 2px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.hazard-status {
  padding: 1px 4px;
  background: var(--c-brand-soft);
  border-radius: 2px;
  font-weight: 500;
}

.hazard-row-arrow {
  flex-shrink: 0;
  color: var(--c-text-3);
  margin-left: 4px;
}

/* 右侧：报告面板 */
.report-panel {
  display: flex;
  flex-direction: column;
  background: var(--c-bg);
  overflow: hidden;
}

.report-head {
  padding: 16px 20px;
  border-bottom: 1px solid var(--c-separator);
  background: var(--c-surface);
  flex-shrink: 0;
}

.report-head-info h2 {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 700;
  color: var(--c-text);
}

.report-head-info p {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--c-text-3);
}

.report-head-info code {
  padding: 2px 6px;
  background: var(--c-surface-2);
  border-radius: 3px;
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  color: var(--c-brand);
}

/* Tab 切换器 */
.tabs {
  display: flex;
  gap: 2px;
}

.tab-btn {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 12px;
  border: 1px solid transparent;
  background: var(--c-surface);
  border-radius: var(--r-sm);
  font-size: 12px;
  font-weight: 500;
  color: var(--c-text-3);
  cursor: pointer;
  transition: all 0.2s;
}

.tab-btn:hover {
  color: var(--c-text);
  background: var(--c-surface-2);
}

.tab-btn.active {
  color: var(--c-brand);
  background: var(--c-brand-light);
  border-color: var(--c-brand);
}

/* Tab 内容区域 */
.tabs-content {
  flex: 1;
  overflow-y: auto;
  position: relative;
}

/* 响应式 */
@media (max-width: 1024px) {
  .ai-layout {
    grid-template-columns: 200px 1fr;
  }
}

@media (max-width: 768px) {
  .ai-layout {
    grid-template-columns: 1fr;
  }

  .hazard-list-panel {
    display: none;
  }

  .page-head {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }

  .page-head-actions {
    width: 100%;
  }

  .refresh-btn {
    flex: 1;
  }
}
</style>
