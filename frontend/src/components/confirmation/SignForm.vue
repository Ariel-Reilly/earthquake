<script setup>
/**
 * 会签表单：选择会签人、并行/串行、超时配置、会签意见。
 */
import { computed, reactive, ref, watch } from 'vue'
import { CheckSquare, Clock, Loader2, Send, Users } from 'lucide-vue-next'

const props = defineProps({
  record:  { type: Object, default: null },
  experts: { type: Array,  required: true },
  modeOptions:    { type: Array, required: true },
  timeoutOptions: { type: Array, default: () => [12, 24, 48, 72] },
})
const emit = defineEmits(['submit', 'cancel'])

const signers = ref(props.record?.signers?.map((s) => s.signerId) ?? [])
const mode    = ref(props.record?.mode ?? 'PARALLEL')
const timeout = ref(props.record?.timeout ?? 24)
const timeoutAction = ref(props.record?.timeoutAction ?? 'ESCALATE')
const comment = ref(props.record?.comment ?? '')
const submitting = ref(false)

const canSubmit = computed(() =>
  signers.value.length > 0 && signers.value.length <= 5 && comment.value.trim().length > 0
)

function toggleSigner(id) {
  const idx = signers.value.indexOf(id)
  if (idx >= 0) signers.value.splice(idx, 1)
  else if (signers.value.length < 5) signers.value.push(id)
}

function submit() {
  if (!canSubmit.value) return
  submitting.value = true
  emit('submit', {
    signers: signers.value,
    mode: mode.value,
    timeout: Number(timeout.value),
    timeoutAction: timeoutAction.value,
    comment: comment.value.trim(),
  })
}

watch(comment, (v) => { if (v.length > 500) comment.value = v.slice(0, 500) })
</script>

<template>
  <form class="sign-form" @submit.prevent="submit">
    <header><Users :size="16" /> 选择会签人（最多 5 名）</header>
    <div class="experts">
      <button
        v-for="exp in experts"
        :key="exp.id"
        type="button"
        class="expert"
        :class="{ selected: signers.includes(exp.id) }"
        :disabled="!signers.includes(exp.id) && signers.length >= 5"
        @click="toggleSigner(exp.id)"
      >
        <CheckSquare v-if="signers.includes(exp.id)" :size="14" />
        <div class="avatar" :data-id="exp.id[6]">{{ exp.name?.[0] ?? '专' }}</div>
        <div class="meta">
          <strong>{{ exp.name }}</strong>
          <small>{{ exp.specialty }} · {{ exp.org }}</small>
        </div>
      </button>
    </div>
    <small v-if="signers.length >= 5" class="warn">已达上限 5 人。</small>

    <div class="row">
      <label>会签方式</label>
      <div class="radios">
        <label v-for="opt in modeOptions" :key="opt.value" :class="{ active: mode === opt.value }">
          <input type="radio" :value="opt.value" v-model="mode" />
          <strong>{{ opt.label }}</strong>
          <small>{{ opt.desc }}</small>
        </label>
      </div>
    </div>

    <div class="row two">
      <div>
        <label><Clock :size="13" /> 超时时间</label>
        <select v-model="timeout" class="select">
          <option v-for="h in timeoutOptions" :key="h" :value="h">{{ h }} 小时</option>
        </select>
      </div>
      <div>
        <label>超时动作</label>
        <select v-model="timeoutAction" class="select">
          <option value="ESCALATE">自动升级到高级别负责人</option>
          <option value="REJECT">自动驳回视为未通过</option>
        </select>
      </div>
    </div>

    <div class="row">
      <label for="sign-comment">会签意见 <span class="required">*</span></label>
      <textarea id="sign-comment" v-model="comment" rows="3" maxlength="500" placeholder="请说明本次会签的背景、目的、需要专家确认的具体问题" />
    </div>

    <div class="actions">
      <button type="button" class="ghost" @click="emit('cancel')">取消</button>
      <button type="submit" class="primary" :disabled="!canSubmit || submitting">
        <Loader2 v-if="submitting" class="spin" :size="14" />
        <Send v-else :size="14" />
        {{ record ? '更新会签' : '发起会签' }}
      </button>
    </div>
  </form>
