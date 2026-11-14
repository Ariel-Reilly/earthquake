# 2026-11-14 审查修复报告（功能 / 数据流转 / UI 可读性）

> 输入：一次「真实接口 + 真实浏览器」的第三方审查结论（证据在 `output/review-*.json`、
> `output/review-ui/*.png`）。本轮按 **P0 → P1** 修复了可在本机闭环的问题，
> 并用同一套真实链路回归。**审查报告本身未修改代码，修复与验证由本轮完成。**

## 一、复现确认（先验证再动手）

在 `release/delivery` 分支 `b31501c`（tag `v1.8.5-delivery`）上用 dev 后端 8080 实测：

| 问题 | 复现命令 / 结果 |
|---|---|
| 设备地图 500 | `GET /api/device-map` → `HTTP 500 {"code":500,"message":"系统繁忙，请稍后重试"}` |
| 灾害预测 500 | `GET /api/ai/forecast/auto?deviceCode=DV-001&metric=displacement&horizon=6` → `HTTP 500` |
| 气象页 403 | `ROLE_PERMISSION_MAP.admin` 与 `PermissionRegistry` admin 集合均缺 `weather:view`；`weather` 路由要求该码 |
| drift 路径重复 | `driftMonitorService.js` 回退写成 `/ai/drift${path}`（path 已含 `/drift/...`） |
| 空 KPI 卡片 | `WarningDto` 无 `notificationRate/deptCount`，`WarningsPage` 永远显示 `—` |
| 首屏弹窗 | `useHazardList.js` 加载后 `drawerOpen = Boolean(selected)` |

审查证据复核：`output/review-endpoint-scan.json` 共 110 个 GET，1 个 500（`/api/device-map`）、
2 个显式 501、1 个外部气象超时；`output/review-menu-scan.json` 41 条路由中 1 个 403（`/dashboard/weather`）。

## 二、修复清单

### P0（交付前）

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 1 | `/api/device-map` 500 | `DeviceMapService.java:111` 把 `Instant` 强转 `java.sql.Timestamp` → `ClassCastException` | 新增 `toOffsetDateTime(Object)`，兼容 `Instant` / `Timestamp` / `OffsetDateTime` / `LocalDateTime` / `java.util.Date` / 字符串，未知类型返回 null 并记 warn；调用方回退设备自身时间 |
| 2 | 前端静默展示 12 台成都 mock 设备 | `deviceMapService.js` 捕获异常后 `return generateMockDeviceMap()`，无标识 | 改为**默认失败即抛错**；演示数据仅在显式 `VITE_DEVICE_MAP_DEMO_FALLBACK=1` 时返回且带 `demo:true`；`DeviceMapPage` 新增错误横幅 / 「演示数据」横幅，失败时清空数据不保留旧帧 |
| 3 | 灾害预测 500 | `ForecastResult` 只有 `deviceCode()` 等 record 风格访问器，Jackson 找不到 getter → `No serializer found` | 补 7 个标准 getter（`getXxx` / `isDegraded`），保留原访问器与 `@JsonAlias` |
| 4 | admin 访问气象分析 403 | 前端权限矩阵 + 后端 `PermissionRegistry` 漏 `weather:view`（刷新按钮还需 `weather:refresh`） | 前端 admin 补两码 → 重新运行 `sync-permission-registry.mjs` 生成后端（admin 123→125 项）；`data.sql` 已有权限定义且 admin 走「超管全量」 |
| 5 | AI 模型页 drift 5xx / 显示 0 | 回退路径拼成 `/api/ai/drift/drift/detect`；后端对 `/api/ai/drift/**` 显式 501；`loadKpi()` catch 静默写 0 | 抽出 `proxyPathFor(path) = '/ai'+path`；501 → `DRIFT_NOT_IMPLEMENTED` 语义错误 → 页面显示「漂移监控能力未接入」；KPI 失败显示「未接入」而非 0 |

### P1（试运行前，本轮做了可编码部分）

