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

  const COLORS = ['rgba(111, 158, 127, ', 'rgba(93, 180, 134, ', 'rgba(94, 143, 193, ']

  function spawn() {
    return {
      x: Math.random() * width,
      y: Math.random() * height,
      vx: (Math.random() - 0.5) * 0.28,
      vy: (Math.random() - 0.5) * 0.28,
      phase: Math.random() * Math.PI * 2,
      freq: 0.2 + Math.random() * 0.4,
      drift: 0.07 + Math.random() * 0.11,
      r: 1 + Math.random() * 2.1,
      color: COLORS[Math.floor(Math.random() * COLORS.length)],
      alpha: 0.3 + Math.random() * 0.45,
    }
  }

  function resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const nextWidth = canvas.clientWidth
    const nextHeight = canvas.clientHeight
    if (!nextWidth || !nextHeight) return
    const target = Math.min(90, Math.max(42, Math.round((nextWidth * nextHeight) / 17000)))

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
        p.vx += (dx / dist) * force * 0.018
        p.vy += (dy / dist) * force * 0.018
      }

      const speed = Math.hypot(p.vx, p.vy)
      if (speed > 1.15) {
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
        if (d < 118) {
          ctx.strokeStyle = `rgba(87, 140, 138, ${(1 - d / 118) * 0.13})`
          ctx.beginPath()
          ctx.moveTo(a.x, a.y)
          ctx.lineTo(b.x, b.y)
          ctx.stroke()
        }
      }
      const md = Math.hypot(a.x - mouse.x, a.y - mouse.y)
      if (md < 170) {
        ctx.strokeStyle = `rgba(111, 158, 127, ${(1 - md / 170) * 0.28})`
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

  resize()
  draw(!prefersReduced)

  window.addEventListener('resize', resize)
  window.addEventListener('mousemove', onMouseMove)
  window.addEventListener('mouseout', onMouseLeave)

  let resizeFrame = null
  function onResize() {
    if (resizeFrame) return
    resizeFrame = requestAnimationFrame(() => {
      resizeFrame = null
      resize()
      draw(false)
    })
  }

  window.addEventListener('resize', onResize)

  frame = () => {
    if (resizeFrame) cancelAnimationFrame(resizeFrame)
    resizeFrame = null
    window.removeEventListener('resize', onResize)
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
  <div class="login-animated-bg" aria-hidden="true">
    <div class="login-bg-blob login-bg-blob-1"></div>
    <div class="login-bg-blob login-bg-blob-2"></div>
    <div class="login-bg-blob login-bg-blob-3"></div>
    <div class="login-bg-grid"></div>
    <canvas ref="canvasRef" class="login-bg-canvas"></canvas>
  </div>
</template>

<style scoped>
.login-animated-bg {
  position: absolute;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
}

.login-bg-blob {
  position: absolute;
  border-radius: 50%;
  filter: blur(72px);
  opacity: 0.32;
  will-change: transform;
}

.login-bg-blob-1 {
  width: 500px;
  height: 500px;
  left: -140px;
  top: -140px;
  background: radial-gradient(circle at 32% 32%, var(--c-brand-tint), var(--c-brand));
  animation: blob-float 26s ease-in-out infinite;
}

.login-bg-blob-2 {
  width: 540px;
  height: 540px;
  right: -180px;
  top: 6%;
  background: radial-gradient(circle at 60% 40%, var(--c-brand-tint), var(--c-brand-hover));
  animation: blob-float 33s ease-in-out infinite reverse;
}

.login-bg-blob-3 {
  width: 440px;
  height: 440px;
  left: 28%;
  bottom: -190px;
  background: radial-gradient(circle at 50% 50%, #c3d9ca, var(--c-brand-tint));
  animation: blob-float 39s ease-in-out infinite;
}

.login-bg-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(111, 158, 127, 0.055) 1px, transparent 1px),
    linear-gradient(90deg, rgba(111, 158, 127, 0.055) 1px, transparent 1px);
  background-size: 46px 46px;
  -webkit-mask-image: radial-gradient(circle at 50% 42%, var(--c-overlay-ink) 28%, transparent 76%);
  mask-image: radial-gradient(circle at 50% 42%, var(--c-overlay-ink) 28%, transparent 76%);
}

.login-bg-canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

@media (prefers-reduced-motion: reduce) {
  .login-bg-blob {
    animation: none;
  }
}

@keyframes blob-float {
  0%, 100% { transform: translate(0, 0) scale(1); }
  33% { transform: translate(42px, -32px) scale(1.08); }
  66% { transform: translate(-30px, 26px) scale(0.94); }
}
</style>
