<script setup>
const props = defineProps({
  person: { type: Object, default: null },
  visible: { type: Boolean, default: false },
})

defineEmits(['close', 'focus'])
</script>

<template>
  <div v-if="visible && person" class="modal-backdrop" @click.self="$emit('close')">
    <div class="modal modal-wide">
      <div class="modal-head">
        <div>
          <span class="eyebrow">PERSONNEL ROUTE</span>
          <h2>{{ person.name }}</h2>
          <p class="modal-subtitle">{{ person.role }} · {{ person.team }} · {{ person.state }}</p>
        </div>
        <button @click="$emit('close')">×</button>
      </div>
      <div class="modal-body">
        <div class="route-summary">
          <div>当前位置</div>
          <strong>{{ Number(person.lon).toFixed(5) }}, {{ Number(person.lat).toFixed(5) }}</strong>
        </div>
        <div class="route-track">
          <div v-for="(step, index) in person.route || []" :key="`${step.at}-${index}`" class="route-step">
            <span>{{ index + 1 }}</span>
            <div>
              <strong>{{ Number(step.lon).toFixed(5) }}, {{ Number(step.lat).toFixed(5) }}</strong>
              <small>{{ step.at }}</small>
            </div>
          </div>
        </div>
      </div>
      <div class="modal-foot">
        <button class="secondary-btn" @click="$emit('close')">关闭</button>
        <button class="primary-btn" @click="$emit('focus', person)">聚焦轨迹</button>
      </div>
    </div>
  </div>
</template>
