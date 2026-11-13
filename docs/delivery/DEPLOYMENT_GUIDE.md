# 交付部署指南（Deployment Guide）

> 适用版本：DisasterGuard / 御灾智巡 v2.0（工作区快照 2026-11-12；演练复核 2026-11-13）
> 事实来源：仓库内代码与配置。**行号基于当前工作区快照**，若其他并行修复改动会漂移，以路径内符号为准。
> 2026-11-13 已完成「干净机器部署演练」（A3）：隔离端口/数据卷实跑 `compose up → 空库建表 → 登录/首页/预警 → nginx 反代`，
> 全过程与修复见 **§9**。标注「待确认」的条目（HTTPS、域名、真实密钥、AI 服务）仍须由部署人员现场核验。

---

## 1. 端口总表

| 组件 | 宿主机端口（开发） | 容器内端口 | 证据 |
|---|---|---|---|
| 前端 dev server | 5173 | — | `start-dev.ps1:21`、`frontend/vite.config.js:23-50` |
| 前端 nginx | `FRONTEND_HOST_PORT:-127.0.0.1:5173` | 80 | `docker-compose.yml:242-245` |
| 后端 Spring Boot | 8080 | 8080 | `backend/src/main/resources/application.yml`（server.port，默认 8080）、`docker-compose.yml:195-198` |
| AI 推理服务 FastAPI | **8001** | 8001 | `docker-compose.yml:221-222`、`backend/src/main/resources/application.yml:65`、`frontend/.env.example:72-77` |
| PostgreSQL | 15432（compose 映射） | 5432 | `docker-compose.yml:11-12` |
| Redis | 16379 | 6379 | `docker-compose.yml:41-42` |
| MinIO | 19000 / 19001（API / 控制台） | 9000 / 9001 | `docker-compose.yml:61-63` |
| EMQX | 11883 / 18883 / 18083 / 18084 / 28083 | 1883 / 8883 / 8083 / 8084 / 18083 | `docker-compose.yml:97-102` |
| TDengine（可选） | 16030 / 16041 | 6030 / 6041 | `docker-compose.yml:119-121` |

> AI 服务端口必须统一为 **8001**。历史上 `frontend/.env.example` 曾写 8000，本次已修正（见 `frontend/.env.example:72-77`）。

---

## 2. 环境变量清单

### 2.1 前端（`frontend/.env`、`frontend/.env.local`，模板见 `frontend/.env.example`）

| 变量 | 示例/默认 | 说明 | 模板行 |
|---|---|---|---|
| `VITE_USE_BACKEND` | `true` | `true`=走后端 API；`false`=浏览器内 mock 演示数据 | `frontend/.env.example:1` |
| `VITE_API_BASE_URL` | `http://localhost:8080/api` | 后端 API 基址；生产经 nginx 同源代理时用 `/api` | `:2` |
| `VITE_WS_URL` | 空 / `/ws` | WebSocket 地址；空时由 API 基址推导 | `:16` |
| `VITE_REALTIME_POLL_INTERVAL` | `15000` | 实时轮询兜底间隔（毫秒） | `:12` |
| `VITE_WARNING_POLL_INTERVAL` | `10000` | 预警高频轮询 | `:13` |
| `VITE_PERSONNEL_POLL_INTERVAL` | `30000` | 人员位置轮询 | `:14` |
| `VITE_USE_PERSONNEL_LIVE` | `false` | `true`=人员坐标由移动端上报后端 | `:3,:15` |
| `VITE_AMAP_TOKEN` / `VITE_AMAP_SECURITY_JS_CODE` | 占位 | 高德地图 Key；缺失时自动降级天地图 → ESRI | `:103-121` |
| `VITE_TDT_TOKEN` / `VITE_TDT_TK` | 空 | 天地图兜底凭证 | `:112-114` |
| `VITE_MAP_TILE_URL` / `VITE_SATELLITE_TILE_URL` | 空 | 自定义瓦片源（最高优先级） | `:117-118` |

