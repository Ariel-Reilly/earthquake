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

  const COLORS = ['rgba(111, 158, 127, ', 'rgba(135, 176, 150, ', 'rgba(157, 192, 170, ', 'rgba(84, 128, 106, ']

  function spawn() {
    return {
      x: Math.random() * width,
      y: Math.random() * height,
      vx: (Math.random() - 0.5) * 0.26,
      vy: (Math.random() - 0.5) * 0.26,
      phase: Math.random() * Math.PI * 2,
      freq: 0.2 + Math.random() * 0.4,
      drift: 0.07 + Math.random() * 0.11,
      r: 1 + Math.random() * 2.1,
      color: COLORS[Math.floor(Math.random() * COLORS.length)],
      alpha: 0.2 + Math.random() * 0.32,
    }
  }

  function resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const nextWidth = canvas.clientWidth
    const nextHeight = canvas.clientHeight
    if (!nextWidth || !nextHeight) return
    const target = Math.min(110, Math.max(34, Math.round((nextWidth * nextHeight) / 24000)))

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
        p.vy *= Math.sqrt(sx * sy)
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
      p.vx += Math.sin(t * p.freq + p.phase) * p.drift * 0.032
      p.vy += Math.cos(t * p.freq * 0.92 + p.phase) * p.drift * 0.032

      const dx = p.x - mouse.x
      const dy = p.y - mouse.y
      const dist = Math.hypot(dx, dy)
      if (dist < 170 && dist > 0) {
        const force = (170 - dist) / 170
        p.vx += (dx / dist) * force * 0.012
        p.vy += (dy / dist) * force * 0.012
      }

      const speed = Math.hypot(p.vx, p.vy)
      if (speed > 1.1) {
        p.vx *= 0.985
        p.vy *= 0.985
      }

      p.x += p.vx
      p.y += p.vy

      if (p.x < -24) p.x = width + 24
      if (p.x > width + 24) p.x = -24
      if (p.y < -24) p.y = height + 24
      if (p.y > height + 24) p.y = -24
    }

    ctx.lineWidth = 1
    for (let i = 0; i < particles.length; i++) {
      const a = particles[i]
      for (let j = i + 1; j < particles.length; j++) {
        const b = particles[j]
        const d = Math.hypot(a.x - b.x, a.y - b.y)
        if (d < 116) {
          ctx.strokeStyle = `rgba(111, 158, 127, ${(1 - d / 116) * 0.09})`
          ctx.beginPath()
          ctx.moveTo(a.x, a.y)
          ctx.lineTo(b.x, b.y)
          ctx.stroke()
        }
      }
      const md = Math.hypot(a.x - mouse.x, a.y - mouse.y)
      if (md < 160) {
        ctx.strokeStyle = `rgba(111, 158, 127, ${(1 - md / 160) * 0.16})`
        ctx.beginPath()
        ctx.moveTo(a.x, a.y)
        ctx.lineTo(mouse.x, mouse.y)
        ctx.stroke()
      }

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
  <div class="dashboard-bg" aria-hidden="true">
    <div class="dashboard-bg-blob dashboard-bg-blob-1"></div>
    <div class="dashboard-bg-blob dashboard-bg-blob-2"></div>
    <div class="dashboard-bg-blob dashboard-bg-blob-3"></div>
    <canvas ref="canvasRef" class="dashboard-bg-canvas"></canvas>
  </div>
</template>

<style scoped>
.dashboard-bg {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
}

.dashboard-bg-blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(72px);
  opacity: 0.22;
  will-change: transform;
}

/* 三枚光晕全部贴边收窄，避免侵入内容区造成\"发雾\" */
.dashboard-bg-blob-1 {
  width: 380px;
  height: 380px;
  left: -190px;
  top: -120px;
  background: radial-gradient(circle at 60% 60%, var(--c-brand-tint), transparent 70%);
  animation: dashboard-blob-float 28s ease-in-out infinite;
}

.dashboard-bg-blob-2 {
  width: 400px;
  height: 400px;
  right: -230px;
  top: 12%;
  background: radial-gradient(circle at 40% 50%, #cbdccf, transparent 70%);
  animation: dashboard-blob-float 34s ease-in-out infinite reverse;
}

.dashboard-bg-blob-3 {
  width: 360px;
  height: 360px;
  left: 42%;
  bottom: -200px;
  background: radial-gradient(circle at 50% 40%, #d2e2d7, transparent 70%);
  animation: dashboard-blob-float 40s ease-in-out infinite;
}

.dashboard-bg-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

@media (prefers-reduced-motion: reduce) {
  .dashboard-bg-blob {
    animation: none;
  }
}

@keyframes dashboard-blob-float {
  0%, 100% { transform: translate(0, 0) scale(1); }
  33% { transform: translate(42px, -32px) scale(1.07); }
  66% { transform: translate(-32px, 26px) scale(0.95); }
}
</style>