/**
 * 演示数据 SEED_BASE —— 地质灾害监测场景的最小可演示数据集。
 *
 * 字段约束与后端 schema 对齐：
 * - hazard_point（HazardDto）
 * - inspection_task（InspectionTaskDto）
 * - monitoring_device（MonitoringDeviceDto）
 * - warning_event（WarningDto）
 * - emergency_incident（EmergencyDto）
 *
 * 使用方式：
 *   import { SEED_BASE, findHazardById, tasksOfHazard, ... } from './seed';
 *
 * 注意：本文件只放"形状 + 查找函数"，不实现业务派生（派生请放 mock/ai.js 等业务模块）。
 */

// 总览看板 4 个 KPI
const overviewStats = [
  {
    label: "监测灾害点",
    value: "128",
    suffix: "处",
    change: "↑ 4.2% 较上月",
    color: "teal",
  },
  {
    label: "在线监测设备",
    value: "1,486",
    suffix: "台",
    change: "↑ 2.8% 较上月",
    color: "blue",
  },
  {
    label: "当前预警事件",
    value: "05",
    suffix: "起",
    change: "↑ 1 起 较昨日",
    color: "orange",
  },
  {
    label: "今日巡检完成率",
    value: "92.4",
    suffix: "%",
    change: "↑ 6.3% 较昨日",
    color: "purple",
  },
];

// 灾害隐患点（5 个）
const hazardPoints = [
  {
    id: 1,
    code: "HZ-001",
    name: "青龙坡滑坡隐患点",
    type: "滑坡",
    level: "高风险",
    distance: "1.2 km",
    status: "位移 +12mm",
    owner: "龙安镇",
    lon: "107.21",
    lat: "30.61",
  },
  {
    id: 2,
    code: "HZ-002",
    name: "大岭村崩塌点",
    type: "崩塌",
    level: "关注",
    distance: "2.8 km",
    status: "裂缝 18mm",
    owner: "石桥镇",
    lon: "107.34",
    lat: "30.48",
  },
  {
    id: 3,
    code: "HZ-003",
    name: "白石沟泥石流",
    type: "泥石流",
    level: "正常",
    distance: "5.6 km",
    status: "雨量 8.2mm",
    owner: "清河镇",
    lon: "107.55",
    lat: "30.42",
  },
  {
    id: 4,
    code: "HZ-004",
    name: "云台山地裂缝",
    type: "地裂缝",
    level: "关注",
    distance: "3.3 km",
    status: "倾角 2.1°",
    owner: "清河镇",
    lon: "107.61",
    lat: "30.67",
  },
  {
    id: 5,
    code: "HZ-005",
    name: "石门沟滑坡点",
    type: "滑坡",
    level: "正常",
    distance: "4.1 km",
    status: "位移稳定",
    owner: "龙安镇",
    lon: "107.14",
    lat: "30.56",
  },
];

// 巡检任务（4 个）
const tasks = [
  {
    id: 1,
    name: "雨后重点灾害点复查",
    type: "雨后复查",
    owner: "王建国",
    due: "18:00",
    progress: 92,
    status: "执行中",
    hazardId: 1,
    hazardCode: "HZ-001",
    hazardLon: 107.21,
    hazardLat: 30.61,
  },
  {
    id: 2,
    name: "青龙坡高风险点加密巡检",
    type: "应急巡检",
    owner: "周小雨",
    due: "16:30",
    progress: 68,
    status: "待处理",
    hazardId: 1,
    hazardCode: "HZ-001",
    hazardLon: 107.21,
    hazardLat: 30.61,
  },
  {
    id: 3,
    name: "云台山地裂缝专项排查",
    type: "专项排查",
    owner: "陈晓峰",
    due: "20:00",
    progress: 100,
    status: "已完成",
    hazardId: 4,
    hazardCode: "HZ-004",
    hazardLon: 107.61,
    hazardLat: 30.67,
  },
  {
    id: 4,
    name: "石门沟设备巡检与校准",
    type: "常规巡检",
    owner: "李敏",
    due: "17:20",
    progress: 54,
    status: "执行中",
    hazardId: 5,
    hazardCode: "HZ-005",
    hazardLon: 107.14,
    hazardLat: 30.56,
  },
];

