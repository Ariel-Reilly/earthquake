#!/usr/bin/env python3
"""
LSTM 位移趋势预测模型训练脚本。

输入 CSV 格式的时序数据（至少包含 device_code, metric_code, recorded_at, value 列），
训练轻量 LSTM 做未来 N 步预测。

用法:
    python train_lstm_displacement.py --data ./device_metric.csv --output-dir ./models
    python train_lstm_displacement.py --data ./device_metric.csv --epochs 200 --lr 0.001
"""
from __future__ import annotations

import argparse
import random
import sys
from pathlib import Path

import numpy as np


def load_csv(path: Path, metric_filter: str = "displacement"):
    """加载 CSV，返回 {device_code: [values...]} 字典。"""
    data: dict[str, list[float]] = {}
    try:
        import csv
    except ImportError:
        print("[ERROR] 需要 Python csv 模块")
        sys.exit(1)

    if not path.exists():
        print(f"[WARN] CSV 不存在: {path}，生成合成数据用于演示")
        return synthesize_synthetic()

    with path.open(newline="", encoding="utf-8") as f:
        reader = csv.DictReader(f)
        for row in reader:
            if metric_filter and row.get("metric_code") != metric_filter:
                continue
            device = row.get("device_code", "unknown")
            try:
                val = float(row.get("value", 0))
            except ValueError:
                continue
            data.setdefault(device, []).append(val)

    print(f"[train] 加载 {len(data)} 台设备，metric={metric_filter}")
    return data


def synthesize_synthetic() -> dict[str, list[float]]:
    """生成合成趋势数据用于无真实数据时的演示训练。"""
    data: dict[str, list[float]] = {}
    for i in range(5):
        base = 10.0 + i * 5
        seq: list[float] = []
        for t in range(120):
            trend = 0.02 * t + random.uniform(-0.3, 0.3)
            value = base + trend + random.gauss(0, 0.5)
            seq.append(round(value, 3))
        data[f"DEV-SYN-{i:03d}"] = seq
    print(f"[train] 生成合成数据: {len(data)} 台设备，每台 120 点")
    return data


def build_sequences(series: list[float], input_size: int, horizon: int):
    """构造 (X, Y) 序列对。"""
    X, Y = [], []
    arr = np.array(series, dtype=np.float32)
    # 归一化
    mean, std = arr.mean(), arr.std() + 1e-8
    arr_n = (arr - mean) / std
    for i in range(len(arr_n) - input_size - horizon + 1):
        X.append(arr_n[i:i + input_size])
        Y.append(arr_n[i + input_size:i + input_size + horizon])
    return np.array(X), np.array(Y), mean, std


def train(data: dict[str, list[float]], input_size: int, horizon: int,
          epochs: int, lr: float, hidden_size: int, output_path: Path):
    try:
        import torch
        import torch.nn as nn
        from torch.utils.data import DataLoader, TensorDataset
    except ImportError:
        print("[ERROR] 请先安装 pytorch: pip install torch")
        sys.exit(1)

    # 拼接所有设备序列
    all_x, all_y, mean_, std_ = [], [], 0.0, 1.0
    for device, series in data.items():
        if len(series) < input_size + horizon + 5:
            continue
        x, y, mean_, std_ = build_sequences(series, input_size, horizon)
        if len(x) > 0:
            all_x.append(x)
            all_y.append(y)

    if not all_x:
        print(f"[ERROR] 有效序列不足（需要每台设备至少 {input_size + horizon + 5} 个数据点）")
        sys.exit(1)

    X = np.concatenate(all_x)
    Y = np.concatenate(all_y)
    X_t = torch.tensor(X, dtype=torch.float32).unsqueeze(-1)
    Y_t = torch.tensor(Y, dtype=torch.float32)
    print(f"[train] 数据集: X={X_t.shape}, Y={Y_t.shape}")

    class TinyLSTM(nn.Module):
        def __init__(self, input_size, hidden_size, num_layers=1, output_size=1):
            super().__init__()
            self.lstm = nn.LSTM(input_size, hidden_size, num_layers, batch_first=True)
            self.fc = nn.Linear(hidden_size, output_size)

        def forward(self, x):
            out, _ = self.lstm(x)
            return self.fc(out[:, -1, :])

    model = TinyLSTM(1, hidden_size, output_size=1)
    optimizer = torch.optim.Adam(model.parameters(), lr=lr)
    criterion = nn.MSELoss()

    dataset = TensorDataset(X_t, Y_t[:, 0].unsqueeze(-1))  # 简化：训练单步预测（递归滚动多步）
    loader = DataLoader(dataset, batch_size=32, shuffle=True)

    for epoch in range(epochs):
        total_loss = 0.0
        for xb, yb in loader:
            optimizer.zero_grad()
            pred = model(xb)
            loss = criterion(pred, yb)
            loss.backward()
            optimizer.step()
            total_loss += loss.item() * xb.size(0)
        avg = total_loss / len(dataset)
        if epoch % 20 == 0 or epoch == epochs - 1:
            print(f"[train] epoch {epoch + 1}/{epochs}, loss={avg:.6f}")

    output_path.parent.mkdir(parents=True, exist_ok=True)
    torch.save(model.state_dict(), str(output_path))
    print(f"[train] 模型已保存: {output_path}")

    # 保存归一化参数（便于推理端对齐）
    import json
    meta = {"mean": float(mean_), "std": float(std_), "input_size": input_size, "hidden_size": hidden_size}
    meta_path = output_path.with_suffix(".meta.json")
    meta_path.write_text(json.dumps(meta, indent=2))
    print(f"[train] 元数据已保存: {meta_path}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--data", required=True, help="CSV 文件（device_metric 导出）")
    parser.add_argument("--metric", default="displacement")
    parser.add_argument("--input-size", type=int, default=30)
    parser.add_argument("--horizon", type=int, default=7)
    parser.add_argument("--epochs", type=int, default=100)
    parser.add_argument("--lr", type=float, default=0.001)
    parser.add_argument("--hidden-size", type=int, default=64)
    parser.add_argument("--output-dir", default=str(Path(__file__).resolve().parent.parent / "models"))
    args = parser.parse_args()

    data = load_csv(Path(args.data), args.metric)
    if not data:
        print("[WARN] 无数据，使用合成数据演示训练")
        data = synthesize_synthetic()

    output_path = Path(args.output_dir) / "lstm_displacement.pt"
    train(data, args.input_size, args.horizon, args.epochs, args.lr, args.hidden_size, output_path)


if __name__ == "__main__":
    main()
