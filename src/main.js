import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import { initBackendMode } from './services/dashboardApi'
import './styles.css'

async function bootstrap() {
  await initBackendMode()
  createApp(App).use(router).mount('#app')
}

bootstrap()
