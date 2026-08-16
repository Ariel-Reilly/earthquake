<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { CalendarDays, FileText, Gauge, ListChecks, UsersRound, PencilLine, Trash2, CheckCircle2, Navigation2, MapPin, ClipboardList } from 'lucide-vue-next'
import PermissionButton from '../components/PermissionButton.vue'
import { dashboardApi } from '../services/dashboardApi'
import { formatDistance, haversineDistance } from '../utils/geo'

const emit = defineEmits(['notify', 'open-task-modal'])
const props = defineProps({ userRole: { type: String, default: 'admin' } })
const router = useRouter()
const tasks = ref([])
const selectedTaskId = ref(null)
const loading = ref(true)
const paging = ref({ page: 1, size: 10, total: 0, pages: 0 })
const tracking = ref(false)
const submitting = ref(false)
const gps = ref({ lat: null, lon: null, accuracy: null, timestamp: null })
const gpsError = ref('')
const gpsResult = ref(null)
const submitForm = ref({ note: '', abnormalFlag: false, formData: {}, photoIds: [] })

const selectedTask = computed(() => tasks.value.find((item) => item.id === selectedTaskId.value) ?? tasks.value[0] ?? null)
const pendingCount = computed(() => tasks.value.filter((task) => task.status !== '已完成').length)
const distanceToSite = computed(() => {
  if (!selectedTask.value?.hazardLon || !selectedTask.value?.hazardLat || gps.value.lon == null || gps.value.lat == null) return '--'
  return formatDistance(haversineDistance(gps.value.lon, gps.value.lat, selectedTask.value.hazardLon, selectedTask.value.hazardLat))
})
const canSubmit = computed(() => Boolean(selectedTask.value && gpsResult.value?.withinFence))

async function loadData(page = 1) {
  loading.value = true
  try {
    const data = await dashboardApi.getTasks({ page, size: paging.value.size })
    tasks.value = data.list
    paging.value = { page: data.page ?? page, size: data.size ?? paging.value.size, total: data.total ?? data.list.length, pages: data.pages ?? 1 }
    selectedTaskId.value = tasks.value[0]?.id ?? null
  } finally {
    loading.value = false
  }
}

async function changePage(step) {
  const nextPage = Math.min(Math.max(paging.value.page + step, 1), paging.value.pages || 1)
  if (nextPage !== paging.value.page) await loadData(nextPage)
}

async function markDone() {
  if (!selectedTask.value) return
  try {
    const result = await dashboardApi.updateTaskStatus(selectedTask.value.id, '已完成')
    tasks.value = tasks.value.map((item) => (item.id === result.item.id ? result.item : item))
    emit('notify', `任务「${selectedTask.value.name}」已标记为完成`)
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '操作失败')
  }
}

function openDetail(task) {
  const target = task ?? selectedTask.value
  if (target) router.push(`/tasks/${target.id}`)
}

async function deleteTask() {
  if (!selectedTask.value) return
  try {
    await dashboardApi.deleteTask(selectedTask.value.id)
    tasks.value = tasks.value.filter((item) => item.id !== selectedTask.value.id)
    selectedTaskId.value = tasks.value[0]?.id ?? null
    emit('notify', `已删除 ${selectedTask.value.name}`)
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '删除失败')
  }
}

function openCreate() {
  emit('open-task-modal')
}

async function startGpsTracking() {
  gpsError.value = ''
  gpsResult.value = null
  if (!navigator.geolocation) {
    gpsError.value = '当前浏览器不支持 GPS 定位'
    emit('notify', gpsError.value)
    return
  }

  tracking.value = true
  navigator.geolocation.getCurrentPosition(
    async (position) => {
      gps.value = {
        lat: position.coords.latitude,
        lon: position.coords.longitude,
        accuracy: position.coords.accuracy,
        timestamp: new Date(position.timestamp),
      }
      try {
        const result = await dashboardApi.gpsCheck({
          taskCode: selectedTask.value?.taskCode ?? selectedTask.value?.id,
          hazardCode: selectedTask.value?.hazardCode,
          lon: gps.value.lon,
          lat: gps.value.lat,
          allowedRadius: 100,
          inspector: props.userRole,
        })
        gpsResult.value = result
        emit('notify', result.withinFence ? `签到成功，已进入围栏（距离 ${Math.round(result.distanceM)} 米）` : `已定位，但未进入围栏（距离 ${Math.round(result.distanceM)} 米）`)
      } catch (error) {
        gpsError.value = error instanceof Error ? error.message : '围栏校验失败'
        emit('notify', gpsError.value)
      } finally {
        tracking.value = false
      }
    },
    (error) => {
      tracking.value = false
      gpsError.value = error.message || '获取定位失败'
      emit('notify', gpsError.value)
    },
    { enableHighAccuracy: true, timeout: 10000, maximumAge: 30000 }
  )
}

