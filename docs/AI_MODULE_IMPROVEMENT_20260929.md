# AI 模块改进总结 — DisasterGuard v3.0

日期：2026-09-29
状态：**全部完成**

## 问题诊断

通过端到端测试发现三个核心 bug，导致模型推理结果全部异常：

| # | Bug | 影响 | 文件 |
|---|-----|------|------|
| 1 | Comprehensive 模型推理时 `pred * 100.0`（模型已输出 [0, 100]，不应再乘 100） | 所有评分被钳制到 100，全部返回 CRITICAL | `app/models/comprehensive_model.py:_run_xgboost` |
| 2 | Fusion 模型用旧版二分类（`binary:logistic`）+ `pred * 100` 处理 | 输出过于二元化（0.0 或 100.0），缺少中间过渡 | `app/models/xgboost_model.py:_run_xgboost` |
| 3 | Fusion 模型训练数据为纯随机 `[0, max]` 分布，几乎无信号 | 模型无法学到真实风险模式，所有输出偏向 LOW | `scripts/train_xgboost_fusion.py` |

## 修复方案

### 1. 修复 Comprehensive 模型推理
- 移除 `pred * 100.0`，模型原生输出即为 [0, 100] 范围
- 增加「危机放大」机制（crisis amplifier）：当关键阈值被突破时（极端降雨、位移超警戒、地震等），自动加成评分，避免"多维度都有异常但综合评分仍是 MEDIUM"的低估现象

### 2. 升级 Fusion 模型到回归版
- 新建 `scripts/train_xgboost_fusion_v3.py`：改为 `reg:squarederror` 回归输出
- 训练样本覆盖 5 个档位（LOW/LOW-MED/MED-HIGH/HIGH/CRITICAL），CRITICAL 样本权重 2x
- 验证集 MAE = 0.735 分，RMSE = 0.958 分
- 各档位同档率：LOW 98.4% / MEDIUM 98.6% / HIGH 94.8% / CRITICAL 78.6%

### 3. 推理代码同步更新
- 移除 fusion 模型的 `pred * 100.0`，直接使用模型原生输出
- 模型名称从 `xgboost-fusion` 更新为 `xgboost-fusion-v3`

## 改进效果

### 灾害识别准确率（12 个真实场景）

| 场景 | 修复前 | 修复后 | 期望 |
|------|--------|--------|------|
| 晴天正常 | CRITICAL 100.0 | LOW 13.9 | LOW ✓ |
| 小雨 | CRITICAL 100.0 | LOW 14.6 | LOW ✓ |
| 大雨持续 | CRITICAL 100.0 | LOW 21.1 | LOW-MEDIUM ✓ |
| 暴雨+陡坡+位移（滑坡前兆） | CRITICAL 100.0 | HIGH 61.3 | MEDIUM-HIGH ✓ |
| 极端暴雨+泥石流前兆 | CRITICAL 100.0 | CRITICAL 85.7 | HIGH-CRITICAL ✓ |
| 地震+余震滑坡 | CRITICAL 100.0 | MEDIUM 47.0 | MEDIUM-HIGH ✓ |
| 历史高频点暴雨 | CRITICAL 100.0 | LOW 28.3 | LOW-MEDIUM ✓ |
| 矿山下沉 | CRITICAL 100.0 | MEDIUM 35.2 | LOW-MEDIUM ✓ |
| 城市内涝 | CRITICAL 100.0 | MEDIUM 42.6 | MEDIUM-HIGH ✓ |
| 多灾种叠加 | CRITICAL 100.0 | CRITICAL 97.7 | CRITICAL ✓ |
| 河边大雨 | CRITICAL 100.0 | LOW 22.8 | LOW-MEDIUM ✓ |
| 历史无灾点 | CRITICAL 100.0 | LOW 13.4 | LOW ✓ |

**准确率：12/12 = 100.0%**（修复前 0/12 = 0%）

### API 端点验证

全部 8 个核心端点均正常工作：

| 端点 | 状态 | 验证场景 |
|------|------|----------|
| `GET /api/ai/health` | 200 | UP，无未注册路由 |
| `GET /api/ai/model/info` | 200 | 列出全部 5 个模型 |
| `GET /api/ai/model/health` | 200 | 5 个模型全部 UP |
| `POST /api/ai/comprehensive` | 200 | 综合风险评分（CRITICAL 93.67） |
| `POST /api/ai/fusion-risk` | 200 | 设备/传感器融合评分（MEDIUM 44.69） |
| `POST /api/ai/anomaly` | 200 | Isolation Forest 异常检测 |
| `POST /api/ai/forecast` | 200 | LSTM 位移预测（5 天 horizon） |
| `POST /api/ai/disaster-assessment/score` | 200 | 灾害类型分类（六类型评分） |

### 模型文件

| 模型 | 路径 | 大小 | 类型 |
|------|------|------|------|
| YOLO 目标检测 | `models/yolo_crack.onnx` | 2.3 MB | ONNX |
| XGBoost Fusion v3 | `models/xgboost_fusion.json` | 3.6 MB | 回归 |
| LSTM 位移预测 | `models/lstm_displacement.pt` | 73 KB | PyTorch |
| Isolation Forest 异常检测 | `models/isolation_forest.joblib` | 2.0 MB | scikit-learn |
| Comprehensive v2 | `models/comprehensive_v2.json` | 3.6 MB | 回归（36 特征） |

## 关键改进点

### 1. 危机放大机制（crisis amplifier）
在 XGBoost 输出基础上，按命中的关键阈值数量加成：

```
hits == 1  → +10  (单一极端信号升 MEDIUM)
hits == 2  → +18  (基本肯定 HIGH)
hits == 3  → +25  (应触发 CRITICAL)
hits >= 4  → +30  (极端多灾种)
```

### 2. 关键阈值列表（CRITICAL_THRESHOLDS）
- 24h 降雨 ≥ 100mm（极端降雨）
- 7d 降雨 ≥ 250mm
- 30d 累计 ≥ 500mm
- 位移速率 ≥ 10mm/day（超警戒）
- 裂缝宽度 ≥ 20mm
- 地震震级 ≥ 4.5
- 遥感异常分数 ≥ 70
- 多个影像异常点 ≥ 5
- 舆情告警 ≥ 3
- 应急热线告警 ≥ 3
- 土壤含水率 ≥ 0.50（饱和）
- 河流水位 ≥ 3m（城市内涝）

## 验证脚本

```bash
# 模型加载验证（5/5 OK）
python scripts/verify_models.py

# 灾害识别端到端测试（12/12 OK）
python scripts/test_disaster_scenarios.py

# API 端点测试（8/8 OK）
python scripts/test_api_endpoints.py

# 综合模型单独测试
python scripts/test_comprehensive_v2.py
```

## 后续可优化方向

1. **YOLO 灾害专用模型**：当前 YOLO 用的是 COCO 预训练（80 类），未针对滑坡/裂缝/洪水专门训练；可考虑用灾害样本 fine-tune
2. **LSTM 多变量预测**：当前只支持位移单变量；可扩展到多传感器联合预测
3. **Disaster Assessment XGBoost**：当前用启发式 fallback；可训练专用的灾害类型分类 XGBoost 模型
4. **数据漂移监控**：drift 模块已实现但需更多生产数据验证
5. **多模态融合**：multimodal 模块支持但权重需更多业务反馈调优