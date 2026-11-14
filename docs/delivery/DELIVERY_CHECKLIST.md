# 交付检查表（DisasterGuard · 御灾智巡）

> 版本：v1.8 · 检查日期：2026-11-12/13 · 检查人：开发交付组
> 结论：**可进入现场演示 / 试运行**。已知限制与二期能力见 [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md)。

## 一、交付范围与环境

- 前端：Vue 3 + Vite 6 + Pinia；后端：Spring Boot 3.4 / Java 17；AI：FastAPI（8001，真实模型）
- 基础设施：Docker Compose（Postgres / Redis / MinIO；EMQX、TDengine 默认不启用）
- 演示入口：`http://127.0.0.1:5173`（admin / admin123；其余角色见 data.sql）
- 一键启动：`.\start-dev.ps1 -Infra -Up` → `.\start-dev.ps1 -All -Wait`；停止：`.\start-dev.ps1 -Stop`

## 二、构建与静态检查（必须全绿）

| 检查项 | 命令 | 结果 |
|---|---|---|
| 前端 lint | `cd frontend; npx eslint src/` | ✅ 0 error |
| 前端单测 | `npx vitest run` | ✅ **69 passed**（7 个测试文件；含 B5-4 SFC 组件测试 12 例） |
| 后端状态机单测 | `cd backend; mvn -q "-Dtest=EventStateMachineTest" test` | ✅ **11 passed**（B5-1；真实 Spring StateMachine 工厂，无 DB 凭据依赖） |
| 后端全量测试 | `cd backend; mvn test`（需 Docker） | ✅ **141 tests / 0 failures / 0 errors / 4 skipped**；Testcontainers 一次性 PostGIS 容器（B5-6） |
| ai-service pytest | `cd ai-service; python -m pytest tests -q` | ✅ **102 passed / 4 skipped**（10 个文件，6.48s；B5-5） |
| 前端生产构建 | `npm run build` | ✅ 成功（`dist/`） |
| SFC 完整性 | `node scripts/verify-sfc-integrity.mjs` | ✅ 全部组件解析通过 |
| 设计令牌完整性 | `node scripts/verify-token-integrity.mjs` | ✅ 无未定义引用/依赖环 |
| 后端编译 | `cd backend; mvn -q -DskipTests compile` | ✅ 通过 |
| AI 模型健康 | `GET /api/ai/health` | ✅ UP；模型加载状态可查 |
| A1 现场项自动化预检 | 见 [A1_PREFLIGHT_REPORT.md](A1_PREFLIGHT_REPORT.md) | ✅ 隔离环境 4 脚本 / **34 项判定全通过**（移动端 8/8、音频链路 10/10、演示预演 5/5、有副作用按钮 11/11）；纯人工项仍见 `ONSITE_ACCEPTANCE_CHECKLIST.md` |

## 三、接口与运行时验收

