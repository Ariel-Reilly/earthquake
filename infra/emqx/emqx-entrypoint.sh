#!/bin/bash
# EMQX 增强 entrypoint：启动 EMQX 主进程后，执行 init.sh 完成 broker 侧认证/授权初始化。
# init.sh 内置 3 次重试；全部失败时本 entrypoint 退出码非零，让容器重启（restart=unless-stopped）。
# 仅首次（或 EMQX data 目录重建后）会创建新用户；重跑幂等安全。
set -e

echo "[emqx-entrypoint] 启动 EMQX 主进程..."
# 后台启动 EMQX（daemon 模式），让容器保持运行
emqx start

echo "[emqx-entrypoint] 等待 EMQX 就绪..."
for i in $(seq 1 30); do
  if emqx ping 2>/dev/null; then
    echo "[emqx-entrypoint] EMQX 已就绪"
    break
  fi
  sleep 2
done

if ! emqx ping; then
  echo "[emqx-entrypoint] ERROR: EMQX 未在预期时间内就绪"
  exit 1
fi

echo "[emqx-entrypoint] 执行 broker 初始化脚本 init.sh ..."
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "${SCRIPT_DIR}/init.sh" ]; then
  # Windows NTFS → Linux 只读挂载时 chmod 会失败，非致命
  chmod +x "${SCRIPT_DIR}/init.sh" 2>/dev/null || true
  # init.sh 内置 3 次重试；失败时阻断容器，让 docker restart 自动重试
  if bash "${SCRIPT_DIR}/init.sh"; then
    echo "[emqx-entrypoint] init.sh 执行成功"
  else
    echo "[emqx-entrypoint] FATAL: init.sh 返回非零，broker 将处于无认证状态"
    echo "[emqx-entrypoint] 容器将退出，由 restart=unless-stopped 触发自动重试"
    exit 1
  fi
else
  echo "[emqx-entrypoint] init.sh 不存在，broker 将以无认证状态运行"
  echo "[emqx-entrypoint] 建议在 infra/emqx/ 目录提供 init.sh 以创建认证用户"
fi

# 容器主循环：保持 emqx 进程在前台（如果后台 daemon 已运行，此处等待）
echo "[emqx-entrypoint] EMQX broker 已就绪，进入前台模式..."
exec emqx foreground
