#!/bin/bash
# EMQX 增强 entrypoint：启动 EMQX 主进程后，执行 init.sh 完成 broker 侧认证/授权初始化。
# 仅首次（或 EMQX data 目录重建后）会创建新用户；重跑幂等安全。
set -e

echo "[emqx-entrypoint] 启动 EMQX 主进程..."
# 后台启动 EMQX，让容器保持运行
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
  echo "[emqx-entrypoint] WARNING: EMQX 未在预期时间内就绪，跳过初始化脚本"
else
  echo "[emqx-entrypoint] 执行 broker 初始化脚本 init.sh ..."
  SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  if [ -f "${SCRIPT_DIR}/init.sh" ]; then
    chmod +x "${SCRIPT_DIR}/init.sh"
    bash "${SCRIPT_DIR}/init.sh" || echo "[emqx-entrypoint] init.sh 返回非零（可能已初始化过），继续运行"
  else
    echo "[emqx-entrypoint] init.sh 不存在，跳过"
  fi
fi

# 容器主循环：让 emqx 进程保持前台运行
echo "[emqx-entrypoint] 进入前台主循环..."
exec emqx foreground