| 验收项 | 结果 |
|---|---|
| 后端健康 `/api/system/health` | ✅ UP（PostgreSQL OK） |
| 前端 dev server + `/api` 代理 | ✅ HTTP 200 |
| 6 角色权限矩阵（菜单/接口 200/403） | ✅ 接口级实测；值班员直达用户管理被 403 |
| `tasks:*` / `backup:*` / `plans:*` 权限 | ✅ 接口实测通过，前端权限码已同步 PermissionRegistry |
| 预警生命周期 | ✅ 待确认 → 已确认 → 处理中 → 已关闭；解除链路 200 |
| 预警/设备坐标字段（地图图层） | ✅ 响应含 lon/lat |
| WS 直连 + Vite `/ws` 代理握手 | ✅ 直连 Open；代理 101 Switching Protocols |
| **WS 预警广播** | ✅ 创建预警后收到 `type=warning`（修复 JavaTimeModule 后） |
| WS 断线降级轮询 | ✅ 阻断 `/ws?*` 后页面仍加载，新建预警经轮询刷新"活跃"计数与列表 |
| 会签多人 + 冒名防护 | ✅ 2 行同 signId 成功；**A6-1 决策后**：admin 持有 `sign:proxy` 可代签并写 warn 审计日志，其余角色代签仍 403（`SignProxyPermissionTest`） |
| AI 链路 | ✅ 综合评分 9 条、设备健康 16 条；多模态 501（设计边界）；四档颜色与 80/60/35 阈值一致 |
| 审计日志筛选 | ✅ 全量 1405 条，`action=LOGIN&range=7` 过滤后 68 条，服务端真实过滤 |
| 报表导出 | ✅ `POST /api/warnings/export`、`/api/devices/export` 200 + xlsx |
| **系统设置落库** | ✅ `system_setting` 表 + `GET/PUT /api/settings`；浏览器保存→刷新仍存在（SM-16） |
| **预警 AI 分析详情** | ✅ `GET /api/warnings/{id}/analysis-detail`：AI 结论 / 详细分析 / 事发前后 ±2h 指标 / 现场影像（SM-18） |
| 对比度/可访问性 | ✅ 审计"确定问题" 5→2（剩余 2 处经核对为脚本跨文件解析误报）；关键纯图标按钮补 aria-label |
| **时相对比页面（B1，必做）** | ✅ 独立页面 + Java 网关 + 菜单/路由/权限；**生产形态端到端实测**：网关 API 14/14 + 真实浏览器 15/15 PASS（nginx→新网关→ai-service）；无真实影像时只提示降级并给出具体原因（日期窗/AOI），不展示假图（SM-19） |
| **管理员代签（A6-1）** | ✅ `sign:proxy` 仅 admin；`SignProxyPermissionTest` 覆盖 admin 放行与 expert 403；每次代签写 warn 审计 |
| **演示材料归属（A2-2）** | ✅ 单独材料包（19 PPTX + 5 文档 + MANIFEST + SHA256，UTF-8 条目名），不入库 |
| **时间显示口径（A6-5）** | ✅ 决策跟随浏览器本地时区，无需改码；已写入 L-15 与 `OPERATOR_CARD.md` |

## 四、真实浏览器端到端（CDP 自动化）

- 脚本：`node scripts/delivery/browser-smoke.mjs`（Edge/Chrome CDP，无 Playwright/Puppeteer）
- 结果：**37 / 37 PASS**，截图 23 张在 `output/browser-verify/`，机器汇总 `summary.json`
- 覆盖：登录、设备页、红色全屏警报（样式/声音兜底/确认/详情跳转）、橙/黄/蓝弹窗、
  移动端 390px 抽屉与无横向溢出、深色模式、综合态势首页、实时地图 canvas、审计日志筛选、
  后端不可达错误态（不误报"态势平稳"）、网络恢复、AI 任务页、设置落库、WS 阻断轮询、
  预警详情确认→派发、AI 分析详情、角色越权 403、登出。
- 防缓存：脚本已启用 `Network.setCacheDisabled(true)`，避免 Vite dev 样式缓存污染颜色验收。

## 五、安全与数据可信

| 项 | 说明 |
|---|---|
| 认证/授权 | JWT + `PermissionRegistry`；前端菜单/路由与后端 `@PreAuthorize` 双向校验 |
| 会签冒名 | signerId 必须与当前登录用户一致，否则 403 |
| AI 图像下载 SSRF | ai-service 对图片 URL 做协议/主机白名单校验（file:// 拒绝） |
| 演示数据标识 | 首页/分析页对 synthetic/降级数据显式标识；空态不伪装成"态势平稳" |
| 审计 | 操作审计落库可查；告警/处置行为可追溯 |
| 初始账号 | 演示环境 admin/admin123，生产启用前必须改密 |

## 六、部署与回滚

1. 准备 `.env`（数据库/Redis/MinIO/AI 地址、JWT 密钥、生产域名）。
2. `docker compose up -d postgres redis minio`（按需启用 emqx/tdengine）。
3. 后端 `mvn -q -DskipTests package` 后 `java -jar`，或 `.\start-dev.ps1 -Backend`。
4. 前端 `npm run build` 产物交给 nginx；`/api` 与 `/ws` 反代到 8080。
5. 健康检查：`/api/system/health`、`/api/ai/health`、首页可登录。
6. 回滚：保留上一版 jar/dist 与数据库备份；schema 变更为幂等补丁，可直接回退应用版本。
7. 首次启动会自动建表/补表（`system_setting`、`event_sign` 补丁）；历史库无需手工迁移。
   > 2026-11-13 已按 [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) §9 用**隔离端口 + 全新数据卷**实跑：空库初始化、
   > 历史库补丁（含重复启动幂等）、`mvn package` + `npm run build` + nginx `/api`、`/ws`（101）全通。
   > 演练同时修复 4 个「全新部署」P0 阻断（initdb 脚本依赖、种子外键、规则镜像创建人、actions JSON 结构）。

