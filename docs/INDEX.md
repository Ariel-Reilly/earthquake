# 文档索引
> 最后更新：2026-11-08
> 本页只做入口与指向，不复述各文档内容；端口/环境以根 `README.md` 为权威源。

## 1. 总入口与一键启动

**说明**：从零跑起来的最短路径，只给权威入口，不给命令。

**链接清单**

| 路径 | 定位 |
|---|---|
| `README.md` | 根总入口；快速启动、一键全栈、端口约定、项目结构、文档导航均在此。 |
| `DEPLOYMENT.md` | 环境变量与配置加载机制、部署步骤、故障排查手册。 |
| `docs/ONBOARDING/README.md` | 新人新入口：五分钟上手与任务路由。 |
| `docs/DEVELOPER.md` | 开发环境准备与技术栈的分章细目。 |

**分工边界**：本节只放一次启动的入口，不复制任何命令、端口或变量；端口与环境归第 5 节，AI 服务启动归第 3 节对应自述，演示启动归第 8 节，角色阅读链归第 2 节。

---

## 2. 角色场景路由（新人 / 开发 / 运维 / 演示）

**说明**：按四类角色给最短文档链，任务路由一律以 ONBOARDING 为准，本节只做映射。

**链接清单**

| 路径 | 定位 |
|---|---|
| `docs/ONBOARDING/README.md` | 任务路由与文档地图的权威源（新人起点）。 |
| `docs/ONBOARDING/MODULES.md` | 模块职责一览（新人 / 开发定位用）。 |
| `docs/ONBOARDING/DATA_FLOW.md` | 端到端数据流（新人 / 调试用）。 |
| `docs/ONBOARDING/DECISIONS.md` | 关键决策、已知坑与已取消任务依据。 |
| `docs/ARCHITECTURE.md` | 系统分层、模块依赖与关键时序（新人 / 开发）。 |
| `docs/DEVELOPER.md` | 前端与后端分章开发指南（开发）。 |
| `docs/FRONTEND_API.md` | 前端功能与后端接口的对照（开发）。 |
| `API_REFERENCE.md` | 核心接口的请求与响应格式（开发 / 联调）。 |
| `DEPLOYMENT.md` | 部署步骤与故障排查（运维）。 |
| `docs/CDSE_SETUP.md` | 卫星合成与影像接入配置（运维 / 部署）。 |
| `docs/demo/沙盘演示执行手册.md` | 现场演示的执行手册（演示）。 |

**分工边界**：不重复 `docs/README.md` 的阅读顺序，只给四行角色映射并指向 ONBOARDING；docs/ 全量地图归第 4 节，维护规则归第 9 节，一键启动归第 1 节。

---

## 3. 子项目入口（README 清单与回退目标）

**说明**：目录级一行索引，十三个 README 只列路径与一句话，另给四个无 README 目录的回退目标。

**链接清单**

| 路径 | 定位 |
|---|---|
| `README.md` | 根总入口：启动、端口、结构、导航。 |
| `ai-service/README.md` | AI 推理服务总说明与目录结构。 |
| `ai-service/datasets/README.md` | 数据集版本一览、来源与许可、重建与校验。 |
| `ai-service/models/README.md` | 模型资产清单、权重使用范围与指标说明。 |
| `ai-service/scripts/README.md` | AI 侧脚本的分类与运行约定。 |
| `ai-service/scripts/training/README.md` | 训练脚本版本与样本来源如实标注。 |
| `docs/ONBOARDING/README.md` | 新人入口：上手、任务路由、文档地图。 |
| `docs/README.md` | 核心指南清单、阅读顺序、维护原则、历史清理。 |
| `frontend/design-system/disasterguard/README.md` | 设计系统令牌与配套指南。 |
| `frontend/src/services/README.md` | 服务层架构、导入约定、例外与决策。 |
| `hardware-sandbox/README.md` | 混合沙盘方案与不需硬件的上手路径。 |
| `hardware-sandbox/examples/revit_model/README.md` | 真实地形 Revit 模型构成与来源说明。 |
| `scripts/README.md` | 顶层运维、审计、修复、探针脚本分类。 |
| `backend/` | 无 README，回退到 `docs/DEVELOPER.md` §6（后端开发指南）+ `docs/ONBOARDING/MODULES.md`（模块职责）。 |
| `frontend/` | 无 README，回退到 `docs/DEVELOPER.md` §5（前端开发指南）+ `frontend/src/services/README.md`（服务层约定）。 |
| `infra/` | 无 README，回退到 `DEPLOYMENT.md` + `README.md` 的项目结构节。 |
| `docs/demo/` | 无 README，回退到 `docs/demo/沙盘演示执行手册.md`（手册本身）与 `docs/demo/D1_ESP32接线表_2节点版.md`（接线附件）。 |

