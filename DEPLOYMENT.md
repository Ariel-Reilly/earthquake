# 部署与环境变量手册 · DisasterGuard

> 适用版本：Spring Boot 3.4.1 / Java 17 / PostgreSQL 15 + PostGIS
>
> 本文档的变量清单来自三处真实来源，已交叉核对：
> `backend/src/main/resources/application.yml`、`docker-compose.yml`、`docker-compose.prod.yml`。

---

## 1. 配置加载机制（先读这一节）

平台的配置分三层，**理解这三层才能明白"为什么本地能跑、生产必须配"**：

| 层 | 文件 | 作用 |
|---|---|---|
| 基础层 | `application.yml` | 所有敏感项写成 `${环境变量:本地开发默认值}`。默认值面向"宿主机直跑" |
| 容器层 | `application-docker.yml` | `SPRING_PROFILES_ACTIVE=docker` 时加载，把主机名换成 `postgres` / `redis` / `minio` |
| 生产层 | `application-prod.yml` | `SPRING_PROFILES_ACTIVE=prod` 时加载，强制安全开关 + 关闭自动建表 |

两个关键差异：

1. **Docker Compose 会自动读取根目录 `.env`**，Spring Boot **不会**。
   - 走 compose：把值写进 `.env` 即可。
   - 走 `mvn spring-boot:run`：值必须进进程环境（`start-dev.ps1` 会代劳，IDE 需在 Run Configuration 里配）。
2. **`application-prod.yml` 把 `spring.sql.init.mode` 设为 `never`**。
   生产环境**不会自动建表**，首次部署必须手工执行一次 `schema.sql` + `data.sql`。

---

## 2. 变量总表

图例：**必填** = 缺失会导致启动失败或功能不可用；**默认** = `application.yml` / compose 中的兜底值。

### 2.1 运行模式与通用

| 变量 | 必填 | 默认 | 用途 |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | 生产必填 | 无（dev） | `docker` 或 `prod`。prod 会启用 `ProductionConfigValidator` 强校验 |
| `SERVER_PORT` | 否 | `8080` | 后端监听端口 |
| `BACKEND_HOST_PORT` | 否 | `127.0.0.1:8080`（prod `8081`） | 宿主机映射端口 |
| `FRONTEND_HOST_PORT` | 否 | `127.0.0.1:5173` | 前端宿主机端口 |
| `FRONTEND_ORIGIN` | 生产必填 | `http://localhost:5173` | CORS 白名单 + WebSocket Origin 校验。**prod 下含 localhost 会被拒绝启动** |
| `FILE_BASE_URL` | 否（仅宿主机直跑） | `http://localhost:19000` | 宿主机直跑时的附件访问基地址。**prod compose 不再读取此变量**，避免开发用的 localhost 被注入容器 |
| `MINIO_PUBLIC_URL` | 生产必填 | `http://minio:9000`（仅容器间可达） | prod 下 `FILE_BASE_URL` 的实际取值。必须设为浏览器可达的公网地址（如 `https://minio.example.com`），并把 `MINIO_ENDPOINT_PROD` 设为同一地址、把该域名加入 `DETECT_IMAGE_ALLOWED_HOSTS`，否则「能上传、打不开附件」 |
| `TZ` | 否 | `Asia/Shanghai` | 容器时区 |

### 2.2 PostgreSQL / PostGIS

| 变量 | 必填 | 默认 | 用途 |
|---|---|---|---|
| `POSTGRES_HOST` | 否 | `127.0.0.1`（容器内 `postgres`） | 数据库主机 |
| `POSTGRES_PORT` | 否 | `15432`（容器内 `5432`） | 数据库端口 |
| `POSTGRES_DB` | 否 | `disasterguard` | 库名 |
| `POSTGRES_USER` | 否 | `disasterguard` | 用户名 |
| `POSTGRES_PASSWORD` | **是** | `CHANGE_ME__dev_postgres_password_2026` | 口令。**prod 下命中默认值会阻断启动** |
| `POSTGRES_HOST_PORT` | 否 | `127.0.0.1:15432` | 宿主机端口映射 |

