<script setup>
/**
 * IosListItem  ── iOS 18 列表行 (inset grouped)
 *
 * 用法:
 *   <IosListItem icon-color="blue" :icon="Bell" chevron>消息</IosListItem>
 *   <IosListItem title="标题" subtitle="说明" detail="详情" />
 */
import { computed } from 'vue'
import { ChevronRight } from 'lucide-vue-next'

const props = defineProps({
  icon: { type: [Object, Function, String], default: null },
  iconColor: { type: String, default: 'blue' },
  title: { type: String, default: '' },
  subtitle: { type: String, default: '' },
  detail: { type: String, default: '' },
  chevron: { type: Boolean, default: false },
  last: { type: Boolean, default: false },
  to: { type: [String, Object], default: null },
  href: { type: String, default: null },
})

defineEmits(['click'])

const isLink = computed(() => Boolean(props.to || props.href))

function handleClick(e) {
  if (props.to) {
    // 仅在有 vue-router 时跳转
    import('vue-router').then(({ useRouter }) => {
      const router = useRouter()
      router.push(props.to)
    }).catch(() => {})
  } else if (props.href) {
    window.location.href = props.href
  }
  return e
}
</script>

<template>
  <component
    :is="isLink ? 'a' : 'div'"
    :class="['ios-list-item', { 'ios-list-item-last': last }]"
    :href="href || undefined"
    @click="(e) => { $emit('click', e); handleClick(e) }"
  >
    <div v-if="icon" :class="['ios-list-icon', `tone-${iconColor}`]">
      <component :is="icon" :size="16" />
    </div>
    <div class="ios-list-title">
      <slot>
        <div class="ios-list-title-text">{{ title }}</div>
        <div v-if="subtitle" class="ios-list-subtitle">{{ subtitle }}</div>
      </slot>
    </div>
    <div v-if="detail || $slots.detail" class="ios-list-detail">
      <slot name="detail">{{ detail }}</slot>
    </div>
    <ChevronRight v-if="chevron" :size="16" class="ios-list-chevron" />
  </component>
</template>

<style scoped>
.ios-list-item { text-decoration: none; }
.ios-list-icon {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  border-radius: var(--r-sm);
  background: var(--c-brand-light);
  color: var(--ios-blue);
  flex: 0 0 auto;
}
.ios-list-item :deep(.ios-list-icon.tone-blue)   { background: var(--c-brand-light);   color: var(--ios-blue); }
.ios-list-item :deep(.ios-list-icon.tone-green)  { background: var(--ios-fill-1);   color: var(--ios-green); }
.ios-list-item :deep(.ios-list-icon.tone-orange) { background: color-mix(in srgb, var(--c-warning) 18%, transparent)
    /* = rgba 255,149,0/.18 */;   color: var(--ios-orange); }
.ios-list-item :deep(.ios-list-icon.tone-red)    { background: var(--c-danger-bg);   color: var(--ios-red); }
.ios-list-item :deep(.ios-list-icon.tone-purple) { background: color-mix(in srgb, var(--sys-purple) 16%, transparent)
    /* = rgba 175,82,222/.16 */;  color: var(--ios-purple); }
.ios-list-item :deep(.ios-list-icon.tone-indigo) { background: color-mix(in srgb, var(--sys-indigo) 16%, transparent)
    /* = rgba 88,86,214/.16 */;   color: var(--ios-indigo); }
.ios-list-item :deep(.ios-list-icon.tone-cyan)   { background: color-mix(in srgb, var(--sys-cyan) 18%, transparent)
    /* = rgba 92,200,250/.18 */;  color: var(--ios-cyan); }
.ios-list-item :deep(.ios-list-icon.tone-gray)   { background: color-mix(in srgb, var(--sys-gray) 16%, transparent)
    /* = rgba 142,142,147/.16 */; color: var(--ios-label-2); }

.ios-list-title { flex: 1; min-width: 0; }
.ios-list-title-text {
  color: var(--ios-label);
  font-size: 15px;
  font-weight: 500;
  line-height: 1.3;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.ios-list-subtitle {
  color: var(--ios-label-3);
  font-size: 12px;
  margin-top: 2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
</style>
