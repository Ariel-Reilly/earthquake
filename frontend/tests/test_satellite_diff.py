"""Standalone test for sentinel diff endpoint (no full app dep)."""
import os
import sys
import time
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'ai-service'))

from fastapi import FastAPI
from fastapi.testclient import TestClient
from app.routers.sentinel import router as sentinel_router

# 只挂载 sentinel 路由（避开 detection 的 multipart 依赖）
app = FastAPI()
app.include_router(sentinel_router)
client = TestClient(app)

print('=== 1. compose (before) ===')
r = client.post('/api/ai/sentinel/compose', json={
    'bbox': [103.30, 30.80, 103.80, 31.20],
    'start': '2025-09-01',
    'end':   '2025-09-30',
    'max_cloud': 50,
})
print('HTTP', r.status_code, r.json().get('jobId', '')[:8] + '...')
before_id = r.json()['jobId']

print('=== 2. compose (after) ===')
r = client.post('/api/ai/sentinel/compose', json={
    'bbox': [103.30, 30.80, 103.80, 31.20],
    'start': '2026-08-01',
    'end':   '2026-09-10',
    'max_cloud': 50,
})
print('HTTP', r.status_code, r.json().get('jobId', '')[:8] + '...')
after_id = r.json()['jobId']

print('=== polling both compose jobs ===')
for i in range(30):
    time.sleep(1)
    rb = client.get(f'/api/ai/sentinel/jobs/{before_id}').json()
    ra = client.get(f'/api/ai/sentinel/jobs/{after_id}').json()
    print(f'  [{i+1:>2}s] before={rb["status"]}/{rb.get("progress")}% after={ra["status"]}/{ra.get("progress")}%')
    if rb['status'] == 'succeeded' and ra['status'] == 'succeeded':
        print(f'  Both succeeded in {i+1}s')
        break

print('=== 3. diff ===')
r = client.post('/api/ai/sentinel/diff', json={
    'before_job_id': before_id,
    'after_job_id':  after_id,
    'bbox':          [103.30, 30.80, 103.80, 31.20],
    'threshold':     30,
    'colormap':      'inferno',
})
print('HTTP', r.status_code, r.json().get('jobId', '')[:8] + '...')
diff_id = r.json()['jobId']

print('=== polling diff job ===')
for i in range(30):
    time.sleep(1)
    rd = client.get(f'/api/ai/sentinel/diff-jobs/{diff_id}').json()
    print(f'  [{i+1:>2}s] diff={rd["status"]}/{rd.get("progress")}%')
    if rd['status'] == 'succeeded':
        result = rd['result']
        print()
        print('  diffUrl       :', (result['diffUrl'][:60] + '...') if result.get('diffUrl') else '(none)')
        print('  changePixels  :', result['changePixels'], '/', result['totalPixels'])
        print('  changePercent :', result['changePercent'], '%')
        print('  threshold     :', result['threshold'])
        print('  colormap      :', result['colormap'])
        print('  imageSize     :', result['imageSize'])
        print('  hotspots count:', len(result['hotspots']))
        for idx, h in enumerate(result['hotspots']):
            lon = h.get('lon', 'n/a')
            lat = h.get('lat', 'n/a')
            print(f'    [{idx+1}] intensity={h["intensity"]:.3f} pixels={h["pixel_count"]:>4} lon={lon} lat={lat}')
        print()
        print('[OK] All endpoints work end-to-end!')
        break
    elif rd['status'] == 'failed':
        print('  FAILED:', rd.get('message'))
        sys.exit(1)
else:
    print('TIMEOUT')
    sys.exit(1)