### 2.3 Redis

| 变量 | 必填 | 默认 | 用途 |
|---|---|---|---|
| `REDIS_HOST` | 否 | `127.0.0.1`（容器内 `redis`） | 缓存主机 |
| `REDIS_PORT` | 否 | `16379`（容器内 `6379`） | 端口 |
| `REDIS_PASSWORD` | **是** | `CHANGE_ME__dev_redis_password_2026` | 口令（长度 ≥ 16）。为空时 prod 仅告警不阻断 |
| `REDIS_HOST_PORT` | 否 | `127.0.0.1:16379` | 宿主机端口映射 |

> Redis 的 `requirepass`、healthcheck、后端密码三处必须引用**同一个**变量，否则健康检查永久 `NOAUTH` 失败，导致 backend 的 `depends_on: service_healthy` 永久阻塞。

### 2.4 MinIO 对象存储

| 变量 | 必填 | 默认 | 用途 |
|---|---|---|---|
| `MINIO_ROOT_USER` | 否 | `disasterguard` | 服务端账号，同时作为后端 `minio.access-key` |
| `MINIO_ROOT_PASSWORD` | **是** | `CHANGE_ME__dev_minio_password_24chars_minimum_2026` | 服务端密钥（长度 ≥ 24），同时作为后端 `minio.secret-key` |
| `MINIO_ACCESS_KEY` | 否 | 回落到 `MINIO_ROOT_USER` | 后端专用别名，优先级更高 |
| `MINIO_SECRET_KEY` | 否 | 回落到 `MINIO_ROOT_PASSWORD` | 后端专用别名，优先级更高 |
| `MINIO_ENDPOINT` | **生产必填** | `http://localhost:19000`（容器内 `http://minio:9000`） | 后端连接地址**且**用于生成附件预签名 URL，必须是浏览器可达地址。**prod 下指向本机会被拒绝启动** |
| `MINIO_BUCKET` | 否 | `disasterguard` | 存储桶名，不存在时自动创建 |
| `MINIO_API_HOST_PORT` | 否 | `127.0.0.1:19000` | API 端口映射 |
| `MINIO_CONSOLE_HOST_PORT` | 否 | `127.0.0.1:19001` | 控制台端口映射 |

> ⚠️ 后端的 `MINIO_*` 与服务端的 `MINIO_ROOT_*` 必须同源，否则连接会被 403 拒绝。

### 2.5 密钥与安全开关

| 变量 | 必填 | 默认 | 用途 |
|---|---|---|---|
| `JWT_SECRET` | **是** | `this_is_a_very_secure_secret_key_for_disaster_guard_2026_!@#` | JWT 签名密钥，长度 ≥ 32。**prod 下命中该默认值会阻断启动**。生成：`openssl rand -base64 48` |
| `JWT_EXPIRE` | 否 | `PT2H` | ISO-8601 Duration 格式的令牌有效期 |
| `JWT_ISSUER` | 否 | `disasterguard` | 签发者标识 |
| `DATA_ENCRYPTION_KEY` | **是（无默认值）** | 无，`docker-compose.prod.yml:144` 用 `${VAR:?}` 强制校验 | AES-256-GCM 密钥，加密注册申请中的身份证号。**缺失时 compose 解析阶段即失败**；**变更后历史密文永久不可解**。生成：`openssl rand -base64 32` |
| `DEV_USERS_ENABLED` | 生产必填 | `true` | 是否启用硬编码开发账号。**prod 下必须为 `false`** |
| `DEVICE_AUTH_STRICT` | 生产必填 | `false` | 设备上报是否强制鉴权。**prod 下必须为 `true`** |
| `WS_REQUIRE_TICKET` | 生产必填 | `false` | 是否禁止 `?token=` 直连，强制一次性 ticket。**prod 下必须为 `true`** |
| `REGISTER_SMS_ENABLED` | 否 | `false` | 注册短信验证码开关。**prod 下必须为 `false`**（网关未接入） |
| `REGISTER_EMAIL_ENABLED` | 否 | `false` | 注册邮件验证码开关。**prod 下必须为 `false`** |

