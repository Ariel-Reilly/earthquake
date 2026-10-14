# DisasterGuard 目录重构计划

> 生成日期：2026-10-14
> 基线 commit：925e9a2
> 重构分支：refactor/2026-10-14

## 背景

对 ai-service / backend / frontend / infra 四个模块做内部文件组织重构。
详细分析见原始报告。本文件为 Cursor 提供上下文引用。

## 关键发现

### ai-service
- 训练代码散落 scripts/ 根目录
- 模型备份文件缺少版本管理
- scripts/ 下有测试文件与 tests/ 重复

### backend
- DTO 散落在包根，未归入 dto/ 子包
- scheduler 分散在 scheduler/、service/、repository/

### frontend
- services/ 根下薄层 re-export 文件造成导入路径混乱
- 16 个组件散落 components/ 根
- 缺少 assets/constants/types 标准目录

### infra / 根目录
- docs/ 有中文报告未归档
- scripts/ 组织良好（保留）

## 执行原则

详见 @.cursor/rules/refactor.mdc 与 @REFACTOR_TASKS.md
