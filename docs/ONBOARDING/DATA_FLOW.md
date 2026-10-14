# 端到端数据流

> 从浏览器点击到数据库返回的完整链路。基于实际代码提取。

---

## 一、整体拓扑

    ┌──────────────┐                                 ┌──────────────┐
    │   浏览器     │                                 │   PostgreSQL │
    │  (Vue 3)     │                                 │  (业务数据)  │
    └──────┬───────┘                                 └──────┬───────┘
           │ REST + JWT                                     │
           ▼                                                │
    ┌──────────────┐         HTTP           ┌──────────────┴┐
    │   backend    │ ──────────────────────►│  ai-service   │
    │(Spring Boot) │◄────────────────────── │  (FastAPI)    │
    └──────┬───────┘        JSON             └───────────────┘
           │ WebSocket                             ▲
           ▼                                       │
    ┌──────────────┐                        ┌──────┴───────┐
    │  浏览器实时  │                        │ TDengine /   │
    │  数据推送    │                        │ Redis /      │
    └──────────────┘                        │ MinIO / EMQX │
                                            └──────────────┘

**核心原则**：前端**不直连** ai-service，统一走 backend 网关。

---

## 二、三类核心链路

### 链路 1：同步 REST 请求（最常见）

**场景**：用户点按钮 → 加载列表 / 提交表单。

**流程**：

    1. view 层调用 service
       └─ 例如 dashboardApi.getZoneKpi(filter)

    2. service 走 httpClient.request()
       └─ frontend/src/services/_architecture/core/httpClient.js

    3. httpClient 自动：
       ├─ 从 stores/auth 注入 Authorization: Bearer <JWT>
       ├─ fetch(`${API_BASE}${path}`)
       └─ 401 自动 redirectToLogin()

    4. backend Controller 接请求
       ├─ Spring Security 校验 JWT + 权限
       ├─ Controller → Service → Repository
       └─ 操作 PostgreSQL

    5. 返回统一响应格式
       └─ { code: 0, data: <payload>, message: "ok" }

    6. httpClient 解析 payload
       ├─ code !== 0 → 抛 createApiError
       └─ code === 0 → return payload.data

    7. view 更新 UI

**关键约定**：