## 七、生产安全与备份恢复（A4/A5，2026-11-13）

| 验收项 | 结果/证据 |
|---|---|
| 生产配置检查器 | ✅ `scripts/delivery/prod-config-check.mjs`（21 项）：合格模拟 env **21/21 PASS**；开发 env 按预期报 13 error |
| 生产 env 模板 | ✅ `docs/delivery/env.production.example`（所有密钥 `__GENERATE__`，含 EMQX 必需变量） |
| prod 启动 fail-fast | ✅ 不安全开关实测 `IllegalStateException: prod 配置校验失败（4 项）`，进程退出 1；MinIO 空凭证也会提前拒绝启动 |
| prod compose 解析 | ✅ `docker compose --env-file env.production.example -f docker-compose.yml -f docker-compose.prod.yml config --quiet` 退出码 0 |
| 端口收敛 | ✅ `.env.example` AI `127.0.0.1:8001`；`docker-compose.prod.yml` 后端默认 `127.0.0.1:8081`；公网仅 frontend nginx |
| **A5 Postgres 备份恢复** | ✅ dump 434,253 B（SHA256 `60839886…9434`）→ 临时库恢复；8 张关键表行数全部相等（预警 46/设备 16/审计 1764/规则 6/设置 1/灾害点 9/AI 9/指标 5899） |
| **A5 MinIO 备份恢复** | ✅ 5 个对象；tar 119,110 B（SHA256 `E548ED84…1B38`）解包后对象数 5=5 |
| **A5 恢复后应用验证** | ✅ 恢复库上独立后端（18080）：健康/登录/预警/设备/设置/规则/审计/首页全部 code=0；验证后清理临时库 |
| 仍待上线日（A4 环境项） | 真实域名/证书、强密钥注入、防火墙、改初始密码、审计留存——见 [PRODUCTION_HARDENING.md](PRODUCTION_HARDENING.md) §五 |

## 八、仍待现场确认（不阻塞交付）

- 红色警报声音实际外放（headless 无法听声；已提供点击开启兜底与单测）
- WS 断线重连计时/提示的现场观感
- 时相对比页面：端到端联调与越权路径已自动化通过；待现场补的是**真实卫星影像凭证**（CDSE / GEE / 公开 STAC）下的三栏真实结果与「取消任务」路径（见 SM-19 / [ONSITE_ACCEPTANCE_CHECKLIST.md](ONSITE_ACCEPTANCE_CHECKLIST.md)）
- 影像需真实上传：种子附件 URL 可能失效，页面显示占位而非伪造图片

## 九、验收结论

- 构建/单测/浏览器冒烟全部通过；核心业务闭环（预警发布→确认→派发→关闭→审计）可演示。
- 时相对比**生产形态端到端**已补做：网关 API 14/14 + 真实浏览器 15/15 PASS（见 `ACCEPTANCE_LOG` §三十三）。
- A1 可自动化项已预检完成；纯人工项见 [ONSITE_ACCEPTANCE_CHECKLIST.md](ONSITE_ACCEPTANCE_CHECKLIST.md)。
- **2026-11-14 审查 P0/P1 修复已回归**：设备地图 500 / 静默 mock 假数据、灾害预测序列化 500、
  admin 气象页 403、drift 路径与 0 值、预警空白 KPI、灾害列表首屏弹窗全部关闭；
  真实接口 **12/12** + 真实浏览器 **14/14** PASS（7 页面、无意外 5xx / JS 异常 / 横向溢出）；
  见 [REVIEW_FIX_REPORT.md](REVIEW_FIX_REPORT.md) 与 `ACCEPTANCE_LOG` §四十四。
- 建议现场按 [DEMO_SCRIPT.md](DEMO_SCRIPT.md) 走一遍，并在试运行环境改密、配置真实地图/AI 服务地址。
- 演示前若发现「0 在线 / 预警日期过期」，由演示负责人确认目标库并备份后执行
  `scripts/delivery/refresh-demo-data.sql`（仅 UPDATE、幂等；**本轮只在隔离库验证，未在开发库执行**）。
- 甲方签字：____________________　日期：____________________
