"""配置模块测试：验证环境变量读取正确性。"""
from __future__ import annotations

import os
from pathlib import Path

import pytest


class TestModelsDir:
    """MODELS_DIR 环境变量测试。"""

    def test_default_models_dir_exists(self, monkeypatch):
        """未设置环境变量时使用默认路径。"""
        monkeypatch.delenv("MODELS_DIR", raising=False)
        # 重新导入以应用 monkeypatch
        import importlib
        from app import config

        importlib.reload(config)
        assert isinstance(config.MODELS_DIR, Path)
        assert "models" in str(config.MODELS_DIR)

    def test_custom_models_dir_from_env(self, monkeypatch, tmp_path):
        """环境变量应覆盖默认路径。"""
        custom_dir = tmp_path / "custom_models"
        custom_dir.mkdir()
        monkeypatch.setenv("MODELS_DIR", str(custom_dir))
        # 重新导入以应用 monkeypatch
        import importlib
        from app import config

        importlib.reload(config)
        assert config.MODELS_DIR == custom_dir

    def test_models_dir_with_relative_path(self, monkeypatch):
        """相对路径环境变量也应生效。"""
        monkeypatch.setenv("MODELS_DIR", "models")
        import importlib
        from app import config

        importlib.reload(config)
        assert str(config.MODELS_DIR) == "models"


class TestBaselineDir:
    """BASELINE_DIR 环境变量测试。"""

    def test_default_baseline_dir(self, monkeypatch):
        """未设置时使用 MODELS_DIR/baseline。"""
        monkeypatch.delenv("BASELINE_DIR", raising=False)
        import importlib
        from app import config

        importlib.reload(config)
        assert config.BASELINE_DIR == config.MODELS_DIR / "baseline"

    def test_custom_baseline_dir(self, monkeypatch, tmp_path):
        """环境变量应覆盖默认路径。"""
        custom_dir = tmp_path / "my_baseline"
        monkeypatch.setenv("BASELINE_DIR", str(custom_dir))
        import importlib
        from app import config

        importlib.reload(config)
        assert config.BASELINE_DIR == custom_dir


class TestSentinelDir:
    """SENTINEL_STORAGE_DIR 环境变量测试。"""

    def test_default_sentinel_dir(self, monkeypatch):
        """未设置时使用默认 outputs 路径。"""
        monkeypatch.delenv("SENTINEL_STORAGE_DIR", raising=False)
        import importlib
        from app import config

        importlib.reload(config)
        assert isinstance(config.SENTINEL_DIR, Path)
        assert "sentinel" in str(config.SENTINEL_DIR)
