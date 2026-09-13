<script setup>
/**
 * 解除预警通知预览 - 用于 WarningResolvePage 中实时文案生成。
 */
import { computed } from 'vue'
import { IdCard } from 'lucide-vue-next'

const props = defineProps({
  warning: { type: Object, required: true },
  reason:  { type: String, default: '' },
  scope:   { type: String, default: 'ALL' },
  scopeArea: { type: String, default: '' },
  channels: { type: Array, default: () => [] },
})

const CHANNEL_LABEL = {
  SMS: '短信',
  APP: 'APP 推送',
  SCREEN: '指挥大屏',
  EMAIL: '邮件',
  PHONE: '电话',
}

const generatedText = computed(() => {
  const now = new Date().toLocaleString('zh-CN', { hour12: false })
  const scopeText = props.scope === 'ALL'
    ? '全部'
    : (props.scope === 'PART' ? (props.scopeArea || '部分区域') : props.scope)
  return `【解除预警】${props.warning?.title ?? '灾害预警'} 已解除
解除时间：${now}
解除原因：${props.reason || '现场核查，风险已消除'}
解除范围：${scopeText}
请相关人员知悉。`
})

const title = computed(() => generatedText.value.split('\n')[0])
const body  = computed(() => generatedText.value.split('\n').slice(1).join('\n'))
</script>

<template>
  <article class="preview">
    <header>
      <IdCard :size="14" /> 解除通知预览
    </header>
    <div class="box">
      <h4>{{ title }}</h4>
      <pre>{{ body }}</pre>
    </div>
    <footer>
      <span>送达渠道：</span>
      <span v-for="c in channels" :key="c" class="tag">{{ CHANNEL_LABEL[c] || c }}</span>
      <span v-if="!channels.length" class="muted">未选择渠道</span>
    </footer>
  </article>
</template>

<style scoped>
.preview { display: flex; flex-direction: column; gap: 10px; }
header { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: var(--dashboard-muted, #4b5563); }
.box {
  border: 1px solid var(--dashboard-border, #eef2f7); border-radius: 10px;
  padding: 14px 16px; background: linear-gradient(180deg, #f8fafc, #fff);
}
.box h4 { margin: 0 0 8px; font-size: 15px; color: #0f172a; }
.box pre {
  margin: 0; white-space: pre-wrap; word-break: break-word;
  font-family: inherit; font-size: 13px; color: var(--dashboard-text, #1e293b); line-height: 1.6;
}
footer { display: inline-flex; align-items: center; flex-wrap: wrap; gap: 6px; font-size: 12px; color: var(--dashboard-muted, #64748b); }
.tag {
  background: #eef4ff; color: #2563eb; padding: 2px 8px; border-radius: 999px; font-size: 11px;
}
.muted { color: #94a3b8; }
</style>
