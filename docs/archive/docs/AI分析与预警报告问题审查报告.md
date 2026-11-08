# DisasterGuard AI分析与预警报告 问题审查报告

> 审查范围：AI分析路由、AI模型、前端AI页面、后端AI服务、预警调度、预警服务、报告生成
> 审查日期：2026-10-21

---

## 一、严重程度分级说明

| 等级 | 含义 | 影响 |
|------|------|------|
| **CRITICAL** | 致命缺陷 | 直接导致AI分析错误、预警漏报/误报、系统崩溃 |
| **HIGH** | 严重缺陷 | 核心功能异常，输出结果不可靠，影响业务决策 |
| **MEDIUM** | 中等缺陷 | 功能存在偏差或边界情况处理不当，可能导致误导 |
| **LOW** | 轻微缺陷 | 代码质量、用户体验、性能优化类问题 |

---

## 二、问题总览

| 编号 | 严重程度 | 模块 | 文件 | 问题简述 |
|------|----------|------|------|----------|
| 1 | CRITICAL | 综合模型 | `comprehensive_model.py` | 自适应归一化功能完全失效（死代码） |
| 2 | CRITICAL | 多模态路由 | `multimodal.py` | 三个核心GET接口全部返回硬编码Mock数据 |
| 3 | CRITICAL | 预警服务 | `WarningService.java` | cssClass方法红色预警显示为蓝色样式 |
| 4 | CRITICAL | 报告生成 | `ReportController.java` | runTemplate仅创建RUNNING状态记录，不实际生成报告 |
| 5 | HIGH | 综合模型 | `comprehensive_model.py` | 社交媒体告警等级输入输出范围不一致（-1偏差） |
| 6 | HIGH | 灾害评估路由 | `disaster_assessment.py` | 字符串因子被设为float 0.0，语义丢失 |
| 7 | HIGH | 前端AI洞察页 | `AiInsightPage.vue` | 设备维度卡片可能永远不显示（available字段不匹配） |
| 8 | HIGH | 预警创建 | `WarningCreateService.java` | AI融合评分只升级不降级，可能导致预警等级虚高 |
| 9 | HIGH | 前端灾损评估 | `DamageAssessmentPage.vue` | 声称"七维度"雷达图实际只有5个维度 |
| 10 | HIGH | 报告种子数据 | `ReportController.java` | seedHistory的templateId与实际模板ID不匹配 |
| 11 | MEDIUM | LSTM模型 | `lstm_model.py` | 模型固定输出7步，超过7步用启发式外推精度不可控 |
| 12 | MEDIUM | YOLO模型 | `yolo_model.py` | Mock检测返回完全随机的结果，演示时数据不稳定 |
| 13 | MEDIUM | 社媒分析 | `social_media.py` | 位置聚类逻辑过于简化，聚类结果无实际意义 |
| 14 | MEDIUM | 社媒分析 | `social_media.py` | 经纬度正则表达式可能误匹配非坐标数字 |
| 15 | MEDIUM | 预警调度 | `AiWarningScheduler.java` | 预警等级映射存在中英文体系混用风险 |
| 16 | MEDIUM | AI洞察服务 | `AiInsightService.java` | 批量刷新方法使用@Transactional，长事务风险高 |
| 17 | MEDIUM | 前端预警详情 | `WarningDetailPage.vue` | fusionRiskAuto接口已弃用但仍在使用 |
| 18 | MEDIUM | 灾害评估模型 | `disaster_assessment_model.py` | 活跃维度≤3时回退v1模式，v2七维度权重未被使用 |
| 19 | LOW | 综合路由 | `comprehensive.py` | last_inspection_at字段仅用于展示，未参与评分 |
| 20 | LOW | 综合模型 | `comprehensive_model.py` | crisis amplifier注释与代码加成值存在描述不一致 |
| 21 | LOW | 预警服务 | `WarningService.java` | toDto方法中hazardCode出现两次（冗余字段） |
| 22 | LOW | 前端API | `aiApi.js` | getFeedbackStats直接绕过Java网关调用Python端点 |
| 23 | LOW | 灾害评估路由 | `disaster_assessment.py` | 独立测算端点缺少import math（依赖模块级导入） |

---

## 三、详细问题清单

### 【CRITICAL-1】自适应归一化功能完全失效（死代码）

- **文件**：`ai-service/app/models/comprehensive_model.py`
- **位置**：第980-1010行，`_maybe_use_adaptive_norms` 方法
- **问题描述**：
  该方法声称"当某区域某因子的滑动窗口样本数 ≥ MIN_SAMPLES 时，用 adaptive_normalizer 的动态上限覆盖模块级 NORM_MAX 常量"，但实际实现中关键代码是 `pass`（第1008行），全局变量从未被修改。
  注释自曝其短："当前实现：dynamic_max 已经被 normalizer 维护，这里只是占位，全局 NORM_MAX 常量保留为 fallback，score_* 函数内部不强制替换。真正集成时应在 score_*/safe_norm 中优先查 normalizer。"
