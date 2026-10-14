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

# YOLOv8n 是在 COCO 数据集上预训练的，所以实际可识别 80 个 COCO 类别。
# 演示场景下，我们把这些原始类别映射到与业务更相关的灾害领域标签。
# 当训练数据为业务专用裂缝数据集（train_crack_yolo.py 流程）时，
# 可重新导出 ONNX 后把这个映射表换成 5 类 [crack, rockfall, slump, debris, normal]。
COCO_CLASSES = [
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
    "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
    "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
    "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
    "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
    "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
    "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
    "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
    "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
    "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
    "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
    "hair drier", "toothbrush",
]

# 业务类别（用于训练后模型的 5 类输出；当前用 ultralytics 预训练权重时仅供占位/文档）
CLASSES = ["crack", "rockfall", "slump", "debris", "normal"]


def get_class_name(class_id: int, is_business_model: bool = False) -> str:
    """统一的类别名查询：业务模型（5类）→ 用 CLASSES；COCO 模型（80类）→ 用 COCO_CLASSES。"""
    if is_business_model:
        return CLASSES[class_id] if 0 <= class_id < len(CLASSES) else f"cls_{class_id}"
    return COCO_CLASSES[class_id] if 0 <= class_id < len(COCO_CLASSES) else f"cls_{class_id}"


def detect_business_model(model_path: Path) -> bool:
    """根据 ONNX 模型的输出维度判断是否为业务 5 类模型：最后一维为 9（4 box + 5 class）。
    COCO 模型最后一维固定为 84（4 + 80）。"""
    try:
        import onnx
        m = onnx.load(str(model_path))
        out_shape = [d.dim_value for d in m.graph.output[0].type.tensor_type.shape.dim]
        # [batch, num_predictions, 4+num_classes]
        if len(out_shape) >= 3 and out_shape[-1] == 9:
            return True
    except Exception:
        pass
    return False


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
        # 是否为业务 5 类模型（由 ONNX 输出维度推断）
        self.is_business_model = False
        self.model_variant = "yolov8n-coco"  # 用于响应里的 model 字段
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
            # 推断模型类型：COCO 80 类 vs 业务 5 类
            self.is_business_model = detect_business_model(self.model_path)
            self.model_variant = "yolov8n-crack" if self.is_business_model else "yolov8n-coco"
            self.model_loaded = True
            print(
                f"[YoloDetector] 模型加载成功: {self.model_path}, provider={provider}, "
                f"variant={self.model_variant} (输出类别数={5 if self.is_business_model else 80})"
            )
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
                "model": self.model_variant,
                "mock": False,
                "is_business_model": self.is_business_model,
                "num_classes": 5 if self.is_business_model else 80,
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
                    "class_id": class_id,
                    "class_name": get_class_name(class_id, self.is_business_model),
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
        """
        启发式 mock：返回 0-2 个随机检测框，用于无模型环境。
        类别名固定使用业务 5 类（crack/rockfall/slump/debris/normal）以兼容前端。
        """
        num = random.randint(0, 2)
        dets = []
        for _ in range(num):
            cls = random.choice(CLASSES[:4])  # 不出现 "normal"
            conf = round(random.uniform(0.4, 0.85), 4)
            x1 = round(random.uniform(0.05, 0.4), 2)
            y1 = round(random.uniform(0.05, 0.4), 2)
            x2 = round(x1 + random.uniform(0.1, 0.35), 2)
            y2 = round(y1 + random.uniform(0.1, 0.35), 2)
            dets.append({
                "class_id": CLASSES.index(cls),
                "class_name": cls,
                "confidence": conf,
                "bbox": [x1, y1, x2, y2],
            })
        return {
            "detections": dets,
            "model": "heuristic-mock-placeholder",
            "mock": True,
            "is_business_model": True,
            "num_classes": 5,
            "warning": "模型未加载，返回启发式 mock 结果；请运行 scripts/download_models.py",
        }


# 单例
_detector: Optional[YoloDetector] = None


def get_detector() -> YoloDetector:
    global _detector
    if _detector is None:
        _detector = YoloDetector()
    return _detector
