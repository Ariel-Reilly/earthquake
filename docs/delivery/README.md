# 交付文档索引（DisasterGuard · 御灾智巡）

本目录是交付甲方的验收与运维文档集合，所有结论均以真实运行/构建结果为依据。

## 快速开始

```powershell
.\start-dev.ps1 -Infra -Up       # Postgres / Redis / MinIO
.\start-dev.ps1 -All -Wait       # 后端 8080 + 前端 5173 + AI 8001
# 浏览器打开 http://127.0.0.1:5173 ，admin / admin123
.\start-dev.ps1 -Stop            # 结束
```

## 文档地图

| 文档 | 用途 | 适合谁 |
|---|---|---|
| [DELIVERY_CHECKLIST.md](DELIVERY_CHECKLIST.md) | 交付检查总表（构建/测试/接口/浏览器/容器/回滚） | 项目负责人、验收人 |
| [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) | 部署手册（环境变量、Docker、启动、健康检查、常见故障） | 运维 |
| [DEMO_SCRIPT.md](DEMO_SCRIPT.md) | 现场演示脚本（15–20 分钟流程、触发命令、兜底话术、清理） | 演示人 |
| [SMOKE_TEST.md](SMOKE_TEST.md) | 冒烟清单与结果（SM-01~SM-18，含真机 CDP 自动化说明） | 测试、验收人 |
| [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) | 已知限制与二期能力（诚实边界） | 甲方、项目经理 |
| [ACCEPTANCE_LOG_20261112.md](ACCEPTANCE_LOG_20261112.md) | 每轮验收记录（问题、修复、证据、排障过程） | 所有人 |
| [DELIVERY_BACKLOG.md](DELIVERY_BACKLOG.md) | 剩余任务清单（P0 交付前必做 / P1 试运行前 / P2 二期，含验收标准与工时） | 项目经理、开发、甲方 |
| [STYLELINT_BASELINE.md](STYLELINT_BASELINE.md) | Stylelint 存量基线（5,264 条）与分批治理计划 | 前端 |
| [PRODUCTION_HARDENING.md](PRODUCTION_HARDENING.md) | 生产安全加固与上线检查（prod 校验器、配置检查器、端口收敛、密钥生成） | 运维、安全 |
| [env.production.example](env.production.example) | 生产环境变量模板（全部 `__GENERATE__` 占位，需替换后校验） | 运维 |

## 可执行的验收命令

```powershell
# 后端编译
cd backend; mvn -q -DskipTests compile

# 前端静态检查 / 单测 / 生产构建
cd frontend; npx eslint src/; npx vitest run; npm run build

# 生产配置静态检查（21 项；合格配置退出码 0）
node scripts/delivery/prod-config-check.mjs .env.production

# 生产 compose 组合校验（prod 是覆盖文件）
docker compose --env-file .env.production -f docker-compose.yml -f docker-compose.prod.yml config --quiet

# 真实浏览器冒烟（37 项，需 Edge/Chrome CDP 9222 + 服务已启动）
node scripts/delivery/browser-smoke.mjs
```

浏览器冒烟截图与机器可读汇总输出在 `output/browser-verify/`（含 `summary.json`）。

## 当前状态摘要（以本目录最新记录为准）

- 前端：eslint 0 error、vitest **57 passed**（含时相对比 12 例、权限回归 1 例）、vite build 成功、SFC/令牌完整性通过
- 后端：`mvn compile` 通过；新增 `SignProxyPermissionTest`（2）+ `SentinelCompareControllerTest`（4）全通过
- 真实浏览器：37/37 PASS（四档告警、AI 分析详情、确认→派发、设置落库、API 宕机、WS 轮询降级、角色 403 等）；
  时相对比新增页面 `verify-satellite-page.mjs` **10/10 PASS**（见 SM-19）
- **端到端联调（原 §31.4 缺口已关闭）**：nginx(dist) → 新网关 → ai-service 真实链路，
  `verify-sentinel-e2e.mjs` **14/14** + `verify-sentinel-browser-e2e.mjs` **15/15** PASS；修复「无场景硬失败」后
  页面进入诚实降级态并给出原因；详见 `ACCEPTANCE_LOG` §三十三
- **A1 可自动化预检**：WS 断线重连 **6/6 PASS**（阻断 32s、重连 2013 ms、轮询持续）；
  页面/按钮巡检 15 路由 / 442 按钮无空白页与 JS 异常；现场人工表见 [ONSITE_ACCEPTANCE_CHECKLIST.md](ONSITE_ACCEPTANCE_CHECKLIST.md)
- 干净机器部署演练（A3，2026-11-13）：空库初始化 / 历史库补丁幂等 / nginx `/api`+`/ws` 101 全通；
  修复 4 个全新部署阻断缺陷，见 [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) §9
- 决策已定（2026-11-13）：**跟随浏览器时区** / **演示材料单独成包** / **允许管理员代签（仅 admin）** / **时相对比必做**；
  详见 [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md) 〇节与 [DELIVERY_BACKLOG.md](DELIVERY_BACKLOG.md) §I
- 演示材料包：`output/delivery-materials/DisasterGuard-演示材料包-v1.0.zip`（19 PPTX + 脚本/文档 + MANIFEST + SHA256，UTF-8 条目名，不入库）
- 值班员一页纸：[OPERATOR_CARD.md](OPERATOR_CARD.md)；时相对比部署配置见 [DEPLOYMENT_GUIDE.md](DEPLOYMENT_GUIDE.md) §10
- 源码提交：`release/delivery` 分支；交付快照 tag **`v1.8.2-delivery`**（D-4 验收闭环轮次）；
  源码包 `output/delivery-verify/DisasterGuard-v1.8.2-delivery.zip` + 同名 `.sha256`；
  `v1.8.1-delivery` / `v1.8.0-delivery` 保留为历史快照，不强制移动
- 现场人工验收记录表：[ONSITE_ACCEPTANCE_CHECKLIST.md](ONSITE_ACCEPTANCE_CHECKLIST.md)（已纳入演示材料包）
- 已知待现场确认：真实听声、真机移动端、有副作用按钮点检、演示彩排、时相对比真实影像凭证、
  两条后端 500 修复后的重新部署复核（见现场验收表）
- **上线前仍须完成**：A1 现场人工验收（真机/听声/彩排）、A4 上线日注入（域名证书/改密/强密钥/审计留存）、A6-3/A6-4 决策（见 DELIVERY_BACKLOG）