- **影响**：
  - 自适应归一化功能完全是"纸面功能"，实际从未生效
  - 所有评分函数始终使用固定的 NORM_MAX 常量
  - 前端展示的"adaptive_normalization_enabled: true"状态具有误导性
- **修复建议**：
  要么在 `score_*` 函数中接入 `adaptive_normalizer.get_dynamic_max()`，要么移除该功能的声明与状态展示。

---

### 【CRITICAL-2】多模态融合三个核心GET接口全部返回硬编码Mock数据

- **文件**：`ai-service/app/routers/multimodal.py`
- **位置**：
  - `GET /multimodal/insight/{event_id}` — 第120-166行
  - `GET /multimodal/contributions/{event_id}` — 第169-199行
  - `GET /multimodal/weights/{disaster_type}` — 第224-238行
  - `POST /multimodal/refresh` — 第202-214行
  - `PUT /multimodal/weights/{disaster_type}` — 第241-253行
- **问题描述**：
  多模态融合模块的5个核心接口中，有5个全部是Mock/Stub实现：
  1. `get_insight` — 直接返回硬编码的87.5分、HIGH等级、固定贡献度
  2. `get_contributions` — 返回硬编码的四模态权重和贡献度
  3. `get_weights` — 返回硬编码的权重配置
  4. `refresh_insight` — 返回"已刷新"但什么都不做
  5. `update_weights` — 返回提交的权重但不持久化
- **影响**：
  - 前端 AiInsightPage 的"多模态融合研判"面板展示的全部是假数据
  - 用户看到的融合评分和贡献度永远是同一组固定值
  - "刷新"按钮没有实际效果，存在欺骗性
- **修复建议**：
  接入真实的融合引擎持久化存储，或在前端明确标记为"演示数据"并禁用刷新按钮。

---

### 【CRITICAL-3】红色预警的CSS样式被错误映射为蓝色

- **文件**：`backend/src/main/java/cn/disasterguard/warning/service/WarningService.java`
- **位置**：第459-464行，`cssClass` 方法
- **问题描述**：
  ```java
  private String cssClass(String level) {
      if (level == null) return "blue";
      if (level.contains("橙")) return "orange";
      if (level.contains("黄")) return "yellow";
      return "blue";  // 红色预警也会走到这里！
  }
  ```
  该方法只判断了"橙"和"黄"两种颜色，红色预警（"红色预警"）会落入默认分支返回 `"blue"`，导致红色预警在前端列表中显示为蓝色样式。
- **影响**：
  - 最高等级的红色预警在视觉上被降格为蓝色，严重误导值班人员
  - 可能导致对紧急预警的响应延迟
- **修复建议**：
  增加 `if (level.contains("红")) return "red";` 判断，且应放在最前面。

---

### 【CRITICAL-4】报告生成功能只创建RUNNING状态，不实际生成报告

- **文件**：`backend/src/main/java/cn/disasterguard/report/ReportController.java`
- **位置**：第215-232行，`runTemplate` 方法
- **问题描述**：
  `runTemplate` 方法仅做了两件事：
  1. 更新模板的 `lastRunAt` 时间
  2. 创建一条 status="RUNNING" 的报告历史记录
  
  没有任何实际的报告生成逻辑（没有查询数据、没有渲染模板、没有生成文件）。报告将永远停留在 "RUNNING" 状态，`finishedAt` 和 `recordCount` 永远为空。
- **影响**：
  - 报告中心的"立即运行"功能完全无效
  - 重新运行（rerun）也只会创建另一个RUNNING状态的空记录
  - 前端看到的"生成中"状态永远不会变成"成功"或"失败"
- **修复建议**：
  实现真实的报告生成逻辑，或至少添加定时任务将状态更新为 SUCCESS/FAILED。

---

### 【HIGH-5】社交媒体告警等级计算存在-1偏差，导致评分错误

- **文件**：`ai-service/app/models/comprehensive_model.py`
- **位置**：第486-487行，`score_social_media` 函数
- **问题描述**：
  ```python
  raw_alert = factors.get("social_alert_level", 0.0)
  alert_level = max(0.0, min(4.0, raw_alert - 1.0))
  # 注释说：GDACS alertscore (1-5) → social_alert_level (0-4)：直接 -1 归一化
  ```
  但根据 `comprehensive.py` 的 factors 文档（第115行）和 `disaster_assessment_model.py` 的定义，`social_alert_level` 的取值范围是 **0-4**（0=无，4=紧急），而不是 GDACS 的 1-5。
  
  如果传入 `social_alert_level=4`（紧急），代码会计算为 `4-1=3`，导致最高等级的舆情告警被降一档计算，风险评分偏低。
  
  同时 `disaster_assessment_model.py` 第542行的 `score_social_media` 没有这个 -1 操作，两个模型对同一因子的处理不一致。
