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
import DashboardLayout from "../layouts/MainLayout.vue"; // 使用新版 MainLayout

// 懒加载所有页面组件（首屏只加载 Layout 和 WelcomePage）
// 使用直接返回 import() 即可,Vue Router 4 自动按路由级代码分割,
// 无需再用 defineAsyncComponent() 包一层(后者会触发警告并丢失部分 router 特性)
const OverviewPage = () =>
  import(/* webpackChunkName: "overview" */ "../views/OverviewPage.vue");
const HazardsListPage = () =>
  import(/* webpackChunkName: "hazard" */ "../views/hazard/HazardsListPage.vue");
const HazardDetailPage = () =>
  import(/* webpackChunkName: "hazard" */ "../views/hazard/HazardDetailPage.vue");
const TaskDetailPage = () =>
  import(/* webpackChunkName: "hazard" */ "../views/hazard/TaskDetailPage.vue");
const DevicesPage = () =>
  import(/* webpackChunkName: "device" */ "../views/device/DevicesPage.vue");
const DeviceDetailPage = () =>
  import(/* webpackChunkName: "device" */ "../views/device/DeviceDetailPage.vue");
const WarningsPage = () =>
  import(/* webpackChunkName: "warning" */ "../views/warning/WarningsPage.vue");
const WarningDetailPage = () =>
  import(/* webpackChunkName: "warning" */ "../views/warning/WarningDetailPage.vue");
const EmergencyPage = () =>
  import(/* webpackChunkName: "warning" */ "../views/warning/EmergencyPage.vue");
const AnalyticsPage = () =>
  import(/* webpackChunkName: "data" */ "../views/data/AnalyticsPage.vue");
const WeatherPage = () =>
  import(/* webpackChunkName: "monitor" */ "../views/monitor/WeatherPage.vue");
const SettingsPage = () =>
  import(/* webpackChunkName: "settings" */ "../views/settings/SettingsPage.vue");
const AiInsightPage = () =>
  import(/* webpackChunkName: "ai" */ "../views/ai/AiInsightPage.vue");
const RealTimeMapPage = () =>
  import(/* webpackChunkName: "monitor" */ "../views/monitor/RealTimeMapPage.vue");
const AiTaskManagePage = () =>
  import(/* webpackChunkName: "ai" */ "../views/ai/AiTaskManagePage.vue");
const AiResultsPage = () =>
  import(/* webpackChunkName: "ai" */ "../views/ai/AiResultsPage.vue");
const ConfirmationWorkbenchPage = () =>
  import(/* webpackChunkName: "hazard" */ "../views/hazard/ConfirmationWorkbenchPage.vue");
const WarningCreatePage = () =>
  import(/* webpackChunkName: "warning" */ "../views/warning/WarningCreatePage.vue");
const ResourceDispatchPage = () =>
  import(/* webpackChunkName: "warning" */ "../views/warning/ResourceDispatchPage.vue");
const DataSourcesPage = () =>
  import(/* webpackChunkName: "data" */ "../views/data/DataSourcesPage.vue");
const DataSyncPage = () =>
  import(/* webpackChunkName: "data" */ "../views/data/DataSyncPage.vue");
const HistoryHazardsPage = () =>
  import(/* webpackChunkName: "monitor" */ "../views/monitor/HistoryHazardsPage.vue");
const UserManagementPage = () =>
  import(/* webpackChunkName: "system" */ "../views/system/UserManagementPage.vue");
const AuditLogPage = () =>
  import(/* webpackChunkName: "system" */ "../views/system/AuditLogPage.vue");
const SystemMonitorPage = () =>
  import(/* webpackChunkName: "system" */ "../views/system/SystemMonitorPage.vue");
const MessageCenterPage = () =>
  import(/* webpackChunkName: "system" */ "../views/system/MessageCenterPage.vue");
const TodoCenterPage = () =>
  import(/* webpackChunkName: "system" */ "../views/system/TodoCenterPage.vue");
const ApprovalCenterPage = () =>
  import(/* webpackChunkName: "system" */ "../views/system/ApprovalCenterPage.vue");
const EventCenterPage = () =>
  import(/* webpackChunkName: "system" */ "../views/system/EventCenterPage.vue");
const WarningDeliveryPage = () =>
  import(/* webpackChunkName: "warning" */ "../views/warning/WarningDeliveryPage.vue");
const ZoneDashboardPage = () =>
  import(/* webpackChunkName: "monitor" */ "../views/monitor/ZoneDashboardPage.vue");
const ModelManagePage = () =>
  import(/* webpackChunkName: "ai" */ "../views/ai/ModelManagePage.vue");
const OtaManagePage = () =>
  import(/* webpackChunkName: "device" */ "../views/device/OtaManagePage.vue");
const NoticeTemplatePage = () =>
  import(/* webpackChunkName: "system" */ "../views/system/NoticeTemplatePage.vue");
const PlanManagePage = () =>
  import(/* webpackChunkName: "system" */ "../views/system/PlanManagePage.vue");
const ReportCenterPage = () =>
  import(/* webpackChunkName: "system" */ "../views/system/ReportCenterPage.vue");