> 前端 `JWT_SECRET`、`SERVER_PORT`、`POSTGRES_*` 等变量虽并列在 `frontend/.env.example`，但实际由后端读取；放在根目录 `.env` 或后端运行环境更合适。
> 注意：容器构建时前端变量在 build 阶段注入（`docker-compose.yml:211-224` 的 `args`），运行期再改 `.env` 不会重新生效。

### 2.2 后端（根目录 `.env`，模板见根 `.env.example`）

| 变量 | 默认/示例 | 说明 | 证据 |
|---|---|---|---|
| `JWT_SECRET` | **必须改为强随机**（≥32 字符） | 弱密钥/占位符会导致启动失败 | 根 `.env.example:26`、`backend/.../application.yml:105-110`、`ProductionConfigValidator` |
| `SERVER_PORT` | `8080` | 后端端口 | 根 `.env.example:55` |
| `FRONTEND_ORIGIN` | `http://localhost:5173` | CORS 允许来源（生产改为真实前端域名，禁止 `*`） | 根 `.env.example:56` |
| `POSTGRES_HOST/PORT/DB/USER/PASSWORD` | `localhost` / `15432` / `disasterguard` / … | 数据库连接；生产必须改强密码 | 根 `.env.example:89-96`、`application.yml:14-18` |
| `REDIS_HOST/PORT/PASSWORD` | `localhost` / `16379` / … | 缓存与会话 | 根 `.env.example:100-104`、`application.yml:39-42` |
| `MINIO_ENDPOINT` / `MINIO_PUBLIC_URL` / `MINIO_BUCKET` / `MINIO_ROOT_USER/PASSWORD` | `http://localhost:19000` / `disasterguard` | 附件与影像对象存储；`MINIO_PUBLIC_URL` 供浏览器访问 | 根 `.env.example:269-276` |
| `FILE_BASE_URL` | `http://localhost:19000` | 附件下载基址 | 根 `.env.example`、`application.yml:112` |
| `AI_ENABLED` | `true` | `false` 时后端纯规则模式，不调用 AI | `application.yml:64` |
| `AI_SERVICE_BASE_URL` | `http://localhost:8001/api/ai` | AI 服务基址（容器内 `http://ai-service:8001/api/ai`） | `application.yml:65`、`application-docker.yml:27`、`application-prod.yml:50` |
| `AI_IMAGE_SERVICE_URL` | `http://127.0.0.1:8001/api/ai/detect-image` | 图像识别独立地址 | `application.yml:143-145` |
| `AI_CORS_ORIGINS` | `http://localhost:5173,http://localhost:8080` | ai-service 允许的前端来源 | `docker-compose.yml:218` |
| `EMQX_ENABLED` | `true` | 设备 MQTT 接入开关 | 根 `.env.example:132` |
| `EMQX_*` / MQTT 端口 | `1883` 等 | Broker 连接与端口 | 根 `.env.example:120-137`、`application.yml`（emqx 段） |
| `DEV_USERS_ENABLED` | `true`（开发） | 允许内存兜底账号；**生产必须 `false`** | `application.yml:88`、`DbUserDetailsService` 类注释 |

### 2.3 AI 服务（`ai-service/.env`，模板见 `ai-service/.env.example`）

| 变量 | 默认 | 说明 |
|---|---|---|
| 监听端口 | `8001` | `ai-service/scripts/run_service.py --port 8001`、`ai-service/Dockerfile` CMD |
| `SENTINEL_CDN_BASE` | 空 → 落盘 `file://` | Sentinel 影像 CDN 前缀，`ai-service/.env.example:28` |
| `SENTINEL_STORAGE_DIR` | `ai-service/outputs/sentinel` | Sentinel 输出目录（`sentinel.py:_sentinel_storage_dir`） |
| `SENTINEL_ALLOWED_HOSTS` | `localhost,127.0.0.1,minio,minio:9000,host.docker.internal` | **本次新增**：`/sentinel/diff` 直传 URL 的主机白名单（见 `sentinel.py:88-136`）；配外部图源时须扩展 |
| `DETECT_IMAGE_ALLOWED_HOSTS` | 同上 | `/detect-image` 的 SSRF 白名单（`ai-service/app/routers/detection.py:143-156`） |
| `COPERNICUS_CLIENT_ID` / `COPERNICUS_CLIENT_SECRET` | 空 | CDSE OAuth；空则降级公开 STAC（`frontend/.env.example:156-171`） |
| `AUTO_MOCK` / 模型目录 | — | 模型缺失时返回 mock 结果（各路由 `mock:true`），见 `docs/delivery/KNOWN_LIMITATIONS.md` |

