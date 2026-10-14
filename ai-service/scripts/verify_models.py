#!/usr/bin/env python3
"""验证所有 5 个模型能正常加载和推理。

可从任意目录运行：
  python ai-service/scripts/verify_models.py
  cd ai-service && python scripts/verify_models.py
  cd ai-service/scripts && python verify_models.py
"""
import sys
from _bootstrap import setup_path
setup_path()

from app.models.yolo_model import YoloDetector
from app.models.xgboost_model import get_fusion_model
from app.models.lstm_model import get_forecaster
from app.models.isolation_forest import get_detector as get_iforest
from app.models.comprehensive_model import get_comprehensive_model

print('=== Model Loading Verification ===')
results = {}

# 1. YOLO
print()
print('1. YOLO:')
det = YoloDetector()
results['yolo'] = det.is_loaded()
print(f'   loaded: {results["yolo"]}  path: {det.model_path}')

# 2. XGBoost Fusion
print()
print('2. XGBoost Fusion:')
fm = get_fusion_model()
results['xgboost_fusion'] = fm.is_loaded()
print(f'   loaded: {results["xgboost_fusion"]}')
r = fm.score({'displacement_rate': 8.0, 'rainfall_24h': 120, 'crack_width': 15})
print(f'   score={r["risk_score"]} level={r["risk_level"]} mock={r["mock"]}')

# 3. LSTM
print()
print('3. LSTM:')
lstm = get_forecaster()
results['lstm'] = lstm.is_loaded()
print(f'   loaded: {results["lstm"]}')
r = lstm.predict([1.2, 1.5, 1.8, 2.0, 2.2, 2.5, 2.8], horizon=7)
print(f'   pred[0]={r["predictions"][0]:.4f} confidence={r["confidence"]} mock={r["mock"]}')

# 4. Isolation Forest
print()
print('4. Isolation Forest:')
iforest = get_iforest()
results['iforest'] = iforest.is_loaded()
print(f'   loaded: {results["iforest"]}')
# 5维特征: [displacement_rate, crack_width, tilt_angle, rainfall, vibration_freq]
# 7个样本，其中第5个是异常的（极端裂缝和振动）
samples = [
    [1.0, 1.1, 0.5, 2.0, 1.5],
    [1.2, 1.3, 0.4, 1.8, 1.2],
    [1.5, 1.2, 0.6, 3.0, 1.0],
    [0.9, 1.0, 0.3, 1.5, 0.8],
    [1.1, 1.4, 0.5, 2.2, 1.3],
    [10.0, 35.0, 8.0, 100.0, 30.0],  # 异常样本
    [1.3, 1.0, 0.4, 1.9, 1.1],
]
r = iforest.detect(samples)
print(f'   is_anomaly={r["is_anomaly"]} anomaly_idx={r["anomaly_indices"]} mock={r["mock"]}')

# 5. Comprehensive
print()
print('5. Comprehensive:')
cm = get_comprehensive_model()
results['comprehensive'] = cm.is_loaded()
print(f'   loaded: {results["comprehensive"]}')
r = cm.score({
    'rainfall_24h': 120, 'rainfall_7d': 300,
    'displacement_rate': 8.0, 'crack_width': 20,
    'slope_deg': 45, 'image_anomaly_score': 75,
    'social_alert_level': 3, 'historical_count': 5,
})
print(f'   score={r["overall"]["risk_score"]} level={r["overall"]["risk_level"]} mock={r["overall"]["mock"]}')

print()
print('=== Summary ===')
all_ok = True
for k, v in results.items():
    status = 'OK  ' if v else 'FAIL'
    print(f'  [{status}] {k}')
    if not v:
        all_ok = False

if all_ok:
    print('\nALL MODELS LOADED SUCCESSFULLY - Real inference active!')
else:
    print('\nWARNING: Some models failed to load (using mock fallback)')