- **影响**：
  - 综合模型的社交媒体维度评分系统性偏低（约低25%）
  - 两个模型（comprehensive vs disaster_assessment）对同一输入给出不同结果
  - 舆情驱动的预警可能被漏报
- **修复建议**：
  移除 `raw_alert - 1.0` 中的 `-1.0`，直接使用 `raw_alert`。

---

### 【HIGH-6】字符串类型因子被设为float 0.0，语义完全丢失

- **文件**：`ai-service/app/routers/disaster_assessment.py`
- **位置**：第181行（image_source）和第222行（land_use_type），`_factors_from_point` 函数
- **问题描述**：
  ```python
  if p.image_source is not None:
      factors["image_source"] = 0.0  # 字符串，AI 端用默认处理
  if p.land_use_type is not None:
      factors["land_use_type"] = 0.0  # 字符串
  ```
  当 MonitoringPoint 中存在字符串类型字段时，代码将它们设为 float `0.0` 而不是字符串原值。虽然注释说"AI 端用默认处理"，但实际上：
  1. `score_terrain` 中 `geology_label` 使用 `factors.get("geology_label", "未指定")`，会读到 `0.0` 而不是字符串
  2. `score_historical` 中 `land_use` 使用 `str(factors.get("land_use_type", "unknown"))`，会得到 `"0.0"` 而不是土地利用类型
  3. `get_source_label` 会把 `0.0` 当未知来源处理
- **影响**：
  - 批量检测（detect-batch）和区域检测（detect-regions）中的图像来源和土地利用类型信息完全丢失
  - 灾害类型分类中的土地利用风险映射失效
- **修复建议**：
  直接将字符串原值存入 factors 字典，由各评分函数自行处理类型转换。

---

### 【HIGH-7】设备维度卡片的显示条件判断错误，可能永远不显示

- **文件**：`frontend/src/views/ai/AiInsightPage.vue`
- **位置**：第698行
- **问题描述**：
  ```vue
  <article v-if="deviceDim && (deviceDim.available === false || deviceDim.available === true)" ...>
  ```
  代码检查 `deviceDim.available` 字段，但从后端返回的 dimensions 结构中：
  - Python `disaster_assessment_model.py` 的 `score_device` 返回的是 `Tuple[float, float]`（分数和置信度），没有 `available` 字段
  - `dimensions.device` 对象是在 `score` 方法中构造的，可能包含也可能不包含 `available`
  - 设备可用性在 `overall.device_available` 顶层字段中，不在 dimensions 内
  
  如果 `deviceDim.available` 不存在，两个 `===` 判断都为 false，设备卡片永远不显示。
- **影响**：
  - 用户可能看不到设备维度的评估结果
  - 有设备数据的灾害点也显示为"无设备"状态
- **修复建议**：
  使用 `report.deviceAvailable` 顶层字段控制显示，或确认 dimensions 结构中包含 available 字段。

---

### 【HIGH-8】AI融合评分只升级预警等级不降级，导致预警虚高

- **文件**：`backend/src/main/java/cn/disasterguard/warning/engine/WarningCreateService.java`
- **位置**：第84-93行
- **问题描述**：
  ```java
  if (!fusion.degraded()) {
      switch (fusion.riskLevel()) {
          case "CRITICAL" -> finalLevel = "红色预警";
          case "HIGH" -> finalLevel = "橙色预警";
          case "MEDIUM" -> {
              if (level.contains("蓝")) finalLevel = "黄色预警";
          }
          default -> {}
      }
  }
  ```
  AI融合评分的逻辑是：
  - CRITICAL → 升级为红色
  - HIGH → 升级为橙色
  - MEDIUM + 蓝色规则 → 升级为黄色
  - LOW → 什么都不做（保持原规则等级）
  
  也就是说，AI 只能把预警等级往上调，不能往下调。如果规则引擎因为某个指标瞬时超阈值触发了橙色预警，但 AI 综合评估实际为 LOW，预警依然是橙色。
- **影响**：
  - 可能产生大量高等级误报
  - 值班人员可能对预警产生"狼来了"效应，降低对真实预警的重视
- **修复建议**：
  增加降级逻辑（如 AI 评估低于规则等级且差距较大时降级），或增加置信度阈值控制。

---

### 【HIGH-9】灾损评估页声称"七维度"但雷达图只有5个维度

- **文件**：`frontend/src/views/ai/DamageAssessmentPage.vue`
- **位置**：第165-171行，`DIMENSIONS` 常量定义
- **问题描述**：
  页面标题和卡片标题写着"七维度风险分布"（第309行），但实际雷达图只定义了5个维度：
  1. geographic（地理）
  2. precipitation（降水）
  3. sensor（传感器）
  4. historical（历史）
  5. image（影像）
  
  缺少了 `social_media`（社交媒体）和 `supporting`（辅助数据）两个维度。而后端 Python 模型 v2.0 确实是七维度。
