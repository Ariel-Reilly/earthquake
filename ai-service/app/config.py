"""
共享配置模块：从环境变量读取配置，支持通过环境变量覆盖默认值。

主要配置项：
- MODELS_DIR: 模型文件目录（默认 ai-service/models，模块自包含）
- SENTINEL_STORAGE_DIR: Sentinel 合成图存储目录

路径解析策略：
  1. 优先使用环境变量 MODELS_DIR（覆盖最高）
  2. 否则使用 `<ai-service>/models/`（与代码同目录，自包含部署）
"""
from __future__ import annotations

import os
from pathlib import Path

# 模型文件目录：优先环境变量；否则 ai-service/models（自包含）
_MODELS_DIR_ENV = os.environ.get("MODELS_DIR", "")
_SELF_MODELS = Path(__file__).resolve().parent.parent / "models"          # ai-service/models

if _MODELS_DIR_ENV:
    MODELS_DIR = Path(_MODELS_DIR_ENV)
elif _SELF_MODELS.exists():
    MODELS_DIR = _SELF_MODELS
else:
    # 最后兜底：保证目录存在
    MODELS_DIR = _SELF_MODELS
    MODELS_DIR.mkdir(parents=True, exist_ok=True)

# 基线数据目录：用于漂移检测
_BASELINE_DIR_ENV = os.environ.get("BASELINE_DIR", "")
BASELINE_DIR = (
    Path(_BASELINE_DIR_ENV)
    if _BASELINE_DIR_ENV
    else MODELS_DIR / "baseline"
)

# Sentinel 合成图存储目录
_SENTINEL_DIR_ENV = os.environ.get("SENTINEL_STORAGE_DIR", "")
SENTINEL_DIR = (
    Path(_SENTINEL_DIR_ENV)
    if _SENTINEL_DIR_ENV
    else Path(__file__).resolve().parent.parent / "outputs" / "sentinel"
)
