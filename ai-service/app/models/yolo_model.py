"""
YOLO 灾害目标检测模型加载与推理（业务类别：crack / landslide）。

自动检测 models/yolo_crack.onnx 是否存在：
- 存在且输出类别数 == len(CLASSES)：用 onnxruntime 做真实业务推理
- 存在但输出 80 类：判定为 COCO 通用模型占位，加载时明确告警（历史的 yolo_crack.onnx 即属此类）
- 不存在：返回 mock 结果（含随机 bbox），响应里 mock=True，调用方不得当作识别结果

不硬依赖 ultralytics，只在训练脚本中使用。
模型目录可通过环境变量 MODELS_DIR 覆盖。
"""
from __future__ import annotations

import os
from pathlib import Path
from typing import List, Dict, Any, Optional

import numpy as np

from app.config import MODELS_DIR

# COCO 的 80 个原始类别。仅在模型被判定为 COCO 通用模型时用于展示原始类别名，
# 这些名字与灾害业务无关，不能作为灾种结论（见 routers/detection.py 的判定逻辑）。
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

# 业务类别。当前只覆盖数据集真实具备的两类：
#   crack     —— 混凝土/路面裂缝（Roboflow 实例分割集，634 实例）
#   landslide —— 滑坡（Zenodo 尼泊尔 + 意大利人工掩码集，629 实例）
# 早期版本这里写死过 5 类 [crack, rockfall, slump, debris, normal]，但没有任何公开标注数据
# 支撑 rockfall/slump/debris，等于虚报能力，已按 datasets/disaster-v2 的真实覆盖收敛为 2 类。
# 未来补齐对应标注数据后，改这里 + 重新训练导出即可。
CLASSES = ["crack", "landslide"]


def get_class_name(class_id: int, is_business_model: bool = False) -> str:
    """统一的类别名查询：业务模型 → 用 CLASSES；COCO 模型（80类）→ 用 COCO_CLASSES。"""
    if is_business_model:
        return CLASSES[class_id] if 0 <= class_id < len(CLASSES) else f"cls_{class_id}"
    return COCO_CLASSES[class_id] if 0 <= class_id < len(COCO_CLASSES) else f"cls_{class_id}"


def infer_num_classes(model_path: Path) -> int:
    """用 onnxruntime 读输出维度，推断模型类别数（不依赖 onnx 包）。

    YOLOv8 导出后常见输出形状 [1, 4+nc, anchors] 或 [1, anchors, 4+nc]，
    其中取值落在 5..100 的那个维度就是 4+nc。
    """
    try:
        import onnxruntime as ort
        sess = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
        shape = sess.get_outputs()[0].shape
        for d in shape:
            if isinstance(d, int) and 5 <= d <= 100:
                return d - 4
    except Exception:
        pass
    return 0


def detect_business_model(model_path: Path) -> bool:
    """模型输出类别数是否等于业务类别数，且 >0。"""
    return infer_num_classes(model_path) == len(CLASSES)


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
        # 是否为业务模型（输出类别数与 CLASSES 一致）
        self.is_business_model = False
        self.num_classes = 0
        self.model_variant = "none"  # 用于响应里的 model 字段
        self._load()

    def _load(self) -> None:
        """尝试加载 ONNX 模型；不可用时静默降级。"""
        if not self.model_path.exists():
            print(f"[YoloDetector] 未找到 ONNX 模型: {self.model_path}，降级为 mock 推理")
            return
        try:
            import onnxruntime as ort
            providers = ort.get_available_providers()
            provider = "CUDAExecutionProvider" if "CUDAExecutionProvider" in providers else "CPUExecutionProvider"
            self.session = ort.InferenceSession(str(self.model_path), providers=[provider])

            # 用会话自身的输出维度推断类别数：COCO=80，业务模型=len(CLASSES)
            nc = 0
            shape = self.session.get_outputs()[0].shape
            for d in shape:
                if isinstance(d, int) and 5 <= d <= 100:
                    nc = d - 4
                    break
            self.num_classes = nc
            self.is_business_model = (nc == len(CLASSES))
            self.model_loaded = True

            if self.is_business_model:
                self.model_variant = "yolov8n-business"
                print(f"[YoloDetector] 业务模型加载成功: {self.model_path}, provider={provider}, "
                      f"类别数={nc} {CLASSES}")
            elif nc == 80:
                self.model_variant = "yolov8n-coco"
                print(f"[YoloDetector] 警告：{self.model_path.name} 是 COCO 通用模型（80 类），"
                      f"不是灾害检测模型，输出类别与业务无关。请用 datasets/disaster-v2 训练后替换。")
            else:
                self.model_variant = f"unknown-{nc}cls"
                print(f"[YoloDetector] 警告：模型输出 {nc} 类，与业务类别数 {len(CLASSES)} 不一致，"
                      f"类别名可能错位。")
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
                "num_classes": self.num_classes,
            }
        except ImportError:
            print("[YoloDetector] cv2 未安装，降级为 mock")
            return self._run_mock()
        except Exception as e:
            print(f"[YoloDetector] ONNX 推理异常: {e}，降级为 mock")
            return self._run_mock()

    def _parse_yolo_output(self, output: np.ndarray, orig_w: int, orig_h: int) -> List[Dict]:
        """解析 YOLOv8 ONNX 输出。形状为 [1, 4+nc, anchors]，COCO 即 [1, 84, 8400]，
        2 类业务模型即 [1, 6, 8400]，解析逻辑与类别数无关。"""
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
        """无模型时的降级结果：**返回空检测，不编造任何框**。

        历史版本在这里 `random.choice` 生成 0–2 个随机框，前端会把它渲染成"识别到 2 处滑坡"，
        属于伪造识别结果。现在改为明确返回空列表 + mock 标记，前端据此显示"模型未就绪"，
        而不是展示看似真实的检测框。
        """
        return {
            "detections": [],
            "model": "none",
            "mock": True,
            "is_business_model": False,
            "num_classes": len(CLASSES),
            "warning": (
                "未加载业务模型，返回空结果（不生成任何检测框）。"
                "请用 scripts/training/train_crack_yolo.py --data datasets/disaster-v2 "
                "--export-onnx 训练并导出 models/yolo_crack.onnx"
            ),
        }


# 单例
_detector: Optional[YoloDetector] = None


def get_detector() -> YoloDetector:
    global _detector
    if _detector is None:
        _detector = YoloDetector()
    return _detector
