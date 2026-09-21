"""
共享配置模块：从环境变量读取配置，支持通过环境变量覆盖默认值。

主要配置项：
- MODELS_DIR: 模型文件目录（默认 ai-service/models）
- SENTINEL_STORAGE_DIR: Sentinel 合成图存储目录
"""
from __future__ import annotations

import os
from pathlib import Path

# 模型文件目录：优先使用环境变量，兼容旧代码的相对路径推导
_MODELS_DIR_ENV = os.environ.get("MODELS_DIR", "")
MODELS_DIR = (
    Path(_MODELS_DIR_ENV)
    if _MODELS_DIR_ENV
    else Path(__file__).resolve().parent.parent.parent / "models"
)

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
    else Path(__file__).resolve().parent.parent.parent / "outputs" / "sentinel"
)
