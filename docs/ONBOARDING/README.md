# 新人入门指南

> 5 分钟看懂项目 + 找到你要的东西。

---

## 这是什么项目

**地脉智巡（DisasterGuard）** —— 地质灾害监测预警系统。

覆盖隐患点管理、巡检任务、监测设备、预警规则、应急调度、
AI 综合风险分析、卫星影像对比、气象数据接入。

---

## 三大模块 30 秒速览

| 模块 | 技术栈 | 职责 |
|------|--------|------|
| `ai-service/` | Python + FastAPI | 模型推理 + 训练，供 backend HTTP 调用 |
| `backend/` | Java + Spring Boot | 业务编排 + 数据库 + 认证 + REST API |
| `frontend/` | Vue 3 + Vite | Web UI |

**数据流**：`frontend` → `backend`（REST）→ `ai-service`（HTTP）→ `backend` → `frontend`

详细数据流见 `docs/ONBOARDING/DATA_FLOW.md`。

---

## 5 分钟上手路径

按顺序读，不要跳：

1. **环境跑起来** → `docs/DEVELOPER.md` 第 4 章（本地开发环境搭建）
2. **项目架构** → `docs/DEVELOPER.md` 第 3 章（整体架构与目录结构）
3. **每个包做什么** → `docs/ONBOARDING/MODULES.md`
4. **端到端数据流** → `docs/ONBOARDING/DATA_FLOW.md`
5. **历史决策 / 已知坑** → `docs/ONBOARDING/DECISIONS.md`

**5 分钟后你就能**：说清三大模块的职责，知道任何代码改动会牵动谁。

---

## 我要做 X，看哪里

| 我要... | 看... |
|--------|------|
| 本地跑起来 | `docs/DEVELOPER.md` 第 4 章 |
| 用 Docker 部署 | `docs/DEVELOPER.md` 第 12 章 |
| 前端加个页面 | `docs/DEVELOPER.md` 第 5 章 + `docs/FRONTEND_API.md` |
| 后端加个 API | `docs/DEVELOPER.md` 第 6 章 + 第 7 章 |
| 看 API 参考 | `docs/DEVELOPER.md` 第 7 章 / `docs/FRONTEND_API.md` |
| 改数据库 schema | `docs/DEVELOPER.md` 第 8 章 + `backend/src/main/resources/schema.sql` |
| 加权限 | `docs/DEVELOPER.md` 第 9 章 + `backend/.../common/security/` |
| WebSocket 推送 | `docs/DEVELOPER.md` 第 10 章 + `backend/.../realtime/` |
| 环境变量 | `docs/DEVELOPER.md` 第 11 章 + `.env.example` |
| 训练模型 | `ai-service/scripts/training/README.md` |
| 加 AI 路由 | `ai-service/README.md` + `docs/ONBOARDING/MODULES.md` |
| 理解 AI 模块 | `docs/guides/AI_MODULE_IMPROVEMENT_20260929.md` |
| 排查本地问题 | `docs/DEVELOPER.md` 第 14/15 章 |
| 了解历史重构 | `docs/ONBOARDING/DECISIONS.md` |
| 看代码评审 | `docs/reviews/CODE_REVIEW*.md` |
| 看前端审计 | `docs/audit/frontend/` |

---

## 文档地图

    docs/
    ├── ONBOARDING/              ← 你在看（新入口）
    │   ├── README.md            ← 本文
    │   ├── MODULES.md           ← 模块职责一览
    │   ├── DATA_FLOW.md         ← 端到端数据流
    │   └── DECISIONS.md         ← 关键决策记录
    ├── DEVELOPER.md             ← 深度开发指南（28 章）
    ├── ARCHITECTURE.md          ← 架构总览
    ├── FRONTEND_API.md          ← 前端 API 参考
    ├── HARDWARE_SANDBOX.md      ← 硬件沙箱
    ├── UNIFIED_EVENT_STATEMACHINE.md ← 事件状态机
    ├── CDSE_SETUP.md            ← CDSE 配置
    ├── README.md                ← 文档索引（旧入口）
    ├── guides/                  ← 操作指南
    ├── reviews/                 ← 代码评审
    └── audit/frontend/          ← 前端审计报告

**模块自述**（就近放置）：

    ai-service/README.md                    ← AI 服务总说明
    ai-service/scripts/README.md            ← scripts 分类
    ai-service/scripts/training/README.md   ← 训练脚本版本
    frontend/src/services/README.md         ← 服务层架构
    scripts/README.md                       ← 顶层工具分类

---

## 快速定位代码

**按需求找代码**：

| 需求 | 模块 | 位置 |
|------|------|------|
| 登录认证 | backend | `backend/.../auth/` |
| 隐患点管理 | backend | `backend/.../hazard/` |
| 预警规则引擎 | backend | `backend/.../warning/engine/` |
| 统一事件状态机 | backend | `backend/.../event/` |
| AI 综合风险 | ai-service | `ai-service/app/routers/comprehensive.py` |
| 融合预警 | ai-service | `ai-service/app/routers/fusion.py` |
| 图像识别 | ai-service | `ai-service/app/routers/detection.py` |
| 前端 API 调用 | frontend | `frontend/src/services/` |
| 前端页面 | frontend | `frontend/src/views/<业务域>/` |

---

## 遇到问题

1. **环境问题** → `docs/DEVELOPER.md` 第 14/15 章
2. **业务问题** → 看对应模块的 README + MODULES.md
3. **历史决策** → `docs/ONBOARDING/DECISIONS.md`
4. **代码风格** → `.cursorrules` + `CONTRIBUTING.md`

---

## 维护

**本文档的维护原则**：

- 不重复已有文档，只做导航
- 引用必须指向存在的文件
- 新增模块时同步更新 `MODULES.md` 和本文

**最后更新**：2026-10-15
**维护者**：见 `git log docs/ONBOARDING/`
