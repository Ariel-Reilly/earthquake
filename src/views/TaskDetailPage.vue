<script setup>
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, CalendarDays, FileText, Gauge, GitBranch, History, PencilLine, Save, UsersRound, Paperclip } from 'lucide-vue-next'
import PermissionButton from '../components/PermissionButton.vue'
import { dashboardApi } from '../services/dashboardApi'

const route = useRoute()
const router = useRouter()
const task = ref(null)
const loading = ref(true)
const editMode = ref(false)
const form = ref({})

async function loadData() {
  loading.value = true
  const data = await dashboardApi.getTaskDetail(route.params.id)
  task.value = data.item
  form.value = {
    name: data.item.name,
    type: data.item.type,
    owner: data.item.owner,
    due: data.item.due,
    status: data.item.status,
    progress: data.item.progress,
  }
  loading.value = false
}

async function saveDetail() {
  if (!task.value) return
  const result = await dashboardApi.updateTask(task.value.id, { ...form.value, progress: Number(form.value.progress) })
  task.value = { ...task.value, ...result.item }
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
            <h2>巡检任务详情</h2>
            <p>任务信息、编辑与执行进度</p>
          </div>
          <div class="heading-actions">
            <button class="text-btn" @click="router.back()"><ArrowLeft :size="15" />返回</button>
            <button class="text-btn" @click="refreshDetail"><History :size="15" />刷新</button>
            <button class="primary-btn" @click="editMode = !editMode"><PencilLine :size="15" />{{ editMode ? '取消编辑' : '编辑详情' }}</button>
          </div>
        </div>
        <div v-if="loading" style="padding: 0 20px 18px; color: #7a8d89;">加载中...</div>
        <template v-else-if="task">
          <div class="system-list">
            <div><span><FileText :size="16" />任务名称</span><strong v-if="!editMode">{{ task.name }}</strong><input v-else v-model="form.name" /></div>
            <div><span><UsersRound :size="16" />负责人</span><strong v-if="!editMode">{{ task.owner }}</strong><input v-else v-model="form.owner" /></div>
            <div><span><CalendarDays :size="16" />任务类型</span><strong v-if="!editMode">{{ task.type }}</strong><input v-else v-model="form.type" /></div>
            <div><span><Gauge :size="16" />完成进度</span><strong v-if="!editMode">{{ task.progress }}%</strong><input v-else v-model="form.progress" type="number" min="0" max="100" /></div>
          </div>
          <div class="task-summary">
            <div class="progress-ring"><span>{{ task.progress }}<small>%</small></span></div>
            <div class="task-overview">
              <strong v-if="!editMode">截止 {{ task.due }}</strong>
              <input v-else v-model="form.due" />
              <span>任务状态：{{ task.status }}</span>
              <div class="task-bars"><i :style="{ width: `${task.progress}%` }"></i></div>
              <p>完成后请上传图片与位置信息</p>
            </div>
          </div>
          <div class="heading-actions" style="padding: 0 20px 18px; justify-content: flex-end;">
            <PermissionButton permission="tasks:edit" :icon="Save" @click="async ({ allowed }) => {
              if (!allowed) return
              await saveDetail()
            }">保存</PermissionButton>
          </div>
        </template>
      </section>

      <section class="panel warning-panel">
        <div class="panel-header compact">
          <div>
            <h2>任务过程记录</h2>
            <p>执行轨迹、附件与关联联动</p>
          </div>
        </div>
        <div v-if="task" class="warning-list">
          <article v-for="record in task.history" :key="`${record.time}-${record.title}`" class="warning-item">
            <div class="warning-symbol blue">R</div>
            <div class="warning-body">
              <div class="warning-title"><strong>{{ record.title }}</strong><span class="blue">{{ record.time }}</span></div>
              <p>{{ record.desc }}</p>
            </div>
          </article>
        </div>
        <div v-if="task" class="chart-card">
          <div class="mini-chart-header">
            <strong><Paperclip :size="16" />附件</strong>
            <span>3 个文件</span>
          </div>
          <div class="pie-list">
            <div v-for="file in task.attachments" :key="file.name" class="pie-item">
              <span>{{ file.name }}</span>
              <div class="pie-track"><i style="width: 100%; background: #4f8f99"></i></div>
              <strong>{{ file.size }}</strong>
            </div>
          </div>
        </div>
        <div v-if="task" class="chart-card">
          <div class="mini-chart-header">
            <strong><GitBranch :size="16" />关联联动</strong>
            <span>点击跳转 · 灾害点 / 预警 / 设备</span>
          </div>
          <div v-if="task.linked?.length" class="pie-list">
            <RouterLink v-for="item in task.linked" :key="`${item.type}-${item.label}`" :to="item.path" class="pie-item link-item">
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
