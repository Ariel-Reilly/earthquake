import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { initBackendMode } from './services/dashboardApi'
import './styles.css'

async function bootstrap() {
  await initBackendMode()
  const pinia = createPinia()
  createApp(App).use(pinia).use(router).mount('#app')
}

bootstrap()
