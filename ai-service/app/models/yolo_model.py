"""
YOLO 裂缝/落石图像识别模型加载与推理。

自动检测 models/yolo_crack.onnx 是否存在：
- 存在：用 onnxruntime 加载并做真实推理
- 不存在：返回 mock 检测结果（含随机 bbox + 置信度）

不硬依赖 ultralytics，只在训练脚本中使用。
模型目录可通过环境变量 MODELS_DIR 覆盖。
"""
from __future__ import annotations

import os
import random
from pathlib import Path
from typing import List, Dict, Any, Optional

import numpy as np

from app.config import MODELS_DIR

# 类别定义（训练时可调整）
CLASSES = ["crack", "rockfall", "slump", "debris", "normal"]


class YoloDetector:
    """YOLO 目标检测封装。"""

    def __init__(self, model_path: Optional[str] = None, input_size: int = 640,
                 conf_threshold: float = 0.35, iou_threshold: float = 0.45):
        self.input_size = input_size
        self.conf_threshold = conf_threshold
        self.iou_threshold = iou_threshold
        self.model_path = Path(model_path) if model_path else (MODELS_DIR / "yolo_crack.onnx")
        self.session = None
        self.model_loaded = False
        self._load()

    def _load(self) -> None:
        """尝试加载 ONNX 模型；不可用时静默降级。"""
        if not self.model_path.exists():
            print(f"[YoloDetector] 未找到 ONNX 模型: {self.model_path}，降级为 mock 推理")
            return
        try:
            import onnxruntime as ort
            providers = ort.get_available_providers()
            # 优先 GPU，回退 CPU
            provider = "CUDAExecutionProvider" if "CUDAExecutionProvider" in providers else "CPUExecutionProvider"
            self.session = ort.InferenceSession(str(self.model_path), providers=[provider])
            self.model_loaded = True
            print(f"[YoloDetector] 模型加载成功: {self.model_path}, provider={provider}")
        except ImportError:
            print("[YoloDetector] onnxruntime 未安装，降级为 mock 推理")
        except Exception as e:
            print(f"[YoloDetector] 模型加载失败: {e}，降级为 mock 推理")

    def is_loaded(self) -> bool:
        return self.model_loaded

    def detect(self, image_bytes: bytes) -> Dict[str, Any]:
        """
        对输入图片做目标检测。

        Args:
            image_bytes: 图片原始字节

        Returns:
            {"detections": [{class_name, confidence, bbox}], "model": str, "mock": bool}
        """
        if self.model_loaded and self.session is not None:
            return self._run_onnx(image_bytes)
        return self._run_mock()

    # 真实 ONNX 推理
    def _run_onnx(self, image_bytes: bytes) -> Dict[str, Any]:
        try:
            import cv2  # noqa
            buf = np.frombuffer(image_bytes, dtype=np.uint8)
            img = cv2.imdecode(buf, cv2.IMREAD_COLOR)
            if img is None:
                return self._run_mock()

            orig_h, orig_w = img.shape[:2]
            resized = cv2.resize(img, (self.input_size, self.input_size))
            blob = cv2.dnn.blobFromImage(resized, 1.0 / 255.0, (self.input_size, self.input_size), swapRB=True)

            input_name = self.session.get_inputs()[0].name
            outputs = self.session.run(None, {input_name: blob})

            detections = self._parse_yolo_output(outputs[0], orig_w, orig_h)
            return {
                "detections": detections,
                "model": "yolov8n-crack-onnx",
                "mock": False,
            }
        except ImportError:
            print("[YoloDetector] cv2 未安装，降级为 mock")
            return self._run_mock()
        except Exception as e:
            print(f"[YoloDetector] ONNX 推理异常: {e}，降级为 mock")
            return self._run_mock()

    def _parse_yolo_output(self, output: np.ndarray, orig_w: int, orig_h: int) -> List[Dict]:
        """解析 YOLOv8 ONNX 输出格式: [1, 84, 8400] → N 个检测框"""
        detections: List[Dict] = []
        try:
            if output.ndim == 3:
                output = output[0]  # 去掉 batch 维
            # output shape: [84, 8400] → transpose → [8400, 84]
            if output.shape[0] < output.shape[1]:
                output = output.T

            scale_x = orig_w / self.input_size
            scale_y = orig_h / self.input_size

            boxes = output[:, :4]  # cx, cy, w, h
            scores = output[:, 4:]  # class scores

            for i in range(len(boxes)):
                class_id = int(np.argmax(scores[i]))
                confidence = float(scores[i, class_id])
                if confidence < self.conf_threshold:
                    continue
                cx, cy, w, h = boxes[i]
                x1 = max(0.0, (cx - w / 2) * scale_x)
                y1 = max(0.0, (cy - h / 2) * scale_y)
                x2 = min(float(orig_w), (cx + w / 2) * scale_x)
                y2 = min(float(orig_h), (cy + h / 2) * scale_y)
                detections.append({
                    "class_name": CLASSES[class_id] if class_id < len(CLASSES) else f"cls_{class_id}",
                    "confidence": round(confidence, 4),
                    "bbox": [round(x1, 2), round(y1, 2), round(x2, 2), round(y2, 2)],
                })
            # 简单 NMS
            detections = self._nms(detections)
        except Exception as e:
            print(f"[YoloDetector] 解析输出异常: {e}")
        return detections

    def _nms(self, dets: List[Dict]) -> List[Dict]:
        """简易非极大值抑制（按类别分组）"""
        if len(dets) <= 1:
            return dets
        dets = sorted(dets, key=lambda d: d["confidence"], reverse=True)
        keep: List[Dict] = []
        for det in dets:
            if det["confidence"] < self.conf_threshold:
                continue
            is_dup = False
            for k in keep:
                if k["class_name"] != det["class_name"]:
                    continue
                if self._iou(k["bbox"], det["bbox"]) > self.iou_threshold:
                    is_dup = True
                    break
            if not is_dup:
                keep.append(det)
        return keep

    @staticmethod
    def _iou(a: List[float], b: List[float]) -> float:
        ax1, ay1, ax2, ay2 = a
        bx1, by1, bx2, by2 = b
        ix1 = max(ax1, bx1)
        iy1 = max(ay1, by1)
        ix2 = min(ax2, bx2)
        iy2 = min(ay2, by2)
        inter = max(0, ix2 - ix1) * max(0, iy2 - iy1)
        union = (ax2 - ax1) * (ay2 - ay1) + (bx2 - bx1) * (by2 - by1) - inter
        return inter / union if union > 0 else 0.0

    # Mock 降级
    def _run_mock(self) -> Dict[str, Any]:
        """启发式 mock：返回 0-2 个随机检测框，用于无模型环境。"""
        num = random.randint(0, 2)
        dets = []
        for _ in range(num):
            cls = random.choice(CLASSES[:4])
            conf = round(random.uniform(0.4, 0.85), 4)
            x1 = round(random.uniform(0.05, 0.4), 2)
            y1 = round(random.uniform(0.05, 0.4), 2)
            x2 = round(x1 + random.uniform(0.1, 0.35), 2)
            y2 = round(y1 + random.uniform(0.1, 0.35), 2)
            dets.append({"class_name": cls, "confidence": conf, "bbox": [x1, y1, x2, y2]})
        return {
            "detections": dets,
            "model": "heuristic-mock",
            "mock": True,
        }


# 单例
_detector: Optional[YoloDetector] = None


def get_detector() -> YoloDetector:
    global _detector
    if _detector is None:
        _detector = YoloDetector()
    return _detector