### 2.6 阿里云号码认证（短信验证码）

| 变量 | 必填 | 默认 | 用途 |
|---|---|---|---|
| `ALIYUN_SMS_ENABLED` | 否 | `true` | 是否启用短信验证码登录 |
| `ALIYUN_SMS_ACCESS_KEY_ID` | 启用短信时必填 | `CHANGE_ME` | RAM AccessKey ID。**默认值不会导致启动失败，但发送必然失败** |
| `ALIYUN_SMS_ACCESS_KEY_SECRET` | 启用短信时必填 | `CHANGE_ME` | RAM AccessKey Secret |
| `ALIYUN_SMS_SIGN_NAME` | 否 | `恒创联众` | 短信签名 |
| `ALIYUN_SMS_TEMPLATE_CODE` | 否 | `100001` | 模板编号 |
| `ALIYUN_SMS_ENDPOINT` | 否 | `dypnsapi.aliyuncs.com` | 服务端点 |

> 不使用短信登录时，建议显式设置 `ALIYUN_SMS_ENABLED=false`，避免启动日志出现凭证未注入告警。

### 2.7 EMQX（MQTT 设备接入）

| 变量 | 必填 | 默认 | 用途 |
|---|---|---|---|
| `EMQX_ENABLED` | 否 | `false`（compose 中 `true`） | 是否启用 MQTT 接入 |
| `EMQX_MQTT_URL` | 否 | `tcp://localhost:11883`（容器内 `tcp://emqx:1883`） | Broker 地址 |
| `EMQX_CLIENT_ID` | 否 | `disasterguard-backend` | 客户端标识 |
| `EMQX_DATA_TOPIC` | 否 | `disasterguard/+/data` | 订阅主题 |
| `EMQX_DATA_QOS` | 否 | `1` | QoS 等级 |
| `EMQX_USERNAME` | 启用时必填 | 空 | 后端连接 broker 的用户名 |
| `EMQX_PASSWORD` | 启用时必填 | 空 | 后端连接 broker 的密码（长度 ≥ 16） |
| `EMQX_DASHBOARD_USER` / `EMQX_DASHBOARD_PASSWORD` | **prod 必填** | `admin` / 无（prod 用 `:?` 强制） | 管理台登录凭据。dev compose 仍有 `CHANGE_ME_*` 兜底，prod 已去掉 |
| `EMQX_NODE_COOKIE` | **prod 必填** | `disasterguard-cookie`（仅 dev） | 集群 Cookie。prod 缺失时 compose 直接拒绝解析；生成：`openssl rand -hex 32` |
| `EMQX_MQTT_HOST_PORT` / `EMQX_WS_HOST_PORT` / `EMQX_HTTP_HOST_PORT` | 否 | `11883` / `18083` / `28083` | 端口映射 |

> broker 侧（`infra/emqx/init.sh`）与后端必须使用**同一组** `EMQX_USERNAME` / `EMQX_PASSWORD`，否则 MQTT 鉴权必然失败。

### 2.8 TDengine（可选时序库）

| 变量 | 必填 | 默认 | 用途 |
|---|---|---|---|
| `TDENGINE_ENABLED` | 否 | `false` | 是否启用 |
| `TDENGINE_URL` | 否 | `jdbc:TAOS-RS://localhost:16041/disasterguard` | JDBC REST 地址 |
| `TDENGINE_USERNAME` / `TDENGINE_PASSWORD` | 否 | `root` / `taosdata` | 凭据 |
| `TDENGINE_TCP_HOST_PORT` / `TDENGINE_HTTP_HOST_PORT` | 否 | `16030` / `16041` | 端口映射 |

