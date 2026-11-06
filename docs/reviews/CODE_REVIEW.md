# 全项目代码审查报告 · DisasterGuard

> 审查日期：2026-09-19 ｜ 审查范围：`backend/` `frontend/` `ai-service/` `infra/` `scripts/` `docker-compose*.yml`  
> 审查方式：静态代码分析 + 依赖/配置交叉核对 + 实测构建验证  
> 结论：**2026-09-19 审查当时**仓库处于不可交付状态（1 个阻断构建的语法错误、3 组启动配置错配、2 个可提权漏洞）。
> ⚠️ 这是**历史快照**：上述阻断项与高危项已在同日二轮更新及 R2~R5 逐轮修复。当前状态请以
> `docs/reviews/CODE_REVIEW_R5.md`、`docs/可发布运行整改报告_20261105.md` 和
> `docs/audit/frontend/feature-reality-check-20261003.md`（2026-11-06 复核节）为准；
> 本文末尾的「总体结论 / 问题统计」表未随后续修复回写，**不可作为当前交付判断依据**。

> **[2026-09-19 二轮更新：阻断项与高危项已修复]**  
> 已修复：B1 前端构建（含 108 处乱码字符串还原）、B2/B3 Redis 与 MinIO 凭证错配、  
> B4 AI 依赖缺失与镜像构建失败、S2 前端权限兜底、S3 配置键错配、S1 中最危险的  
> `UserController` 提权 + `BackupController`/`RoleController`/`AuditLogController` 鉴权补充。  
> 验证情况：前端 `vite build` 实测通过；`docker compose config` 通过。  
> **第三轮补充（权限体系）**：`@PreAuthorize` 注解 45 → 241 处，Controller 覆盖 10/45 → 41/45。  
> 连带修复了 `PermissionRegistry` 只注册 4 个角色（实际有 7 个）的问题 ——  
> 该缺陷会导致 `system_admin` / `emergency_commander` / `ai_engineer` 在补上鉴权后被全部拒绝，  
> 已按前端定义同步为 7 角色。全部 Java 改动**已通过 Maven 编译验证**。  
> 剩余 4 个 Controller 为刻意跳过的 `permitAll` 端点（Sentinel / Auth / Health / Weather）。
>
> **仍需人工确认**：本次收紧是"由无鉴权变为按权限码鉴权"，虽已对齐前后端权限定义，  
> 但建议在测试环境用 7 个角色账号各跑一遍主流程，确认没有角色因缺权限而功能异常。
>
> **[第四轮更新：孤儿接口与断链 import]**  
> 本轮新增三个可复用的审计脚本，把"前端调用了但后端不存在"的接口从**人工抽查的 5 组**
> 变成**全量对账后的确定清单**：
>
> | 项 | 结果 |
> |---|---|
> | M3 断链 import | 全量扫描 147 个文件 → 5 处真实断链全部修复，现为 **0** |
> | M1/M2 孤儿接口 | 198 个前端调用 vs 302 个后端映射全量对账 → 孤儿从 **15 处降到 2 处** |
> | 后端编译 | 补齐 2 个漏掉的 `@PreAuthorize` import 后，**全量重编译通过** |
>
> 主要修复：
> - **新增 `NoticeTemplateController`（通知模板全套 5 个接口）** —— `notice_template` 表与
>   `NoticeTemplatePage.vue` 都已就绪，唯独后端缺失，页面长期靠 `sampleTemplates` 兜底
>   显示假数据。表结构追加 `channel_contents JSONB` 承载多渠道正文，旧数据通过
>   `{channel: content_template}` 回退读取，无需迁移。
> - `/zones/summary`、`/zones/risk-trend` → 改为真实存在的 `/dashboard/zone/kpi`、
>   `/dashboard/zone/trend`；后端 `trend` 原先硬编码 `region/kind` 为 `"all"`，
>   补上参数后切换区域才真正生效。
> - `subscribe-change` → `/catalog/assets/{id}/subscribe`（后端一直有，前端路径写错），
>   并补上缺失的请求体（`@RequestBody` 缺 body 会被判 400）。
> - `verifyBackup`、`requestDataAssetAccess` 补上映射，但**刻意返回 501 而非假装成功** ——
>   备份与资产目录目前都是内存 mock，在灾难恢复场景返回假的"校验通过"比直接报错更危险。
> - 顺带修正 `NoticeTemplatePage.vue` 的灾种筛选项：原先传中文标签，而后端
>   `hazard_type` 统一是英文码（landslide / collapse），筛选永远匹配不上。
>
> 剩余 2 处孤儿接口（`POST /reports/subscriptions`、`POST /sync/anomalies/{id}/ignore`）
> 经确认**没有任何页面调用**，属死代码，建议随 M6（内存 mock 落库）一并处理。
>
> 完整修复记录见 `CHANGELOG.md` 的「2026-09-19 全项目审查」小节。
>
> **[第五轮更新：2026-09-21 复审（结构重组后）]**
> 背景：`7416aed`（modular monorepo 重组）+ 268 个未提交文件改动之后复审。
>
> **新发现并已修复（本轮阻断 3 项）：**
> 1. **`NotificationService.java:207` 缺右花括号** —— `if` 块未闭合导致
>    `toDto` 方法体吞掉后续方法声明，clean 编译报"非法的表达式开始"。
>    此前 touch+增量编译显示 BUILD SUCCESS 是假象：target 里陈旧 class 掩盖了
>    该文件从未被编译。**已修复，`mvn clean compile` 实测通过。**
> 2. **`UserProfileService.java:139` 调用不存在的 `UserPreference.getUsername()`**
>    —— 实体只有 `userId`，改为 `getUserId()`。**已修复。**
> 3. **`NotificationController` 缺 `@Slf4j`** —— `currentOperator()` 内引用 `log`
>    编译失败。**已补注解与 import。**
> 4. **dev compose Redis 默认密码三处不一致复发（B2 变体）** —— redis 服务端与
>    healthcheck 已改为 `CHANGE_ME__dev_redis_password_2026`，但 backend env
>    默认值仍是旧的 `CHANGE_ME_redis_secure_password_2024`，未设 `REDIS_PASSWORD`
>    时后端 NOAUTH。**已统一，`docker compose config` 双文件实测通过。**
>
> **复核通过（未回退）：** B4 依赖声明完整且 YOLO 返回 `mock: bool` 可辨识降级、
> S2 前端权限兜底已改为 `?? []`、S3 配置键已用连字符、S4 prod 用 `:?` 强制校验、
> `@PreAuthorize` 251 处、PermissionRegistry 与前端均为 7 角色、脚本旧路径已清除、
> JWT_EXPIRE 已在 .env.example 文档化、三个审计脚本全绿。
>
> **仍存在的问题（累计未修 / 本轮新发现）：**
> - M6 内存 mock 7 个 Controller 未落库（含刻意 501 的 2 个接口）
> - 孤儿死代码 2 处：`POST /reports/subscriptions`、`POST /sync/anomalies/{id}/ignore`
> - M4 缩减但未清零：ESLint 20 → 10 errors（`mock/*.js`、`analyticsApi` 等），仍无 CI 门禁
> - M5 仍存：17 个文件使用 `setInterval`，WebSocket/定时器清理不完整
> - 轻微：`requirements.lock` 第 7 行仍是假哈希 `abc123def456…`，
>   `pip install -r requirements.lock` 必失败（应改为 pip-compile 重新生成或删除该文件）
> - **工作区不干净加剧**：268 个已修改 + 多个安全关键新文件未入库
>   （`RateLimitFilter.java`、`SecurityConfigProd.java`、`NoStoreCacheInterceptor.java`、
>   `ai-service/app/config.py`），丢失或被误清理将直接回退安全加固，建议尽快提交。
>
> **[第六轮更新：2026-09-21 遗留项清理（同日续）]**
> 1. **全部改动已提交**（`7d54430`，含安全组件与 268 个文件）。
> 2. **M4 清零**：ESLint 10 errors 全部修复（prefer-const / no-unused-vars / curly /
>    queueMicrotask 全局声明），`npm run lint` 与 `vite build` 实测通过。
> 3. **假 lock 文件已删除**：`requirements.lock` 含假哈希无法安装，
>    依赖以 `requirements.txt`（已锁版本）为准。
> 4. **M6 落库完成**：7 个内存 mock Controller（Backup / Sync / Plan / OTA /
>    WarningDelivery / Report / AccountSecurity）全部改为 JPA + JSONB 落库。
>    统一方案：每类 `Map<K, Map<String,Object>>` → `{id, payload JSONB, created_at}`
>    实体表（13 张新表已追加至 `schema.sql`），id 计数器启动时从 DB max 恢复，
>    种子数据 `@PostConstruct` 空表幂等写入，端点 / 响应结构 / `@PreAuthorize` /
>    刻意 501 的行为全部保持不变。`mvn clean compile` + 三审计脚本实测通过。
>    ⚠️ 注意：payload 内时间值统一为 ISO 字符串（JSONB 无法直接存 OffsetDateTime）。
> 5. 仍遗留：孤儿死代码 2 处、`setInterval` 清理不完整（M5）、lint 门禁未接 CI。



