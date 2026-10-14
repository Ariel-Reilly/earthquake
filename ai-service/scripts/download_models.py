#!/usr/bin/env python3
"""
模型下载与训练脚本 —— 将所有 AI 模型下载/训练到 ai-service/models/ 目录。

用法（均在 ai-service/ 目录下运行）：
    python scripts/download_models.py              # 全部下载/训练
    python scripts/download_models.py --yolo       # 仅下载 YOLO
    python scripts/download_models.py --lstm      # 仅训练 LSTM
    python scripts/download_models.py --iforest   # 仅训练 Isolation Forest
    python scripts/download_models.py --check     # 仅检查状态
    python scripts/download_models.py --all       # --yolo + --lstm + --iforest

模型清单：
  yolo_crack.onnx          YOLOv8n 裂缝/落石检测（从 ultralytics 下载并导出）
  lstm_displacement.pt      LSTM 时序预测（用合成数据训练）
  isolation_forest.joblib   Isolation Forest 异常检测（用合成数据训练）

说明：
  YOLO 使用 ultralytics 预训练模型（yolov8n.pt）并导出为 ONNX，
  不做额外微调——演示环境可用，生产场景请用自己的标注数据微调。
  类别固定为 ["crack", "rockfall", "slump", "debris", "normal"]，
  与 app/models/yolo_model.CLASSES 保持一致。
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

# ─────────────────────────────────────────────────────────────────────────────
# 路径设置（统一走 _bootstrap，支持任意 cwd 调用）
# ─────────────────────────────────────────────────────────────────────────────
from _bootstrap import setup_path
AI_ROOT = setup_path()
SCRIPT_DIR = AI_ROOT / "scripts"          # ai-service/scripts/
MODELS_DIR = AI_ROOT / "models"
MODELS_DIR.mkdir(parents=True, exist_ok=True)

# ─────────────────────────────────────────────────────────────────────────────
# 颜色输出
# ─────────────────────────────────────────────────────────────────────────────
GREEN = "\033[92m"
YELLOW = "\033[93m"
RED = "\033[91m"
CYAN = "\033[96m"
BOLD = "\033[1m"
RESET = "\033[0m"


def info(msg: str) -> None:
    print(f"{CYAN}[INFO]{RESET}  {msg}")


def ok(msg: str) -> None:
    print(f"{GREEN}[ OK ]{RESET}  {msg}")


def warn(msg: str) -> None:
    print(f"{YELLOW}[WARN]{RESET}  {msg}")


def fail(msg: str) -> None:
    print(f"{RED}[FAIL]{RESET}  {msg}")


# ─────────────────────────────────────────────────────────────────────────────
# 1. YOLO 模型下载 + ONNX 导出
# ─────────────────────────────────────────────────────────────────────────────
def download_yolo() -> bool:
    """
    从 ultralytics 下载 yolov8n.pt，导出为 ONNX 格式到 models/yolo_crack.onnx。

    ONNX 导出理由：
      - onnxruntime 不依赖 torch，轻量且跨平台
      - onnxruntime 已声明于 requirements.txt
      - yolo_model.py 用 onnxruntime 推理，不依赖 ultralytics
      - ultralytics 仅在脚本中使用，运行时不需要

    下载/导出优先级：
      1. ultralytics 库（需要 numpy>=2 或兼容版本），自动缓存到 ~/.ultralytics/
      2. 直接从 GitHub Releases 下载 ONNX（需要网络可达）
      3. 用 torch.hub 从 ultralytics 加载 pt，再导出 ONNX
    """
    out_path = MODELS_DIR / "yolo_crack.onnx"
    if out_path.exists():
        ok(f"YOLO 模型已存在: {out_path}")
        return True

    info("开始下载 YOLOv8n 模型...")

    # ── 方法 1: 用 ultralytics 库下载 + 导出 ──────────────────────────────
    try:
        from ultralytics import YOLO
        info(f"使用 ultralytics 下载并导出...")
        model = YOLO("yolov8n.pt")  # 自动从 GitHub 下载
        info("导出为 ONNX 格式（opset=12，dynamic axes=True）...")
        export_path = model.export(format="onnx", opset=12, dynamic=True)
        exported = Path(export_path)
        shutil.move(str(exported), str(out_path))
        ok(f"YOLO 模型已导出: {out_path} ({out_path.stat().st_size / 1024 / 1024:.1f} MB)")
        return True
    except ImportError:
        warn("ultralytics 未安装")
    except Exception as e:
        warn(f"ultralytics 方案失败: {e}")

    # ── 方法 2: 直接从 GitHub 下载 ONNX（多版本尝试） ─────────────────────
    ONNX_URLS = [
        ("https://github.com/ultralytics/assets/releases/download/v8.3.0/yolov8n.onnx",
         "ultralytics/assets v8.3.0"),
        ("https://github.com/ultralytics/assets/releases/download/v8.2.103/yolov8n.onnx",
         "ultralytics/assets v8.2.103"),
    ]
    for url, desc in ONNX_URLS:
        info(f"尝试下载 ONNX: {desc}")
        try:
            tmp = MODELS_DIR / "_yolov8n.onnx"
            _download_file(url, str(tmp))
            if tmp.exists() and tmp.stat().st_size > 100_000:
                shutil.move(str(tmp), str(out_path))
                ok(f"YOLO ONNX 已下载: {out_path} ({out_path.stat().st_size / 1024 / 1024:.1f} MB)")
                return True
        except Exception as e:
            warn(f"  下载失败: {e}")

    # ── 方法 3: 用 torch 加载后转为 ONNX ───────────────────────────────────
    warn("尝试备用方案: 使用 torch.hub 下载并转为 ONNX...")
    try:
        import torch
        torch.hub.set_dir(str(MODELS_DIR / "_torch_cache"))
        # 从 ultralytics 官方 hub 加载（trust_repo=True 跳过确认）
        model_torch = torch.hub.load(
            "ultralytics/ultralytics", "yolov8n", pretrained=True, trust_repo=True
        )
        info("转换为 ONNX 格式...")
        dummy_input = torch.randn(1, 3, 640, 640)
        torch.onnx.export(
            model_torch,
            dummy_input,
            str(out_path),
            export_params=True,
            opset_version=12,
            input_names=["images"],
            output_names=["output"],
            dynamic_axes={"images": {0: "batch_size"}, "output": {0: "batch_size"}},
        )
        ok(f"YOLO 模型已转换: {out_path} ({out_path.stat().st_size / 1024 / 1024:.1f} MB)")
        return True
    except ImportError:
        fail("torch 也未安装，无法获取 YOLO 模型")
    except Exception as e:
        fail(f"YOLO 转换失败: {e}")

    return False


def _download_file(url: str, dest: str, chunk: int = 8192) -> None:
    """带进度条的文件下载。"""
    req = urllib.request.Request(url, headers={"User-Agent": "DisasterGuard/1.0"})
    with urllib.request.urlopen(req, timeout=120) as resp:
        total = int(resp.headers.get("Content-Length", 0))
        path = Path(dest)
        path.parent.mkdir(parents=True, exist_ok=True)
        downloaded = 0
        with open(path, "wb") as f:
            while True:
                chunk_data = resp.read(chunk)
                if not chunk_data:
                    break
                f.write(chunk_data)
                downloaded += len(chunk_data)
                if total > 0:
                    pct = downloaded * 100 // total
                    print(f"\r  下载进度: {pct}% ({downloaded//1024}KB/{total//1024}KB)", end="", flush=True)
        if total > 0:
            print()


# ─────────────────────────────────────────────────────────────────────────────
# 2. LSTM 模型训练
# ─────────────────────────────────────────────────────────────────────────────
def train_lstm() -> bool:
    """
    用合成时间序列数据训练 LSTM 位移预测模型，保存为 lstm_displacement.pt。

    训练数据：
      - 合成 1000 条位移序列（长度 30，模拟 30 天历史）
      - 每条序列对应 7 天未来预测
      - 序列模式：基线位移 + 正弦波动 + 随机噪声 + 趋势突变（模拟滑坡加速）
    """
    out_path = MODELS_DIR / "lstm_displacement.pt"
    if out_path.exists():
        ok(f"LSTM 模型已存在: {out_path}")
        return True

    info("开始训练 LSTM 位移预测模型...")

    try:
        import numpy as np
        import torch
        import torch.nn as nn
    except ImportError as e:
        fail(f"缺少依赖: {e}")
        return False

    torch.manual_seed(42)
    np.random.seed(42)

    # ── 合成数据生成 ────────────────────────────────────────────────────────
    INPUT_SIZE = 30    # 历史天数
    HIDDEN_SIZE = 64
    NUM_LAYERS = 1
    OUTPUT_STEPS = 7   # 预测 7 天

    def _gen_sequence(seed: int) -> np.ndarray:
        """生成一条合成位移序列。"""
        rng = np.random.RandomState(seed)
        seq_len = INPUT_SIZE + OUTPUT_STEPS
        t = np.arange(seq_len)

        # 基线：某坡体的"正常"日均位移速率
        base_rate = rng.uniform(0.05, 0.3)      # mm/day
        # 季节波动（模拟雨季/旱季）
        seasonal = 0.15 * np.sin(2 * np.pi * t / 365 + rng.uniform(0, 6))
        # 随机噪声
        noise = rng.normal(0, 0.08, seq_len)
        # 趋势：偶尔注入"加速位移"（模拟滑坡前兆）
        trend = np.zeros(seq_len)
        if rng.random() < 0.2:  # 20% 概率出现加速
            # 注入从 INPUT_SIZE 位置开始的位移跳变
            trend[INPUT_SIZE:] = np.linspace(0, rng.uniform(0.5, 2.0), OUTPUT_STEPS)

        displacement = base_rate * t + seasonal + noise + trend
        return displacement.astype(np.float32)

    # 生成 1000 条序列
    n_samples = 1000
    all_seqs = np.stack([_gen_sequence(i) for i in range(n_samples)])

    # 归一化参数
    global_mean = float(all_seqs.mean())
    global_std = float(all_seqs.std()) if all_seqs.std() > 1e-6 else 1.0

    # 保存归一化参数
    norm_meta = MODELS_DIR / "lstm_displacement.norm.json"
    norm_meta.write_text(json.dumps({
        "mean": global_mean, "std": global_std,
        "input_size": INPUT_SIZE, "output_steps": OUTPUT_STEPS,
    }))
    info(f"归一化参数: mean={global_mean:.4f} std={global_std:.4f}")

    # 训练集 / 验证集
    val_size = 200
    train_seqs = all_seqs[:-val_size]
    val_seqs = all_seqs[-val_size:]

    # ── 模型定义（与 lstm_model.py 的 TinyLSTM 保持一致） ──────────────────
    class TinyLSTM(nn.Module):
        def __init__(self, input_size: int, hidden_size: int, num_layers: int = 1, output_size: int = 1):
            super().__init__()
            self.lstm = nn.LSTM(input_size, hidden_size, num_layers, batch_first=True)
            self.fc = nn.Linear(hidden_size, output_size)

        def forward(self, x):
            out, _ = self.lstm(x)
            return self.fc(out[:, -1, :])

    model = TinyLSTM(1, HIDDEN_SIZE, NUM_LAYERS, OUTPUT_STEPS)

    # ── 训练 ───────────────────────────────────────────────────────────────
    criterion = nn.MSELoss()
    optimizer = torch.optim.Adam(model.parameters(), lr=0.005)

    batch_size = 32
    epochs = 60

    for epoch in range(1, epochs + 1):
        model.train()
        indices = np.random.permutation(len(train_seqs))
        total_loss = 0.0

        for i in range(0, len(indices), batch_size):
            batch_idx = indices[i:i + batch_size]
            batch_raw = train_seqs[batch_idx]  # (batch, seq_len)

            # 归一化
            batch_norm = (batch_raw - global_mean) / global_std

            # 构造 LSTM 输入: (batch, seq_len, 1)
            x = torch.from_numpy(batch_norm[:, :INPUT_SIZE]).unsqueeze(-1)
            # 目标: (batch, OUTPUT_STEPS)
            y = torch.from_numpy(
                (batch_raw[:, INPUT_SIZE:] - global_mean) / global_std
            ).float()

            pred = model(x)  # (batch, OUTPUT_STEPS)
            loss = criterion(pred, y)

            optimizer.zero_grad()
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            total_loss += loss.item()

        avg_loss = total_loss / (len(indices) / batch_size)

        # 验证
        if epoch % 10 == 0 or epoch == epochs:
            model.eval()
            with torch.no_grad():
                val_raw = val_seqs
                val_norm = (val_raw - global_mean) / global_std
                val_x = torch.from_numpy(val_norm[:, :INPUT_SIZE]).unsqueeze(-1)
                val_y = torch.from_numpy(
                    (val_raw[:, INPUT_SIZE:] - global_mean) / global_std
                ).float()
                val_pred = model(val_x)
                val_loss = criterion(val_pred, val_y).item()
            info(
                f"  Epoch {epoch:3d}/{epochs}  train_loss={avg_loss:.4f}  val_loss={val_loss:.4f}"
            )

    # 保存模型（与 lstm_model.py 的加载逻辑一致）
    torch.save(model.state_dict(), str(out_path))
    ok(f"LSTM 模型已保存: {out_path} ({out_path.stat().st_size / 1024:.0f} KB)")

    # 保存 metadata
    meta_path = MODELS_DIR / "lstm_displacement.meta.json"
    meta_path.write_text(json.dumps({
        "version": "1.0.0",
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "n_samples": n_samples,
        "val_samples": val_size,
        "input_size": INPUT_SIZE,
        "hidden_size": HIDDEN_SIZE,
        "output_steps": OUTPUT_STEPS,
        "epochs": epochs,
        "norm_mean": global_mean,
        "norm_std": global_std,
    }))
    return True


# ─────────────────────────────────────────────────────────────────────────────
# 3. Isolation Forest 模型训练
# ─────────────────────────────────────────────────────────────────────────────
def train_isolation_forest() -> bool:
    """
    用合成传感器数据训练 Isolation Forest 模型，保存为 isolation_forest.joblib。

    训练数据：
      - 合成 2000 条多维传感器记录
      - 特征: 位移速率 / 裂缝宽度 / 倾斜角 / 雨量 / 振动频率
      - 10% 异常注入（模拟设备故障或突发位移）
    """
    out_path = MODELS_DIR / "isolation_forest.joblib"
    if out_path.exists():
        ok(f"Isolation Forest 模型已存在: {out_path}")
        return True

    info("开始训练 Isolation Forest 异常检测模型...")

    try:
        import numpy as np
        from sklearn.ensemble import IsolationForest
        import joblib
    except ImportError as e:
        fail(f"缺少依赖: {e}")
        return False

    np.random.seed(42)

    # ── 合成数据生成 ────────────────────────────────────────────────────────
    # 正常样本特征分布
    n_normal = 1800
    n_anomaly = 200

    # 正常样本: 各特征按经验分布采样
    normal_data = np.column_stack([
        np.random.lognormal(mean=0.2, sigma=0.5, size=n_normal),   # 位移速率 mm/day
        np.random.lognormal(mean=0.5, sigma=0.6, size=n_normal),   # 裂缝宽度 mm
        np.random.normal(loc=0.5, scale=0.3, size=n_normal),        # 倾斜角 °
        np.random.gamma(shape=2, scale=5, size=n_normal),            # 雨量 mm
        np.random.exponential(scale=2.0, size=n_normal),              # 振动频率 Hz
    ]).clip(0)  # 确保非负

    # 异常样本: 与正常分布显著偏离
    anomaly_data = np.column_stack([
        np.random.uniform(8.0, 20.0, size=n_anomaly),               # 极端位移
        np.random.uniform(20.0, 50.0, size=n_anomaly),               # 极端裂缝
        np.random.uniform(5.0, 15.0, size=n_anomaly),                 # 极端倾斜
        np.random.uniform(50.0, 200.0, size=n_anomaly),                # 极端雨量
        np.random.uniform(15.0, 50.0, size=n_anomaly),                # 极端振动
    ])

    # 合并
    X = np.vstack([normal_data, anomaly_data]).astype(np.float64)
    y_true = np.array([0] * n_normal + [1] * n_anomaly)  # 0=正常, 1=异常

    # 打乱
    perm = np.random.permutation(len(X))
    X, y_true = X[perm], y_true[perm]

    info(f"训练数据: {len(X)} 条（正常={n_normal} 异常={n_anomaly}）")

    # ── 训练 ───────────────────────────────────────────────────────────────
    # contamination=0.1 与 anomaly_data 比例一致
    clf = IsolationForest(
        n_estimators=200,
        contamination=0.1,
        random_state=42,
        n_jobs=-1,
    )
    clf.fit(X)

    # 评估
    from sklearn.metrics import classification_report, f1_score
    y_pred = clf.predict(X)
    # sklearn: 1=正常, -1=异常 → 转为 0=正常, 1=异常
    y_pred_binary = (y_pred == -1).astype(int)
    f1 = f1_score(y_true, y_pred_binary, average="binary")
    info(f"训练集 F1-score: {f1:.4f}")

    # 保存模型
    joblib.dump(clf, str(out_path))
    ok(f"Isolation Forest 模型已保存: {out_path} ({out_path.stat().st_size / 1024:.0f} KB)")

    # 保存 metadata
    FEATURE_NAMES = [
        "displacement_rate", "crack_width", "tilt_angle", "rainfall", "vibration_freq"
    ]
    meta_path = MODELS_DIR / "isolation_forest.meta.json"
    meta_path.write_text(json.dumps({
        "version": "1.0.0",
        "trained_at": datetime.now(timezone.utc).isoformat(),
        "n_samples": len(X),
        "n_normal": int(n_normal),
        "n_anomaly": int(n_anomaly),
        "contamination": 0.1,
        "n_estimators": 200,
        "f1_score": round(f1, 4),
        "feature_names": FEATURE_NAMES,
    }))
    return True


# ─────────────────────────────────────────────────────────────────────────────
# 4. 状态检查
# ─────────────────────────────────────────────────────────────────────────────
def check_models() -> None:
    """检查所有模型文件是否存在及大小。"""
    models = [
        ("yolo_crack.onnx", "YOLO 裂缝/落石检测"),
        ("lstm_displacement.pt", "LSTM 位移预测"),
        ("isolation_forest.joblib", "Isolation Forest 异常检测"),
        ("xgboost_fusion.json", "XGBoost 融合预警"),
        ("comprehensive_v2.json", "综合风险模型"),
    ]

    print(f"\n{BOLD}模型文件状态{RESET}")
    print(f"{'模型文件':<30} {'状态':<8} {'大小':>12}  说明")
    print("-" * 80)

    all_ok = True
    for fname, desc in models:
        p = MODELS_DIR / fname
        if p.exists():
            size = p.stat().st_size
            if size > 1024 * 1024:
                size_str = f"{size / 1024 / 1024:.1f} MB"
            else:
                size_str = f"{size / 1024:.0f} KB"
            print(f"  {fname:<28} {GREEN}已存在{RESET}  {size_str:>10}  {desc}")
        else:
            print(f"  {fname:<28} {RED}不存在{RESET}  {'—':>12}  {desc}")
            all_ok = False

    print()
    if all_ok:
        ok("所有模型已就位，AI 服务将使用真实推理！")
    else:
        warn("部分模型缺失，缺失项将使用 mock 降级")
    print()


# ─────────────────────────────────────────────────────────────────────────────
# 主入口
# ─────────────────────────────────────────────────────────────────────────────
def main() -> int:
    parser = argparse.ArgumentParser(
        description="下载/训练 DisasterGuard AI 模型",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__,
    )
    parser.add_argument("--yolo", action="store_true", help="下载 YOLO 模型")
    parser.add_argument("--lstm", action="store_true", help="训练 LSTM 模型")
    parser.add_argument("--iforest", action="store_true", help="训练 Isolation Forest 模型")
    parser.add_argument("--check", action="store_true", help="仅检查模型状态")
    parser.add_argument("--all", action="store_true", help="下载/训练全部模型")
    args = parser.parse_args()

    # 无参数时显示帮助
    if len(sys.argv) == 1:
        parser.print_help()
        print()
        check_models()
        return 0

    print(f"\n{BOLD}DisasterGuard 模型下载工具{RESET}")
    print(f"模型目录: {MODELS_DIR}\n")

    success = True

    if args.check:
        check_models()
        return 0

    if args.all or args.yolo:
        print(f"\n{BOLD}─── 1. YOLO 裂缝/落石检测模型 ───{RESET}")
        if not download_yolo():
            success = False

    if args.all or args.lstm:
        print(f"\n{BOLD}─── 2. LSTM 位移预测模型 ───{RESET}")
        if not train_lstm():
            success = False

    if args.all or args.iforest:
        print(f"\n{BOLD}─── 3. Isolation Forest 异常检测模型 ───{RESET}")
        if not train_isolation_forest():
            success = False

    print()
    if success:
        ok("全部完成！")
        check_models()
        return 0
    else:
        fail("部分模型处理失败，请检查错误信息")
        return 1


if __name__ == "__main__":
    sys.exit(main())
