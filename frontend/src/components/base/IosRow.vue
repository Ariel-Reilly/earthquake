<script setup>
/**
 * IosRow  ── iOS inset-grouped 列表行
 *
 * 用法:
 *   <IosRow title="告警阈值" detail="80mm/h" chevron @click="open" />
 *   <IosRow :icon="Bell" icon-color="orange" title="消息" subtitle="3 条未读" />
 *   <IosRow title="启用" :switch="true" v-model:switch-value="enabled" />
 */
import { ChevronRight } from 'lucide-vue-next'

const props = defineProps({
  icon: { type: [Object, Function, String], default: null },
  iconColor: { type: String, default: 'green' },
  title: { type: String, default: '' },
  subtitle: { type: String, default: '' },
  detail: { type: String, default: '' },
  chevron: { type: Boolean, default: false },
  switch: { type: Boolean, default: false },
  switchValue: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  active: { type: Boolean, default: false },
  tag: { type: String, default: 'button' },
})

const emit = defineEmits(['click', 'update:switchValue', 'change'])

function onClick(e) {
  if (props.disabled) return
  emit('click', e)
}

function toggleSwitch() {
  if (props.disabled) return
  const next = !props.switchValue
  emit('update:switchValue', next)
  emit('change', next)
}
</script>

<template>
  <component
    :is="tag"
    :class="['ios-row', { 'has-icon': icon, active }]"
    :type="tag === 'button' ? 'button' : undefined"
    :disabled="disabled"
    :aria-selected="active || undefined"
    @click="onClick"
  >
    <span v-if="icon" :class="['ios-row-icon', `tone-${iconColor}`]">
      <component :is="icon" :size="16" />
    </span>

    <span class="ios-row-main">
      <span class="ios-row-title">
        <slot name="title">{{ title }}</slot>
      </span>
      <span v-if="subtitle || $slots.subtitle" class="ios-row-sub">
        <slot name="subtitle">{{ subtitle }}</slot>
      </span>
    </span>

    <span v-if="$slots.detail || detail" class="ios-row-detail">
      <slot name="detail">{{ detail }}</slot>
    </span>

    <!-- iOS 开关 -->
    <button
      v-if="switch"
      type="button"
      role="switch"
      class="ios-switch"
      :aria-checked="switchValue"
      :disabled="disabled"
      @click.stop="toggleSwitch"
    />

    <ChevronRight v-else-if="chevron" class="ios-row-chevron" :size="17" />
  </component>
</template>
