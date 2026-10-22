# 2026-10-19 阶段一：盘点与诊断

## 快速命令

无需启动任何服务，直接运行这些命令来扫描问题：

### 1) 前端 ↔ 后端 API 对账（找孤儿接口）
```bash
node scripts/audit/check-endpoint-parity.mjs
```

### 2) 前端 URL 端点追踪
```bash
node scripts/audit/check-frontend-endpoints.mjs
```

### 3) 前端 import 完整性检查
```bash
node scripts/audit/check-frontend-imports.mjs
```

### 4) 缺失 import 检测
```bash
node scripts/audit/check-missing-imports.mjs
```

### 5) 空功能/Mock 残留扫描
```bash
python scripts/audit/empty_feats.py
```

### 6) 路由全量巡检（需启动前端 dev server）
```bash
# 先在另一个终端启动
npm run dev

# 然后运行
node scripts/audit/patrol-all-routes.mjs
```

---

## 2026-10-19 发现问题清单

### 问题统计

| 类型 | 数量 | 严重度 | 状态 |
|-----|------|--------|------|
| 孤儿接口（后端声明但前端未用） | 5 | 中 | 待处理 |
| HTTP 方法不匹配 | 2 | 中 | 待处理 |
| 已知缺口（基线登记） | 2 | 低 | 已知 |

**总计**：7 条问题

---

### 一、孤儿接口（前端调用但后端缺实现）

| HTTP 方法 | API 路径 | 前端位置 | 优先级 | 状态 |
|----------|---------|--------|-------|------|
| POST | `/api/emergency/{id}/dispatch` | `dashboardApi.js` | P1 | ❌ 待修 |
| POST | `/api/notice-templates/{id}/test` | `dashboardApi.js` | P1 | ❌ 待修 |
| POST | `/api/plans/{id}/launch` | `dashboardApi.js` | P1 | ❌ 待修 |
| POST | `/api/users/{id}/reset-password` | `dashboardApi.js` | P1 | ❌ 待修 |
| PUT | `/api/datasources/{id}` | `dashboardApi.js` | P1 | ❌ 待修 |

**建议**：逐个确认后端是否遗漏实现，或改为注释掉前端调用

---

### 二、HTTP 方法不匹配

| API 路径 | 前端用 | 后端有 | 前端位置 | 状态 |
|---------|------|--------|--------|------|
| `/api/catalog/assets` | POST | GET | `dashboardApi.js` | ❌ 待协调 |
| `/api/datasources` | POST | GET | `dashboardApi.js` | ❌ 待协调 |

**建议**：确认接口用途后修正 HTTP 方法

---

### 三、已知缺口（基线登记，不阻断 CI）

| API 路径 | 引用页面 | 说明 | 记录日期 |
|---------|--------|------|---------|
| `/api/ai/drift` | ModelManagePage | 漂移监控接口 | 2026-09-21 |
| `/api/ai/multimodal` | AiInsightPage | 多模态分析接口 | 2026-09-21 |

**处理**：修复后从脚本 KNOWN_GAPS 删除

---

## 后续行动

### 第一步：确认问题（今日）

- [x] 扫描孤儿接口 ✅
- [x] 检测 API 对账不匹配 ✅
- [ ] 跑 `patrol-all-routes.mjs` 检查路由错误（需 dev server）
- [ ] 收集其他死代码线索（TODO/FIXME/console.log）

### 第二步：修复优先级排序（明日）

1. **P3-2a ~ P3-2e**：修复 5 个孤儿接口（后端实现或前端移除）
2. **P3-3a ~ P3-3b**：修复 2 个方法不匹配
3. **P3-4 ~ P3-6**：清理其他死代码

### 第三步：分批执行修复（本周）

每个修复都应按 `REFACTOR_TASKS.md` 规范：
1. 输出执行计划（涉及文件 + 修改方式 + 影响面）
2. 等待确认
3. 执行修改
4. 验证（grep 旧路径 + npm build + mvn compile）

---

## 下一次审计

在执行修复后，重新运行命令验证：

```bash
# 一遍过所有无依赖的脚本
node scripts/audit/check-endpoint-parity.mjs && \
  node scripts/audit/check-frontend-endpoints.mjs && \
  node scripts/audit/check-frontend-imports.mjs && \
  node scripts/audit/check-missing-imports.mjs && \
  python scripts/audit/empty_feats.py

echo "✅ 所有审计通过"
```

---

## 注

- `orphan_endpoint_scan.py` 因编码问题暂时无法运行，但 `check-endpoint-parity.mjs` 已提供同样功能
- 所有发现的问题和建议已记录到此文件，按优先级处理即可
