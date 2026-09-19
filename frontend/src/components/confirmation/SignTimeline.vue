<script setup>
/**
 * 会签时间线：展示每位会签人的状态与意见。
 */
import { computed } from 'vue'
import { CheckCircle2, Clock, Loader2, UserPlus, X } from 'lucide-vue-next'

const props = defineProps({
  sign: { type: Object, required: true }, // 含 signers, mode, status, transfers
})

const fmt = (iso) => iso ? new Date(iso).toLocaleString('zh-CN', { hour12: false }) : ''

const items = computed(() => {
  const s = props.sign ?? {}
  if (s.mode === 'SEQUENTIAL') {
    return (s.signers ?? []).slice().sort((a, b) => a.order - b.order)
  }
  return s.signers ?? []
})

function chip(status) {
  switch (status) {
    case 'PENDING':   return { label: '待确认', color: '#f59e0b', icon: Clock }
    case 'CONFIRMED': return { label: '已同意', color: '#10b981', icon: CheckCircle2 }
    case 'REJECTED':  return { label: '已驳回', color: '#ef4444', icon: X }
    default:          return { label: status, color: '#64748b', icon: Clock }
  }
}
</script>

<template>
  <div class="timeline">
    <div v-if="!items.length" class="empty">暂无会签人</div>
    <ul v-else>
      <li v-for="(s, i) in items" :key="s.signerId" :data-state="s.status">
        <div class="dot" :style="{ background: chip(s.status).color }">
          <component :is="chip(s.status).icon" :size="14" />
        </div>
        <div class="meta">
          <div class="row1">
            <strong>{{ s.signerName }}</strong>
            <span class="role">{{ s.specialty }}</span>
            <span class="state">{{ chip(s.status).label }}</span>
          </div>
          <div class="row2">
            <span v-if="s.signedAt" class="time">{{ fmt(s.signedAt) }}</span>
            <span v-else class="time pending">— 等待确认 —</span>
            <span v-if="s.opinion" class="opinion">"{{ s.opinion }}"</span>
          </div>
        </div>
        <div v-if="i < items.length - 1" class="line" />
      </li>
    </ul>

    <div v-if="sign.transfers?.length" class="transfers">
      <h4><UserPlus :size="14" /> 转派历史</h4>
      <ul>
        <li v-for="t in sign.transfers" :key="t.id">
          <strong>{{ t.operator }}</strong> → <em>{{ t.toUser }}</em> @ {{ fmt(t.transferredAt) }}
          <small v-if="t.reason"> · {{ t.reason }}</small>
        </li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.timeline { display: flex; flex-direction: column; gap: 12px; }
.empty { color: var(--dashboard-muted, #94a3b8); font-size: 13px; }
ul { list-style: none; padding: 0; margin: 0; display: flex; flex-direction: column; gap: 8px; position: relative; }
li { display: flex; align-items: flex-start; gap: 12px; position: relative; padding: 4px 0; }
.dot {
  width: 24px; height: 24px; border-radius: 50%;
  display: flex; align-items: center; justify-content: center;
  color: white; flex-shrink: 0;
}
.meta { display: flex; flex-direction: column; gap: 4px; flex: 1; }
.row1 { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.row1 strong { font-size: 14px; color: var(--dashboard-text, #1e293b); }
.role { font-size: 11px; color: var(--dashboard-muted, #64748b); background: #f1f5f9; padding: 1px 8px; border-radius: 999px; }
.state {
  margin-left: auto; font-size: 12px; padding: 2px 8px; border-radius: 999px;
  background: var(--dashboard-muted-bg, #f1f5f9); color: var(--dashboard-muted, #475569);
}
.row2 { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 12px; color: var(--dashboard-muted, #64748b); }
.time.pending { color: #f59e0b; }
.opinion { font-style: italic; color: var(--dashboard-text, #334155); }
.line { position: absolute; left: 12px; top: 28px; bottom: -8px; width: 2px; background: var(--dashboard-border, #e2e8f0); }
.transfers { border-top: 1px dashed var(--dashboard-border, #e2e8f0); padding-top: 10px; }
.transfers h4 { display: inline-flex; align-items: center; gap: 4px; margin: 0 0 6px; font-size: 13px; color: var(--dashboard-muted, #4b5563); }
.transfers ul { gap: 4px; }
.transfers li { font-size: 12px; color: var(--dashboard-muted, #475569); }
</style>
