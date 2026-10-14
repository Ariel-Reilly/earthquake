"""
AI 模块自包含运行助手。

所有 scripts/ 下的脚本都应该先 `from _bootstrap import setup_path; setup_path()`
以确保无论从哪个目录运行都能正确 import app.* 模块。

可工作场景：
  - 从 ai-service/ 运行（python scripts/verify_models.py）
  - 从 ai-service/scripts/ 运行（python verify_models.py）
  - 从仓库根目录运行（python ai-service/scripts/verify_models.py）
  - 从任意目录运行（AI_SERVICE_ROOT=/path/to/ai-service python verify_models.py）

设计要点：
  - 通过 __file__ 解析 ai-service 根目录，不依赖当前工作目录
  - 兼容 sys.path.insert(0, '.') 的旧脚本（额外把 cwd 也加进去）
  - 不需要 scripts/ 作为 Python 包（避免 __init__.py 副作用）
"""
from __future__ import annotations

import os
import sys
from pathlib import Path


_AI_ROOT: Path = None  # type: ignore


def setup_path() -> Path:
    """
    把 ai-service 根目录加入 sys.path[0]，返回根目录路径。

    多次调用幂等。

    支持场景：
      - python scripts/verify_models.py  (cwd=ai-service, __file__ in scripts/)
      - python verify_models.py          (cwd=ai-service/scripts)
      - python /full/path/to/verify_models.py (任意 cwd)
      - AI_SERVICE_ROOT=/path python anywhere/verify_models.py
    """
    global _AI_ROOT

    # 0. 把当前脚本所在目录加入 sys.path（支持 python -m ai_service.scripts.xxx）
    here_file = Path(__file__).resolve().parent
    here_str = str(here_file)
    if here_str not in sys.path:
        sys.path.insert(0, here_str)

    # 1. 环境变量优先
    env_root = os.environ.get("AI_SERVICE_ROOT", "").strip()
    if env_root:
        root = Path(env_root).resolve()
    else:
        # 2. 通过 __file__ 推导（核心方案）
        if here_file.name == "scripts":
            # 情况 A：bootstrap.py 在 ai-service/scripts/ 里
            root = here_file.parent
        else:
            # 情况 B：bootstrap.py 放在其他位置——退化用 cwd
            cwd = Path.cwd().resolve()
            if (cwd / "app").is_dir() and (cwd / "scripts").is_dir():
                root = cwd
            elif (cwd.parent / "app").is_dir() and (cwd.parent / "scripts").is_dir():
                root = cwd.parent
            else:
                # 最后兜底
                root = here_file

    # 3. 加进 sys.path（ai-service 根）
    root_str = str(root)
    if root_str not in sys.path:
        sys.path.insert(0, root_str)

    # 4. 把 cwd 也加进去（兼容旧脚本 sys.path.insert(0, '.')）
    cwd_str = str(Path.cwd().resolve())
    if cwd_str not in sys.path:
        sys.path.insert(0, cwd_str)

    _AI_ROOT = root
    return root


def get_ai_root() -> Path:
    """获取 ai-service 根目录（Path 对象）。"""
    if _AI_ROOT is None:
        return setup_path()
    return _AI_ROOT


# 模块加载时立即执行（脚本顶层 import 即生效）
setup_path()