---

## 3. 一键启动

### 3.1 本地开发（Windows，推荐）

```powershell
# 首次：确保 .env 存在（脚本会从 .env.example 复制并自动生成 JWT_SECRET）
.\start-dev.ps1 -Infra -Up        # 启动 Docker 基础设施（PostgreSQL/Redis/MinIO/EMQX/TDengine）
.\start-dev.ps1 -All -Wait        # 后端 8080 + 前端 5173 + AI 8001，等就绪后报告
.\start-dev.ps1 -Status           # 健康度速览（端口监听 + 端到端探活）
.\start-dev.ps1 -Doctor           # 依赖/版本诊断
.\start-dev.ps1 -Stop             # 停止全部本地服务
```

证据：`start-dev.ps1:3-21`（用法与端口）、`start-dev.ps1:449`（释放 8001）、`start-dev.ps1:504-505`（预清理 8001/8080/5173）。

Linux/macOS 对应脚本：`start-dev.sh`（同仓库根目录）。

### 3.2 Docker Compose

```bash
cp .env.example .env            # 生产/演示前先改 JWT_SECRET、各类密码、FRONTEND_ORIGIN
docker compose up -d --build
docker compose ps               # 期望 postgres/redis/minio/emqx/backend/ai-service/frontend 均为 healthy
```

证据：`docker-compose.yml:2-265`，其中 backend healthcheck 用 `/api/system/health`（`:198`），frontend 用 `/healthz`（`:245`）。
生产编排 `docker-compose.prod.yml` 需 `EMQX_NODE_COOKIE` 等强制变量（根 `.env.example:122-125`）。

### 3.3 仅启动单个服务

```powershell
.\start-dev.ps1 -Backend      # 仅后端（前台）
.\start-dev.ps1 -Frontend     # 仅前端（前台）
.\start-dev.ps1 -Ai           # 仅 AI 微服务（前台，8001）
```

---

## 4. 健康检查 URL

| 服务 | URL | 期望 |
|---|---|---|
| 后端（关键依赖） | `GET http://localhost:8080/api/system/health` | 200，`status=UP`；PostgreSQL 不可用时 503 `DEGRADED`（`backend/.../health/HealthController.java:38`） |
| 后端（详细） | `GET http://localhost:8080/api/system/health?detailed=true` | 追加 JVM 堆/线程/TDengine 状态 |
| 后端 AI 网关探活 | `GET http://localhost:8080/api/ai/health` | 需 `ai:insight:view` 权限；`UP`/`DEGRADED`/`DISABLED`，恒 200（`AiController.java:618-639`） |
| AI 服务 | `GET http://localhost:8001/api/ai/health` | `{"status":"UP","unregistered_routers":{}}`（`ai-service/app/main.py:225-233`） |
| AI 服务文档 | `http://localhost:8001/docs` | FastAPI Swagger |
| 前端（nginx） | `GET http://localhost:5173/`、`/healthz` | 200；`/healthz` 为 nginx 固定返回（`infra/nginx/frontend.conf:84-88`） |
| WebSocket | `ws://localhost:8080/ws`（经前端代理 `/ws`） | 101 Switching Protocols |

> 健康检查通过不等于业务可用：AI 模型缺失时 `/api/ai/health` 仍为 `UP`，但各推理接口可能返回 `mock:true`（见限制清单）。

---

## 5. Nginx 反向代理要求（生产必读）

配置文件：`infra/nginx/frontend.conf`（前端镜像默认挂载）。

1. **API 代理**：`location /api/` → `http://backend:8080/api/`（`:48-59`）。
2. **WebSocket 代理**：`location /ws` → `http://backend:8080/ws`，必须带：
   ```nginx
   proxy_http_version 1.1;
   proxy_set_header Upgrade $http_upgrade;
   proxy_set_header Connection "upgrade";
   proxy_read_timeout 86400;
   proxy_send_timeout 86400;
   ```
   证据：`infra/nginx/frontend.conf:62-70`。**漏配 Upgrade/Connection 会导致 WS 握手 400/101 失败，前端退化为轮询。**
