<template>
  <svg
    :width="size"
    :height="size"
    viewBox="0 0 40 40"
    fill="none"
    xmlns="http://www.w3.org/2000/svg"
    :aria-label="label"
    role="img"
  >
    <rect width="40" height="40" rx="10" fill="url(#brandGrad)" />
    <circle cx="28" cy="13" r="4.5" fill="white" fill-opacity="0.45" />
    <!-- 峰1 - 主峰 -->
    <path d="M3 30 L13 14 L20 22 L27 10 L37 30 Z" fill="white" />
    <!-- 峰2 - 次峰遮盖左侧底部余量 -->
    <path d="M3 30 L13 14 L20 22 L20 30 Z" fill="rgba(255,255,255,0.18)" />
    <!-- 地面/云层装饰 -->
    <path d="M3 30 L37 30" stroke="white" stroke-opacity="0.3" stroke-width="1.5" stroke-linecap="round" />
    <defs>
      <linearGradient id="brandGrad" x1="0" y1="0" x2="40" y2="40" gradientUnits="userSpaceOnUse">
        <stop offset="0%" stop-color="#3caa82" />
        <stop offset="100%" stop-color="#267a62" />
      </linearGradient>
    </defs>
  </svg>
</template>

<script setup>
defineProps({
  size: {
    type: [Number, String],
    default: 20,
  },
  label: {
    type: String,
    default: "御灾智巡",
  },
});
</script>