### 2.9 AI 服务

| 变量 | 必填 | 默认 | 用途 |
|---|---|---|---|
| `AI_ENABLED` | 否 | `true` | 是否启用 AI 分析能力 |
| `AI_SERVICE_BASE_URL` | 否 | `http://localhost:8001/api/ai`（容器内 `http://ai-service:8001/api/ai`） | Python 服务基地址 |
| `AI_IMAGE_SERVICE_URL` | 否 | `http://127.0.0.1:8001/api/ai/detect-image` | **图像识别专用地址**，可单独指向 GPU 实例 |
| `AI_TIMEOUT_MS` | 否 | `30000` | 常规分析超时 |
| `AI_SCHEDULER_ENABLED` | 否 | `false` | 是否启用定时综合研判 |
| `AI_FORECAST_HORIZON` | 否 | `7` | 预测天数 |
| `AI_FORECAST_CRON` | 否 | `0 0 2 * * *` | 预测任务调度 |
| `AI_HAZARD_MONITOR_ENABLED` | 否 | `true` | AI 实时灾害点监测开关 |
| `AI_HAZARD_MONITOR_INTERVAL_MS` | 否 | `90000` | 监测周期 |
| `AI_HAZARD_MONITOR_MIN_RISK_LEVEL` | 否 | `HIGH` | 入预警的最低风险等级 |
| `AI_HAZARD_MONITOR_MIN_LEVEL` | 否 | `town` | AI 监测覆盖的最小行政区级别（`town` / `village` / `district`） |
| `AI_HAZARD_MONITOR_MAX_REGIONS` | 否 | `200` | 单轮监测的行政区上限，防止一次扫描过重 |
| `AI_CORS_ORIGINS` | 否 | `http://localhost:5173,http://localhost:8080` | Python 服务 CORS 白名单 |
| `AI_HOST_PORT` | 否 | `127.0.0.1:8001` | 端口映射 |

Python 侧另有独立变量（`ai-service/.env`）：`DETECT_TIMEOUT_SECONDS`（默认 30）、`DETECT_IMAGE_TIMEOUT_SECONDS`（默认 10）、`DETECT_IMAGE_ALLOWED_HOSTS`（下载白名单，防 SSRF）。

### 2.10 外部数据源

| 变量 | 必填 | 默认 | 用途 |
|---|---|---|---|
| `EXTERNAL_DATA_ENABLED` | 否 | `true` | 是否启用外部数据融合 |
| `EXTERNAL_DATA_TIMEOUT` | 否 | `10` | 请求超时（秒） |
| `EXTERNAL_DATA_CACHE_TTL` | 否 | `300` | 缓存秒数 |
| `EXTERNAL_DATA_SOURCES` | 否 | `openmeteo,usgs,openelevation,gdacs,osm,gpm,smap,copernicus` | 启用的数据源 |
| `OPEN_METEO_URL` | 否 | Open-Meteo 官方地址 | 气象预报（24h 降雨） |
| `USGS_FDSN_URL` | 否 | USGS 官方地址 | 地震事件 |
| `OPEN_ELEVATION_URL` | 否 | Open-Elevation 官方地址 | 高程查询 |
| `WEATHER_CACHE_ENABLED` / `WEATHER_CACHE_TTL_SECONDS` / `WEATHER_PROXY_ENABLED` | 否 | `true` / `300` / `true` | 气象卫星产品缓存与代理 |
| `COPERNICUS_CLIENT_ID` / `COPERNICUS_CLIENT_SECRET` | 否 | 空 | CDSE OAuth（留空自动降级到公开 STAC） |

### 2.11 前端构建变量（Vite，构建期注入）

