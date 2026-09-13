import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import cesium from 'vite-plugin-cesium'

export default defineConfig({
  plugins: [vue(), cesium()],
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