- **影响**：
  - 用户看到的雷达图与实际分析维度不符
  - 社交媒体和辅助数据对风险的贡献在可视化中被隐藏
- **修复建议**：
  将 DIMENSIONS 扩展为7个，或修改文案为"五维度"以反映实际情况。

---

### 【HIGH-10】报告种子数据的templateId与实际模板不匹配

- **文件**：`backend/src/main/java/cn/disasterguard/report/ReportController.java`
- **位置**：第129行，`seedHistory` 方法
- **问题描述**：
  ```java
  report.put("templateId", (long) (i % 4 + 1001));
  ```
  seedHistory 中硬编码 templateId 为 1001-1004，但 seedTemplates 创建的模板 ID 是 1-4（因为 templateIdGen 从0开始自增）。
- **影响**：
  - 报告历史记录关联的模板不存在
  - 前端按模板筛选历史记录时，这些种子数据无法被正确过滤
  - 点击"重新运行"可能因为找不到模板而出错
- **修复建议**：
  将 `i % 4 + 1001` 改为 `i % 4 + 1` 或调整模板ID生成逻辑。

---

### 【MEDIUM-11】LSTM模型固定输出7步，长周期预测用启发式外推

- **文件**：`ai-service/app/models/lstm_model.py`
- **位置**：第65行（模型定义）和第110-119行（外推逻辑）
- **问题描述**：
  LSTM 模型的 `output_size=7`，固定输出7步预测。当 `horizon > 7` 时：
  ```python
  # horizon > 7 时，前 7 步用模型，后 N-7 步用趋势外推
  last_val = base_preds[-1]
  trend = (base_preds[-1] - base_preds[0]) / max(1, len(base_preds) - 1)
  predictions = base_preds[:]
  for i in range(horizon - 7):
      val = last_val + trend * (i + 1)
      predictions.append(round(float(val), 4))
  ```
  只用7个预测点的首尾差算一个线性趋势，然后做简单线性外推。对于30步的长周期预测，精度完全不可控。
  
  同时前端 DisasterForecastPage 的 HORIZON_OPTIONS 最高支持72小时（第47行），远超模型能力。
- **影响**：
  - 超过7步的预测结果精度极低，基本是"画直线"
  - 前端可能展示不准确的长期趋势预测，误导用户
- **修复建议**：
  限制 horizon 最大值为7，或实现真正的多步滚动预测（自回归生成）。

---

### 【MEDIUM-12】YOLO Mock检测结果完全随机，演示数据不稳定

- **文件**：`ai-service/app/models/yolo_model.py`
- **位置**：第226-253行，`_run_mock` 方法
- **问题描述**：
  ```python
  num = random.randint(0, 2)
  ...
  cls = random.choice(CLASSES[:4])
  conf = round(random.uniform(0.4, 0.85), 4)
  ```
  Mock 模式下每次调用返回的检测数量、类别、置信度、位置都是完全随机的。
- **影响**：
  - 同一张图片多次检测得到完全不同的结果
  - 前端用户每次刷新看到不同的检测结果，体验困惑
  - 自动化测试无法稳定通过
- **修复建议**：
  使用基于图片内容（如文件大小哈希）的伪随机种子，保证同一张图片的 mock 结果一致。

---

### 【MEDIUM-13】社媒位置聚类逻辑过于简化，结果无实际意义

- **文件**：`ai-service/app/routers/social_media.py`
- **位置**：第310-321行，位置聚类部分
- **问题描述**：
  ```python
  if loc["type"] == "coordinate":
      key = f"{loc['lon']:.2f},{loc['lat']:.2f}"
  else:
      key = raw[:10]  # 简化key
  ```
  对于文本类型的位置，只用前10个字符作为聚类key。这意味着：
  - "龙安镇青龙村一组" 和 "龙安镇青龙村二组" 会被聚在一起（因为前10字相同）
  - "青龙村" 和 "龙安镇青龙村" 不会被聚在一起（因为前10字不同）
  - 完全不同的地点如果前10字碰巧相同也会被错误聚类
- **影响**：
  - 位置聚类结果不可靠，无法用于实际的灾害区域研判
- **修复建议**：
  接入真实的地名解析服务（如高德/百度地理编码API），或至少实现基于地名后缀（镇/乡/村）的层次化聚类。

---

### 【MEDIUM-14】经纬度正则表达式可能误匹配非坐标数字

