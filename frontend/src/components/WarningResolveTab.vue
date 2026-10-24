<script setup>
/**
 * 预警解除 / 归档 Tab 组件 (/dashboard/warnings/:id?tab=resolve)。
 * 必填理由、二次确认、解锁真实 /resolve 端点联动 + 自动归档 + 审计留痕。
 * 
 * 接收 warningId 作为 prop，成功后 emit('success') 通知父组件。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import ConfirmDialog from './common/ConfirmDialog.vue'
import {
  AlertTriangle,
  CheckCircle2,
  Loader2,
  ShieldCheck,
  X,
} from 'lucide-vue-next'
import WarningResolveForm from './warning/WarningResolveForm.vue'
import WarningResolvePreview from './warning/WarningResolvePreview.vue'
import { dashboardApi } from '../services/dashboardApi'
import { getAuthUser, hasPermission } from '../stores/auth'

const emit = defineEmits(['success', 'notify'])
defineProps({
  warningId: { type: String, required: true },
})

const operator = computed(() => getAuthUser() ?? { name: 'system', role: 'admin' })
const canResolve = computed(() => hasPermission(operator.value.role, 'warnings:close'))

const warning = ref(null)
const scopes = ref([])
const loading = ref(false)
const submitting = ref(false)
const submitError = ref('')
const submitOk = ref(false)

const reason  = ref('')
const scope   = ref('ALL')
const channels = ref(['SMS', 'APP', 'SCREEN'])
const scopeArea = ref('')

const previewReason  = computed(() => reason.value)
const previewScope   = computed(() => scope.value)
const previewScopeArea = computed(() => scopeArea.value)
const previewChannels = computed(() => channels.value)

const confirmDialogRef = ref(null)

async function load() {
  loading.value = true
  try {
    const data = await dashboardApi.getWarningDetail(props.warningId)
    warning.value = data?.item ?? data?.warning ?? data
    const s = await dashboardApi.getWarningResolveScope(props.warningId).catch(() => ({}))
    scopes.value = s?.scopes ?? [
      { value: '龙安镇', label: '龙安镇' },
      { value: '石桥镇', label: '石桥镇' },
      { value: '清河镇', label: '清河镇' },
    ]
  } catch (e) {
    emit('notify', e?.message ?? '加载预警失败')
    warning.value = null
  } finally {
    loading.value = false
  }
}

function handleFormSubmit(payload) {
  reason.value = payload.reason
  scope.value  = payload.scope
  scopeArea.value = payload.scopeArea ?? ''
  channels.value = payload.channels
  doResolve()
}

async function doResolve() {
  if (!canResolve.value) {
    submitError.value = '当前角色无解除预警权限（需要 warnings:close）。'
    return
  }
  if (!reason.value.trim()) {
    submitError.value = '请填写解除理由'
    return
  }
  
  const ok = await confirmDialogRef.value?.show({
    title: '确认操作',
    message: '解除预警后无法撤销，确认继续？',
    confirmText: '确认',
    cancelText: '取消',
    isDanger: true
  })
  if (!ok) return
  
  submitting.value = true
  submitError.value = ''
  try {
    await dashboardApi.resolveWarning(warning.value?.id ?? props.warningId, {
      reason: reason.value.trim(),
      scope: scope.value,
      scopeArea: scopeArea.value || null,
      channels: channels.value,
      resolvedBy: operator.value.name,
      resolvedAt: new Date().toISOString(),
    })
    submitOk.value = true
    emit('notify', '预警已解除并自动归档')
    // 触发全局事件：审计 / 通知中心 / 预警列表可选择监听
    window.dispatchEvent(new CustomEvent('disasterguard:warning:resolved', {
      detail: { id: warning.value?.id, reason: reason.value },
    }))
    // 详情细节 2：成功后通知父组件刷新并改变按钮状态
    setTimeout(() => emit('success'), 900)
  } catch (e) {
    submitError.value = e?.message ?? '解除失败，请稍后重试'
  } finally {
    submitting.value = false
  }
}


onMounted(() => {
  if (!canResolve.value) {
    emit('notify', '当前角色无权访问解除页面')
    return
  }
  load()
})
</script>

<template>
  <div class="resolve-tab">
    <div v-if="loading" class="loading">
      <Loader2 class="spin" :size="20" /> 加载预警信息中…
    </div>

    <div v-else-if="!warning" class="empty">
      未找到该预警，可能已解除或归档。
    </div>

    <div v-else class="grid">
      <section class="panel">
        <h3><ShieldCheck :size="16" /> 解除操作</h3>
        <WarningResolveForm :warning="warning" :scopes="scopes" @submit="handleFormSubmit" @cancel="() => {}" />
      </section>

      <section class="panel">
        <h3>通知预览</h3>
        <WarningResolvePreview
          :warning="warning"
          :reason="previewReason"
          :scope="previewScope"
          :scope-area="previewScopeArea"
          :channels="previewChannels"
        />

        <div v-if="submitting" class="status pending">
          <Loader2 class="spin" :size="14" /> 正在解除并归档…
        </div>
        <div v-else-if="submitError" class="status err">
          <X :size="14" /> {{ submitError }}
        </div>
        <div v-else-if="submitOk" class="status ok">
          <CheckCircle2 :size="14" /> 解除成功，即将刷新…
        </div>
      </section>
    </div>
  </div>
  <ConfirmDialog ref="confirmDialogRef" />
</template>

<style scoped>
.resolve-tab { display: flex; flex-direction: column; gap: 18px; }
.loading { display: flex; align-items: center; gap: 8px; color: var(--dashboard-muted, var(--c-text-3)); }
.empty { color: var(--c-danger); }
.grid { display: grid; grid-template-columns: 1.4fr 1fr; gap: 18px; }
@media (max-width: 980px) { .grid { grid-template-columns: 1fr; } }
.panel {
  background: var(--dashboard-card-bg, var(--c-surface));
  border: 1px solid var(--dashboard-border, var(--c-brand-mist)); border-radius: var(--r-lg);
  padding: 18px 20px; box-shadow: 0 4px 12px rgba(0,0,0,0.04);
  display: flex; flex-direction: column; gap: 14px;
}
.panel h3 { margin: 0; font-size: 16px; display: inline-flex; align-items: center; gap: 6px; border-bottom: 1px solid var(--dashboard-border, var(--c-brand-mist)); padding-bottom: 8px; }
.status { display: inline-flex; align-items: center; gap: 6px; padding: 8px 12px; border-radius: var(--r-sm); font-size: 13px; }
.status.pending { background: var(--sys-blue-soft); color: var(--c-brand-deeper); }
.status.err { background: var(--c-danger-bg); color: var(--c-danger-strong); }
.status.ok { background: var(--c-brand-mist); color: var(--c-success-strong); }
.spin { animation: rotate 1s linear infinite; }
@keyframes rotate { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