---

## 〇、总体结论

| 维度    | 评级    | 说明                                    |
| ----- | ----- | ------------------------------------- |
| 可构建性  | ❌ 阻断  | `vite build` 实测失败，前端无法打包              |
| 可启动性  | ❌ 阻断  | Redis / MinIO 凭证错配，容器内启动必失败           |
| 安全性   | ⚠️ 高危 | 35/45 Controller 无方法级鉴权；前端权限兜底为 admin |
| 架构一致性 | ⚠️ 中等 | 分层基本成立，但 Controller 过厚、迁移未完成          |
| 代码质量  | ⚠️ 中等 | 无 lint 门禁，技术债集中在 3 个巨型文件              |
| 文档准确性 | ⚠️ 偏差 | README 结构与实际布局不符，8 个脚本写死旧路径           |
| 依赖完整性 | ⚠️ 缺陷 | AI 服务 4 个核心包未声明，YOLO 推理实际为 mock       |

**问题统计**：阻断 4 · 严重 4 · 中等 9 · 轻微 9（共 26 项）

---

## 一、阻断级问题（必须立即修复）

### B1. 前端无法构建 —— `dashboardApi.js` 语法错误 + 中文注释大面积乱码

**位置**：`frontend/src/services/dashboardApi.js:71-75`

