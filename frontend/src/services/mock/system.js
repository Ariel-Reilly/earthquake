/**
 * 系统管理类离线 mock：
 * - 用户 / 角色
 * - 日志审计
 * - 系统监控
 * - AI 模型管理（离线骨架）
 * - 账号与安全（API 列表：getAccountSecurity / listLoginHistory / listActiveSessions / listAccountAudit）
 */

// =====================================================================
// 用户管理：5 个示例 + 角色字典
// =====================================================================

export function getUsersMock() {
  return {
    records: [
      {
        id: 1,
        username: "lichenxi",
        name: "李晨曦",
        role: "admin",
        org: "应急管理中心",
        enabled: true,
        lastLogin: "12分钟前",
      },
      {
        id: 2,
        username: "wangjianguo",
        name: "王建国",
        role: "attendant",
        org: "龙安镇值班组",
        enabled: true,
        lastLogin: "2小时前",
      },
      {
        id: 3,
        username: "chenxiaofeng",
        name: "陈晓峰",
        role: "expert",
        org: "四川地质专家库",
        enabled: true,
        lastLogin: "昨天",
      },
      {
        id: 4,
        username: "lixxx",
        name: "李敏",
        role: "maintenance",
        org: "设备运维组",
        enabled: true,
        lastLogin: "3天前",
      },
      {
        id: 5,
        username: "zhouxiaoyu",
        name: "周小雨",
        role: "attendant",
        org: "龙安镇值班组",
        enabled: false,
        lastLogin: "1周前",
      },
    ],
    total: 5,
    page: 1,
    size: 5,
    pages: 1,
  };
}

export function getRolesMock() {
  return [
    {
      code: "admin",
      label: "系统管理员",
      description: "拥有所有模块的查看与编辑权限",
      userCount: 1,
    },
    {
      code: "attendant",
      label: "值班员",
      description: "态势、巡检、预警处置、AI 确认",
      userCount: 2,
    },
    {
      code: "expert",
      label: "领域专家",
      description: "灾害评估、会签复核、AI 确认",
      userCount: 1,
    },
    {
      code: "maintenance",
      label: "运维员",
      description: "设备管理、数据源、系统监控",
      userCount: 1,
    },
  ];
}

// =====================================================================
// 日志审计：5 条示例
// =====================================================================

export function getAuditLogsMock() {
  return {
    records: [
      {
        id: 1,
        time: new Date(Date.now() - 60 * 1000).toISOString(),
        operator: "李晨曦",
        action: "WARNING_CONFIRM",
        target: "WG-A12F4C",
        detail: "确认青龙坡橙色预警",
        ip: "10.0.0.21",
      },
      {
        id: 2,
        time: new Date(Date.now() - 5 * 60 * 1000).toISOString(),
        operator: "王建国",
        action: "HAZARD_CREATE",
        target: "HZ-022",
        detail: "新增白杨坡崩塌点",
        ip: "10.0.0.34",
      },
      {
        id: 3,
        time: new Date(Date.now() - 12 * 60 * 1000).toISOString(),
        operator: "陈晓峰",
        action: "CONFIRMATION_CONFIRM",
        target: "HZ-019",
        detail: "AI 初筛·人工确认为泥石流",
        ip: "10.0.0.42",
      },
      {
        id: 4,
        time: new Date(Date.now() - 33 * 60 * 1000).toISOString(),
        operator: "lixxx",
        action: "DEVICE_EDIT",
        target: "RAIN-052",
        detail: "修改采集间隔 5min→10min",
        ip: "10.0.0.18",
      },
      {
        id: 5,
        time: new Date(Date.now() - 50 * 60 * 1000).toISOString(),
        operator: "李晨曦",
        action: "USER_CREATE",
        target: "zhouxiaoyu",
        detail: "新增值班员账号",
        ip: "10.0.0.21",
      },
    ],
    total: 5,
    page: 1,
    size: 5,
    pages: 1,
  };
}

// =====================================================================
// 系统监控：服务状态 + API 调用 24h + 存储 + CPU/GPU/Mem 趋势
// =====================================================================

export function getSystemMonitorMock() {
  return {
    services: [
      { name: "Backend API", state: "UP", latency: 42, version: "1.7.0" },
      { name: "AI Service", state: "UP", latency: 132, version: "1.7.0" },
      { name: "PostgreSQL", state: "UP", latency: 8, version: "15.4" },
      { name: "Redis", state: "UP", latency: 2, version: "7.2" },
      {
        name: "MinIO",
        state: "UP",
        latency: 18,
        version: "RELEASE.2024-08-29T01-40-52Z",
      },
      { name: "EMQX", state: "UP", latency: 6, version: "5.6.1" },
    ],
    apiCalls24h: {
      total: 18472,
      success: 18260,
      failed: 212,
      successRate: 98.85,
    },
    storage: {
      pg: { total: 100, used: 38.4, unit: "GB" },
      td: { total: 500, used: 87.0, unit: "GB" },
      minio: { total: 1000, used: 156.8, unit: "GB" },
    },
    trends: {
      cpu: [22, 18, 26, 24, 32, 41, 38, 35, 28, 24, 22, 19],
      gpu: [12, 14, 18, 22, 26, 28, 25, 22, 19, 16, 14, 12],
      mem: [40, 41, 42, 43, 44, 46, 45, 44, 43, 42, 41, 41],
    },
  };
}

// =====================================================================
// AI 模型管理（v1 PRD 2.5）—— 全部走空骨架
// =====================================================================

export const modelsListMock = {
  records: [],
  total: 0,
  page: 1,
  size: 20,
  pages: 1,
};

export const modelDetailMock = null;
