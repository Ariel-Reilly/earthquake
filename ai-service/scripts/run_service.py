#!/usr/bin/env python3
"""
AI 服务独立启动脚本。

支持从任意目录启动，模型路径自动解析。

用法：
    # 默认端口 8001
    python scripts/run_service.py

    # 自定义端口
    python scripts/run_service.py --port 9001

    # 自定义 host
    python scripts/run_service.py --host 0.0.0.0 --port 8001

    # 不自动重载（生产模式）
    python scripts/run_service.py --no-reload

    # 指定模型目录
    python scripts/run_service.py --models-dir /path/to/models
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

# 统一走 _bootstrap 路径解析（支持任意 cwd 调用）
from _bootstrap import setup_path
_AI_ROOT = setup_path()


def main():
    parser = argparse.ArgumentParser(
        description='DisasterGuard AI Service - standalone launcher',
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
环境变量：
  AI_SERVICE_ROOT    ai-service 根目录（默认从脚本位置自动推导）
  MODELS_DIR         模型文件目录（默认 ai-service/models）
  AI_CORS_ORIGINS    CORS 允许的来源，逗号分隔
  AI_LOG_LEVEL       日志级别（DEBUG/INFO/WARNING/ERROR）

示例：
  python scripts/run_service.py --host 0.0.0.0 --port 8001
  AI_LOG_LEVEL=DEBUG python scripts/run_service.py
        """,
    )
    parser.add_argument('--host', default='127.0.0.1', help='监听地址（默认 127.0.0.1）')
    parser.add_argument('--port', type=int, default=8001, help='监听端口（默认 8001）')
    parser.add_argument('--no-reload', action='store_true', help='禁用代码热重载（生产推荐）')
    parser.add_argument('--workers', type=int, default=1, help='worker 数量（默认 1）')
    parser.add_argument('--log-level', default='info',
                        choices=['critical', 'error', 'warning', 'info', 'debug', 'trace'])
    parser.add_argument('--models-dir', default=None,
                        help='模型文件目录（覆盖 MODELS_DIR 环境变量）')
    parser.add_argument('--check', action='store_true',
                        help='仅检查环境和模型加载，不启动服务')

    args = parser.parse_args()

    # 路径解析（_bootstrap 已把 ai_root 加入 sys.path）
    ai_root = _AI_ROOT

    if args.models_dir:
        import os
        os.environ['MODELS_DIR'] = str(Path(args.models_dir).resolve())

    # 启动模式
    if args.check:
        print('[CHECK] 验证 AI 服务环境...')
        print(f'  ai-service root: {ai_root}')
        try:
            from app.config import MODELS_DIR
            print(f'  MODELS_DIR: {MODELS_DIR}')
            assert MODELS_DIR.exists(), f'MODELS_DIR 不存在: {MODELS_DIR}'
        except Exception as e:
            print(f'  [FAIL] 配置加载失败: {e}')
            sys.exit(1)
        # 验证所有模型
        try:
            from app.models.yolo_model import YoloDetector
            from app.models.xgboost_model import get_fusion_model
            from app.models.lstm_model import get_forecaster
            from app.models.isolation_forest import get_detector
            from app.models.comprehensive_model import get_comprehensive_model

            for name, loader in [
                ('YOLO', YoloDetector),
                ('XGBoost Fusion', get_fusion_model),
                ('LSTM', get_forecaster),
                ('Isolation Forest', get_detector),
                ('Comprehensive', get_comprehensive_model),
            ]:
                m = loader()
                status = 'OK' if (m.is_loaded() if hasattr(m, 'is_loaded') else True) else 'FAIL'
                print(f'  [{status}] {name}')
        except Exception as e:
            print(f'  [FAIL] 模型加载异常: {e}')
            sys.exit(1)
        print()
        print('[OK] 环境检查通过，可以启动服务')
        return

    # 启动 uvicorn
    import uvicorn
    print(f'[START] DisasterGuard AI Service')
    print(f'  host={args.host} port={args.port}')
    print(f'  reload={not args.no_reload} workers={args.workers}')
    print(f'  ai-root={ai_root}')

    uvicorn.run(
        'app.main:app',
        host=args.host,
        port=args.port,
        reload=not args.no_reload,
        workers=args.workers,
        log_level=args.log_level,
    )


if __name__ == '__main__':
    main()