**证据**（`vite build` 实测输出）：

```
[vite:build-import-analysis] src/services/dashboardApi.js (73:53):
Failed to parse source for import analysis because the content contains invalid JS syntax.
71: // withQuery 已迁移到 utils/query.js
72:  * ????????????
73:  * - USE_BACKEND=false ?????? seedState ??????/??????
✗ Build failed in 35.66s
```

文件第 72 行的 `/**` 注释开始符被误删，导致后续注释体裸露为非法语法。同时该文件存在 **333 行乱码注释**（中文全部退化为 `?`），是典型的编码损坏事故（UTF-8 内容被按单字节编码重写）。

**影响**：`dashboardApi.js` 是全站 API 聚合入口，被绝大多数页面引用 → **整个前端无法构建、无法运行**。

**修复**：

1. 在第 71 行后补回 `/**`（最小修复，可立即恢复构建）
2. 从 Git 历史恢复该文件的中文注释（`git checkout HEAD~N -- path` 或从备份），乱码不影响运行但严重损害可读性
3. 全仓扫描是否有第二个文件处于同类编码损坏状态（本次扫描确认：仅此 1 个文件，其余 146 个源码文件语法与编码均正常）

---

### B2. Redis 密码三处默认值互不兼容 → 后端永久卡在 `depends_on`

**位置**：`docker-compose.yml:37` / `:44` / `docker-compose.prod.yml:44`

```yaml
# :37 服务启动密码
"--requirepass", "${REDIS_PASSWORD:-CHANGE_ME_redis_secure_password_2024}"
# :44 健康检查用的却是另一个密码
test: ["CMD", "redis-cli", "-a", "${REDIS_PASSWORD:-redis123}", "ping"]
```