| 变量 | 必填 | 默认 | 用途 |
|---|---|---|---|
| `VITE_USE_BACKEND` | 否 | `true` | 是否走真实后端（`false` 用 mock） |
| `VITE_API_BASE_URL` | 否 | `/api` | API 前缀 |
| `VITE_WS_URL` | 否 | `/ws` | WebSocket 路径 |
| `VITE_REALTIME_POLL_INTERVAL` / `VITE_WARNING_POLL_INTERVAL` / `VITE_PERSONNEL_POLL_INTERVAL` | 否 | `15000` / `30000` / `30000` | 轮询兜底间隔（毫秒） |
| `VITE_TDT_TOKEN` | 否 | 空 | 天地图密钥（底图） |
| `VITE_AMAP_TOKEN` / `VITE_AMAP_SECURITY_JS_CODE` | 否 | 空 | 高德地图密钥 |

> Vite 变量是**构建期**注入，改了必须重新 `npm run build`，运行时改环境变量无效。

---

## 3. 开发环境部署

```bash
cp .env.example .env       # 首次
docker compose up -d       # 拉起全部容器
```

compose 会按 `depends_on: service_healthy` 串行等待：postgres / redis / minio 健康后再启动 backend，backend 健康后再启动 frontend。

> **建表时机**：`infra/postgres/init/` 只创建 PostGIS 扩展、几何触发器、审计归档表与演示规则；
> **业务表与种子数据由后端首次启动时创建**（开发配置 `spring.sql.init.mode: always`，
> 执行 `classpath:schema.sql` + `data.sql`）。因此基础设施起来后必须先启动一次后端，再访问前端。

**宿主机直跑**（推荐日常开发，热重载更快）：

```powershell
.\start-dev.ps1 -Infra -Up     # 只起基础设施
.\start-dev.ps1 -Backend       # 后端（自动加载 .env）
.\start-dev.ps1 -Ai            # Python AI
.\start-dev.ps1 -Frontend      # 前端
.\start-dev.ps1 -Status        # 健康速览
```

**健康检查**：

```bash
curl http://localhost:8080/api/system/health
curl http://localhost:8001/api/ai/detect/status
docker compose ps
```

---

## 4. 生产环境部署

### 4.1 必填变量（compose 用 `:?` 强制校验，缺失时 compose 解析阶段即失败）

| 变量 | 缺失后果 |
|---|---|
| `POSTGRES_PASSWORD` | compose 拒绝解析 |
| `REDIS_PASSWORD` | compose 拒绝解析 |
| `MINIO_ROOT_PASSWORD` | compose 拒绝解析 |
| `JWT_SECRET` | compose 拒绝解析 |
| `DATA_ENCRYPTION_KEY` | compose 拒绝解析 |
| `EMQX_PASSWORD` | compose 拒绝解析 |
| `EMQX_DASHBOARD_PASSWORD` | compose 拒绝解析 |
| `EMQX_NODE_COOKIE` | compose 拒绝解析 |
| `MINIO_ENDPOINT` | **不参与 `:?` 校验**：prod 改用 `MINIO_ENDPOINT_PROD`（默认 `http://minio:9000`）。若需浏览器直接打开附件，必须把它改成对外可达域名，否则会被 `ProductionConfigValidator` 判定「指向本机」而阻断启动 |

此外 `SPRING_PROFILES_ACTIVE=prod` 下还会由 `ProductionConfigValidator` 阻断以下情况：

- `DEV_USERS_ENABLED=true` / `DEVICE_AUTH_STRICT=false` / `WS_REQUIRE_TICKET=false`
- `FRONTEND_ORIGIN` 含 `localhost` 或为 `*`
- `FILE_BASE_URL` 含 `localhost:9000`
- `EMQX_PASSWORD` 为空、命中弱口令或仍是任意未替换的 `CHANGE_ME*` 占位值
- `MINIO_ENDPOINT` 指向 `localhost` / `127.0.0.1` / `0.0.0.0`
- `JWT_SECRET` / `DATA_ENCRYPTION_KEY` / `MINIO_SECRET_KEY` / `POSTGRES_PASSWORD` 为空、过短或**仍为 `application.yml` 的开发默认值**
- 种子用户密码仍为 `admin123`

