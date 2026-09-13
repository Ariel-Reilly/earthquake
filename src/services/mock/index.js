/**
 * 离线 mock 数据统一聚合入口。
 *
 * dashboardApi.js 应只 import 这个文件，按业务域访问：
 *
 *   import * as mock from './mock';
 *
 *   mock.seed.SEED_BASE.hazardPoints
 *   mock.empty.paged(20)
 *   mock.ai.getTaskRuntimeMock(id)
 *   mock.analytics.getAnalyticsMock()
 *   mock.workflow.getConfirmationQueueMock()
 *   mock.operations.getResourcesMock()
 *   mock.system.getUsersMock()
 *   mock.runtime.planRouteMock(payload)
 *
 * 设计原则：
 * 1. 各子模块互不依赖（除 seed）—— 方便独立替换或迁移。
 * 2. 不在 mock 层做任何 fetch / 业务判空 —— 那是 dashboardApi.js 的职责。
 * 3. 命名约定：xxxMock 函数返回纯数据；xxxMock 常量是预生成的静态值。
 */

export * as seed from "./seed";
export * as empty from "./empty";
export * as ai from "./ai";
export * as analytics from "./analytics";
export * as workflow from "./workflow";
export * as operations from "./operations";
export * as system from "./system";
export * as runtime from "./runtime";
