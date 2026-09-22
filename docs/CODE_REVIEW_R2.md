# DisasterGuard 第二次全面复审报告

- 复审日期：2026-09-21
- 代码基线：`532dc6c`（工作区干净，无未提交改动）
- 规模：后端 294 个 Java 文件 / 前端 149 个源文件 / AI 服务 31 个 Python 模块
- 复审范围：构建门禁、静态审计、安全配置、跨端契约、业务逻辑、测试覆盖

---

## 一、总体结论

**构建链与既有门禁全部通过；复审共发现 1 个阻断级缺陷、2 个严重问题、1 个中等
配置问题。其中阻断级与统计口径错误已于同日修复并补测（见第六节）。**

上一轮遗留的几项技术债（孤儿接口、假哈希锁文件、编码损坏、凭证错配）均已修复，
本次新发现的问题集中在「前端已实现、后端未承接」的接口缺口和**跨端编码双轨**上。

> 复审过程中额外发现：预警等级（`warning_level`）也存在中英双轨——
> 前端提交 `red/orange/yellow/blue`，库内存 `红色预警/橙色预警/...`。
> 该问题与灾害类型双轨一并纳入本次修复的归一化处理。

## 二、门禁执行结果

| 检查项 | 命令 | 结果 |
|---|---|---|
| 后端全量编译 | `mvn clean compile` | **PASS**（62s，EXIT=0） |
| 缺失 import 审计 | `check-missing-imports.mjs` | 0（扫描 290 文件） |
| 孤儿接口对账 | `check-endpoint-parity.mjs` | 0（后端 305 映射 / 前端 197 调用） |
| 前端断链 import | `check-broken-imports.mjs` | 0（扫描 147 文件） |
| 前端 ESLint | `npm run lint` | 0 问题 |
| 前端生产构建 | `npm run build` | **PASS**（14.29s） |
| 依赖漏洞 | `npm audit --omit=dev` | 0 vulnerabilities |
| AI 服务语法 | `py_compile` 全量 | 全部通过 |
| 硬编码凭证扫描 | 全仓库正则 | 仅 `node_modules` 误报；`.env` 已被 gitignore 正确忽略 |
| SQL 注入 | 原生查询拼接扫描 | 0 |

## 三、问题清单

### P0-1（阻断，✅ 已修复）预警创建接口在后端缺失

- 前端 `services/dashboardApi.js:632` 的 `createWarning()` 发起 `POST /api/warnings`。
- 后端 `warning/WarningController.java`（`@RequestMapping("/api/warnings")`）中
  **不存在任何无子路径的 `@PostMapping`**，仅有 `GET /stats`、`GET /viewport`、
  `GET /preview`、`GET /{id}`、`PUT /{id}`、`POST /{id}/confirm|dispatch|close|dismiss|resolve`、
  `DELETE /{id}`、`POST /export`。
- `WarningService.create(hazardCode, title, level, triggerValue, ruleCode)` 方法确实存在，
  但全仓库**没有任何调用者**——是死代码。
- `WarningCreatePage.vue` 已在路由中注册（`router/index.js:308`），用户可正常进入该页面，
  点击提交必然失败（404/405）。

**影响**：整个「预警发布向导」功能端到端不可用。

**修复方向**：在 `WarningController` 增加 `@PostMapping` 接收前端 payload 并转调
`WarningService.create`；注意前端 payload 字段（`title/level/hazardCode/hazardName/
hazardType/region/lon/lat/bufferKm/channels/content/validUntil/operator`）与现有
`create()` 签名不匹配，需一并扩展服务方法。

### P1-1（严重，✅ 已修复）预警统计接口返回值恒为 0

`WarningController.stats()` 第 66 行起：

```java
List<Object[]> byStatus = warningRepository.countByLevel();   // 返回 [warning_level, count]
...
result.put("dispatched", byLevel.getOrDefault("处理中", 0L));  // 用「状态」名去取「等级」Map
result.put("closed", byLevel.getOrDefault("已关闭", 0L) + ...); // 同上
```

