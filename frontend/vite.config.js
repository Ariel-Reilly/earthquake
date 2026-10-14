import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import cesium from 'vite-plugin-cesium'

export default defineConfig({
  plugins: [vue(), cesium()],
  server: {
    // 显式监听 IPv4 双栈：默认只绑 [::1] 会导致 127.0.0.1 无法访问，
    // 内置预览面板与部分抓包工具走 IPv4，会表现为“打不开”。
    host: '127.0.0.1',
    port: 5173,
    strictPort: true,
    // ── DEV PROXY ──
    // 把 /api 转发到后端，使浏览器侧请求与页面同源：
    //   · 预览面板走 http://127.0.0.1:5173
    //   · 后端 CorsConfig 白名单只含 http://localhost:5173
    //
    // ⚠ 关键：changeOrigin 只改写 Host，不改写 Origin。
    //   vite 代理默认会把浏览器的 Origin（http://127.0.0.1:5173）原样透传给后端，
    //   后端 CORS 校验发现该源不在白名单 → 直接返回 403 Forbidden。
    //   实测：同一请求 带 Origin → 403，不带 Origin → 正常。
    //   因此必须用 configure 钩子把 Origin 改写成白名单内的地址。
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
        ws: true,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            // 改写为后端白名单内的源，避免 CORS 校验拒绝
            proxyReq.setHeader('Origin', 'http://localhost:5173')
            proxyReq.setHeader('Referer', 'http://localhost:5173/')
          })
        },
      },
    },
  },
  build: {
    chunkSizeWarningLimit: 1024,
    rollupOptions: {
      output: {
        manualChunks: {
          // 体积较大的第三方库拆出，便于浏览器并行下载与缓存命中
          vue: ['vue', 'vue-router'],
          map: ['ol', 'ol/format/GeoJSON'],
          icons: ['lucide-vue-next'],
        },
      },
    },
  },
})