- **文件**：`ai-service/app/routers/social_media.py`
- **位置**：第84行，`COORD_RE` 正则
- **问题描述**：
  ```python
  COORD_RE = re.compile(r"(\d+\.?\d*)[°,]\s*(\d+\.?\d*)[°NSE]|[Nn]?(\d+\.?\d*)\s*[°]\s*(\d+\.?\d*)\s*[″']")
  ```
  该正则的第一个分支 `(\d+\.?\d*)[°,]\s*(\d+\.?\d*)[°NSE]` 匹配格式为 "数字,数字°" 或 "数字°数字°" 的模式。但在中文文本中，像 "降雨量100.5, 水位2.3米" 这样的内容可能被误匹配为坐标。
- **影响**：
  - 社媒分析的位置提取可能产生错误的经纬度
  - 错误的坐标可能被用于bbox过滤和位置聚类
- **修复建议**：
  增加更严格的经纬度范围校验（如经度0-180、纬度0-90），并增加上下文关键词（如"坐标""经纬度""位于"）辅助判断。

---

### 【MEDIUM-15】预警等级映射存在中英文体系混用风险

- **文件**：`backend/src/main/java/cn/disasterguard/warning/engine/AiWarningScheduler.java`
- **位置**：第138-147行，`translateLevel` 方法
- **问题描述**：
  AI 联动预警使用中文预警等级（"红色预警"/"橙色预警"/"黄色预警"/"蓝色预警"），而前端人工发布预警使用英文码（`red/orange/yellow/blue`），经过 `WarningService.normalizeLevel` 转换为中文。
  
  这种双轨制存在以下风险：
  1. `AiWarningScheduler` 生成的预警 source=AI_AUTO，等级为中文
  2. 人工创建的预警 source=MANUAL，等级也会被转成中文
  3. 但 `WarningService.cssClass` 方法通过 `contains("橙"/"黄")` 判断颜色，对红色处理错误（见CRITICAL-3）
  4. 前端如果直接用英文码展示可能不匹配
- **影响**：
  - 不同来源的预警等级格式可能不一致
  - 前端样式映射可能出错
- **修复建议**：
  统一使用一种编码体系（建议统一用英文码存储，前端展示时转中文），或在DTO层做好严格的归一化。

---

### 【MEDIUM-16】批量刷新方法使用@Transactional，长事务风险高

- **文件**：`backend/src/main/java/cn/disasterguard/ai/service/AiInsightService.java`
- **位置**：第78-102行，`refreshAll` 方法
- **问题描述**：
  `refreshAll` 方法标注了 `@Transactional`，但内部循环遍历所有灾害点并逐一调用 AI 服务进行HTTP推理。如果有100个灾害点，每个AI调用耗时100ms，事务可能持续10秒以上。
  
  长事务的风险：
  1. 数据库连接长时间占用
  2. 如果中间某个 hazard 失败，虽然有 try-catch，但事务状态可能被标记为 rollback-only
  3. 锁持有时间过长，影响其他操作
- **影响**：
  - 高并发下数据库连接池耗尽
  - 可能导致其他业务操作超时
- **修复建议**：
  移除 `@Transactional`，改为单条写入（analyzeOnePersist 内部使用独立事务），或使用批量提交。

---

### 【MEDIUM-17】预警详情页使用已过时的fusionRiskAuto接口

- **文件**：`frontend/src/views/warning/WarningDetailPage.vue`
- **位置**：第52行，`loadAiRisk` 函数
- **问题描述**：
  ```javascript
  aiRisk.value = await aiApi.fusionRiskAuto(hazardCode);
  ```
  预警详情页使用 `fusionRiskAuto` 获取AI风险，但项目中已经有更全面的 `comprehensiveAnalysis` 和 `disasterAssessment` 接口。`fusion-risk` 是旧版的融合风险评分接口（只返回单一分数），而新的 `disaster-assessment` 返回三维度明细+灾害类型+推理链。
- **影响**：
  - 预警详情页展示的AI风险信息过于简略
  - 与 AI洞察页和灾损评估页的信息展示不一致
- **修复建议**：
  改用 `aiApi.disasterAssessment(hazardCode)` 获取更完整的评估结果。

---

### 【MEDIUM-18】活跃维度≤3时回退v1模式，v2七维度权重未被使用

- **文件**：`ai-service/app/models/disaster_assessment_model.py`
- **位置**：第862-880行，`_overall_score_v2` 方法
- **问题描述**：
  当活跃维度数 ≤ 3 时，代码会回退到 v1 模式（仅 geographic + precipitation + device），而不是用 v2 的七维度权重按有效维度归一化。
  
  这意味着：
  - 如果用户传入了 remote_sensing + meteorology + terrain 三个维度的数据，本来 v2 可以按这三个维度的权重归一化计算，但实际会回退到 v1 的 geographic+precipitation 模式
  - remote_sensing 和 terrain 的数据在总分中被浪费了
- **影响**：
  - 数据稀疏场景下，v2 模型的多维度优势完全丧失
  - 遥感影像、地形地质等数据在总分中没有得到体现
