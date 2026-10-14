#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
DisasterGuard AI 模块独立运行入口。

这是 ai-service 模块的"对外门面"——一行命令就能完成日常运维和测试，
无需记忆 scripts/ 下散落的脚本文件名。

────────────────────────────────────────────────────────────
两种使用模式：

  ① 交互式菜单（默认）
       python ai.py
       python ai.py menu

  ② 直接子命令
       python ai.py status        进程/端口/模型加载状态
       python ai.py check        等价 scripts/run_service.py --check
       python ai.py verify       等价 scripts/verify_models.py
       python ai.py test         等价 scripts/test_models.py
       python ai.py scenarios     等价 scripts/test_disaster_scenarios.py
       python ai.py api          等价 scripts/test_api_endpoints.py（需服务在跑）
       python ai.py models       等价 scripts/download_models.py --check
       python ai.py download     下载/训练缺失模型（--all/--yolo/--lstm/--iforest）
       python ai.py start        启动 FastAPI 服务（后台）
       python ai.py stop         停止后台服务
       python ai.py restart       重启后台服务
       python ai.py logs         tail 服务日志
       python ai.py info         打印 ai-service 配置 & 环境变量

────────────────────────────────────────────────────────────
支持从任何目录运行（通过 _bootstrap 解析 ai-service 根）。

用法：
  python ai-service/ai.py status
  cd ai-service && python ai.py menu
  cd ai-service/scripts && python ../ai.py status

跨机器/容器：
  AI_SERVICE_ROOT=/path/to/ai-service python /anywhere/ai.py status
