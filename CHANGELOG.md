# 变更日志 · CHANGELOG

> 所有版本演进记录。格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。
> 版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [Unreleased]

### 🐛 修复（2026-09-21 结构重组后复审 — 编译阻断与配置）

- **后端编译阻断 ×3（均被 target 陈旧 class 掩盖，clean 编译才暴露）**：
  1. `NotificationService.java` `toDto` 内 `if` 块缺右花括号 → 补回；
  2. `UserProfileService.java` 调用不存在的 `UserPreference.getUsername()` → 改为 `getUserId()`；
  3. `NotificationController` 缺 `@Slf4j` → 补注解与 import。
  修复后 `mvn clean compile` 实测通过。
- **dev compose Redis 默认密码不一致复发（B2 变体）**：backend env 默认值仍为
  旧的 `CHANGE_ME_redis_secure_password_2024`，与 redis 服务端
  `CHANGE_ME__dev_redis_password_2026` 不匹配，未设 `REDIS_PASSWORD` 时后端
  NOAUTH 连接失败。已统一并加注释约束，`docker compose config` 双文件验证通过。
- 复审详情见 `docs/CODE_REVIEW.md` 第五轮更新。

### 🐛 修复（2026-09-19 全项目审查 — 可运行性与安全）

- **前端构建阻断**：`dashboardApi.js:72` 丢失 `/**` 导致 `vite build` 失败；
  同时还原该文件中 108 处因编码损坏退化为 `?` 的**字符串字面量**
  （报错提示、导出文件名、状态机标签等，此前会直接显示成问号）。
  状态机标签与解除范围取值分别对齐 `EventDetailDrawer.vue` 与 `WarningService.resolveScope()`。
- **容器无法启动**：Redis 启动密码与 healthcheck 密码默认值不一致，
  导致 healthcheck 永久失败、`depends_on` 永久阻塞；MinIO 服务端与后端凭证三方不一致。
  两处已统一，生产文件改用 `:?` 缺失即 fail-fast。
- **AI 服务 YOLO 恒为 mock**：`opencv-python` / `numpy` / `scipy` 未在 requirements 声明，
  容器内必缺 cv2，YOLO 静默降级为随机检测框。已补齐（cv2 用 headless 变体）。
- **AI 镜像构建失败**：Dockerfile 中 `chown -R python:python` 指向不存在的用户，已移除。
- **越权提权（后端）**：`UserController` 任意登录用户可创建/修改 `roleCode=admin`。
  已补充 `@PreAuthorize` 四件套 + roleCode 服务端白名单（原默认值 `VIEWER` 不在 `sys_role` 表）。
  另为 `BackupController`（9 个端点，含恢复备份/执行策略）、`RoleController`、`AuditLogController` 补鉴权。
- **越权提权（前端）**：`stores/auth.js` 中角色缺失时兜底为 `admin`。
  根因是后端 `role_code` 用下划线（`system_admin`）而前端映射表用连字符（`system-admin`），
  三个角色从未命中映射、全靠兜底"救活"，实际拿到管理员权限。
  已引入 `normalizeRole()` 统一键名，并将兜底收紧为空权限。
- **弱口令校验失效**：`MinioCredentialValidator` / `ProductionConfigValidator` 的
  `@Value` 键名误写为点号形式（`minio.access.key`），而实际配置键为连字符，
  `@Value` 不做宽松绑定导致校验从未执行。已修正，并拆分 access/secret 黑名单。
- **权限体系补齐（第二轮）**：为缺失鉴权的端点批量补充 `@PreAuthorize`，
  注解数 45 → 241，Controller 覆盖 10/45 → 41/45（剩余 4 个为 SecurityConfig 显式 permitAll：
  Sentinel / Auth / Health / Weather）。
- **权限定义与前端对齐**：`PermissionRegistry` 原先只注册 4 个角色，而 `sys_role` 与前端有 7 个，
  `permissionsOf()` 对未注册角色返回空集 —— 意味着 `system_admin` / `emergency_commander` /
  `ai_engineer` 在后端毫无权限。此前因 Controller 普遍无鉴权而不显形，补上注解后会直接拒掉这三个角色。
  现已按前端 `ROLE_PERMISSION_MAP` 同步为 7 角色完整定义（脚本 `sync-permission-registry.mjs`）。
  新增 `inspection` 权限域，并修正 `PersonnelController`（→ personnel 域）、
  `SyncController`（→ sync 域）的归属。