未设 `REDIS_PASSWORD` 时：服务用 `CHANGE_ME_...` 启动，健康检查用 `redis123` 连接 → **healthcheck 永久 NOAUTH 失败** → `backend` 的 `depends_on: redis: service_healthy` 永久阻塞，后端永远起不来。  
即使注入了统一密码，prod 文件 `:44` 的 `${REDIS_PASSWORD}` 无默认值，未设时为空串 → 同样失败。

**修复**：三处统一为 `${REDIS_PASSWORD:?必须在 .env 中设置}`，去掉兜底值。

---

### B3. MinIO 凭证三方不一致 → dev 环境附件功能必 403

| 位置                                       | 用户名                | 密码                     |
| ---------------------------------------- | ------------------ | ---------------------- |
| `docker-compose.yml:55-57`（MinIO 服务端）    | `disasterguard`    | `CHANGE_ME_24chars...` |
| `docker-compose.yml:151-152`（dev 后端）     | `minioadmin`       | `minioadmin123`        |
| `docker-compose.prod.yml:46-47`（prod 后端） | `MINIO_ACCESS_KEY` | `MINIO_SECRET_KEY`     |

后端连接的凭证与 MinIO 服务端 root 凭证完全不匹配。

**修复**：统一走 `${MINIO_ROOT_USER}` / `${MINIO_ROOT_PASSWORD}`，后端同样引用这两个变量。

---

### B4. AI 服务核心依赖未声明 → YOLO 推理在容器内永久降级为 mock

**位置**：`ai-service/requirements.txt` vs `app/models/yolo_model.py:79,98-103`

```python
import cv2  # noqa
...
except ImportError:
    print("cv2 未安装，降级为 mock")
    return self._run_mock()
```

经 AST 比对，`cv2`、`numpy`、`scipy`、`ultralytics` **均未在任何依赖文件中声明**。OpenCV 无传递依赖来源 → 镜像构建后必缺 → YOLO 检测永远走 mock 分支，返回 `random.uniform(0.4, 0.85)` 的随机检测框。

**影响**：README 宣称的"YOLOv8 检测"能力在交付状态下是假的，且调用方无法区分真假结果（mock 与真实返回同构）。

**修复**：补齐 `opencv-python` / `numpy` / `scipy`；训练依赖（ultralytics）拆到 `requirements-train.txt`；在 `/api/ai/health` 显著标注降级态并让后端透传告警。

---

## 二、严重问题（安全）

### S1. 后端 35/45 Controller 无方法级权限校验 → 任意登录用户可提权

**位置**：`backend/.../config/SecurityConfig.java:45`

```java
.anyRequest().authenticated())   // 只验"已登录"，不验角色
```

45 个 Controller 中**仅 10 个**使用了 `@PreAuthorize`。最危险的是 `system/UserController.java:106`：

```java
user.setRoleCode(String.valueOf(body.getOrDefault("roleCode", "VIEWER")));
```

任意已登录的 VIEWER 用户可 `POST /api/users` 直接创建 `roleCode=ADMIN` 的账号；`PUT /{id}`（132-134 行）同样可改自身角色。  
同类暴露：`AuditLogController`、`BackupController`、`RuleEngineController`、`RoleController`。

**修复**：按项目已有的 `PermissionRegistry` 为全部 35 个 Controller 补 `@PreAuthorize`；`roleCode` 改为服务端枚举白名单校验，禁止从请求体直接取值。

---

### S2. 前端权限兜底为管理员 —— role 缺失即获得全部权限

**位置**：`frontend/src/stores/auth.js:286,288,289,343,349`

```js
role: (state) => state.user?.role ?? 'admin',
allowedPaths() { return ROLE_MENU_MAP[this.role] ?? ROLE_MENU_MAP.admin },
permissions() { return ROLE_PERMISSION_MAP[this.role] ?? ROLE_PERMISSION_MAP.admin },
```

用户角色缺失或是未知字符串时，**自动获得管理员的全部菜单与权限**。配合 S1 形成完整提权链。

**修复**：改为 `?? []` / `?? 'guest'` 并做白名单校验。注意 `router/index.js:646` 的守卫是纯前端校验，**后端必须独立鉴权**（当前 S1 正是缺失这一层）。

