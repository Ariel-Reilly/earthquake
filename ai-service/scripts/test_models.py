"""Test comprehensive model with various inputs. Can run from any directory."""
import sys
from _bootstrap import setup_path
setup_path()

from app.models.comprehensive_model import get_comprehensive_model
from app.models.disaster_assessment_model import get_disaster_assessment_model
from app.models.xgboost_model import get_fusion_model

cm = get_comprehensive_model()
dm = get_disaster_assessment_model()
fm = get_fusion_model()

# 测试明显 CRITICAL 输入
test_cases = [
    ('极端输入_综合', {
        'rainfall_24h': 180, 'rainfall_7d': 450, 'rainfall_30d': 900,
        'soil_moisture': 0.55, 'water_level': 6.0,
        'slope_deg': 45, 'vegetation_cover': 0.15, 'soil_grade': 5,
        'distance_to_water_m': 100, 'geology_grade': 5,
        'displacement_rate': 15, 'crack_width': 30, 'earthquake_mag': 5.5,
        'displacement_total': 150, 'site_grade': 5, 'tilt_angle': 8,
        'image_anomaly_score': 80, 'image_anomaly_count': 8,
        'historical_count': 8, 'historical_max_score': 85,
        'exposure_population': 8000, 'exposure_buildings': 1000,
        'social_alert_level': 4, 'social_post_count': 50,
        'social_sentiment_score': -0.7, 'social_emergency_level': 3,
        'nighttime_light_index': 5,
        'infrastructure_score': 0.2, 'road_access_score': 0.2, 'power_status': 0.3,
    }),
    ('高风险输入_综合', {
        'rainfall_24h': 100, 'rainfall_7d': 250,
        'slope_deg': 35, 'soil_grade': 4, 'vegetation_cover': 0.2,
        'displacement_rate': 6, 'crack_width': 12,
    }),
    ('低风险输入_综合', {
        'rainfall_24h': 5, 'rainfall_7d': 20,
        'slope_deg': 8, 'vegetation_cover': 0.7,
    }),
]

print("=" * 80)
print("Comprehensive Risk Model")
print("=" * 80)
for name, factors in test_cases:
    r = cm.score(factors, region_code='test')
    overall = r['overall']
    print(f'\n{name}:')
    print(f'  risk_level={overall["risk_level"]}, score={overall["risk_score"]}, model={overall["model"]}, mock={overall["mock"]}')
    print(f'  dim scores: {{k: v.get("score", 0) for k, v in r["dimensions"].items()}}')
    for k, v in r['dimensions'].items():
        if isinstance(v, dict):
            print(f'    {k}: {v.get("score", "n/a")}')

print()
print("=" * 80)
print("Disaster Assessment Model")
print("=" * 80)
for name, factors in test_cases:
    hazard = {'hazard_code': 'TEST-001', 'hazard_type': None, 'region_code': 'test'}
    r = dm.score(hazard, factors)
    overall = r['overall']
    print(f'\n{name}:')
    print(f'  risk_level={overall["risk_level"]}, score={overall["risk_score"]}, model={overall["model"]}, mock={overall["mock"]}')
    print(f'  disaster_type: {r["disaster_type"].get("disaster_type")} ({r["disaster_type"].get("source")}, conf={r["disaster_type"].get("confidence")})')

print()
print("=" * 80)
print("Fusion Risk Model")
print("=" * 80)
for name, factors in test_cases:
    r = fm.score(factors)
    print(f'\n{name}:')
    print(f'  risk_level={r["risk_level"]}, score={r["risk_score"]}, model={r["model"]}, mock={r["mock"]}')
