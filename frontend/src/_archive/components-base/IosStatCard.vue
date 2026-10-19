<script setup>
/**
 * IosStatCard  ── iOS 18 统计卡
 *
 * 用法:
 *   <IosStatCard
 *     title="高风险灾害点"
 *     value="14"
 *     unit="处"
 *     trend="+3"
 *     trend-direction="up"
 *     icon-color="red"
 *     :icon="MountainSnow"
 *   />
 */
import { computed } from 'vue'
import { TrendingUp, TrendingDown } from 'lucide-vue-next'

const props = defineProps({
  title: { type: String, required: true },
  value: { type: [String, Number], default: '' },
  unit: { type: String, default: '' },
  trend: { type: [String, Number], default: null },
  trendDirection: { type: String, default: 'up' }, // up | down
  icon: { type: [Object, Function, String], default: null },
  iconColor: { type: String, default: 'blue' },
  glass: { type: Boolean, default: false },
  interactive: { type: Boolean, default: false },
})

defineEmits(['click'])

const trendClass = computed(() => {
  if (props.trendDirection === 'down') return 'negative'
  return 'positive'
})
</script>

<template>
  <div
    :class="['ios-stat-card', glass ? 'ios-card-glass' : '', { 'is-interactive': interactive }]"
    :role="interactive ? 'button' : undefined"
    :tabindex="interactive ? 0 : undefined"
    @click="(e) => interactive && $emit('click', e)"
  >
    <div class="ios-stat-head">
      <div v-if="icon" :class="['tint', `tint-${iconColor}`]">
        <component :is="icon" :size="18" />
      </div>
      <span class="ios-stat-title">{{ title }}</span>
    </div>
    <div class="ios-stat-value">
      {{ value }}<small v-if="unit">{{ unit }}</small>
    </div>
    <div v-if="trend != null" :class="['ios-stat-trend', trendClass]">
      <component :is="trendDirection === 'down' ? TrendingDown : TrendingUp" :size="11" />
      <span>{{ trend }}</span>
    </div>
  </div>
</template>

<style scoped>
.ios-stat-card.is-interactive { cursor: pointer; }
.ios-stat-card .tint { width: 32px; height: 32px; border-radius: var(--r-sm); }
</style>