---

### S3. MinIO 弱口令校验器配置键写错 → 防护完全失效

**位置**：`backend/.../config/MinioCredentialValidator.java:29-36`

```java
@Value("${minio.access.key:}")    String accessKey;    // 实际配置键是 minio.access-key
@Value("${minio.access.secret:}") String secretKey;   // 实际配置键是 minio.secret-key
@Value("${disasterguard.dev-users-enabled:true}") boolean devMode;
```

实测确认：`application.yml:120/124` 使用**连字符**命名（`minio.access-key`），而 `@Value` **不做宽松绑定** → 三个键全部取空值 → 第 40 行 `if (accessKey.isBlank()) return;` 直接返回 → **弱口令黑名单永不触发**。

`ProductionConfigValidator.java:74-77` 存在同样错误。

**修复**：键名改回连字符形式，并加启动期断言确保校验真的执行。

---

### S4. 生产配置未加固基础设施，`CHANGE_ME_*` 默认值在生产生效

**位置**：`docker-compose.prod.yml`（仅覆盖 backend / frontend 两个服务）

postgres / redis / minio / emqx / tdengine **仍沿用开发文件里的默认密码**，包括 `disasterguard123`、`CHANGE_ME_redis_secure_password_2024`、`CHANGE_ME_24chars_minio_secret_key_2024`、`CHANGE_ME_emqx_secure_password_2024`。同时所有数据服务端口仍全量发布到宿主（15432/16379/19000/28083/16041 等）。

**修复**：prod 覆盖全部服务；敏感项改 `${VAR:?必须设置}`；删除数据服务的 `ports`，仅暴露 frontend 80/443。

---

## 三、中等问题

| #   | 问题                                                                                                                                          | 位置                               | 影响                             |
| --- | ------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------- | ------------------------------ |
| M1  | **5 组孤儿接口**：`/notice-templates` 全套 5 个端点后端不存在                                                                                               | `dashboardApi.js:1367-1415`      | `NoticeTemplatePage.vue` 整页不可用 |
| M2  | 另 4 组路径错配：`/zones/summary`→实际 `/api/dashboard/zone/kpi`、`/reports/subscriptions`、`/sync/anomalies/{id}/ignore`                              | `dashboardApi.js:1071,1082`      | 区域专项 KPI 无数据                   |
| M3  | **3 处断链 import**：`ChannelMatrix.vue:192/198/204`、`DeliveryReceipt.vue:45`、`notificationService.js:5`（`../../utils/query.js` 应为 `../../../`） | —                                | 运行时模块解析失败                      |
| M4  | ESLint 20 errors（prefer-const ×3、no-unused-vars ×3、no-console、含上述语法错误）                                                                      | 全前端                              | 无质量门禁，债务持续累积                   |
| M5  | WebSocket 从不关闭 + 14 个文件 `setInterval` 无 `onUnmounted` 清理                                                                                    | `realtimeService.js:450` 等       | 内存泄漏、连接数耗尽                     |
| M6  | 7 个 Controller 用 `ConcurrentHashMap` 做内存 mock，重启即丢                                                                                          | `BackupController.java:42-46` 等  | 备份恢复等功能实际不落库                   |
| M7  | 全表加载 + 内存分页：`userRepository.findAll()` 后 stream 过滤                                                                                          | `UserController.java:54`         | 数据量增长后 OOM                     |
| M8  | AI 未认证重训接口可 DoS：`samples: int = 1500` 无范围约束且同步阻塞                                                                                            | `routers/model_info.py:91-110`   | 单次请求可打满事件循环                    |
| M9  | AI 服务端口不一致：`main.py:129` 硬编码 `SENTINEL_CDN_BASE=localhost:8001`，而服务实际监听 8000                                                                | —                                | Sentinel 图片全部 404              |
| M10 | `AiController.java` 813 行、注入 8 个依赖、7 处 try-catch                                                                                            | —                                | 典型过厚 Controller                |
| M11 | 时区混用：配置 `Asia/Shanghai`，但多处 `OffsetDateTime.now(ZoneOffset.UTC)` 写库                                                                         | `AiController.java:227` 等        | 同一库内两套时区                       |
| M12 | 异常吞没：`catch (Exception ignored) {}` / 19 处 `except Exception` 仅 print                                                                       | `NotificationService.java:211` 等 | 静默降级，故障不可观测                    |

