import { createRouter, createWebHistory } from "vue-router";
import {
  canAccessPath,
  getAuthRole,
  getAuthToken,
  getHomePathByRole,
  hasPermission,
  isTokenExpired,
  redirectToLogin,
} from "../stores/auth";
import WelcomePage from "../views/WelcomePage.vue";
import DashboardLayout from "../layouts/DashboardLayout.vue";
import OverviewPage from "../views/OverviewPage.vue";
import HazardsListPage from "../views/hazard/HazardsListPage.vue";
import HazardDetailPage from "../views/hazard/HazardDetailPage.vue";
import TaskDetailPage from "../views/hazard/TaskDetailPage.vue";
import DevicesPage from "../views/device/DevicesPage.vue";
import DeviceDetailPage from "../views/device/DeviceDetailPage.vue";
import WarningsPage from "../views/warning/WarningsPage.vue";
import WarningDetailPage from "../views/warning/WarningDetailPage.vue";
import EmergencyPage from "../views/warning/EmergencyPage.vue";
import AnalyticsPage from "../views/data/AnalyticsPage.vue";
import WeatherPage from "../views/monitor/WeatherPage.vue";
import SettingsPage from "../views/settings/SettingsPage.vue";
import AiInsightPage from "../views/ai/AiInsightPage.vue";
// v1.7.0 目标架构新增 13 个界面
import RealTimeMapPage from "../views/monitor/RealTimeMapPage.vue";
import AiTaskManagePage from "../views/ai/AiTaskManagePage.vue";
import AiResultsPage from "../views/ai/AiResultsPage.vue";
import ConfirmationWorkbenchPage from "../views/hazard/ConfirmationWorkbenchPage.vue";
import WarningCreatePage from "../views/warning/WarningCreatePage.vue";
import ResourceDispatchPage from "../views/warning/ResourceDispatchPage.vue";
import DataSourcesPage from "../views/data/DataSourcesPage.vue";
import HistoryHazardsPage from "../views/monitor/HistoryHazardsPage.vue";
import UserManagementPage from "../views/system/UserManagementPage.vue";
import AuditLogPage from "../views/system/AuditLogPage.vue";
import SystemMonitorPage from "../views/system/SystemMonitorPage.vue";
import MessageCenterPage from "../views/system/MessageCenterPage.vue";
import TodoCenterPage from "../views/system/TodoCenterPage.vue";
import ApprovalCenterPage from "../views/system/ApprovalCenterPage.vue";
import WarningDeliveryPage from "../views/warning/WarningDeliveryPage.vue";
import ZoneDashboardPage from "../views/monitor/ZoneDashboardPage.vue";
import ModelManagePage from "../views/ai/ModelManagePage.vue";
import OtaManagePage from "../views/device/OtaManagePage.vue";
import NoticeTemplatePage from "../views/system/NoticeTemplatePage.vue";
import PlanManagePage from "../views/system/PlanManagePage.vue";
import ReportCenterPage from "../views/system/ReportCenterPage.vue";
import DataCatalogPage from "../views/data/DataCatalogPage.vue";
import BackupRecoveryPage from "../views/system/BackupRecoveryPage.vue";
import AccountSecurityPage from "../views/settings/AccountSecurityPage.vue";
import RuleEnginePage from "../views/ai/RuleEnginePage.vue";
import SatelliteComparePage from "../views/monitor/SatelliteComparePage.vue";
import DeviceMapPage from "../views/monitor/DeviceMapPage.vue";
import ForbiddenPage from "../views/ForbiddenPage.vue";
import ServerErrorPage from "../views/ServerErrorPage.vue";
// P0 阶段新增
import ProfilePage from "../views/settings/ProfilePage.vue";
import RoutePlanningPage from "../views/warning/RoutePlanningPage.vue";
import ConfirmationSignPage from "../views/hazard/ConfirmationSignPage.vue";
import WarningResolvePage from "../views/warning/WarningResolvePage.vue";

// 404 组件（inline，避免单独建文件）
const NotFoundPage = {
  template: `
    <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;font-family:-apple-system,sans-serif;color:#4a5568;">
      <div style="font-size:96px;font-weight:800;color:#e27d5b;">404</div>
      <div style="font-size:20px;margin-top:12px;">页面不存在</div>
      <router-link to="/dashboard" style="margin-top:24px;padding:8px 20px;background:#2b6cb0;color:#fff;border-radius:6px;text-decoration:none;">返回首页</router-link>
    </div>
  `,
};