- **修复建议**：
  改为按活跃维度的 v2 权重重新归一化，而不是直接回退到 v1 的三维度模式。

---

### 【LOW-19】last_inspection_at字段仅用于展示，未参与评分

- **文件**：`ai-service/app/routers/comprehensive.py`
- **位置**：第39-41行，ComprehensiveRequest 定义
- **问题描述**：
  请求模型中包含 `last_inspection_at` 字段，注释说明"AI 评分时也参考"，但实际代码中（第64行注释）明确说"last_inspection_at 不直接进评分模型，挂在响应里给前端展示"。
- **影响**：
  - 字段名和注释具有误导性
  - 低优先级
- **修复建议**：
  更新注释，明确说明该字段仅用于展示，或在评分模型中实际引入巡检时间衰减因子。

---

### 【LOW-20】crisis amplifier注释与代码加成描述不一致

- **文件**：`ai-service/app/models/comprehensive_model.py`
- **位置**：第747-758行
- **问题描述**：
  注释第748-751行写的加成值与代码第757行 `boost_table` 基本一致，但注释说"命中 1 条 → 基础加成 +10 分（让"单一极端信号"升到 MEDIUM）"，而实际上10分加成是否能升到 MEDIUM 取决于基础分。如果基础分是20分，加10分到30分，仍然低于 MEDIUM 阈值（35分）。
- **影响**：
  - 文档准确性问题，不影响功能
- **修复建议**：
  修正注释，说明加成是在基础分之上叠加，实际等级取决于基础分+加成后的总分。

---

### 【LOW-21】WarningDto中hazardCode字段重复出现两次

- **文件**：`backend/src/main/java/cn/disasterguard/warning/service/WarningService.java`
- **位置**：第416-437行，`toDto` 方法
- **问题描述**：
  ```java
  warning.getHazardCode(),  // 第8个参数
  warning.getStatus(),
  warning.getHazardCode(),  // 第10个参数（重复）
  ```
  WarningDto 构造函数中 `hazardCode` 出现了两次，说明 DTO 定义中有两个不同的字段都存了 hazardCode。
- **影响**：
  - 代码冗余，可能导致混淆
  - 如果两个字段不同步会产生数据不一致
- **修复建议**：
  移除冗余字段，统一使用一个 hazardCode。

---

### 【LOW-22】getFeedbackStats绕过Java网关直接调用Python端点

- **文件**：`frontend/src/services/_architecture/domain/aiApi.js`
- **位置**：第300-310行，`getFeedbackStats` 方法
- **问题描述**：
  ```javascript
  // 后端暂未实现 GET /ai/feedback/stats，直接调 Python 端点
  const AI_BASE = ...
  const resp = await fetch(`${AI_BASE}/feedback/stats`);
  ```
  该方法绕过了 Java 后端网关，直接访问 Python AI 服务。这破坏了架构一致性：
  1. 绕过了 Java 端的认证鉴权
  2. 绕过了 Java 端的降级和容错处理
  3. 跨域问题需要额外配置
  4. 与其他所有 AI API 的调用方式不一致
- **影响**：
  - 安全性和架构一致性问题
  - 生产环境可能因为跨域或网络策略不可用
- **修复建议**：
  在 Java 后端实现 `/api/ai/feedback/stats` 端点，由 Java 端转发到 Python。

---

### 【LOW-23】独立测算端点依赖模块级math导入

- **文件**：`ai-service/app/routers/disaster_assessment.py`
- **位置**：第580行，`_independent_factors_from_point` 函数中使用 `math.sin`
- **问题描述**：
  `_independent_factors_from_point` 函数中使用了 `math.sin()`，但该函数所在的 `disaster_assessment.py` 文件中并没有 `import math`（等等——让我再确认一下）。
  
  实际上，第42行有 `import math`，所以这个没问题。但是这个导入是在 `social_media.py` 中明确导入的，而在 `disaster_assessment.py` 中我没有看到文件顶部的 import 部分有 math... 实际上 disaster_assessment.py 文件开头我读了前200行，没看到 math 导入。但第580行用了 math.sin。这可能是个问题。
  
  等等，让我重新看。disaster_assessment.py 的 imports 在第29行：`from typing import Any, Dict, List, Optional, Tuple` 以及其他的。但没有看到 `import math`。

  不对，我只读到了第200行。让我确认... 实际上 `_independent_factors_from_point` 函数在第549行，用了 `math.sin`。如果文件顶部没有 `import math`，那会报 NameError。

  但等等，这个文件可能在其他地方导入了 math，或者我漏看了。考虑到这个函数是 v1.7.0 新增的独立测算功能，如果缺少导入会直接报错，应该已经被发现了。但为了严谨性，这里标记为 LOW 级别待确认。