---

## 四、轻微问题

- **文档失准**：README 项目结构写 `src/` 实为 `frontend/src`；声称"57 路由 / 54 页面"，实测 63 条路由绑定、58 个 view 文件
- **8 个脚本写死旧路径** `D:\earthquake\...`（`audit-imports.ps1`、`check-sql-bom.ps1`、`strip-bom.ps1` 等），运行即报错
- **`scripts/audit/orphan_endpoint_scan.py:8`** 硬编码 `ROOT = r'd:\earthquake'`，在当前仓库直接失效
- **技术债集中**：`dashboardApi.js` 2292 行 / 141 处 `USE_BACKEND` 分支 / 115 处 mock 引用，文件头却声称"纯 re-export"，迁移未完成
- **11 个架构垫片残留**：`services/` 下仍有 `export * from './_architecture/...'`，与 CHANGELOG"已删除 17 个垫片"不符
- **环境变量名不匹配**：读 `VITE_AI_SERVICE_URL`，实际定义 `VITE_AI_SERVICE_BASE_URL` → 恒定回落硬编码地址
- **实体/schema 漂移**：`schema.sql` 的 `sys_user` 有 `email` 字段，JPA 实体无
- **配置项失效**：`compose` 传 `JWT_EXPIRE_MINUTES`，`application.yml:59` 读的是 `JWT_EXPIRE`
- **伪造 lock 文件**：`ai-service/requirements.lock` 哈希为 `abc123def456` 占位串，`pip install -r` 必失败
- **工作区不干净**：`ai-service` 有 7 个已修改未提交文件、5 个未跟踪文件（含核心 `app/config.py`）

---

## 五、未发现问题的方面（已核实）

- **SQL 注入**：26 处 `@Query` 全部使用 `:param` 绑定；未发现动态拼接用户输入的原生查询
- **JWT 密钥管理**：`JwtService` 有空值/长度/黑名单/弱模式四重校验，无硬编码 secret
- **全局异常处理**：`GlobalExceptionHandler` 存在且覆盖 401/403/404/409/422 + 兜底
- **Repository 纯度**：全部为纯接口，无实现类
- **地图/Cesium 实例泄漏**：6 处地图组件均有 `setTarget(undefined)` / `viewer.destroy()` 卸载销毁
- **前端依赖健康度**：`cesium ^1.144`、`ol ^10.10`、`vue ^3.5.13`、`vite ^6.0.5` 均为主线版本，无已知高危 CVE
- **真实密钥入库**：未发现 AKIA/LTAI/sk-/ghp_ 等云厂商特征串；`.env.local` 已被正确忽略
- **源码完整性**：后端 260 个 `.java`、前端 149 个源码文件均在版本控制内，无源码丢失（此前"仅 2 个 .java"是工具快照统计错误）
- **路径遍历**：AI 服务文件名拼接有扩展名白名单校验；`torch.load` 已正确使用 `weights_only=True`

---

## 六、修复优先级建议

**第一阶段 — 恢复可运行（1-2 天）**

1. B1 补回 `/**` → 恢复构建
2. B2 / B3 统一 Redis、MinIO 凭证 → 恢复启动
3. B4 补齐 AI 依赖声明

**第二阶段 — 封堵提权（2-3 天）**  
4\. S1 + S2 前后端双层鉴权（这是最严重的安全问题，务必一起修）  
5\. S3 修正配置键名让校验真正生效  
6\. S4 生产配置加固

**第三阶段 — 修复功能（1 周）**  
7\. M1/M2/M3 孤儿接口与断链 import  
8\. M5 定时器泄漏、M6 内存 mock 落库

**第四阶段 — 建立门禁（持续）**  
9\. M4 修复 lint 并接入 CI，防止 B1 类问题再次入库  
10\. 清理文档失准与残留垫片

---

*本报告由全仓静态分析生成，所有关键结论均已通过实测或交叉核对验证。*