// 路由名 → 权限编码映射（目标架构 20 个仪表盘内路由）
const routePermissionMap = {
  overview: "overview:view",
  "hazards-list": "hazards:view",
  "hazard-detail": "hazards:view",
  "devices-list": "devices:view",
  "device-detail": "devices:view",
  "warnings-list": "warnings:view",
  "warning-detail": "warnings:view",
  "warning-create": "warnings:create",
  emergency: "emergency:view",
  analytics: "analytics:view",
  weather: "weather:view",
  "ai-insight": "ai:insight:view",
  "ai-tasks": "ai:task:view",
  "ai-results": "ai:result:view",
  "real-time-map": "map:view",
  "confirmation-workbench": "confirmation:view",
  "confirmation-detail": "confirmation:view",
  "resource-dispatch": "resource:view",
  "data-sources": "datasource:view",
  "data-sync": "sync:view",
  "history-hazards": "history:view",
  "user-management": "users:view",
  "audit-log": "audit:view",
  "system-monitor": "monitor:view",
  "message-center": "messages:view",
  "todo-center": "todo:view",
  "approvals-center": "approval:view",
  "zone-dashboard": "overview:view",
  "warning-delivery": "warnings:view",
  "ai-models": "ai:model:view",
  "ota-manage": "devices:ota",
  "notice-templates": "notice:view",
  "plans-manage": "plans:view",
  "reports-center": "reports:view",
  "data-catalog": "data:catalog:view",
  "backup-recovery": "backup:view",
  "account-security": "settings:view",
  "rule-engine": "settings:view",
  settings: "settings:view",
  "satellite-compare": "map:view",
  "device-map": "devices:view",
  // P0 阶段新增
  "route-planning": "resource:dispatch",
  "warning-resolve": "warnings:close",
  "profile": "settings:view",
  "confirmation-sign": "confirmation:view",
  "confirmation-sign-event": "confirmation:view",
};

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: "/",
      name: "welcome",
      component: WelcomePage,
      meta: { public: true },
    },
    {
      path: "/welcome",
      redirect: "/",
    },
    {
      path: "/login",
      redirect: () => {
        return { path: "/", query: { login: "true" } }
      },
      meta: { public: true },
    },
    {
      path: "/dashboard",
      component: DashboardLayout,
      meta: { requiresAuth: true },
      children: [
        // ===X= 监测与态势 ===X=
        { path: "", name: "overview", component: OverviewPage },
        {
          path: "real-time-map",
          name: "real-time-map",
          component: RealTimeMapPage,
        },
        // ===X= AI 与事件 ===X=
        {
          path: "ai-tasks",
          children: [
            { path: "", name: "ai-tasks", component: AiTaskManagePage },
            {
              path: ":id",
              name: "task-detail",
              component: TaskDetailPage,
              props: true,
            },
          ],
        },
        {
          path: "ai-results",
          name: "ai-results",
          component: AiResultsPage,
        },
        {
          path: "ai-insight",
          name: "ai-insight",
          component: AiInsightPage,
        },
        {
          path: "hazards",
          children: [
            { path: "", name: "hazards-list", component: HazardsListPage },
            {
              path: ":id",
              name: "hazard-detail",
              component: HazardDetailPage,
              props: true,
            },
          ],
        },
        {
          path: "confirmation-workbench",
          children: [
            {
              path: "",
              name: "confirmation-workbench",
              component: ConfirmationWorkbenchPage,
            },
            {
              path: ":id",
              name: "confirmation-detail",
              component: HazardDetailPage,
              props: true,
            },
          ],
        },
        // ===X= 预警与处置 ===X=
        {
          path: "warnings",
          children: [
            { path: "", name: "warnings-list", component: WarningsPage },
            {
              path: "create",
              name: "warning-create",
              component: WarningCreatePage,
            },
            {
              path: ":id",
              name: "warning-detail",
              component: WarningDetailPage,
              props: true,
            },
          ],
        },
        { path: "emergency", name: "emergency", component: EmergencyPage },
        {
          path: "resource-dispatch",
          name: "resource-dispatch",
          component: ResourceDispatchPage,
        },
        // ===X= 数据与模型 ===X=
        {
          path: "devices",
          children: [
            { path: "", name: "devices-list", component: DevicesPage },
            {
              path: ":id",
              name: "device-detail",
              component: DeviceDetailPage,
              props: true,
            },
          ],
        },
        {
          path: "data-sources",
          name: "data-sources",
          component: DataSourcesPage,
        },
        { path: "weather", name: "weather", component: WeatherPage },
        // ===X= 系统管理 ===X=
        { path: "analytics", name: "analytics", component: AnalyticsPage },
        {
          path: "history-hazards",
          name: "history-hazards",
          component: HistoryHazardsPage,
        },
        {
          path: "user-management",
          name: "user-management",
          component: UserManagementPage,
        },
        { path: "audit-log", name: "audit-log", component: AuditLogPage },
        {
          path: "system-monitor",
          name: "system-monitor",
          component: SystemMonitorPage,
        },
        {
          path: "message-center",
          name: "message-center",
          component: MessageCenterPage,
        },
        {
          path: "todo",
          name: "todo-center",
          component: TodoCenterPage,
        },
        {
          path: "approvals",
          name: "approvals-center",
          component: ApprovalCenterPage,
        },
        {
          path: "ai-models",
          name: "ai-models",
          component: ModelManagePage,
        },
        {
          path: "devices/ota",
          name: "ota-manage",
          component: OtaManagePage,
        },
        {
          path: "notice-templates",
          name: "notice-templates",
          component: NoticeTemplatePage,
        },
        {
          path: "plans",
          name: "plans-manage",
          component: PlanManagePage,
        },
        {
          path: "reports",
          name: "reports-center",
          component: ReportCenterPage,
        },
        {
          path: "data-catalog",
          name: "data-catalog",
          component: DataCatalogPage,
        },
        {
          path: "backup-recovery",
          name: "backup-recovery",
          component: BackupRecoveryPage,
        },
        {
          path: "account-security",
          name: "account-security",
          component: AccountSecurityPage,
        },
        {
          path: "rule-engine",
          name: "rule-engine",
          component: RuleEnginePage,
        },
        {
          path: "zone/:kind?/:region?",
          name: "zone-dashboard",
          component: ZoneDashboardPage,
        },
        {
          path: "warnings/:id/delivery",
          name: "warning-delivery",
          component: WarningDeliveryPage,
          props: true,
        },
        { path: "settings", name: "settings", component: SettingsPage },
        {
          path: "satellite-compare",
          name: "satellite-compare",
          component: SatelliteComparePage,
        },
        {
          path: "device-map",
          name: "device-map",
          component: DeviceMapPage,
        },
        // ===X= P0 阶段新增路由 ===X=
        {
          path: "route-planning",
          name: "route-planning",
          component: RoutePlanningPage,
        },
        {
          path: "warnings/:id/resolve",
          name: "warning-resolve",
          component: WarningResolvePage,
          props: true,
        },
        {
          path: "profile",
          name: "profile",
          component: ProfilePage,
        },
        {
          path: "confirmation-sign",
          name: "confirmation-sign",
          component: ConfirmationSignPage,
        },
        {
          path: "confirmation-sign/:id",
          name: "confirmation-sign-event",
          component: ConfirmationSignPage,
          props: true,
        },
        // ===X= 错误页 ===X=
        {
          path: "error/403",
          name: "forbidden",
          component: ForbiddenPage,
          meta: { public: true },
        },
        {
          path: "error/500",
          name: "server-error",
          component: ServerErrorPage,
          meta: { public: true },
        },
      ],
    },
    {
      path: "/:pathMatch(.*)*",
      name: "not-found",
      component: NotFoundPage,
      meta: { public: true },
    },
  ],
  scrollBehavior(to, from, savedPosition) {
    if (savedPosition) {return savedPosition;}
    if (to.hash) {return { el: to.hash, top: 18 };}
    return { top: 0 };
  },
});

