# frontend/src/services/ 架构说明

## 分层

    services/
    ├── aiApi.js                       ← 薄层：export * from '_architecture/domain/aiApi.js'
    ├── mapService.js                  ← 薄层
    ├── dashboardApi.js                ← 真实实现（无对应 _architecture 文件）
    └── _architecture/                 ← 内部实现
        ├── core/                      ← 跨模块基础工具
        │   ├── httpClient.js
        │   ├── healthService.js
        │   ├── geocodeService.js
        │   ├── realtimeService.js
        │   ├── mapService.js
        │   └── sentinelService.js
        └── domain/                    ← 按业务领域
            ├── aiApi.js
            ├── notificationService.js
            └── ...

## 导入约定

**业务代码**走薄层：

    import { aiApi } from '@/services/aiApi'

**service 内部互相引用**走深路径：

    import { request } from './_architecture/core/httpClient'

## 为什么保留薄层

1. **路径简洁**：`@/services/aiApi` 比 `@/services/_architecture/domain/aiApi` 短
2. **重构友好**：内部实现换位置时，业务代码不用改
3. **向后兼容**：`realtimeService.js` 显式注释"Kept so legacy views... continue to work"

## 例外

以下文件直接引用 `_architecture/` 下服务，因**无对应薄层**：

- `ChannelMatrix.vue` → `notificationService.js`
- `DeliveryReceipt.vue` → `notificationService.js`
- `ServiceHealthBanner.vue` → `healthService.js`
- `useOverviewSearch.js` → `geocodeService.js`
- `DashboardLayout.vue` → `healthService`

该统一方案（P2-11）已评估并取消：无薄层服务引用面仅 7 处，收益 < 成本；结论见 `docs/ONBOARDING/DECISIONS.md`「5 个取消任务的决策依据」。

## 决策记录

**P1-3（2026-10-14）**：曾计划删除薄层 + 重命名 `_architecture/`，
只读验证后取消。原因：薄层引用 33 处 vs 深路径 6 处，薄层有效；
删后路径变长；5 个深路径引用者无薄层可走。

`_architecture` 前缀下划线表示"内部实现，业务代码不应直接依赖"。
未来统一重命名（如 `_internal/`）属于 P2 范畴（影响 17 个文件）。