### 4.2 首次部署流程

```bash
# 1) 准备生产变量（不要提交到 Git）
cp .env.example .env
#    逐项填入强随机值，务必设置 SPRING_PROFILES_ACTIVE=prod

# 2) 初始化数据库（prod 不会自动建表！）
psql -h <host> -U disasterguard -d disasterguard -f backend/src/main/resources/schema.sql
psql -h <host> -U disasterguard -d disasterguard -f backend/src/main/resources/data.sql

# 3) 启动
# ⚠️ docker-compose.prod.yml 是**覆盖文件**（只写了需要改的字段，postgres/redis/minio
#    没有 image）。必须与基础文件一起加载，单独 -f 会被 compose 判为非法编排：
#    "service postgres has neither an image nor a build context specified"。
docker compose -f docker-compose.yml -f docker-compose.prod.yml up -d
```

### 4.3 已知部署缺口（务必人工确认）

1. **`emqx` 与 `ai-service` 已在 `docker-compose.prod.yml` 定义**（两个独立 service，后端走容器网 `tcp://emqx:1883` 与 `http://ai-service:8001/api/ai`）。旧版本节写的「缺服务定义」已作废，不要再据此关闭 `EMQX_ENABLED` / `AI_ENABLED`。仍需人工确认的是：
   - EMQX 目前为 **allow-all 匿名放行**，`EMQX_*`、`EMQX_DASHBOARD_PASSWORD` 仍是 `CHANGE_ME_*` 占位；正式发布前必须开启认证并注入真实凭据（同时同步 `hardware-sandbox/gateway/config.yaml` 的网关凭据）。
   - `ai-service` 镜像内必须带 `models/` 权重，否则图像/融合接口会返回 `mock: true`。
2. **表结构无版本化迁移**。目前依赖手工执行 `schema.sql`，改动结构时需人工比对，长期建议引入 Flyway / Liquibase。
3. `schema.sql` 内含 `DROP VIEW hazard_point` / `DROP TABLE hazard_point CASCADE` 的升级路径，**禁止**在已有数据的库上直接重复执行。

---

## 5. 故障排查

| 现象 | 可能原因 | 排查 |
|---|---|---|
| 后端启动即退出，日志含"prod 配置校验失败" | 生产变量缺失或仍为开发默认值 | 按日志逐项修复，不要跳过 |
| `NOAUTH Authentication required` | `REDIS_PASSWORD` 在 broker / healthcheck / 后端三处不一致 | 确认三处引用同一变量 |
| MinIO 403 | `MINIO_ROOT_USER/PASSWORD` 与后端 `minio.access-key/secret-key` 不一致 | 检查 `MINIO_ACCESS_KEY` 是否覆盖了 `MINIO_ROOT_USER` |
| 能上传但点开附件打不开 | `MINIO_ENDPOINT` 是内网服务名，预签名 URL 主机不可达 | 改为公开地址 |
| 短信验证码收不到 | `ALIYUN_SMS_ACCESS_KEY_ID` 仍为 `CHANGE_ME` | 注入真实 RAM 凭证，或关闭 `ALIYUN_SMS_ENABLED` |
| AI 识别返回 `mock: true` | Python 端模型文件缺失 | 运行 `ai-service/scripts/download_models.py` |
| 图像识别超时 | 图片过大或推理排队 | 检查 `DETECT_TIMEOUT_SECONDS`、单图 ≤ 10MB |
| 前端 401 | `JWT_SECRET` 变更导致旧令牌失效 | 重新登录 |
| 身份证号解密失败 | `DATA_ENCRYPTION_KEY` 被更换 | 恢复原密钥；该密钥不可轮换 |
| 通知回执全是 `BLOCKED` | `NOTIFICATION_TEST_RECIPIENTS` 未配置（fail-closed） | 按 §6.2 填入项目测试成员名单；这不是故障 |

---

