/**
 * 运营/资源/调度类离线 mock：
 * - 资源库（队伍/物资/场所）
 * - 数据源配置（外部气象/地震/卫星）
 * - OTA / 通知模板 / 应急预案 等 list 类骨架统一使用 emptyPaged
 */
import { emptyPaged } from "./empty";

// 资源库：3 类示例（队伍/物资/场所）

export function getResourcesMock() {
  return {
    records: [
      {
        id: 1,
        code: "TEAM-001",
        name: "自然资源局应急一组",
        type: "队伍",
        count: 12,
        location: "龙安镇",
        status: "在岗",
        lat: 107.21,
        lon: 30.61,
      },
      {
        id: 2,
        code: "MAT-014",
        name: "石桥镇应急物资仓",
        type: "物资",
        count: 86,
        location: "石桥镇",
        status: "可用",
        lat: 107.34,
        lon: 30.48,
      },
      {
        id: 3,
        code: "SHEL-007",
        name: "清河镇避难所",
        type: "场所",
        count: 200,
        location: "清河镇",
        status: "可用",
        lat: 107.55,
        lon: 30.42,
      },
    ],
    total: 3,
    page: 1,
    size: 3,
    pages: 1,
  };
}

// 数据源配置：6 个示例（气象/卫星/地震/地图）

export function getDataSourcesMock() {
  return [
    {
      code: "OPEN_METEO",
      label: "Open-Meteo 气象",
      enabled: true,
      healthy: true,
      lastSync: "12秒前",
    },
    {
      code: "NASA_GPM",
      label: "NASA Giovanni GPM 降雨",
      enabled: true,
      healthy: true,
      lastSync: "5分钟前",
    },
    {
      code: "USGS",
      label: "USGS 实时地震",
      enabled: true,
      healthy: true,
      lastSync: "8秒前",
    },
    {
      code: "OSM",
      label: "OSM Overpass 暴露度",
      enabled: true,
      healthy: false,
      lastSync: "32分钟前",
    },
    {
      code: "COPERNICUS",
      label: "Copernicus STAC 卫星",
      enabled: false,
      healthy: true,
      lastSync: "—",
    },
    {
      code: "NSMC",
      label: "国家气象 NSMC 云图",
      enabled: true,
      healthy: true,
      lastSync: "10秒前",
    },
  ];
}

// OTA / 通知模板 / 预案 / 固件 —— 全部走标准空分页
// 这些接口尚未接入后端，离线模式直接给空壳避免静默 null

export const otaFirmwareListMock = () => emptyPaged;
export const otaTaskListMock = () => emptyPaged;
export const otaTaskDetailMock = () => null;

export const noticeTemplateListMock = () => emptyPaged;
export const noticeTemplateDetailMock = () => null;

export const planListMock = () => emptyPaged;
export const planDetailMock = () => null;
export const planLaunchesMock = () => emptyPaged;

// 备份 / 报表 / OTA 任务等纯离线骨架

export const backupListMock = () => emptyPaged;
export const backupStrategiesMock = () => emptyPaged;
export const restoreHistoryMock = () => emptyPaged;
export const reportTemplatesMock = () => emptyPaged;
export const reportHistoryMock = () => emptyPaged;
export const approvalListMock = () => emptyPaged;