3. **AI 直连代理**：`location /ai-service/api/ai/` → `http://ai-service:8001/api/ai/`（`:34-44`），用于多模态/漂移等前端直连路径；该代理不构成鉴权，ai-service 目前无 JWT 校验（`:32-33`，见限制清单）。
4. **SPA fallback**：`try_files $uri $uri/ /index.html`（`:73-82`），否则刷新子路由 404。
5. **HTTPS**：生产建议启用 TLS；`wss://` 需在证书与代理层同时放通（本地未提供 TLS 模板，「待确认」）。
6. `VITE_WS_URL=/ws`（容器构建注入，`docker-compose.yml:214-217`）时浏览器会解析为同源绝对地址，依赖上述代理；`frontend/src/utils/wsUrl.js:4-6` 说明了该约束。

---

## 6. 常见故障排查

| 现象 | 首要检查 | 处理 |
|---|---|---|
| 端口冲突（5173/8080/8001） | `.\start-dev.ps1 -Status`；`netstat -ano \| findstr :8001` | `.\start-dev.ps1 -Stop`；或 `scripts/fix/free-8001.bat` 释放 8001；确认无残留 uvicorn/java/node 进程 |
| Docker 未启动 / 基础设施不健康 | `docker compose ps` | 启动 Docker Desktop；`.\start-dev.ps1 -Infra -Up`；查看 `docker compose logs postgres` |
| AI 能力全部 404/连接拒绝 | `frontend/.env.example` 与后端 `AI_SERVICE_BASE_URL` 是否为 8001；`curl http://localhost:8001/api/ai/health` | 确认 ai-service 已起于 8001（`start-dev.ps1:449,488-490`）；检查 `AI_ENABLED`；容器内用 `http://ai-service:8001/api/ai` |
| WebSocket 101 失败 / 实时数据不动 | 浏览器 Network 中 `/ws` 状态；nginx Upgrade/Connection；`FRONTEND_ORIGIN` | 补 nginx 头（见 §5）；确认后端 `/ws` permitAll（`SecurityConfig.java:55`）；前端会自动降级轮询（`realtimeService.js:650-657`） |
| CORS 报错 | 后端 `FRONTEND_ORIGIN`；ai-service `AI_CORS_ORIGINS` | 改为真实前端 Origin，禁止 `*`；容器内 localhost 不通时改用服务名 |
| 后端起不来，日志提示弱密钥 | `JWT_SECRET` 是否 `CHANGE_ME*` 占位 | 用 `openssl rand -base64 48` 生成；`start-dev.ps1` 首次会自动生成并写回 `.env` |
| 健康检查 503 | PostgreSQL 是否 healthy；`POSTGRES_*` 是否与 compose 映射端口一致（15432） | 修 `.env` 后重启后端；看 `docker compose logs postgres backend` |
| 会签确认/驳回返回 403 | 请求体 `signerId` 是否属于当前登录用户 | 属预期安全行为；**管理员代签已按 A6-1 决策开启**：`sign:proxy` 仅授予 admin（其余 6 角色仍 403），每次代签写 warn 审计日志；见限制清单 L-12 / SM-15 |
| 页面刷新 404（生产） | nginx SPA fallback | 补 `try_files ... /index.html`（`:73-75`） |

---

## 7. 交付前部署核对（勾选）

- [ ] `.env` 非 `.env.example`，JWT/数据库/Redis/MinIO/EMQX 密码全部替换为强随机值
- [ ] `FRONTEND_ORIGIN`、`AI_CORS_ORIGINS` 为真实域名，无 `*`
- [ ] AI 端口 8001 在前端/后端/编排三处一致
- [x] nginx `/ws` 与 `/api/` 代理配置经 HTTP 实测（`/api/system/health` 200、`/ws?ticket=` 101；见 §9，非浏览器）
- [x] 空数据卷初始化路径、历史库补丁幂等路径均实跑通过（见 §9）
- [ ] 7 个容器 healthy；`/api/system/health`、`/api/ai/health`、`/api/ai/health`(8001) 全通
- [ ] 前端 `VITE_USE_BACKEND=true`，页面左下角无「演示模式」角标（`frontend/src/App.vue:14-44`）
- [ ] 冒烟测试按 `docs/delivery/SMOKE_TEST.md` 全量执行并留痕

