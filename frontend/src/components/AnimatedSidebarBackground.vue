<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'

const canvasRef = ref(null)
let animationId = null
let frame = null

const prefersReduced = typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)')?.matches === true

onMounted(() => {
  const canvas = canvasRef.value
  if (!canvas) return
  const ctx = canvas.getContext('2d')

  let width = 0
  let height = 0
  let particles = []
  const mouse = { x: -9999, y: -9999 }

  // 优化配色方案 - 更有层次感
  const COLORS = [
    'rgba(99, 209, 156, ',   // 绿色 - 主色调
    'rgba(94, 168, 158, ',   // 青色 - 侧边栏主色
    'rgba(105, 156, 206, ',  // 蓝色 - 科技感
    'rgba(244, 200, 123, ',   // 金色 - 强调色
  ]

  function spawn() {
    return {
      x: Math.random() * width,
      y: Math.random() * height,
      vx: (Math.random() - 0.5) * 0.25,
      vy: (Math.random() - 0.5) * 0.25,
      phase: Math.random() * Math.PI * 2,
      freq: 0.2 + Math.random() * 0.4,
      drift: 0.05 + Math.random() * 0.12,
      r: 0.6 + Math.random() * 1.8,
      color: COLORS[Math.floor(Math.random() * COLORS.length)],
      alpha: 0.15 + Math.random() * 0.25,
      // 新增：星芒效果
      hasGlow: Math.random() > 0.7,
      glowPhase: Math.random() * Math.PI * 2,
    }
  }

  function resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const nextWidth = canvas.clientWidth
    const nextHeight = canvas.clientHeight
    if (!nextWidth || !nextHeight) return
    // 根据侧边栏宽度调整粒子数量
    const target = Math.min(48, Math.max(14, Math.round((nextWidth * nextHeight) / 7500)))

    canvas.width = Math.round(nextWidth * dpr)
    canvas.height = Math.round(nextHeight * dpr)
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)

    const prevWidth = width
    const prevHeight = height
    width = nextWidth
    height = nextHeight

    if (particles.length && prevWidth > 0 && prevHeight > 0) {
      const sx = nextWidth / prevWidth
      const sy = nextHeight / prevHeight
      for (const p of particles) {
        p.x *= sx
        p.y *= sy
        p.vx *= Math.sqrt(sx * sy)
        p.vy *= Math.sqrt(sy * sy)
      }
      while (particles.length < target) particles.push(spawn())
      if (particles.length > target) particles.length = target
    } else {
      particles = Array.from({ length: target }, spawn)
    }
  }

  function draw(animate) {
    const t = performance.now() / 1000
    ctx.clearRect(0, 0, width, height)

    for (const p of particles) {
      // 改进运动算法 - 更自然
      p.vx += Math.sin(t * p.freq + p.phase) * p.drift * 0.04
      p.vy += Math.cos(t * p.freq * 0.85 + p.phase) * p.drift * 0.04

      // 鼠标交互 - 更柔和的排斥效果
      const dx = p.x - mouse.x
      const dy = p.y - mouse.y
      const dist = Math.hypot(dx, dy)
      if (dist < 120 && dist > 0) {
        const force = Math.pow((120 - dist) / 120, 2) * 0.018
        p.vx += (dx / dist) * force
        p.vy += (dy / dist) * force
      }

      // 速度限制 - 更平滑
      const speed = Math.hypot(p.vx, p.vy)
      if (speed > 0.85) {
        p.vx *= 0.985
        p.vy *= 0.985
      }

      p.x += p.vx
      p.y += p.vy

      // 边界循环
      if (p.x < -24) p.x = width + 24
      if (p.x > width + 24) p.x = -24
      if (p.y < -24) p.y = height + 24
      if (p.y > height + 24) p.y = -24
    }

    ctx.lineWidth = 0.8
    // 改进连线效果
    for (let i = 0; i < particles.length; i++) {
      const a = particles[i]
      for (let j = i + 1; j < particles.length; j++) {
        const b = particles[j]
        const d = Math.hypot(a.x - b.x, a.y - b.y)
        if (d < 90) {
          const opacity = (1 - d / 90) * 0.1
          ctx.strokeStyle = `rgba(94, 168, 158, ${opacity})`
          ctx.beginPath()
          ctx.moveTo(a.x, a.y)
          ctx.lineTo(b.x, b.y)
          ctx.stroke()
        }
      }
      // 鼠标连线效果
      const md = Math.hypot(a.x - mouse.x, a.y - mouse.y)
      if (md < 130) {
        ctx.strokeStyle = `rgba(99, 209, 156, ${(1 - md / 130) * 0.18})`
        ctx.beginPath()
        ctx.moveTo(a.x, a.y)
        ctx.lineTo(mouse.x, mouse.y)
        ctx.stroke()
      }

      // 星芒效果
      if (a.hasGlow) {
        const glowAlpha = a.alpha * (0.5 + 0.5 * Math.sin(t * 2 + a.glowPhase))
        ctx.beginPath()
        ctx.arc(a.x, a.y, a.r * 1.5, 0, Math.PI * 2)
        ctx.fillStyle = a.color + glowAlpha * 0.5 + ')'
        ctx.fill()
      }

      // 主粒子
      ctx.beginPath()
      ctx.arc(a.x, a.y, a.r, 0, Math.PI * 2)
      ctx.fillStyle = a.color + a.alpha + ')'
      ctx.fill()
    }

    if (animate) {
      animationId = requestAnimationFrame(() => draw(true))
    }
  }

  function onMouseMove(event) {
    const rect = canvas.getBoundingClientRect()
    mouse.x = event.clientX - rect.left
    mouse.y = event.clientY - rect.top
  }

  function onMouseLeave() {
    mouse.x = -9999
    mouse.y = -9999
  }

  let resizeFrame = null
  function onResize() {
    if (resizeFrame) return
    resizeFrame = requestAnimationFrame(() => {
      resizeFrame = null
      resize()
      draw(false)
    })
  }

  resize()
  draw(!prefersReduced)

  const resizeObserver = typeof ResizeObserver !== 'undefined'
    ? new ResizeObserver(onResize)
    : null
  resizeObserver?.observe(canvas)

  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('mouseout', onMouseLeave)

  frame = () => {
    if (resizeFrame) cancelAnimationFrame(resizeFrame)
    resizeFrame = null
    resizeObserver?.disconnect()
    window.removeEventListener('mousemove', onMouseMove)
    window.removeEventListener('mouseout', onMouseLeave)
  }
})

