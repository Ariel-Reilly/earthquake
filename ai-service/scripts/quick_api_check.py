"""快速验证启动后的 FastAPI 服务（端到端）。"""
import json
import urllib.request
import urllib.error

BASE_URL = "http://127.0.0.1:8765"

endpoints = [
    ('GET', '/api/ai/health', None),
    ('GET', '/api/ai/model/info', None),
    ('GET', '/api/ai/model/health', None),
    ('POST', '/api/ai/fusion-risk', {
        'hazard_code': 'TEST',
        'factors': {'displacement_rate': 12, 'rainfall_24h': 150, 'crack_width': 25, 'earthquake_mag': 5.0}
    }),
    ('POST', '/api/ai/comprehensive', {
        'hazard_code': 'TEST_LANDSLIDE',
        'factors': {
            'rainfall_24h': 180, 'rainfall_7d': 450, 'rainfall_30d': 700,
            'soil_moisture': 0.55, 'slope_deg': 42, 'vegetation_cover': 0.15,
            'soil_grade': 5, 'distance_to_water_m': 80, 'geology_grade': 4.5,
            'displacement_rate': 18, 'crack_width': 35, 'earthquake_mag': 5.0,
            'image_anomaly_score': 80, 'image_anomaly_count': 10,
            'social_alert_level': 4, 'social_post_count': 60,
        }
    }),
    ('POST', '/api/ai/anomaly', {
        'device_code': 'TEST',
        'data': [[1.0, 1.0, 0.5, 2.0, 1.0], [25.0, 35.0, 8.0, 100.0, 30.0]]
    }),
    ('POST', '/api/ai/forecast', {
        'device_code': 'TEST',
        'history': [1.0, 1.5, 2.0, 2.8, 3.5, 4.5, 5.8, 7.5],
        'horizon': 5,
    }),
]

ok = 0
fail = 0
for m, p, pl in endpoints:
    try:
        url = BASE_URL + p
        if pl:
            req = urllib.request.Request(url, data=json.dumps(pl).encode(),
                                          headers={'Content-Type': 'application/json'},
                                          method=m)
        else:
            req = urllib.request.Request(url, method=m)
        with urllib.request.urlopen(req, timeout=15) as r:
            resp = json.loads(r.read())
            info = ''
            if 'overall' in resp:
                ov = resp['overall']
                info = 'level=%s score=%.1f' % (ov['risk_level'], ov['risk_score'])
            elif 'risk_level' in resp:
                info = 'level=%s score=%.1f' % (resp['risk_level'], resp['risk_score'])
            elif 'anomaly_indices' in resp:
                info = 'anomalies=%s' % resp['anomaly_indices']
            elif 'predictions' in resp:
                info = 'horizon=%d days' % len(resp['predictions'])
            elif 'results' in resp:
                info = '%d models UP' % len(resp['results'])
            elif 'status' in resp:
                info = 'status=%s' % resp['status']
            print('  [ OK ] %s %-32s HTTP %d  %s' % (m, p, r.status, info))
            ok += 1
    except urllib.error.HTTPError as e:
        print('  [FAIL] %s %-32s HTTP %d  %s' % (m, p, e.code, e.read().decode()[:100]))
        fail += 1
    except Exception as e:
        print('  [ERR ] %s %-32s  %s' % (m, p, e))
        fail += 1

print()
print('Total: %d OK, %d FAIL' % (ok, fail))