- **通知模板后端补齐（第三轮）**：`notice_template` 表与 `NoticeTemplatePage.vue` 都已就绪，
  唯独后端没有任何接口，前端 5 个调用全部 404、页面长期靠 `sampleTemplates` 显示假数据。
  新增 `NoticeTemplateController` + 实体 + 仓储，覆盖列表/详情/新建/更新/删除/启停。
  表结构追加 `channel_contents JSONB` 承载「渠道 → 正文」映射（原表设计是一行绑定单渠道），
  旧数据经 `{channel: content_template}` 回退读取，**无需数据迁移**。
- **孤儿接口修复**：新增 `scripts/audit/check-endpoint-parity.mjs` 做前后端全量对账
  （198 个前端调用 vs 302 个后端映射），据此修复：
  - `/zones/summary` → `/dashboard/zone/kpi`、`/zones/risk-trend` → `/dashboard/zone/trend`
    （后端从未有过 `/api/zones` 基址）；
  - 后端 `ZoneDashboardController.trend` 原先硬编码 `region/kind` 为 `"all"`，
    补上参数后前端切换区域的过滤才真正生效；
  - `subscribe-change` → `/catalog/assets/{id}/subscribe`（后端一直存在，前端路径写错），
    并补上缺失的请求体。
- **刻意返回 501 的两个接口**：`POST /backup/records/{id}/verify` 与
  `POST /catalog/assets/{id}/access-requests` 此前完全没有映射（页面点击直接 404），
  已补上映射，但明确返回 501 —— 备份记录与资产目录目前都是内存 mock，
  在灾难恢复场景返回假的"校验通过"比直接报错更危险，待落库后再实现真实逻辑。
- **断链 import 修复**：`ChannelMatrix.vue`（3 处）、`DeliveryReceipt.vue`、
  `_architecture/domain/notificationService.js` 的相对路径在目录重构后未同步更新；
  均为动态 `await import()`，构建期不报错、运行时才失败。
- **灾种取值对齐**：`NoticeTemplatePage.vue` 的灾种筛选传中文标签，而后端
  `hazard_type` 统一为英文码（landslide / collapse，同 `RuleEditor.vue` 的约定），
  筛选永远匹配不到记录。已改为 `{value, label}` 结构并新增 `hazardLabel()`。

> 本轮 Java 改动已用 Maven 编译验证通过。说明：本机 `mvn` 因 `M2_HOME` 未设置而不可用，
> 需直接调用 classworlds launcher（见下方"本地验证命令"）。

#### 本地验证命令

本机 `mvn` 启动器不可用（环境变量只有 `MAVEN_HOME`，没有 `M2_HOME`，
`mvn.cmd` 依赖 `M2_HOME` 拼 classworlds 路径，报
`找不到或无法加载主类 org.codehaus.plexus.classworlds.launcher.Launcher`）。
已提供包装脚本，**必须在 `backend/` 目录下执行，不要带 `-f` 参数**：

```bash
# 后端编译（--offline）
cd backend && bash ../scripts/audit/mvn.sh compile
```

注意 Maven 增量编译只重编译变更文件，`BUILD SUCCESS` **不代表整个模块可编译**。
做全量校验前先刷新时间戳：

```bash
cd backend && find src/main/java -name "*.java" -exec touch {} + && bash ../scripts/audit/mvn.sh compile
```

新增的审计脚本（均从仓库根目录执行，失败退出码 1，可直接接入 CI）：

```bash
node scripts/audit/check-missing-imports.mjs      # 用了注解却漏 import（260 个 Java 文件）
node scripts/audit/check-endpoint-parity.mjs      # 前后端接口对账，找孤儿接口
cd frontend && node ../scripts/fix/check-broken-imports.mjs   # 前端断链 import
```