**分工边界**：只给路径与一句话，不复制各 README 的启动步骤或表格；docs/ 内部分区归第 4 节，AI 资产细目归第 6 节，脚本细目归第 7 节，演示与硬件归第 8 节。

---

## 4. `docs/` 文档地图

**说明**：按分区给 docs/ 的入口；评审、审计、归档只给目录级一句说明，不逐份列。

**链接清单**

| 路径 | 定位 |
|---|---|
| `docs/README.md` | 核心指南清单、阅读顺序、维护原则、历史清理记录。 |
| `docs/ONBOARDING/README.md` | 新入口与任务路由（任务路由以此为准）。 |
| `docs/ONBOARDING/MODULES.md` | 模块职责一览。 |
| `docs/ONBOARDING/DATA_FLOW.md` | 端到端数据流。 |
| `docs/ONBOARDING/DECISIONS.md` | 关键决策记录与已取消任务依据。 |
| `docs/ARCHITECTURE.md` | 系统分层与架构总览。 |
| `docs/DEVELOPER.md` | 深度开发指南（分章）。 |
| `docs/FRONTEND_API.md` | 前端功能与接口对照。 |
| `docs/UNIFIED_EVENT_STATEMACHINE.md` | 统一事件状态机的状态与转移规则。 |
| `docs/CDSE_SETUP.md` | 卫星数据接入配置。 |
| `docs/HARDWARE_SANDBOX.md` | 硬件沙箱总说明与沙盘加工方案。 |
| `docs/项目结构梳理报告.md` | 项目结构专项梳理。 |
| `docs/guides/AI_MODULE_IMPROVEMENT_20260929.md` | 操作与改进类指南（该分区唯一一份）。 |
| `docs/reviews/` | 代码评审记录分区（目录级，历史记录）。 |
| `docs/reviews/CODE_REVIEW.md` | 评审分区的代表入口。 |
| `docs/audit/frontend/` | 前端审计报告分区（目录级，历史记录）。 |
| `docs/audit/frontend/UX_Audit_Report.md` | 审计分区的代表入口。 |
| `docs/demo/` | 演示手册分区，实体清单转第 8 节。 |
| `docs/archive/` | 归档区（历史记录，不作为入口），只登记目录，不逐份列、不链接到具体报告。 |

**分工边界**：不复制 `docs/README.md` 的阅读顺序，不给归档与评审逐份链接；目录级子项目索引归第 3 节，演示实体归第 8 节，维护契约归第 9 节。

---

## 5. 环境与端口单一表（以根 README 为权威源）

**说明**：只声明权威源位置，本页不保存任何端口、变量或表格副本。

**链接清单**

| 路径 | 定位 |
|---|---|
| `README.md` | 端口约定的唯一权威表（端口约定节）。 |
| `README.md` | 快速启动节：服务启动顺序的唯一权威说明。 |
| `DEPLOYMENT.md` | 环境变量与配置加载机制的唯一权威清单。 |
| `scripts/README.md` | 端口、进程、健康探针脚本的分类入口（转第 7 节）。 |

**分工边界**：不写任何端口号、不抄变量清单、不复制表格；探针脚本的逐项说明只在第 7 节对应自述，AI 侧环境配置不在此重复。

---

## 6. AI 资产与数据 / 许可

**说明**：模型、数据集、数据卡与使用边界集中一处，指标与许可细节只在目标文档中维护。

**链接清单**

| 路径 | 定位 |
|---|---|
| `ai-service/models/README.md` | 模型资产、权重使用范围、实测指标与训练复现。 |
| `ai-service/datasets/README.md` | 数据集版本一览、来源与许可、重建与校验。 |
| `ai-service/datasets/disaster-v1/DATASET_CARD.md` | 旧版对比基线的数据卡。 |
| `ai-service/datasets/disaster-v2.inpaint/DATASET_CARD.md` | 已归档构建的数据卡。 |
| `ai-service/datasets/disaster-v2/DATASET_CARD.md` | 当前训练用数据卡，含来源与许可边界。 |
| `ai-service/scripts/training/README.md` | 训练脚本版本与样本来源的如实标注。 |
| `docs/CDSE_SETUP.md` | 卫星影像接入与回退链路配置。 |
| `DEPLOYMENT.md` | 当前阶段使用边界（被模型文档引用）。 |

