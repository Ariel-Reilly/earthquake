"""
LSTM 时序预测模型：位移/雨量趋势预测。

自动检测 models/lstm_displacement.pt 是否存在：
- 存在：用 PyTorch 加载并做真实推理
- 不存在：返回基于移动平均的启发式预测

模型目录可通过环境变量 MODELS_DIR 覆盖。
"""
from __future__ import annotations

import math
import random
from pathlib import Path
from typing import List, Dict, Any, Optional

import numpy as np

from app.config import MODELS_DIR


class LstmForecaster:
    """LSTM 时序预测封装。"""

    def __init__(self, model_path: Optional[str] = None, input_size: int = 30, hidden_size: int = 64):
        self.input_size = input_size
        self.hidden_size = hidden_size
        self.model_path = Path(model_path) if model_path else (MODELS_DIR / "lstm_displacement.pt")
        self.model = None
        self.model_loaded = False
        self._load()

    def _load(self) -> None:
        if not self.model_path.exists():
            print(f"[LstmForecaster] 未找到模型: {self.model_path}，降级为启发式预测")
            return
        try:
            import torch
            import torch.nn as nn

            # 轻量 LSTM（与训练脚本结构一致）
            class TinyLSTM(nn.Module):
                def __init__(self, input_size, hidden_size, num_layers=1, output_size=1):
                    super().__init__()
                    self.lstm = nn.LSTM(input_size, hidden_size, num_layers, batch_first=True)
                    self.fc = nn.Linear(hidden_size, output_size)

                def forward(self, x):
                    out, _ = self.lstm(x)
                    return self.fc(out[:, -1, :])

            self.model = TinyLSTM(1, self.hidden_size)
            state = torch.load(str(self.model_path), map_location="cpu", weights_only=True)
            self.model.load_state_dict(state)
            self.model.eval()
            self.model_loaded = True
            print(f"[LstmForecaster] 模型加载成功: {self.model_path}")
        except ImportError:
            print("[LstmForecaster] torch 未安装，降级为启发式预测")
        except Exception as e:
            print(f"[LstmForecaster] 模型加载失败: {e}，降级为启发式预测")

    def is_loaded(self) -> bool:
        return self.model_loaded

    def predict(self, history: List[float], horizon: int = 7) -> Dict[str, Any]:
        """对历史序列做未来 N 步预测。"""
        if self.model_loaded and self.model is not None:
            try:
                return self._run_torch(history, horizon)
            except Exception as e:
                print(f"[LstmForecaster] 推理异常: {e}，降级为启发式")
        return self._run_heuristic(history, horizon)

    def _run_torch(self, history: List[float], horizon: int) -> Dict[str, Any]:
        import torch
        arr = np.array(history[-self.input_size:], dtype=np.float32)
        # 归一化（用历史均值/标准差）
        mean = float(np.mean(arr)) if len(arr) > 0 else 0.0
        std = float(np.std(arr)) if len(arr) > 1 else 1.0
        std = std if std > 1e-6 else 1.0
        norm = (arr - mean) / std

        x = torch.tensor(norm, dtype=torch.float32).unsqueeze(0).unsqueeze(-1)
        with torch.no_grad():
            pred = self.model(x).item()

        # 递归滚动预测 horizon 步（简化：直接复用单步预测 + 累积噪声）
        predictions: List[float] = []
        current = norm.copy()
        for i in range(horizon):
            x_cur = torch.tensor(current[-self.input_size:], dtype=torch.float32).unsqueeze(0).unsqueeze(-1)
            with torch.no_grad():
                val = self.model(x_cur).item()
            # 反归一化
            predictions.append(round(val * std + mean, 4))
            current = np.append(current, val)

        return {
            "predictions": predictions,
            "confidence": round(max(0.5, 1.0 - 0.05 * horizon), 4),
            "model": "lstm-displacement",
            "mock": False,
        }

    def _run_heuristic(self, history: List[float], horizon: int) -> Dict[str, Any]:
        """启发式：移动平均 + 线性趋势外推 + 随机抖动。"""
        if not history:
            history = [0.0]
        arr = np.array(history[-min(7, len(history)):])
        if len(arr) >= 2:
            trend = (arr[-1] - arr[0]) / max(1, len(arr) - 1)
        else:
            trend = 0.0
        base = float(np.mean(arr[-3:]))
        predictions: List[float] = []
        for i in range(horizon):
            val = base + trend * (i + 1) + random.uniform(-0.1, 0.1)
            predictions.append(round(val, 4))
        return {
            "predictions": predictions,
            "confidence": round(max(0.3, 0.6 - 0.03 * horizon), 4),
            "model": "heuristic-moving-avg",
            "mock": True,
        }


_forecaster: Optional[LstmForecaster] = None


def get_forecaster() -> LstmForecaster:
    global _forecaster
    if _forecaster is None:
        _forecaster = LstmForecaster()
    return _forecaster
