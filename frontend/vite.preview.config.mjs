// ⚠️ 临时预览配置（验证用，验证结束后删除）
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import cesium from 'vite-plugin-cesium'

const WARNINGS = [
  {
    id: 'W-1001',
    title: '后山滑坡体位移速率突增',
    level: '橙色预警',
    desc: 'GNSS 位移监测点 24 小时累计位移 42mm，超过阈值 30mm，坡体存在失稳风险',
    point: '临溪镇 后山 K3+200 监测点',
    time: '2026-10-27 14:32',
    status: '待确认',
    hazardCode: 'HZ-001',
    cssClass: 'orange',
    notificationRate: 92,
    linkedDepartments: ['应急管理局', '自然资源局', '临溪镇政府'],
  },
  {
    id: 'W-1002',
    title: '梅溪河水位超警戒 1.2m',
    level: '红色预警',
    desc: '上游强降雨叠加水库泄洪，水位已达 118.6m，超警戒水位 1.2m',
    point: '梅溪河 梅溪水文站',
    time: '2026-10-27 13:05',
    status: '已派发',
    hazardCode: 'HZ-002',
    cssClass: 'red',
    notificationRate: 100,
    linkedDepartments: ['应急管理局', '水利局', '消防救援支队'],
  },
  {
    id: 'W-1003',
    title: '东坡裂缝宽度扩大',
    level: '黄色预警',
    desc: '裂缝监测仪记录宽度由 6mm 增至 11mm，暂未达到红色阈值',
    point: '东坡 3 号裂缝观测点',
    time: '2026-10-27 11:48',
    status: '已确认',
    hazardCode: 'HZ-003',
    cssClass: 'yellow',
    notificationRate: 88,
    linkedDepartments: ['自然资源局'],
  },
  {
    id: 'W-1004',
    title: '云台山区域降雨量异常',
    level: '蓝色预警',
    desc: '区域累计降雨量 120mm，土壤含水量接近饱和，关注后续变化',
    point: '云台山 雨量观测站 A',
    time: '2026-10-27 09:20',
    status: '处理中',
    hazardCode: 'HZ-004',
    cssClass: 'blue',
    notificationRate: 0,
    linkedDepartments: ['气象局', '临溪镇政府'],
  },
  {
    id: 'W-0995',
    title: '旧矿区地表沉降复测',
    level: '橙色预警',
    desc: '沉降速率 18mm/月，超过设计阈值，已完成现场核查',
    point: '旧矿区 沉降监测网',
    time: '2026-10-20 08:10',
    status: '已关闭',
    hazardCode: 'HZ-005',
    cssClass: 'orange',
    closeReason: '现场处置完成，险情解除',
    closedAt: '2026-10-22 16:40',
    notificationRate: 95,
    linkedDepartments: ['自然资源局'],
  },
  {
    id: 'W-0990',
    title: '村道边坡小型落石',
    level: '蓝色预警',
    desc: '巡测发现零星落石，已设置警示标志',
    point: 'S203 村道 K7 段',
    time: '2026-10-15 15:02',
    status: '已忽略',
    hazardCode: 'HZ-006',
    cssClass: 'blue',
    closeReason: '确认为误报，阈值误触发',
    closedAt: '2026-10-16 09:00',
    notificationRate: 76,
    linkedDepartments: [],
  },
]

const AI = [
  { hazardCode: 'HZ-001', riskLevel: 'HIGH', riskScore: 87.4 },
  { hazardCode: 'HZ-002', riskLevel: 'CRITICAL', riskScore: 96.1 },
  { hazardCode: 'HZ-003', riskLevel: 'MEDIUM', riskScore: 54.2 },
  { hazardCode: 'HZ-004', riskLevel: 'LOW', riskScore: 22.7 },
  { hazardCode: 'HZ-005', riskLevel: 'HIGH', riskScore: 73.0 },
  { hazardCode: 'HZ-006', riskLevel: 'LOW', riskScore: 18.3 },
]

function mockApiPlugin() {
  return {
    name: 'preview-mock-api',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const path = (req.url || '').split('?')[0]
        const send = (data) => {
          res.setHeader('Content-Type', 'application/json; charset=utf-8')
          res.end(JSON.stringify({ code: 0, data }))
        }
        if (path === '/api/warnings') {
          return send({ records: WARNINGS, total: WARNINGS.length, page: 1, size: 10, pages: 1 })
        }
        if (path === '/api/ai/comprehensive-analysis/all') {
          return send(AI)
        }
        if (path === '/api/system/health') {
          return send({ status: 'UP' })
        }
        next()
      })
    },
  }
}

export default defineConfig({
  plugins: [vue(), cesium(), mockApiPlugin()],
  server: { host: '127.0.0.1', port: 5199, strictPort: true },
})
