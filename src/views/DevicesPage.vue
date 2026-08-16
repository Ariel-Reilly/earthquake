<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AlertTriangle, Database, Gauge, Layers3, MapPin, MessageSquareText, Radio, Video, PencilLine, Trash2, Wrench } from 'lucide-vue-next'
import PermissionButton from '../components/PermissionButton.vue'
import { dashboardApi } from '../services/dashboardApi'

const emit = defineEmits(['notify'])
const router = useRouter()
const devices = ref([])
const selectedDeviceId = ref(null)
const loading = ref(true)
const paging = ref({ page: 1, size: 10, total: 0, pages: 0 })

const selectedDevice = computed(() => devices.value.find((item) => item.id === selectedDeviceId.value) ?? devices.value[0] ?? null)
const onlineCount = computed(() => devices.value.filter((item) => item.state === '在线').length)
const offlineCount = computed(() => devices.value.filter((item) => item.state === '离线').length)

async function loadData(page = 1) {
  loading.value = true
  try {
    const data = await dashboardApi.getDevices({ page, size: paging.value.size })
    devices.value = data.list
    paging.value = { page: data.page ?? page, size: data.size ?? paging.value.size, total: data.total ?? data.list.length, pages: data.pages ?? 1 }
    selectedDeviceId.value = devices.value[0]?.id ?? null
  } finally {
    loading.value = false
  }
}

async function changePage(step) {
  const nextPage = Math.min(Math.max(paging.value.page + step, 1), paging.value.pages || 1)
  if (nextPage !== paging.value.page) await loadData(nextPage)
}

function openDetail(device) {
  const target = device ?? selectedDevice.value
  if (target) router.push(`/devices/${target.id}`)
}

async function deleteDevice() {
  if (!selectedDevice.value) return
  try {
    await dashboardApi.deleteDevice(selectedDevice.value.id)
    devices.value = devices.value.filter((item) => item.id !== selectedDevice.value.id)
    selectedDeviceId.value = devices.value[0]?.id ?? null
    emit('notify', `已删除 ${selectedDevice.value.name}`)
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '删除失败')
  }
}

async function diagnoseDevice() {
  if (!selectedDevice.value) return
  try {
    const result = await dashboardApi.diagnoseDevice(selectedDevice.value.id)
    devices.value = devices.value.map((item) => (item.id === result.item.id ? result.item : item))
    emit('notify', `已完成 ${selectedDevice.value.name} 诊断`)
  } catch (error) {
    emit('notify', error instanceof Error ? error.message : '诊断失败')
  }
}

onMounted(loadData)
</script>

<template>
  <div>
    <div v-if="loading" class="panel" style="padding: 24px;">设备数据加载中...</div>
    <template v-else>
      <div class="stats-grid">
        <div class="stat-card"><div class="stat-icon teal"><Radio :size="19" /></div><div class="stat-copy"><span>在线设备</span><strong>{{ onlineCount }} <small>台</small></strong><em class="positive">高可用 <i>MQTT接入</i></em></div></div>
        <div class="stat-card"><div class="stat-icon orange"><AlertTriangle :size="19" /></div><div class="stat-copy"><span>离线设备</span><strong>{{ offlineCount }} <small>台</small></strong><em class="negative">需运维 <i>立即处理</i></em></div></div>
        <div class="stat-card"><div class="stat-icon blue"><Database :size="19" /></div><div class="stat-copy"><span>今日上报</span><strong>8,642 <small>条</small></strong><em class="positive">时序稳定 <i>TDengine</i></em></div></div>
        <div class="stat-card"><div class="stat-icon purple"><Video :size="19" /></div><div class="stat-copy"><span>视频在线率</span><strong>98.7 <small>%</small></strong><em class="positive">联动正常 <i>监控接入</i></em></div></div>
      </div>

      <div class="content-grid">
        <section class="panel warning-panel">
          <div class="panel-header compact">
            <div>
              <h2>设备列表</h2>
              <p>在线状态、电量与信号一目了然</p>
            </div>
            <div class="heading-actions">
              <PermissionButton permission="devices:create" :icon="PencilLine" @click="({ allowed, reason }) => allowed ? emit('notify', '设备新增表单已打开') : emit('notify', reason)">新增</PermissionButton>
              <PermissionButton permission="devices:export" :icon="Database" @click="async ({ allowed, reason }) => {
                if (!allowed) return emit('notify', reason)
                const result = await dashboardApi.exportDeviceReport()
                emit('notify', `已导出 ${result.fileName}`)
              }">导出</PermissionButton>
            </div>
          </div>
          <div class="warning-list">
            <article v-for="device in devices" :key="device.id" class="warning-item" @click="openDetail">
              <div class="warning-symbol" :class="device.state === '在线' ? 'blue' : 'orange'">{{ device.state === '在线' ? '✓' : '!' }}</div>
              <div class="warning-body">
                <div class="warning-title"><strong>{{ device.name }}</strong><span :class="device.state === '在线' ? 'blue' : 'orange'">{{ device.state }}</span></div>
                <p>{{ device.type }} · {{ device.point }} · 信号 {{ device.signal }}</p>
                <small><Gauge :size="12" />电量 {{ device.battery }}% · {{ device.last }}</small>
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
              <h2>设备详情</h2>
              <p>单台设备的运行概况和接入情况</p>
            </div>
            <span class="health-label"><i></i>{{ selectedDevice?.state }}</span>
          </div>
          <div v-if="selectedDevice" class="system-list">
            <div><span><Radio :size="16" />设备编号</span><strong>{{ selectedDevice.name }}</strong></div>
            <div><span><Layers3 :size="16" />设备类型</span><strong>{{ selectedDevice.type }}</strong></div>
            <div><span><MapPin :size="16" />绑定点位</span><strong>{{ selectedDevice.point }}</strong></div>
            <div><span><MessageSquareText :size="16" />最近上报</span><strong>{{ selectedDevice.last }}</strong></div>
          </div>
          <div v-if="selectedDevice" class="task-summary">
            <div class="progress-ring"><span>{{ selectedDevice.battery }}<small>%</small></span></div>
            <div class="task-overview">
              <strong>电量</strong>
              <span>信号：{{ selectedDevice.signal }}</span>
              <div class="task-bars"><i :style="{ width: `${selectedDevice.battery}%` }"></i></div>
              <p>支持 EMQX 接入与 WebSocket 推送</p>
            </div>
          </div>
          <div class="heading-actions" style="padding: 0 20px 18px; justify-content: flex-end;">
            <PermissionButton permission="devices:edit" :icon="Wrench" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              await diagnoseDevice()
            }">诊断</PermissionButton>
            <PermissionButton permission="devices:delete" :icon="Trash2" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              await deleteDevice()
            }">删除</PermissionButton>
            <PermissionButton permission="devices:export" :icon="Database" @click="async ({ allowed, reason }) => {
              if (!allowed) return emit('notify', reason)
              const result = await dashboardApi.exportDeviceReport()
              emit('notify', `已导出 ${result.fileName}`)
            }">导出</PermissionButton>
            <button class="primary-btn" @click="openDetail">查看详情页</button>
          </div>
        </section>
      </div>
    </template>
  </div>
</template>