## 8. 既有数据库升级说明（2026-11-12）

本轮修复了 `event_sign.sign_id` 被误加 UNIQUE 约束导致"多人会签插入第二行 500"的问题。三种处理方式任选其一：

1. **自动修复（推荐）**：新版本后端启动时 `DatabaseSchemaPatchInitializer` 会自动检测并删除旧库上的该唯一约束、补建 `idx_event_sign_sign` 普通索引（幂等，可反复启动）。
2. **手工执行**：不重启后端时，由 DBA 执行
   `scripts/db/migrations/20261112_event_sign_drop_sign_id_unique.sql`。
3. **全新部署**：无需操作（`schema.sql` 已改为普通索引）。

验证方式（PostgreSQL）：

```sql
-- 应返回 0 行（sign_id 上不再有唯一约束）
SELECT con.conname FROM pg_constraint con
JOIN pg_class rel ON rel.oid = con.conrelid
JOIN pg_attribute att ON att.attrelid = rel.oid AND att.attnum = ANY (con.conkey)
WHERE rel.relname = 'event_sign' AND con.contype = 'u' AND att.attname = 'sign_id';

-- 应存在 idx_event_sign_sign
SELECT indexname FROM pg_indexes WHERE tablename = 'event_sign' AND indexname = 'idx_event_sign_sign';
```

同时：本轮在前端权限源补齐 `tasks:*` / `backup:*` / `plans:*` / `reports:*` 后重新生成了
`PermissionRegistry`。如果后续再改动权限矩阵，请务必以前端
`frontend/src/stores/auth.js` 的 `ROLE_PERMISSION_MAP` 为唯一源，运行
`node scripts/fix/sync-permission-registry.mjs` 重新生成，避免"只改后端、下次生成被覆盖"。

---

## 9. 干净机器部署演练记录（2026-11-13，A3）

### 9.1 隔离方式（不触碰现有开发环境）

演练编排 `scripts/delivery/compose.delivery-verify.yml`：project name `dg-delivery-verify`，
独立命名卷与独立宿主机端口，与开发用的 15432 / 16379 / 19000 / 5173 完全隔离。

| 组件 | 演练端口 | 说明 |
|---|---|---|
| PostgreSQL / Redis / MinIO | 25432 / 26379 / 29000+29001 | 全新命名卷，`up -d --wait` 等 healthy |
| 后端 jar | 28080（空库首启）、28081/28082（补丁/幂等） | `java -jar`，演练期间 `AI_ENABLED=false`、`EMQX_ENABLED=false` |
| nginx（profile `frontend`） | 25173 | 挂载 `frontend/dist` + 演练版 `frontend.conf` |

复现命令（仓库根目录）：

```bash
docker compose -f scripts/delivery/compose.delivery-verify.yml up -d --wait
# 后端环境变量见 §2.2；POSTGRES_PORT=25432、REDIS_PORT=26379、SERVER_PORT=28080
java -jar backend/target/disasterguard-backend-0.0.1-SNAPSHOT.jar

# A3-3：生成 nginx 演练配置（只替换 upstream 为宿主机，其余与生产 conf 一致）
mkdir -p output/delivery-verify/nginx
sed -e 's|http://backend:8080|http://host.docker.internal:28080|g' \
    -e 's|http://ai-service:8001|http://host.docker.internal:28081|g' \
    infra/nginx/frontend.conf > output/delivery-verify/nginx/frontend.conf
docker compose -f scripts/delivery/compose.delivery-verify.yml --profile frontend up -d nginx
# 清理：docker compose -f scripts/delivery/compose.delivery-verify.yml --profile frontend down -v
```