而 `WarningEventRepository.countByLevel()` 的 SQL 是按 `warning_level` 分组
（红/橙/黄/蓝 + "未分级"），**不是按 status 分组**。

**结果**：`dispatched` 与 `closed` 永远返回 0。`pending`（走 `countActiveWarnings`）正常。

**影响**：概览页 / 顶栏红点的「已派发」「已关闭」计数永远显示 0，误导运营决策。

**修复方向**：新增 `countByStatus()` 按 `status` 分组，或改用现有 `countActiveWarnings`
配合终态集合计算。

### P1-2（中等）灾害类型中英文双轨并存

- **隐患点 / 预警侧**：`hazard_point.hazard_type` 用英文码，
  `NoticeTemplateController` 注释明确示例为 `landslide / collapse`。
- **AI 侧**：`AiController:327`、`AiServiceClient`、`DisasterAssessmentRequestDto`
  一律用中文六大类（滑坡 / 崩塌 / 泥石流 / 地裂缝 / 地面沉降 / 塌陷）。
- **前端**：`WarningCreatePage.vue:181` 直接提交 `hazardType: form.hazardType`，
  其值来自中文 option（"滑坡" 等）；
  `HistoryHazardsPage.vue:163` 的筛选下拉同样为中文。

**影响**：`hazard_type` 是无约束 `VARCHAR(64)`，中文能写入，因此不会报错，
但隐患点与预警的类型维度对不上——按灾害类型筛选、跨模块关联会静默失效。
**该问题在 P0-1 修复后会立即暴露。**

**修复方向**：统一为英文码存储 + 前端中文标签映射展示（AI 侧中文仅作展示层输出，
入库前转换）。

### P1-3（严重，新发现，待排期）两个 AI 接口前端已调用、后端未实现

由本次新建的反向对账门禁检出（见第七节）：

- `GET /api/ai/drift` ← `frontend/src/views/ai/ModelManagePage.vue`（经 `driftMonitorService`）
- `GET /api/ai/multimodal` ← `frontend/src/views/ai/AiInsightPage.vue`（经 `multiModalService`）

已核实**不是误报**：前端所有请求统一走 `http://localhost:8080/api`（`runtime.js:22`），
而后端 `AiController`（`@RequestMapping("/api/ai")`）下 25 个映射中**没有** drift / multimodal，
也**不存在**通配转发或 `/api/ai/**` 代理。

**影响**：这两个页面打开时对应功能会 404。

**未擅自实现**：漂移监控与多模态分析涉及 AI 侧业务语义与算法输出契约，
凭空补一个空壳接口比 404 更有误导性。已登记在门禁的 `KNOWN_GAPS` 基线中，
待明确契约后实现，实现完成请从基线移除。

### P2-1（中等）测试覆盖几乎为零

- 后端 294 个 Java 文件，仅 4 个测试类
  （`AiRequestDtoSerializationTest`、`JwtAuthenticationFilterTest`、
  `RateLimitFilterTest`、`DisasterguardApplicationTests`）。
- 前端 0 个测试文件。

**影响**：本次 P1-1 这类「方法名与返回语义不一致」的错误，本可被一个单测捕获。

### P2-2（轻微）生产默认配置偏宽松

- `docker-compose.prod.yml`：`MINIO_ROOT_USER` 默认 `disasterguard`（可枚举用户名，
  密码已强制要求环境变量，风险可控但不建议用默认名）。
- `SecurityConfig` / `SecurityConfigProd` 均对 `/api/weather/**`、
  `/api/sentinel/**`（仅 `/coverage` 只读端点）完全 `permitAll`。
- WebSocket `/ws`：`disasterguard.ws.require-ticket` 默认 `false`
  （已有 `JwtHandshakeInterceptor` 与 origin 白名单兜底，整体可接受）。