// 监测设备（4 个）
const devices = [
  {
    id: 1,
    name: "GNSS-001",
    type: "GNSS位移",
    point: "青龙坡",
    hazardId: 1,
    state: "在线",
    battery: 92,
    signal: "强",
    last: "2分钟前",
    lon: 107.21,
    lat: 30.61,
  },
  {
    id: 2,
    name: "CRK-014",
    type: "裂缝计",
    point: "大岭村",
    hazardId: 2,
    state: "在线",
    battery: 88,
    signal: "强",
    last: "1分钟前",
    lon: 107.34,
    lat: 30.48,
  },
  {
    id: 3,
    name: "RAIN-052",
    type: "雨量计",
    point: "白石沟",
    hazardId: 3,
    state: "离线",
    battery: 34,
    signal: "弱",
    last: "18分钟前",
    lon: 107.55,
    lat: 30.42,
  },
  {
    id: 4,
    name: "INC-223",
    type: "倾角计",
    point: "云台山",
    hazardId: 4,
    state: "在线",
    battery: 76,
    signal: "中",
    last: "刚刚",
    lon: 107.61,
    lat: 30.67,
  },
];

// 预警事件（2 个）
const warnings = [
  {
    id: 1,
    bizNo: "W-20260813-001",
    level: "橙色预警",
    cssClass: "orange",
    title: "青龙坡滑坡隐患点",
    desc: "位移速率连续 3 小时超过阈值",
    time: "10:42",
    point: "青龙坡 · 龙安镇",
    status: "待确认",
    version: 1,
    updatedAt: "2026-08-13T10:42:00+08:00",
    lon: 107.21,
    lat: 30.61,
    hazardId: 1,
  },
  {
    id: 2,
    bizNo: "W-20260813-002",
    level: "蓝色提醒",
    cssClass: "blue",
    title: "云台山地裂缝",
    desc: "倾角变化达到关注值",
    time: "09:15",
    point: "云台山 · 清河镇",
    status: "待确认",
    version: 1,
    updatedAt: "2026-08-13T09:15:00+08:00",
    lon: 107.61,
    lat: 30.67,
    hazardId: 4,
  },
];

// 应急事件（1 个）
const incidents = [
  {
    id: 1,
    title: "青龙坡橙色预警",
    step: "专家复核中",
    time: "10:52",
    owner: "自然资源局值班组",
    warningId: 1,
    level: "橙色预警",
  },
];

export const SEED_BASE = Object.freeze({
  overviewStats,
  hazardPoints,
  tasks,
  devices,
  warnings,
  incidents,
});

// 附件按业务分类
export const SEED_ATTACHMENTS = Object.freeze({
  hazard: [
    { name: "青龙坡位移监测月报.pdf", size: "2.4 MB" },
    { name: "隐患点现场核查照片.zip", size: "18.6 MB" },
    { name: "群测群防巡查记录表.xlsx", size: "356 KB" },
  ],
  device: [
    { name: "GNSS 安装调试记录.pdf", size: "1.2 MB" },
    { name: "设备校准证书.png", size: "880 KB" },
    { name: "运维工单派发单.pdf", size: "512 KB" },
  ],
  task: [
    { name: "巡检任务书.pdf", size: "960 KB" },
    { name: "现场巡检照片.zip", size: "24.1 MB" },
    { name: "巡检结果签认表.xlsx", size: "428 KB" },
  ],
  warning: [
    { name: "预警处置单.pdf", size: "680 KB" },
    { name: "监测数据导出.xlsx", size: "1.6 MB" },
  ],
});

// 查找 / 过滤助手（离线模式复刻后端 join 行为）

export function findHazardById(id) {
  return (
    SEED_BASE.hazardPoints.find((entry) => String(entry.id) === String(id)) ??
    null
  );
}

export function findTaskById(id) {
  return (
    SEED_BASE.tasks.find((entry) => String(entry.id) === String(id)) ?? null
  );
}

export function findDeviceById(id) {
  return (
    SEED_BASE.devices.find((entry) => String(entry.id) === String(id)) ?? null
  );
}

export function findWarningById(id) {
  return (
    SEED_BASE.warnings.find((entry) => String(entry.id) === String(id)) ?? null
  );
}

export function tasksOfHazard(hazardId) {
  return SEED_BASE.tasks.filter(
    (task) => String(task.hazardId) === String(hazardId),
  );
}

export function devicesOfHazard(hazardId) {
  return SEED_BASE.devices.filter(
    (device) => String(device.hazardId) === String(hazardId),
  );
}

export function warningsOfHazard(hazardId) {
  return SEED_BASE.warnings.filter(
    (warning) => String(warning.hazardId) === String(hazardId),
  );
}