</template>

<style scoped>
.sign-form { display: flex; flex-direction: column; gap: 14px; }
header { display: inline-flex; align-items: center; gap: 6px; font-size: 14px; font-weight: 600; }
.experts { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px; }
.expert {
  display: flex; align-items: center; gap: 10px;
  background: var(--dashboard-muted-bg, var(--c-brand-mist)); border: 1px solid var(--dashboard-border, var(--c-brand-soft));
  border-radius: var(--r-md); padding: 10px 12px; cursor: pointer; text-align: left;
  color: var(--dashboard-text, var(--c-scrim));
}
.expert.selected { border-color: var(--c-brand-tint); background: linear-gradient(135deg, var(--sys-blue-soft), var(--sys-blue-soft)); }
.expert:disabled { opacity: 0.4; cursor: not-allowed; }
.avatar {
  width: 36px; height: 36px; border-radius: 50%;
  background: linear-gradient(135deg, var(--c-brand-tint), var(--sys-indigo)); color: white;
  display: flex; align-items: center; justify-content: center;
  font-weight: 700; font-size: 15px;
}
.meta { display: flex; flex-direction: column; gap: 2px; }
.meta strong { font-size: 13px; }
.meta small { font-size: 11px; color: var(--dashboard-muted, var(--c-text-3)); }
.warn { color: var(--c-warning); font-size: 12px; }
.row { display: flex; flex-direction: column; gap: 6px; }
.row label { font-size: 13px; color: var(--dashboard-muted, var(--c-text-2)); font-weight: 500; display: inline-flex; align-items: center; gap: 4px; }
.row.two { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.radios { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.radios label {
  display: flex; flex-direction: column; gap: 2px;
  border: 1px solid var(--dashboard-border, var(--c-brand-soft)); border-radius: var(--r-sm);
  padding: 8px 10px; cursor: pointer; font-size: 12px;
  background: var(--dashboard-muted-bg, var(--c-brand-mist));
}
.radios label.active { border-color: var(--c-brand-tint); background: linear-gradient(135deg, var(--sys-blue-soft), var(--sys-blue-soft)); }
.radios strong { font-size: 13px; color: var(--dashboard-text, var(--c-scrim)); }
.radios small { font-size: 11px; color: var(--dashboard-muted, var(--c-text-2)); }
.select {
  padding: 6px 10px; border-radius: var(--r-sm); border: 1px solid var(--dashboard-border, var(--c-brand-soft));
  background: var(--dashboard-input-bg, var(--c-surface)); font-size: 13px; width: 100%;
}
textarea {
  padding: 10px 12px; border: 1px solid var(--dashboard-border, var(--c-brand-soft)); border-radius: var(--r-sm);
  font-size: 13px; font-family: inherit; min-height: 80px; resize: vertical;
}
textarea:focus { outline: none; border-color: var(--c-brand-tint); box-shadow: 0 0 0 3px rgba(99,199,127,0.15); }
.required { color: var(--c-danger); }
.actions { display: flex; justify-content: flex-end; gap: 8px; }
.primary, .ghost {
  display: inline-flex; align-items: center; gap: 6px; padding: 8px 18px; border-radius: var(--r-sm);
  font-size: 14px; cursor: pointer; border: 1px solid transparent;
}
.primary { background: linear-gradient(135deg, var(--c-brand-tint), var(--c-brand-hover)); color: white; border: none; }
.primary:disabled { opacity: 0.5; cursor: not-allowed; }
.ghost { background: transparent; color: var(--dashboard-muted, var(--c-text-3)); border-color: var(--dashboard-border, var(--c-brand-tint)); }
.spin { animation: rotate 1s linear infinite; }
@keyframes rotate { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
</style>
