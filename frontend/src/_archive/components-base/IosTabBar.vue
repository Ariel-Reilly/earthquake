<script setup>
/**
 * IosTabBar  ── iOS 底部 Tab 栏 (移动端主导航)
 *
 * 用法:
 *   <IosTabBar :items="tabs" v-model="activeTab" @select="go" />
 *   items: [{ key, label, icon, badge? }]
 */
defineProps({
  items: { type: Array, required: true },
  modelValue: { type: [String, Number], default: null },
})

defineEmits(['update:modelValue', 'select'])
</script>

<template>
  <nav class="ios-tabbar" role="tablist">
    <button
      v-for="item in items"
      :key="item.key"
      type="button"
      role="tab"
      :class="['ios-tabbar-item', { active: modelValue === item.key }]"
      :aria-selected="modelValue === item.key"
      @click="
        $emit('update:modelValue', item.key);
        $emit('select', item.key)
      "
    >
      <span class="ios-tabbar-icon">
        <component :is="item.icon" :size="23" />
        <span v-if="item.badge" class="ios-tabbar-badge">{{ item.badge }}</span>
      </span>
      <span>{{ item.label }}</span>
    </button>
  </nav>
</template>
