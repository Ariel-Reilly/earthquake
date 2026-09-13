<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  Database,
  GitBranch,
  Gauge,
  Layers3,
  MapPin,
  Mountain,
  Radio,
  Search,
  Sparkles,
  Trash2,
  X,
  PencilLine,
} from 'lucide-vue-next'
import PermissionButton from '../../components/PermissionButton.vue'
import { useHazardList } from '../../composables/useHazardList'
import { getRealtimeState, onRealtimeUpdate } from '../../services/realtimeService'

const props = defineProps({
  searchTerm: { type: String, default: '' },
  userRole: { type: String, default: 'admin' },
})

const emit = defineEmits(['notify', 'create-task'])
const router = useRouter()
const {
  levelFilter,
  selected,
  drawerOpen,
  loading,
  hazards,
  filteredHazards,
  paging,
  loadData,
  openDetail,
  closeDrawer,
  setLevelFilter,
} = useHazardList(props)

const sourceFilter = ref('all')  // 'all' | 'AI_MONITOR' | 'MANUAL'
const aiMonitorCount = ref(0)     // AI 实时监测发现的灾害总数（用于"AI 实时监测"徽标）
const lastAiDetectedAt = ref(null)

const filteredBySource = computed(() => {
  if (sourceFilter.value === 'all') return filteredHazards.value
  return filteredHazards.value.filter((h) => (h.source || 'MANUAL') === sourceFilter.value)
})

async function changePage(step) {
  const nextPage = Math.min(Math.max(paging.value.page + step, 1), paging.value.pages || 1)
  if (nextPage !== paging.value.page) await loadData(nextPage)
}

function openRouteDetail() {
  if (!selected.value) return
  router.push(`/dashboard/hazards/${selected.value.id}`)
}

/**
 * v1.7.0+ P3-4：跳转到对应的统一事件详情。
 * HazardDto 已携带 eventCode（来自 unified_event.eventCode）。
 */
function openEventOverview() {
  if (!selected.value) return
  const code = selected.value.eventCode
  if (!code) {
    emit('notify', '该灾害点尚未关联统一事件（可能未走 AI / 人工新建流程）')
    return
  }
  router.push({ path: '/dashboard/events', query: { code } })
}

function createTaskForSelected() {
  if (!selected.value) return
  emit('create-task', {
    name: `${selected.value.name} 巡检`,
    scope: selected.value.name,
    hazardId: selected.value.id,
    hazardCode: selected.value.hazardCode ?? `HZ-${String(selected.value.id).padStart(3, '0')}`,
    hazardLon: selected.value.lon,
    hazardLat: selected.value.lat,
  })
}

function aiLevelLabel(level) {
  return { CRITICAL: '极高', HIGH: '高', MEDIUM: '中', LOW: '低' }[level] || '—'
}

/**
 * v1.6.0：监听 realtimeService 的 hazard 事件，新发现的 AI 灾害实时显示在列表里。
 * 无需手动刷新页面 —— 后端 AiHazardMonitorScheduler 每 90s 一次推送。
 */
function refreshAiMonitorStats() {
  const all = getRealtimeState().hazards || []
  const aiOnly = all.filter((h) => h.source === 'AI_MONITOR')
  aiMonitorCount.value = aiOnly.length
  if (aiOnly.length) {
    const ts = aiOnly.map((h) => h.discoveredAt || h.updatedAt || '').filter(Boolean).sort()
    if (ts.length) lastAiDetectedAt.value = ts[ts.length - 1]
  }
}

const stopRealtime = ref(null)
onMounted(() => {
  loadData()
  stopRealtime.value = onRealtimeUpdate((payload) => {
    if (payload.type === 'hazard' && payload.data) {
      // 新灾害到达：插入到列表顶部
      const next = payload.data
      const existingIdx = hazards.value.findIndex((h) => h.id === next.id || h.hazardCode === next.hazardCode)
      if (existingIdx >= 0) {
        hazards.value.splice(existingIdx, 1, { ...hazards.value[existingIdx], ...next })
      } else {
        hazards.value.unshift(next)
        // 给用户一个轻量提示（不打扰）
        if (next.source === 'AI_MONITOR') {
          emit('notify', `AI 实时监测发现：${next.name}`)
        }
      }
      refreshAiMonitorStats()
    } else if (payload.type === 'poll') {
      refreshAiMonitorStats()
    }
  })
  refreshAiMonitorStats()
})

onBeforeUnmount(() => {
  if (stopRealtime.value) stopRealtime.value()
})

function fmtRelTime(iso) {
  if (!iso) return ''
  const ms = Date.now() - Date.parse(iso)
  if (!Number.isFinite(ms) || ms < 0) return iso
  if (ms < 60_000) return `${Math.floor(ms / 1000)}s 前`
  if (ms < 3_600_000) return `${Math.floor(ms / 60_000)}m 前`
  if (ms < 86_400_000) return `${Math.floor(ms / 3_600_000)}h 前`
  return `${Math.floor(ms / 86_400_000)}d 前`
}
</script>