router.beforeEach((to) => {
  if (to.meta.public) {
    const token = getAuthToken();
    if (to.path === "/") {
      if (token && !isTokenExpired() && to.query.login === "true") {
        return true;
      }
      if (token && !isTokenExpired()) {
        return { path: getHomePathByRole(getAuthRole()) };
      }
      return true;
    }
    if (to.path === "/login" && token && !isTokenExpired()) {
      return { path: getHomePathByRole(getAuthRole()) };
    }
    return true;
  }

  const token = getAuthToken();
  if (!token || isTokenExpired()) {
    if (token && isTokenExpired()) {redirectToLogin();}
    return { path: "/", query: { login: "true", redirect: to.fullPath } };
  }

  const role = getAuthRole();
  if (!canAccessPath(role, to.path)) {
    // 角色完全无此模块路径权限：跳 403 而非静默回首页，便于用户感知被限制
    if (to.path.startsWith("/dashboard/") && to.path !== getHomePathByRole(role)) {
      return { path: "/dashboard/error/403", query: { from: to.fullPath, reason: "no-path" } };
    }
  }

  const routePermission = routePermissionMap[to.name];
  if (routePermission && !hasPermission(role, routePermission)) {
    // 角色有路径访问权但无操作权限：跳 403，告知具体原因
    return {
      path: "/dashboard/error/403",
      query: { from: to.fullPath, reason: "no-permission", required: routePermission, role },
    };
  }

  return true;
});

export default router;