"""
from __future__ import annotations

import argparse
import json
import os
import signal
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

# Windows 终端默认 GBK，把 stdout 强制改成 UTF-8（不然菜单里的 ✓/✗/═ 等字符无法打印）
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
        sys.stderr.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

# 路径解析（统一走 _bootstrap，支持任意 cwd 调用）
_SCRIPTS_DIR = Path(__file__).resolve().parent / "scripts"
sys.path.insert(0, str(_SCRIPTS_DIR))
from _bootstrap import setup_path  # noqa: E402

AI_ROOT: Path = setup_path()
SCRIPTS_DIR: Path = AI_ROOT / "scripts"
MODELS_DIR: Path = AI_ROOT / "models"
LOGS_DIR: Path = AI_ROOT / "logs"
PID_FILE: Path = LOGS_DIR / "ai-service.pid"
LOG_FILE: Path = LOGS_DIR / "ai-service.log"

LOGS_DIR.mkdir(parents=True, exist_ok=True)

# ───────────────────────────── 颜色 / 终端 ─────────────────────────────

def _supports_color() -> bool:
    if os.environ.get("NO_COLOR"):
        return False
    if os.environ.get("FORCE_COLOR"):
        return True
    return sys.stdout.isatty()


C = _supports_color()

RESET = "\033[0m" if C else ""
BOLD = "\033[1m" if C else ""
DIM = "\033[2m" if C else ""
GREEN = "\033[92m" if C else ""
YELLOW = "\033[93m" if C else ""
RED = "\033[91m" if C else ""
CYAN = "\033[96m" if C else ""
BLUE = "\033[94m" if C else ""


def c(text: str, color: str) -> str:
    return f"{color}{text}{RESET}" if C else text


def info(msg: str) -> None:
    print(f"{c('[INFO]', CYAN)} {msg}")


def ok(msg: str) -> None:
    print(f"{c('[ OK ',  GREEN)}] {msg}")


def warn(msg: str) -> None:
    print(f"{c('[WARN]', YELLOW)} {msg}")


def fail(msg: str) -> None:
    print(f"{c('[FAIL]', RED)} {msg}")


def header(title: str) -> None:
    bar = "═" * max(0, 72 - len(title))
    print()
    print(c(f"══ {title} {bar}", BOLD + CYAN))


# ───────────────────────────── 配置探测 ─────────────────────────────

def _detect_service_url() -> str:
    """FastAPI 默认端口 8001，可被 AI_PORT/AI_HOST 覆盖。"""
    host = os.environ.get("AI_HOST", "127.0.0.1")
    port = int(os.environ.get("AI_PORT", "8001"))
    return f"http://{host}:{port}"


SERVICE_URL: str = _detect_service_url()


def _read_pid() -> int | None:
    if not PID_FILE.exists():
        return None
    try:
        return int(PID_FILE.read_text().strip())
    except (ValueError, OSError):
        return None


def _is_pid_alive(pid: int) -> bool:
    if pid is None:
        return False
    try:
        # Windows / POSIX 通用：尝试发送 0 信号
        os.kill(pid, 0)
        return True
    except (OSError, ProcessLookupError):
        return False


def _port_in_use(host: str, port: int, timeout: float = 0.4) -> bool:
    """检测 host:port 是否有进程监听。"""
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def _http_get_json(path: str, timeout: float = 4.0) -> tuple[int, dict | str]:
    """返回 (status_code, body)。"""
    url = SERVICE_URL + path
    try:
        req = urllib.request.Request(url)
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return r.status, json.loads(r.read())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read())
        except Exception:
            return e.code, str(e)
    except Exception as e:
        return -1, str(e)


# ───────────────────────────── 子命令实现 ─────────────────────────────

def cmd_info(_args: argparse.Namespace) -> int:
    header("AI Service 配置信息")
    print(f"  {c('ai-service 根:', BLUE)}  {AI_ROOT}")
    print(f"  {c('models 目录:', BLUE)}  {MODELS_DIR}")
    print(f"  {c('logs 目录:  ', BLUE)}  {LOGS_DIR}")
    print(f"  {c('服务地址:  ', BLUE)}  {SERVICE_URL}")
    print()
    print(f"  {c('进程 PID:', BLUE)}    {_read_pid() or '(未启动)'}")
    print(f"  {c('端口占用:', BLUE)}    {'是' if _port_in_use('127.0.0.1', int(SERVICE_URL.rsplit(':', 1)[1])) else '否'}")
    print()
    print(f"  {c('环境变量:', BLUE)}")
    for k in ("AI_SERVICE_ROOT", "MODELS_DIR", "AI_HOST", "AI_PORT",
              "AI_LOG_LEVEL", "AI_CORS_ORIGINS", "SENTINEL_STORAGE_DIR"):
        v = os.environ.get(k, "(未设置)")
        print(f"    {k} = {v}")
    print()
    print(f"  {c('模型文件:', BLUE)}")
    for f in ("yolo_crack.onnx", "lstm_displacement.pt",
              "isolation_forest.joblib", "xgboost_fusion.json",
              "comprehensive_v2.json"):
        p = MODELS_DIR / f
        if p.exists():
            size = p.stat().st_size
            size_s = f"{size / 1024 / 1024:.1f} MB" if size > 1024 * 1024 else f"{size / 1024:.0f} KB"
            print(f"    {c(f, GREEN):<32}  OK  {size_s}")
        else:
            print(f"    {c(f, RED):<32}  --  缺失")
    return 0


def _is_pid_alive_simple(pid: int) -> bool:
    """检测 PID 是否仍存在。

    Windows 限制：os.kill(pid, 0) 对子进程 / 跨会话进程偶尔抛 WinError 87。
    在 reload 模式下父进程是 uvicorn reloader，子进程是 worker——用 psutil
    不可靠的环境里直接 fallback 到端口检测。
    """
    try:
        import psutil  # type: ignore
        return psutil.pid_exists(pid)
    except ImportError:
        pass
    try:
        os.kill(pid, 0)
        return True
    except (OSError, ProcessLookupError) as e:
        # WinError 87 等"参数错误"也算不存在（pid 已被回收但句柄还在）
        if getattr(e, "winerror", None) in (87,):
            return False
        return False


def cmd_status(_args: argparse.Namespace) -> int:
    header("AI Service 运行状态")
    pid = _read_pid()
    port = int(SERVICE_URL.rsplit(":", 1)[1])
    alive = _port_in_use("127.0.0.1", port)
    p_alive = _is_pid_alive_simple(pid) if pid else False
    # 端口在 = 服务可用；端口不在但有 pid 文件 = 我们的进程死了
    if alive:
        state_label = c("UP", GREEN)
    else:
        state_label = c("DOWN", RED)

    proc_state = (
        f"pid={pid or '-'} alive" if p_alive
        else (f"pid={pid} dead" if pid else "pid=(none)")
    )
    print(f"  进程:   {proc_state}    服务:   {state_label}  ({SERVICE_URL})")

    # 探针 /api/ai/health
    status, body = _http_get_json("/api/ai/health")
    if status == 200 and isinstance(body, dict):
        print(f"  /api/ai/health -> 200  service={body.get('service')}  status={body.get('status')}")
        unregistered = body.get("unregistered_routers") or {}
        if unregistered:
            print(f"  {c('降级路由:', YELLOW)} {unregistered}")
    elif status == -1:
        print(f"  {c('✗', RED)} /api/ai/health  不可达: {body}")
    else:
        print(f"  {c('?', YELLOW)} /api/ai/health -> HTTP {status}  {body}")

    # 探针 /api/ai/model/info  +  兼容 /api/ai/model-info
    for ep in ("/api/ai/model/info", "/api/ai/model-info"):
        status, body = _http_get_json(ep)
        if status == 200 and isinstance(body, dict):
            print()
            print(f"  {c(ep, BLUE)}")
            models = body.get("models") or []
            # 既要兼容 list[{name, exists_on_disk, ...}, ...]
            # 也要兼容 dict{name: {...}}
            items: list[tuple[str, dict]] = []
            if isinstance(models, list):
                for m in models:
                    if isinstance(m, dict):
                        items.append((str(m.get("name", "?")), m))
            elif isinstance(models, dict):
                for k, v in models.items():
                    items.append((str(k), v if isinstance(v, dict) else {}))
            for name, info_ in items:
                on_disk = info_.get("exists_on_disk")
                loaded = on_disk if on_disk is not None else (
                    info_.get("loaded") or info_.get("status") == "loaded" or info_.get("ok")
                )
                tag = c("OK", GREEN) if loaded else c("--", RED)
                size = info_.get("size_bytes") or 0
                size_s = f"({size // 1024} KB)" if size else ""
                print(f"    {tag:<10} {name:<28}  {size_s}")
            break
        elif status != -1:
            continue
        else:
            print(f"    /api/ai/model/info 不可达: {body}")
            break

    return 0


def cmd_check(_args: argparse.Namespace) -> int:
    """调 scripts/run_service.py --check（等价 fast --check）。"""
    header("环境与模型加载检查")
    return _run_script([sys.executable, str(SCRIPTS_DIR / "run_service.py"), "--check"])


def cmd_verify(_args: argparse.Namespace) -> int:
    header("模型加载与推理验证（verify_models.py）")
    return _run_script([sys.executable, str(SCRIPTS_DIR / "verify_models.py")])


def cmd_test(_args: argparse.Namespace) -> int:
    header("综合模型测试（test_models）")
    return _run_script([sys.executable, str(SCRIPTS_DIR / "test_models.py")])


def cmd_scenarios(_args: argparse.Namespace) -> int:
    header("12 场景端到端测试")
    return _run_script([sys.executable, str(SCRIPTS_DIR / "test_disaster_scenarios.py")])


def cmd_api(_args: argparse.Namespace) -> int:
    header("HTTP API 端点测试")
    return _run_script([sys.executable, str(SCRIPTS_DIR / "test_api_endpoints.py")])


def cmd_models(_args: argparse.Namespace) -> int:
    header("模型文件清单")
    return _run_script([sys.executable, str(SCRIPTS_DIR / "download_models.py"), "--check"])


def cmd_download(args: argparse.Namespace) -> int:
    flags = []
    if args.all:
        flags.append("--all")
    else:
        if args.yolo: flags.append("--yolo")
        if args.lstm: flags.append("--lstm")
        if args.iforest: flags.append("--iforest")
    if not flags:
        flags = ["--all"]  # 默认全跑
    header("下载/训练 AI 模型")
    return _run_script([sys.executable, str(SCRIPTS_DIR / "download_models.py"), *flags])


# ───────────────────────────── 服务启停 ─────────────────────────────

def cmd_start(args: argparse.Namespace) -> int:
    header("启动 AI Service")

    port = int(SERVICE_URL.rsplit(":", 1)[1])
    if _port_in_use("127.0.0.1", port):
        warn(f"端口 {port} 已被占用，假定服务已在运行")
        return 0

    pid = _read_pid()
    if pid and _is_pid_alive_simple(pid):
        warn(f"已有进程 pid={pid} 在运行，先 stop 再 start")
        return 1

    cmd = [
        sys.executable, "-m", "uvicorn",
        "app.main:app",
        "--host", args.host,
        "--port", str(args.port),
        "--log-level", args.log_level,
    ]
    if not args.no_reload:
        cmd.append("--reload")

    log_fp = open(LOG_FILE, "ab", buffering=0)
    info(f"启动命令: {' '.join(cmd)}")
    info(f"日志文件: {LOG_FILE}")

    kwargs = {
        "cwd": str(AI_ROOT),
        "stdout": log_fp,
        "stderr": subprocess.STDOUT,
        "stdin": subprocess.DEVNULL,
    }
    if os.name == "nt":
        # 防止子进程弹出控制台窗口
        kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW
    else:
        kwargs["start_new_session"] = True

    proc = subprocess.Popen(cmd, **kwargs)
    PID_FILE.write_text(str(proc.pid))

    # 等服务起来（最多 10 秒）
    deadline = time.time() + 10
    while time.time() < deadline:
        if _port_in_use("127.0.0.1", args.port):
            ok(f"服务已就绪 pid={proc.pid}  {SERVICE_URL}")
            return 0
        time.sleep(0.3)
    warn("服务进程已启动，但 10 秒内未监听到端口（可能在重载模式延迟）")
    return 0


def cmd_stop(_args: argparse.Namespace) -> int:
    header("停止 AI Service")
    pid = _read_pid()
    if not pid:
        info("未运行")
        return 0
    if not _is_pid_alive_simple(pid):
        warn(f"pid={pid} 已不存在，清理 pid 文件")
        PID_FILE.unlink(missing_ok=True)
        return 0
    try:
        os.kill(pid, signal.SIGTERM)
        # 等 5 秒
        for _ in range(50):
            if not _is_pid_alive_simple(pid):
                break
            time.sleep(0.1)
        else:
            warn("SIGTERM 未退出，发送 SIGKILL")
            try:
                os.kill(pid, signal.SIGKILL)
            except OSError:
                pass
    finally:
        PID_FILE.unlink(missing_ok=True)
    ok(f"已停止 pid={pid}")
    return 0


def cmd_restart(args: argparse.Namespace) -> int:
    cmd_stop(args)
    time.sleep(0.5)
    return cmd_start(args)


def cmd_logs(args: argparse.Namespace) -> int:
    if not LOG_FILE.exists():
        fail(f"日志文件不存在: {LOG_FILE}")
        return 1
    if args.follow:
        info(f"tail -f {LOG_FILE}（Ctrl+C 退出）")
        try:
            with LOG_FILE.open("rb") as f:
                f.seek(0, os.SEEK_END)
                while True:
                    chunk = f.read()
                    if chunk:
                            sys.stdout.buffer.write(chunk)
                            sys.stdout.buffer.flush()
                    time.sleep(0.2)
        except KeyboardInterrupt:
            return 0
    else:
        # 打印最后 N 行
        try:
            n = int(args.lines)
        except ValueError:
            n = 50
        with LOG_FILE.open("r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
        n_total = len(lines)
        tail = lines[-n:] if n_total > n else lines
        print("".join(tail))
    return 0


# ───────────────────────────── 独立 Web UI ─────────────────────────────

WEB_PID_FILE: Path = LOGS_DIR / "ai-web.pid"
WEB_LOG_FILE: Path = LOGS_DIR / "ai-web.log"

WEB_DEFAULT_HOST = os.environ.get("AI_WEB_HOST", "127.0.0.1")
WEB_DEFAULT_PORT = int(os.environ.get("AI_WEB_PORT", "8080"))


def _read_web_pid() -> int | None:
    if not WEB_PID_FILE.exists():
        return None
    try:
        return int(WEB_PID_FILE.read_text().strip())
    except (ValueError, OSError):
        return None


def cmd_web(args: argparse.Namespace) -> int:
    """启动独立 Web UI（端口 8080）。"""
    host = args.host
    port = args.port
    url = f"http://{host}:{port}"

    # 端口占用检查
    if _port_in_use(host, port):
        warn(f"端口 {port} 已被占用，假定 Web UI 已在 {url} 运行")
        info(f"  如需重启: python ai.py web-stop")
        return 0

    pid = _read_web_pid()
    if pid and _is_pid_alive_simple(pid):
        warn(f"已有 Web UI 进程 pid={pid} 在跑，先 web-stop 再启动")
        return 1

    cmd = [sys.executable, str(AI_ROOT / "web.py"),
           "--host", host, "--port", str(port)]
    if args.reload:
        cmd.append("--reload")
    if args.no_browser:
        cmd.append("--no-browser")

    log_fp = open(WEB_LOG_FILE, "ab", buffering=0)
    info(f"启动命令: {' '.join(cmd)}")
    info(f"日志文件: {WEB_LOG_FILE}")
    info(f"UI 地址:  {url}")

    kwargs = {
        "cwd": str(AI_ROOT),
        "stdout": log_fp,
        "stderr": subprocess.STDOUT,
        "stdin": subprocess.DEVNULL,
    }
    if os.name == "nt":
        kwargs["creationflags"] = subprocess.CREATE_NO_WINDOW
    else:
        kwargs["start_new_session"] = True

    proc = subprocess.Popen(cmd, **kwargs)
    WEB_PID_FILE.write_text(str(proc.pid))

    deadline = time.time() + 8
    while time.time() < deadline:
        if _port_in_use(host, port):
            ok(f"Web UI 已就绪 pid={proc.pid}  {url}")
            return 0
        time.sleep(0.3)
    warn("Web UI 进程已启动，但 8 秒内未监听到端口（查看日志）")
    return 0


def cmd_web_stop(_args: argparse.Namespace) -> int:
    """停止 Web UI 进程。"""
    header("停止 Web UI")
    pid = _read_web_pid()
    if not pid:
        info("未运行")
        return 0
    if not _is_pid_alive_simple(pid):
        warn(f"pid={pid} 已不存在，清理 pid 文件")
        WEB_PID_FILE.unlink(missing_ok=True)
        return 0
    try:
        os.kill(pid, signal.SIGTERM)
        for _ in range(50):
            if not _is_pid_alive_simple(pid):
                break
            time.sleep(0.1)
        else:
            warn("SIGTERM 未退出，发送 SIGKILL")
            try:
                os.kill(pid, signal.SIGKILL)
            except OSError:
                pass
    finally:
        WEB_PID_FILE.unlink(missing_ok=True)
    ok(f"已停止 Web UI pid={pid}")
    return 0


def cmd_web_status(_args: argparse.Namespace) -> int:
    """检查 Web UI 状态。"""
    header("Web UI 状态")
    port = WEB_DEFAULT_PORT
    pid = _read_web_pid()
    alive = _port_in_use(WEB_DEFAULT_HOST, port)
    p_alive = _is_pid_alive_simple(pid) if pid else False
    if alive:
        state = c("UP", GREEN)
    else:
        state = c("DOWN", RED)
    proc = (
        f"pid={pid} alive" if p_alive
        else (f"pid={pid} dead" if pid else "pid=(none)")
    )
    url = f"http://{WEB_DEFAULT_HOST}:{port}"
    print(f"  进程:   {proc}    Web UI:   {state}  ({url})")

    # 探针 /_internal/health（直连 web 服务的内部端点）
    try:
        status, body = _http_get_json("/_internal/health", timeout=2.0)
        # 实际是访问 8001 服务，而不是 web；只显示一个标注
    except Exception:
        pass

    # 探针 web 自身的根路径
    try:
        import urllib.request
        with urllib.request.urlopen(url, timeout=2.0) as r:
            print(f"  GET {url} -> HTTP {r.status}  ({len(r.read())} bytes)")
    except Exception as e:
        print(f"  {c('✗', RED)} GET {url} 失败: {e}")
    return 0


# ───────────────────────────── 交互菜单 ─────────────────────────────

MENU_ITEMS: list[tuple[str, str, callable]] = [
    ("1", "查看运行状态 (status)",         cmd_status),
    ("2", "检查环境与模型加载 (check)",    cmd_check),
    ("3", "运行模型验证 verify_models",    cmd_verify),
    ("4", "运行综合模型测试",              cmd_test),
    ("5", "运行 12 场景端到端测试",        cmd_scenarios),
    ("6", "HTTP API 端点测试（需先起服务）",cmd_api),
    ("7", "查看模型文件清单",              cmd_models),
    ("8", "下载/训练缺失模型",             cmd_download),
    ("9", "启动 AI Service (FastAPI 8001)", cmd_start),
    ("10", "停止 AI Service",             cmd_stop),
    ("11", "重启 AI Service",             cmd_restart),
    ("12", "查看 FastAPI 日志",            cmd_logs),
    ("13", "打印配置信息",                cmd_info),
    ("W1", "启动 Web UI (浏览器 8080)",   cmd_web),
    ("W2", "查看 Web UI 状态",            cmd_web_status),
    ("W3", "停止 Web UI",                 cmd_web_stop),
    ("0", "退出",                          None),
]


def cmd_menu(_args: argparse.Namespace) -> int:
    while True:
        header("DisasterGuard AI · 独立运行菜单")
        print(f"  FastAPI 地址: {c(SERVICE_URL, BLUE)}")
        print(f"  Web UI 地址:  {c(f'http://{WEB_DEFAULT_HOST}:{WEB_DEFAULT_PORT}', CYAN)}")
        print(f"  ai 根:        {c(str(AI_ROOT), DIM)}")
        print(f"  api pid:      {_read_pid() or '(未启动)'}")
        print(f"  web pid:      {_read_web_pid() or '(未启动)'}")
        print()
        print(f"  {c('FastAPI 后端 (端口 8001)', YELLOW)}")
        for k, label, _ in MENU_ITEMS:
            if k.startswith("W"): continue
            print(f"      {c(k, CYAN):>4}  {label}")
        print()
        print(f"  {c('独立 Web UI (端口 8080)', YELLOW)}")
        for k, label, _ in MENU_ITEMS:
            if not k.startswith("W"): continue
            print(f"      {c(k, CYAN):>4}  {label}")
        print()
        try:
            choice = input(c("选择 [0-13, W1-W3]: ", BOLD)).strip()
        except (EOFError, KeyboardInterrupt):
            print()
            return 0

        if choice in ("0", "q", "Q", ""):
            return 0

        matched = next(((label, fn) for k, label, fn in MENU_ITEMS if k == choice), None)
        if not matched:
            warn("无效选择")
            continue
        label, fn = matched
        if fn is None:
            return 0
        info(f"执行: {label}")
        # 复用顶层解析过的 argv 不可得，这里给个 Namespace
        ns = argparse.Namespace()
        # 提供给 download 用的常见 flag
        ns.all = False
        ns.yolo = False
        ns.lstm = False
        ns.iforest = False
        # 给 start 用的
        ns.host = os.environ.get("AI_HOST", "127.0.0.1")
        ns.port = int(SERVICE_URL.rsplit(":", 1)[1])
        ns.no_reload = False
        ns.log_level = os.environ.get("AI_LOG_LEVEL", "info").lower()
        # 给 logs 用的
        ns.follow = False
        ns.lines = "50"
        # 给 web 用的
        if fn is cmd_web:
            ns.host = WEB_DEFAULT_HOST
            ns.port = WEB_DEFAULT_PORT
            ns.reload = False
            ns.no_browser = False
        # 给 web_stop / web_status 用的（无参数）

        try:
            rc = fn(ns)
        except KeyboardInterrupt:
            warn("用户中断")
            rc = 130
        if rc:
            warn(f"  退出码: {rc}")
        input(c("\n按回车继续…", DIM))


# ───────────────────────────── 工具 ─────────────────────────────

def _run_script(cmd: list[str]) -> int:
    """同步跑一个脚本，把 stdout/stderr 透传给用户。"""
    info("$ " + " ".join(cmd))
    try:
        return subprocess.call(cmd, cwd=str(AI_ROOT))
    except KeyboardInterrupt:
        return 130


# ───────────────────────────── 参数解析 ─────────────────────────────

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="ai.py",
        description="DisasterGuard AI 模块独立运行入口",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例:
  python ai.py                  交互菜单
  python ai.py status           查看 FastAPI 运行状态
  python ai.py check            环境与模型加载检查
  python ai.py verify           加载验证所有模型
  python ai.py scenarios        12 场景端到端测试
  python ai.py start            启动 FastAPI（后台，端口 8001）
  python ai.py stop             停止 FastAPI
  python ai.py logs -f          tail FastAPI 日志
  python ai.py web              启动独立 Web UI（浏览器，端口 8080）
  python ai.py web-stop         停止 Web UI
  python ai.py download --all   下载/训练所有缺失模型
""",
    )
    p.add_argument("-V", "--version", action="store_true", help="打印版本")

    sub = p.add_subparsers(dest="cmd")

    sub.add_parser("menu", help="交互式菜单（默认）")
    sub.add_parser("status", help="进程/端口/模型加载状态")
    sub.add_parser("check", help="环境与模型加载检查（run_service.py --check）")
    sub.add_parser("verify", help="加载验证所有模型（verify_models）")
    sub.add_parser("test", help="综合模型测试")
    sub.add_parser("scenarios", help="12 场景端到端测试")
    sub.add_parser("api", help="HTTP API 端点测试（需服务在跑）")
    sub.add_parser("models", help="查看模型文件清单")
    sub.add_parser("info", help="打印配置 & 环境变量")

    p_dl = sub.add_parser("download", help="下载/训练缺失模型")
    p_dl.add_argument("--all", action="store_true", help="全部模型")
    p_dl.add_argument("--yolo", action="store_true", help="仅 YOLO")
    p_dl.add_argument("--lstm", action="store_true", help="仅 LSTM")
    p_dl.add_argument("--iforest", action="store_true", help="仅 Isolation Forest")

    p_start = sub.add_parser("start", help="启动 FastAPI 服务（后台）")
    p_start.add_argument("--host", default=os.environ.get("AI_HOST", "127.0.0.1"))
    p_start.add_argument("--port", type=int, default=int(os.environ.get("AI_PORT", "8001")))
    p_start.add_argument("--no-reload", action="store_true")
    p_start.add_argument("--log-level", default=os.environ.get("AI_LOG_LEVEL", "info"))

    sub.add_parser("stop", help="停止后台服务")
    sub.add_parser("restart", help="重启后台服务")

    p_logs = sub.add_parser("logs", help="查看服务日志")
    p_logs.add_argument("-f", "--follow", action="store_true", help="tail -f 模式")
    p_logs.add_argument("-n", "--lines", default="50", help="打印最后 N 行（默认 50）")

    # 独立 Web UI 子命令
    p_web = sub.add_parser("web", help="启动独立 Web UI（浏览器端，端口 8080）")
    p_web.add_argument("--host", default=os.environ.get("AI_WEB_HOST", "127.0.0.1"))
    p_web.add_argument("--port", type=int, default=int(os.environ.get("AI_WEB_PORT", "8080")))
    p_web.add_argument("--reload", action="store_true", help="开发模式自动重载")
    p_web.add_argument("--no-browser", action="store_true", help="启动后不自动打开浏览器")

    sub.add_parser("web-stop", help="停止独立 Web UI")
    sub.add_parser("web-status", help="查看独立 Web UI 状态")

    return p


DISPATCH = {
    "menu": cmd_menu,
    "status": cmd_status,
    "check": cmd_check,
    "verify": cmd_verify,
    "test": cmd_test,
    "scenarios": cmd_scenarios,
    "api": cmd_api,
    "models": cmd_models,
    "info": cmd_info,
    "download": cmd_download,
    "start": cmd_start,
    "stop": cmd_stop,
    "restart": cmd_restart,
    "logs": cmd_logs,
    "web": cmd_web,
    "web-stop": cmd_web_stop,
    "web-status": cmd_web_status,
}


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    if argv is None:
        argv = sys.argv[1:]
    # 无参数默认进 menu
    if not argv:
        return cmd_menu(argparse.Namespace())
    if argv[0] in ("-V", "--version"):
        print("ai-service CLI  v3.0.0")
        return 0
    args = parser.parse_args(argv)
    fn = DISPATCH.get(args.cmd or "menu")
    if fn is None:
        parser.print_help()
        return 0
    return fn(args)


if __name__ == "__main__":
    sys.exit(main())