> 生产配置里的 `backend` / `ai-service` 是 compose 服务名；宿主机直跑 jar 时用
> `host.docker.internal` 替换（只改演练配置，不改 `infra/nginx/frontend.conf`）。

### 9.2 演练发现并修复的 4 个「全新部署」P0 阻断

| # | 现象 | 根因 | 修复 |
|---|---|---|---|
| 1 | 空卷 `compose up` 后 postgres `Exited(3)` | `initdb.d` 执行 `002_geom_triggers.sql` 时业务表尚未创建（`relation "hazard_point" does not exist`）；`004_insert_demo_rules.sql` 同理 | `docker-compose.yml` 改为**只挂载 001/003** 两个不依赖业务表的脚本；002/004 保留并加醒目说明（geom 由应用层维护、演示规则已由 `data.sql` R001~R006 提供） |
| 2 | 后端首启失败：`data.sql` 语句 #21 违反 `emergency_event_warning_code_fkey` | 预警种子已停用，空库无 `WG-001`，应急响应种子却写死 `warning_code='WG-001'` | 改为子查询取 `warning_code`，老库保持关联、空库降级 NULL |
| 3 | 规则缓存 0 条，`WarningRuleSyncService` 6 条 INSERT 全失败 | 镜像规则 `created_by='SYSTEM'`，`sys_user` 无该账号，违反 `fk_rule_created_by` | 系统同步的规则无人工创建者，置 NULL |
| 4 | 规则入库后 6 条「解析规则失败」 | `rule.actions` 写成对象，`RuleEngineService` 按 `List<RuleAction>` 反序列化 | 改为数组 `[{"type":"CREATE_EVENT","params":{}}]` |

修复后空库首启日志 ERROR 为 0，`rule` 表 6 条，且 `WarningCreateService` 按真实指标
自动生成预警（日志 `warningCode=WG-*`），规则引擎链路确实可用。

### 9.3 验收结果（A3-1 ~ A3-4）

| 项 | 结果 | 证据 |
|---|---|---|
| A3-1 空库初始化 | ✅ 三容器 healthy；后端 17.9s 启动并自动建表/种子；健康 UP；admin 登录 200；`/api/warnings`、`/api/devices`、`/api/rules`、`/api/settings`、`/api/hazards`、`/api/dashboard/overview` 全 200 | `output/delivery-verify/backend-fresh.log` |
| A3-2 历史库补丁 | ✅ 人为加回 `event_sign.sign_id` UNIQUE、删除 `system_setting` 后重启：唯一约束 0、表存在、`idx_event_sign_sign` 存在、0 ERROR；再启动一次状态不变 | `backend-patch-1.log` / `backend-patch-2.log` |
| A3-3 生产构建 + nginx | ✅ `mvn package` + `npm run build`；nginx `/` 200、`/healthz` 200、`/api/system/health` 200；`GET /api/auth/ws-ticket` → `/ws?ticket=` **101 Switching Protocols** | nginx 容器访问日志 |
| A3-4 手册复核 | ✅ 头部说明、§7 勾选、本节按实跑结果校正 | 本文件 |

**WS 验证注意**：本机 `HTTP_PROXY` 会让 curl 走代理并剥掉 `Upgrade` 头，后端报
`invalid Upgrade header: null`、握手 400。必须加 `--noproxy '*'`（或用真实浏览器）才能拿到 101；
这是验证环境问题，不是 nginx / 后端缺陷。

### 9.4 演练范围边界（诚实说明）

- 演练关闭了 AI（8001）与 EMQX；HTTPS、域名、CSP、真实密钥未纳入，属 A4 生产配置，仍待现场执行。
- nginx 反代用 HTTP 请求实测，未跑真实浏览器页面；页面级 37 项浏览器冒烟结果另见 `SMOKE_TEST.md`。
- 演练用临时命名卷，结束后 `down -v` 清理，不影响开发库。

---

## 10. 时相对比（B1）部署与配置（2026-11-13 新增）

### 10.1 链路与端口

```
浏览器 → /api/ai/sentinel/**（同源，带 JWT）
       → nginx location /api/ → backend:8080
       → Java 网关 SentinelCompareController（satellite:view 权限）
       → ai-service:8001 /api/ai/sentinel/*（仅内网，不暴露公网）
图片   → /api/ai/sentinel/image/{filename} → 后端代理 ai-service /sentinel_files/{filename}
```

