# 变更日志 · CHANGELOG

> 所有版本演进记录。格式基于 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。
> 版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

---

## [Unreleased]

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