const DataCatalogPage = () =>
  import(/* webpackChunkName: "data" */ "../views/data/DataCatalogPage.vue");
const BackupRecoveryPage = () =>
  import(/* webpackChunkName: "system" */ "../views/system/BackupRecoveryPage.vue");
const AccountSecurityPage = () =>
  import(/* webpackChunkName: "settings" */ "../views/settings/AccountSecurityPage.vue");
const RuleEnginePage = () =>
  import(/* webpackChunkName: "ai" */ "../views/ai/RuleEnginePage.vue");
const SatelliteComparePage = () =>
  import(/* webpackChunkName: "monitor" */ "../views/monitor/SatelliteComparePage.vue");
const DeviceMapPage = () =>
  import(/* webpackChunkName: "monitor" */ "../views/monitor/DeviceMapPage.vue");
const ForbiddenPage = () =>
  import(/* webpackChunkName: "error" */ "../views/ForbiddenPage.vue");
const ServerErrorPage = () =>
  import(/* webpackChunkName: "error" */ "../views/ServerErrorPage.vue");
const ProfilePage = () =>
  import(/* webpackChunkName: "settings" */ "../views/settings/ProfilePage.vue");
const RoutePlanningPage = () =>
  import(/* webpackChunkName: "warning" */ "../views/warning/RoutePlanningPage.vue");
const ConfirmationSignPage = () =>
  import(/* webpackChunkName: "hazard" */ "../views/hazard/ConfirmationSignPage.vue");
const WarningResolvePage = () =>
  import(/* webpackChunkName: "warning" */ "../views/warning/WarningResolvePage.vue");
const DisasterForecastPage = () =>
  import(/* webpackChunkName: "ai" */ "../views/ai/DisasterForecastPage.vue");
const DamageAssessmentPage = () =>
  import(/* webpackChunkName: "ai" */ "../views/ai/DamageAssessmentPage.vue");
const FieldInspectionPage = () =>
  import(/* webpackChunkName: "hazard" */ "../views/hazard/FieldInspectionPage.vue");
const PersonnelManagePage = () =>
  import(/* webpackChunkName: "system" */ "../views/system/PersonnelManagePage.vue");
const MobileCommandPage = () =>
  import(/* webpackChunkName: "mobile" */ "../views/mobile/MobileCommandPage.vue");
const SocialMediaPage = () =>
  import(/* webpackChunkName: "ai" */ "../views/ai/SocialMediaPage.vue");
const HelpCenterPage = () =>
  import(/* webpackChunkName: "system" */ "../views/system/HelpCenterPage.vue");
const NotificationChannelsPage = () =>
  import(/* webpackChunkName: "system" */ "../views/system/NotificationChannelsPage.vue");
const CommandScreenPage = () =>
  import(/* webpackChunkName: "screen" */ "../views/screen/CommandScreenPage.vue");
const WorkbenchPage = () =>
  import(/* webpackChunkName: "system" */ "../views/system/WorkbenchPage.vue");
const DataManagePage = () =>
  import(/* webpackChunkName: "data" */ "../views/data/DataManagePage.vue");
const WarningConfigPage = () =>
  import(/* webpackChunkName: "warning" */ "../views/warning/WarningConfigPage.vue");
// 注册 / 审核：匿名注册页不进首屏 chunk，审核页归入 system 分包
const RegisterPage = () =>
  import(/* webpackChunkName: "register" */ "../views/RegisterPage.vue");
const AdminApplicationsPage = () =>
  import(/* webpackChunkName: "system" */ "../views/AdminApplicationsPage.vue");