- **不要**给该页面复用 nginx 的 `/ai-service/api/ai/` 前缀：那条链路无 JWT 校验（见 C5），
  且合成图 URL 是 `http://localhost:8001/sentinel_files/...` 或 `file://...`，生产不可达（C3）。
  新页面整条链路走 `/api/`，nginx 无需新增 location。
- 前端**不用** `<img src>` 直接取图：浏览器不会自动带 Authorization 头，会被 401。
  页面用带 JWT 的 `fetch` 取 blob 再转 object URL（`fetchImageObjectUrl`），CSP 允许 `blob:`。

### 10.2 环境变量

| 变量 | 服务 | 说明 |
|---|---|---|
| `AI_SERVICE_BASE_URL` | backend | 默认 `http://localhost:8001/api/ai`；prod compose 注入 `http://ai-service:8001/api/ai`。网关同时用它推导图片 origin |
| `SENTINEL_CDN_BASE` | ai-service | 合成图 URL 前缀；未设置时 main.py 设 `http://localhost:8001/sentinel_files`（网关会重写成同源地址） |
| `SENTINEL_STORAGE_DIR` | ai-service | 合成图落盘目录，需持久化或挂 volume，否则重启后历史任务图片 404 |
| `SENTINEL_ALLOWED_HOSTS` | ai-service | diff 直传图片 URL 的主机白名单，默认仅本机/MinIO |
| CDSE / GEE 凭证 | ai-service | 真实卫星影像必需，配置见 `docs/CDSE_SETUP.md` |

### 10.3 权限与降级口径

- `satellite:view` 已在权限种子与运行时注册表注册，授予 admin / attendant / emergency_commander；
  其余角色菜单不可见、接口被 `@PreAuthorize` 拒绝。改角色权限矩阵即可调整，无需改代码。
- **没有真实影像凭证时页面只显示降级原因**（`dataSource=mock`），不提交差异任务、不展示演示噪声图或假热力图。
  这是刻意边界，不能通过"强制 mock 模式出图"绕过。

### 10.4 部署后自检

```bash
# ① ai-service 端点链路（把地址换成实际 ai-service）
SENTINEL_VERIFY_BASE=http://127.0.0.1:8001/api/ai \
  node scripts/delivery/verify-sentinel-chain.mjs

# ② Java 网关（需 ai-service 可达；不可达时自动跳过）
cd backend && mvn -q "-Dtest=SentinelCompareControllerTest" test

# ③ 前端纯函数
cd frontend && npx vitest run tests/satelliteCompare.spec.js

# ④ 真实浏览器页面（需 5173 / 8080 / Edge CDP 9222）
node scripts/delivery/verify-satellite-page.mjs
```

浏览器现场清单见 `SMOKE_TEST.md` 的 SM-19 小节与 `ONSITE_ACCEPTANCE_CHECKLIST.md`。
④ 在 2026-11-13 实跑 10/10 PASS（旧 dev 后端，验证错误路径）；随后已补做**生产形态端到端**：
`verify-sentinel-e2e.mjs` 14/14 + `verify-sentinel-browser-e2e.mjs` 15/15 PASS
（nginx dist → 新网关 → ai-service），详见 `ACCEPTANCE_LOG` §三十三。

> **演练环境两个易踩点**（非产品缺陷）：
> 1. 后端 `CorsConfig` / `WebSocketConfig` 白名单只含 `http://localhost:5173`，而演练 nginx 的
>    浏览器 Origin 是 `http://127.0.0.1:25173`，不改写会被 `Invalid CORS request` 403、
>    `/ws` 也握不上手；Vite dev proxy 已用 `configure` 改写 Origin，演练 nginx 需同样补
>    `proxy_set_header Origin http://localhost:5173;`。生产按真实域名配置白名单。
> 2. 生产 nginx 的 CSP 需允许 Cesium 的 eval / WASM（见 `PRODUCTION_HARDENING.md` §五），
>    否则三维地图起不来并产生未捕获异常。
