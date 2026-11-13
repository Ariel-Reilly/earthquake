# 生产安全加固与上线检查（A4）

> 适用：试运行/生产上线前由运维执行。本文档只描述**可验证的配置项**；真实密钥、证书、域名由甲方环境注入，不进入仓库。

## 一、代码侧已有的安全关卡

| 关卡 | 位置 | 行为 |
|---|---|---|
| prod profile 严格默认 | `backend/src/main/resources/application-prod.yml` | 禁用 dev 账号、设备强鉴权、WS 必须 ticket；`sql.init.mode=never` 防止误删库 |
| 启动 fail-fast 校验 | `backend/.../config/ProductionConfigValidator.java`（`@Profile("prod")`） | 密钥为空/弱/开发默认、`DEV_USERS_ENABLED=true`、`DEVICE_AUTH_STRICT=false`、`WS_REQUIRE_TICKET=false`、`FRONTEND_ORIGIN` 为 localhost/*、`FILE_BASE_URL` 为 localhost、种子密码 `admin123` 等 → **拒绝启动** |
| MinIO 凭证校验 | `MinioCredentialValidator` | 空/弱 AccessKey/SecretKey 拒绝启动 |
| 配置检查器（只读） | `scripts/delivery/prod-config-check.mjs` | 部署前静态检查 `.env`，21 项 error/warn |
| 生产 env 模板 | `docs/delivery/env.production.example` | 所有密钥标注 `__GENERATE__`，需替换后校验 |
| 端口收敛 | `docker-compose.prod.yml` + `.env.example` | 后端默认绑回环 `127.0.0.1:8081`；AI 绑 `127.0.0.1:8001`；公网只走 frontend nginx 80/443 |

## 二、密钥生成

```powershell
# JWT / AES / 各类密码（生成后写入 .env.production，勿提交 git）
node -e "console.log(require('crypto').randomBytes(48).toString('base64'))"  # JWT_SECRET
node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"  # DATA_ENCRYPTION_KEY
node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"  # POSTGRES_PASSWORD
node -e "console.log(require('crypto').randomBytes(24).toString('base64'))"  # REDIS_PASSWORD
node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"  # MINIO_ROOT_PASSWORD
```

`DATA_ENCRYPTION_KEY` 一旦用于正式数据**不可更换**（换钥会导致历史身份证密文不可解），需与 JWT 分开保管、纳入密钥管理。

## 三、上线前操作步骤

1. 复制模板并替换所有 `__GENERATE__` 占位：
   ```powershell
   Copy-Item docs/delivery/env.production.example .env.production
   ```
2. 运行配置检查（必须 0 error）：
   ```powershell
   node scripts/delivery/prod-config-check.mjs .env.production
   ```
3. 校验 compose 组合（prod 是覆盖文件，必须同时带基础文件）：
   ```powershell
   docker compose --env-file .env.production -f docker-compose.yml -f docker-compose.prod.yml config --quiet
   ```
4. 配置 nginx：HTTPS 证书、`server_name`、CSP 白名单、`/api` 与 `/ws` 反代；确认 `infra/nginx/frontend.conf` 中上游为 `backend:8080` / `ai-service:8001`。
5. 网络隔离：
   - AI 服务无 JWT 鉴权（L-10），8001 只允许内网/容器网访问，严禁公网；
   - Postgres/Redis/MinIO 不暴露公网；
   - 后端仅由 nginx 访问（默认回环绑定）。
6. 启动并检查：
   ```powershell
   docker compose --env-file .env.production -f docker-compose.yml -f docker-compose.prod.yml up -d --build
   Invoke-RestMethod https://<域名>/api/system/health
   Invoke-RestMethod https://<域名>/api/ai/health
   ```
7. 首次登录后立即修改 admin 密码；删除/禁用演示账号；确认 `DEV_USERS_ENABLED=false`。
8. 配置备份任务（见 A5 演练命令）与审计日志留存周期。

## 四、已完成的验证证据（2026-11-13）

| 验证 | 命令 | 结果 |
|---|---|---|
| 配置检查器识别开发默认值 | `node scripts/delivery/prod-config-check.mjs .env` | ✅ 按预期报 **13 个 error**（DEV_USERS/DEVICE_AUTH/WS/域名/密钥等），退出码 1，证明检查有效 |
| 合格生产配置通过 | 用模拟强密钥 env 运行同一脚本 | ✅ **21/21 PASS**，退出码 0 |
| prod 校验器 fail-fast | 以 `SPRING_PROFILES_ACTIVE=prod` + 不安全开关启动后端 | ✅ `IllegalStateException: prod 配置校验失败（4 项）`，进程退出 1；日志 `output/delivery-verify/prod-validator-fail2.log` |
| 生产 compose 解析 | `docker compose --env-file docs/delivery/env.production.example -f docker-compose.yml -f docker-compose.prod.yml config --quiet` | ✅ 退出码 0 |
| 端口收敛 | `.env.example` / `docker-compose.prod.yml` | ✅ AI `127.0.0.1:8001`；后端 `127.0.0.1:8081`；公网仅 frontend |

> prod 校验器首次试验还额外证明：MinIO 空凭证会在更早阶段直接拒绝启动（`AccessKey and SecretKey must not be empty`），同样是 fail-fast。

## 五、上线日仍需甲方/运维完成（不在代码仓库内）

- [ ] 真实域名、DNS、TLS 证书
- [ ] `.env.production` 真实强密钥注入（不落仓库）
- [ ] 防火墙/安全组：仅开放 80/443；8001/5432/6379/9000 仅内网
- [ ] 改初始管理员密码、删除演示账号
- [ ] 备份定时任务（每日 pg_dump + MinIO 同步）与恢复演练（见 `ACCEPTANCE_LOG` A5 节）
- [ ] 审计日志留存周期与 SIEM/日志采集（如有）
- [ ] 甲方等保/密评要求逐条核对（如有）
- [ ] **CSP 收紧复核（2026-11-13 新增）**：`infra/nginx/frontend.conf` 为让 Cesium 三维地图可用，
  在 `script-src` 放开了 `'unsafe-eval' 'wasm-unsafe-eval'` 并补 `worker-src 'self' blob:`
  （真实浏览器验收发现不放开时 WASM/eval 被拦、控制台报 EvalError/CompileError）。
  这是明确的可用性取舍：上线时应结合真实域名重新评估，优先改用不含 eval 的 Cesium 构建或
  Trusted Types，再移除这两项。见 `KNOWN_LIMITATIONS.md` 〇节、`ACCEPTANCE_LOG` §33.3。
- [ ] **CORS 白名单按真实域名核对**：演练环境靠 nginx 把 `Origin` 改写为 `http://localhost:5173` 才通过
  后端校验；生产必须把真实前端 Origin 配进白名单，禁止 `*` 与 localhost 残留。
