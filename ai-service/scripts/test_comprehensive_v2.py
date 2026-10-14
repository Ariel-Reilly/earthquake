"""Test the retrained comprehensive model. Can run from any directory."""
import sys
from _bootstrap import setup_path
setup_path()

from app.models.comprehensive_model import get_comprehensive_model
import app.models.comprehensive_model as cm_mod

# 强制重新加载
cm_mod._comprehensive_model = None
cm = get_comprehensive_model()

test_cases = [
    ('极端风险', {'rainfall_24h': 180, 'rainfall_7d': 450, 'soil_moisture': 0.55,
     'slope_deg': 45, 'vegetation_cover': 0.15, 'soil_grade': 5,
     'distance_to_water_m': 100, 'geology_grade': 5,
     'displacement_rate': 15, 'crack_width': 30, 'earthquake_mag': 5.5,
     'image_anomaly_score': 80, 'image_anomaly_count': 8,
     'social_alert_level': 4, 'social_post_count': 50}),
    ('高风险', {'rainfall_24h': 100, 'rainfall_7d': 250,
     'slope_deg': 35, 'soil_grade': 4, 'vegetation_cover': 0.2,
     'displacement_rate': 6, 'crack_width': 12}),
    ('中等风险', {'rainfall_24h': 30, 'rainfall_7d': 80, 'slope_deg': 22, 'vegetation_cover': 0.5}),
    ('低风险', {'rainfall_24h': 5, 'rainfall_7d': 20, 'slope_deg': 8, 'vegetation_cover': 0.7}),
    ('滑坡前兆', {'rainfall_24h': 95, 'rainfall_7d': 240, 'soil_moisture': 0.48,
     'slope_deg': 38, 'vegetation_cover': 0.18, 'soil_grade': 4.2,
     'distance_to_water_m': 120, 'geology_grade': 4.5,
     'displacement_rate': 12, 'crack_width': 25, 'earthquake_mag': 4.0,
     'image_anomaly_score': 60}),
    ('泥石流', {'rainfall_24h': 130, 'rainfall_7d': 380, 'rainfall_30d': 700,
     'soil_moisture': 0.52, 'slope_deg': 32, 'vegetation_cover': 0.15,
     'distance_to_water_m': 80, 'geology_grade': 4.0,
     'image_anomaly_score': 70}),
]

for name, factors in test_cases:
    r = cm.score(factors, region_code='test')
    print(f'{name}: level={r["overall"]["risk_level"]}, score={r["overall"]["risk_score"]}, model={r["overall"]["model"]}')
