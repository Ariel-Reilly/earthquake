<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AlertTriangle, Bell, FileText, Layers3, MapPin, MessageSquareText, ShieldCheck, UsersRound, Send, CheckCircle2, XCircle, RotateCcw } from 'lucide-vue-next'
import PermissionButton from '../components/PermissionButton.vue'
import { dashboardApi } from '../services/dashboardApi'

const emit = defineEmits(['notify'])
const props = defineProps({ userRole: { type: String, default: 'admin' } })
const router = useRouter()
const warnings = ref([])
const selectedWarningId = ref(null)
const showAll = ref(false)
const loading = ref(true)
const paging = ref({ page: 1, size: 10, total: 0, pages: 0 })

const visibleWarnings = computed(() => warnings.value.slice(0, showAll.value ? warnings.value.length : 3))
const selectedWarning = computed(() => warnings.value.find((item) => item.id === selectedWarningId.value) ?? warnings.value[0] ?? null)
const escalationHint = computed(() => {
  const level = selectedWarning.value?.level ?? ''
  if (/橙|红/.test(level)) return '橙色及以上：派发后自动启动应急响应，联动应急值守组'
  if (/黄/.test(level)) return '黄色：加密巡查，根据事态按需启动应急响应'
  if (/蓝/.test(level)) return '蓝色：常规处置，持续关注变化趋势'
  return '按预警等级采取对应处置策略'
})

async function loadData(page = 1) {
  loading.value = true
  try {
    const data = await dashboardApi.getWarnings({ page, size: paging.value.size })
    warnings.value = data.list
    paging.value = { page: data.page ?? page, size: data.size ?? paging.value.size, total: data.total ?? data.list.length, pages: data.pages ?? 1 }
    selectedWarningId.value = warnings.value[0]?.id ?? null
  } finally {
    loading.value = false
  }
}

async function changePage(step) {
  const nextPage = Math.min(Math.max(paging.value.page + step, 1), paging.value.pages || 1)
  if (nextPage !== paging.value.page) await loadData(nextPage)
}

function openDetail(warning) {
  const target = warning ?? selectedWarning.value
  if (target) router.push(`/warnings/${target.id}`)
}

async function confirmWarning() {
  if (!selectedWarning.value) return
  try {
    const result = await dashboardApi.confirmWarning(selectedWarning.value.id)
    warnings.value = warnings.value.map((item) => (item.id === result.item.id ? result.item : item))
    emit('notify', `已确认 ${selectedWarning.value.title}`)
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '确认失败')
  }
}

async function dispatchWarning() {
  if (!selectedWarning.value) return
  try {
    const result = await dashboardApi.dispatchWarning(selectedWarning.value.id)
    warnings.value = warnings.value.map((item) => (item.id === result.item.id ? result.item : item))
    if (result.escalate) {
      emit('notify', `已派发 ${selectedWarning.value.title}，并按等级自动启动应急响应`)
    } else {
      emit('notify', `已派发 ${selectedWarning.value.title}，按等级采取加密巡查`)
    }
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '派发失败')
  }
}

async function closeWarning(reason = '处置完成') {
  if (!selectedWarning.value) return
  try {
    const result = await dashboardApi.closeWarning(selectedWarning.value.id, { reason })
    warnings.value = warnings.value.map((item) => (item.id === result.item.id ? result.item : item))
    emit('notify', `已${result.item.status === '已关闭' ? '关闭' : result.item.status} ${selectedWarning.value.title}`)
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '关闭失败')
  }
}

async function dismissWarning() {
  if (!selectedWarning.value) return
  try {
    const result = await dashboardApi.dismissWarning(selectedWarning.value.id)
    warnings.value = warnings.value.map((item) => (item.id === result.item.id ? result.item : item))
    emit('notify', `已忽略（误报） ${selectedWarning.value.title}`)
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '忽略失败')
  }
}

async function resolveWarning() {
  if (!selectedWarning.value) return
  try {
    const result = await dashboardApi.resolveWarning(selectedWarning.value.id)
    warnings.value = warnings.value.map((item) => (item.id === result.item.id ? result.item : item))
    emit('notify', `已自动解除 ${selectedWarning.value.title}`)
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '解除失败')
  }
}

onMounted(loadData)
</script>