<template>
  <div class="content-grid">
    <section class="panel warning-panel">
      <div class="panel-header compact">
        <div>
          <h2>灾害点列表</h2>
          <p>支持搜索、筛选和快速查看</p>
        </div>
        <div class="heading-actions">
          <PermissionButton v-if="userRole !== 'maintenance'" permission="hazards:create" :icon="PencilLine" @click="({ allowed, reason }) => allowed ? emit('notify', '新增灾害点表单已打开') : emit('notify', reason)">新增</PermissionButton>
          <PermissionButton permission="hazards:export" :icon="Database" @click="({ allowed, reason }) => allowed ? emit('notify', '已同步灾害点数据') : emit('notify', reason)">导出</PermissionButton>
        </div>
      </div>
      <div v-if="loading" style="padding: 0 20px 18px; color: #7a8d89;">
        <span><Radio :size="13" class="spin" />AI 监测初始化中…</span>
      </div>
      <template v-else>
        <div class="map-toolbar">
          <div class="map-legend">
            <button v-for="item in ['全部', '高风险', '关注', '正常']" :key="item" :class="{ selected: levelFilter === item }" @click="setLevelFilter(item); loadData(1)">
              <span :class="item === '全部' ? 'all-dot' : item === '高风险' ? 'danger-dot' : item === '关注' ? 'warn-dot' : 'safe-dot'"></span>{{ item }}
            </button>
          </div>
          <div class="map-tools">
            <button
              v-for="item in [{ key: 'all', label: '全部来源' }, { key: 'AI_MONITOR', label: 'AI 监测', icon: Sparkles }, { key: 'MANUAL', label: '人工新增' }]"
              :key="item.key"
              :class="{ 'source-active': sourceFilter === item.key }"
              @click="sourceFilter = item.key"
            >
              <span v-if="item.icon" :class="`source-dot source-${item.key}`"></span>{{ item.label }}
            </button>
          </div>
        </div>

        <!-- v1.6.0：AI 实时监测状态条 -->
        <div v-if="aiMonitorCount > 0 || sourceFilter === 'AI_MONITOR'" class="ai-monitor-bar">
          <Sparkles :size="14" />
          <span class="ai-monitor-text">
            AI 实时监测：已发现 <strong>{{ aiMonitorCount }}</strong> 个灾害点
            <small v-if="lastAiDetectedAt"> · 最新 {{ fmtRelTime(lastAiDetectedAt) }}</small>
          </span>
          <small class="ai-monitor-hint">每 90 秒由 AiHazardMonitorScheduler 自动巡查</small>
        </div>

        <div v-if="filteredBySource.length" class="warning-list">
          <article v-for="point in filteredBySource" :key="point.id" class="warning-item" @click="openDetail(point)">
            <div class="warning-symbol" :class="point.level === '高风险' ? 'orange' : point.level === '关注' ? 'yellow' : 'blue'">{{ point.level === '高风险' ? '!' : point.level === '关注' ? '?' : '✓' }}</div>
            <div class="warning-body">
              <div class="warning-title">
                <strong>{{ point.name }}</strong>
                <span :class="point.level === '高风险' ? 'orange' : point.level === '关注' ? 'yellow' : 'blue'">{{ point.level }}</span>
                <!-- v1.6.0：AI 实时监测徽标 -->
                <span
                  v-if="point.source === 'AI_MONITOR'"
                  class="source-badge source-ai"
                  :title="point.discoveredAt ? `AI 于 ${fmtRelTime(point.discoveredAt)} 首次识别` : 'AI 实时监测识别'"
                >
                  <Sparkles :size="10" />AI 实时监测
                </span>
                <span
                  v-if="point.aiScore != null"
                  :class="`ai-tag ai-tag-${(point.aiLevel || '').toLowerCase()}`"
                  :title="`AI 综合分 ${Number(point.aiScore).toFixed(1)} / 100`"
                >
                  <Sparkles :size="10" />AI {{ aiLevelLabel(point.aiLevel) }} {{ Number(point.aiScore).toFixed(0) }}
                </span>
              </div>
              <p>{{ point.type }} · {{ point.owner }} · {{ point.status }}</p>
              <small>
                <MapPin :size="12" />
                {{ point.distance }} · {{ point.lon }}, {{ point.lat }}
                <span v-if="point.discoveredAt && point.source === 'AI_MONITOR'"> · 发现 {{ fmtRelTime(point.discoveredAt) }}</span>
              </small>
            </div>
          </article>
        </div>
        <div v-else class="panel-empty" style="padding: 0 20px 18px; color: #7a8d89;">
          <template v-if="sourceFilter === 'AI_MONITOR'">
            AI 实时监测尚未发现 {{ levelFilter !== '全部' ? `${levelFilter} ` : '' }}灾害 —— 等待下一个 90s 调度周期
          </template>
          <template v-else>当前条件下没有匹配的灾害点</template>
        </div>
        <div class="panel-footer pagination-bar">
          <span>共 {{ paging.total }} 条，{{ paging.page }}/{{ paging.pages || 1 }} 页</span>
          <div class="detail-utility">
            <button class="secondary-btn" :disabled="paging.page <= 1" @click="changePage(-1)">上一页</button>
            <button class="secondary-btn" :disabled="paging.page >= (paging.pages || 1)" @click="changePage(1)">下一页</button>
          </div>
        </div>
      </template>
    </section>

    <section class="panel system-panel">
      <div class="panel-header compact">
        <div>
          <h2>灾害点详情</h2>
          <p>档案、风险和空间信息</p>
        </div>
        <span class="health-label"><i></i>{{ selected?.level || '未选择' }}</span>
      </div>
      <template v-if="selected">
        <div class="system-list">
          <div><span><Mountain :size="16" />点位名称</span><strong>{{ selected.name }}</strong></div>
          <div><span><Layers3 :size="16" />灾害类型</span><strong>{{ selected.type }}</strong></div>
          <div><span><MapPin :size="16" />所属辖区</span><strong>{{ selected.owner }}</strong></div>
          <div><span><Gauge :size="16" />当前状态</span><strong>{{ selected.status }}</strong></div>
        </div>
        <div class="task-summary">
          <div class="progress-ring"><span>{{ selected.level === '高风险' ? '86' : selected.level === '关注' ? '63' : '41' }}<small>%</small></span></div>
          <div class="task-overview">
            <strong>{{ selected.distance }}</strong>
            <span>与值守中心距离</span>
            <div class="task-bars"><i style="width: 72%"></i></div>
            <p>最近巡检时间：今天 09:30</p>
          </div>
        </div>
        <div class="heading-actions" style="padding: 0 20px 18px; justify-content: flex-end;">
          <button
            v-if="selected.eventCode"
            class="secondary-btn"
            @click="openEventOverview"
            title="查看完整事件全貌（血缘、状态机）"
          >
            <GitBranch :size="14" /> 查看事件全貌
          </button>
          <button class="secondary-btn" @click="createTaskForSelected">创建任务</button>
          <PermissionButton permission="hazards:delete" :icon="Trash2" @click="({ allowed, reason }) => allowed ? emit('notify', `已删除 ${selected?.name}`) : emit('notify', reason)">删除</PermissionButton>
          <PermissionButton permission="hazards:export" :icon="Database" @click="({ allowed, reason }) => allowed ? emit('notify', '已生成灾害点巡检记录') : emit('notify', reason)">导出</PermissionButton>
          <button class="primary-btn" @click="openRouteDetail">查看完整档案</button>
        </div>
      </template>
      <div v-else class="panel-empty" style="padding: 0 20px 18px; color: #7a8d89;">暂无灾害点数据</div>
    </section>
  </div>

  <div v-if="drawerOpen && selected" class="modal-backdrop" @click.self="closeDrawer">
    <div class="modal">
      <div class="modal-head">
        <div><span class="eyebrow">HAZARD POINT</span><h2>{{ selected.name }}</h2></div>
        <button @click="closeDrawer"><X :size="18" /></button>
      </div>
      <label>空间坐标<input :value="`${selected.lon}, ${selected.lat}`" readonly /></label>
      <label>当前状态<input :value="selected.status" readonly /></label>
      <label>风险等级<input :value="selected.level" readonly /></label>
      <!-- v1.7.0+ P3-4：显示统一事件号 + 跳转按钮 -->
      <label v-if="selected.eventCode">
        <span>统一事件号</span>
        <input :value="selected.eventCode" readonly />
        <small class="event-state-tag" :data-state="selected.eventState">
          {{ selected.eventState || '—' }}
        </small>
      </label>
      <div class="modal-foot">
        <button class="secondary-btn" @click="closeDrawer">关闭</button>
        <button
          v-if="selected.eventCode"
          class="secondary-btn"
          @click="openEventOverview"
          title="查看完整事件全貌（血缘、状态机）"
        >
          <GitBranch :size="14" /> 查看事件全貌
        </button>
        <button class="primary-btn" @click="openRouteDetail">查看完整档案</button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.event-state-tag {
  display: inline-block;
  margin-left: 8px;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.3px;
  font-family: ui-monospace, monospace;
  background: rgba(79, 143, 153, 0.12);
  color: #4f8f99;
}
.event-state-tag[data-state="DETECTED"]          { background: rgba(79, 143, 153, 0.12); color: #4f8f99; }
.event-state-tag[data-state="CONFIRMING"]        { background: rgba(224, 160, 80, 0.14); color: #9b761e; }
.event-state-tag[data-state="CONFIRMED"]         { background: rgba(99, 143, 194, 0.12); color: #2b6cb0; }
.event-state-tag[data-state="WARNING_ISSUED"]    { background: rgba(216, 133, 97, 0.14); color: #c7553b; }
.event-state-tag[data-state="EMERGENCY_ACTIVE"]  { background: rgba(224, 96, 96, 0.12); color: #e06060; }
.event-state-tag[data-state="CLOSED"]            { background: rgba(155, 174, 173, 0.18); color: #5a6a68; }
.event-state-tag[data-state="REJECTED"]          { background: rgba(122, 141, 137, 0.18); color: #7a8d89; }
</style>