**分工边界**：不复述指标数值、类别数或许可证全文（避免二次维护）；脚本运行细节归第 7 节，影像接入的配置步骤只在目标文档，第 3 节只保留目录级一句话。

---

## 7. 脚本与工具

**说明**：只给工具分类入口，不搬运逐份脚本清单。

**链接清单**

| 路径 | 定位 |
|---|---|
| `scripts/README.md` | 顶层脚本四大分类与前端脚本分工。 |
| `ai-service/scripts/README.md` | AI 侧脚本四类与入口约束说明。 |
| `ai-service/scripts/training/README.md` | 训练脚本版本表与样本来源标注。 |

**分工边界**：AI 资产与许可归第 6 节，演示用脚本归第 8 节；逐份脚本的用途、命令与注意事项只在各自自述中维护。

---

## 8. 演示 / 沙盘 / 硬件

**说明**：现场演示与沙盘方案的唯一入口区，手册与方案只做指向、不复制内容。

**链接清单**

| 路径 | 定位 |
|---|---|
| `docs/demo/沙盘演示执行手册.md` | 现场演示执行手册（`docs/demo/` 的回退目标）。 |
| `docs/demo/D1_ESP32接线表_2节点版.md` | 接线附件，现场单独打印。 |
| `docs/HARDWARE_SANDBOX.md` | 硬件沙盘总说明；沙盘加工方案与硬件在环描述的权威文档。 |
| `hardware-sandbox/README.md` | 混合沙盘方案、不需硬件的上手路径与工具链。 |
| `hardware-sandbox/examples/revit_model/README.md` | 真实地形 Revit 模型示例说明。 |
| `frontend/design-system/disasterguard/README.md` | 演示大屏的视觉规范入口。 |

**分工边界**：不复制彩排命令、接线表、物料清单、方案细节；`docs/demo/` 与沙盘方案按约定只指向不改动，脚本入口归第 7 节，docs/ 分区归第 4 节。

---

## 9. 维护契约

**说明**：明确两页分工、日期标注规则，并只登记已知失效引用与待调查项，不在本页修复。

**链接清单**

| 路径 | 定位 |
|---|---|
| `docs/README.md` | 分工说明、核心指南清单、阅读顺序、文档维护原则与历史清理记录。 |
| `docs/ONBOARDING/README.md` | 新入口：上手、任务路由与文档地图；维护原则见其维护节。 |
| `docs/ONBOARDING/DECISIONS.md` | 关键决策记录与已取消任务的决策依据。 |

**待调查清单（5 类，统一标注：已登记待调查，本轮不修）**

| 路径 | 问题 | 状态 |
|---|---|---|
| `docs/ONBOARDING/README.md` | 遇到问题节指向 `.cursorrules` 与 `CONTRIBUTING.md`，两者均不存在。 | 已登记待调查，本轮不修 |
| `frontend/src/services/README.md` | 例外列表中的 `DashboardLayout.vue` 引用悬空。 | 已登记待调查，本轮不修 |
| `docs/ONBOARDING/DECISIONS.md` | 多处指向已不存在的 `REFACTOR_TASKS.md`。 | 已登记待调查，本轮不修 |
| `ai-service/web.py` | 上传区 UI 文案仍称 COCO 类别，与当前两类模型不一致。 | 已登记待调查，本轮不修 |
| `ai-service/datasets/README.md` | 目录树的 `validation.json` 与校验命令中 `samples` 路径不一致，待核实。 | 已登记待调查，本轮不修 |

**日期标注规则**：引用 `docs/README.md` 维护原则中的日期标注约定，每个文档顶部保留「最后更新：YYYY-MM-DD」，随每次改动更新；本页同样遵守。

**分工边界**：任务路由一律以 ONBOARDING 为准，本页不产出第二个入口、不重复维护核心指南清单；上述 5 类只登记状态，不修改被引用的源文件，也不在本页给出修复结论。
