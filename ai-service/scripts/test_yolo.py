"""Test YOLO inference with synthetic disaster-like images. Can run from any directory."""
import sys
from _bootstrap import setup_path
setup_path()

import cv2
import numpy as np

from app.models.yolo_model import YoloDetector

d = YoloDetector()
print('Model loaded:', d.is_loaded())
print('Variant:', d.model_variant)
print('Is business:', d.is_business_model)
print('Model path:', d.model_path)

# 制造一个测试图片（灰白岩石 + 一些形状）
img = np.zeros((480, 640, 3), dtype=np.uint8)
img[:] = (120, 120, 120)
# 加一些线条模拟裂缝
cv2.line(img, (100, 100), (300, 200), (50, 50, 50), 4)
cv2.line(img, (300, 200), (500, 150), (60, 60, 60), 3)
cv2.rectangle(img, (400, 300), (550, 450), (180, 180, 180), -1)
cv2.circle(img, (200, 350), 50, (100, 100, 100), -1)
# 编码为 jpg
_, buf = cv2.imencode('.jpg', img)
image_bytes = buf.tobytes()

result = d.detect(image_bytes)
print('\nDetection result:')
print(f'  model: {result.get("model")}')
print(f'  mock: {result.get("mock")}')
print(f'  is_business_model: {result.get("is_business_model")}')
print(f'  num_classes: {result.get("num_classes")}')
print(f'  detections count: {len(result.get("detections", []))}')
for det in result.get('detections', []):
    print(f'    {det}')

# 测试第二张图（疑似房屋 + 人物）
img2 = np.zeros((480, 640, 3), dtype=np.uint8)
img2[:] = (100, 150, 200)
# 一栋房子
cv2.rectangle(img2, (200, 200), (400, 400), (200, 200, 100), -1)
cv2.rectangle(img2, (250, 250), (290, 350), (50, 50, 50), -1)
# 一个人
cv2.circle(img2, (500, 280), 30, (255, 200, 200), -1)
cv2.rectangle(img2, (470, 300), (530, 420), (255, 200, 200), -1)
_, buf2 = cv2.imencode('.jpg', img2)
result2 = d.detect(buf2.tobytes())
print('\nDetection result #2 (buildings/persons):')
for det in result2.get('detections', []):
    print(f'    {det}')
