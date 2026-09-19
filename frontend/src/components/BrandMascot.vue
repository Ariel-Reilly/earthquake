<script setup>
defineProps({
  size: { type: Number, default: 120 },
})
</script>

<template>
  <div class="mascot" :style="{ width: size + 'px', height: size + 'px' }">
    <img src="/mascot.jpg" alt="地脉智巡品牌形象" class="mascot-img" />
    <div class="mascot-shadow" />
  </div>
</template>

<style scoped>
.mascot {
  position: relative;
  display: inline-block;
  animation: mascot-float 4s ease-in-out infinite;
}

.mascot-img {
  width: 100%;
  height: 100%;
  object-fit: contain;
  border-radius: 50%;
  filter: drop-shadow(0 8px 20px rgba(15, 78, 71, 0.2));
}

.mascot-shadow {
  position: absolute;
  bottom: -6px;
  left: 50%;
  transform: translateX(-50%);
  width: 60%;
  height: 10px;
  border-radius: 50%;
  background: radial-gradient(ellipse, rgba(15, 78, 71, 0.15), transparent 70%);
  animation: shadow-pulse 4s ease-in-out infinite;
  z-index: -1;
}

@keyframes mascot-float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-8px); }
}

@keyframes shadow-pulse {
  0%, 100% { width: 60%; opacity: 0.15; }
  50% { width: 45%; opacity: 0.08; }
}

@media (prefers-reduced-motion: reduce) {
  .mascot, .mascot-shadow { animation: none; }
}
</style>