export function incidentsOfWarning(warningId) {
  return SEED_BASE.incidents.filter(
    (incident) => String(incident.warningId) === String(warningId),
  );
}

// 详情页 history / linked 派生（与后端 DTO 字段保持一致）

export function historyOfDevice(deviceId) {
  const device = findDeviceById(deviceId);
  const history = [];
  if (device) {
    history.push({
      title: "设备接入",
      desc: `${device.name}（${device.type}）接入监测网络`,
      time: "2026-06-01",
    });
    history.push({
      title: "数据上报",
      desc: `${device.state === "离线" ? "离线，最后上报于" : "最近上报于"} ${device.last}，信号 ${device.signal}`,
      time: device.last,
    });
  }
  const hazard = device ? findHazardById(device.hazardId) : null;
  if (hazard) {
    history.push({
      title: `绑定灾害点「${hazard.name}」`,
      desc: `监测对象风险等级 ${hazard.level}`,
      time: "建档",
    });
  }
  return history;
}

export function linkedOfDevice(deviceId) {
  const device = findDeviceById(deviceId);
  if (!device) {return [];}
  const links = [];
  const hazard = findHazardById(device.hazardId);
  if (hazard)
    {links.push({
      type: "绑定灾害点",
      label: hazard.name,
      path: `/dashboard/hazards/${hazard.id}`,
    });}
  for (const warning of warningsOfHazard(device.hazardId)) {
    links.push({
      type: "关联预警",
      label: `${warning.level} · ${warning.title}`,
      path: `/dashboard/warnings/${warning.id}`,
    });
  }
  for (const task of tasksOfHazard(device.hazardId)) {
    links.push({
      type: "关联任务",
      label: task.name,
      path: `/dashboard/tasks/${task.id}`,
    });
  }
  return links;
}

export function historyOfTask(taskId) {
  const task = findTaskById(taskId);
  if (!task) {return [];}
  return [
    {
      title: "任务创建",
      desc: `${task.name}（${task.type}）已创建，负责人 ${task.owner}`,
      time: "今天 08:00",
    },
    {
      title: "GPS 围栏校验",
      desc: "巡检员已到达任务点位附近，围栏校验通过",
      time: "今天",
    },
    {
      title: "任务执行",
      desc: `${task.status}，完成进度 ${task.progress}%`,
      time: "实时",
    },
  ];
}

export function linkedOfTask(taskId) {
  const task = findTaskById(taskId);
  if (!task) {return [];}
  const links = [];
  const hazard = findHazardById(task.hazardId);
  if (hazard)
    {links.push({
      type: "关联灾害点",
      label: hazard.name,
      path: `/dashboard/hazards/${hazard.id}`,
    });}
  for (const warning of warningsOfHazard(task.hazardId)) {
    links.push({
      type: "关联预警",
      label: `${warning.level} · ${warning.title}`,
      path: `/dashboard/warnings/${warning.id}`,
    });
  }
  for (const device of devicesOfHazard(task.hazardId)) {
    links.push({
      type: "监测设备",
      label: device.name,
      path: `/dashboard/devices/${device.id}`,
    });
  }
  return links;
}

export function historyOfWarning(warningId) {
  const warning = findWarningById(warningId);
  if (!warning) {return [];}
  const history = [
    { title: "预警触发", desc: warning.desc, time: warning.time },
  ];
  if (warning.status !== "待确认")
    {history.push({
      title: `预警${warning.status === "已忽略" ? "忽略（误报）" : warning.status === "已解除" ? "解除（自动）" : warning.status}`,
      desc: warning.closeReason || "处置流程正常推进",
      time: "实时",
    });}
  return history;
}

export function linkedOfWarning(warningId) {
  const warning = findWarningById(warningId);
  if (!warning) {return [];}
  const links = [];
  const hazard = findHazardById(warning.hazardId);
  if (hazard)
    {links.push({
      type: "触发灾害点",
      label: hazard.name,
      path: `/dashboard/hazards/${hazard.id}`,
    });}
  for (const device of devicesOfHazard(warning.hazardId)) {
    links.push({
      type: "监测设备",
      label: device.name,
      path: `/dashboard/devices/${device.id}`,
    });
  }
  for (const task of tasksOfHazard(warning.hazardId)) {
    links.push({
      type: "关联任务",
      label: task.name,
      path: `/dashboard/tasks/${task.id}`,
    });
  }
  for (const incident of incidentsOfWarning(warningId)) {
    links.push({
      type: "应急事件",
      label: incident.title,
      path: `/dashboard/emergency`,
    });
  }
  return links;
}