onBeforeUnmount(() => {
  if (animationId) cancelAnimationFrame(animationId)
  animationId = null
  if (frame) frame()
  frame = null
})
</script>

<template>
  <div class="sidebar-bg" aria-hidden="true">
    <!-- 装饰性渐变 blob -->
    <div class="sidebar-bg-blob sidebar-bg-blob-1"></div>
    <div class="sidebar-bg-blob sidebar-bg-blob-2"></div>
    <div class="sidebar-bg-blob sidebar-bg-blob-3"></div>
    <!-- 底部渐变 -->
    <div class="sidebar-bg-gradient-bottom"></div>
    <!-- 顶部渐变 -->
    <div class="sidebar-bg-gradient-top"></div>
    <!-- 暗角效果 -->
    <div class="sidebar-bg-vignette"></div>
    <!-- 网格纹理 -->
    <div class="sidebar-bg-grid"></div>
    <!-- 粒子画布 -->
    <canvas ref="canvasRef" class="sidebar-bg-canvas"></canvas>
  </div>
</template>

<style scoped>
.sidebar-bg {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
}

/* 装饰性渐变 blob */
.sidebar-bg-blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(72px);
  opacity: 0.28;
  will-change: transform;
}

.sidebar-bg-blob-1 {
  width: 320px;
  height: 320px;
  left: -140px;
  top: -100px;
  background: radial-gradient(circle at 34% 34%, #2f8f7a, #12383e 68%);
  animation: sidebar-blob-float 22s ease-in-out infinite;
}

.sidebar-bg-blob-2 {
  width: 380px;
  height: 380px;
  right: -180px;
  top: 30%;
  background: radial-gradient(circle at 58% 42%, #3a7d8f, #12383e 70%);
  animation: sidebar-blob-float 28s ease-in-out infinite reverse;
}

.sidebar-bg-blob-3 {
  width: 300px;
  height: 300px;
  left: 18%;
  bottom: -160px;
  background: radial-gradient(circle at 50% 50%, #9a6f3e, #12383e 72%);
  animation: sidebar-blob-float 34s ease-in-out infinite;
}

/* 底部渐变 - 增加深度感 */
.sidebar-bg-gradient-bottom {
  position: absolute;
  bottom: 0;
  left: 0;
  right: 0;
  height: 200px;
  background: linear-gradient(to top,
    rgba(7, 27, 29, 0.6) 0%,
    rgba(18, 56, 62, 0.3) 40%,
    transparent 100%
  );
  pointer-events: none;
}

/* 顶部渐变 - 与顶部装饰线呼应 */
.sidebar-bg-gradient-top {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 120px;
  background: linear-gradient(to bottom,
    rgba(47, 143, 122, 0.15) 0%,
    rgba(18, 56, 62, 0.08) 50%,
    transparent 100%
  );
  pointer-events: none;
}

/* 暗角效果 */
.sidebar-bg-vignette {
  position: absolute;
  inset: 0;
  background: radial-gradient(120% 86% at 36% 12%, transparent 38%, rgba(7, 27, 29, 0.45) 100%);
  mix-blend-mode: multiply;
}

/* 网格纹理 - 增加科技感 */
.sidebar-bg-grid {
  position: absolute;
  inset: 0;
  opacity: 0.04;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.1) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.1) 1px, transparent 1px);
  background-size: 24px 24px;
  pointer-events: none;
}

/* 粒子画布 */
.sidebar-bg-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

/* 减少动画偏好 */
@media (prefers-reduced-motion: reduce) {
  .sidebar-bg-blob {
    animation: none;
  }
}

/* Blob 浮动动画 */
@keyframes sidebar-blob-float {
  0%, 100% { transform: translate(0, 0) scale(1); }
  25% { transform: translate(28px, -22px) scale(1.05); }
  50% { transform: translate(-18px, 16px) scale(0.96); }
  75% { transform: translate(22px, 12px) scale(1.02); }
}
</style>