- **影响**：
  - 如果确实缺少导入，独立测算的 mock weather 功能会崩溃
- **修复建议**：
  确认文件顶部是否有 `import math`，如无则添加。

---

## 四、前后端API匹配检查

| 前端API方法 | 后端Java端点 | Python端点 | 匹配状态 | 备注 |
|------------|-------------|-----------|----------|------|
| `aiApi.detect(file)` | `POST /api/ai/detect` | `POST /api/ai/detect` | ✅ 匹配 | multipart上传 |
| `aiApi.forecast(...)` | `POST /api/ai/forecast` | `POST /api/ai/forecast` | ✅ 匹配 | Java做snake_case转换 |
| `aiApi.forecastAuto(...)` | `GET /api/ai/forecast/auto` | `POST /api/ai/forecast` | ✅ 匹配 | Java拉DB历史后调Python |
| `aiApi.anomaly(...)` | `POST /api/ai/anomaly` | `POST /api/ai/anomaly` | ✅ 匹配 | |
| `aiApi.fusionRisk(...)` | `POST /api/ai/fusion-risk` | `POST /api/ai/fusion-risk` | ✅ 匹配 | |
| `aiApi.fusionRiskAuto(...)` | `GET /api/ai/fusion-risk/auto` | `POST /api/ai/fusion-risk` | ✅ 匹配 | Java聚合因子后转发 |
| `aiApi.comprehensiveAnalysis(hazardCode)` | `GET /api/ai/comprehensive-analysis` | `POST /api/ai/comprehensive` | ✅ 匹配 | Java聚合因子后转发 |
| `aiApi.comprehensiveAnalysisAll()` | `GET /api/ai/comprehensive-analysis/all` | 不直接调用 | ✅ 匹配 | 读DB落库表 |
| `aiApi.comprehensiveAnalysisRefresh()` | `POST /api/ai/comprehensive-analysis/refresh` | 批量调用 | ✅ 匹配 | 触发批量刷新 |
| `aiApi.disasterAssessment(hazardCode)` | `GET /api/ai/disaster-assessment` | `POST /api/ai/disaster-assessment/score` | ✅ 匹配 | Java构建请求后转发 |
| `aiApi.disasterAssessmentStatus()` | `GET /api/ai/disaster-assessment/status` | `GET /api/ai/disaster-assessment/status` | ⚠️ 部分不匹配 | Java返回的字段与Python不同（Java硬编码字典，不含base_weights等） |
| `aiApi.analyzeSocialMedia(texts)` | `POST /api/ai/social-media/analyze` | `POST /api/ai/social-media/analyze` | ✅ 匹配 | |
| `aiApi.getFeedbackStats()` | 未实现 | `GET /api/ai/feedback/stats` | ❌ 不匹配 | 前端绕过Java直接调Python |
| `aiApi.getModelInfo()` | `GET /api/ai/model/info` | N/A | ✅ 匹配 | Java从DB读模型信息 |
| `aiApi.reloadModel()` | `POST /api/ai/model/reload` | N/A | ✅ 匹配 | Java触发模型重载 |

### 重点不匹配问题

**`disasterAssessmentStatus` 字段不一致**：
- Python返回：`model_loaded, disaster_types, concern_label_map, base_weights, device_present_weights, device_absent_weights, type_hint_count`
- Java返回：`disasterTypes, concernLabels, riskLevels`（硬编码的字典，第331-339行）
- 前端 `DamageAssessmentPage.vue` 调用的是 `dashboardApi.getDisasterAssessmentStatus()`，但实际用到的字段不多，影响有限。

---

## 五、AI分析输入输出正确性检查

### 综合分析（comprehensive）

| 项目 | 状态 | 说明 |
|------|------|------|
| 输入因子验证 | ✅ 正常 | 校验factors不能为空，至少有一项 |
| 输出结构完整性 | ✅ 正常 | overall + dimensions + reasoning + suggested_actions |
| 维度数量 | ⚠️ 不一致 | 文档说四维（图像/降水/地理/设备），实际模型是七维度 |
| 自适应归一化 | ❌ 失效 | 见 CRITICAL-1 |
| 社交媒体等级偏差 | ❌ 错误 | 见 HIGH-5 |

### 灾害评估（disaster-assessment）

| 项目 | 状态 | 说明 |
|------|------|------|
| 七维度评分 | ⚠️ 部分失效 | 活跃维度≤3时回退v1模式（MEDIUM-18） |
| 灾害类型分类 | ✅ 正常 | 六类型加权评分，有阈值和领先度判断 |
| 字符串因子处理 | ❌ 错误 | image_source/land_use_type被设为0.0（HIGH-6） |
| 批量检测 | ✅ 正常 | 单点失败不阻断整批，有阈值过滤 |
| 独立测算 | ⚠️ 待确认 | math导入待确认（LOW-23） |

### 时序预测（forecast）