// 404 组件（inline，避免单独建文件）
const NotFoundPage = {
  template: `
    <div style="display:flex;flex-direction:column;align-items:center;justify-content:center;height:100vh;font-family:-apple-system,sans-serif;color:#4a5568;">
      <div style="font-size:96px;font-weight:800;color:#cd927e;">404</div>
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
  "approval-center": "approval:view",
  "event-center": "events:view",
  "event-detail": "events:view",
  "zone-dashboard": "zone:view",
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
  // P1 新增
  "disaster-forecast": "ai:forecast:view",
  "damage-assessment": "ai:forecast:view",
  "field-inspection": "inspection:view",
  "personnel-manage": "personnel:view",
  "mobile-command": "overview:view",
  "social-media": "ai:forecast:view",
  "help-center": "settings:view",
  "notification-channels": "notice:view",
  // 注册申请审核。后端判据是 hasRole('ADMIN')，前端用独立的 users:approve 权限码：
  // 该码只加在 ROLE_PERMISSION_MAP.admin，因此非 admin 走到这里会被拦到 403。
  "admin-applications": "users:approve",
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
      path: "/screen",
      name: "command-screen",
      component: CommandScreenPage,
      meta: { public: true },
    },
    {
      path: "/mobile",
      name: "mobile-command",
      component: MobileCommandPage,
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
      // 注册申请页：匿名可访问（后端 SecurityConfig 对 /api/auth/register* 是 permitAll）。
      // meta 不设 requiresAuth，登录与未登录都能进来。
      path: "/register",
      name: "register",
      component: RegisterPage,
      meta: { public: true },
    },
    {
      path: "/dashboard",
      component: DashboardLayout,
      meta: { requiresAuth: true },
      children: [
        // 监测与态势
        { path: "", name: "overview", component: OverviewPage },
        {
          path: "real-time-map",
          name: "real-time-map",
          component: RealTimeMapPage,
        },
        // AI 与事件
        {
          path: "ai-tasks",
          children: [
            { path: "", name: "ai-tasks", component: AiTaskManagePage },
            {
              path: ":id",
              name: "task-detail",
              component: TaskDetailPage,
              props: true,
              meta: { showAiAssistant: true }, // AI 助手在任务详情页显示
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
              meta: { showAiAssistant: true }, // AI 助手在灾害点详情页显示
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
              meta: { showAiAssistant: true }, // AI 助手在确认详情页显示
            },
          ],
        },
        // 预警与处置
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
              meta: { showAiAssistant: true }, // AI 助手在预警详情页显示
            },
          ],
        },
        {
          path: "warning-config",
          name: "warning-config",
          component: WarningConfigPage,
        },
        { path: "emergency", name: "emergency", component: EmergencyPage },
        {
          path: "resource-dispatch",
          name: "resource-dispatch",
          component: ResourceDispatchPage,
        },
        // 数据与模型
        {
          path: "devices",
          children: [
            { path: "", name: "devices-list", component: DevicesPage },
            {
              path: ":id",
              name: "device-detail",
              component: DeviceDetailPage,
              props: true,
              meta: { showAiAssistant: true }, // AI 助手在设备详情页显示
            },
          ],
        },
        {
          path: "data-manage",
          name: "data-manage",
          component: DataManagePage,
        },
        {
          path: "data-sources",
          name: "data-sources",
          component: DataSourcesPage,
        },
        {
          path: "data-sync",
          name: "data-sync",
          component: DataSyncPage,
        },
        { path: "weather", name: "weather", component: WeatherPage },
        // 系统管理
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
          path: "workbench",
          name: "workbench",
          component: WorkbenchPage,
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
          name: "approval-center",
          component: ApprovalCenterPage,
        },
        {
          path: "events",
          children: [
            { path: "", name: "event-center", component: EventCenterPage },
            {
              path: ":code",
              name: "event-detail",
              component: EventCenterPage,
              props: true,
            },
          ],
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
        // P0 阶段新增路由
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
        // P1 AI 预测与评估
        {
          path: "disaster-forecast",
          name: "disaster-forecast",
          component: DisasterForecastPage,
        },
        {
          path: "damage-assessment",
          name: "damage-assessment",
          component: DamageAssessmentPage,
        },
        // P1 现场巡检 + 人员管理
        {
          path: "field-inspection",
          name: "field-inspection",
          component: FieldInspectionPage,
        },
        {
          path: "personnel",
          name: "personnel-manage",
          component: PersonnelManagePage,
        },
        {
          path: "social-media",
          name: "social-media",
          component: SocialMediaPage,
        },
        {
          path: "help",
          name: "help-center",
          component: HelpCenterPage,
        },
        {
          path: "notification-channels",
          name: "notification-channels",
          component: NotificationChannelsPage,
        },
        // 错误页
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
      // 注册申请审核挂在 /admin 下而不是 /dashboard 下，但仍复用同一套 MainLayout：
      // 它只对 admin 开放，放进 /dashboard 会让 canAccessPath 的前缀匹配
      // 把 system_admin 等角色的 /dashboard 权限误判为可达。
      // 真正的访问控制来自 routePermissionMap["admin-applications"] = users:approve，
      // 该权限码只存在于 ROLE_PERMISSION_MAP.admin，其余角色会被守卫拦到 403。
      path: "/admin",
      component: DashboardLayout,
      meta: { requiresAuth: true },
      children: [
        {
          path: "applications",
          name: "admin-applications",
          component: AdminApplicationsPage,
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
    console.warn("路由被拦截(未登录或 token 过期):", to.path);
    if (token && isTokenExpired()) {redirectToLogin();}
    return { path: "/", query: { login: "true", redirect: to.fullPath } };
  }

  const role = getAuthRole();
  if (!canAccessPath(role, to.path)) {
    // 角色完全无此模块路径权限：跳 403 而非静默回首页，便于用户感知被限制
    if (to.path.startsWith("/dashboard/") && to.path !== getHomePathByRole(role)) {
      console.warn("路由被拦截(角色无路径权限):", to.path, "role =", role);
      return { path: "/dashboard/error/403", query: { from: to.fullPath, reason: "no-path" } };
    }
  }

  const routePermission = routePermissionMap[to.name];
  if (routePermission && !hasPermission(role, routePermission)) {
    // 角色有路径访问权但无操作权限：跳 403，告知具体原因
    console.warn("路由被拦截(缺少权限码):", to.path, "required =", routePermission, "role =", role);
    return {
      path: "/dashboard/error/403",
      query: { from: to.fullPath, reason: "no-permission", required: routePermission, role },
    };
  }

  return true;
});

export default router;
