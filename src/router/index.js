import { createRouter, createWebHistory } from 'vue-router'
import { canAccessPath, getAuthRole, getAuthToken, getHomePathByRole, hasPermission } from '../stores/auth'
import LoginPage from '../views/LoginPage.vue'
import DashboardLayout from '../layouts/DashboardLayout.vue'
import OverviewPage from '../views/OverviewPage.vue'
import HazardsListPage from '../views/HazardsListPage.vue'
import HazardDetailPage from '../views/HazardDetailPage.vue'
import TasksPage from '../views/TasksPage.vue'
import TaskDetailPage from '../views/TaskDetailPage.vue'
import DevicesPage from '../views/DevicesPage.vue'
import DeviceDetailPage from '../views/DeviceDetailPage.vue'
import WarningsPage from '../views/WarningsPage.vue'
import WarningDetailPage from '../views/WarningDetailPage.vue'
import EmergencyPage from '../views/EmergencyPage.vue'
import AnalyticsPage from '../views/AnalyticsPage.vue'
import SettingsPage from '../views/SettingsPage.vue'

const routePermissionMap = {
  overview: 'overview:view',
  'hazards-list': 'hazards:view',
  'hazard-detail': 'hazards:view',
  'tasks-list': 'tasks:view',
  'task-detail': 'tasks:view',
  'devices-list': 'devices:view',
  'device-detail': 'devices:view',
  'warnings-list': 'warnings:view',
  'warning-detail': 'warnings:view',
  emergency: 'emergency:view',
  analytics: 'analytics:view',
  settings: 'settings:view',
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', name: 'login', component: LoginPage, meta: { public: true } },
    {
      path: '/',
      component: DashboardLayout,
      meta: { requiresAuth: true },
      children: [
        { path: '', name: 'overview', component: OverviewPage },
        {
          path: 'hazards',
          children: [
            { path: '', name: 'hazards-list', component: HazardsListPage },
            { path: ':id', name: 'hazard-detail', component: HazardDetailPage, props: true },
          ],
        },
        {
          path: 'tasks',
          children: [
            { path: '', name: 'tasks-list', component: TasksPage },
            { path: ':id', name: 'task-detail', component: TaskDetailPage, props: true },
          ],
        },
        {
          path: 'devices',
          children: [
            { path: '', name: 'devices-list', component: DevicesPage },
            { path: ':id', name: 'device-detail', component: DeviceDetailPage, props: true },
          ],
        },
        {
          path: 'warnings',
          children: [
            { path: '', name: 'warnings-list', component: WarningsPage },
            { path: ':id', name: 'warning-detail', component: WarningDetailPage, props: true },
          ],
        },
        { path: 'emergency', name: 'emergency', component: EmergencyPage },
        { path: 'analytics', name: 'analytics', component: AnalyticsPage },
        { path: 'settings', name: 'settings', component: SettingsPage },
      ],
    },
  ],
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) return savedPosition
    if (to.hash) return { el: to.hash, top: 18 }
    return { top: 0 }
  },
})

router.beforeEach((to) => {
  if (to.meta.public) {
    const token = getAuthToken()
    if (to.path === '/login' && token) {
      return { path: getHomePathByRole(getAuthRole()) }
    }
    return true
  }

  const token = getAuthToken()
  if (!token) {
    return { path: '/login', query: { redirect: to.fullPath } }
  }

  const role = getAuthRole()
  if (!canAccessPath(role, to.path)) {
    const fallback = getHomePathByRole(role)
    if (to.path !== fallback) return { path: fallback }
  }

  const routePermission = routePermissionMap[to.name]
  if (routePermission && !hasPermission(role, routePermission)) {
    const fallback = getHomePathByRole(role)
    return { path: fallback }
  }

  return true
})

export default router
