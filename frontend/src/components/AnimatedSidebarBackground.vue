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

  const COLORS = ['rgba(94, 168, 158, ', 'rgba(99, 209, 156, ', 'rgba(105, 156, 206, ', 'rgba(244, 200, 123, ']

  function spawn() {
    return {
      x: Math.random() * width,
      y: Math.random() * height,
      vx: (Math.random() - 0.5) * 0.22,
      vy: (Math.random() - 0.5) * 0.22,
      phase: Math.random() * Math.PI * 2,
      freq: 0.22 + Math.random() * 0.38,
      drift: 0.06 + Math.random() * 0.1,
      r: 0.8 + Math.random() * 1.6,
      color: COLORS[Math.floor(Math.random() * COLORS.length)],
      alpha: 0.18 + Math.random() * 0.3,
    }
  }

  function resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const nextWidth = canvas.clientWidth
    const nextHeight = canvas.clientHeight
    if (!nextWidth || !nextHeight) return
    const target = Math.min(56, Math.max(18, Math.round((nextWidth * nextHeight) / 7000)))

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
      p.vx += Math.sin(t * p.freq + p.phase) * p.drift * 0.035
      p.vy += Math.cos(t * p.freq * 0.9 + p.phase) * p.drift * 0.035

      const dx = p.x - mouse.x
      const dy = p.y - mouse.y
      const dist = Math.hypot(dx, dy)
      if (dist < 140 && dist > 0) {
        const force = (140 - dist) / 140
        p.vx += (dx / dist) * force * 0.016
        p.vy += (dy / dist) * force * 0.016
      }

      const speed = Math.hypot(p.vx, p.vy)
      if (speed > 0.9) {
        p.vx *= 0.99
        p.vy *= 0.99
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
        if (d < 100) {
          ctx.strokeStyle = `rgba(94, 168, 158, ${(1 - d / 100) * 0.08})`
          ctx.beginPath()
          ctx.moveTo(a.x, a.y)
          ctx.lineTo(b.x, b.y)
          ctx.stroke()
        }
      }
      const md = Math.hypot(a.x - mouse.x, a.y - mouse.y)
      if (md < 150) {
        ctx.strokeStyle = `rgba(99, 209, 156, ${(1 - md / 150) * 0.16})`
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
  <div class="sidebar-bg" aria-hidden="true">
    <div class="sidebar-bg-blob sidebar-bg-blob-1"></div>
    <div class="sidebar-bg-blob sidebar-bg-blob-2"></div>
    <div class="sidebar-bg-blob sidebar-bg-blob-3"></div>
    <div class="sidebar-bg-vignette"></div>
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

.sidebar-bg-blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(64px);
  opacity: 0.32;
  will-change: transform;
}

.sidebar-bg-blob-1 {
  width: 380px;
  height: 380px;
  left: -170px;
  top: -120px;
  background: radial-gradient(circle at 34% 34%, #2f8f7a, #12383e 68%);
  animation: sidebar-blob-float 24s ease-in-out infinite;
}

.sidebar-bg-blob-2 {
  width: 420px;
  height: 420px;
  right: -210px;
  top: 32%;
  background: radial-gradient(circle at 58% 42%, #3a7d8f, #12383e 70%);
  animation: sidebar-blob-float 30s ease-in-out infinite reverse;
}

.sidebar-bg-blob-3 {
  width: 360px;
  height: 360px;
  left: 22%;
  bottom: -190px;
  background: radial-gradient(circle at 50% 50%, #9a6f3e, #12383e 72%);
  animation: sidebar-blob-float 36s ease-in-out infinite;
}

.sidebar-bg-vignette {
  position: absolute;
  inset: 0;
  background: radial-gradient(120% 86% at 36% 12%, transparent 38%, rgba(7, 27, 29, 0.5) 100%);
  mix-blend-mode: multiply;
}

.sidebar-bg-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

@media (prefers-reduced-motion: reduce) {
  .sidebar-bg-blob {
    animation: none;
  }
}

@keyframes sidebar-blob-float {
  0%, 100% { transform: translate(0, 0) scale(1); }
  33% { transform: translate(34px, -28px) scale(1.08); }
  66% { transform: translate(-26px, 22px) scale(0.94); }
}
</style>