<script setup>
/**
 * 解除预警表单：
 * - 必填解除理由（自带模板）
 * - 解除范围（全部 / 部分）
 * - 复选通知渠道
 * - 提交后调用 /warnings/{id}/resolve 并跳转到预警列表
 */
import { computed, reactive, ref, watch } from 'vue'
import { Loader2, Send, Cpu } from 'lucide-vue-next'

const props = defineProps({
  warning: { type: Object, required: true },
  scopes:  { type: Array, default: () => [] },
})
const emit = defineEmits(['submit', 'cancel'])

const reason = ref('')
const scope  = ref('ALL')
const scopeArea = ref('')
const channels = reactive({ SMS: true, APP: true, SCREEN: true, EMAIL: false, PHONE: false })

const TEMPLATES = [
  '现场核查位移速率恢复正常，风险已消除。',
  '应急响应已结束，本次预警风险已消除。',
  '现场复核确认威胁消除，预警予以解除，请继续关注后续监测。',
]

const canSubmit = computed(() =>
  !!reason.value.trim() && (scope.value !== 'PART' || !!scopeArea.value)
)

const selectedChannels = computed(() =>
  Object.entries(channels).filter(([, v]) => v).map(([k]) => k)
)

function pickTemplate(t) {
  reason.value = t
}

function submit() {
  if (!canSubmit.value) return
  emit('submit', {
    warningId: props.warning?.id ?? props.warning?.warningCode,
    reason: reason.value.trim(),
    scope: scope.value,
    scopeArea: scope.value === 'PART' ? scopeArea.value : null,
    channels: selectedChannels.value,
  })
}

watch(reason, (v) => {
  if (v.length > 500) reason.value = v.slice(0, 500)
})
</script>

<template>
  <form class="resolve-form" @submit.prevent="submit">
    <section class="field">
      <label>解除预警：<strong>{{ warning?.title ?? '未命名预警' }}</strong></label>
      <p class="meta">
        级别：{{ warning?.warningLevel ?? '—' }} ·
        范围：{{ warning?.scope ?? '—' }} ·
        发布时间：{{ warning?.triggeredAt || warning?.publishTime || '—' }}
      </p>
    </section>

    <section class="field">
      <label for="reason">解除理由 <span class="required">*</span></label>
      <textarea
        id="reason"
        v-model="reason"
        rows="3"
        maxlength="500"
        placeholder="请说明解除本预警的具体原因"
      />
      <small class="hint">常用模板：</small>
      <div class="templates">
        <button type="button" v-for="t in TEMPLATES" :key="t" @click="pickTemplate(t)">
          <Cpu :size="12" /> {{ t }}
        </button>
      </div>
    </section>

    <section class="field">
      <label>解除范围</label>
      <div class="radios">
        <label>
          <input type="radio" v-model="scope" value="ALL" /> 全部
        </label>
        <label>
          <input type="radio" v-model="scope" value="PART" /> 部分（请选择）
        </label>
      </div>
      <select v-if="scope === 'PART'" v-model="scopeArea" class="select">
        <option value="">请选择区域</option>
        <option v-for="s in scopes" :key="s.value" :value="s.value">{{ s.label }}</option>
      </select>
    </section>

    <section class="field">
      <label>通知渠道</label>
      <div class="channels">
        <label v-for="(_, k) in channels" :key="k" :class="{ checked: channels[k] }">
          <input type="checkbox" v-model="channels[k]" />
          <span>{{ ({ SMS: '短信', APP: 'APP', SCREEN: '指挥大屏', EMAIL: '邮件', PHONE: '电话' })[k] }}</span>
        </label>
      </div>
    </section>

    <div class="actions">
      <button type="button" class="ghost" @click="emit('cancel')">取消</button>
      <button type="submit" class="primary" :disabled="!canSubmit">
        <Send :size="14" /> 确认解除
      </button>
    </div>
  </form>
</template>

<style scoped>
.resolve-form { display: flex; flex-direction: column; gap: 18px; }
.field { display: flex; flex-direction: column; gap: 6px; }
.field label { font-size: 13px; color: var(--dashboard-muted, #4b5563); font-weight: 500; }
.required { color: #ef4444; }
.meta { margin: 4px 0 0; font-size: 12px; color: var(--dashboard-muted, #6b7280); }
textarea {
  border: 1px solid var(--dashboard-border, #e2e8f0); border-radius: 8px;
  padding: 10px 12px; font-size: 14px; resize: vertical; min-height: 80px;
  font-family: inherit;
  background: var(--dashboard-input-bg, #fff);
}
textarea:focus { outline: none; border-color: #5b8ef9; box-shadow: 0 0 0 3px rgba(91,142,249,0.15); }
.hint { color: var(--dashboard-muted, #64748b); margin-top: 6px; font-size: 12px; }
.templates { display: flex; flex-direction: column; gap: 4px; }
.templates button {
  text-align: left; background: var(--dashboard-muted-bg, #f1f5f9);
  border: 1px dashed var(--dashboard-border, #cbd5e1); border-radius: 6px;
  padding: 6px 10px; cursor: pointer; font-size: 12px;
  display: inline-flex; align-items: center; gap: 4px;
}
.templates button:hover { border-color: #5b8ef9; color: #2563eb; }
.radios { display: flex; gap: 14px; font-size: 13px; }
.radios label { display: inline-flex; align-items: center; gap: 4px; cursor: pointer; }
.select {
  padding: 6px 10px; border-radius: 8px; border: 1px solid var(--dashboard-border, #e2e8f0);
  background: var(--dashboard-input-bg, #fff);
}
.channels { display: flex; flex-wrap: wrap; gap: 8px; }
.channels label {
  display: inline-flex; align-items: center; gap: 4px;
  padding: 4px 10px; border-radius: 999px; cursor: pointer; font-size: 13px;
  border: 1px solid var(--dashboard-border, #e2e8f0);
  background: var(--dashboard-muted-bg, #f8fafc);
}
.channels label.checked { background: linear-gradient(135deg, #eef4ff, #e0ecff); border-color: #5b8ef9; color: #2563eb; }
.actions { display: flex; justify-content: flex-end; gap: 8px; }
.primary, .ghost {
  display: inline-flex; align-items: center; gap: 6px;
  padding: 8px 18px; border-radius: 8px; cursor: pointer; font-size: 14px;
  border: 1px solid transparent;
}
.primary { background: linear-gradient(135deg, #ef4444, #dc2626); color: white; border: none; }
.primary:disabled { opacity: 0.5; cursor: not-allowed; }
.ghost { background: transparent; color: var(--dashboard-muted, #64748b); border-color: var(--dashboard-border, #cbd5e1); }
</style>