| # | 处理 |
|---|---|
| 6 | `WarningsPage` 两张永远为 `—` 的卡片改为显示「未接入」，note 写明「通知送达/联动部门统计未接入」，并加 `title` 说明 |
| 7 | `useHazardList.js` 加载后 `drawerOpen = false`；`selected` 仍保留默认高亮，详情由点击行打开 |
| 8 | 新增 `scripts/delivery/refresh-demo-data.sql`（只 UPDATE、不 INSERT/DELETE，幂等）：把 6/16 台设备置在线、未闭环预警与灾害点时间刷到今天。**仅在隔离演练库验证通过，未在开发库/任何真实环境执行**；`hazard_point` 是 `unified_event` 的视图，脚本更新基表 |
| 9 | `AiComprehensiveTab.vue` 加载态增加「已等待 N 秒」与 ≥8 秒的渐进提示（不改 API 契约、不中断请求） |
| 10 | 外部地图图片 ORB / NSMC 天气源超时：属外部依赖边界，页面已有逐产品失败文案（`no available slot … HTTP 404`）且地图仍可渲染，本轮不代理外链，已登记 `KNOWN_LIMITATIONS` L-20 |

## 三、回归验证（真实接口 + 真实浏览器）

隔离环境：`compose.delivery-verify.yml` 独立库（从开发库 dump 恢复）→ 新 jar 后端 `28084`
（`AI_ENABLED=true`）→ ai-service `28081` → nginx(dist) `25173` → Edge headless CDP `9222`。
全程只使用编排文件内标注「仅本机隔离演练」的口令，未读取 `.env`。

| 脚本 | 结果 | 关键断言 |
|---|---|---|
| `verify-review-api-fixes.mjs` | **12/12 PASS** | device-map 200 / 16 台 / 统计自洽 / 无 `GPS-001` 等 mock；forecast GET+POST 200 且字段完整；气象接口非 403；drift 保持显式 501 + `deprecated:true` |
| `verify-review-browser-fixes.mjs` | **14/14 PASS** | 7 个页面（weather / device-map / disaster-forecast / ai-models / warnings / hazards / ai-insight）：无 403、无 mock 设备、无演示横幅、无意外 5xx、无未捕获异常、无横向溢出；drift 请求实测落到 `/api/ai/drift/detect` 与 `/api/ai/drift/trend/...`（无 `/drift/drift`） |

证据：`output/delivery-verify/review-fixes-api.json`、`review-fixes-browser.json`，
截图 `output/review-fixes/review-fix-*.png`。

**修复后设备地图与总览页一致**：真实库 16 台设备、设备名 `DV-001 嘉陵江水位计-01` 等；
演示数据脚本在隔离库验证后为 6 在线 / 10 离线，接口统计同步变化，不再出现「12 台 / 10 在线」的矛盾。

### 门禁

| 项 | 结果 |
|---|---|
| 前端 | eslint 0 / stylelint 全量 0 / vitest **77 passed**（8 文件）/ SFC 98 / 令牌完整性 0 / `vite build` 7.01s |
| 后端 | 全量 `mvn test`（Testcontainers）**149 tests / 0 failures / 0 errors**（原 141 + 本轮新增 8）；`mvn -q -DskipTests package` 成功（fat jar 127,456,012 B） |
| 新增单测 | `DeviceMapTimeConversionTest`(5)、`ForecastResultSerializationTest`(2)、`PermissionRegistryTest` 增 1、`deviceMapFallback.spec.js`(7)、`auth.spec.js` 增 1 |

## 四、诚实边界 / 仍需人工

- **drift / multimodal 后端代理仍是显式 501**（`AiDeprecatedGapController`），这是 `KNOWN_LIMITATIONS` L-01/L-08 登记的刻意缺口；本轮只做到「页面明确提示能力未接入」，没有把 501 包装成真实接口。ai-service 直连 `8001` 的 drift 端点本身可用（实测 200），生产形态要真正用上需补同源代理或开放直连，属二期。
- **演示数据刷新脚本（本轮状态）**：会改写业务时间戳与设备状态，本轮只在隔离库验证语法与效果，
  未在开发库执行。→ **后续已处理**：2026-11-14 复测轮次先全库备份再在开发演示库执行，
  并发现 5 分钟心跳窗口，详见 [RETEST_FIX_REPORT.md](RETEST_FIX_REPORT.md) §二.2。
- 外部 NSMC/NMC 天气源、地图瓦片外链仍有 `ERR_BLOCKED_BY_ORB` / 超时；页面已逐项显示失败原因，不影响核心闭环。
- A1 纯人工项（人耳听声、真机、完整彩排计时、真实影像凭证）、A4 上线日注入、A6-3/A6-4 甲方决策、B6-1 / B7-1 / 状态机 409 契约 / Vite 升级评估与 P2 二期能力保持不变，见 `DELIVERY_BACKLOG.md` D 节与 `ONSITE_ACCEPTANCE_CHECKLIST.md`。
