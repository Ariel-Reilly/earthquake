import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { initBackendMode } from './services/dashboardApi'
import './styles.css'

/**
 * 抑制浏览器控制台告警：
 *   Canvas2D: Multiple readback operations using getImageData are faster
 *   with the willReadFrequently attribute set to true.
 *
 * 触发源：OpenLayers 的 ol_layer_Vector.js 内部 benchmarkCanvasReads +
 * forEachFeatureAtPixel 命中检测，每次 pointermove 都会对 OL 内部的
 * hit-detect canvas 调 getImageData。OL 创建 canvas 时为了绘制性能默认传
 * willReadFrequently: false，但同一 context 后续被用于命中检测（读回像素），
 * 浏览器按规范要求给出性能提示（不影响功能）。
 *
 * 修复策略：给 HTMLCanvasElement.prototype.getContext 打补丁，无条件
 * 给 2d 上下文开启 willReadFrequently: true。本项目所有 canvas 都是 OL
 * 管理（hit-detect / 渲染层），主要使用场景就是读回，统一开启：
 *   - 消除浏览器告警
 *   - 让 OL 的 benchmark 路径走快速通道
 * 副作用：纯渲染用途的 canvas 会多占一点内存（浏览器为 readback 做了
 * 软件缓存），但本项目不存在这种纯绘制 canvas，权衡之下利大于弊。
 */
{
  const proto = HTMLCanvasElement.prototype
  const originalGetContext = proto.getContext
  if (!proto.__willReadFrequentlyPatched) {
    proto.getContext = function patchedGetContext(type, options) {
      if (type === '2d') {
        options = { ...(options || {}), willReadFrequently: true }
      }
      return originalGetContext.call(this, type, options)
    }
    proto.__willReadFrequentlyPatched = true
  }
}

async function bootstrap() {
  await initBackendMode()
  const pinia = createPinia()
  createApp(App).use(pinia).use(router).mount('#app')
}

bootstrap()
