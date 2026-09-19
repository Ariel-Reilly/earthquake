<script setup>
import { computed } from "vue";

const props = defineProps({
  person: { type: Object, default: null },
  visible: { type: Boolean, default: false },
});

defineEmits(["close"]);

const routeText = computed(() => {
  const route = props.person?.route ?? [];
  if (!route.length) return "暂无轨迹";
  return route
    .map(
      (step) =>
        `${Number(step.lon).toFixed(4)}, ${Number(step.lat).toFixed(4)}`,
    )
    .join(" → ");
});
</script>

<template>
  <div v-if="visible && person" class="personnel-tooltip">
    <button class="close-popover" @click="$emit('close')">×</button>
    <div class="personnel-tooltip-head">
      <strong>{{ person.name }}</strong>
      <span>{{ person.state }}</span>
    </div>
    <p>{{ person.role }} · {{ person.team }}</p>
    <div class="personnel-tooltip-meta">
      <span>速度 {{ person.speed }} km/h</span>
      <span>更新时间 {{ person.updatedAt }}</span>
    </div>
    <div class="personnel-tooltip-route">轨迹 {{ routeText }}</div>
  </div>
</template>