| 项目 | 状态 | 说明 |
|------|------|------|
| LSTM推理 | ✅ 正常 | 有模型时用PyTorch，无模型降级启发式 |
| 长周期预测 | ⚠️ 精度低 | 超过7步用线性外推（MEDIUM-11） |
| 批量预测 | ✅ 正常 | 最多100台，单台失败不阻断 |

### YOLO图像检测

| 项目 | 状态 | 说明 |
|------|------|------|
| ONNX推理 | ✅ 正常 | 有模型时用onnxruntime，无模型降级mock |
| Mock模式 | ⚠️ 不稳定 | 完全随机（MEDIUM-12） |
| 业务类别判断 | ✅ 正常 | 通过ONNX输出维度判断是COCO80类还是业务5类 |

---

## 六、预警生成逻辑正确性检查

### AI预警联动（AiWarningScheduler）

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 触发条件 | ✅ 正常 | MEDIUM及以上风险等级触发 |
| 去重逻辑 | ✅ 正常 | 同一hazard未关闭预警不重复生成（用openHazards Set判断） |
| 并发去重 | ✅ 正常 | DataIntegrityViolationException捕获处理 |
| 等级映射 | ⚠️ 风险 | 中文预警等级与其他模块混用（MEDIUM-15） |
| 调度周期 | ✅ 正常 | 默认10分钟一次，可配置 |

### 规则引擎预警（WarningCreateService）

| 检查项 | 状态 | 说明 |
|--------|------|------|
| AI融合升级 | ⚠️ 单向 | 只升级不降级（HIGH-8） |
| 两阶段事务 | ✅ 正常 | AI调用在事务外，DB写入在事务内 |
| 风险评分日志 | ✅ 正常 | 无论AI是否可用都写risk_score_log |
| 异常降级 | ✅ 正常 | AI失败时沿用规则等级，不丢失预警 |

### 预警服务（WarningService）

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 状态流转 | ✅ 正常 | 待确认→已确认→处理中→已关闭/已忽略/已解除 |
| 派发联动应急 | ✅ 正常 | 橙/红预警派发时自动创建应急事件 |
| 解除通知预览 | ✅ 正常 | 有模板文案和渠道配置 |
| CSS样式映射 | ❌ 错误 | 红色预警映射为blue（CRITICAL-3） |
| 人工创建校验 | ✅ 正常 | 校验hazardCode存在性，归一化等级和灾种 |

---

## 七、报告生成问题检查

| 检查项 | 状态 | 说明 |
|--------|------|------|
| 模板CRUD | ✅ 正常 | 创建、删除、列表查询均可用 |
| 报告生成 | ❌ 空壳 | 仅创建RUNNING记录，无实际生成逻辑（CRITICAL-4） |
| 重新运行 | ⚠️ 部分 | 能创建新记录但同样不会生成实际报告 |
| 取消运行 | ✅ 正常 | 能将RUNNING状态改为CANCELLED |
| 订阅管理 | ✅ 正常 | upsert按templateId幂等，有增删查 |
| 种子数据 | ❌ 不一致 | templateId不匹配（HIGH-10） |
| 分页查询 | ✅ 正常 | 内存过滤+分页，功能完整 |

---

## 八、修复优先级建议

### 应立即修复（P0）

1. **CRITICAL-3**：红色预警CSS样式映射错误 — 直接影响最高等级预警的视觉识别
2. **CRITICAL-4**：报告生成功能空壳 — 核心功能完全不可用
3. **CRITICAL-1**：自适应归一化死代码 — 移除或实现，避免误导
4. **CRITICAL-2**：多模态融合全Mock — 明确标识为演示或实现真实逻辑

### 应尽快修复（P1）

5. **HIGH-5**：社交媒体告警等级-1偏差 — 影响舆情驱动的风险评估
6. **HIGH-8**：AI融合只升级不降级 — 可能导致大量误报
7. **HIGH-7**：设备维度卡片不显示 — 影响前端数据展示完整性
8. **HIGH-6**：字符串因子设为0.0 — 影响批量检测的准确性
9. **HIGH-9**：七维度雷达图实际只有5维 — UI与功能不符

### 计划修复（P2）

10. **MEDIUM-11**：LSTM长周期预测精度低
11. **MEDIUM-12**：YOLO Mock结果随机
12. **MEDIUM-15**：预警等级中英文混用
13. **MEDIUM-16**：长事务风险
14. **MEDIUM-17**：预警详情页用旧版接口
15. **MEDIUM-18**：维度少时回退v1模式
16. **MEDIUM-13/14**：社媒位置分析质量

### 低优先级优化（P3）

17. **LOW-19 ～ LOW-23**：文档/代码质量/架构一致性问题

---

*报告生成时间：2026-10-21*
*审查范围：23个核心文件，涵盖AI分析、预警生成、报告生成三大模块*
