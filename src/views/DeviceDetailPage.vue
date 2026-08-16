<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Database, Gauge, GitBranch, History, Layers3, MapPin, MessageSquareText, PencilLine, Save, Radio, Paperclip } from 'lucide-vue-next'
import PermissionButton from '../components/PermissionButton.vue'
import { dashboardApi } from '../services/dashboardApi'

const route = useRoute()
const router = useRouter()
const device = ref(null)
const loading = ref(true)
const editMode = ref(false)
const form = ref({})

async function loadData() {
  loading.value = true
  const data = await dashboardApi.getDeviceDetail(route.params.id)
  device.value = data.item
  form.value = {
    name: data.item.name,
    type: data.item.type,
    point: data.item.point,
    state: data.item.state,
    battery: data.item.battery,
    signal: data.item.signal,
  }
  loading.value = false
}

async function saveDetail() {
  if (!device.value) return
  const result = await dashboardApi.updateDevice(device.value.id, { ...form.value, battery: Number(form.value.battery) })
  device.value = { ...device.value, ...result.item }
  editMode.value = false
}

async function refreshDetail() {
  await loadData()
}

onMounted(loadData)
</script>

<template>
  <div>
    <div class="content-grid">
      <section class="panel system-panel">
        <div class="panel-header compact">
          <div>
            <h2>监测设备详情</h2>
            <p>接入信息、编辑与运行概况</p>
          </div>
          <div class="heading-actions">
            <button class="text-btn" @click="router.back()"><ArrowLeft :size="15" />返回</button>
            <button class="text-btn" @click="refreshDetail"><History :size="15" />刷新</button>
            <button class="primary-btn" @click="editMode = !editMode"><PencilLine :size="15" />{{ editMode ? '取消编辑' : '编辑详情' }}</button>
          </div>
        </div>
        <div v-if="loading" style="padding: 0 20px 18px; color: #7a8d89;">加载中...</div>
        <template v-else-if="device">
          <div class="system-list">
            <div><span><Radio :size="16" />设备编号</span><strong v-if="!editMode">{{ device.name }}</strong><input v-else v-model="form.name" /></div>
            <div><span><Layers3 :size="16" />设备类型</span><strong v-if="!editMode">{{ device.type }}</strong><input v-else v-model="form.type" /></div>
            <div><span><MapPin :size="16" />绑定点位</span><strong v-if="!editMode">{{ device.point }}</strong><input v-else v-model="form.point" /></div>
            <div><span><MessageSquareText :size="16" />最近上报</span><strong>{{ device.last }}</strong></div>
          </div>
          <div class="task-summary">
            <div class="progress-ring"><span>{{ device.battery }}<small>%</small></span></div>
            <div class="task-overview">
              <strong v-if="!editMode">{{ device.state }}</strong>
              <select v-else v-model="form.state">
                <option value="在线">在线</option>
                <option value="离线">离线</option>
              </select>
              <span>信号：{{ device.signal }}</span>
              <div class="task-bars"><i :style="{ width: `${device.battery}%` }"></i></div>
              <p>支持平台自动巡检和离线告警</p>
            </div>
          </div>
          <div class="heading-actions" style="padding: 0 20px 18px; justify-content: flex-end;">
            <PermissionButton permission="devices:edit" :icon="Save" @click="async ({ allowed }) => {
              if (!allowed) return
              await saveDetail()
            }">保存</PermissionButton>
          </div>
        </template>
      </section>

      <section class="panel warning-panel">
        <div class="panel-header compact">
          <div>
            <h2>数据上报曲线</h2>
            <p>最近一段时间的监测上报趋势</p>
          </div>
        </div>
        <div v-if="device" class="warning-list">
          <article v-for="record in device.history" :key="`${record.time}-${record.title}`" class="warning-item">
            <div class="warning-symbol blue">D</div>
            <div class="warning-body">
              <div class="warning-title"><strong>{{ record.title }}</strong><span class="blue">{{ record.time }}</span></div>
              <p>{{ record.desc }}</p>
            </div>
          </article>
        </div>
        <div v-if="device" class="chart-card">
          <div class="mini-chart-header">
            <strong><Paperclip :size="16" />附件</strong>
            <span>设备说明与校准记录</span>
          </div>
          <div class="pie-list">
            <div v-for="file in device.attachments" :key="file.name" class="pie-item">
              <span>{{ file.name }}</span>
              <div class="pie-track"><i style="width: 100%; background: #4f8f99"></i></div>
              <strong>{{ file.size }}</strong>
            </div>
          </div>
        </div>
        <div v-if="device" class="chart-card">
          <div class="mini-chart-header">
            <strong><GitBranch :size="16" />关联联动</strong>
            <span>点击跳转 · 灾害点 / 预警 / 任务</span>
          </div>
          <div v-if="device.linked?.length" class="pie-list">
            <RouterLink v-for="item in device.linked" :key="`${item.type}-${item.label}`" :to="item.path" class="pie-item link-item">
              <span class="link-type">{{ item.type }}</span>
              <span class="link-label">{{ item.label }}</span>
              <div class="pie-track"><i style="width: 100%; background: #6dbfa5"></i></div>
              <strong class="link-go">查看 ›</strong>
            </RouterLink>
          </div>
          <p v-else class="people-empty" style="padding: 12px;">暂无关联记录</p>
        </div>
      </section>
    </div>
  </div>
</template>