async function submitInspection() {
  if (!selectedTask.value) return
  if (!gpsResult.value?.withinFence) {
    gpsError.value = '未进入围栏，不能提交巡检'
    emit('notify', gpsError.value)
    return
  }
  submitting.value = true
  try {
    const result = await dashboardApi.submitInspection({
      taskCode: selectedTask.value.taskCode,
      hazardCode: selectedTask.value.hazardCode,
      lon: gps.value.lon,
      lat: gps.value.lat,
      allowedRadius: gpsResult.value.allowedRadius,
      inspector: props.userRole,
      abnormalFlag: submitForm.value.abnormalFlag,
      note: submitForm.value.note,
      photoIds: submitForm.value.photoIds,
      formData: submitForm.value.formData,
    })
    emit('notify', `巡检已上报：${result.recordCode}`)
    tasks.value = tasks.value.map((item) => (item.id === selectedTask.value.id ? { ...item, progress: Math.min(100, (item.progress ?? 0) + 15), status: submitForm.value.abnormalFlag ? '执行中' : item.status } : item))
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '上报失败')
  } finally {
    submitting.value = false
  }
}

onMounted(loadData)
</script>

<template>
  <div>
    <div v-if="loading" class="panel" style="padding: 24px;">任务数据加载中...</div>
    <template v-else>
      <div class="stats-grid">
        <div class="stat-card"><div class="stat-icon teal"><ListChecks :size="19" /></div><div class="stat-copy"><span>待处理任务</span><strong>{{ pendingCount }} <small>项</small></strong><em class="positive">及时跟进 <i>执行中</i></em></div></div>
        <div class="stat-card"><div class="stat-icon blue"><CalendarDays :size="19" /></div><div class="stat-copy"><span>今日任务</span><strong>{{ paging.total }} <small>项</small></strong><em class="positive">全部已排期 <i>任务池</i></em></div></div>
        <div class="stat-card"><div class="stat-icon orange"><Gauge :size="19" /></div><div class="stat-copy"><span>平均完成率</span><strong>78 <small>%</small></strong><em class="negative">需提速 <i>过程管控</i></em></div></div>
        <div class="stat-card"><div class="stat-icon purple"><UsersRound :size="19" /></div><div class="stat-copy"><span>执行人员</span><strong>4 <small>人</small></strong><em class="positive">覆盖全部任务 <i>轮班调度</i></em></div></div>
      </div>

      <div class="content-grid">
        <section class="panel warning-panel">
          <div class="panel-header compact">
            <div>
              <h2>任务列表</h2>
              <p>任务状态、负责人和进度可直接处理</p>
            </div>
            <div class="heading-actions">
              <PermissionButton permission="tasks:create" :icon="PencilLine" @click="({ allowed, reason }) => allowed ? openCreate() : emit('notify', reason)">新建</PermissionButton>
              <PermissionButton permission="tasks:export" :icon="FileText" @click="async ({ allowed, reason }) => {
                if (!allowed) return emit('notify', reason)
                const result = await dashboardApi.exportTaskReport()
                emit('notify', `已导出 ${result.fileName}`)
              }">导出</PermissionButton>
            </div>
          </div>
          <div class="warning-list">
            <article v-for="task in tasks" :key="task.id" class="warning-item" @click="openDetail(task)">
              <div class="warning-symbol" :class="task.status === '已完成' ? 'blue' : task.status === '执行中' ? 'yellow' : 'orange'">{{ task.progress }}%</div>
              <div class="warning-body">
                <div class="warning-title"><strong>{{ task.name }}</strong><span :class="task.status === '已完成' ? 'blue' : task.status === '执行中' ? 'yellow' : 'orange'">{{ task.status }}</span></div>
                <p>{{ task.type }} · {{ task.owner }} · 截止 {{ task.due }}</p>
                <small><Gauge :size="12" />完成进度 {{ task.progress }}%</small>
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
        </section>

        <section class="panel system-panel">
          <div class="panel-header compact">
            <div>
              <h2>GPS 签到与巡检上报</h2>
              <p>到点定位、围栏校验、提交上报闭环</p>
            </div>
            <span class="health-label"><i></i>{{ selectedTask?.status }}</span>
          </div>
          <div v-if="selectedTask" class="system-list">
            <div><span><FileText :size="16" />任务名称</span><strong>{{ selectedTask.name }}</strong></div>
            <div><span><UsersRound :size="16" />负责人</span><strong>{{ selectedTask.owner }}</strong></div>
            <div><span><CalendarDays :size="16" />任务类型</span><strong>{{ selectedTask.type }}</strong></div>
            <div><span><Gauge :size="16" />完成进度</span><strong>{{ selectedTask.progress }}%</strong></div>
            <div><span><Navigation2 :size="16" />到灾害点距离</span><strong>{{ distanceToSite }}</strong></div>
            <div><span><MapPin :size="16" />围栏状态</span><strong>{{ gpsResult?.fenceStatus ?? '未签到' }}</strong></div>
          </div>
          <div v-if="selectedTask" class="task-summary">
            <div class="progress-ring"><span>{{ selectedTask.progress }}<small>%</small></span></div>
            <div class="task-overview">
              <strong>截止 {{ selectedTask.due }}</strong>
              <span>任务状态：{{ selectedTask.status }}</span>
              <div class="task-bars"><i :style="{ width: `${selectedTask.progress}%` }"></i></div>
              <p>建议：先获取 GPS 定位，再进行巡检上报</p>
              <p v-if="gps.timestamp">当前 GPS：{{ gps.lat?.toFixed(6) }}, {{ gps.lon?.toFixed(6) }} · 精度 {{ Math.round(gps.accuracy || 0) }}m</p>
              <p v-if="gpsResult">围栏校验：{{ gpsResult.withinFence ? '通过' : '未通过' }} · 距离 {{ Math.round(gpsResult.distanceM) }} 米</p>
            </div>
          </div>
          <p v-if="gpsError" class="login-error" style="padding: 0 20px;">{{ gpsError }}</p>
          <div class="system-list" style="padding-top: 0;">
            <div><span><ClipboardList :size="16" />异常上报</span><strong>{{ submitForm.abnormalFlag ? '已勾选' : '未勾选' }}</strong></div>
          </div>
          <div style="padding: 0 20px 10px;">
            <label class="login-role-tip" style="display:block; margin-top:0;">巡检备注</label>
            <textarea v-model="submitForm.note" rows="4" placeholder="填写现场情况、隐患描述和处置意见" style="width:100%; padding:12px; border:1px solid #dfeae6; border-radius:12px; outline:none; resize:vertical;"></textarea>
            <label style="display:flex; align-items:center; gap:8px; margin-top:10px; color:#5e7675; font-size:12px;">
              <input v-model="submitForm.abnormalFlag" type="checkbox" />
              发现异常，立即上报为预警候选
            </label>
          </div>
          <div class="heading-actions" style="padding: 0 20px 18px; justify-content: flex-end; flex-wrap: wrap;">
            <PermissionButton permission="tasks:edit" :icon="CheckCircle2" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              await markDone()
            }">完结</PermissionButton>
            <PermissionButton permission="tasks:delete" :icon="Trash2" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              await deleteTask()
            }">删除</PermissionButton>
            <PermissionButton permission="tasks:export" :icon="FileText" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              const result = await dashboardApi.exportTaskReport()
              emit('notify', `已导出 ${result.fileName}`)
            }">导出</PermissionButton>
            <button class="secondary-btn" :disabled="tracking" @click="startGpsTracking">{{ tracking ? '定位中...' : '获取 GPS 并围栏校验' }}</button>
            <button class="primary-btn" :disabled="submitting || !canSubmit" @click="submitInspection">{{ submitting ? '上报中...' : '提交巡检上报' }}</button>
            <button class="primary-btn" @click="openDetail">查看详情页</button>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>
