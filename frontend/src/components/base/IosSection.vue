<script setup>
/**
 * IosSection  ── iOS inset-grouped 分组容器
 *
 * 用法:
 *   <IosSection title="基本信息" footer="修改后立即生效">
 *     <IosRow title="设备名称" detail="SB-001" chevron />
 *     <IosRow title="状态" detail="在线" />
 *   </IosSection>
 */
defineProps({
  title: { type: String, default: '' },
  footer: { type: String, default: '' },
  /** 去掉容器白底与投影(嵌套在已有卡片内时用) */
  plain: { type: Boolean, default: false },
})
</script>

<template>
  <section class="ios-section">
    <div v-if="title" class="ios-group-title">{{ title }}</div>
    <div :class="['ios-group', { plain }]">
      <slot />
    </div>
    <div v-if="footer" class="ios-group-footer">{{ footer }}</div>
  </section>
</template>

<style scoped>
.ios-section + .ios-section { margin-top: var(--sp-5); }
.ios-group.plain { box-shadow: none; background: transparent; }
</style>
