"""测试所有 AI 端点。可从任意目录运行（需先启动 FastAPI 服务）。"""
import sys
from _bootstrap import setup_path
setup_path()

import json
import urllib.request
import urllib.error

endpoints = [
    ('GET', '/api/ai/health', None),
    ('GET', '/api/ai/model-info', None),
    ('POST', '/api/ai/fusion', {
        'hazard_code': 'TEST_001',
        'factors': {'displacement_rate': 12, 'rainfall_24h': 150, 'crack_width': 25, 'earthquake_mag': 5.0}
    }),
    ('POST', '/api/ai/anomaly', {
        'hazard_code': 'TEST_001',
        'data': [
            [1.0, 1.0, 0.5, 2.0, 1.0],
            [1.2, 1.1, 0.6, 2.1, 1.1],
            [25.0, 35.0, 8.0, 100.0, 30.0],  # anomaly
            [1.1, 1.0, 0.5, 2.2, 1.0],
        ]
    }),
    ('POST', '/api/ai/forecast', {
        'hazard_code': 'TEST_001',
        'history': [1.0, 1.5, 2.0, 2.8, 3.5, 4.5, 5.8, 7.5],
        'horizon': 5,
    }),
    ('POST', '/api/ai/comprehensive', {
        'hazard_code': 'TEST_LANDSLIDE',
        'factors': {
            'rainfall_24h': 95,
            'rainfall_7d': 240,
            'soil_moisture': 0.50,
            'slope_deg': 35,
            'vegetation_cover': 0.2,
            'soil_grade': 4,
            'displacement_rate': 12,
            'crack_width': 22,
            'image_anomaly_score': 65,
            'historical_count': 4,
        }
    }),
]

for method, path, payload in endpoints:
    try:
        url = 'http://127.0.0.1:8001' + path
        if payload is not None:
            req = urllib.request.Request(
                url, data=json.dumps(payload).encode(),
                headers={'Content-Type': 'application/json'}, method=method
            )
        else:
            req = urllib.request.Request(url, method=method)
        with urllib.request.urlopen(req, timeout=10) as r:
            resp = json.loads(r.read())
            line = '[ OK ] %s %-28s -> HTTP %d' % (method, path, r.status)
            print(line)
            if 'overall' in resp:
                ov = resp['overall']
                print('       overall: level=%s, score=%.1f, conf=%.3f' %
                      (ov.get('risk_level'), ov.get('risk_score'), ov.get('confidence')))
            elif 'risk_level' in resp:
                print('       risk_level=%s, score=%.1f, conf=%.3f' %
                      (resp['risk_level'], resp['risk_score'], resp.get('confidence', 0)))
            elif 'anomaly_indices' in resp:
                print('       anomaly_indices=%s, detected=%s' %
                      (resp['anomaly_indices'], resp['is_anomaly']))
            elif 'predictions' in resp:
                print('       first 3 predictions: %s' % resp['predictions'][:3])
            elif 'models' in resp:
                print('       models: %s' % list(resp.get('models', {}).keys())[:5])
            else:
                print('       keys: %s' % list(resp.keys())[:6])
    except urllib.error.HTTPError as e:
        body = e.read().decode()[:200]
        print('[FAIL] %s %-28s -> HTTP %d' % (method, path, e.code))
        print('       %s' % body)
    except Exception as e:
        print('[ERR ] %s %-28s -> %s: %s' % (method, path, type(e).__name__, e))