<template>
  <div>
    <div v-if="loading" class="panel" style="padding: 24px;">预警数据加载中...</div>
    <template v-else>
      <div class="stats-grid">
        <div class="stat-card"><div class="stat-icon orange"><Bell :size="19" /></div><div class="stat-copy"><span>未闭环预警</span><strong>{{ warnings.filter((warning) => !['已关闭', '已忽略', '已解除'].includes(warning.status)).length }} <small>起</small></strong><em class="negative">需快速处置 <i>重点升级</i></em></div></div>
        <div class="stat-card"><div class="stat-icon blue"><ShieldCheck :size="19" /></div><div class="stat-copy"><span>已确认预警</span><strong>{{ warnings.filter((warning) => warning.status === '已确认').length }} <small>起</small></strong><em class="positive">闭环处理 <i>流程规范</i></em></div></div>
        <div class="stat-card"><div class="stat-icon teal"><MessageSquareText :size="19" /></div><div class="stat-copy"><span>消息通知</span><strong>100 <small>%</small></strong><em class="positive">全渠道触达 <i>短信/APP</i></em></div></div>
        <div class="stat-card"><div class="stat-icon purple"><UsersRound :size="19" /></div><div class="stat-copy"><span>联动部门</span><strong>4 <small>个</small></strong><em class="positive">协同响应 <i>专家/乡镇/运维</i></em></div></div>
      </div>

      <div class="content-grid">
        <section class="panel warning-panel">
          <div class="panel-header compact">
            <div>
              <h2>预警列表</h2>
              <p>确认、升级和关闭一体化处理</p>
            </div>
            <div class="heading-actions">
              <PermissionButton permission="warnings:dispatch" :icon="Send" @click="async ({ allowed, reason }) => {
                if (!allowed) return emit('notify', reason)
                await dispatchWarning()
              }">派发</PermissionButton>
              <PermissionButton permission="warnings:export" :icon="FileText" @click="async ({ allowed, reason }) => {
                if (!allowed) return emit('notify', reason)
                const result = await dashboardApi.exportWarningReport()
                emit('notify', `已导出 ${result.fileName}`)
              }">导出</PermissionButton>
            </div>
          </div>
          <div class="warning-list">
            <article v-for="warning in visibleWarnings" :key="warning.id" class="warning-item" @click="openDetail(warning)">
              <div class="warning-symbol" :class="warning.class">!</div>
              <div class="warning-body">
                <div class="warning-title"><strong>{{ warning.title }}</strong><span :class="warning.class">{{ warning.level }}</span></div>
                <p>{{ warning.desc }}</p>
                <small><MapPin :size="12" />{{ warning.point }}<time>{{ warning.time }}</time></small>
              </div>
            </article>
          </div>
          <div class="panel-footer pagination-bar">
            <span>共 {{ paging.total }} 条，{{ paging.page }}/{{ paging.pages || 1 }} 页</span>
            <div class="detail-utility">
              <button class="secondary-btn" :disabled="paging.page <= 1" @click="changePage(-1)">上一页</button>
              <button class="secondary-btn" :disabled="paging.page >= (paging.pages || 1)" @click="changePage(1)">下一页</button>
            </div>
          </div>
          <div class="detail-utility" style="padding: 0 20px 18px;">
            <button class="secondary-btn" @click="showAll = !showAll">{{ showAll ? '收起列表' : '查看全部' }}</button>
          </div>
        </section>

        <section class="panel system-panel">
          <div class="panel-header compact">
            <div>
              <h2>预警处置</h2>
              <p>当前预警的闭环状态</p>
            </div>
            <span class="health-label"><i></i>{{ selectedWarning?.status }}</span>
          </div>
          <div v-if="selectedWarning" class="system-list">
            <div><span><Bell :size="16" />预警等级</span><strong>{{ selectedWarning.level }}</strong></div>
            <div><span><Layers3 :size="16" />触发点位</span><strong>{{ selectedWarning.point }}</strong></div>
            <div><span><AlertTriangle :size="16" />触发时间</span><strong>{{ selectedWarning.time }}</strong></div>
            <div><span><FileText :size="16" />当前状态</span><strong>{{ selectedWarning.status }}</strong></div>
            <div v-if="selectedWarning.closeReason"><span><RotateCcw :size="16" />关闭原因</span><strong>{{ selectedWarning.closeReason }}</strong></div>
          </div>
          <div v-if="selectedWarning" class="task-summary">
            <div class="progress-ring"><span>{{ selectedWarning.level.includes('橙色') ? '86' : selectedWarning.level.includes('黄色') ? '71' : '54' }}<small>%</small></span></div>
            <div class="task-overview">
              <strong>{{ selectedWarning.title }}</strong>
              <span>{{ selectedWarning.desc }}</span>
              <div class="task-bars"><i style="width: 78%"></i></div>
              <p>{{ escalationHint }}</p>
            </div>
          </div>
          <div class="heading-actions" style="padding: 0 20px 18px; justify-content: flex-end;">
            <PermissionButton v-if="selectedWarning?.status === '待确认' || selectedWarning?.status === '已确认'" permission="warnings:confirm" :icon="CheckCircle2" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              await confirmWarning()
            }">确认</PermissionButton>
            <PermissionButton v-if="selectedWarning && !['已关闭', '已忽略', '已解除'].includes(selectedWarning.status)" permission="warnings:dispatch" :icon="Send" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              await dispatchWarning()
            }">派发</PermissionButton>
            <PermissionButton v-if="selectedWarning && !['已关闭', '已忽略', '已解除'].includes(selectedWarning.status)" permission="warnings:close" :icon="XCircle" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              await closeWarning('处置完成')
            }">关闭</PermissionButton>
            <PermissionButton v-if="selectedWarning?.status === '待确认'" permission="warnings:close" :icon="RotateCcw" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              await dismissWarning()
            }">误报</PermissionButton>
            <PermissionButton v-if="selectedWarning?.status === '处理中'" permission="warnings:close" :icon="RotateCcw" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              await resolveWarning()
            }">自动解除</PermissionButton>
            <PermissionButton permission="warnings:export" :icon="FileText" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              const result = await dashboardApi.exportWarningReport()
              emit('notify', `已导出 ${result.fileName}`)
            }">导出</PermissionButton>
            <button class="primary-btn" @click="openDetail">查看详情页</button>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>