| 约定 | 说明 |
|------|------|
| 响应格式 | `{ code: 0, data, message }` |
| JWT 注入 | httpClient 自动加 Authorization header |
| 401 处理 | 自动跳登录（排除 /auth/*） |
| 超时 | 不强制，交给浏览器默认 |
| 错误 | 统一 `createApiError`，用 `error.code` 判定 |

**关键文件**：

- 前端客户端：`frontend/src/services/_architecture/core/httpClient.js`
- 后端统一响应：`backend/src/main/java/cn/disasterguard/common/api/`
- 后端权限：`backend/src/main/java/cn/disasterguard/common/security/`

---

### 链路 2：AI 定时任务（后台，无需用户触发）

**两个调度器**：

| 调度器 | 频率 | 职责 |
|--------|------|------|
| `AiHazardMonitorScheduler` | 默认 **90 秒**（可配） | 全国隐患排查，发现高风险点自动落库 |
| `AiInsightService.refreshAll()` | 默认 **每 5 分钟**（可配） | 全量刷新 AI 综合评分 |

#### AiHazardMonitorScheduler 流程

    1. 从 region 表加载可监控区域
       └─ town/village 级别，geo_type ≠ PLAIN

    2. 按 geo_type 决定网格步长
       ├─ MOUNTAIN:  0.01°  (~1.1 km)
       ├─ HILL:      0.02°  (~2.2 km)
       ├─ COASTAL:   0.015° (~1.6 km)
       └─ 默认:      0.02°

    3. 每个 region 中心生成 3×3 网格（中心 + 八方位）

    4. 批量调 Python AI
       └─ AiServiceClient.detectHazardsBatch(points, minRiskLevel)

    5. Python 端返回 risk_level ≥ HIGH 的点

    6. Java 端落库
       └─ HazardService.createFromAiDetection(...)

    7. WebSocket 推送
       └─ RealtimeBroadcastService.broadcastHazard(...)

**可配置项**（`application.yml` / 环境变量）：

| 键 | 默认 | 说明 |
|----|------|------|
| `disasterguard.ai.hazard-monitor-enabled` | `true` | 是否启用 |
| `disasterguard.ai.hazard-monitor-interval-ms` | `90000` | 调度间隔 |
| `disasterguard.ai.hazard-monitor-initial-delay-ms` | `8000` | 启动后首次延迟 |
| `disasterguard.ai.hazard-monitor-max-regions-per-tick` | `200` | 防过载 |
| `disasterguard.ai.hazard-monitor-include-plain` | `false` | 是否含 PLAIN 区域 |
| `disasterguard.ai.hazard-monitor-min-level` | `town` | 最小行政区级别 |
| `disasterguard.ai.hazard-monitor-min-risk-level` | `HIGH` | AI 检测阈值 |

#### AiInsightService.refreshAll() 流程

    1. 遍历所有 hazard_point
    2. 对每个点调 AI 综合分析
       └─ AiServiceClient.comprehensiveAnalysis(...)
    3. 落库 AiComprehensiveScore
    4. 可选：调 AiDeviceHealthService 更新设备健康分
    5. 可选：调 AiRiskEnricher 增强风险数据

**依赖组件**：

- `AiServiceClient` —— 调 Python AI
- `AiComprehensiveScoreRepository` —— 落库评分
- `HazardPointRepository` —— 读隐患点
- `AiComprehensiveAnalysisSupport` —— 组装请求参数
- `AiDeviceHealthService` —— 设备健康分
- `AiRiskEnricher` —— 风险数据增强

---

### 链路 3：WebSocket 实时推送

**场景**：预警、设备状态、应急事件变化，秒级推给前端。

**握手流程**：

    1. 客户端先取一次性 ticket
       └─ GET /api/auth/ws-ticket → 返回 ticket（60s TTL）

    2. 用 ticket 建立 WebSocket
       └─ new WebSocket("ws://host:port/ws?ticket=<xxx>")

    3. 服务端校验 ticket
       └─ JwtHandshakeInterceptor 校验 + consume（一次性）

    4. 连接建立，加入连接池

    5. 服务端主动推送
       └─ RealtimeBroadcastService.broadcastXxx(...)

**为什么用 ticket 而非直接 JWT**：

- 反向代理 / 服务器日志里看到的是**一次性随机 token**，即使泄露也无法重放
- JWT 有效期长，不适合放在 URL query（日志会记录）

**广播类别**（`RealtimeBroadcastService`）：

| 方法 | 推送内容 |
|------|---------|
| `broadcastWarning` | 预警事件 |
| `broadcastDevice` | 设备状态 |
| `broadcastPersonnel` | 人员位置 |
| `broadcastEmergency` | 应急事件 |
| `broadcastHazard` | 新增隐患点 |
| `broadcastScreen` | 大屏数据 |

**关键文件**：

- `backend/.../realtime/WsTicketStore.java` —— ticket 存储
- `backend/.../realtime/JwtHandshakeInterceptor.java` —— 握手拦截
- `backend/.../realtime/RealtimeBroadcastService.java` —— 广播服务
- `backend/.../realtime/WebSocketConfig.java` —— WebSocket 配置

---

## 三、AI 降级策略

**核心设计**：AI 不可用时**不阻塞主流程**。

**降级触发条件**：

- ai-service 未启动 / 网络不通
- `AiProperties.enabled = false`
- 请求超时

**降级行为**：

| 场景 | 降级结果 |
|------|---------|
| 融合预警 | 启发式加权融合 |
| 图像识别 | 返回 mock（无检测） |
| 时序预测 | 返回 null 或空预测 |
| 综合评分 | 用最近一次成功值 |

**实现位置**：

- 后端：`AiServiceClient` 每个方法内部 try/catch，降级到启发式
- Python 端：模型文件缺失时加载为 null，`_load()` 打印警告

---

## 四、其他数据流向

| 服务 | 用途 | 访问方 |
|------|------|--------|
| **PostgreSQL** | 主数据库（业务 + AI 结果） | backend |
| **TDengine** | 时序指标（设备高频数据） | backend / ai-service |
| **Redis** | 会话 / 缓存 | backend |
| **MinIO** | 文件附件 | backend |
| **EMQX** | MQTT 设备接入 | backend / hardware-sandbox |

---

## 五、关键约定速查

| 约定 | 位置 |
|------|------|
| 前端响应格式 `{ code, data, message }` | `backend/.../common/api/` |
| JWT 注入 | `frontend/.../core/httpClient.js` |
| 401 处理 | `frontend/.../core/httpClient.js` |
| AI 降级 | `backend/.../ai/AiServiceClient.java` |
| WebSocket ticket | `backend/.../realtime/WsTicketStore.java` |
| 统一异常 | `backend/.../common/exception/` |

---

**最后更新**：2026-10-15
**维护者**：见 `git log docs/ONBOARDING/DATA_FLOW.md`
