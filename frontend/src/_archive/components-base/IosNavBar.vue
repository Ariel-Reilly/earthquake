<script setup>
/**
 * IosNavBar  ── iOS 大标题导航栏
 *
 * 用法:
 *   <IosNavBar title="灾害点管理" :back="true" action-label="编辑" @action="onEdit">
 *     <template #leading>…</template>
 *   </IosNavBar>
 */
import { ChevronLeft } from 'lucide-vue-next'

defineProps({
  title: { type: String, default: '' },
  /** 大标题(左对齐 34pt) vs 紧凑标题(居中 17pt) */
  large: { type: Boolean, default: true },
  back: { type: Boolean, default: false },
  backLabel: { type: String, default: '返回' },
  actionLabel: { type: String, default: '' },
})

defineEmits(['back', 'action'])
</script>

<template>
  <header class="ios-navbar">
    <div class="ios-navbar-compact">
      <button v-if="back" type="button" class="ios-navbar-back" @click="$emit('back')">
        <ChevronLeft :size="19" />
        <span>{{ backLabel }}</span>
      </button>
      <slot name="leading" />
      <span v-if="!large && title" class="ios-navbar-center">{{ title }}</span>
      <button v-if="actionLabel" type="button" class="ios-navbar-action" @click="$emit('action')">
        {{ actionLabel }}
      </button>
      <slot name="trailing" />
    </div>
    <h1 v-if="large && title" class="ios-navbar-large">{{ title }}</h1>
  </header>
</template>

<style scoped>
.ios-navbar-center {
  position: absolute;
  left: 50%;
  transform: translateX(-50%);
  font-size: var(--text-base);
  font-weight: 600;
  color: var(--c-text);
}
.ios-navbar-compact { position: relative; }
</style>