### 🔧 优化（Step 9 — 二次清理 + 文档同步）
- 清理冗余脚本与历史快照：
  - 删除 `scripts/rewrite-operation-log.ps1`（一次性迁移工具，已完成使命）
  - 删除 `scripts/check-vue.cjs`（一次性 Vue 语法检查脚本）
  - 删除 `ai-service/models/history/xgboost_fusion-20260907-062517.json`（与现役模型重复的旧快照）
  - 删除本地 `logs/`、`backend/logs/`、`backend/.github/modernize/` 等 AI 工具 / 开发期残留目录
- `.gitignore` 增补规则：
  - `.github/` / `backend/.github/`（Copilot App Modernization 等 AI 工具运行时产物）
  - `ai-service/models/history/`（模型历史快照目录）
- 文档同步：
  - `README.md` 更新项目结构（`scripts/` 8 项、`views/` 54 项、组件目录、`_architecture/` 子目录）
  - `README.md` 特性列表去重并细化权限矩阵（7 角色 × 34 模块）
  - `start-dev.ps1` / `start-dev.sh` 增加 `status` 子命令（健康度速览）
  - `docs/DEVELOPER.md` 顶部时间戳 + §28「项目结构与清理」章节
  - `CHANGELOG.md` 新增本次清理条目（本文档）

### 📝 文档
- 维护脚本总数：**8 → 8**（删 2 旧 + 新增说明）
- 模型历史快照：已统一忽略，避免下次训练误提交

---

## [Unreleased — Step 6/7/8 累计]

### ✨ 新增
- **移动端指挥入口**（`/mobile`）— PWA 极简指挥界面，支持 KPI 看板、待办一键审批、预警一键发布/解除、视频会商、离线检测
- **社交媒体舆情监测**（`/dashboard/social-media`）— 微博/微信/Twitter/抖音多平台聚合、关键词监控、情感分析、疑似灾害线索提取
- **帮助中心**（`/dashboard/help`）— 操作手册（按角色展示）、FAQ、事件状态机图、API 文档入口、反馈提交
- **通知渠道配置**（`/dashboard/notification-channels`）— 6 种渠道类型（短信/APP/邮件/LED/Webhook/声光）、连接测试、降级策略、发送日志
- **权限矩阵视图** — `UserManagementPage` 新增"权限矩阵"标签页，7 角色 × 34 模块可视化
- **区域专项菜单入口** — `zone` 路由在菜单中显式可见

### 🔧 优化
- 项目结构清理：
  - 删除 7 个调试输出文件（`audit_output*.txt`、`audit_result.txt`）
  - 删除 2 个临时测试脚本（`test-exit*.ps1`）
  - 删除 1 个重复 JSONB 校验脚本（合并到 `check_jsonb_sql.py`）
  - 删除 17 个老版本兼容垫片（更新所有 import 至 `_architecture/`）
  - 删除 1 个一次性 helper 脚本（`count_statements.ps1`）
  - 删除 2 个 `.pytest_cache` 自动生成目录
- `.gitignore` 增加 `.pytest_cache/` 规则
- 文档全面更新：
  - 重写 `README.md`，新增启动脚本与项目结构
  - 新增 `docs/ARCHITECTURE.md`（架构总览）
  - 新增 `CHANGELOG.md`（本文件）
  - 新增 `CONTRIBUTING.md`（贡献指南）
- 启动脚本统一：
  - 新增 `start-dev.ps1`（Windows 跨域启动：infra/backend/frontend/ai/all）
  - 新增 `start-dev.sh`（Linux/Mac/WSL 同等能力）
  - 保留 `run-backend.ps1` / `run-frontend.ps1` 作为内部子命令入口

### 📝 文档
- 路由总数：**53 → 57**
- 前端页面总数：**50 → 54**
- 完整路由清单见 [README.md](../README.md) 与 `src/router/index.js`

---

## [1.7.0] - 2026-08-15

