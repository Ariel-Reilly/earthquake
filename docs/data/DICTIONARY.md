# 数据字典（DICTIONARY）

> 最后更新：2026-11-14 ｜ 状态：**骨架，待补**
> 原则：枚举 / 状态 / 等级以**代码与数据库为准**；本文只做索引，不复制第二份真相。

## 待补清单

| 类别 | 位置（以代码为准） | 状态 |
|---|---|---|
| 预警状态 / 等级 | 后端 `warning` 域枚举 + `docs/UNIFIED_EVENT_STATEMACHINE.md` | 待补 |
| 设备在线状态 | `frontend/src/services/_architecture/domain/deviceMapService.js` 的 `statusColor` / 后端设备枚举 | 待补 |
| 灾害类型 / 风险等级 | 后端 `hazard` 域 | 待补 |
| 区域 / 行政区划 | `dashboard` 区域配置 | 待补 |
| AI 生成方式（`generatedBy`） | 后端 `ai` 域（rule / llm / fallback） | 待补 |

> 说明：补全前，任何枚举解释以对应代码定义为准；本文件**不臆造取值**。