> 已核实：Swagger 在生产受 `swagger.enabled` 开关控制（默认关闭），**不存在**接口文档暴露问题。

### P3-1（轻微）构建产物残留

`frontend/dist.bak`、`frontend/dist.prev` 遗留在工作区（均未被 git 跟踪，
不会污染仓库，但占用磁盘）。

## 四、上一轮遗留问题的修复确认

| 上次记录的技术债 | 当前状态 |
|---|---|
| 2 处孤儿接口（`POST /reports/subscriptions`、`POST /sync/anomalies/{id}/ignore`） | ✅ 已归零 |
| `ai-service/requirements.lock` 假哈希致 `pip install` 失败 | ✅ 文件已移除 |
| `dashboardApi.js` 约 211 行编码损坏注释 | ✅ 0 残留 |
| MinIO `access-key` 与 `MINIO_ROOT_USER` 错配 | ✅ 已同源，生产强制环境变量 |
| 268 个文件未提交 | ✅ 工作区已干净 |

## 五、改进建议（按优先级）

1. ~~**修复 P0-1**：补齐 `POST /api/warnings`，让预警创建向导可用。~~ ✅ 已完成
2. ~~**修复 P1-1**：修正 `stats()` 的统计口径。~~ ✅ 已完成
3. **补齐测试**：至少为 `WarningService`、规则引擎、鉴权过滤器补充单测。
   （✅ 已迈出第一步：新增 `WarningManualCreateTest` 10 例）
4. ~~**补充反向契约门禁**~~ ✅ 已完成：新增 `scripts/audit/check-frontend-endpoints.mjs`
   并接入 CI。它内置**解析自检**（5 条已知映射）+ **已知缺口基线**，
   存量缺口不阻断、新增缺口立即失败。详见第七节。
5. ~~**彻底统一灾害类型与等级编码**~~ ✅ 已完成（前端侧）：`WarningCreatePage` / `HistoryHazardsPage` 灾种字段统一改为英文码 + 中文标签展示（与 `NoticeTemplatePage` 一致）；`selectHazard` 增加中文归一化兜底；后端 `normalizeHazardType()` 退化为防御性保留。

---

## 六、修复记录（2026-09-21 同日完成）

| 问题 | 处理 |
|---|---|
| P0-1 创建接口缺失 | `WarningController` 新增 `@PostMapping`（`warnings:create`），新增 `WarningCreateRequest` DTO 与 `WarningService#createManual()` |
| P1-1 统计恒为 0 | `WarningEventRepository` 新增 `countByStatus()`（按 status 分组），`stats()` 改用它，并保留 `byLevel`、新增 `byStatus` |
| P1-2 灾害类型双轨 | `createManual()` 内 `normalizeHazardType()`：中文 → 英文码（landslide / collapse / debris_flow / subsidence / earthquake），未识别值原样保留并告警 |
| 新发现：等级双轨 | `createManual()` 内 `normalizeLevel()`：red/orange/yellow/blue → 红色预警/橙色预警/黄色预警/蓝色预警，未识别回落黄色预警 |
| schema 同步 | `schema.sql` 为 `warning_event` 增加 `hazard_type VARCHAR(64)`、`manual_payload TEXT`（项目 `ddl-auto: none`，必须显式 ALTER）；`WarningEvent` 实体同步加字段 |
| 隐患点外键保护 | `hazard_code` 为 NOT NULL 且外键引用 `hazard_point`，`createManual()` 先校验存在性，不存在返回 400 而非 500 外键冲突 |
| 前端 | `WarningCreatePage` 第一步校验由 `hazardType` 改为 `hazardCode` |
| 测试 | 新增 `backend/src/test/java/cn/disasterguard/warning/WarningManualCreateTest.java`（10 例） |

**验证结果**：后端 `clean compile` PASS、`mvn test -Dtest=WarningManualCreateTest`
10/10 通过、缺 import 0、孤儿接口 0（后端映射 306）、前端 ESLint 0、`vite build` 成功。