## 6. 当前阶段使用边界（2026-11-06 决策，部署前必读）

### 6.1 不商用

项目当前定位为**非商业用途**：教学、竞赛演示、毕业设计、项目内部测试。因此：

- 保留 `ai-service/datasets/disaster-v2` 中 CAS Landslide Dataset（**CC-BY-NC-4.0**）的滑坡影像，
  现阶段**不换源、不重训**；`crack` 源上游未声明许可，同样只在非商业范围内使用。
- `ai-service/models/yolo_crack.onnx` 是上述数据的训练产物，**不得随商业交付分发**。
- 一旦用途改为商业部署 / 对外收费服务，必须先替换为可商用数据或取得作者授权、重新训练并重新评估，
  同时复核 crack 源许可。约束原文见 `DATASET_CARD.md` §6.5。

### 6.2 只投递给项目测试成员

现阶段任何对外投递（预警通知、渠道测试、模板测试、注册验证码）**只允许发给白名单内的项目测试成员**：

| 项 | 说明 |
|---|---|
| 配置项 | `NOTIFICATION_TEST_RECIPIENTS`（`.env` / 部署环境变量），逗号分隔 |
| 后端键 | `disasterguard.notification.test-recipients`，dev / docker / prod 均默认空 |
| 默认行为 | **留空 = 全部拦截（fail-closed）**，不会误发给外部人员 |
| 名单外结果 | 回执状态 `BLOCKED`，备注写明「未投递」，**不调用任何 Sender**，不写 `sentAt`，不记为 `SENT`；补齐名单后可通过「重试」补发 |
| 名单内结果 | 仍走现有 6 个演示 Sender（未接网关），回执状态 `MOCK_SENT`、备注「演示发送·未真实投递」，**不计入 `sent` 统计**（单独计 `mockSent`） |
| 无收件人探测 | `target` 为空时返回 `PROBE_ONLY`，**不调用任何 Sender**（`ScreenSender` 会向所有在线 WebSocket 客户端广播，空 target 不等于零副作用）。探测**只确认该渠道发送器已注册**：不查渠道配置、不连外部网关，因此不得宣称「渠道配置已校验 / 网关连通」；渠道未注册时如实返回 `NO_SENDER` |
| 站内广播渠道 | `SCREEN` / `SOUND_LIGHT` 没有定向收件人，即使 target 在白名单，可见范围仍是所有在线客户端；回执备注会写明，不能对外宣称「只发给了指定成员」 |
| 注册验证码 | 仅当短信真实网关已接入（`ALIYUN_SMS_ENABLED=true` 且 `access-key-id` 非 `CHANGE_ME`）时，验证码下发才受名单约束。**prod 且网关未接入时接口明确返回 50301**，不会「报成功但用户收不到」；非 prod 允许日志降级。邮箱 Sender 仍是演示实现，不产生对外投递；将来接入 SMTP 必须同步声明真实投递能力，否则 fail-closed 抛错 |
| 派发记录终态 | 全部成功 `SENT` / 全部演示 `MOCK_SENT` / 全部拦截 `BLOCKED` / 全部失败 `FAILED` / 混合 `PARTIAL` / 尚无渠道结果 `SENDING`。全失败不会再被误标成「发送中」 |

> **边界说明**：白名单是**准入闸门**，不是**投递能力**。它保证消息不会发给外部人员，
> 但**不提供真实送达**；真实送达需另行接入 SMTP / 短信 / APP 推送网关。
> 「名单内 = 已送达」是错误理解，回执与页面均以 `mock` 标记区分。

覆盖的三条链路：`NotificationRouter#sendToChannel`（预警派发）、
`NotificationService#testSend`（渠道页 / 模板页测试发送）、
`VerificationCodeService#send`（注册验证码真实下发）。
名单为空时，赛场演示里「通知派发」会留下 `BLOCKED` 回执——这是预期行为，不是故障；
需要演示「发给测试成员」时，先在 `.env` 填入实际名单再重启后端。
