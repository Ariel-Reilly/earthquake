#!/bin/bash
# EMQX broker 侧认证初始化 + 启动包装脚本
# 阶段1: 等待 EMQX 就绪后执行一次性配置（用户/ACL）
# 阶段2: 通过 exec 切换为 EMQX 主进程（保持 PID，信号正常传递）

set -e

# R3 P0-3：回退值必须与 docker-compose.yml 中「emqx 服务」和「backend 服务」
# 使用的默认值完全一致。此前本脚本回退 CHANGE_ME_EMQX_PASSWORD，与后端持有的
# CHANGE_ME_emqx_secure_password_2024 不等 → MQTT 鉴权必然失败。
USERNAME="${EMQX_USERNAME:-disasterguard_backend}"
PASSWORD="${EMQX_PASSWORD:-CHANGE_ME_emqx_secure_password_2024}"
MAX_RETRIES=3
RETRY_INTERVAL=5

# ── 阶段1: 等待 broker 就绪 ───────────────────────────────────────────────
echo "[emqx-init] 等待 EMQX 就绪（最多 60s）..."
for i in $(seq 1 30); do
  if emqx ping 2>/dev/null; then
    echo "[emqx-init] ✓ EMQX 已就绪"
    break
  fi
  sleep 2
done

if ! emqx ping 2>/dev/null; then
  echo "[emqx-init] WARNING: EMQX 未在 60s 内就绪，继续尝试初始化..."
fi

# ── 阶段2: 执行认证配置 ────────────────────────────────────────────────────
do_init() {
  if ! emqx ctl authenticator list 2>/dev/null | grep -q built_in_database; then
    echo "[emqx-init] 创建 Built-in Database 认证器..."
    emqx ctl authenticator create \
      -n disasterguard_auth \
      -t built_in_database \
      -c '{"password_hash_algorithm":{"type":"bcrypt","salt_position":"suffix"}}' 2>/dev/null || true
  fi

  if ! emqx ctl authenticator user list -a disasterguard_auth 2>/dev/null | grep -q "$USERNAME"; then
    echo "[emqx-init] 创建 MQTT 用户 $USERNAME ..."
    emqx ctl authenticator user create -a disasterguard_auth \
      -u "$USERNAME" -p "$PASSWORD" 2>/dev/null || true
  else
    echo "[emqx-init] 用户 $USERNAME 已存在，跳过"
  fi

  if ! emqx ctl authorization source list 2>/dev/null | grep -q disasterguard_acl; then
    echo "[emqx-init] 创建 ACL 源..."
    emqx ctl authorization source create \
      -t built_in_database \
      -n disasterguard_acl 2>/dev/null || true
  fi
}

attempt=1
while [ $attempt -le $MAX_RETRIES ]; do
  echo "[emqx-init] 初始化尝试 $attempt/$MAX_RETRIES"
  if do_init 2>&1; then
    echo "[emqx-init] ✓ 完成。Broker 认证用户: $USERNAME"
    break
  fi
  echo "[emqx-init] 尝试 $attempt 失败，重试..."
  sleep $RETRY_INTERVAL
  attempt=$((attempt + 1))
done

# ── 阶段3: 启动 EMQX 主进程 ───────────────────────────────────────────────
# exec 替换当前 bash 为 EMQX，保持 PID=1，SIGTERM 等信号正常传递
# 必须显式传递 EMQX 的启动命令，因为 docker-entrypoint.sh 依赖 "$@"
exec /usr/bin/docker-entrypoint.sh /opt/emqx/bin/emqx foreground