**部署注意**：本次含 `schema.sql` 变更，已有库需执行新增的两条
`ALTER TABLE ... ADD COLUMN IF NOT EXISTS`（幂等，可随迁移脚本重复执行）。

---

## 六（续）、修复记录（2026-09-22）

| 问题 | 处理 |
|---|---|
| P1-2 灾害类型双轨（前端侧） | 前端 `form.hazardType` 默认值改为英文码 `"landslide"`；`WarningCreatePage` 灾种下拉改为 `hazardOptions`（`:value="opt.value"` 绑定英文码，展示中文 label）；`selectHazard()` 增加 `normalizeHazardType()` 中文→英文码兜底；`submit()` payload 提交英文码；摘要/模板卡展示改为中文标签（`hazardTypeToLabel` / `hazardTypeLabel`）；`templates` 数组 `disasterType` 改为英文码，`filteredTemplates` 匹配逻辑保持不变 |
| P1-2 灾害类型双轨（历史页侧） | `HistoryHazardsPage` 灾种筛选下拉改为 `hazardOptions`；`typeChart` 图表分组展示中文标签（`hazardTypeToLabel[h.type]`）；列表 `h.type` 展示改为中文标签；CSV 导出保持英文码以维护数据一致性 |
| P3-1 构建产物残留 | 删除 `frontend/dist.bak`（`dist.prev` 本不存在） |

**验证结果**：前端 ESLint 0 问题、`vite build` PASS（6.37s）。仅 1 条 OL chunk 重复导入警告（既有代码问题，与本次修改无关）。

**前端 API 约定**：灾种字段 `hazardType` 在整个应用中统一为英文码（landslide / collapse / debris_flow / subsidence / earthquake / flood / fire）；用户界面展示时通过 `hazardTypeToLabel` 映射为中文标签。后端 `WarningService.createManual()` 中的 `normalizeHazardType()` 同时承担「前端未归一化的中文兜底」与「AI 侧中文展示值兜底」双重职责，本次前端归一化后，该兜底路径退化为防御性保留。

---

## 七、新增门禁：反向接口对账

`scripts/audit/check-frontend-endpoints.mjs`，已接入 `.github/workflows/ci.yml`
的 `audits` job，与既有三项审计并列。

**为什么需要**：既有 `check-endpoint-parity.mjs` 只查「后端有映射、前端没调用」的
孤儿接口，方向相反。本次 P0-1（`POST /api/warnings` 后端不存在，而向导页已上线）
正属于它查不到的那类缺口。

**两个防误报设计**（2026-09-21 的一次性脚本曾因解析 bug 把 `/api/hazards` 误报成缺失，
教训是不能直接拿正则脚本的输出当结论）：

1. **解析自检**：脚本启动时先断言 5 条已知存在的映射能被测出来
   （`GET/POST /api/hazards`、`GET/POST /api/warnings`、`GET /api/warnings/{x}`）。
   解析不到就以退出码 2 报错——解析器写错时不给出任何结论。
2. **已知缺口基线 `KNOWN_GAPS`**：已人工核实的存量缺口登记在脚本顶部并注明日期与影响，
   不阻断 CI；**新增缺口才会导致退出码 1**。修复后从清单移除即可重新纳入覆盖。

解析时兼容的写法：类级 `@RequestMapping`、方法级 `@Get/Post/Put/Delete/PatchMapping`、
带 `value =` 的写法、**全限定注解名**（如
`@org.springframework.web.bind.annotation.PostMapping`）、
`@RequestMapping(value=..., method=RequestMethod.X)`。
前端侧区分「路径段变量 `${id}`」与「查询串变量 `${qs}`」，避免把 `/logs${qs}`
误判成 `/logs{x}`。

当前状态：后端映射 264、前端请求去重后 192，解析自检通过，
已知缺口 2 条（P1-3），退出码 0。