### ✨ 新增
- 统一事件中心（`/dashboard/events`）— 跨 hazard/warning/emergency 三表统一跟踪，7 状态 × 12 转移
- 现场巡检（`/dashboard/field-inspection`）— P1 阶段新增
- 人员管理（`/dashboard/personnel`）— P1 阶段新增
- 灾害预测（`/dashboard/disaster-forecast`）— P1 阶段新增
- 灾损评估（`/dashboard/damage-assessment`）— P1 阶段新增
- 路径规划（`/dashboard/route-planning`）— P0 阶段新增
- 解除预警（`/dashboard/warnings/:id/resolve`）— P0 阶段新增
- 个人中心（`/dashboard/profile`）— P0 阶段新增
- 会签与转派（`/dashboard/confirmation-sign`）— P0 阶段新增
- AI 健康徽标组件 `AiHealthBadge.vue`
- 事件状态时间线组件 `EventStatusTimeline.vue`
- 服务健康横幅组件 `ServiceHealthBanner.vue`
- 事件组件目录 `src/components/event/`
- AI 服务健康检查 `services/_architecture/core/healthService.js`

### 🔧 优化
- 前后端架构统一到 `_architecture/core` + `_architecture/domain` 分层
- `dashboardApi.js` 大幅重构，从 80KB 单文件迁移到分层服务
- `mapService.js` 从内联升级为分层控制器
- `realtimeService.js` 实现基于 ticket 的 WebSocket 鉴权
- 所有 service 引入 mock/真实双实现，可独立切换

### 🐛 修复
- 修复了 WebSocket 重连时 token 过期问题
- 修复了 AI 服务不可用时主流程阻塞问题（降级为 mock）
- 修复了权限路由 403 后无提示问题

### 📝 文档
- 新增 [docs/UNIFIED_EVENT_STATEMACHINE.md](../docs/UNIFIED_EVENT_STATEMACHINE.md)
- 新增 [docs/STRUCTURE_OPTIMIZATION_PLAN.md](../docs/STRUCTURE_OPTIMIZATION_PLAN.md)
- DEVELOPER.md 更新至 250+ 小节

---

## [1.6.0] - 2026-05-20

### ✨ 新增
- AI 智能巡检与推理（YOLOv8 / LSTM / XGBoost）
- 多时相卫星对比（`/dashboard/satellite-compare`）— Sentinel-1/2 卷帘/闪烁/并排
- 设备地图与轨迹回放（`/dashboard/device-map`）
- 时相对比大屏
- 通知模板管理（`/dashboard/notice-templates`）
- 预案管理（`/dashboard/plans`）
- 报表中心（`/dashboard/reports`）
- 数据资产目录（`/dashboard/data-catalog`）

### 🔧 优化
- 集成 PostGIS 空间索引
- MinIO 真实附件存储替代数据库 BLOB
- TDengine 设备遥测高频数据接入
- EMQX 5 设备认证（用户名 + 客户端 ID）

### 🐛 修复
- 大量前端 mock 数据脏数据清理
- 修复多角色登录后菜单缓存问题

---

## [1.5.0] - 2026-03-10

### ✨ 新增
- 设备管理（`/dashboard/devices`）
- OTA 与边缘升级（`/dashboard/devices/ota`）
- 数据源接入（`/dashboard/data-sources`）
- 数据同步监控（`/dashboard/data-sync`）
- 气象分析（`/dashboard/weather`）
- 消息中心（`/dashboard/message-center`）
- 待办中心（`/dashboard/todo`）
- 审批中心（`/dashboard/approvals`）

### 🔧 优化
- 引入 Spring Data JPA + Hibernate Spatial
- 引入 Vue Router 4 + Pinia

---

## [1.0.0] - 2025-12-01

### 🎉 首次发布

- 综合态势大屏
- 灾害事件列表与详情
- 人工确认工作台
- 预警中心
- 应急事件
- 用户与权限管理
- 7 角色权限矩阵
- JWT 鉴权 + 细粒度操作权限

---

## 版本命名规范

- **主版本号**：架构重大变更（不兼容 API）
- **次版本号**：新功能（向后兼容）
- **修订号**：Bug 修复（向后兼容）

预发布版本：`1.7.0-rc.1`
构建元数据：`1.7.0+build.123`
